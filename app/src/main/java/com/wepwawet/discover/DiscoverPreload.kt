package com.wepwawet.discover

import android.content.Context
import android.net.ConnectivityManager
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * v0.8.25: THE ADAPTIVE PRELOADER — replaces the fixed refill loop.
 *
 * The review's requirements, implemented:
 *  • REFILL ON EVERY CONSUMPTION — [onConsumed] fires from every place a
 *    queued image is popped (the Discover refresh, the rule pulls); the
 *    queue tops back up in the background without the user ever waiting.
 *  • ADAPT TO THE USER — every consumption and every manual refresh bumps
 *    a demand counter; the TARGET queue depth and the download CONCURRENCY
 *    scale with recent demand (spam Refresh → up to [MAX_TARGET] deep and
 *    [MAX_CONCURRENCY] parallel downloads) and decay on the [TICK_MS]
 *    idle tick back to the [BASE_TARGET]/1 baseline. Bounded on both axes.
 *  • RESPECT the existing host-health and rate-limit handling — all
 *    downloads go through [DiscoverSearch.searchOne] + [downloadImage],
 *    which skip blocked hosts, honour 429 Retry-After cool-downs and
 *    never re-download a failed URL (DiscoverSourceHealth/DiscoverDedup).
 *  • SMOOTH — the queue is a set of small pref files; popping is instant;
 *    downloads run on Dispatchers.IO; nothing touches the main thread.
 *
 * Single-flight per fill (a fill already in flight is never doubled up —
 * the consumption/ticker calls re-run it); one idle-decay ticker per
 * process (started with the first fill).
 */
object DiscoverPreload {

    private const val TAG = "DiscoverPreload"

    /** The resting target the queue holds when nobody's tapping. */
    private const val BASE_TARGET = 8
    /** The demand-scaled ceiling ("button spam" mode). */
    private const val MAX_TARGET = 24
    /** Parallel download ceiling. */
    private const val MAX_CONCURRENCY = 4
    /** How much one consumption/refresh bumps the target (+2, up to the ceiling). */
    private const val TARGET_STEP = 2
    /** The demand decay tick. */
    private const val TICK_MS = 15_000L
    /** Hard bound on one fill run's downloads (all sources blocked etc.). */
    private const val MAX_ATTEMPTS_PER_FILL = 24
    /** Straight all-lane failures tolerated before a fill gives up. */
    private const val MAX_CONSECUTIVE_FAILURES = 3

    private val filling = AtomicBoolean(false)
    private val tickerStarted = AtomicBoolean(false)
    /** Recent demand: every consumption +1; decays -1 per tick. */
    private val demand = AtomicInteger(0)

    /**
     * v0.8.26 (THE RETRY-STORM FIX): the 0.8.25 log wall — after the
     * network died, every idle tick re-ran a 3-attempt fill, every 15
     * seconds, forever (3 lanes × 8s of DoH timeouts each round, plus a
     * WARN line per lane). A fill that appends NOTHING now backs off
     * exponentially (1 → 2 → 4 → … → 15 min); kicks during a backoff are
     * ignored. Any success, any consumption, or the network coming back
     * resets it.
     */
    @Volatile
    private var backoffUntilMs: Long = 0L
    @Volatile
    private var consecutiveEmptyFills: Int = 0

    private fun noteFillResult(appended: Int) {
        if (appended > 0) {
            consecutiveEmptyFills = 0
            backoffUntilMs = 0L
        } else {
            val n = ++consecutiveEmptyFills
            val minutes = (1L shl (n - 1).coerceAtMost(4)).coerceAtMost(15L)
            backoffUntilMs = System.currentTimeMillis() + minutes * 60_000L
            WepWawetLogger.appWarn("$TAG: nothing appended — backing off",
                "empty_fills" to n,
                "minutes" to minutes)
        }
    }

    private fun resetBackoff() {
        consecutiveEmptyFills = 0
        backoffUntilMs = 0L
    }

    /** One in-flight guard for logs/tests. */
    fun isRunning(): Boolean = filling.get()

