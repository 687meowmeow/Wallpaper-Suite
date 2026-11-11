package com.wepwawet.nyanpasu

import android.content.Context

/**
 * Nyanpasu clone — settings persistence.
 *
 * Based on Nyanpasu Wallpaper v1.0.0 by FranklinNexus (MIT License),
 * adapted to WallpaperSuite's prefs style. The 1.0 semantics are kept:
 *   • Strict tags  🔒 — required intent (searched first, hard preference)
 *   • Soft tags    🎲 — hints, attached with a 38% chance per query
 *   • Style slider — body-type preference bands (see NyanpasuSearch)
 *   • Content      — Pure / Mix / NSFW (SearchR18Policy)
 *   • Screens      — home/lock enabled flags + same-vs-different lock image
 *   • Schedule     — off / daily 7:00 / every 6 / 12 / 24 h
 */
object NyanpasuPrefs {
    private const val PREFS = "wallpapersuite_nyanpasu"

    private const val KEY_STRICT_TAGS = "strict_tags"
    private const val KEY_SOFT_TAGS = "soft_tags"
    private const val KEY_STYLE = "style_value"
    private const val KEY_R18 = "r18_mode"
    private const val KEY_HOME_ON = "home_on"
    private const val KEY_LOCK_ON = "lock_on"
    private const val KEY_LOCK_SAME = "lock_same_image"
    private const val KEY_SCHEDULE = "schedule_mode"
    private const val KEY_RECENT_URLS = "recent_urls"

    /** v1.0.0 default: neutral style, Pure content, both screens on, same image. */
    const val DEFAULT_STYLE = 50
    const val R18_PURE = 0
    const val R18_NSFW = 1
    const val R18_MIX = 2

    const val SCHEDULE_OFF = "off"
    const val SCHEDULE_DAILY_7 = "daily7"
    const val SCHEDULE_H6 = "h6"
    const val SCHEDULE_H12 = "h12"
    const val SCHEDULE_H24 = "h24"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readStrictTags(context: Context): List<String> =
        prefs(context).getStringSet(KEY_STRICT_TAGS, emptySet())?.sorted() ?: emptyList()

    fun readSoftTags(context: Context): List<String> =
        prefs(context).getStringSet(KEY_SOFT_TAGS, emptySet())?.sorted() ?: emptyList()

    fun writeTags(context: Context, strict: Collection<String>, soft: Collection<String>) {
        prefs(context).edit()
            .putStringSet(KEY_STRICT_TAGS, strict.filter { it.isNotBlank() }.toSet())
            .putStringSet(KEY_SOFT_TAGS, soft.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    fun readStyle(context: Context): Int =
        prefs(context).getInt(KEY_STYLE, DEFAULT_STYLE).coerceIn(0, 100)

    fun writeStyle(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_STYLE, value.coerceIn(0, 100)).apply()
    }

    fun readR18(context: Context): Int = when (prefs(context).getInt(KEY_R18, R18_PURE)) {
        R18_NSFW -> R18_NSFW
        R18_MIX -> R18_MIX
        else -> R18_PURE
    }

    fun writeR18(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_R18, mode).apply()
    }

    fun readHomeOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HOME_ON, true)

    fun readLockOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCK_ON, true)

    /** true = home & lock use the SAME image (sync mode); false = different (dual). */
    fun readLockSame(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCK_SAME, true)

    fun writeScreens(context: Context, homeOn: Boolean, lockOn: Boolean, lockSame: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HOME_ON, homeOn)
            .putBoolean(KEY_LOCK_ON, lockOn)
            .putBoolean(KEY_LOCK_SAME, lockSame)
            .apply()
    }

    fun readSchedule(context: Context): String =
        prefs(context).getString(KEY_SCHEDULE, SCHEDULE_OFF) ?: SCHEDULE_OFF

    fun writeSchedule(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_SCHEDULE, mode).apply()
    }

    /**
     * Recent image URLs (dedup so Refresh doesn't repeat the same picture
     * back to back — v1.0.0's Tier dedup, kept as a soft preference, NOT a
     * hard gate: the ladder still falls through to untagged results).
     */
    fun readRecentUrls(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_RECENT_URLS, emptySet()) ?: emptySet()

    fun pushRecentUrl(context: Context, url: String) {
        val current = readRecentUrls(context).toMutableSet()
        current.add(url)
        // Keep the newest 10.
        while (current.size > 10) {
            current.remove(current.first())
        }
        prefs(context).edit().putStringSet(KEY_RECENT_URLS, current).apply()
    }
}
