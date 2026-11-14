package com.wepwawet.discover

import android.content.Context
import android.content.SharedPreferences
import com.wepwawet.log.WepWawetLogger

/**
 * Discover (the booru auto-wallpaper screen) — settings persistence.
 *
 * Based on Nyanpasu Wallpaper v1.0.0 by FranklinNexus (MIT License),
 * adapted to WallpaperSuite's prefs style. The 1.0 semantics are kept:
 *   • Strict tags — required intent (searched first, hard preference)
 *   • Soft tags   — hints, attached with a 38% chance per query
 *   • Style slider — body-type preference bands (see DiscoverSearch)
 *   • Content     — Pure / Mix / NSFW (SearchR18Policy)
 *   • Screens     — v1.0.0's TRI-STATE model: home/lock each cycle
 *     Off → Pink → Blue. Same color on both = ONE image (sync mode);
 *     different colors = TWO independent images (dual mode). This is
 *     WallpaperTargetMode's exact contract (v0.8.11 port).
 *   • Schedule    — off / daily 7:00 / every 6 / 12 / 24 h
 */
object DiscoverPrefs {
    // v0.8.19 (rename): the prefs file is "wallpapersuite_discover" now
    // ("rename the nyanpasu file in the app"). The legacy
    // "wallpapersuite_nyanpasu" prefs migrate wholesale on first read —
    // every key moves in one copy (see prefs()).
    private const val PREFS = "wallpapersuite_discover"
    private const val LEGACY_PREFS = "wallpapersuite_nyanpasu"

    private const val KEY_STRICT_TAGS = "strict_tags"
    private const val KEY_SOFT_TAGS = "soft_tags"
    private const val KEY_STYLE = "style_value"
    private const val KEY_R18 = "r18_mode"
    private const val KEY_SCHEDULE = "schedule_mode"
    // v0.8.18 (review B20): recent URLs — ordered JSON array under
    // KEY_RECENT_URLS; KEY_RECENT_URLS_SET is the legacy StringSet form,
    // migrated on first read (see readRecentUrlsList).
    private const val KEY_RECENT_URLS = "recent_urls"
    private const val KEY_RECENT_URLS_SET = "recent_urls_set"
    /**
     * Max remembered recent URLs. v1.0.0's cap was 10 — with a modest tag
     * pool the search drew all-recent within 8 attempts and fell into the
     * "all recent — reuse last draw" fallback, so Discover KEPT SHOWING
     * THE SAME IMAGES (v0.9.1 review). 40 breaks that cycle while the
     * fallback still guarantees an image is always handed out.
     */
    private const val RECENT_URLS_LIMIT = 40
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

    /**
     * v0.9.1: the ON value for the universal binary toggles (stored as the
     * legacy PINK slot so every reader keeps working untouched). The
     * pink/blue tri-state is RETIRED — readScreens migrates any stored
     * blue to plain ON, so dual mode (two different images) can never
     * trigger again: both screens ON always means ONE image on both.
     */
    const val SCREEN_ON = SCREEN_PINK

    const val SCHEDULE_OFF = "off"
    const val SCHEDULE_DAILY_7 = "daily7"
    const val SCHEDULE_H6 = "h6"
    const val SCHEDULE_H12 = "h12"
    const val SCHEDULE_H24 = "h24"