    /** The current adaptive target (tests + logs). */
    fun currentTarget(): Int =
        (BASE_TARGET + demand.get() * TARGET_STEP).coerceIn(BASE_TARGET, MAX_TARGET)

    /** The current adaptive concurrency. */
    fun currentConcurrency(): Int =
        (1 + (demand.get() + 1) / 3).coerceIn(1, MAX_CONCURRENCY)

    // ── The demand signals ─────────────────────────────────────────────

    /** Call on EVERY image consumption (queue pop) — refill + scale up. */
    fun onConsumed(context: Context) {
        resetBackoff()
        val d = demand.incrementAndGet()
        WepWawetLogger.appDebug("$TAG: consumed — demand $d, target ${currentTarget()}")
        kick(context)
    }

    /** Call on a manual refresh/search (demand without a queue pop). */
    fun onManualDemand(context: Context) {
        resetBackoff()
        val d = demand.incrementAndGet()
        WepWawetLogger.appDebug("$TAG: manual demand — demand $d")
        kick(context)
    }

    /** The idle decay ticker (started once; pure in-memory counter). */
    private fun ensureTicker(context: Context) {
        if (!tickerStarted.compareAndSet(false, true)) return
        com.wepwawet.WepWawetApplication.appScope.launch {
            while (true) {
                kotlinx.coroutines.delay(TICK_MS)
                val d = demand.updateAndGet { if (it > 0) it - 1 else 0 }
                WepWawetLogger.appDebug("$TAG: idle tick — demand $d, target ${currentTarget()}")
                // Scale-down still tops the (smaller) queue up — a shallow
                // queue after a spam burst settles toward BASE_TARGET.
                kick(context)
            }
        }
    }

    // ── The fill ────────────────────────────────────────────────────────

    /**
     * Top the queue up to the CURRENT adaptive target under the CURRENT
     * Discover settings. Safe to call from anywhere (Application onCreate,
     * every consumption, the network callback); a fill already in flight
     * is a no-op (the demand/ticker calls will re-run it).
     */
    fun kick(context: Context) {
        if (!filling.compareAndSet(false, true)) return
        if (System.currentTimeMillis() < backoffUntilMs) {
            filling.set(false)
            return
        }
        ensureTicker(context)
        val appContext = context.applicationContext
        com.wepwawet.WepWawetApplication.appScope.launch {
            try {
                fill(appContext)
            } finally {
                filling.set(false)
            }
        }
    }

