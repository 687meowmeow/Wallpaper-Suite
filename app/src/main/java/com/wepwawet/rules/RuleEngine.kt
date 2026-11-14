package com.wepwawet.rules

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.playlist.GroupBlocks
import com.wepwawet.playlist.RandomPullEngine
import com.wepwawet.playlist.SuitePlaylistStore
import io.wallpaperengine.weclient.WEWallpaperService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.random.Random

/**
 * v0.9.0: THE RULES ENGINE — executes playlist rules.
 *
 * Dispatch: [onEvent] is the single entry point for every trigger (a system
 * broadcast, a time wake, an app event, a manual run). It walks every
 * playlist's rules IN ORDER; a rule fires when one of its triggers matches
 * the event, its GATE passes, and its cooldown allows it. The playlist's
 * mode picks "the first matching rule wins" or "every matching rule runs".
 *
 * Execution: the action stack runs top to bottom in a coroutine (short
 * blocks; Wait uses delay — no wake locks). Blocks produce VALUES ("last"
 * and "block:<id>") that later blocks reference with "@last"/"@block:<id>".
 * Choose blocks ask the user ONLY when started by hand (the engine gets a
 * chooser hook from the UI; a background run takes the first option).
 *
 * Battery: nothing here polls — conditions read phone state ON DEMAND
 * (BatteryManager's sticky intent, ConnectivityManager, AudioManager…),
 * events come from the aggregated receiver in RuleTriggerHub, and time
 * triggers come from the hub's computed wake schedule.
 */
object RuleEngine {

    private const val TAG = "RuleEngine"
    private const val MAX_DEPTH = 8          // Run-rule chain depth
    private const val MAX_REPEATS = 50       // Repeat block sanity cap
    private const val NOTIF_CHANNEL = "rules"

    /** One run's mutable context. */
    class RunContext(
        val playlistId: String,
        val manual: Boolean,
        /** blockId → highlight-until epoch ms (the editor animates these). */
        val highlights: MutableMap<String, Long> = HashMap(),
        /** Produced values: "last", "block:<id>", named copies. */
        val values: MutableMap<String, String> = HashMap(),
        /** Manual-run hook: show a menu, await the pick (null = cancelled). */
        val chooser: (suspend (title: String, options: List<String>) -> Int?)? = null,
        /** Manual-run hook: a block started executing (flash its card). */
        val onHighlight: ((blockId: String) -> Unit)? = null,
        var lastPickOk: Boolean = false,
        /** v0.9.2 (honest ▶ results): WHY the last pick/step didn't apply
         *  (paused / held / empty / no service / read failure) — surfaced
         *  in the run-now result instead of a lying "ran 1 block(s)". */
        var blockReason: String? = null,
    ) {
        fun value(key: String): String? = values[key]
    }

    /** Control flow result of a block/stack execution. */
    enum class Flow { CONTINUE, STOPPED }

    // ══ Event dispatch ═════════════════════════════════════════════════

