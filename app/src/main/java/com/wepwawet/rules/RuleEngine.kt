package com.wepwawet.rules

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.wepwawet.WepWawetApplication
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
        /** v0.8.28: the running rule id (wait persistence keys on it). */
        val ruleId: String = "",
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

    /** v0.8.28: how many rule Discover picks stay on disk (newest kept). */
    private const val RULE_PICKS_LIMIT = 5

    /** v0.8.26: playlists already journaled as not-active this session. */
    private val notedInactivePlaylists = HashSet<String>()

    /** v0.8.27: rules already journaled as disabled / in-cooldown this
     *  session — with the always-armed broadcast family every power press
     *  delivers an event, and a per-event journal record per disabled or
     *  cooling rule flooded the journal (the same class as the 0.8.26
     *  not-active spam). The per-event DEBUG LOG line stays. */
    private val notedDisabledRules = HashSet<String>()
    private val notedCooldownRules = HashSet<String>()

    // ══ Event dispatch ═════════════════════════════════════════════════

    /**
     * Fire every rule triggered by [type]. The hub, app events, the QS tile
     * and the editor's play button all end up here. Time triggers arrive
     * as events too (the hub computes their wakes).
     */
    // ═══ v0.8.28: EVENT-RUN MACHINERY ═══════════════════════════════
    // The user's demands, made real:
    //  • rules of one playlist run ONE AT A TIME (a screen-on and an
    //    unlock arriving together used to run their rules concurrently —
    //    double picks, double applies);
    //  • the COOLDOWN is stamped when a run STARTS (it used to be
    //    recorded after the run finished, so a long run — a Wait block
    //    can hold one for ten minutes — let every new event start a
    //    duplicate);
    //  • same-type events arriving within a short window COALESCE into
    //    one run (OEMs burst USER_PRESENT + SCREEN_ON + SCREEN_OFF in
    //    quick succession on some unlock paths);
    //  • every decision is logged (matched / no match / cooldown /
    //    conditions / first-match-wins) — a log alone tells a broadcast
    //    that never arrived from a rule that did not run.

    /** One playlist's runs at a time, in arrival order. */
    private val playlistRunLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** The last run START per (playlist|event-type) — coalescing window. */
    private val lastEventRunAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Same-type events inside this window join the run already going. */
    private const val EVENT_COALESCE_MS = 750L

    private fun runLockFor(playlistId: String): Any =
        playlistRunLocks.computeIfAbsent(playlistId) { Any() }

    private suspend fun <T> withPlaylistRunLock(playlistId: String, block: suspend () -> T): T =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val lock = runLockFor(playlistId)
            val thread = Thread({
                synchronized(lock) {
                    // Resume the coroutine on its context with the result.
                    val result = runCatching {
                        kotlinx.coroutines.runBlocking { block() }
                    }
                    cont.resumeWith(result.mapCatching { it })
                }
            }, "rules-run-${playlistId.takeLast(6)}")
            thread.isDaemon = true
            thread.start()
        }

    /** True when a same-type run started within the coalescing window. */
    private fun coalescedRecently(playlistId: String, type: String, now: Long): Boolean {
        val key = "$playlistId|$type"
        val last = lastEventRunAt[key] ?: return false
        return now - last < EVENT_COALESCE_MS
    }

    private fun stampEventRun(playlistId: String, type: String, now: Long) {
        lastEventRunAt["$playlistId|$type"] = now
    }

    // ═══ v0.8.28: persistent waits across process death ══════════════
    // A Wait block used to hold a plain coroutine — lost when OnePlus
    // killed the process; the rest of the rule never ran. Now the
    // remaining wait is persisted at wait START; on the next process
    // start, [resumePendingWaits] re-runs the rule's REMAINING blocks
    // after the remaining time.

    private const val KEY_PENDING_WAITS = "pendingWaits"

    private fun runtimes(context: Context) =
        context.getSharedPreferences("suite_rules_runtime", Context.MODE_PRIVATE)

    private data class PendingWait(
        val playlistId: String,
        val ruleId: String,
        val blockId: String,
        val resumeAt: Long,
    )

    private fun readPendingWaits(context: Context): List<PendingWait> {
        val raw = runtimes(context).getString(KEY_PENDING_WAITS, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.optJSONObject(i) ?: return@List null
                PendingWait(
                    o.optString("playlist"), o.optString("rule"),
                    o.optString("block"), o.optLong("resumeAt"))
            }.filterNotNull()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writePendingWaits(context: Context, waits: List<PendingWait>) {
        val arr = org.json.JSONArray()
        for (w in waits) {
            arr.put(org.json.JSONObject()
                .put("playlist", w.playlistId)
                .put("rule", w.ruleId)
                .put("block", w.blockId)
                .put("resumeAt", w.resumeAt))
        }
        runtimes(context).edit().putString(KEY_PENDING_WAITS, arr.toString()).apply()
    }

    /** Record (or clear) the wait-survival entry for one running rule. */
    private fun noteWaitStarted(context: Context, playlistId: String, ruleId: String, blockId: String, resumeAt: Long) {
        val waits = readPendingWaits(context).filterNot {
            it.playlistId == playlistId && it.ruleId == ruleId
        } + PendingWait(playlistId, ruleId, blockId, resumeAt)
        writePendingWaits(context, waits)
    }

    private fun noteWaitCleared(context: Context, playlistId: String, ruleId: String) {
        val waits = readPendingWaits(context).filterNot {
            it.playlistId == playlistId && it.ruleId == ruleId
        }
        writePendingWaits(context, waits)
    }

    /**
     * Called once per process start (Application.onCreate): every wait
     * whose time passed while we were dead resumes NOW; the rest re-arm
     * a one-shot alarm for the remaining time.
     */
    fun resumePendingWaits(context: Context) {
        val waits = readPendingWaits(context)
        if (waits.isEmpty()) return
        val now = System.currentTimeMillis()
        val due = waits.filter { it.resumeAt <= now }
        val later = waits - due.toSet()
        writePendingWaits(context, later)
        for (w in due) {
            WepWawetLogger.appInfo("$TAG: resuming a wait that outlived the process",
                "playlist" to w.playlistId.takeLast(6),
                "rule" to w.ruleId.takeLast(6),
                "late_by_ms" to (now - w.resumeAt))
            WepWawetApplication.appScope.launch {
                fireRuleFromWait(context, w.playlistId, w.ruleId, w.blockId)
            }
        }
        for (w in later) {
            val remaining = (w.resumeAt - now).coerceAtLeast(1000L)
            WepWawetLogger.appInfo("$TAG: re-arming a wait for its remaining time",
                "playlist" to w.playlistId.takeLast(6),
                "remaining_ms" to remaining)
            val appCtx = context.applicationContext
            val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                appCtx, w.ruleId.hashCode(),
                Intent(appCtx, RuleAlarmReceiver::class.java)
                    .setAction(RuleAlarmReceiver.ACTION_RESUME_WAIT)
                    .putExtra("playlist", w.playlistId)
                    .putExtra("rule", w.ruleId)
                    .putExtra("block", w.blockId),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE)
            try {
                am?.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, w.resumeAt, pi)
            } catch (e: Exception) {
                WepWawetLogger.appWarn("$TAG: wait alarm set failed",
                    "exception" to e.javaClass.simpleName)
                am?.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, w.resumeAt, pi)
            }
        }
    }

    /** The alarm's target: resume one rule at its wait block. */
    suspend fun onWaitAlarm(context: Context, playlistId: String, ruleId: String, blockId: String) {
        val waits = readPendingWaits(context)
        val w = waits.firstOrNull {
            it.playlistId == playlistId && it.ruleId == ruleId && it.resumeAt <= System.currentTimeMillis()
        } ?: return
        writePendingWaits(context, waits - w)
        fireRuleFromWait(context, playlistId, ruleId, blockId)
    }

    private suspend fun fireRuleFromWait(
        context: Context, playlistId: String, ruleId: String, blockId: String,
    ) {
        val all = SuiteRulesStore.readAll(context)
        val pr = all.firstOrNull { it.playlistId == playlistId } ?: return
        val rule = pr.rules.firstOrNull { it.id == ruleId } ?: return
        val idx = rule.actions.indexOfFirst { it.id == blockId }
        if (idx < 0) return
        val remaining = rule.actions.drop(idx + 1)
        if (remaining.isEmpty()) return
        WepWawetLogger.appInfo("$TAG: wait resumed — running the remaining blocks",
            "rule" to rule.name, "blocks" to remaining.size)
        val ctx = RunContext(playlistId, manual = false, ruleId = ruleId)
        execBlocks(context, ctx, remaining, depth = 0)
        SuiteRulesStore.recordFire(context, playlistId, rule,
            "Resumed after a wait — ran ${remaining.size} block(s)")
    }

    suspend fun onEvent(context: Context, type: String, extra: Map<String, String> = emptyMap()) {
        val all = SuiteRulesStore.readAll(context)
        // v0.8.28: the decision log lives BEFORE any early return — an
        // event with no rule sets says so (a log alone must tell a
        // broadcast that never arrived from a rule that did not run).
        val activeId = if (all.isEmpty()) ""
        else SuitePlaylistStore.resolveActiveId(context)
        WepWawetLogger.appInfo("$TAG: event received",
            "event" to type,
            "rule_sets" to all.size,
            "active" to activeId.takeLast(6),
            "extra_keys" to extra.keys.joinToString(","))
        if (all.isEmpty()) return
        for (pr in all) {
            // v0.8.28: same-type events inside the window join the run in
            // progress instead of starting a duplicate.
            val nowMs = System.currentTimeMillis()
            if (coalescedRecently(pr.playlistId, type, nowMs)) {
                WepWawetLogger.appInfo("$TAG: event coalesced into the run in progress",
                    "event" to type, "playlist" to pr.playlistId.takeLast(6))
                continue
            }
            // v0.8.25 (ONLY THE INTENDED PLAYLIST RUNS): a playlist's rules
            // fire only while THAT playlist is the active one — rules edited
            // on a non-active playlist never rotate the wallpaper from
            // under the active playlist. Manual runs (▶, tile, notification
            // button) bypass onEvent and are unaffected.
            if (pr.playlistId != activeId) {
                val hasEnabled = pr.rules.any { it.enabled }
                if (hasEnabled) {
                    WepWawetLogger.appDebug("$TAG: rule set skipped — playlist not active",
                        "playlist" to pr.playlistId.takeLast(6),
                        "rules" to pr.rules.size)
                    // v0.8.26: journaled ONCE per playlist per session —
                    // the per-event record flooded the journal (and two
                    // playlists skipped by one event wrote two entries in
                    // the same millisecond, the exact pair that crashed
                    // the Activity screen's list keys).
                    if (notedInactivePlaylists.add(pr.playlistId)) {
                        RuleActivityLog.record(context, "skipped", pr.playlistId, "",
                            "This playlist's rules don't run — it isn't the active playlist. " +
                                "Set it active to let them fire.")
                    }
                }
                continue
            }
            val firedThisPlaylist = StringBuilder()
            withPlaylistRunLock(pr.playlistId) {
            stampEventRun(pr.playlistId, type, System.currentTimeMillis())
            for (rule in pr.rules) {
                if (!rule.enabled) {
                    WepWawetLogger.appDebug("$TAG: rule skipped — disabled",
                        "rule" to rule.name, "playlist" to pr.playlistId)
                    if (notedDisabledRules.add(rule.id)) {
                        RuleActivityLog.record(context, "skipped", pr.playlistId, rule.name,
                            "Rule is off — its triggers never fire. Turn it on in the editor.")
                    }
                    continue
                }
                if (!triggerMatches(context, rule, type, extra, pr.playlistId)) {
                    WepWawetLogger.appDebug("$TAG: rule skipped — no trigger match",
                        "rule" to rule.name, "event" to type)
                    continue
                }
                // Cooldown — v0.8.28: stamped at run START (below), so a
                // long-running rule can never be started twice by events
                // that arrive while it runs.
                if (rule.cooldownMinutes > 0 &&
                    System.currentTimeMillis() - rule.lastFiredAt <
                    rule.cooldownMinutes * 60_000L
                ) {
                    WepWawetLogger.appInfo("$TAG: rule in cooldown — skipped",
                        "rule" to rule.name, "cooldown_min" to rule.cooldownMinutes)
                    if (notedCooldownRules.add(rule.id)) {
                        RuleActivityLog.record(context, "skipped", pr.playlistId, rule.name,
                            "In cooldown (${rule.cooldownMinutes} min) — some events were skipped")
                    }
                    continue
                }
                // Gate.
                val gate = rule.gate
                if (gate != null && !evalCond(context, pr.playlistId, gate, RunContext(pr.playlistId, manual = false))) {
                    WepWawetLogger.appInfo("$TAG: gate blocked the rule",
                        "rule" to rule.name)
                    RuleActivityLog.record(context, "skipped", pr.playlistId, rule.name,
                        "Conditions not met (event: $type)")
                    continue
                }
                // v0.8.28: the cooldown is stamped the moment the run
                // starts, BEFORE the blocks execute.
                if (rule.cooldownMinutes > 0) {
                    SuiteRulesStore.stampLastFiredAt(context, pr.playlistId, rule)
                }
                WepWawetLogger.appInfo("$TAG: first match — running the rule",
                    "rule" to rule.name,
                    "mode" to pr.mode)
                val result = try {
                    fireRuleInternal(context, pr.playlistId, rule, manual = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Failed: ${e.message ?: e.javaClass.simpleName}"
                }
                firedThisPlaylist.append("${rule.name} → $result; ")
                // v0.8.28 (first-match-wins, FIXED): a rule whose actions
                // FAILED or did nothing no longer blocks the later rules —
                // only a rule that actually did its job wins the match.
                val didSomething = result.contains("applied") ||
                    result.contains("ran") && !result.contains("Failed")
                if (pr.mode != "all" && didSomething) break   // first match wins
                if (pr.mode != "all" && !didSomething) {
                    WepWawetLogger.appInfo("$TAG: matched rule did nothing — trying the next",
                        "rule" to rule.name)
                }
            }
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
        val ctx = RunContext(playlistId, manual, ruleId = rule.id,
            chooser = chooser, onHighlight = onHighlight)
        WepWawetLogger.appInfo("$TAG: rule fired",
            "rule" to rule.name,
            "playlist" to playlistId,
            "manual" to manual)
        RuleActivityLog.record(context, "fired", playlistId, rule.name,
            if (manual) "Run by hand" else "Triggered")
        val flow = execBlocks(context, ctx, rule.actions, depth)
        val result = buildString {
            append(if (ctx.lastPickOk) "applied a wallpaper" else "ran ${rule.actions.size} block(s)")
            if (flow == Flow.STOPPED) append(" (stopped early)")
            // v0.9.2: the honest reason a pick didn't apply.
            ctx.blockReason?.let { append(" — $it") }
            ctx.value("last")?.let { append(" — ${it.substringAfterLast('/')}") }
        }
        SuiteRulesStore.recordFire(context, playlistId, rule, result)
        RuleActivityLog.record(context,
            if (ctx.lastPickOk) "applied" else "info",
            playlistId, rule.name, result)
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
        RuleModel.COND_DAY_OF_WEEK -> {
            // v0.8.25 (THE MONDAY-TESTS-SUNDAY FIX): the editor's picker is
            // Mon..Sun (0..6) — same convention as the day-of-week TRIGGER
            // (fixed for the trigger in 0.9.2's audit) — but this condition
            // compared against `Calendar.DAY_OF_WEEK - 1`, which is 0=SUNDAY.
            // A "Monday" condition therefore evaluated on SUNDAYS. Map the
            // user's Mon-first index to the explicit Calendar constant.
            if (cond.dayOfWeek < 0) true
            else intArrayOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY,
                Calendar.SUNDAY,
            )[cond.dayOfWeek.coerceIn(0, 6)] ==
                Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        }
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
                    // v0.8.26: the target is param3 (same as every other
                    // pick block) — param2 used to be read as the TARGET
                    // here and as the pick MODE inside applyPick at the
                    // same time.
                    applyPick(context, ctx, b, paths, resolvedTarget(context, b.param3))
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
                    // v0.8.28 (THE WAIT THAT SURVIVES DEATH): the remaining
                    // wait is PERSISTED at wait start (resumeAt); if the
                    // process dies, resumePendingWaits/onWaitAlarm re-runs
                    // the rule's REMAINING blocks after the remaining time.
                    val ms = b.num.coerceIn(1, 600).coerceAtMost(600) * 1000L
                    val resumeAt = System.currentTimeMillis() + ms
                    noteWaitStarted(context, ctx.playlistId, ctx.ruleId, b.id, resumeAt)
                    try {
                        delay(ms)
                    } finally {
                        noteWaitCleared(context, ctx.playlistId, ctx.ruleId)
                    }
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
            // v0.8.26: an honest result — a failed block used to leave
            // "ran 1 block(s)" standing while the wallpaper never changed
            // (the 0.8.25 screen-on crash read exactly like that).
            ctx.lastPickOk = false
            ctx.blockReason = "block " + b.type + " failed: " +
                (e.message ?: e.javaClass.simpleName)
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
                                // v0.8.26: a group with no block entry
                                // resolves in its natural order — the pick
                                // is random unless a block entry says
                                // otherwise (tapping the group sets it).
                                blockOrder = GroupBlocks.ORDER_RANDOM,
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
                "group" -> candidatesFor(context, ctx, b.copy(type = RuleModel.BLOCK_PICK_GROUP))
                "playlist" -> candidatesFor(context, ctx, b.copy(type = RuleModel.BLOCK_PICK_PLAYLIST))
                "discover" -> {
                    // v0.8.28: the PRELOADED Discover queue first — a
                    // screen-on or unlock rotation pops an already-
                    // downloaded image (instant, offline-safe). The live
                    // search + download only runs when the queue is empty.
                    val signature = com.wepwawet.discover.DiscoverPrefs.searchSignature(
                        com.wepwawet.discover.DiscoverPrefs.readStrictTags(context),
                        com.wepwawet.discover.DiscoverPrefs.readSoftTags(context),
                        com.wepwawet.discover.DiscoverPrefs.readStyle(context),
                        com.wepwawet.discover.DiscoverPrefs.readR18(context),
                        com.wepwawet.discover.DiscoverPrefs.readSources(context))
                    val queued = withContext(Dispatchers.IO) {
                        com.wepwawet.discover.DiscoverPrefs.consumeQueuedImage(context, signature)
                    }
                    if (queued != null) {
                        val (qBytes, qUrl) = queued
                        WepWawetLogger.appInfo("$TAG: discover pick from the preloaded queue",
                            "bytes" to qBytes.size)
                        // Keep the queue full for the NEXT pick.
                        com.wepwawet.WepWawetApplication.appScope.launch {
                            runCatching { com.wepwawet.discover.DiscoverPreload.kick(context) }
                        }
                        val f = writeRulePick(context, qBytes)
                        if (f != null) listOf(f) else emptyList()
                    } else {
                        // Queue empty — the live path (slow/offline may fail
                        // honestly here).
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
                            runCatching {
                                com.wepwawet.discover.DiscoverSearch.downloadImage(url)
                            }.onFailure {
                                WepWawetLogger.appWarn("$TAG: discover download failed",
                                    "exception" to it.javaClass.simpleName)
                            }.getOrNull()
                        } else null
                        if (bytes != null && url != null) {
                            com.wepwawet.discover.DiscoverPrefs.pushRecentUrl(context, url)
                            val f = writeRulePick(context, bytes)
                            if (f != null) listOf(f) else emptyList()
                        } else emptyList()
                    }
                }
                else -> withContext(Dispatchers.IO) {
                    com.wepwawet.engine.EngineLibrary.enumerateWallpapers(context)
                        .map { it.filePath }
                }
            }
            else -> emptyList()
        }
        // v0.8.26: no pick-mode re-sorting — the candidate list arrives in
        // the source's OWN order (the group's block order / the playlist's
        // entry order), which is the single order source now.
    }



    /**
     * v0.8.28: write a rule Discover pick into filesDir/rule_picks, keeping
     * only the newest [RULE_PICKS_LIMIT] files — the old path wrote a full
     * image per pick and NEVER deleted any (storage grew forever).
     */
    private fun writeRulePick(context: Context, bytes: ByteArray): String? {
        return try {
            val dir = java.io.File(context.filesDir, "rule_picks").apply { mkdirs() }
            val f = java.io.File(dir, "pick_${System.currentTimeMillis()}.img")
            f.writeBytes(bytes)
            // v0.8.28: the ring keeps only the newest picks, but NEVER
            // deletes a file a screen selection, playlist, or rule still
            // references — a screen can hold an older pick for a long
            // time while the other screen's applies push it out of the
            // ring (the deleted file broke the next screen-on load).
            dir.listFiles()
                ?.sortedBy { it.name }
                ?.dropLast(RULE_PICKS_LIMIT)
                ?.forEach { candidate ->
                    if (com.wepwawet.wallpaper.WallpaperRouter
                            .isPathReferenced(context, candidate.absolutePath)
                    ) {
                        WepWawetLogger.appInfo("$TAG: rule_picks ring kept a REFERENCED file",
                            "file" to candidate.name)
                        return@forEach
                    }
                    runCatching { candidate.delete() }
                }
            f.absolutePath
        } catch (e: Exception) {
            WepWawetLogger.appWarn("$TAG: rule pick write failed",
                "exception" to e.javaClass.simpleName)
            null
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
        // v0.8.26 (ONE ORDER SOURCE): the pick mode is DERIVED from the
        // source's own configured order — the group's block order (set by
        // tapping the group in the playlist) or the playlist's order mode.
        // The per-block "How to pick" override is gone: it duplicated the
        // group's Random setting (confusing), and for "show a set" the
        // param2 slot collided with the TARGET ("home"/"lock").
        val mode = when (b.type) {
            RuleModel.BLOCK_PICK_GROUP -> {
                val group = resolveRef(b.param, ctx)
                val playlist = SuitePlaylistStore.read(context, ctx.playlistId)
                val entry = playlist?.entries?.firstOrNull {
                    com.wepwawet.playlist.GroupBlocks.isGroupBlock(it.filepath) &&
                        it.groupRef == group
                }
                val order = entry?.blockOrder ?: com.wepwawet.playlist.GroupBlocks.ORDER_RANDOM
                if (order == com.wepwawet.playlist.GroupBlocks.ORDER_RANDOM) "random" else "inOrder"
            }
            RuleModel.BLOCK_PICK_PLAYLIST -> {
                val pl = SuitePlaylistStore.read(context, b.param.ifEmpty { ctx.playlistId })
                if (pl?.orderMode == SuitePlaylistStore.ORDER_ORDERED) "inOrder" else "random"
            }
            else -> "random"
        }
        val current = WEWallpaperService.instance?.currentWallpaperFile()
        var picked = pickFrom(candidates, mode, current, context, ctx, b.id)
        when (resolvedTarget(context, target)) {
            "bothDifferent" -> {
                // The lock-screen second pick is random — the cursor must
                // advance once per rule run, not twice.
                val second = pickFrom(candidates.filter { it != picked }, "random", current, context, ctx, b.id)
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
        // v0.8.28: the saved rule's own target wins, with the LEGACY
        // bothDifferent folded into "both" (dual mode is gone — the
        // toggles are the single screen model now).
        if (target.isNotEmpty()) return if (target == "bothDifferent") "both" else target
        val screens = com.wepwawet.discover.DiscoverPrefs.readScreens(context)
        val home = com.wepwawet.discover.DiscoverPrefs.homeRequired(screens.first)
        val lock = com.wepwawet.discover.DiscoverPrefs.lockRequired(screens.second)
        return when {
            home && lock -> "both"
            home -> "home"
            lock -> "lock"
            // v0.8.28: both toggles off → NOTHING changes. The old code
            // applied to both screens here — exactly backwards. A manual
            // run gets the honest reason ("both screens are switched
            // off") instead of an unwanted apply.
            else -> "none"
        }
    }

    private fun pickFrom(
        candidates: List<String>,
        mode: String,
        current: String?,
        context: Context,
        ctx: RunContext,
        blockId: String,
    ): String {
        val pool = candidates.filter { it != current }.ifEmpty { candidates }
        return when (mode) {
            // v0.8.25 (THE IN-ORDER OSCILLATION FIX): a PERSISTED per-block
            // cursor advances +1 with wrap over the ordered candidates —
            // the old `pool[0]` (first non-current) meant every run picked
            // the first entry after the current one, bouncing between the
            // first two forever.
            "inOrder", "newest" -> {
                val key = "pickcursor|${ctx.playlistId}|$blockId"
                val prefs = stepPrefs(context)
                var idx = (prefs.getInt(key, -1) + 1).mod(candidates.size)
                if (candidates[idx] == current && candidates.size > 1) {
                    idx = (idx + 1).mod(candidates.size)
                }
                prefs.edit().putInt(key, idx).apply()
                candidates[idx]
            }
            "avoidRecent" -> {
                val recent = readRecentPicks(context, ctx.playlistId)
                pool.firstOrNull { it !in recent } ?: pool[Random.nextInt(pool.size)]
            }
            else -> pool[Random.nextInt(pool.size)]
        }
    }

    /**
     * Apply one picked path to the target screen(s) — THE ONE PATH.
     *
     * v0.8.28 (research-corrected): the old home path silently "succeeded"
     * the moment it poked the service (a null-safe no-op when no engine
     * was attached), and the lock path called WallpaperManager.setBitmap
     * — every setBitmap installs a NEW system wallpaper (a new
     * WallpaperManager ID — that is what filled the OnePlus "Wallpaper &
     * style" list with entries) and REPLACES the live wallpaper, which
     * stops the playlist and rules until the user sets the live wallpaper
     * again.
     *
     * Everything routes through [com.wepwawet.wallpaper.WallpaperRouter]
     * now: the per-screen selections are written (an OFF screen is never
     * touched), the engines of the ON screens load their selection, and
     * success is ENGINE-CONFIRMED — an apply only reports OK when a
     * living engine actually loaded the file. The lock screen is served
     * by the app's own live wallpaper (it CAN own the lock screen on
     * current Android — Engine.getWallpaperFlags() tells each engine
     * which screen it renders; the old "a third-party live wallpaper can
     * never own the lock screen" claim was wrong).
     */
    private suspend fun applyImagePath(
        context: Context,
        ctx: RunContext,
        b: RuleModel.Block,
        path: String,
        target: String,
    ) {
        ctx.blockReason = null
        val outcome = com.wepwawet.wallpaper.WallpaperRouter.applyPath(
            context, path, target)
        val ok = outcome.allOk
        ctx.lastPickOk = ok
        if (ok) {
            ctx.values["last"] = path
            ctx.values["block:${b.id}"] = path
            rememberRecentPick(context, ctx.playlistId, path)
            RuleActivityLog.record(context, "applied", ctx.playlistId, "",
                "Wallpaper applied — ${path.substringAfterLast('/').take(48)} (${outcome.describe()})")
            // v0.9.0 (rules): the "the wallpaper changed" trigger — our own
            // apply (the system broadcast covers manual changes). The hub
            // suppresses the SYSTEM broadcast that follows our own apply.
            RuleTriggerHub.noteSelfWallpaperApply()
            RuleEvents.post(context, RuleModel.TRIG_WALLPAPER_CHANGED)
        } else {
            ctx.blockReason = outcome.reason.ifEmpty { "no engine showed the wallpaper" }
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
            RuleActivityLog.record(context, "skipped", ctx.playlistId, "",
                "$stepName wallpaper skipped — $reason")
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
