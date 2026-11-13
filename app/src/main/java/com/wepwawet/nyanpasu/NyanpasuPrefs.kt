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
    // v0.8.18: which booru sources Discover searches ("add the ability to
    // choose the booru sources for the discover page").
    private const val KEY_SOURCES = "booru_sources"

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

    // ── v0.8.18: booru sources ────────────────────────────────────────
    // Every source NyanpasuSearch can query. Defaults: everything except
    // Gelbooru (its anonymous JSON API now answers 401 — it requires an
    // API key) and Behoimi (connection-dead). Both stay selectable.

    const val SOURCE_LOLICON = "lolicon"
    const val SOURCE_DANBOORU = "danbooru"
    const val SOURCE_KONACHAN = "konachan"
    const val SOURCE_YANDE = "yande"
    const val SOURCE_SAFEBOORU = "safebooru"
    const val SOURCE_TBIB = "tbib"
    const val SOURCE_XBOORU = "xbooru"
    const val SOURCE_GELBOORU = "gelbooru"
    const val SOURCE_BEHOIMI = "behoimi"

    val ALL_SOURCES = listOf(
        SOURCE_LOLICON, SOURCE_DANBOORU, SOURCE_KONACHAN, SOURCE_YANDE,
        SOURCE_SAFEBOORU, SOURCE_TBIB, SOURCE_XBOORU,
        SOURCE_GELBOORU, SOURCE_BEHOIMI,
    )

    private val DEFAULT_SOURCES = setOf(
        SOURCE_LOLICON, SOURCE_DANBOORU, SOURCE_KONACHAN, SOURCE_YANDE,
        SOURCE_SAFEBOORU, SOURCE_TBIB, SOURCE_XBOORU,
    )

    fun readSources(context: Context): Set<String> {
        val saved = prefs(context).getStringSet(KEY_SOURCES, null) ?: return DEFAULT_SOURCES
        // Empty set = everything (kept honest for anyone who ticked every
        // box; an explicitly saved empty selection is treated as "all").
        return saved.ifEmpty { ALL_SOURCES.toSet() }
    }

    fun writeSources(context: Context, sources: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SOURCES, sources).apply()
    }

    /** UI labels for the source chips. */
    fun sourceLabel(id: String): String = when (id) {
        SOURCE_LOLICON -> "Lolicon"
        SOURCE_DANBOORU -> "Danbooru"
        SOURCE_KONACHAN -> "Konachan"
        SOURCE_YANDE -> "Yande.re"
        SOURCE_SAFEBOORU -> "Safebooru"
        SOURCE_TBIB -> "TBIB"
        SOURCE_XBOORU -> "Xbooru"
        SOURCE_GELBOORU -> "Gelbooru"
        SOURCE_BEHOIMI -> "Behoimi"
        else -> id
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

    // ── Preload queue (v0.8.16 — replaces the single v1.0.0 prefetch) ──
    // "refresh takes too long on the downloading stage. it should have
    // them preloaded to ensure it can change it instantly." — Refresh now
    // pops an ALREADY-DOWNLOADED image (instant swap) and a background
    // refill keeps three images ready at all times. The old single-slot
    // prefetch only restocked after a Set, so consecutive Refreshes each
    // hit the network.

    private const val QUEUE_SIZE = 3

    fun queueFile(context: Context, slot: Int) =
        java.io.File(context.cacheDir, "nyanpasu_queue_$slot.img")

    /**
     * v0.8.18: the search parameters a queued image was fetched under
     * (tags + style + content tier + sources). THE TAGS BUG: the queue used
     * to be parameter-blind — changing tags and tapping Refresh still popped
     * an image preloaded under the OLD (or empty) tags, so tags appeared to
     * do nothing. Slots are now stamped; only slots whose signature matches
     * the CURRENT search are eligible.
     */
    fun searchSignature(
        strictTags: Collection<String>,
        softTags: Collection<String>,
        styleValue: Int,
        r18Mode: Int,
        sources: Set<String>,
    ): String =
        (strictTags.filter { it.isNotBlank() }.sorted() + "|" +
            softTags.filter { it.isNotBlank() }.sorted() + "|" +
            styleValue + "|" + r18Mode + "|" + sources.sorted())
            .toString()

    /** How many downloaded images with THIS signature sit in the queue. */
    fun queueDepth(context: Context, signature: String): Int {
        val p = prefs(context)
        var n = 0
        for (s in 0 until QUEUE_SIZE) {
            if (p.getString("nyanpasu_q_sig_$s", "") == signature &&
                !p.getString("nyanpasu_q_url_$s", "").isNullOrBlank() &&
                queueFile(context, s).isFile
            ) n++
        }
        return n
    }

    /** Back-compat overload for logs. */
    fun queueDepth(context: Context): Int {
        val p = prefs(context)
        var n = 0
        for (s in 0 until QUEUE_SIZE) {
            if (!p.getString("nyanpasu_q_url_$s", "").isNullOrBlank() &&
                queueFile(context, s).isFile
            ) n++
        }
        return n
    }

    /**
     * Pop the OLDEST queued image fetched under `signature`. Stale slots
     * (different signature, or meta without a file) are dropped on sight.
     */
    fun consumeQueuedImage(context: Context, signature: String): Pair<ByteArray, String>? {
        val p = prefs(context)
        for (s in 0 until QUEUE_SIZE) {
            val url = p.getString("nyanpasu_q_url_$s", null)
            val at = p.getLong("nyanpasu_q_at_$s", 0L)
            val sig = p.getString("nyanpasu_q_sig_$s", null)
            if (url.isNullOrBlank() || at <= 0L) continue
            if (sig != signature) {
                // Fetched under different search parameters — stale, drop.
                queueFile(context, s).delete()
                p.edit().remove("nyanpasu_q_url_$s").remove("nyanpasu_q_at_$s")
                    .remove("nyanpasu_q_sig_$s").apply()
                continue
            }
            val f = queueFile(context, s)
            if (!f.isFile) {
                p.edit().remove("nyanpasu_q_url_$s").remove("nyanpasu_q_at_$s")
                    .remove("nyanpasu_q_sig_$s").apply()
                continue
            }
            return try {
                val bytes = f.readBytes()
                p.edit().remove("nyanpasu_q_url_$s").remove("nyanpasu_q_at_$s")
                    .remove("nyanpasu_q_sig_$s").apply()
                f.delete()
                bytes to url
            } catch (e: Exception) {
                p.edit().remove("nyanpasu_q_url_$s").remove("nyanpasu_q_at_$s")
                    .remove("nyanpasu_q_sig_$s").apply()
                f.delete()
                null
            }
        }
        return null
    }

    /** Add a downloaded image to the first free slot (oldest slot when full). */
    fun appendQueuedImage(context: Context, url: String, bytes: ByteArray, signature: String) {
        if (bytes.size <= 1024) return
        val p = prefs(context)
        var slot = -1
        for (s in 0 until QUEUE_SIZE) {
            if (p.getString("nyanpasu_q_url_$s", "").isNullOrBlank()) { slot = s; break }
        }
        if (slot < 0) {
            var oldest = 0
            var oldestAt = Long.MAX_VALUE
            for (s in 0 until QUEUE_SIZE) {
                val at = p.getLong("nyanpasu_q_at_$s", Long.MAX_VALUE)
                if (at < oldestAt) { oldestAt = at; oldest = s }
            }
            queueFile(context, oldest).delete()
            slot = oldest
        }
        try {
            queueFile(context, slot).writeBytes(bytes)
            p.edit()
                .putString("nyanpasu_q_url_$slot", url)
                .putLong("nyanpasu_q_at_$slot", System.currentTimeMillis())
                .putString("nyanpasu_q_sig_$slot", signature)
                .apply()
        } catch (e: Exception) {
            queueFile(context, slot).delete()
            p.edit().remove("nyanpasu_q_url_$slot").remove("nyanpasu_q_at_$slot")
                .remove("nyanpasu_q_sig_$slot").apply()
        }
    }
}