    /**
     * Fire every rule triggered by [type]. The hub, app events, the QS tile
     * and the editor's play button all end up here. Time triggers arrive
     * as events too (the hub computes their wakes).
     */
    suspend fun onEvent(context: Context, type: String, extra: Map<String, String> = emptyMap()) {
        val all = SuiteRulesStore.readAll(context)
        if (all.isEmpty()) return
        val activeId = SuitePlaylistStore.activePlaylistId(context)
        // v0.9.2 (structured logging): EVERY trigger event gets a log line,
        // so a log alone shows why a wallpaper did or didn't change.
        WepWawetLogger.appInfo("$TAG: event received",
            "event" to type,
            "rule_sets" to all.size,
            "extra_keys" to extra.keys.joinToString(","))
        for (pr in all) {
            val firedThisPlaylist = StringBuilder()
            for (rule in pr.rules) {
                if (!rule.enabled) {
                    WepWawetLogger.appDebug("$TAG: rule skipped — disabled",
                        "rule" to rule.name, "playlist" to pr.playlistId)
                    continue
                }
                if (!triggerMatches(context, rule, type, extra, pr.playlistId)) {
                    WepWawetLogger.appDebug("$TAG: rule skipped — no trigger match",
                        "rule" to rule.name, "event" to type)
                    continue
                }
                // Cooldown.
                if (rule.cooldownMinutes > 0 &&
                    System.currentTimeMillis() - rule.lastFiredAt <
                    rule.cooldownMinutes * 60_000L
                ) {
                    WepWawetLogger.appInfo("$TAG: rule in cooldown — skipped",
                        "rule" to rule.name, "cooldown_min" to rule.cooldownMinutes)
                    continue
                }
                // Gate.
                val gate = rule.gate
                if (gate != null && !evalCond(context, pr.playlistId, gate, RunContext(pr.playlistId, manual = false))) {
                    WepWawetLogger.appInfo("$TAG: gate blocked the rule",
                        "rule" to rule.name)
                    continue
                }
                val result = try {
                    fireRuleInternal(context, pr.playlistId, rule, manual = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Failed: ${e.message ?: e.javaClass.simpleName}"
                }
                firedThisPlaylist.append("${rule.name} → $result; ")
                if (pr.mode != "all") break   // first match wins
            }
            if (firedThisPlaylist.isNotEmpty()) {
                WepWawetLogger.appInfo("$TAG: event handled",
                    "event" to type,
                    "playlist" to pr.playlistId,
                    "active_playlist" to (activeId == pr.playlistId),
                    "fired" to firedThisPlaylist.toString())
            }
        }
        // v0.9.0 redesign: a condition EVENT (charger, Wi-Fi, battery…)
        // can flip a Limit scope on or off — hand the ACTIVE playlist's
        // service a scope re-check. This never rotates on its own; it only
        // switches when the current wallpaper left the allowed set.
        WEWallpaperService.instance?.requestScopeReevaluation()
    }

    /** Fire ONE rule by hand (the editor's play button, the QS tile). */
    suspend fun fireRuleManual(
        context: Context,
        playlistId: String,
        rule: RuleModel.Rule,
        chooser: (suspend (String, List<String>) -> Int?)? = null,
        onHighlight: ((String) -> Unit)? = null,
    ): String = try {
        fireRuleInternal(context, playlistId, rule, manual = true,
            chooser = chooser, onHighlight = onHighlight)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Failed: ${e.message ?: e.javaClass.simpleName}"
    }

    private suspend fun fireRuleInternal(
        context: Context,
        playlistId: String,
        rule: RuleModel.Rule,
        manual: Boolean,
        chooser: (suspend (String, List<String>) -> Int?)? = null,
        onHighlight: ((String) -> Unit)? = null,
        depth: Int = 0,
    ): String {
        val ctx = RunContext(playlistId, manual, chooser = chooser, onHighlight = onHighlight)
        WepWawetLogger.appInfo("$TAG: rule fired",
            "rule" to rule.name,
            "playlist" to playlistId,
            "manual" to manual)
        val flow = execBlocks(context, ctx, rule.actions, depth)
        val result = buildString {
            append(if (ctx.lastPickOk) "applied a wallpaper" else "ran ${rule.actions.size} block(s)")
            if (flow == Flow.STOPPED) append(" (stopped early)")
            // v0.9.2: the honest reason a pick didn't apply.
            ctx.blockReason?.let { append(" — $it") }
            ctx.value("last")?.let { append(" — ${it.substringAfterLast('/')}") }
        }
        SuiteRulesStore.recordFire(context, playlistId, rule, result)
        // The hold option: a wallpaper this rule applied isn't replaced by
        // normal rotation until the hold passes.
        if (rule.holdMinutes > 0 && ctx.lastPickOk) {
            WEWallpaperService.instance?.holdWallpaperFor(rule.holdMinutes)
        }
        return result
    }

    // ══ Trigger matching ═══════════════════════════════════════════════

    private fun triggerMatches(
        context: Context,
        rule: RuleModel.Rule,
        eventType: String,
        extra: Map<String, String>,
        playlistId: String,
    ): Boolean {
        for (t in rule.triggers) {
            when {
                t.type == eventType -> {
                    // Time triggers carry their canonical identity — a
                    // window's wake fires THAT window's rules, not every
                    // time-window rule in the app.
                    if (t.type in RuleModel.TIME_TRIGGERS) {
                        if (extra["trigId"] == RuleTriggerHub.canon(t)) return true
                    } else if (triggerParamsMatch(context, t, extra)) {
                        return true
                    }
                }
                // Edge-triggered battery thresholds: the hub reports raw
                // level changes; a threshold trigger matches on CROSSES.
                t.type == RuleModel.TRIG_BATTERY_BELOW && eventType == "batteryChanged" -> {
                    val level = extra["level"]?.toIntOrNull() ?: continue
                    val prev = extra["prev"]?.toIntOrNull() ?: level
                    if (level <= t.batteryLevel && prev > t.batteryLevel) return true
                }
                t.type == RuleModel.TRIG_BATTERY_ABOVE && eventType == "batteryChanged" -> {
                    val level = extra["level"]?.toIntOrNull() ?: continue
                    val prev = extra["prev"]?.toIntOrNull() ?: level
                    if (level >= t.batteryLevel && prev < t.batteryLevel) return true
                }
                // Wi-Fi triggers match any network or a chosen SSID.
                t.type == RuleModel.TRIG_WIFI_CONNECT && eventType == "wifiConnected" -> {
                    if (t.networkSsid.isEmpty() || extra["ssid"] == t.networkSsid) return true
                }
                t.type == RuleModel.TRIG_WIFI_DISCONNECT && eventType == "wifiDisconnected" -> {
                    if (t.networkSsid.isEmpty() || extra["ssid"] == t.networkSsid) return true
                }
                // Headphone / BT device triggers: "" = any device.
                t.type == RuleModel.TRIG_HEADPHONES_IN && eventType == "headphonesConnected" -> return true
                t.type == RuleModel.TRIG_HEADPHONES_OUT && eventType == "headphonesDisconnected" -> return true
                t.type == RuleModel.TRIG_BT_DEVICE_IN && eventType == "btConnected" -> {
                    if (t.deviceName.isEmpty() || extra["device"]?.contains(t.deviceName, true) == true) return true
                }
                t.type == RuleModel.TRIG_BT_DEVICE_OUT && eventType == "btDisconnected" -> {
                    if (t.deviceName.isEmpty() || extra["device"]?.contains(t.deviceName, true) == true) return true
                }
                // A rule started by another rule (any rule event from this playlist).
                t.type == RuleModel.TRIG_FROM_RULE && eventType == "fromRule" -> {
                    if (extra["playlistId"] == playlistId && extra["fromRuleId"] != rule.id) return true
                }
            }
        }
        return false
    }

    private fun triggerParamsMatch(
        context: Context,
        t: RuleModel.Trigger,
        extra: Map<String, String>,
    ): Boolean = when (t.type) {
        RuleModel.TRIG_BATTERY_BELOW -> (extra["level"]?.toIntOrNull() ?: 100) <= t.batteryLevel
        RuleModel.TRIG_BATTERY_ABOVE -> (extra["level"]?.toIntOrNull() ?: 0) >= t.batteryLevel
        else -> true
    }

    // ══ Condition evaluation ═══════════════════════════════════════════

    fun evalCond(
        context: Context,
        playlistId: String,
        cond: RuleModel.Cond,
        ctx: RunContext?,
    ): Boolean = when (cond.type) {
        RuleModel.COND_ALL -> cond.children.all { evalCond(context, playlistId, it, ctx) }
        RuleModel.COND_ANY -> cond.children.any { evalCond(context, playlistId, it, ctx) }
        RuleModel.COND_NOT -> {
            val inner = cond.children.firstOrNull()
            if (inner == null) true
            else !evalCond(context, playlistId, inner, ctx)
        }
        RuleModel.COND_TIME_RANGE -> {
            val m = minuteOfDay()
            if (cond.minuteStart == cond.minuteEnd) true
            else if (cond.minuteStart < cond.minuteEnd)
                m >= cond.minuteStart && m < cond.minuteEnd
            else m >= cond.minuteStart || m < cond.minuteEnd
        }
        RuleModel.COND_DAY_OF_WEEK ->
            cond.dayOfWeek < 0 || Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1 == cond.dayOfWeek
        RuleModel.COND_CHARGING -> isCharging(context)
        RuleModel.COND_BATTERY_BELOW -> batteryLevel(context) <= cond.batteryLevel
        RuleModel.COND_BATTERY_ABOVE -> batteryLevel(context) >= cond.batteryLevel
        RuleModel.COND_SAVER_ON -> isSaverOn(context)
        RuleModel.COND_WIFI_CONNECTED -> isWifiConnected(context)
        RuleModel.COND_WIFI_IS -> isWifiConnected(context) &&
            (cond.networkSsid.isEmpty() || currentSsid(context) == cond.networkSsid)
        RuleModel.COND_MOBILE_DATA -> isMobileDataActive(context)
        RuleModel.COND_HEADPHONES -> areHeadphonesOn(context)
        RuleModel.COND_SCREEN_ON -> PowerManager::class.let {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
        }
        RuleModel.COND_ORIENTATION_IS ->
            if (cond.orientation == "portrait")
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            else context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT
        RuleModel.COND_PLAYLIST_ACTIVE ->
            SuitePlaylistStore.activePlaylistId(context) ==
                cond.playlistId.ifEmpty { playlistId }
        RuleModel.COND_WALLPAPER_IS ->
            WEWallpaperService.instance?.currentWallpaperFile() ==
                resolveRef(cond.wallpaperPath, ctx)
        RuleModel.COND_SHOWN_MINUTES ->
            (WEWallpaperService.instance?.currentWallpaperShownMinutes() ?: 0L) >= cond.minutes
        RuleModel.COND_VALUE_IS -> {
            val v = SuiteRulesStore.readValue(context, cond.valueName)
            if (v == null) false
            else valueMatches(v, cond, ctx)
        }
        RuleModel.COND_LAST_PICK_OK -> ctx?.lastPickOk == true
        else -> false
    }

    private fun compareBool(a: Boolean, cmp: String, b: Boolean): Boolean = when (cmp) {
        "=" -> a == b
        "≠" -> a != b
        else -> a == b
    }

    private fun compareNum(a: Long, cmp: String, b: Long): Boolean = when (cmp) {
        "=" -> a == b
        "≠" -> a != b
        "<" -> a < b
        ">" -> a > b
        "≤" -> a <= b
        "≥" -> a >= b
        else -> a == b
    }

    /** The named-value comparison (kind-aware). */
    private fun valueMatches(
        v: SuiteRulesStore.NamedValue,
        cond: RuleModel.Cond,
        ctx: RunContext?,
    ): Boolean = when (v.kind) {
        "bool" -> compareBool(v.boolValue, cond.cmp,
            cond.compareTo == "on" || cond.compareTo == "true")
        "number" -> compareNum(v.numValue, cond.cmp, cond.compareToNum)
        else -> v.strValue == resolveRef(cond.compareTo, ctx)
    }

    /** Resolve a "@last"/"@block:<id>" reference against the run context. */
    fun resolveRef(ref: String, ctx: RunContext?): String = when {
        ref == "@last" -> ctx?.value("last") ?: ref
        ref.startsWith("@block:") -> ctx?.value(ref.removePrefix("@")) ?: ref
        else -> ref
    }

    // ══ Phone state (on-demand — nothing polls) ═════════════════════════

    fun batteryLevel(context: Context): Int = try {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra("level", -1) ?: -1
        val scale = i?.getIntExtra("scale", 100) ?: 100
        if (level >= 0 && scale > 0) level * 100 / scale else 100
    } catch (e: Exception) {
        100
    }

    fun isCharging(context: Context): Boolean = try {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = i?.getIntExtra("status", -1) ?: -1
        status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    } catch (e: Exception) {
        false
    }

    fun isSaverOn(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    fun isWifiConnected(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) {
        false
    }

    fun isMobileDataActive(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    } catch (e: Exception) {
        false
    }

    fun currentSsid(context: Context): String = try {
        @Suppress("DEPRECATION")
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        wifi?.connectionInfo?.ssid?.removeSurrounding("\"") ?: ""
    } catch (e: Exception) {
        ""
    }

    fun areHeadphonesOn(context: Context): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val devices = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        devices.any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
    } catch (e: Exception) {
        false
    }

    fun minuteOfDay(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    // ══ Block execution ═══════════════════════════════════════════════

    private suspend fun execBlocks(
        context: Context,
        ctx: RunContext,
        blocks: List<RuleModel.Block>,
        depth: Int,
    ): Flow {
        for (b in blocks) {
            // v0.9.0 redesign: a disabled block (the ⋮ menu) is skipped.
            if (b.disabled) continue
            // The highlight window: brief, so a manual run shows the flow.
            ctx.highlights[b.id] = System.currentTimeMillis() + 1400
            ctx.onHighlight?.invoke(b.id)
            if (execBlock(context, ctx, b, depth) == Flow.STOPPED) return Flow.STOPPED
        }
        return Flow.CONTINUE
    }

    private suspend fun execBlock(
        context: Context,
        ctx: RunContext,
        b: RuleModel.Block,
        depth: Int,
    ): Flow {
        return try {
            when (b.type) {
                // ── Wallpaper ──
                RuleModel.BLOCK_SHOW -> {
                    val path = resolveRef(b.param, ctx)
                    applyPick(context, ctx, b, listOf(path), resolvedTarget(context, b.param3))
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_SHOW_SET -> {
                    val paths = b.paths.map { resolveRef(it, ctx) }.filter { it.isNotEmpty() }
                    applyPick(context, ctx, b, paths, b.param2.ifEmpty { "home" })
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_PICK_GROUP, RuleModel.BLOCK_PICK_PLAYLIST, RuleModel.BLOCK_RANDOM -> {
                    val candidates = candidatesFor(context, ctx, b)
                    applyPick(context, ctx, b, candidates, resolvedTarget(context, b.param3))
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_PULL_DISCOVER -> {
                    val playlist = SuitePlaylistStore.read(context, ctx.playlistId)
                    if (playlist != null) {
                        val path = withContext(Dispatchers.IO) {
                            RandomPullEngine.pull(
                                context, playlist,
                                RandomPullEngine.newSlotEntry(
                                    strict = b.param3.takeIf { it.isNotBlank() }
                                        ?.split(',')?.map { it.trim() },
                                ),
                            )
                        }
                        if (path != null) {
                            applyImagePath(context, ctx, b, path,
                                resolvedTarget(context, b.param3))
                            Flow.CONTINUE
                        } else {
                            ctx.lastPickOk = false
                            Flow.CONTINUE
                        }
                    } else Flow.CONTINUE
                }
                RuleModel.BLOCK_NEXT -> {
                    stepPlaylist(context, ctx, b, +1)
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_LIMIT -> {
                    // A Limit scope is evaluated CONTINUOUSLY by the
                    // service (suiteSetForNow) — firing the rule runs
                    // nothing here; the scope's state applies on the
                    // service's next re-evaluation.
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_PREV -> {
                    stepPlaylist(context, ctx, b, -1)
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_SHUFFLE -> {
                    stepPlaylist(context, ctx, b, 0)
                    Flow.CONTINUE
                }
                // ── Playlist ──
                RuleModel.BLOCK_PAUSE_ROTATION -> {
                    WEWallpaperService.instance?.setRotationPaused(true)
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_RESUME_ROTATION -> {
                    WEWallpaperService.instance?.setRotationPaused(false)
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_RESTART_PLAYLIST -> {
                    WEWallpaperService.instance?.forcePlaylistRotation()
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_HOLD_CURRENT -> {
                    WEWallpaperService.instance?.holdWallpaperFor(b.num.coerceIn(1, 24 * 60))
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_SWITCH_PLAYLIST -> {
                    if (b.param.isNotEmpty()) {
                        SuitePlaylistStore.setActive(context, b.param)
                    }
                    Flow.CONTINUE
                }
                // ── Engine ──
                RuleModel.BLOCK_SET_FPS -> {
                    WEWallpaperService.instance
                        ?.rulesSetFps(b.num.coerceIn(1, 120))
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_AUDIO_MUTE -> {
                    WEWallpaperService.instance?.rulesAudioMuted = b.param != "unmute"
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_RENDERER_PAUSE -> {
                    WEWallpaperService.instance
                        ?.rulesSetRendererPaused(b.param != "resume")
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_APPLY_PRESET -> {
                    val preset = SuiteRulesStore.readPresets(context)
                        .firstOrNull { it.name == b.param }
                    if (preset != null) {
                        WEWallpaperService.instance
                            ?.rulesApplyProperties(preset.propertiesJson)
                    }
                    Flow.CONTINUE
                }
                // ── Logic ──
                RuleModel.BLOCK_IF -> {
                    val cond = b.cond ?: return Flow.CONTINUE
                    if (evalCond(context, ctx.playlistId, cond, ctx)) {
                        execBlocks(context, ctx, b.thenBlocks, depth)
                    } else {
                        execBlocks(context, ctx, b.elseBlocks, depth)
                    }
                }
                RuleModel.BLOCK_CHOOSE -> {
                    if (b.options.isEmpty()) return Flow.CONTINUE
                    // A background run takes the FIRST option; a manual run
                    // asks (the chooser hook) — a cancel stops the rule.
                    val pick: Int? = if (ctx.manual && ctx.chooser != null) {
                        ctx.chooser.invoke(b.param.ifEmpty { "Choose" },
                            b.options.map { it.label })
                    } else 0
                    if (pick == null) Flow.STOPPED
                    else execBlocks(context, ctx,
                        b.options[pick.coerceIn(0, b.options.size - 1)].blocks, depth)
                }
                RuleModel.BLOCK_REPEAT -> {
                    var flow = Flow.CONTINUE
                    repeat(b.num.coerceIn(1, MAX_REPEATS).coerceAtMost(MAX_REPEATS)) {
                        if (flow == Flow.CONTINUE) {
                            flow = execBlocks(context, ctx, b.children, depth)
                        }
                    }
                    flow
                }
                RuleModel.BLOCK_WAIT -> {
                    delay(b.num.coerceIn(1, 600).coerceAtMost(600) * 1000L)
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_STOP -> Flow.STOPPED
                RuleModel.BLOCK_RUN_RULE -> {
                    if (depth >= MAX_DEPTH) {
                        WepWawetLogger.appWarn("$TAG: run-rule chain too deep — stopping",
                            "depth" to depth)
                        return Flow.CONTINUE
                    }
                    val target = SuiteRulesStore.readAll(context)
                        .firstOrNull { it.playlistId == ctx.playlistId }
                        ?.rules?.firstOrNull { it.id == b.param }
                        ?: return Flow.CONTINUE
                    fireRuleInternal(context, ctx.playlistId, target,
                        manual = ctx.manual, chooser = ctx.chooser,
                        onHighlight = ctx.onHighlight, depth = depth + 1)
                    // Also fan the event out to fromRule triggers elsewhere.
                    Flow.CONTINUE
                }
                // ── Utility ──
                RuleModel.BLOCK_NOTIFY -> {
                    notifyUser(context, b.param, b.param2)
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_DISCOVER_REFRESH -> {
                    com.wepwawet.discover.DiscoverPreload.kick(context)
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_WORKSHOP_DOWNLOAD -> {
                    val id = b.param.trim()
                    if (id.isNotEmpty()) {
                        com.wepwawet.WepWawetApplication.appScope.launch {
                            // Resolve the workshop item by id, then start the
                            // same debug download the detail screen uses.
                            val item = withContext(Dispatchers.IO) {
                                com.wepwawet.data.SteamApiService()
                                    .getPublishedFileDetails(listOf(id))
                                    .getOrNull()?.firstOrNull()
                            }
                            if (item != null) {
                                com.wepwawet.pairing.WorkshopDownloadJob
                                    .launchDebugDownload(context, item)
                            } else {
                                WepWawetLogger.appWarn("$TAG: workshop download — item not found",
                                    "id" to id)
                            }
                        }
                    }
                    Flow.CONTINUE
                }
                // ── Named values ──
                RuleModel.BLOCK_SET_VALUE -> {
                    if (b.param.isNotEmpty()) {
                        SuiteRulesStore.setValue(context, b.param, SuiteRulesStore.NamedValue(
                            kind = b.param3.ifEmpty { "string" },
                            boolValue = b.param2 == "on" || b.param2 == "true",
                            numValue = b.num.toLong(),
                            strValue = b.param2,
                        ))
                    }
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_ADD_VALUE -> {
                    if (b.param.isNotEmpty()) {
                        SuiteRulesStore.addValue(context, b.param, b.num.toLong())
                    }
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_CLEAR_VALUE -> {
                    if (b.param.isNotEmpty()) SuiteRulesStore.clearValue(context, b.param)
                    Flow.CONTINUE
                }
                // ── Value flow ──
                RuleModel.BLOCK_SAVE_TO_FOLDER -> {
                    val last = ctx.value("last")
                    if (last != null) savePickToGroupFolder(context, ctx, b, last)
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_ADD_TO_PLAYLIST -> {
                    val last = ctx.value("last")
                    if (last != null && b.param.isNotEmpty()) {
                        SuitePlaylistStore.addEntry(
                            context, b.param,
                            SuitePlaylistStore.Entry(
                                filepath = last,
                                title = last.substringAfterLast('/'),
                                type = ""))
                    }
                    Flow.CONTINUE
                }
                else -> Flow.CONTINUE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WepWawetLogger.appWarn("$TAG: block failed",
                "type" to b.type,
                "exception" to e.javaClass.simpleName,
                "message" to (e.message ?: ""))
            Flow.CONTINUE
        }
    }

    // ══ Wallpaper picking + applying ══════════════════════════════════

    /** The candidate list for pick-style blocks. */
    private suspend fun candidatesFor(
        context: Context,
        ctx: RunContext,
        b: RuleModel.Block,
    ): List<String> {
        val pick = b.param2.ifEmpty { "random" }
        return when (b.type) {
            RuleModel.BLOCK_PICK_GROUP -> {
                val group = resolveRef(b.param, ctx)
                val playlist = SuitePlaylistStore.read(context, ctx.playlistId)
                val entry = playlist?.entries?.firstOrNull {
                    com.wepwawet.playlist.GroupBlocks.isGroupBlock(it.filepath) &&
                        it.groupRef == group
                }
                if (entry != null) {
                    withContext(Dispatchers.IO) { GroupBlocks.resolve(context, entry).paths }
                } else {
                    // Resolve the group live without a block entry.
                    withContext(Dispatchers.IO) {
                        GroupBlocks.resolve(
                            context,
                            SuitePlaylistStore.Entry(
                                filepath = GroupBlocks.marker(group),
                                title = group, type = "Group",
                                groupRef = group,
                                blockOrder = if (pick == "random") GroupBlocks.ORDER_RANDOM else GroupBlocks.ORDER_MANUAL,
                            ),
                        ).paths
                    }
                }
            }
            RuleModel.BLOCK_PICK_PLAYLIST -> {
                val playlist = SuitePlaylistStore.read(context,
                    b.param.ifEmpty { ctx.playlistId }) ?: return emptyList()
                val paths = ArrayList<String>()
                for (e in playlist.entries) {
                    if (GroupBlocks.isGroupBlock(e.filepath)) {
                        paths.addAll(withContext(Dispatchers.IO) {
                            GroupBlocks.resolve(context, e).paths
                        })
                    } else if (!RandomPullEngine.isSlot(e.filepath)) {
                        paths.add(e.filepath)
                    }
                }
                paths
            }
            RuleModel.BLOCK_RANDOM -> when (b.param.ifEmpty { "library" }) {
                "group" -> candidatesFor(context, ctx, b.copy(type = RuleModel.BLOCK_PICK_GROUP, param2 = pick))
                "playlist" -> candidatesFor(context, ctx, b.copy(type = RuleModel.BLOCK_PICK_PLAYLIST, param2 = pick))
                "discover" -> {
                    // The Discover source = Discover's own current settings.
                    val url = withContext(Dispatchers.IO) {
                        runCatching {
                            com.wepwawet.discover.DiscoverSearch.searchOne(
                                context = context,
                                strictTags = com.wepwawet.discover.DiscoverPrefs.readStrictTags(context),
                                softTags = com.wepwawet.discover.DiscoverPrefs.readSoftTags(context),
                                styleValue = com.wepwawet.discover.DiscoverPrefs.readStyle(context),
                                r18Mode = com.wepwawet.discover.DiscoverPrefs.readR18(context),
                                recentUrls = com.wepwawet.discover.DiscoverPrefs.readRecentUrls(context),
                                sources = com.wepwawet.discover.DiscoverPrefs.readSources(context),
                            )
                        }.onFailure {
                            WepWawetLogger.appWarn("$TAG: discover pick failed",
                                "exception" to it.javaClass.simpleName)
                        }.getOrNull()
                    }
                    val bytes = if (url != null) withContext(Dispatchers.IO) {
                        runCatching { com.wepwawet.discover.DiscoverSearch.downloadImage(url) }.onFailure { WepWawetLogger.appWarn("RuleEngine: com — best-effort call failed", "exception" to it.javaClass.simpleName) }
                            .onFailure {
                                WepWawetLogger.appWarn("$TAG: discover download failed",
                                    "exception" to it.javaClass.simpleName)
                            }.getOrNull()
                    } else null
                    if (bytes != null && url != null) {
                        com.wepwawet.discover.DiscoverPrefs.pushRecentUrl(context, url)
                        val f = java.io.File(
                            java.io.File(context.filesDir, "rule_picks").apply { mkdirs() },
                            "pick_${System.currentTimeMillis()}.img")
                        withContext(Dispatchers.IO) { f.writeBytes(bytes) }
                        listOf(f.absolutePath)
                    } else emptyList()
                }
                else -> withContext(Dispatchers.IO) {
                    com.wepwawet.engine.EngineLibrary.enumerateWallpapers(context)
                        .map { it.filePath }
                }
            }
            else -> emptyList()
        }.let { list ->
            // Apply the pick MODE ordering.
            when (pick) {
                "inOrder" -> list
                "newest" -> list.sortedByDescending { it.substringAfterLast('/') }
                else -> list
            }
        }
    }

    /** Pick from [candidates] per the block's mode and apply. */
    private suspend fun applyPick(
        context: Context,
        ctx: RunContext,
        b: RuleModel.Block,
        candidates: List<String>,
        target: String,
    ) {
        if (candidates.isEmpty()) {
            // v0.9.2: the honest ▶ reason — an empty group/set was named.
            ctx.lastPickOk = false
            ctx.blockReason = "no candidates — the group/set is empty"
            WepWawetLogger.appInfo("$TAG: pick skipped — empty candidates",
                "block" to b.type)
            return
        }
        val mode = when (b.type) {
            RuleModel.BLOCK_SHOW_SET, RuleModel.BLOCK_PICK_GROUP,
            RuleModel.BLOCK_PICK_PLAYLIST, RuleModel.BLOCK_RANDOM,
            -> b.param2.ifEmpty { "random" }
            else -> "random"
        }
        val current = WEWallpaperService.instance?.currentWallpaperFile()
        var picked = pickFrom(candidates, mode, current, context, ctx)
        when (resolvedTarget(context, target)) {
            "bothDifferent" -> {
                val second = pickFrom(candidates.filter { it != picked }, mode, current, context, ctx)
                if (second != null && second != picked) {
                    applyImagePath(context, ctx, b, picked, "home")
                    applyImagePath(context, ctx, b, second, "lock")
                } else {
                    applyImagePath(context, ctx, b, picked, "both")
                }
            }
            else -> applyImagePath(context, ctx, b, picked, resolvedTarget(context, target))
        }
    }

    /**
     * v0.9.0 redesign: the UNIVERSAL target resolution. The Home/Lock
     * tri-state toggles (DiscoverPrefs.readScreens) sit at the top of the
     * app and govern EVERY apply anywhere. A block with an EMPTY target
     * field (the default) follows them: home-only, lock-only, both-same
     * (one image, same color toggles) or both-different (different
     * colors). A block with an EXPLICIT target ("on the lock screen")
     * keeps it — the editor still offers per-block choices.
     */
    fun resolvedTarget(context: Context, target: String): String {
        if (target.isNotEmpty()) return target
        val screens = com.wepwawet.discover.DiscoverPrefs.readScreens(context)
        val home = com.wepwawet.discover.DiscoverPrefs.homeRequired(screens.first)
        val lock = com.wepwawet.discover.DiscoverPrefs.lockRequired(screens.second)
        return when {
            home && lock -> if (com.wepwawet.discover.DiscoverPrefs.isDualMode(
                    screens.first, screens.second)) "bothDifferent" else "both"
            home -> "home"
            lock -> "lock"
            // Both toggles off: nothing selected anywhere — apply to both
            // (the toggles' screen makes the state obvious; a rule the
            // user runs by hand should still show SOMETHING).
            else -> "both"
        }
    }

    private fun pickFrom(
        candidates: List<String>,
        mode: String,
        current: String?,
        context: Context,
        ctx: RunContext,
    ): String {
        val pool = candidates.filter { it != current }.ifEmpty { candidates }
        return when (mode) {
            "inOrder" -> pool[0]
            "newest" -> pool.first()
            "avoidRecent" -> {
                val recent = readRecentPicks(context, ctx.playlistId)
                pool.firstOrNull { it !in recent } ?: pool[Random.nextInt(pool.size)]
            }
            else -> pool[Random.nextInt(pool.size)]
        }
    }

    /**
     * Apply one picked path to the target screen(s).
     *
     * v0.9.2 (THE "NEXT CANNOT DISPLAY MY WALLPAPERS" FIX, from the
     * 2026-11-14 review): the old version read EVERY image with
     * `java.io.File(path).readBytes()` — a guaranteed FileNotFoundException
     * for the `content://com.android.externalstorage.documents/…` SAF
     * URIs a linked folder's members are — and applied through
     * WallpaperManager.setBitmap (FLAG_SYSTEM/FLAG_LOCK), which sets a
     * STATIC system wallpaper and REPLACES the live wallpaper service the
     * playlist runs on.
     *
     * The new path:
     *  • HOME (or BOTH) — ALWAYS through the LIVE SERVICE's own loader:
     *    the file is written as the engine's selected wallpaper and
     *    `requestWallpaperReload()` loads it — exactly the
     *    reloadActiveWallpaper path that has always worked (it handles
     *    .mpkg packages, plain files AND content:// SAF URIs). The live
     *    service keeps running; the playlist keeps its rotation state.
     *  • LOCK — the one screen a third-party live wallpaper can never
     *    own — gets the static WallpaperManager.setBitmap(FLAG_LOCK)
     *    path. Its bytes are read through ContentResolver for content://
     *    URIs and File for plain paths.
     *  • BOTH-DIFFERENT — home via the service loader + a second image,
     *    static, on the lock screen.
     * The universal Home/Lock toggles drive `target` (resolvedTarget) —
     * nothing here re-reads them.
     */
    private suspend fun applyImagePath(
        context: Context,
        ctx: RunContext,
        b: RuleModel.Block,
        path: String,
        target: String,
    ) {
        val wantHome = target == "home" || target == "both" || target == "bothDifferent"
        val wantLock = target == "lock" || target == "both" || target == "bothDifferent"
        ctx.blockReason = null
        var homeOk = !wantHome
        var lockOk = !wantLock
        if (wantHome) {
            val svc = WEWallpaperService.instance
            if (svc == null) {
                // v0.9.2: no fallback to a static set — that is the very
                // call that replaced the live service. Report the honest
                // reason instead (the ▶ result and the log both show it).
                homeOk = false
                ctx.blockReason = "wallpaper service not running"
                WepWawetLogger.appWarn("$TAG: apply skipped — no live service",
                    "path" to path.takeLast(56))
            } else {
                withContext(Dispatchers.IO) {
                    io.wallpaperengine.weclient.WeEnginePrefs.storeString(
                        context, io.wallpaperengine.weclient.WeEnginePrefs
                            .PREF_KEY_SELECTED_WALLPAPER, path)
                }
                svc.requestWallpaperReload()
                homeOk = true
                WepWawetLogger.appInfo(
                    "$TAG: wallpaper applied via live service loader",
                    "path" to path.takeLast(56),
                    "target" to target)
            }
        }
        if (wantLock) {
            lockOk = withContext(Dispatchers.IO) {
                val bmp = readImageBytes(context, path)?.let {
                    com.wepwawet.discover.DiscoverApplier.decode(it, 2160)
                }
                if (bmp == null) {
                    ctx.blockReason = "could not read the image for the lock screen"
                    WepWawetLogger.appWarn("$TAG: lock apply failed — unreadable image",
                        "path" to path.takeLast(56))
                    false
                } else {
                    val ok = com.wepwawet.discover.DiscoverApplier
                        .apply(context, bmp, homeOn = false, lockOn = true).allOk
                    if (ok) WepWawetLogger.appInfo(
                        "$TAG: wallpaper applied to the lock screen (static)",
                        "path" to path.takeLast(56))
                    ok
                }
            }
        }
        val ok = homeOk && lockOk
        ctx.lastPickOk = ok
        if (ok) {
            ctx.values["last"] = path
            ctx.values["block:${b.id}"] = path
            rememberRecentPick(context, ctx.playlistId, path)
            // v0.9.0 (rules): the "the wallpaper changed" trigger — our own
            // apply (the system broadcast covers manual changes).
            RuleEvents.post(context, RuleModel.TRIG_WALLPAPER_CHANGED)
        }
    }

    /**
     * v0.9.2: read image bytes for ANY path the app uses — plain files
     * and content:// SAF URIs (a linked folder's members are all
     * `content://com.android.externalstorage.documents/…`, which
     * `File(path).readBytes()` can never open).
     */
    private fun readImageBytes(context: Context, path: String): ByteArray? = try {
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(android.net.Uri.parse(path))
                ?.use { it.readBytes() }
        } else {
            java.io.File(path).takeIf { it.exists() }?.readBytes()
        }
    } catch (e: Exception) {
        WepWawetLogger.appWarn("$TAG: image read failed",
            "path" to path.takeLast(56),
            "exception" to e.javaClass.simpleName,
            "message" to (e.message ?: ""))
        null
    }

    /**
     * next / previous / shuffle relative to the playlist's rotation order.
     *
     * v0.9.2 (THE "RANDOM / IN ORDER IS IGNORED" FIX, from the 2026-11-14
     * review): stepPlaylist now READS playlist.orderMode and keeps a
     * PERSISTED per-playlist cursor + history + random bag
     * (suite_rules_runtime — they survive service restarts and reboots):
     *
     *  • IN ORDER — the persisted cursor advances +1 with wrap, following
     *    the candidate list's order (each playlist entry in order; a group
     *    block expands to its members in the block's own order).
     *  • RANDOM — a shuffled BAG with no repeats until it is exhausted,
     *    reshuffled then, and NEVER containing the current image.
     *  • PREVIOUS — walks back through the persisted history.
     *
     * The cursor no longer derives from svc.currentWallpaperFile() — that
     * value is stale for static images and left `idx = -1` (Next always
     * landing on the first image). Every successful step updates the
     * cursor + history atomically.
     *
     * v0.9.0 redesign notes that still hold: this is the ONLY thing that
     * advances a playlist — the "Every N" trigger runs it; random slots
     * are real candidates (a picked slot PULLS a fresh image through its
     * own settings); a paused rotation or an active rule hold steps
     * nothing; the target is the universal Home/Lock system.
     */
    private suspend fun stepPlaylist(
        context: Context,
        ctx: RunContext,
        b: RuleModel.Block,
        dir: Int,
    ) {
        val stepName = when { dir > 0 -> "next"; dir < 0 -> "previous"; else -> "shuffle" }
        fun skip(reason: String) {
            ctx.lastPickOk = false
            ctx.blockReason = reason
            WepWawetLogger.appInfo("$TAG: playlist step skipped — $reason",
                "step" to stepName,
                "playlist" to ctx.playlistId)
        }
        val svc = WEWallpaperService.instance
        if (svc == null) { skip("wallpaper service not running"); return }
        if (svc.isRotationPaused()) { skip("rotation is paused"); return }
        if (svc.isRotationHeld()) { skip("a rule is holding the current wallpaper"); return }
        val playlist = SuitePlaylistStore.read(context, ctx.playlistId)
        if (playlist == null) { skip("playlist not found"); return }
        val paths = orderedStepCandidates(context, playlist)
        if (paths.isEmpty()) { skip("playlist is empty"); return }
        val current = svc.currentWallpaperFile()

        // ── Pick per the order mode + direction ──
        val picked: String? = when {
            dir < 0 -> {
                val history = readStepHistory(context, playlist.id)
                val back = history.lastOrNull { it != current }
                if (back == null) {
                    skip("no previous wallpaper yet (history is empty)")
                    null
                } else back
            }
            playlist.orderMode == SuitePlaylistStore.ORDER_ORDERED -> {
                val cursor = readStepCursor(context, playlist.id)
                    .coerceIn(0, paths.size - 1)
                // A cursor that points AT the current image advances to the
                // NEXT one; a stale cursor (entries edited) just clamps.
                var idx = cursor
                if (paths[idx] == current) idx = (idx + 1).mod(paths.size)
                paths[idx]
            }
            else -> {
                // RANDOM — the bag (persisted, no repeats until exhausted,
                // never the current image).
                val bag = popBagNext(context, playlist.id, paths, current)
                if (bag == null) {
                    skip("random bag could not produce a new image")
                    null
                } else bag
            }
        }
        if (picked == null) return

        // ── Apply (a slot pulls a fresh image through its settings) ──
        val applied: String? = if (RandomPullEngine.isSlot(picked)) {
            val slot = playlist.entries.firstOrNull { it.filepath == picked }
            if (slot == null) {
                skip("random slot entry disappeared")
                null
            } else {
                val pulled = withContext(Dispatchers.IO) {
                    RandomPullEngine.pull(context, playlist, slot)
                }
                if (pulled == null) {
                    skip("random slot pull failed")
                    null
                } else pulled
            }
        } else picked

        if (applied == null) return
        applyImagePath(context, ctx, b, applied, resolvedTarget(context, b.param3))
        if (ctx.lastPickOk) {
            recordStep(context, playlist.id, applied, paths, dir)
            WepWawetLogger.appInfo("$TAG: playlist step applied",
                "step" to stepName,
                "order" to playlist.orderMode,
                "picked" to applied.takeLast(56),
                "target" to resolvedTarget(context, b.param3))
        } else {
            WepWawetLogger.appWarn("$TAG: playlist step picked but apply failed",
                "step" to stepName,
                "picked" to applied.takeLast(56),
                "reason" to (ctx.blockReason ?: "unknown"))
        }
    }

    /** The step candidate list: entries in order, group blocks resolved
     *  in their block order, slots included as their markers. */
    private suspend fun orderedStepCandidates(
        context: Context,
        playlist: SuitePlaylistStore.Playlist,
    ): List<String> {
        val paths = ArrayList<String>()
        for (e in playlist.entries) {
            if (GroupBlocks.isGroupBlock(e.filepath)) {
                paths.addAll(withContext(Dispatchers.IO) { GroupBlocks.resolve(context, e).paths })
            } else {
                paths.add(e.filepath)
            }
        }
        return paths
    }

    // ── The persisted per-playlist step state (v0.9.2) ──────────────────
    // Paths are joined with a newline separator (URIs can contain any
    // other character).

    private fun stepPrefs(context: Context) =
        context.getSharedPreferences("suite_rules_runtime", Context.MODE_PRIVATE)

    private fun readStepCursor(context: Context, playlistId: String): Int =
        stepPrefs(context).getInt("plcursor|$playlistId", 0)

    private fun readStepHistory(context: Context, playlistId: String): List<String> =
        stepPrefs(context).getString("plhistory|$playlistId", "")
            ?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    /** Persist the cursor + history after a successful step. */
    private fun recordStep(
        context: Context,
        playlistId: String,
        applied: String,
        paths: List<String>,
        dir: Int,
    ) {
        val prefs = stepPrefs(context)
        val history = readStepHistory(context, playlistId).toMutableList()
        if (dir < 0) {
            // Previous walked back — everything after the re-shown image
            // leaves the history so the NEXT Previous goes further back.
            val at = history.indexOfLast { it == applied }
            if (at >= 0) history.subList(at + 1, history.size).clear()
        } else {
            history.add(applied)
            while (history.size > 40) history.removeAt(0)
        }
        val idx = paths.indexOf(applied)
        prefs.edit()
            .putString("plhistory|$playlistId", history.joinToString("\n"))
            .apply()
        if (idx >= 0) {
            prefs.edit().putInt("plcursor|$playlistId", idx).apply()
        }
        // In-order mode: the cursor should point AT what's shown so the
        // next step advances past it.
        WepWawetLogger.appDebug("RuleEngine: step state persisted",
            "playlist" to playlistId,
            "cursor" to idx,
            "history" to history.size)
    }

    /**
     * Pop the next path from the persisted random bag — a shuffled copy of
     * the candidates minus the current image, refilled when exhausted (or
     * when the candidate membership changed since the bag was built).
     */
    private fun popBagNext(
        context: Context,
        playlistId: String,
        paths: List<String>,
        current: String?,
    ): String? {
        if (paths.isEmpty()) return null
        if (paths.size == 1) return paths[0]
        val prefs = stepPrefs(context)
        val membership = paths.joinToString(",").hashCode().toString()
        val bag = prefs.getString("plbag|$playlistId", "")
            ?.split('\n')?.filter { it.isNotBlank() && it in paths } ?: emptyList()
        val staleMembership = prefs.getString("plbagSig|$playlistId", "") != membership
        var pool = if (bag.isEmpty() || staleMembership) {
            (paths.filter { it != current }).shuffled(Random)
        } else bag.toMutableList()
        // The current image NEVER comes out of the bag.
        pool = pool.filter { it != current }.toMutableList()
        while (pool.isEmpty()) {
            pool = (paths.filter { it != current }).shuffled(Random).toMutableList()
            if (pool.isEmpty() && paths.isNotEmpty()) return paths[0]
        }
        val next = pool.removeAt(0)
        prefs.edit()
            .putString("plbag|$playlistId", pool.joinToString("\n"))
            .putString("plbagSig|$playlistId", membership)
            .apply()
        return next
    }

    // Recent picks ("avoid the most recently shown").

    private fun recentKey(playlistId: String) = "recentPicks|$playlistId"

    private fun readRecentPicks(context: Context, playlistId: String): Set<String> =
        context.getSharedPreferences("suite_rules_runtime", Context.MODE_PRIVATE)
            .getStringSet(recentKey(playlistId), emptySet()) ?: emptySet()

    private fun rememberRecentPick(context: Context, playlistId: String, path: String) {
        val prefs = context.getSharedPreferences("suite_rules_runtime", Context.MODE_PRIVATE)
        val recent = (prefs.getStringSet(recentKey(playlistId), emptySet()) ?: emptySet())
            .toMutableSet()
        recent.add(path)
        while (recent.size > 12) {
            recent.remove(recent.first())
        }
        prefs.edit().putStringSet(recentKey(playlistId), recent).apply()
    }

    // ── Save the last pick into a group's linked folder ──────────────────

    private suspend fun savePickToGroupFolder(
        context: Context,
        ctx: RunContext,
        b: RuleModel.Block,
        path: String,
    ) {
        val group = resolveRef(b.param, ctx)
        val link = com.wepwawet.ui.screens.LibraryPrefs.groupFolderOf(context, group)
        if (link == null) {
            WepWawetLogger.appWarn("$TAG: save-to-folder — group has no linked folder",
                "group" to group)
            return
        }
        withContext(Dispatchers.IO) {
            val bytes = try {
                when {
                    path.startsWith("content://") -> context.contentResolver
                        .openInputStream(android.net.Uri.parse(path))?.readBytes()
                    else -> java.io.File(path).readBytes()
                }
            } catch (e: Exception) {
                WepWawetLogger.appWarn("$TAG: save-to-folder read failed",
                    "exception" to e.javaClass.simpleName)
                null
            }
            if (bytes != null) {
                val bmp = com.wepwawet.discover.DiscoverApplier.decode(bytes, 2160)
                if (bmp != null) {
                    val ok = com.wepwawet.ui.screens.ImageFolderAssets
                        .saveImageToFolder(context, link, bmp)
                    WepWawetLogger.appInfo("$TAG: pick saved to group folder",
                        "group" to group, "ok" to ok)
                }
            }
        }
    }

    // ── Notifications ────────────────────────────────────────────────────

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIF_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(
                NOTIF_CHANNEL, "Rules",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Rule notifications and rule buttons" })
        }
    }

    fun notifyUser(context: Context, title: String, text: String) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notif = androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title.ifEmpty { "Rule" })
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(title.hashCode().mod(100000), notif)
        } catch (e: Exception) {
            WepWawetLogger.appWarn("$TAG: notify failed",
                "exception" to e.javaClass.simpleName)
        }
    }

    /** The notification-button rule: a persistent action button fires it. */
    fun buildRuleButtonNotification(context: Context) {
        val ref = SuiteRulesStore.notificationRule(context) ?: return
        val pr = SuiteRulesStore.readAll(context).firstOrNull { it.playlistId == ref.first }
            ?: return
        val rule = pr.rules.firstOrNull { it.id == ref.second } ?: return
        ensureChannel(context)
        val fireIntent = Intent("com.wepwawet.rules.FIRE_RULE")
            .setPackage(context.packageName)
            .putExtra("playlistId", ref.first)
            .putExtra("ruleId", ref.second)
        val pending = android.app.PendingIntent.getBroadcast(
            context, 9001, fireIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notif = androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Rule button")
            .setContentText("Run \"${rule.name}\"")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_play, "Run \"${rule.name}\"", pending)
            .build()
        try {
            manager.notify(9001, notif)
        } catch (e: Exception) {
            WepWawetLogger.appWarn("$TAG: rule-button notification failed",
                "exception" to e.javaClass.simpleName)
        }
    }
}
