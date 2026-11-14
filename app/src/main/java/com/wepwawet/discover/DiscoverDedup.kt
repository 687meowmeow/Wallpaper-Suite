package com.wepwawet.discover

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import org.json.JSONArray

/**
 * v0.9.2 (THE "DISCOVER REPEATS THE SAME IMAGES" FIX, from the 2026-11-14
 * review): deduplication on a CANONICAL IMAGE ID, not the raw URL string.
 *
 * The review's holes, closed here:
 *  • The same image under a different URL — a size variant
 *    (`…_master1200.jpg` vs `…_square1200.jpg`), a pixiv proxy chain
 *    (i.pixiv.re / i.pixiv.nl / pixiv.yuki.sh all serve the same file),
 *    a query string (`?size=…`), or a different booru CDN directory — all
 *    collapse to ONE id (pixiv: `<illust>_p<page>`, booru: `md5:<hash>`,
 *    else the query-stripped URL).
 *  • Only the last 40 remembered — the persisted set now holds ~2000 ids
 *    (FIFO-trimmed), plus an UNBOUNDED in-memory set for the session.
 *  • Concurrent searchOne calls raced on a stale exclusion snapshot —
 *    [reserve] is the atomic gate: the id is claimed the MOMENT a search
 *    returns it, BEFORE the download, so two concurrent callers can never
 *    both take the same image; ids whose download FAILED stay claimed too
 *    (a dead URL is never worth retrying this session — the host-health
 *    tracker already handles the host side).
 *
 * Call points (per the review): search results ([reserve] inside
 * searchOne), [claim] at appendQueuedImage, and [claim] when the preview
 * pops a queued slot.
 */
object DiscoverDedup {

    private const val TAG = "DiscoverDedup"
    private const val PREFS = "discover_dedup"
    private const val KEY_ORDER = "seen_ids_order"
    /** The persisted id history cap (~2000 per the review). */
    private const val PERSISTED_CAP = 2000

    /** Unbounded for the session. */
    private val sessionIds = HashSet<String>()
    /** id → the url whose search result reserved it (the handoff to the
     *  append/pop claim). */
    private val pending = HashMap<String, String>()
    private var persisted: MutableList<String>? = null

    // ── Canonical ids ───────────────────────────────────────────────────

    /** The pixiv filename: `<illustId>_p<page>[_<size>].<ext>`. */
    private val PIXIV_NAME = Regex("(\\d+)_p(\\d+)(?:_[a-z0-9]+)?\\.\\w+$")
    /** A booru CDN hash filename: 32 hex chars (md5) + extension. */
    private val HASH_NAME = Regex("([0-9a-f]{32})\\.\\w+$")

    fun canonicalId(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val lower = noQuery.lowercase()
        // Pixiv (any proxy host): the illust id + page survives every size
        // variant and every proxy. NOTE: the main image host is
        // i.PXIMG.net — it contains "pximg", NOT "pixiv" (the unit test
        // caught exactly this: pximg URLs fell through to the url:
        // fallback and their variants didn't collapse).
        if ("pixiv" in lower || "pximg" in lower) {
            PIXIV_NAME.find(noQuery)?.let { return "pixiv:${it.groupValues[1]}_p${it.groupValues[2]}" }
        }
        // A ?id=/<digit>/ post id (dapi JSON endpoints hand these out).
        Regex("[?&]id=(\\d+)").find(url)?.let { return "post:${it.groupValues[1]}" }
        Regex("[?&]md5=([0-9a-f]{32})").find(url)?.let { return "md5:${it.groupValues[1]}" }
        // Moebooru/danbooru/gelbooru-style CDN hashes.
        HASH_NAME.find(noQuery)?.let { return "md5:${it.groupValues[1]}" }
        return "url:$noQuery"
    }

    // ── The session + persisted seen sets ────────────────────────────────

    @Synchronized
    private fun loadPersisted(context: Context): MutableList<String> {
        persisted?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null)
        val list = try {
            if (raw != null) JSONArray(raw).let { arr ->
                MutableList(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
                    .toMutableList()
            } else mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
        persisted = list
        return list
    }

    @Synchronized
    private fun persist(context: Context) {
        val list = loadPersisted(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDER, JSONArray(list).toString()).apply()
    }

    // ── The three call points ────────────────────────────────────────────

    /**
     * Atomically claim the image's id for THIS caller — true when it was
     * new (now reserved, and remembered as this url's pending handoff),
     * false when the id was already seen (a duplicate — logged with its
     * id, per the review).
     */
    fun reserve(context: Context, url: String): Boolean {
        val id = canonicalId(url)
        synchronized(this) {
            val seenIds = loadPersisted(context)
            if (id in sessionIds || id in seenIds) {
                WepWawetLogger.appDebug("$TAG: skipped duplicate image",
                    "id" to id,
                    "url" to url.takeLast(64))
                return false
            }
            sessionIds.add(id)
            seenIds.add(id)
            while (seenIds.size > PERSISTED_CAP) seenIds.removeAt(0)
            pending[id] = url
        }
        persist(context)
        return true
    }

    /**
     * The append/pop gate: true when [url] carries a pending reservation
     * made by its own search (consumed), or was never seen at all (a
     * legacy slot from an older build); false when the id was seen under a
     * DIFFERENT url — a duplicate to skip.
     */
    @Synchronized
    fun claim(url: String): Boolean {
        val id = canonicalId(url)
        if (pending.remove(id) == url) return true
        if (id in sessionIds) {
            WepWawetLogger.appDebug("$TAG: skipped duplicate at append/pop",
                "id" to id,
                "url" to url.takeLast(64))
            return false
        }
        // Not pending + not in the session set: either genuinely unseen
        // (legacy) or persisted-only — treat persisted-only as seen.
        return id !in (persisted ?: emptyList())
    }

    /** A reserved url whose download failed: its id STAYS seen (never
     *  re-served this session), but the pending handoff is dropped. */
    @Synchronized
    fun abandon(url: String) {
        pending.remove(canonicalId(url))
    }

    @Synchronized
    fun isSeen(url: String): Boolean {
        val id = canonicalId(url)
        return id in sessionIds || id in loadPersistedOnceSafe()
    }

    private fun loadPersistedOnceSafe(): List<String> =
        try { persisted ?: emptyList() } catch (e: Exception) { emptyList() }
}
