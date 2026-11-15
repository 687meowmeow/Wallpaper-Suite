package com.wepwawet.rules

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import org.json.JSONObject
import java.io.File

/**
 * v0.9.0: persistence for the rules engine — one JSON file
 * (filesDir/suite_rules.json):
 *
 *  • per-playlist rule sets (mode + the ordered rules) — the rules live
 *    INSIDE their playlist ("a rules section inside each playlist");
 *  • named values shared across rules and playlists (a Night-mode flag, a
 *    counter, a number…) with set/add/clear and comparisons;
 *  • saved engine property presets (name → {wallpaper, properties JSON})
 *    applied by the "apply preset" engine block;
 *  • the Quick-Settings-tile pinned rule + the notification-button rule;
 *  • last-fired bookkeeping (when each rule fired + what it did).
 *
 * org.json throughout — same rationale as RuleModel (no reflection).
 */
object SuiteRulesStore {

    private const val FILE = "suite_rules.json"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_VALUES = "values"
    private const val KEY_PRESETS = "presets"
    private const val KEY_PINNED = "pinned"     // {playlistId, ruleId}
    private const val KEY_NOTIF = "notifRule"   // {playlistId, ruleId}
    private const val KEY_LAST_FIRED = "lastFired"  // "playlistId|ruleId" → {at, result}

    /** A named value: boolean / number / string. */
    data class NamedValue(
        var kind: String = "string",   // "bool" | "number" | "string"
        var boolValue: Boolean = false,
        var numValue: Long = 0,
        var strValue: String = "",
    )

    /** A saved engine property preset (applied via applyWallpaperProperties). */
    data class Preset(
        var name: String,
        var wallpaperPath: String,
        var propertiesJson: String,
    )

    private fun file(context: Context): File = File(context.filesDir, FILE)

    // ═══ v0.10.0: DATA SAFETY ═════════════════════════════════════════
    // The old writeRoot did a plain writeText of the whole file with no
    // lock and no atomicity: every rule firing (recordFire), every named
    // value change, and every editor save read the file, changed one
    // part, and wrote the WHOLE file back from different coroutines — a
    // rule firing while the user edits overwrote the edit, and a crash
    // mid-write corrupted the file (then quarantined → "my rules are
    // gone").
    //
    // Now: ALL writes go through [mutate] — ONE lock, read-modify-write
    // under it, then an ATOMIC temp-file + fsync + rename (a crash leaves
    // either the old file or the new one, never a half-written one).

    private val writeLock = Any()

    /**
     * The single writer: mutate the root under one lock and persist
     * atomically. Every mutation API (rules, values, presets, pins,
     * last-fired, wait bookkeeping) funnels through here.
     */
    fun mutate(context: Context, block: (JSONObject) -> Unit) {
        synchronized(writeLock) {
            val root = readRoot(context)
            block(root)
            writeRootAtomic(context, root)
        }
    }

