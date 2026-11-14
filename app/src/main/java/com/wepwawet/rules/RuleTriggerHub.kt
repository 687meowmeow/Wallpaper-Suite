package com.wepwawet.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.wepwawet.WepWawetApplication
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.rules.RuleModel.Trigger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * v0.9.0: THE TRIGGER HUB — every event source, aggregated.
 *
 * Battery-first shape (per the v0.9.0 research):
 *  • ONE dynamically-registered receiver for the system broadcasts (implicit
 *    broadcasts can't be manifest-registered since API 26) — registered only
 *    for the trigger types some rule actually uses, re-aggregated whenever
 *    the rules change;
 *  • a ConnectivityManager.NetworkCallback for Wi-Fi (far cheaper than
 *    polling or the legacy CONNECTIVITY_ACTION broadcast);
 *  • a BluetoothProfile service listener for chosen-device triggers;
 *  • a DisplayListener for orientation;
 *  • battery LEVEL conditions read BatteryManager's sticky intent on demand
 *    (zero receivers); the "drops below / rises above" triggers edge-detect
 *    on the raw battery broadcasts (registered only when used);
 *  • time triggers (window / time-of-day / day / date / interval) use ONE
 *    computed wake — the next activation moment across every used time
 *    trigger — posted with a 30-minute self-heal cap, exactly like the
 *    service's rotation tick rework. No polling anywhere.
 *
 * The hub lives as long as the pairing foreground service (the always-alive
 * process root) and is also (re)started from the Application and
 * BootReceiver paths.
 */
object RuleTriggerHub {

    private const val TAG = "RuleTriggerHub"
    /** WallPaperManager.ACTION_WALLPAPER_CHANGED's literal — the SDK constant
     *  was removed in API 34, the broadcast itself still fires. */
    private const val ACTION_WALLPAPER_CHANGED =
        "android.service.wallpaper.WALLPAPER_CHANGED"
    private const val MAX_WAKE_MS = 30 * 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var registeredTypes: Set<String> = emptySet()

    // The single aggregated receiver for every broadcast-based trigger.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> dispatch(ctx, RuleModel.TRIG_SCREEN_ON)
                Intent.ACTION_SCREEN_OFF -> dispatch(ctx, RuleModel.TRIG_SCREEN_OFF)
                Intent.ACTION_USER_PRESENT -> dispatch(ctx, RuleModel.TRIG_UNLOCKED)
                Intent.ACTION_POWER_CONNECTED -> dispatch(ctx, RuleModel.TRIG_CHARGER_IN)
                Intent.ACTION_POWER_DISCONNECTED -> dispatch(ctx, RuleModel.TRIG_CHARGER_OUT)
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val on = (ctx.getSystemService(Context.POWER_SERVICE)
                        as? android.os.PowerManager)?.isPowerSaveMode == true
                    dispatch(ctx, if (on) RuleModel.TRIG_SAVER_ON else RuleModel.TRIG_SAVER_OFF)
                }
                // The system wallpaper changed (also fires for OUR applies —
                // the engine posts a deduplicated internal event too).
                // The SDK constant was removed in API 34+; the action still
                // reaches dynamic receivers by its literal string.
                ACTION_WALLPAPER_CHANGED ->
                    dispatch(ctx, RuleModel.TRIG_WALLPAPER_CHANGED)
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra("level", -1)
                    val scale = intent.getIntExtra("scale", 100)
                    if (level < 0) return
                    val pct = level * 100 / scale.coerceAtLeast(1)
                    val prev = lastBatteryPct
                    lastBatteryPct = pct
                    if (prev >= 0) {
                        // Edge-detected threshold events; the engine matches
                        // the crossing against every rule's own level.
                        dispatch(ctx, "batteryChanged",
                            mapOf("level" to pct.toString(), "prev" to prev.toString()))
                    }
                }
            }
        }
    }

    @Volatile
    private var lastBatteryPct = -1

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null
    private var orientationListener: android.hardware.display.DisplayManager.DisplayListener? =
        null
    private var lastOrientation = -1

    private val wakeRunnable = object : Runnable {
        override fun run() {
            fireDueTimeTriggers()
            scheduleNextWake()
        }
    }

    /** Start (or re-aggregate) the hub for the trigger types rules use. */
    fun start(context: Context) {
        val types = try {
            SuiteRulesStore.usedTriggerTypes(context)
        } catch (e: Exception) {
            emptySet()
        }
        if (!started) {
            started = true
            lastBatteryPct = RuleEngine.batteryLevel(context)
            lastOrientation = context.resources.configuration.orientation
            WepWawetLogger.appInfo("$TAG: hub started", "types" to types.size)
        }
        if (types == registeredTypes) return
        // Re-aggregate: unregister everything, register only what's used.
        reaggregate(context, types)
        scheduleNextWake()
    }

    private fun reaggregate(context: Context, types: Set<String>) {
        val appCtx = context.applicationContext
        try {
            appCtx.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // not registered — fine
        }
        val actions = HashSet<String>()
        if (RuleModel.TRIG_SCREEN_ON in types) actions.add(Intent.ACTION_SCREEN_ON)
        if (RuleModel.TRIG_SCREEN_OFF in types) actions.add(Intent.ACTION_SCREEN_OFF)
        if (RuleModel.TRIG_UNLOCKED in types) actions.add(Intent.ACTION_USER_PRESENT)
        if (RuleModel.TRIG_CHARGER_IN in types) actions.add(Intent.ACTION_POWER_CONNECTED)
        if (RuleModel.TRIG_CHARGER_OUT in types) actions.add(Intent.ACTION_POWER_DISCONNECTED)
        if (RuleModel.TRIG_SAVER_ON in types || RuleModel.TRIG_SAVER_OFF in types) {
            actions.add(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (RuleModel.TRIG_WALLPAPER_CHANGED in types) {
            actions.add(ACTION_WALLPAPER_CHANGED)
        }
        if (RuleModel.TRIG_BATTERY_BELOW in types || RuleModel.TRIG_BATTERY_ABOVE in types) {
            actions.add(Intent.ACTION_BATTERY_CHANGED)
        }
        if (actions.isNotEmpty()) {
            val filter = IntentFilter()
            for (a in actions) filter.addAction(a)
            try {
                appCtx.registerReceiver(receiver, filter)
            } catch (e: Exception) {
                WepWawetLogger.appWarn("$TAG: receiver registration failed",
                    "exception" to e.javaClass.simpleName)
            }
        }

        // Wi-Fi: a NetworkCallback (any or a chosen SSID).
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (networkCallback != null) {
            try {
                val cb = networkCallback
                if (cb != null) cm?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                WepWawetLogger.appDebug("Cleanup: wifi callback unregister failed",
                    "exception" to e.javaClass.simpleName)
            }
            networkCallback = null
        }
        if (RuleModel.TRIG_WIFI_CONNECT in types || RuleModel.TRIG_WIFI_DISCONNECT in types) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm?.getNetworkCapabilities(network)
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        val ssid = RuleEngine.currentSsid(appCtx)
                        lastWifiSsid = ssid
                        dispatch(appCtx, "wifiConnected", mapOf("ssid" to ssid))
                    }
                }

                override fun onLost(network: Network) {
                    val caps = cm?.getNetworkCapabilities(network)
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        dispatch(appCtx, "wifiDisconnected", mapOf("ssid" to lastWifiSsid))
                    }
                }
            }
            try {
                cm?.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build(), cb)
                networkCallback = cb
            } catch (e: Exception) {
                WepWawetLogger.appWarn("$TAG: wifi callback registration failed",
                    "exception" to e.javaClass.simpleName)
            }
        }

        // Headphones + Bluetooth audio devices: ONE AudioDeviceCallback —
        // added/removed audio devices cover wired, USB, and BT audio, with
        // the device name for the chosen-device triggers. (The
        // BluetoothProfile.Callback route does not exist as public API.)
        if (audioDeviceCallback != null) {
            try {
                (appCtx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
                    ?.let { am ->
                        val cb = audioDeviceCallback
                        if (cb != null) am.unregisterAudioDeviceCallback(cb)
                    }
            } catch (e: Exception) {
                WepWawetLogger.appDebug("Cleanup: audio callback unregister failed",
                    "exception" to e.javaClass.simpleName)
            }
            audioDeviceCallback = null
        }
        if (RuleModel.TRIG_HEADPHONES_IN in types || RuleModel.TRIG_HEADPHONES_OUT in types ||
            RuleModel.TRIG_BT_DEVICE_IN in types || RuleModel.TRIG_BT_DEVICE_OUT in types
        ) {
            val callback = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) {
                    for (d in added) classifyAudioDevice(appCtx, d, connected = true)
                }

                override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) {
                    for (d in removed) classifyAudioDevice(appCtx, d, connected = false)
                }
            }
            try {
                (appCtx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
                    ?.registerAudioDeviceCallback(callback, handler)
                audioDeviceCallback = callback
            } catch (e: Exception) {
                WepWawetLogger.appWarn("$TAG: audio device callback failed",
                    "exception" to e.javaClass.simpleName)
            }
        }

        // Orientation: a DisplayListener.
        val dm = appCtx.getSystemService(Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager
        if (orientationListener != null) {
            try {
                dm?.unregisterDisplayListener(orientationListener)
            } catch (e: Exception) {
                WepWawetLogger.appDebug("Cleanup: display listener unregister failed",
                    "exception" to e.javaClass.simpleName)
            }
            orientationListener = null
        }
        if (RuleModel.TRIG_ORIENTATION in types) {
            val listener = object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    val o = appCtx.resources.configuration.orientation
                    if (o != lastOrientation) {
                        lastOrientation = o
                        dispatch(appCtx, RuleModel.TRIG_ORIENTATION,
                            mapOf("orientation" to
                                if (o == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                                    "portrait" else "landscape"))
                    }
                }
            }
            try {
                dm?.registerDisplayListener(listener, handler)
                orientationListener = listener
            } catch (e: Exception) {
                WepWawetLogger.appWarn("$TAG: display listener failed",
                    "exception" to e.javaClass.simpleName)
            }
        }

        registeredTypes = types
        WepWawetLogger.appInfo("$TAG: trigger registrations aggregated",
            "types" to types.size,
            "broadcasts" to actions.size,
            "wifi" to (networkCallback != null),
            "audio" to (audioDeviceCallback != null),
            "orientation" to (orientationListener != null))
    }

    private var lastWifiSsid: String = ""

    /** Classify one audio device event into headphone / BT-device events. */
    private fun classifyAudioDevice(ctx: Context, d: android.media.AudioDeviceInfo, connected: Boolean) {
        val bt = d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            d.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val name = try { d.productName?.toString() ?: "" } catch (e: Exception) { "" }
        if (bt) {
            dispatch(ctx,
                if (connected) "btConnected" else "btDisconnected",
                mapOf("device" to name))
        }
        // Every audio output counts as "headphones" (the condition mirrors
        // RuleEngine.areHeadphonesOn).
        dispatch(ctx,
            if (connected) "headphonesConnected" else "headphonesDisconnected",
            mapOf("device" to name))
    }

    /** Rules changed — re-aggregate the registrations. */
    fun onRulesChanged(context: Context) {
        if (started) {
            handler.post { start(context) }
        }
    }

    // ── The time-trigger wake schedule ──────────────────────────────────

    /**
     * The next activation moment across every used time trigger — the next
     * window start, time-of-day, day-of-week midnight, date midnight, or
     * interval tick. Null when nothing schedules.
     */
    fun nextTimeActivation(context: Context, now: Long): Long? {
        var best: Long? = null
        val used = try {
            SuiteRulesStore.readAll(context)
        } catch (e: Exception) {
            return null
        }
        for (pr in used) {
            for (rule in pr.rules) {
                if (!rule.enabled) continue
                for (t in rule.triggers) {
                    val at = nextActivationOf(t, now) ?: continue
                    if (best == null || at < best) best = at
                }
            }
        }
        return best
    }

    private fun nextActivationOf(t: Trigger, now: Long): Long? = when (t.type) {
        RuleModel.TRIG_TIME_WINDOW -> {
            if (t.minuteStart == t.minuteEnd) null
            else nextMinuteOfDay(t.minuteStart, now)
        }
        RuleModel.TRIG_TIME_OF_DAY -> nextMinuteOfDay(t.timeMinute, now)
        RuleModel.TRIG_DAY_OF_WEEK -> nextDayOfWeek(t.dayOfWeek, now)
        RuleModel.TRIG_DATE -> nextDate(t.dateEpochDay, now)
        RuleModel.TRIG_INTERVAL -> now + t.intervalMinutes.coerceAtLeast(1) * 60_000L
        else -> null
    }

    private fun nextMinuteOfDay(minute: Int, now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, minute / 60)
        cal.set(Calendar.MINUTE, minute % 60)
        cal.set(Calendar.SECOND, 5)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun nextDayOfWeek(day: Int, now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        if (day in 0..6) {
            val target = (day + 1) % 7 + cal.firstDayOfWeek  // Calendar day constant
            while (cal.get(Calendar.DAY_OF_WEEK) != target ||
                (cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) < 1)
            ) {
                cal.add(Calendar.MINUTE, 1)
                if (cal.timeInMillis > now + 8 * 24 * 60 * 60_000L) break
            }
        }
        return cal.timeInMillis
    }

    private fun nextDate(epochDay: Long, now: Long): Long {
        if (epochDay <= 0) return now
        val cal = Calendar.getInstance()
        cal.timeInMillis = java.time.LocalDate.ofEpochDay(epochDay)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return cal.timeInMillis
    }

    /** The interval anchor: the last interval fire per playlist (persisted). */
    private fun lastIntervalFire(context: Context, key: String): Long =
        context.getSharedPreferences("suite_rules_runtime", Context.MODE_PRIVATE)
            .getLong(key, 0L)

    private fun rememberIntervalFire(context: Context, key: String, at: Long) {
        context.getSharedPreferences("suite_rules_runtime", Context.MODE_PRIVATE)
            .edit().putLong(key, at).apply()
    }

    private fun scheduleNextWake() {
        handler.removeCallbacks(wakeRunnable)
        WepWawetApplication.appScope.launch {
            // The schedule needs a context — grab one from the application.
            val ctx = WepWawetApplication.instance ?: return@launch
            val now = System.currentTimeMillis()
            val next = withContext(kotlinx.coroutines.Dispatchers.IO) {
                nextTimeActivation(ctx, now)
            }
            val delay = ((next ?: (now + MAX_WAKE_MS)) - now)
                .coerceIn(1_000L, MAX_WAKE_MS)
            handler.postDelayed(wakeRunnable, delay)
        }
    }

    /**
     * Fire every used time trigger whose activation moment passed since the
     * last wake (the wake schedule already aimed at the earliest one; each
     * trigger checks its own window so nothing double-fires).
     */
    private fun fireDueTimeTriggers() {
        WepWawetApplication.appScope.launch {
            val ctx = WepWawetApplication.instance ?: return@launch
            val sets = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SuiteRulesStore.readAll(ctx)
            }
            val now = System.currentTimeMillis()
            val lastWake = lastWakeAt
            lastWakeAt = now
            for (pr in sets) {
                for (rule in pr.rules) {
                    if (!rule.enabled) continue
                    for (t in rule.triggers) {
                        if (t.type !in RuleModel.TIME_TRIGGERS) continue
                        val next = nextActivationOf(t, lastWake)
                        if (next != null && next <= now + 500) {
                            when (t.type) {
                                RuleModel.TRIG_INTERVAL -> {
                                    // The interval's own anchor: fire at most
                                    // once per interval, not once per wake.
                                    val key = "interval|${pr.playlistId}|${canon(t)}"
                                    val last = lastIntervalFire(ctx, key)
                                    if (now - last >= t.intervalMinutes * 60_000L) {
                                        rememberIntervalFire(ctx, key, now)
                                        dispatch(ctx, t.type, mapOf("trigId" to canon(t)))
                                    }
                                }
                                else -> dispatch(ctx, t.type, mapOf("trigId" to canon(t)))
                            }
                        }
                    }
                }
            }
        }
    }

    @Volatile
    private var lastWakeAt = System.currentTimeMillis()

    /** A trigger's canonical identity (event ↔ trigger matching). */
    fun canon(t: Trigger): String = listOf(
        t.type, t.minuteStart, t.minuteEnd, t.timeMinute, t.dayOfWeek,
        t.dateEpochDay, t.intervalMinutes,
    ).joinToString("|")

    // ── Dispatch ────────────────────────────────────────────────────────

    /**
     * The single dispatch path: an event with its extras goes straight to
     * the engine (the app scope; no service binding, no wake locks — a
     * rule's actions are short, and Wait blocks use coroutine delay).
     */
    fun dispatch(context: Context, type: String, extra: Map<String, String> = emptyMap()) {
        WepWawetApplication.appScope.launch {
            RuleEngine.onEvent(context, type, extra)
        }
    }
}
