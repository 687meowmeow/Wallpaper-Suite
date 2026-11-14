package com.wepwawet.data

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import org.json.JSONArray

/**
 * v0.8.12: Workshop tag persistence — "sort by tag" in the Wallpapers
 * section means the WORKSHOP's own tags.
 *
 * Every workshop download carries its Steam Workshop tags (the same
 * `tags` list the browse/detail screens show). [com.wepwawet.data.DownloadManager]
 * stores them here the moment a download starts; the library screen also
 * back-fills tags for items downloaded before this store existed (or by
 * other means) with a lazy `GetPublishedFileDetails` lookup through
 * [com.wepwawet.data.SteamApiService].
 *
 * Storage: one SharedPreferences entry per item id, tags as a JSON array
 * string. An item with zero tags stores an EMPTY array (not a missing
 * key) so the lazy back-fill does not re-fetch it forever.
 */
object WorkshopTagStore {

    private const val PREFS = "workshop_tag_store"
    private const val KEY_PREFIX = "tags:"

    /** Persist [tags] for [itemId]. Empty list is stored (and counts as known). */
    fun put(context: Context, itemId: String, tags: List<String>) {
        if (itemId.isBlank()) return
        val clean = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(24)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + itemId, JSONArray(clean).toString())
            .apply()
    }

    /**
     * The stored tags for [itemId], or null when this item has never been
     * seen (caller may fetch from the network).
     */
    fun get(context: Context, itemId: String): List<String>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + itemId, null) ?: return null
        return parse(raw)
    }

    /** Every known itemId → tags mapping (for the library's tag chips). */
    fun readAll(context: Context): Map<String, List<String>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = mutableMapOf<String, List<String>>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX) && value is String) {
                parse(value)?.let { result[key.removePrefix(KEY_PREFIX)] = it }
            }
        }
        return result
    }

    fun remove(context: Context, itemId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PREFIX + itemId).apply()
    }

    private fun parse(raw: String): List<String>? = try {
        val array = JSONArray(raw)
        List(array.length()) { i -> array.optString(i, "") }.filter { it.isNotEmpty() }
    } catch (t: Throwable) {
        WepWawetLogger.appWarn("WorkshopTagStore: parse failed",
            "exception" to t.javaClass.simpleName)
        null
    }
}