    private suspend fun fill(context: Context) {
        try {
            val strict = DiscoverPrefs.readStrictTags(context)
            val soft = DiscoverPrefs.readSoftTags(context)
            val style = DiscoverPrefs.readStyle(context)
            val r18 = DiscoverPrefs.readR18(context)
            val sources = DiscoverPrefs.readSources(context)
            val signature = DiscoverPrefs.searchSignature(strict, soft, style, r18, sources)
            val target = currentTarget()
            val concurrency = currentConcurrency()
            var consecutiveFailures = 0
            var attempts = 0
            var added = 0

            while (DiscoverPrefs.queueDepth(context, signature) < target &&
                attempts < MAX_ATTEMPTS_PER_FILL
            ) {
                val missing = target - DiscoverPrefs.queueDepth(context, signature)
                if (missing <= 0) break
                // Parallel bounded batch (concurrency 1 at rest — the
                // single-lane behavior; up to MAX_CONCURRENCY under spam).
                val batch = missing.coerceAtMost(concurrency)
                val results = coroutineScope {
                    (1..batch).map {
                        async(Dispatchers.IO) {
                            searchAndQueue(context, strict, soft, style, r18, sources, signature)
                        }
                    }.awaitAll()
                }
                attempts += batch
                val appended = results.count { it }
                added += appended
                if (appended == 0) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        WepWawetLogger.appWarn(
                            "$TAG: fill giving up this round — nothing appends",
                            "attempts" to attempts,
                            "added" to added,
                            "depth" to DiscoverPrefs.queueDepth(context, signature))
                        break
                    }
                    // All lanes failed this round (sources blocked / a dead
                    // network) — one short backoff, then try again.
                    kotlinx.coroutines.delay(1200L)
                } else {
                    consecutiveFailures = 0
                }
            }
            WepWawetLogger.appInfo("$TAG: fill done",
                "added" to added,
                "depth" to DiscoverPrefs.queueDepth(context, signature),
                "target" to target,
                "concurrency" to concurrency,
                "attempts" to attempts,
                "content_tier" to when (r18) {
                    DiscoverPrefs.R18_PURE -> "pure"
                    DiscoverPrefs.R18_NSFW -> "nsfw"
                    else -> "mix"
                },
                "sources" to sources.sorted().joinToString(","))
            if (attempts == 0 && added == 0 &&
                DiscoverPrefs.queueDepth(context, signature) >= target
            ) {
                // v0.8.28: the queue was ALREADY FULL — that is SUCCESS
                // (the old path counted it as "nothing appended" and
                // armed the exponential backoff; the field log showed the
                // warning twice at startup with depth 8 == target 8).
                WepWawetLogger.appInfo("$TAG: queue already full — success",
                    "depth" to DiscoverPrefs.queueDepth(context, signature),
                    "target" to target)
                resetBackoff()
            } else {
                noteFillResult(added)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WepWawetLogger.appWarn("$TAG: fill failed",
                "exception" to e.javaClass.simpleName,
                "message" to (e.message ?: ""))
            noteFillResult(0)
        }
    }

    /** One search → download → append lane. True when an image was queued. */
    private suspend fun searchAndQueue(
        context: Context,
        strict: List<String>,
        soft: List<String>,
        style: Int,
        r18: Int,
        sources: Set<String>,
        signature: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val url = try {
            DiscoverSearch.searchOne(
                context = context,
                strictTags = strict,
                softTags = soft,
                styleValue = style,
                r18Mode = r18,
                // Exclude what's already queued too (a parallel lane may
                // have just appended it; the dedup reserve is the backstop).
                recentUrls = DiscoverPrefs.readRecentUrls(context) +
                    DiscoverPrefs.readQueuedUrls(context),
                sources = sources,
            )
        } catch (e: Exception) {
            // v0.8.26: message-first — the minified build rendered the
            // class name as "b" (R8), hiding the actual reason.
            WepWawetLogger.appWarn("$TAG: lane search failed",
                "message" to (e.message ?: e.javaClass.simpleName))
            null
        } ?: return@withContext false
        val bytes = try {
            DiscoverSearch.downloadImage(url)
        } catch (e: DiscoverSearch.HostSkippedException) {
            // v0.8.27: nothing was downloaded and nothing failed — draw
            // the NEXT candidate immediately instead of burning the slot
            // (the dedup handoff is already dropped inside downloadImage).
            WepWawetLogger.appDebug("$TAG: lane draw skipped — host blocked",
                "url" to url.take(80))
            return@withContext false
        } catch (e: Exception) {
            // The URL/host is already marked failed in the session health
            // tracker; the dedup handoff is dropped (id stays seen).
            DiscoverDedup.abandon(url)
            WepWawetLogger.appWarn("$TAG: lane download failed",
                "message" to (e.message ?: e.javaClass.simpleName),
                "url" to url.take(80))
            return@withContext false
        }
        DiscoverPrefs.appendQueuedImage(context, url, bytes, signature)
    }

    // ── Network-arrival retry (kept from v0.9.0) ────────────────────────

    /**
     * Retry when the network arrives — the app-start kick races
     * connectivity on cold boots. One NetworkCallback kicks the fill the
     * moment a network becomes available.
     */
    fun ensureNetworkRetry(context: Context) {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        try {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        WepWawetLogger.appInfo("$TAG: network available — retrying preload")
                        resetBackoff()
                        kick(appContext)
                    }
                },
            )
            WepWawetLogger.appInfo("$TAG: network retry armed")
        } catch (e: Exception) {
            WepWawetLogger.appWarn("$TAG: network retry registration failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }
}