    private fun prefs(context: Context): SharedPreferences {
        val fresh = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (fresh.getBoolean("migrated_from_legacy", false) || fresh.all.isNotEmpty()) {
            migrateLegacyQueueMeta(fresh)
            return fresh
        }
        // v0.8.19: one-time wholesale migration from the legacy prefs file.
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return fresh
        val edit = fresh.edit()
        for ((key, value) in legacy.all) {
            when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(
                    key, value as Set<String>)
            }
        }
        edit.putBoolean("migrated_from_legacy", true).apply()
        WepWawetLogger.appInfo("DiscoverPrefs: migrated legacy prefs",
            "keys" to legacy.all.size)
        migrateLegacyQueueMeta(fresh)
        return fresh
    }

    /** One-shot latch for [migrateLegacyQueueMeta] (per process). */
    private val queueMetaMigrated = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * v0.8.21: one-time migration of the v0.8.19 queue meta keys. v0.8.20
     * renamed "nyanpasu_q_url/at/sig_N" to "discover_q_*" but never
     * carried the VALUES over — the user's already-downloaded queue (the
     * .img files under discover_queue_N.img) went invisible and refill
     * started from zero. Copy each legacy meta triple whose target is
     * empty, then remove the legacy keys.
     */
    private fun migrateLegacyQueueMeta(prefs: SharedPreferences) {
        if (!queueMetaMigrated.compareAndSet(false, true)) return
        var migrated = 0
        // One .all snapshot (the check runs once per process anyway).
        val all = prefs.all
        val edit = prefs.edit()
        for (s in 0 until QUEUE_SIZE) {
            val oldUrl = all["nyanpasu_q_url_$s"] as? String
            val oldAt = all["nyanpasu_q_at_$s"] as? Long
            val oldSig = all["nyanpasu_q_sig_$s"] as? String
            if (oldUrl.isNullOrBlank()) continue
            val newUrlBlank = (all["discover_q_url_$s"] as? String).isNullOrBlank()
            if (!newUrlBlank) {
                // Target slot already holds newer data — drop the old meta.
                edit.remove("nyanpasu_q_url_$s").remove("nyanpasu_q_at_$s")
                    .remove("nyanpasu_q_sig_$s")
                continue
            }
            edit.putString("discover_q_url_$s", oldUrl)
            if (oldAt != null) edit.putLong("discover_q_at_$s", oldAt)
            if (oldSig != null) edit.putString("discover_q_sig_$s", oldSig)
            edit.remove("nyanpasu_q_url_$s").remove("nyanpasu_q_at_$s")
                .remove("nyanpasu_q_sig_$s")
            migrated++
        }
        if (migrated > 0) {
            edit.apply()
            WepWawetLogger.appInfo("DiscoverPrefs: migrated v0.8.19 queue meta",
                "slots" to migrated)
        }
    }

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
    // Every source DiscoverSearch can query. Defaults: everything except
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
        // v0.9.1: BINARY — a stored blue migrates to plain ON here, so the
        // universal toggles and every apply path only ever see OFF or ON.
        val home = prefs(context).getInt(KEY_HOME_STATE, SCREEN_ON).coerceIn(0, 2)
        val lock = prefs(context).getInt(KEY_LOCK_STATE, SCREEN_ON).coerceIn(0, 2)
        return home.toOnOff() to lock.toOnOff()
    }

    /** Blue → ON (the tri-state's dual mode is retired). */
    private fun Int.toOnOff(): Int = if (this > SCREEN_OFF) SCREEN_ON else SCREEN_OFF

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
     *
     * v0.8.18 (review B20): stored as an ORDERED JSON array, newest last.
     * The old StringSet storage had unspecified iteration order, so the
     * eviction (`remove(first())`) dropped an ARBITRARY member — not
     * necessarily the oldest — and the dedup could forget the newest URL
     * (the one the next Refresh most needs to avoid repeating). The
     * StringSet is migrated to the ordered form on first read.
     */
    fun readRecentUrls(context: Context): Set<String> =
        readRecentUrlsList(context).toSet()

    /** v0.9.1: every URL currently sitting in the preload queue. */
    fun readQueuedUrls(context: Context): Set<String> {
        val p = prefs(context)
        val out = HashSet<String>()
        for (s in 0 until QUEUE_SIZE) {
            p.getString("discover_q_url_$s", "")?.let { if (it.isNotBlank()) out.add(it) }
        }
        return out
    }

    /** The ordered recent-URL list (oldest first, newest last). */
    fun readRecentUrlsList(context: Context): List<String> {
        val p = prefs(context)
        // v0.8.21 CRITICAL FIX: NEVER call p.getString(KEY_RECENT_URLS) —
        // the ORIGINAL clone (through v0.8.16) stored recent URLs as a
        // STRINGSET under "recent_urls", and SharedPreferences.getString
        // on a set throws ClassCastException ("java.util.HashSet cannot be
        // cast to java.lang.String") — which killed every Discover refresh
        // on devices with old data while the type looked clean on fresh
        // installs. p.all returns values WITHOUT casts; dispatch on the
        // runtime type instead.
        val stored = p.all[KEY_RECENT_URLS]
        if (stored is String) {
            return try {
                org.json.JSONArray(stored).let { arr ->
                    List(arr.length()) { i -> arr.optString(i) }
                        .filter { it.isNotEmpty() }
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
        if (stored is Set<*>) {
            // The pre-v0.8.18 StringSet form — migrate to the ordered
            // JSON array (insertion order is lost; dedup is kept).
            val list = stored.filterIsInstance<String>()
                .filter { it.isNotEmpty() }.sorted().take(RECENT_URLS_LIMIT)
            p.edit()
                .putString(KEY_RECENT_URLS, org.json.JSONArray(list).toString())
                .remove(KEY_RECENT_URLS_SET)
                .apply()
            WepWawetLogger.appInfo("DiscoverPrefs: migrated legacy StringSet recent_urls",
                "urls" to list.size)
            return list
        }
        // v0.8.18: one-time migration from the legacy StringSet — the
        // insertion order is lost, but every retained URL still dedups.
        val legacy = p.getStringSet(KEY_RECENT_URLS_SET, null) ?: return emptyList()
        val list = legacy.filter { it.isNotEmpty() }.sorted().take(RECENT_URLS_LIMIT)
        p.edit()
            .putString(KEY_RECENT_URLS, org.json.JSONArray(list).toString())
            .remove(KEY_RECENT_URLS_SET)
            .apply()
        return list
    }

    /** Remember [url] as the most recent (dedup, newest-last, capped). */
    fun pushRecentUrl(context: Context, url: String) {
        val list = readRecentUrlsList(context).toMutableList()
        list.remove(url)
        list.add(url)
        while (list.size > RECENT_URLS_LIMIT) list.removeAt(0)
        prefs(context).edit()
            .putString(KEY_RECENT_URLS, org.json.JSONArray(list).toString())
            .apply()
    }

    // ── Preload queue (v0.8.16 — replaces the single v1.0.0 prefetch) ──
    // "refresh takes too long on the downloading stage. it should have
    // them preloaded to ensure it can change it instantly." — Refresh now
    // pops an ALREADY-DOWNLOADED image (instant swap) and a background
    // refill keeps the queue ready at all times. The old single-slot
    // prefetch only restocked after a Set, so consecutive Refreshes each
    // hit the network.
    //
    // v0.8.19: QUEUE_SIZE 3 → 8 ("increase the number of images
    // preloaded") and the queue is kicked ON APP START (see
    // WepWawetApplication.onCreate — "have them preload on app start"),
    // so the first Discover visit of a session already has a full queue.

    private const val QUEUE_SIZE = 8

    /** How many images the refill loop keeps ready (the queue size). */
    const val QUEUE_TARGET = 8

    // v0.8.19 (rename): queue files are "discover_queue_N.img" now; a
    // legacy "nyanpasu_queue_N.img" is picked up on sight (same slot).
    fun queueFile(context: Context, slot: Int): java.io.File {
        val fresh = java.io.File(context.cacheDir, "discover_queue_$slot.img")
        if (!fresh.isFile) {
            val legacy = java.io.File(context.cacheDir, "nyanpasu_queue_$slot.img")
            if (legacy.isFile) legacy.renameTo(fresh)
        }
        return fresh
    }

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
            if (p.getString("discover_q_sig_$s", "") == signature &&
                !p.getString("discover_q_url_$s", "").isNullOrBlank() &&
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
            if (!p.getString("discover_q_url_$s", "").isNullOrBlank() &&
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
            val url = p.getString("discover_q_url_$s", null)
            val at = p.getLong("discover_q_at_$s", 0L)
            val sig = p.getString("discover_q_sig_$s", null)
            if (url.isNullOrBlank() || at <= 0L) continue
            if (sig != signature) {
                // Fetched under different search parameters — stale, drop.
                queueFile(context, s).delete()
                p.edit().remove("discover_q_url_$s").remove("discover_q_at_$s")
                    .remove("discover_q_sig_$s").apply()
                continue
            }
            val f = queueFile(context, s)
            if (!f.isFile) {
                p.edit().remove("discover_q_url_$s").remove("discover_q_at_$s")
                    .remove("discover_q_sig_$s").apply()
                continue
            }
            return try {
                val bytes = f.readBytes()
                p.edit().remove("discover_q_url_$s").remove("discover_q_at_$s")
                    .remove("discover_q_sig_$s").apply()
                f.delete()
                bytes to url
            } catch (e: Exception) {
                p.edit().remove("discover_q_url_$s").remove("discover_q_at_$s")
                    .remove("discover_q_sig_$s").apply()
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
        // v0.9.1: DEDUPE — a random draw can repeat inside one refill loop;
        // without this the queue filled with N copies of the same image and
        // Refresh popped the same picture over and over ("keeps showing the
        // same 5 images"). A URL already queued (any signature) is skipped.
        for (s in 0 until QUEUE_SIZE) {
            if (p.getString("discover_q_url_$s", "") == url) {
                WepWawetLogger.appDebug("DiscoverPrefs: queue dedup skipped a repeat",
                    "url" to url.take(80))
                return
            }
        }
        var slot = -1
        for (s in 0 until QUEUE_SIZE) {
            if (p.getString("discover_q_url_$s", "").isNullOrBlank()) { slot = s; break }
        }
        if (slot < 0) {
            var oldest = 0
            var oldestAt = Long.MAX_VALUE
            for (s in 0 until QUEUE_SIZE) {
                val at = p.getLong("discover_q_at_$s", Long.MAX_VALUE)
                if (at < oldestAt) { oldestAt = at; oldest = s }
            }
            queueFile(context, oldest).delete()
            slot = oldest
        }
        try {
            queueFile(context, slot).writeBytes(bytes)
            p.edit()
                .putString("discover_q_url_$slot", url)
                .putLong("discover_q_at_$slot", System.currentTimeMillis())
                .putString("discover_q_sig_$slot", signature)
                .apply()
        } catch (e: Exception) {
            queueFile(context, slot).delete()
            p.edit().remove("discover_q_url_$slot").remove("discover_q_at_$slot")
                .remove("discover_q_sig_$slot").apply()
        }
    }
}
