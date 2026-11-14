package com.wepwawet.discover

import com.wepwawet.log.WepWawetLogger

/**
 * v0.9.2 (THE "DISCOVER IS NOT LOADING ANYTHING" FIX, from the 2026-11-14
 * review): ONE session-scoped health tracker for every network hop
 * Discover makes — searches AND image downloads.
 *
 * The review's log: every i.pixiv.re download timed out (bytes=0), and
 * [DiscoverPreload]'s loop BROKE on the first one, so the queue never
 * filled ("app-start preload done added=0 depth=0" twice) even though
 * xbooru was perfectly healthy.
 *
 * Tracked, per session (in-memory, process lifetime):
 *  • FAILED URLS — a URL whose download failed is never retried this
 *    session (the same image can be re-found later under its canonical
 *    ID dedup anyway);
 *  • HOST HEALTH — after [BLOCK_AFTER] consecutive failures a host is
 *    blocked for [BLOCK_WINDOW_MS]; one success clears it. The search
 *    side's older per-failure 10-minute skip lives on through
 *    [rememberSearchFailure] so an existing behavior isn't lost;
 *  • 429 COOL-DOWNS — a source that answered 429 cools down for its
 *    Retry-After (or [DEFAULT_RETRY_AFTER_MS]); it is not queried again
 *    until the window passes. (The log: api.lolicon.app answered 429
 *    twice in a row — hammering it only extends the block.)
 */
object DiscoverSourceHealth {

    private const val TAG = "DiscoverSourceHealth"

    /** Consecutive download failures before a host is session-blocked. */
    private const val BLOCK_AFTER = 3
    /** How long a blocked host stays blocked. */
    private const val BLOCK_WINDOW_MS = 10 * 60_000L
    /** A 429 without a usable Retry-After cools down this long. */
    private const val DEFAULT_RETRY_AFTER_MS = 60_000L

    private val failedUrls = HashSet<String>()
    private val hostFailureCounts = HashMap<String, Int>()
    private val hostBlockedUntil = HashMap<String, Long>()
    private val hostCoolingUntil = HashMap<String, Long>()
    /** The search side's older behavior: any failure skips the host 10 min. */
    private val searchFailureUntil = HashMap<String, Long>()

    fun hostOf(url: String): String =
        url.substringAfter("//").substringBefore('/')

    // ── Failed URLs ─────────────────────────────────────────────────────

    fun markUrlFailed(url: String) {
        failedUrls.add(url)
        markHostFailed(url)
        WepWawetLogger.appDebug("$TAG: URL failed for this session",
            "host" to hostOf(url))
    }

    fun isUrlFailed(url: String): Boolean = url in failedUrls

    fun markUrlOk(url: String) {
        failedUrls.remove(url)
        markHostOk(url)
    }

    // ── Host health ─────────────────────────────────────────────────────

    fun markHostFailed(url: String) {
        val host = hostOf(url)
        val n = (hostFailureCounts[host] ?: 0) + 1
        hostFailureCounts[host] = n
        if (n >= BLOCK_AFTER) {
            hostBlockedUntil[host] = System.currentTimeMillis() + BLOCK_WINDOW_MS
            WepWawetLogger.appWarn("$TAG: host blocked for this window",
                "host" to host,
                "consecutive_failures" to n,
                "blocked_ms" to BLOCK_WINDOW_MS)
        }
    }

    fun markHostOk(url: String) {
        val host = hostOf(url)
        hostFailureCounts.remove(host)
        hostBlockedUntil.remove(host)
    }

    fun isHostBlocked(url: String): Boolean =
        System.currentTimeMillis() < (hostBlockedUntil[hostOf(url)] ?: 0L)

    // ── Search-side failures (the older single-failure skip) ────────────

    fun rememberSearchFailure(url: String) {
        searchFailureUntil[hostOf(url)] = System.currentTimeMillis() + BLOCK_WINDOW_MS
        markHostFailed(url)
    }

    fun isSearchBlocked(url: String): Boolean =
        System.currentTimeMillis() < (searchFailureUntil[hostOf(url)] ?: 0L) ||
            isHostBlocked(url)

    fun clearSearchFailure(url: String) {
        searchFailureUntil.remove(hostOf(url))
        markHostOk(url)
    }

    // ── 429 cool-downs (Retry-After honoured) ───────────────────────────

    fun markRateLimited(url: String, retryAfterSeconds: Long?) {
        val host = hostOf(url)
        val ms = (retryAfterSeconds?.coerceIn(1, 600) ?: (DEFAULT_RETRY_AFTER_MS / 1000)) * 1000
        hostCoolingUntil[host] = System.currentTimeMillis() + ms
        WepWawetLogger.appWarn("$TAG: source rate-limited — cooling down",
            "host" to host,
            "retry_after_ms" to ms)
    }

    fun isRateLimited(url: String): Boolean =
        System.currentTimeMillis() < (hostCoolingUntil[hostOf(url)] ?: 0L)
}
