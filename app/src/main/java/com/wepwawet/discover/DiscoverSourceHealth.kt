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
 *
 * v0.8.25 (Nyanpasu merge — EndpointHealthTracker): hosts now also carry
 * a LATENCY EWMA and a RELIABILITY EWMA, and [rank] orders candidate URLs
 * by health (reliability − latency − failures; unknown hosts keep their
 * incoming order) — the newest Nyanpasu ranks its mirror/proxy candidates
 * this way instead of always trying them in a fixed order. A fully
 * blocked candidate set gets ONE half-open probe (the least-recently
 * blocked host) instead of hammering every route. Our blocks stay
 * wall-clock windows (not their exponential backoff) — the goal here is
 * skipping known-dead hosts this session, not punishing them.
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

    // ═══ v0.8.28 (field-reported health bugs) ═══════════════════════
    /** IMAGE hosts cool separately from SEARCH sources — a pixiv CDN
     *  timeout must never block searches (they kept succeeding) NOR
     *  block every pixiv image for 10 minutes: 90 s windows here. */
    private val imageFailureUntil = HashMap<String, Long>()
    private const val IMAGE_BLOCK_MS = 90_000L

    /** A source that failed 3× in a row (dead DNS, dead endpoint) is
     *  DISABLED for the session — retrying api.yetal.ml 4× in a tenth of
     *  a second was pure log spam. */
    private val deadHosts = HashMap<String, Int>()
    private val deadHostCounts = HashMap<String, Int>()
    private val deadReasons = HashMap<String, String>()

    /** v0.8.28: the cooldown-skip log fires ONCE per cooldown window. */
    private val notedCooldownSkips = HashSet<String>()
    /** v0.8.25 (Nyanpasu merge): per-host latency EWMA (ms) + reliability EWMA. */
    private val hostLatencyEwma = HashMap<String, Double>()
    private val hostReliabilityEwma = HashMap<String, Double>()
    private val hostSamples = HashMap<String, Int>()

    fun hostOf(url: String): String =
        url.substringAfter("//").substringBefore('/')

    // ── Failed URLs ─────────────────────────────────────────────────────

    /** v0.8.28: mark ONLY the URL (an image download failure must not
     *  block the SEARCH side's host health). */
    fun rememberUrlFailedOnly(url: String) {
        failedUrls.add(url)
    }

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

    /** Tests reset the session state. */
    fun resetForTests() {
        failedUrls.clear()
        hostFailureCounts.clear()
        hostBlockedUntil.clear()
        hostCoolingUntil.clear()
        searchFailureUntil.clear()
        hostLatencyEwma.clear()
        hostReliabilityEwma.clear()
        hostSamples.clear()
        imageFailureUntil.clear()
        deadHosts.clear()
        deadHostCounts.clear()
        deadReasons.clear()
        notedCooldownSkips.clear()
    }

    /** v0.8.25 (Nyanpasu merge): a success with its observed latency. */
    fun markUrlOk(url: String, elapsedMs: Long) {
        failedUrls.remove(url)
        markHostOk(url, elapsedMs)
    }

    // ── Host health ─────────────────────────────────────────────────────

    fun markHostFailed(url: String) {
        val host = hostOf(url)
        val n = (hostFailureCounts[host] ?: 0) + 1
        hostFailureCounts[host] = n
        noteSample(host, success = false, elapsedMs = 0)
        if (n >= BLOCK_AFTER) {
            hostBlockedUntil[host] = System.currentTimeMillis() + BLOCK_WINDOW_MS
            WepWawetLogger.appWarn("$TAG: host blocked for this window",
                "host" to host,
                "consecutive_failures" to n,
                "blocked_ms" to BLOCK_WINDOW_MS)
        }
    }

    fun markHostOk(url: String) {
        markHostOk(url, elapsedMs = 0)
    }

    /** v0.8.25 (Nyanpasu merge): a success with its latency feeds the EWMA. */
    fun markHostOk(url: String, elapsedMs: Long) {
        val host = hostOf(url)
        hostFailureCounts.remove(host)
        hostBlockedUntil.remove(host)
        noteSample(host, success = true, elapsedMs = elapsedMs)
    }

    private fun noteSample(host: String, success: Boolean, elapsedMs: Long) {
        val samples = hostSamples[host] ?: 0
        hostReliabilityEwma[host] =
            if (samples == 0) (if (success) 1.0 else 0.0)
            else (hostReliabilityEwma[host] ?: 0.5) * 0.72 + (if (success) 1.0 else 0.0) * 0.28
        if (elapsedMs > 0) {
            hostLatencyEwma[host] =
                if (samples == 0 || hostLatencyEwma[host] == null) elapsedMs.toDouble()
                else (hostLatencyEwma[host] ?: 0.0) * 0.7 + elapsedMs * 0.3
        }
        hostSamples[host] = samples + 1
    }

    /**
     * v0.8.25 (Nyanpasu merge — EndpointHealthTracker.rank): order
     * candidate URLs by health. Blocked hosts drop out while ANY healthy
     * candidate remains; when every candidate is blocked, the
     * least-recently-blocked host gets ONE half-open probe instead of the
     * whole list being hammered.
     */
    @Synchronized
    fun rank(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls
        val now = System.currentTimeMillis()
        val distinct = urls.distinct()
        val indexed = distinct.withIndex().toList()
        val healthy = indexed.filter {
            val until = hostBlockedUntil[hostOf(it.value)] ?: 0L
            until <= now
        }
        val pool = if (healthy.isNotEmpty()) healthy else {
            // All blocked: one half-open probe (the soonest-to-unblock host).
            listOfNotNull(indexed.minByOrNull {
                hostBlockedUntil[hostOf(it.value)] ?: Long.MAX_VALUE
            })
        }
        return pool.sortedWith(
            compareByDescending<IndexedValue<String>> {
                val host = hostOf(it.value)
                (hostReliabilityEwma[host] ?: 0.5) * 100.0 -
                    (hostLatencyEwma[host] ?: 0.0) / 250.0 -
                    (hostFailureCounts[host] ?: 0) * 15.0
            }.thenBy { it.index }
        ).map { it.value }
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

    // ── v0.8.28: image-host health (separate from search) ───────────────

    fun rememberImageFailure(url: String) {
        imageFailureUntil[hostOf(url)] = System.currentTimeMillis() + IMAGE_BLOCK_MS
    }

    fun isImageBlocked(url: String): Boolean =
        System.currentTimeMillis() < (imageFailureUntil[hostOf(url)] ?: 0L)

    fun clearImageFailure(url: String) {
        imageFailureUntil.remove(hostOf(url))
    }

    // ── v0.8.28: dead sources (auto-disabled for the session) ───────────

    /** A 3rd consecutive failure disables the host for the session. */
    fun noteDeadCandidate(url: String, reason: String) {
        val host = hostOf(url)
        val n = (deadHostCounts[host] ?: 0) + 1
        deadHostCounts[host] = n
        deadReasons[host] = reason
        if (n >= 3) {
            if (host !in deadHosts) {
                deadHosts[host] = 1
                WepWawetLogger.appWarn("$TAG: source DISABLED for this session",
                    "host" to host,
                    "reason" to reason,
                    "consecutive_failures" to n)
            }
        }
    }

    fun isDead(url: String): Boolean = hostOf(url) in deadHosts

    fun noteHostAlive(url: String) {
        deadHostCounts.remove(hostOf(url))
    }

    /** The settings screen's status line for a source's endpoint host. */
    fun statusLineFor(url: String): String = when {
        hostOf(url) in deadHosts ->
            "offline — auto-disabled this session (" + (deadReasons[hostOf(url)] ?: "?") + ")"
        else -> ""
    }

    /** Once-per-cooldown skip logging (the 0.8.28 log had 422 of them). */
    fun noteCooldownSkipOnce(url: String): Boolean {
        val host = hostOf(url)
        if (host in notedCooldownSkips) return false
        notedCooldownSkips.add(host)
        WepWawetLogger.appInfo("$TAG: source cooling down — skipped until the window ends",
            "host" to host)
        return true
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
