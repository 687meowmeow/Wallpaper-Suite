package com.wepwawet.nyanpasu

import android.content.Context
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
 * The queue machinery itself lives in [NyanpasuPrefs] (slots + signatures
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
     * Top the queue up to [NyanpasuPrefs.QUEUE_TARGET] images under the
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

    private suspend fun refill(context: Context) {
        try {
            val strict = NyanpasuPrefs.readStrictTags(context)
            val soft = NyanpasuPrefs.readSoftTags(context)
            val style = NyanpasuPrefs.readStyle(context)
            val r18 = NyanpasuPrefs.readR18(context)
            val sources = NyanpasuPrefs.readSources(context)
            val signature = NyanpasuPrefs.searchSignature(strict, soft, style, r18, sources)
            var added = 0
            while (NyanpasuPrefs.queueDepth(context, signature) < NyanpasuPrefs.QUEUE_TARGET) {
                val url = withContext(Dispatchers.IO) {
                    runCatching {
                        NyanpasuSearch.searchOne(
                            context = context,
                            strictTags = strict,
                            softTags = soft,
                            styleValue = style,
                            r18Mode = r18,
                            recentUrls = NyanpasuPrefs.readRecentUrls(context),
                            sources = sources,
                        )
                    }.getOrNull()
                } ?: break
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { NyanpasuSearch.downloadImage(url) }.getOrNull()
                }
                if (bytes == null || bytes.size <= 1024) break
                withContext(Dispatchers.IO) {
                    NyanpasuPrefs.appendQueuedImage(context, url, bytes, signature)
                }
                added++
            }
            WepWawetLogger.appInfo("$TAG: app-start preload done",
                "added" to added,
                "depth" to NyanpasuPrefs.queueDepth(context, signature))
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
