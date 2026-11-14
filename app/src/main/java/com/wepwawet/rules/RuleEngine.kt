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
        for (pr in all) {
            val firedThisPlaylist = StringBuilder()
            for (rule in pr.rules) {
                if (!rule.enabled) continue
                if (!triggerMatches(context, rule, type, extra, pr.playlistId)) continue
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
                    applyPick(context, ctx, b, listOf(path), b.param3.ifEmpty { "home" })
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_SHOW_SET -> {
                    val paths = b.paths.map { resolveRef(it, ctx) }.filter { it.isNotEmpty() }
                    applyPick(context, ctx, b, paths, b.param2.ifEmpty { "home" })
                    Flow.CONTINUE
                }
                RuleModel.BLOCK_PICK_GROUP, RuleModel.BLOCK_PICK_PLAYLIST, RuleModel.BLOCK_RANDOM -> {
                    val candidates = candidatesFor(context, ctx, b)
                    applyPick(context, ctx, b, candidates, b.param2.ifEmpty { "home" })
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
                            applyImagePath(context, ctx, b, path, b.param2.ifEmpty { "home" })
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
                        runCatching { com.wepwawet.discover.DiscoverSearch.downloadImage(url) }
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
            ctx.lastPickOk = false
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
        when (target) {
            "bothDifferent" -> {
                val second = pickFrom(candidates.filter { it != picked }, mode, current, context, ctx)
                if (second != null && second != picked) {
                    applyImagePath(context, ctx, b, picked, "home")
                    applyImagePath(context, ctx, b, second, "lock")
                } else {
                    applyImagePath(context, ctx, b, picked, "both")
                }
            }
            else -> applyImagePath(context, ctx, b, picked, target)
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

    /** Apply one picked path to the target screen(s). */
    private suspend fun applyImagePath(
        context: Context,
        ctx: RunContext,
        b: RuleModel.Block,
        path: String,
        target: String,
    ) {
        val ok = withContext(Dispatchers.IO) {
            if (path.startsWith("content://") ||
                listOf(".jpg", ".jpeg", ".png", ".webp", ".img", ".gif").any {
                    path.lowercase().endsWith(it)
                } || !path.contains(".mpkg")
            ) {
                // A static image: apply through the system wallpaper path
                // (crop-free — the rule picked exactly what it wanted).
                val bmp = com.wepwawet.discover.DiscoverApplier
                    .decode(java.io.File(path).readBytes(), 2160)
                if (bmp != null) {
                    val home = target == "home" || target == "both"
                    val lock = target == "lock" || target == "both"
                    com.wepwawet.discover.DiscoverApplier.apply(context, bmp, home, lock).allOk
                } else false
            } else {
                // An engine wallpaper: the WE service loads it.
                val svc = WEWallpaperService.instance
                if (svc != null) {
                    io.wallpaperengine.weclient.WeEnginePrefs.storeString(
                        context, io.wallpaperengine.weclient.WeEnginePrefs
                            .PREF_KEY_SELECTED_WALLPAPER, path)
                    svc.requestWallpaperReload()
                    true
                } else false
            }
        }
        ctx.lastPickOk = ok
        if (ok) {
            ctx.values["last"] = path
            ctx.values["block:${b.id}"] = path
            rememberRecentPick(context, ctx.playlistId, path)
            WepWawetLogger.appInfo("$TAG: wallpaper applied",
                "path" to path.takeLast(56),
                "target" to target)
            // v0.9.0 (rules): the "the wallpaper changed" trigger — our own
            // apply (the system broadcast covers manual changes).
            RuleEvents.post(context, RuleModel.TRIG_WALLPAPER_CHANGED)
        }
    }

    /** next / previous / shuffle relative to the playlist's rotation order. */
    private suspend fun stepPlaylist(
        context: Context,
        ctx: RunContext,
        b: RuleModel.Block,
        dir: Int,
    ) {
        val playlist = SuitePlaylistStore.read(context, ctx.playlistId) ?: return
        val paths = ArrayList<String>()
        for (e in playlist.entries) {
            if (GroupBlocks.isGroupBlock(e.filepath)) {
                paths.addAll(withContext(Dispatchers.IO) { GroupBlocks.resolve(context, e).paths })
            } else if (!RandomPullEngine.isSlot(e.filepath)) {
                paths.add(e.filepath)
            }
        }
        if (paths.isEmpty()) return
        val current = WEWallpaperService.instance?.currentWallpaperFile()
        val idx = current?.let { paths.indexOf(it) } ?: -1
        val picked = when {
            dir > 0 -> paths[(idx + 1).coerceAtLeast(0).mod(paths.size)]
            dir < 0 -> paths[(idx - 1 + paths.size).mod(paths.size)]
            else -> paths[Random.nextInt(paths.size)]
        }
        applyImagePath(context, ctx, b, picked, b.param2.ifEmpty { "home" })
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
