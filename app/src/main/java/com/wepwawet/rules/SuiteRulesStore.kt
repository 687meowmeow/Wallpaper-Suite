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

    @Synchronized
    private fun readRoot(context: Context): JSONObject {
        val f = file(context)
        if (!f.isFile) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            // v0.9.0: a corrupt store is logged, never silently reset — the
            // rules are user-built data; a WARN with the reason makes a
            // hand-recoverable backup of the file possible.
            WepWawetLogger.appWarn("SuiteRulesStore: read failed — starting fresh",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            JSONObject()
        }
    }

    @Synchronized
    private fun writeRoot(context: Context, root: JSONObject) {
        try {
            file(context).writeText(root.toString())
        } catch (e: Exception) {
            WepWawetLogger.appError("SuiteRulesStore: write failed",
                "exception" to e.javaClass.name)
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
        val root = readRoot(context)
        val all = root.optJSONObject(KEY_PLAYLISTS) ?: JSONObject().also {
            root.put(KEY_PLAYLISTS, it)
        }
        all.put(pr.playlistId, RuleModel.playlistRulesToJson(pr))
        writeRoot(context, root)
        WepWawetLogger.appInfo("SuiteRulesStore: rules saved",
            "playlist" to pr.playlistId,
            "rules" to pr.rules.size,
            "mode" to pr.mode)
        // The trigger hub re-aggregates its registrations on change.
        RuleTriggerHub.onRulesChanged(context)
    }

    fun deletePlaylistRules(context: Context, playlistId: String) {
        val root = readRoot(context)
        root.optJSONObject(KEY_PLAYLISTS)?.remove(playlistId)
        writeRoot(context, root)
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
        val root = readRoot(context)
        val all = root.optJSONObject(KEY_VALUES) ?: JSONObject().also {
            root.put(KEY_VALUES, it)
        }
        all.put(name, JSONObject()
            .put("kind", value.kind)
            .put("bool", value.boolValue)
            .put("num", value.numValue)
            .put("str", value.strValue))
        writeRoot(context, root)
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
        val root = readRoot(context)
        root.optJSONObject(KEY_VALUES)?.remove(name)
        writeRoot(context, root)
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
        val root = readRoot(context)
        val all = root.optJSONObject(KEY_PRESETS) ?: JSONObject().also {
            root.put(KEY_PRESETS, it)
        }
        all.put(preset.name, JSONObject()
            .put("wallpaper", preset.wallpaperPath)
            .put("properties", preset.propertiesJson))
        writeRoot(context, root)
        WepWawetLogger.appInfo("SuiteRulesStore: preset saved", "name" to preset.name)
    }

    fun deletePreset(context: Context, name: String) {
        val root = readRoot(context)
        root.optJSONObject(KEY_PRESETS)?.remove(name)
        writeRoot(context, root)
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
        val root = readRoot(context)
        if (ref == null) {
            root.remove(key)
        } else {
            root.put(key, JSONObject()
                .put("playlistId", ref.first)
                .put("ruleId", ref.second))
        }
        writeRoot(context, root)
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
        // the editor reads cheaply.
        val root = readRoot(context)
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
        writeRoot(context, root)
    }
}