    /** Atomic write: temp file → fsync → rename over the real file. */
    private fun writeRootAtomic(context: Context, root: JSONObject) {
        val f = file(context)
        val tmp = File(context.filesDir, "$FILE.tmp-$${System.currentTimeMillis()}")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                // Cross-filesystem or busy — stream-copy then delete.
                f.outputStream().use { tmp.inputStream().copyTo(it) }
                tmp.delete()
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            WepWawetLogger.appError("SuiteRulesStore: atomic write failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }

    @Synchronized
    private fun readRoot(context: Context): JSONObject {
        val f = file(context)
        if (!f.isFile) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            // v0.8.25 (audit): a read failure must NOT return an EMPTY root —
            // the next write would then wipe every rule the user built. The
            // unreadable file is quarantined (timestamped .corrupt backup)
            // and the failure is LOUD.
            val stamp = System.currentTimeMillis()
            runCatching {
                file(context).copyTo(File(context.filesDir, "suite_rules.json.corrupt-$stamp"),
                    overwrite = true)
            }
            runCatching { file(context).delete() }
            WepWawetLogger.appWarn("SuiteRulesStore: read failed — file quarantined",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
                "backup" to "suite_rules.json.corrupt-$stamp")
            RuleActivityLog.record(context, "error", "", "",
                "Rules file unreadable — quarantined as suite_rules.json.corrupt-$stamp")
            JSONObject()
        }
    }



    // ── Per-playlist rule sets ─────────────────────────────────────────

    fun readPlaylistRules(context: Context, playlistId: String): RuleModel.PlaylistRules {
        val root = readRoot(context)
        val o = root.optJSONObject(KEY_PLAYLISTS)?.optJSONObject(playlistId)
            ?: return RuleModel.PlaylistRules(playlistId = playlistId)
        return RuleModel.playlistRulesFromJson(playlistId, o)
    }

    fun writePlaylistRules(context: Context, pr: RuleModel.PlaylistRules) {
        mutate(context) { root ->
            val all = root.optJSONObject(KEY_PLAYLISTS) ?: JSONObject().also {
                root.put(KEY_PLAYLISTS, it)
            }
            all.put(pr.playlistId, RuleModel.playlistRulesToJson(pr))
        }
        WepWawetLogger.appInfo("SuiteRulesStore: rules saved",
            "playlist" to pr.playlistId,
            "rules" to pr.rules.size,
            "mode" to pr.mode)
        // The trigger hub re-aggregates its registrations on change.
        RuleTriggerHub.onRulesChanged(context)
        // v0.9.0 redesign: editing a rule never rotates the wallpaper —
        // but a scope change CAN change which wallpapers are allowed, so
        // the service re-checks the scopes (it only switches if the
        // current wallpaper just left the allowed set).
        io.wallpaperengine.weclient.WEWallpaperService.instance
            ?.requestScopeReevaluation()
    }

    fun deletePlaylistRules(context: Context, playlistId: String) {
        mutate(context) { root ->
            root.optJSONObject(KEY_PLAYLISTS)?.remove(playlistId)
        }
        RuleTriggerHub.onRulesChanged(context)
    }

    /** Every playlist's rules — the event dispatch reads these. */
    fun readAll(context: Context): List<RuleModel.PlaylistRules> {
        val root = readRoot(context)
        val all = root.optJSONObject(KEY_PLAYLISTS) ?: return emptyList()
        val out = ArrayList<RuleModel.PlaylistRules>()
        for (key in all.keys()) {
            val o = all.optJSONObject(key) ?: continue
            out.add(RuleModel.playlistRulesFromJson(key, o))
        }
        return out
    }

    /** All trigger types used across every playlist's rules (hub registration). */
    fun usedTriggerTypes(context: Context): Set<String> {
        val types = HashSet<String>()
        for (pr in readAll(context)) {
            for (rule in pr.rules) {
                if (!rule.enabled) continue
                for (t in rule.triggers) types.add(t.type)
            }
        }
        return types
    }

    // ── Named values ───────────────────────────────────────────────────

    fun readValues(context: Context): Map<String, NamedValue> {
        val root = readRoot(context)
        val o = root.optJSONObject(KEY_VALUES) ?: return emptyMap()
        val out = LinkedHashMap<String, NamedValue>()
        for (key in o.keys()) {
            val v = o.optJSONObject(key) ?: continue
            out[key] = NamedValue(
                kind = v.optString("kind", "string"),
                boolValue = v.optBoolean("bool", false),
                numValue = v.optLong("num", 0),
                strValue = v.optString("str", ""),
            )
        }
        return out
    }

    fun readValue(context: Context, name: String): NamedValue? =
        readValues(context)[name]

    fun setValue(context: Context, name: String, value: NamedValue) {
        mutate(context) { root ->
            val all = root.optJSONObject(KEY_VALUES) ?: JSONObject().also {
                root.put(KEY_VALUES, it)
            }
            all.put(name, JSONObject()
                .put("kind", value.kind)
                .put("bool", value.boolValue)
                .put("num", value.numValue)
                .put("str", value.strValue))
        }
        WepWawetLogger.appInfo("SuiteRulesStore: value set",
            "name" to name, "kind" to value.kind)
    }

    /** addValue: numbers add, counters increment, strings append nothing. */
    fun addValue(context: Context, name: String, delta: Long) {
        val v = readValue(context, name) ?: NamedValue(kind = "number")
        when (v.kind) {
            "number" -> v.numValue += delta
            "bool" -> v.boolValue = delta != 0L
            else -> v.strValue = (v.strValue + delta)
        }
        setValue(context, name, v)
    }

    fun clearValue(context: Context, name: String) {
        mutate(context) { root ->
            root.optJSONObject(KEY_VALUES)?.remove(name)
        }
        WepWawetLogger.appInfo("SuiteRulesStore: value cleared", "name" to name)
    }

    // ── Property presets ───────────────────────────────────────────────

    fun readPresets(context: Context): List<Preset> {
        val root = readRoot(context)
        val o = root.optJSONObject(KEY_PRESETS) ?: return emptyList()
        val out = ArrayList<Preset>()
        for (key in o.keys()) {
            val p = o.optJSONObject(key) ?: continue
            out.add(Preset(
                name = key,
                wallpaperPath = p.optString("wallpaper", ""),
                propertiesJson = p.optString("properties", "{}"),
            ))
        }
        return out
    }

    fun savePreset(context: Context, preset: Preset) {
        mutate(context) { root ->
            val all = root.optJSONObject(KEY_PRESETS) ?: JSONObject().also {
                root.put(KEY_PRESETS, it)
            }
            all.put(preset.name, JSONObject()
                .put("wallpaper", preset.wallpaperPath)
                .put("properties", preset.propertiesJson))
        }
        WepWawetLogger.appInfo("SuiteRulesStore: preset saved", "name" to preset.name)
    }

    fun deletePreset(context: Context, name: String) {
        mutate(context) { root ->
            root.optJSONObject(KEY_PRESETS)?.remove(name)
        }
    }

    // ── Pinned (QS tile) + notification-button rule ─────────────────────

    private fun readRuleRef(context: Context, key: String): Pair<String, String>? {
        val o = readRoot(context).optJSONObject(key) ?: return null
        val pl = o.optString("playlistId", "")
        val r = o.optString("ruleId", "")
        if (pl.isEmpty() || r.isEmpty()) return null
        return pl to r
    }

    private fun writeRuleRef(context: Context, key: String, ref: Pair<String, String>?) {
        mutate(context) { root ->
            if (ref == null) {
                root.remove(key)
            } else {
                root.put(key, JSONObject()
                    .put("playlistId", ref.first)
                    .put("ruleId", ref.second))
            }
        }
    }

    fun pinRuleForTile(context: Context, playlistId: String?, ruleId: String?) {
        writeRuleRef(context, KEY_PINNED,
            if (playlistId != null && ruleId != null) playlistId to ruleId else null)
    }

    fun pinnedRuleForTile(context: Context): Pair<String, String>? =
        readRuleRef(context, KEY_PINNED)

    fun setNotificationRule(context: Context, playlistId: String?, ruleId: String?) {
        writeRuleRef(context, KEY_NOTIF,
            if (playlistId != null && ruleId != null) playlistId to ruleId else null)
    }

    fun notificationRule(context: Context): Pair<String, String>? =
        readRuleRef(context, KEY_NOTIF)

    // ── Last-fired bookkeeping ──────────────────────────────────────────

    fun recordFire(context: Context, playlistId: String, rule: RuleModel.Rule, result: String) {
        rule.lastFiredAt = System.currentTimeMillis()
        rule.lastResult = result
        // Persist both on the rule object in the store AND in the flat map
        // the editor reads cheaply — through the single writer.
        mutate(context) { root ->
            val all = root.optJSONObject(KEY_PLAYLISTS) ?: JSONObject().also {
                root.put(KEY_PLAYLISTS, it)
            }
            all.optJSONObject(playlistId)?.let { po ->
                val rules = po.optJSONArray("rules") ?: return@let
                for (i in 0 until rules.length()) {
                    val o = rules.optJSONObject(i) ?: continue
                    if (o.optString("id") == rule.id) {
                        o.put("lastFiredAt", rule.lastFiredAt)
                        o.put("lastResult", result)
                        break
                    }
                }
            }
            val fired = root.optJSONObject(KEY_LAST_FIRED) ?: JSONObject().also {
                root.put(KEY_LAST_FIRED, it)
            }
            fired.put("$playlistId|${rule.id}", JSONObject()
                .put("at", rule.lastFiredAt)
                .put("result", result))
        }
    }

    /**
     * v0.10.0: stamp the cooldown the moment a run STARTS (recordFire used
     * to be the only stamp — written AFTER the run finished, so a long run
     * (a Wait block can hold one for ten minutes) let every arriving event
     * start a duplicate). The final recordFire still writes the result.
     */
    fun stampLastFiredAt(context: Context, playlistId: String, rule: RuleModel.Rule) {
        rule.lastFiredAt = System.currentTimeMillis()
        mutate(context) { root ->
            val all = root.optJSONObject(KEY_PLAYLISTS) ?: return@mutate
            val po = all.optJSONObject(playlistId) ?: return@mutate
            val rules = po.optJSONArray("rules") ?: return@mutate
            for (i in 0 until rules.length()) {
                val o = rules.optJSONObject(i) ?: continue
                if (o.optString("id") == rule.id) {
                    o.put("lastFiredAt", rule.lastFiredAt)
                    break
                }
            }
        }
    }
}
