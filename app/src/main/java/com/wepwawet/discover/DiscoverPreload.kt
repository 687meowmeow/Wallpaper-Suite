package com.wepwawet.discover

import android.content.Context
import android.net.ConnectivityManager
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.8.19: kick the Discover preload queue ON APP START ("have them
 * preload on app start") — the first Discover visit of a session then
 * pops an ALREADY-DOWNLOADED image instead of hitting the network.
 *
 * The queue machinery itself lives in [DiscoverPrefs] (slots + signatures
 * + files); this coordinator just runs the same fetch-loop the Discover
 * screen's refillQueue runs, with the CURRENT Discover settings (tags,
 * style, tier, sources). Single-flight process-wide (a kick already
 * running is never doubled up); quiet — nothing is surfaced anywhere.
 */
object DiscoverPreload {

    private const val TAG = "DiscoverPreload"
    private val kicking = AtomicBoolean(false)

    /** One in-flight guard for logs/tests. */
    fun isRunning(): Boolean = kicking.get()

    /**
     * Top the queue up to [DiscoverPrefs.QUEUE_TARGET] images under the
     * CURRENT Discover settings. Safe to call from anywhere (Application
     * onCreate, the Discover screen); a run already in flight is a no-op.
     */
    fun kick(context: Context) {
        if (!kicking.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        com.wepwawet.WepWawetApplication.appScope.launch {
            try {
                refill(appContext)
            } finally {
                kicking.set(false)
            }
        }
    }

    /**
     * v0.9.0: RETRY WHEN THE NETWORK ARRIVES. The app-start kick can race
     * connectivity (cold boot, airplane mode just off) — every source
     * fails, the loop logs its exit, and the queue stays empty until the
     * user manually Refreshes. This registers ONE NetworkCallback that
     * kicks the preload again the moment a network becomes available (and
     * on every subsequent regaining — single-flight guards the overlap).
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

    private suspend fun refill(context: Context) {
        try {
            val strict = DiscoverPrefs.readStrictTags(context)
            val soft = DiscoverPrefs.readSoftTags(context)
            val style = DiscoverPrefs.readStyle(context)
            val r18 = DiscoverPrefs.readR18(context)
            val sources = DiscoverPrefs.readSources(context)
            val signature = DiscoverPrefs.searchSignature(strict, soft, style, r18, sources)
            var added = 0
            while (DiscoverPrefs.queueDepth(context, signature) < DiscoverPrefs.QUEUE_TARGET) {
                val url = withContext(Dispatchers.IO) {
                    // v0.9.0: the swallow that hid the "added=0" bug for
                    // several versions — a silent search failure broke the
                    // loop instantly and the only log said "done, added=0".
                    // Every swallowed failure here is now LOUD.
                    runCatching {
                        DiscoverSearch.searchOne(
                            context = context,
                            strictTags = strict,
                            softTags = soft,
                            styleValue = style,
                            r18Mode = r18,
                            recentUrls = DiscoverPrefs.readRecentUrls(context),
                            sources = sources,
                        )
                    }.onFailure {
                        WepWawetLogger.appWarn("$TAG: preload search failed",
                            "exception" to it.javaClass.name,
                            "message" to (it.message ?: ""))
                    }.getOrNull()
                } ?: run {
                    // v0.9.0: the first silent exit, LOGGED — the search
                    // returned nothing (a failed tier ladder, e.g. no
                    // source reachable on this network).
                    WepWawetLogger.appWarn("$TAG: preload loop stopping — search returned nothing",
                        "added_so_far" to added,
                        "depth" to DiscoverPrefs.queueDepth(context, signature))
                    null
                } ?: break
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { DiscoverSearch.downloadImage(url) }.onFailure { WepWawetLogger.appWarn("DiscoverPreload: DiscoverSearch — best-effort call failed", "exception" to it.javaClass.simpleName) }
                        .onFailure {
                            WepWawetLogger.appWarn("$TAG: preload download failed",
                                "exception" to it.javaClass.name,
                                "message" to (it.message ?: ""))
                        }
                        .getOrNull()
                }
                if (bytes == null || bytes.size <= 1024) {
                    // v0.9.0: the second silent exit, LOGGED — the download
                    // failed or came back too small to be a real image.
                    WepWawetLogger.appWarn("$TAG: preload loop stopping — download failed or image too small",
                        "bytes" to (bytes?.size ?: 0),
                        "url" to url.take(100))
                    break
                }
                withContext(Dispatchers.IO) {
                    DiscoverPrefs.appendQueuedImage(context, url, bytes, signature)
                }
                added++
            }
            WepWawetLogger.appInfo("$TAG: app-start preload done",
                "added" to added,
                "depth" to DiscoverPrefs.queueDepth(context, signature))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A background preload failure is never fatal — the Discover
            // screen's own refill still runs when the user gets there.
            WepWawetLogger.appWarn("$TAG: app-start preload failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }
}
