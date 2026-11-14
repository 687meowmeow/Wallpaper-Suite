package com.wepwawet.rules

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.8.25: THE ACTIVITY JOURNAL — a small persisted ring buffer of recent
 * playlist-system events, shown in-app (PlaylistActivityScreen) so "why
 * did/didn't my wallpaper change" never requires pulling a logcat.
 *
 * Entries are appended by the trigger hub, the rules engine and the
 * playlist editor at every decision point: rules fired (with their
 * result), rules skipped (with the reason: disabled, no trigger match,
 * cooldown, gate blocked, playlist not active), playlist steps applied or
 * skipped (with the reason), hub start/registrations, alarm wakes.
 *
 * Storage: filesDir/rule_activity.json — a JSON array capped at
 * [CAP] entries (newest last). One synchronized write per record; the
 * reads happen only when the Activity screen is open.
 */
object RuleActivityLog {

    private const val FILE = "rule_activity.json"
    private const val CAP = 250

    /** kinds: "fired" | "skipped" | "applied" | "event" | "info" | "error" */
    data class Entry(
        val at: Long,
        val kind: String,
        val playlist: String,
        val rule: String,
        val text: String,
    )

    private fun file(context: Context): File = File(context.filesDir, FILE)

    @Synchronized
    fun record(context: Context, kind: String, playlist: String, rule: String, text: String) {
        try {
            val arr = readRaw(context)
            arr.put(JSONObject()
                .put("at", System.currentTimeMillis())
                .put("kind", kind)
                .put("playlist", playlist)
                .put("rule", rule)
                .put("text", text))
            while (arr.length() > CAP) arr.remove(0)
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            // The journal must never take the engine down with it.
            WepWawetLogger.appDebug("RuleActivityLog: record failed",
                "exception" to e.javaClass.simpleName)
        }
    }

    /** Newest first. */
    @Synchronized
    fun read(context: Context): List<Entry> {
        val arr = readRaw(context)
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(
                at = o.optLong("at", 0L),
                kind = o.optString("kind", "info"),
                playlist = o.optString("playlist", ""),
                rule = o.optString("rule", ""),
                text = o.optString("text", ""),
            ))
        }
        return out.asReversed()
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun readRaw(context: Context): JSONArray = try {
        if (file(context).isFile) JSONArray(file(context).readText()) else JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }
}
