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
    /** v0.8.25: the self-heal re-check cap (pure bound — never gates a fire). */
    private const val MAX_WAKE_MS = 45 * 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var registeredTypes: Set<String> = emptySet()
    @Volatile
    private var registrationFailed = false

    /**
     * v0.8.27 (THE SCREEN-WAKE/UNLOCK FIX): the phone-broadcast family is
     * registered WHENEVER ANY RULE EXISTS — not only when some stored rule
     * names the type. The 0.8.26 field log: the user's "Screen turns on"
     * rule carried an orientation trigger instead (an editing casualty),
     * the hub therefore registered no screen receiver at all, and screen
     * wake / device unlock silently did nothing. With the family always
     * on, the event ARRIVES regardless of what the aggregation saw; the
     * engine re-reads the rules from disk on every event, so a stored
     * screenOn/unlocked trigger fires even when aggregation missed it.
     * One receiver, six system actions — the cost is negligible.
     */
    private val ALWAYS_REGISTERED_TYPES = setOf(
        RuleModel.TRIG_SCREEN_ON, RuleModel.TRIG_SCREEN_OFF, RuleModel.TRIG_UNLOCKED,
        RuleModel.TRIG_CHARGER_IN, RuleModel.TRIG_CHARGER_OUT,
        RuleModel.TRIG_SAVER_ON, RuleModel.TRIG_SAVER_OFF,
    )

    /**
     * The trigger types whose events can arrive RIGHT NOW (for the editors'
     * live status):
     *  • the broadcast family — armed whenever ANY rule exists;
     *  • listener types (wifi / audio / orientation) and the gated
     *    broadcasts (wallpaper-changed, battery) — armed when some enabled
     *    rule uses them (the hub registered them);
     *  • TIME types — armed only when used by an enabled rule on the
     *    ACTIVE playlist (the wake schedule is active-only since 0.8.25).
     */
    fun armedTypes(context: Context): Set<String> {
        val armed = HashSet<String>()
        val activeId = try {
            com.wepwawet.playlist.SuitePlaylistStore.activePlaylistId(context)
        } catch (e: Exception) { "" }
        val sets = try {
            SuiteRulesStore.readAll(context)
        } catch (e: Exception) {
            emptyList()
        }
        var anyRule = false
        for (pr in sets) {
            for (rule in pr.rules) {
                if (!rule.enabled) continue
                anyRule = true
                for (t in rule.triggers) {
                    if (t.type in RuleModel.TIME_TRIGGERS) {
                        if (pr.playlistId == activeId) armed.add(t.type)
                    } else {
                        armed.add(t.type)
                    }
                }
            }
        }
        if (anyRule) armed.addAll(ALWAYS_REGISTERED_TYPES)
        return armed
    }

    // The single aggregated receiver for every broadcast-based trigger.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    // v0.10.0: the broadcast ARRIVED — logged even when no
                    // rule matches or no rules exist (the user's demand: a
                    // log alone must tell a broadcast that never arrived
                    // from a rule that did not run).
                    WepWawetLogger.appInfo("$TAG: broadcast arrived",
                        "action" to "SCREEN_ON")
                    dispatch(ctx, RuleModel.TRIG_SCREEN_ON)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    WepWawetLogger.appInfo("$TAG: broadcast arrived",
                        "action" to "SCREEN_OFF")
                    dispatch(ctx, RuleModel.TRIG_SCREEN_OFF)
                }
                Intent.ACTION_USER_PRESENT -> {
                    WepWawetLogger.appInfo("$TAG: broadcast arrived",
                        "action" to "USER_PRESENT (unlocked)")
                    dispatch(ctx, RuleModel.TRIG_UNLOCKED)
                }
                Intent.ACTION_POWER_CONNECTED -> dispatch(ctx, RuleModel.TRIG_CHARGER_IN)
                Intent.ACTION_POWER_DISCONNECTED -> dispatch(ctx, RuleModel.TRIG_CHARGER_OUT)
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val on = (ctx.getSystemService(Context.POWER_SERVICE)
                        as? android.os.PowerManager)?.isPowerSaveMode == true
                    dispatch(ctx, if (on) RuleModel.TRIG_SAVER_ON else RuleModel.TRIG_SAVER_OFF)
                }
                // The system wallpaper changed. The SDK constant was
                // removed in API 34+; the action still reaches dynamic
                // receivers by its literal string. OUR OWN applies also
                // touch the system wallpaper sometimes (the live service
                // re-binds) — the engine posts its OWN internal event for
                // those, so the system broadcast right after an internal
                // post is dropped (v0.8.25: the double-fire made a
                // wallpaper-changed rule run twice per apply).
                ACTION_WALLPAPER_CHANGED -> {
                    val sinceSelf = System.currentTimeMillis() - lastSelfWallpaperApplyAt
                    if (sinceSelf < 1500L) {
                        WepWawetLogger.appDebug("$TAG: system wallpaper-changed " +
                            "dropped — our own apply ($sinceSelf ms ago)")
                    } else {
                        dispatch(ctx, RuleModel.TRIG_WALLPAPER_CHANGED)
                    }
                }
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

    /** v0.8.25: when OUR engine last applied a wallpaper (self-apply dedup). */
    @Volatile
    private var lastSelfWallpaperApplyAt = 0L

    /** The engine calls this on every apply — its own internal event follows. */
    fun noteSelfWallpaperApply() {
        lastSelfWallpaperApplyAt = System.currentTimeMillis()
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null
    private var orientationListener: android.hardware.display.DisplayManager.DisplayListener? =
        null
    private var lastOrientation = -1


    /** Start (or re-aggregate) the hub for the trigger types rules use. */
    fun start(context: Context) {
        val types = try {
            SuiteRulesStore.usedTriggerTypes(context)
        } catch (e: Exception) {
            WepWawetLogger.appWarn("$TAG: usedTriggerTypes read failed",
                "exception" to e.javaClass.simpleName,
                "message" to (e.message ?: ""))
            emptySet()
        }
        if (!started) {
            started = true
            lastBatteryPct = RuleEngine.batteryLevel(context)
            lastOrientation = context.resources.configuration.orientation
            WepWawetLogger.appInfo("$TAG: hub started", "types" to types.size)
            RuleActivityLog.record(context, "info", "", "",
                "Trigger hub started (${types.size} trigger types in use)")
        }
        // v0.8.25 (audit): the wake is re-armed on EVERY start — a rule edit
        // that only changes a VALUE (15 min → 1 hour) keeps the same trigger
        // TYPE SET, and the old early-return left the alarm aimed at the old
        // schedule until the self-heal tick noticed, up to 45 minutes late.
        scheduleNextWake()
        // v0.8.27: also re-aggregate after a FAILED registration (the old
        // early-return left a dead receiver forever if one register threw).
        if (types == registeredTypes && !registrationFailed) return
        // Re-aggregate: unregister everything, register what's used (+ the
        // always-on phone-broadcast family).
        reaggregate(context, types)
    }

    private fun reaggregate(context: Context, types: Set<String>) {
        val appCtx = context.applicationContext
        try {
            appCtx.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // not registered — fine
        }
        var failed = false
        val actions = HashSet<String>()
        // v0.8.27: the always-armed family (see ALWAYS_REGISTERED_TYPES).
        if (types.isNotEmpty()) {
            actions.add(Intent.ACTION_SCREEN_ON)
            actions.add(Intent.ACTION_SCREEN_OFF)
            actions.add(Intent.ACTION_USER_PRESENT)
            actions.add(Intent.ACTION_POWER_CONNECTED)
            actions.add(Intent.ACTION_POWER_DISCONNECTED)
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
                // v0.9.2 (THE HUB-NEVER-STARTS FIX): this filter can include
                // the CUSTOM ACTION_WALLPAPER_CHANGED action, so Android 14+
                // requires an export flag — without it registerReceiver
                // throws SecurityException and EVERY system-broadcast
                // trigger in the filter dies with it (screen/charger/battery
                // triggers never fire). NOT_EXPORTED: system broadcasts are
                // delivered to the receiver regardless of the flag; the
                // custom action is only ever sent by the system
                // (WallpaperManager), which reaches dynamic receivers too.
                androidx.core.content.ContextCompat.registerReceiver(
                    appCtx, receiver, filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                WepWawetLogger.appInfo("$TAG: broadcast receiver registered",
                    "actions" to actions.joinToString(","),
                    "flag" to "RECEIVER_NOT_EXPORTED")
            } catch (e: Exception) {
                failed = true
                WepWawetLogger.appWarn("$TAG: receiver registration failed",
                    "exception" to e.javaClass.simpleName,
                    "message" to (e.message ?: ""))
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
            // v0.8.27 (THE BOOT-STORM FIX): the 0.8.26 field log shows the
            // display listener firing TWICE during boot (sensor/surface
            // settling) — the rule rotated the wallpaper twice before the
            // user even unlocked. Two guards: a BOOT GRACE (ignore the
            // first 10s after registration) and a DEBOUNCE (an orientation
            // must hold for 800ms before it dispatches; a flap inside the
            // window is cancelled).
            val registeredAt = System.currentTimeMillis()
            val pendingRunnable = java.util.concurrent.atomic.AtomicReference<Runnable?>(null)
            val listener = object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (System.currentTimeMillis() - registeredAt < 10_000L) {
                        WepWawetLogger.appDebug("$TAG: orientation ignored — boot grace")
                        return
                    }
                    val o = appCtx.resources.configuration.orientation
                    if (o == lastOrientation) return
                    pendingRunnable.get()?.let { handler.removeCallbacks(it) }
                    val check = Runnable {
                        val now = appCtx.resources.configuration.orientation
                        if (now != lastOrientation) {
                            lastOrientation = now
                            dispatch(appCtx, RuleModel.TRIG_ORIENTATION,
                                mapOf("orientation" to
                                    if (now == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                                        "portrait" else "landscape"))
                        }
                    }
                    pendingRunnable.set(check)
                    handler.postDelayed(check, 800L)
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

        val previousTypes = registeredTypes
        registeredTypes = types
        registrationFailed = failed
        // v0.8.27: UNCONDITIONAL — the old log skipped unchanged type sets,
        // which read in the field like saves that never re-registered.
        WepWawetLogger.appInfo("$TAG: trigger registrations aggregated",
            "types" to types.size,
            "type_list" to types.joinToString(",").take(120),
            "broadcasts" to actions.size,
            "always_on_family" to (types.isNotEmpty()),
            "wifi" to (networkCallback != null),
            "audio" to (audioDeviceCallback != null),
            "orientation" to (orientationListener != null),
            "changed" to (types != previousTypes || failed))
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
        // v0.8.25 (audit): only the ACTIVE playlist's rules are scheduled —
        // the engine's dispatch runs active-playlist rules only, so arming
        // wakes for an inactive playlist's triggers is pure battery cost.
        val activeId = try {
            com.wepwawet.playlist.SuitePlaylistStore.activePlaylistId(context)
        } catch (e: Exception) { "" }
        for (pr in used) {
            if (pr.playlistId != activeId) continue
            for (rule in pr.rules) {
                if (!rule.enabled) continue
                for (t in rule.triggers) {
                    val at = nextActivationOf(context, pr.playlistId, t, now) ?: continue
                    if (best == null || at < best) best = at
                }
            }
        }
        return best
    }

    /**
     * The next activation moment of ONE trigger.
     *
     * v0.8.25 (THE INTERVAL-NEVER-FIRES FIX): INTERVAL now schedules from
     * the trigger's own PERSISTED anchor — `anchor + N`, normalized forward
     * to the latest passed boundary — instead of `now + N`. Combined with
     * the old in-memory lastWake that advanced on every self-heal wake, the
     * `now + N` form pushed every interval LONGER than the 30-minute
     * self-heal cap permanently out of reach: each no-op wake moved the
     * target a full interval ahead while the cap kept the alarm at 30
     * minutes, so the gate never opened. Anchored scheduling also removes
     * drift: a late alarm (Doze slack) fires, the anchor advances by whole
     * intervals, and the schedule stays locked to the user's grid.
     */
    private fun nextActivationOf(
        context: Context,
        playlistId: String,
        t: Trigger,
        now: Long,
    ): Long? = when (t.type) {
        RuleModel.TRIG_TIME_WINDOW -> {
            if (t.minuteStart == t.minuteEnd) null
            else nextMinuteOfDay(t.minuteStart, now)
        }
        RuleModel.TRIG_TIME_OF_DAY -> nextMinuteOfDay(t.timeMinute, now)
        RuleModel.TRIG_DAY_OF_WEEK -> nextDayOfWeek(t.dayOfWeek, now)
        RuleModel.TRIG_DATE -> nextDate(t.dateEpochDay, now)
        RuleModel.TRIG_INTERVAL -> intervalNextFire(
            context, "interval|$playlistId|${canon(t)}",
            t.intervalMinutes.coerceAtLeast(1) * 60_000L, now)
        else -> null
    }

    /**
     * The next interval boundary at or after [now], anchored to the
     * persisted last-fire (normalized forward — whole missed boundaries
     * collapse, so time offline never stacks catch-up fires and never
     * shifts the grid).
     *
     * v0.8.26 (re-audit): a never-fired interval PERSISTS its anchor HERE
     * (at arm time). Before, the anchor was only written when a wake
     * fired — so the very first wake after a restart hit the
     * "first schedule" branch, re-anchored to NOW and swallowed the fire
     * it was armed for: "Every 1 min" fired at minute 2, not minute 1.
     */
    private fun intervalNextFire(context: Context, key: String, intervalMs: Long, now: Long): Long {
        var anchor = lastIntervalFire(context, key)
        if (anchor <= 0L) {
            anchor = now
            rememberIntervalFire(context, key, anchor)
        }
        while (anchor + intervalMs <= now) anchor += intervalMs
        return anchor + intervalMs
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
        // v0.9.2 (audit pass 2): the old `(day + 1) % 7 + firstDayOfWeek`
        // mixed a 0-based user day with Calendar's 1-based constants and
        // the LOCALE's first-day-of-week — correct only where the week
        // starts on Sunday (a Sunday rule fired on Tuesday in Monday-first
        // locales). The user's day 0..6 (Mon..Sun) maps to the explicit
        // Calendar constant, and the walk advances by whole DAYS.
        if (day !in 0..6) return now
        val target = intArrayOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
        )[day]
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 5)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        while (cal.get(Calendar.DAY_OF_WEEK) != target) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
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
        WepWawetApplication.appScope.launch {
            val ctx = WepWawetApplication.instance ?: return@launch
            val now = System.currentTimeMillis()
            val next = withContext(kotlinx.coroutines.Dispatchers.IO) {
                nextTimeActivation(ctx, now)
            }
            // v0.8.25 (audit): the cap is a pure SELF-HEAL bound — a
            // no-op re-check every ≤45 minutes (DST, manual clock, rule
            // edits while armed). The FIRE decision is anchor-driven (see
            // fireDueTimeTriggers), so capping the alarm early can never
            // gate a trigger the way it did for long intervals before.
            val delay = ((next ?: (now + MAX_WAKE_MS)) - now)
                .coerceIn(1_000L, MAX_WAKE_MS)
            // v0.9.2 (THE WAKE-CAN'T-BE-TRUSTED FIX): AlarmManager instead
            // of Handler.postDelayed — the handler ran on the UPTIME clock
            // and stopped in deep sleep, so interval/time triggers never
            // fired while the device dozed. setAndAllowWhileIdle fires in
            // doze (once per 9-minute idle window worst-case, immediately
            // otherwise); RTC_WAKEUP uses the wall clock. The PendingIntent
            // is explicit (component + package) — a manifest receiver with
            // android:exported="false" and no filter. Re-armed on app
            // start, boot (BootReceiver), and every rules change.
            val at = now + delay
            val am = ctx.getSystemService(Context.ALARM_SERVICE)
                as? android.app.AlarmManager
            if (am == null) {
                WepWawetLogger.appWarn("$TAG: no AlarmManager — wake NOT armed")
                return@launch
            }
            val pi = alarmPendingIntent(ctx)
            runCatching {
                am.cancel(pi)
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    am.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    @Suppress("DEPRECATION")
                    am.setExact(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                }
                WepWawetLogger.appInfo("$TAG: next wake armed",
                    "at" to at,
                    "in_ms" to delay,
                    "driven_by" to "AlarmManager.setAndAllowWhileIdle")
            }.onFailure {
                WepWawetLogger.appWarn("$TAG: wake arming failed",
                    "exception" to it.javaClass.simpleName,
                    "message" to (it.message ?: ""))
            }
        }
    }

    /** The alarm's explicit PendingIntent → the manifest RuleAlarmReceiver. */
    private fun alarmPendingIntent(ctx: Context): android.app.PendingIntent {
        val intent = Intent(ctx, RuleAlarmReceiver::class.java)
            .setPackage(ctx.packageName)
            .setAction("com.wepwawet.rules.WAKE_TICK")
        return android.app.PendingIntent.getBroadcast(
            ctx, 9101, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE)
    }

    /** The alarm fired (RuleAlarmReceiver) — run due triggers + re-arm. */
    suspend fun onAlarmWake(ctx: Context) {
        RuleActivityLog.record(ctx, "info", "", "", "Wake alarm fired")
        fireDueTimeTriggers()
        scheduleNextWake()
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
            // v0.8.25 (THE LOST-TIMING FIX): lastWake is PERSISTED. The old
            // in-memory stamp reset on every process death, so an alarm that
            // fired right after a cold start (the common case — the manifest
            // receiver boots the process) computed the trigger's next
            // activation from NOW and missed the very fire it was armed for.
            val lastWake = readLastWake(ctx).coerceAtMost(now)
            writeLastWake(ctx, now)
            val activeId = try {
                com.wepwawet.playlist.SuitePlaylistStore.activePlaylistId(ctx)
            } catch (e: Exception) { "" }
            for (pr in sets) {
                // v0.8.25 (audit): only the ACTIVE playlist's rules run.
                if (pr.playlistId != activeId) continue
                for (rule in pr.rules) {
                    if (!rule.enabled) continue
                    for (t in rule.triggers) {
                        if (t.type !in RuleModel.TIME_TRIGGERS) continue
                        val next = nextActivationOf(ctx, pr.playlistId, t, lastWake)
                        if (next != null && next <= now + 500) {
                            when (t.type) {
                                RuleModel.TRIG_INTERVAL -> {
                                    // v0.8.25: the anchor advances by WHOLE
                                    // intervals — a fire re-anchors to the
                                    // boundary that just passed, so a late
                                    // (Doze-slacked) alarm never shifts the
                                    // grid and missed boundaries never stack
                                    // catch-up fires.
                                    val key = "interval|${pr.playlistId}|${canon(t)}"
                                    val ms = t.intervalMinutes.coerceAtLeast(1) * 60_000L
                                    var anchor = lastIntervalFire(ctx, key)
                                    if (anchor <= 0L) {
                                        // First schedule after (re)start: the
                                        // interval counts from NOW, no fire.
                                        rememberIntervalFire(ctx, key, now)
                                        continue
                                    }
                                    if (now >= anchor + ms) {
                                        while (anchor + ms <= now) anchor += ms
                                        rememberIntervalFire(ctx, key, anchor)
                                        RuleActivityLog.record(ctx, "event",
                                            pr.playlistId, rule.name,
                                            "Every ${t.intervalMinutes} min trigger due")
                                        dispatch(ctx, t.type, mapOf("trigId" to canon(t)))
                                    }
                                }
                                else -> {
                                    RuleActivityLog.record(ctx, "event",
                                        pr.playlistId, rule.name,
                                        "Time trigger due: $t.type")
                                    dispatch(ctx, t.type, mapOf("trigId" to canon(t)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readLastWake(context: Context): Long =
        context.getSharedPreferences("suite_rules_runtime", Context.MODE_PRIVATE)
            .getLong("lastWake", System.currentTimeMillis())

    private fun writeLastWake(context: Context, at: Long) {
        context.getSharedPreferences("suite_rules_runtime", Context.MODE_PRIVATE)
            .edit().putLong("lastWake", at).apply()
    }

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
