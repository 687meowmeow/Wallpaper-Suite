package com.wepwawet.nyanpasu

import android.content.Context

/**
 * Discover (the booru auto-wallpaper screen) — settings persistence.
 *
 * Based on Nyanpasu Wallpaper v1.0.0 by FranklinNexus (MIT License),
 * adapted to WallpaperSuite's prefs style. The 1.0 semantics are kept:
 *   • Strict tags — required intent (searched first, hard preference)
 *   • Soft tags   — hints, attached with a 38% chance per query
 *   • Style slider — body-type preference bands (see NyanpasuSearch)
 *   • Content     — Pure / Mix / NSFW (SearchR18Policy)
 *   • Screens     — v1.0.0's TRI-STATE model: home/lock each cycle
 *     Off → Pink → Blue. Same color on both = ONE image (sync mode);
 *     different colors = TWO independent images (dual mode). This is
 *     WallpaperTargetMode's exact contract (v0.8.11 port).
 *   • Schedule    — off / daily 7:00 / every 6 / 12 / 24 h
 */
object NyanpasuPrefs {
    private const val PREFS = "wallpapersuite_nyanpasu"

    private const val KEY_STRICT_TAGS = "strict_tags"
    private const val KEY_SOFT_TAGS = "soft_tags"
    private const val KEY_STYLE = "style_value"
    private const val KEY_R18 = "r18_mode"
    private const val KEY_SCHEDULE = "schedule_mode"
    private const val KEY_RECENT_URLS = "recent_urls"
    private const val KEY_PREFETCH_URL = "prefetch_url"
    private const val KEY_PREFETCH_AT = "prefetch_at"

    // ── v1.0.0 tri-state screens ─────────────────────────────────────
    private const val KEY_HOME_STATE = "home_state"
    private const val KEY_LOCK_STATE = "lock_state"

    // Legacy v0.8.10 booleans (migrated once on first read).
    private const val KEY_HOME_ON = "home_on"
    private const val KEY_LOCK_ON = "lock_on"
    private const val KEY_LOCK_SAME = "lock_same_image"

    /** v1.0.0 default: neutral style, Pure content. */
    const val DEFAULT_STYLE = 50
    const val R18_PURE = 0
    const val R18_NSFW = 1
    const val R18_MIX = 2

    /** WallpaperTargetMode: 0 = off, 1 = pink, 2 = blue. */
    const val SCREEN_OFF = 0
    const val SCREEN_PINK = 1
    const val SCREEN_BLUE = 2

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

    // ── Tri-state screens (v1.0.0 WallpaperTargetMode) ───────────────

    /**
     * Reads the (home, lock) tri-state pair, migrating the v0.8.10
     * boolean model (homeOn/lockOn/lockSame) on first access:
     *   homeOn & lockOn & lockSame  → (PINK, PINK)   one image
     *   homeOn & lockOn & !lockSame → (PINK, BLUE)   two images
     *   homeOn only                 → (PINK, OFF)
     *   lockOn only                 → (OFF, PINK)
     */
    fun readScreens(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        if (!p.contains(KEY_HOME_STATE) && !p.contains(KEY_LOCK_STATE)) {
            val homeOn = p.getBoolean(KEY_HOME_ON, true)
            val lockOn = p.getBoolean(KEY_LOCK_ON, true)
            val lockSame = p.getBoolean(KEY_LOCK_SAME, true)
            val migrated = when {
                homeOn && lockOn && lockSame -> SCREEN_PINK to SCREEN_PINK
                homeOn && lockOn -> SCREEN_PINK to SCREEN_BLUE
                homeOn -> SCREEN_PINK to SCREEN_OFF
                lockOn -> SCREEN_OFF to SCREEN_PINK
                else -> SCREEN_OFF to SCREEN_OFF
            }
            writeScreens(context, migrated.first, migrated.second)
            return migrated
        }
        val home = prefs(context).getInt(KEY_HOME_STATE, SCREEN_PINK).coerceIn(0, 2)
        val lock = prefs(context).getInt(KEY_LOCK_STATE, SCREEN_PINK).coerceIn(0, 2)
        return home to lock
    }

    fun writeScreens(context: Context, homeState: Int, lockState: Int) {
        prefs(context).edit()
            .putInt(KEY_HOME_STATE, homeState.coerceIn(0, 2))
            .putInt(KEY_LOCK_STATE, lockState.coerceIn(0, 2))
            .apply()
    }

    /** Both screens on and the SAME color → one image for both. */
    fun isSyncMode(homeState: Int, lockState: Int): Boolean =
        homeState > SCREEN_OFF && lockState > SCREEN_OFF && homeState == lockState

    /** Both screens on with DIFFERENT colors → two independent images. */
    fun isDualMode(homeState: Int, lockState: Int): Boolean =
        homeState > SCREEN_OFF && lockState > SCREEN_OFF && homeState != lockState

    fun homeRequired(homeState: Int): Boolean = homeState > SCREEN_OFF
    fun lockRequired(lockState: Int): Boolean = lockState > SCREEN_OFF

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

    // ── Prefetch (v1.0.0 PrefetchCoordinator, simplified) ────────────

    /**
     * After an apply, the NEXT image is fetched into
     * cacheDir/prefetch.img so a later Refresh is instant. Entries older
     * than 12 h are considered stale.
     */
    fun prefetchFile(context: Context) =
        java.io.File(context.cacheDir, "nyanpasu_prefetch.img")

    fun readPrefetchMeta(context: Context): Pair<String, Long>? {
        val p = prefs(context)
        val url = p.getString(KEY_PREFETCH_URL, null) ?: return null
        val at = p.getLong(KEY_PREFETCH_AT, 0L)
        if (url.isBlank() || at <= 0L) return null
        return url to at
    }

    fun writePrefetchMeta(context: Context, url: String) {
        prefs(context).edit()
            .putString(KEY_PREFETCH_URL, url)
            .putLong(KEY_PREFETCH_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearPrefetch(context: Context) {
        prefs(context).edit()
            .remove(KEY_PREFETCH_URL)
            .remove(KEY_PREFETCH_AT)
            .apply()
        prefetchFile(context).delete()
    }

    fun isPrefetchFresh(context: Context): Boolean {
        val meta = readPrefetchMeta(context) ?: return false
        val (_, at) = meta
        val fresh = System.currentTimeMillis() - at < 12L * 60 * 60 * 1000
        if (!fresh) return false
        return prefetchFile(context).let { it.isFile && it.length() > 1024 }
    }
}
