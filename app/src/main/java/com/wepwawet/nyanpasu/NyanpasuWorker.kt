package com.wepwawet.nyanpasu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wepwawet.log.WepWawetLogger
import java.util.concurrent.TimeUnit

/**
 * Nyanpasu clone — the auto-refresh engine.
 *
 * Based on Nyanpasu Wallpaper v1.0.0 by FranklinNexus (MIT License), with
 * the v1.2.5 scheduling fixes folded in (they are pure reliability fixes,
 * not image-choice changes):
 *   • periodic schedules use a lightweight PeriodicWork ticker that only
 *     posts an independent OneTime "apply now" job — a single failing run
 *     (offline, API miss, write conflict) no longer poisons the period
 *     through WorkManager's exponential backoff;
 *   • re-enrolling uses ExistingPeriodicWorkPolicy.UPDATE so the original
 *     time anchor is kept when settings change mid-cycle;
 *   • the daily-7:00 mode chains a OneTime job computed to the next 7:00
 *     and re-arms itself after each run (v1.0.1's Daily chain fix).
 */
object NyanpasuScheduler {
    private const val TAG = "NyanpasuScheduler"
    const val WORK_PERIODIC = "nyanpasu_periodic_ticker"
    const val WORK_ONESHOT = "nyanpasu_apply_once"
    const val WORK_DAILY = "nyanpasu_daily_chain"

    fun reschedule(context: Context) {
        val mode = NyanpasuPrefs.readSchedule(context)
        val wm = WorkManager.getInstance(context)
        when (mode) {
            NyanpasuPrefs.SCHEDULE_OFF -> {
                wm.cancelUniqueWork(WORK_PERIODIC)
                wm.cancelUniqueWork(WORK_DAILY)
                WepWawetLogger.appInfo("$TAG: schedule OFF — cancelled")
            }
            NyanpasuPrefs.SCHEDULE_DAILY_7 -> {
                wm.cancelUniqueWork(WORK_PERIODIC)
                enqueueDaily(context, replace = true)
                WepWawetLogger.appInfo("$TAG: daily 7:00 chain (re)armed")
            }
            else -> {
                wm.cancelUniqueWork(WORK_DAILY)
                val hours = when (mode) {
                    NyanpasuPrefs.SCHEDULE_H6 -> 6L
                    NyanpasuPrefs.SCHEDULE_H12 -> 12L
                    else -> 24L
                }
                val request = PeriodicWorkRequestBuilder<NyanpasuTickerWorker>(hours, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build())
                    .build()
                // UPDATE keeps the existing anchor (v1.2.5 fix) — a settings
                // change mid-cycle doesn't reset the clock to "now".
                wm.enqueueUniquePeriodicWork(
                    WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
                WepWawetLogger.appInfo("$TAG: periodic ticker armed",
                    "hours" to hours)
            }
        }
    }

    /** The daily-7:00 chain: one OneTime job per day, target next 07:00. */
    private fun enqueueDaily(context: Context, replace: Boolean) {
        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 7)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val delayMs = target.timeInMillis - now.timeInMillis
        val request = OneTimeWorkRequestBuilder<NyanpasuApplyWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(WORK_DAILY)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_DAILY,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request)
        WepWawetLogger.appInfo("$TAG: daily chain armed",
            "delay_ms" to delayMs,
            "target_epoch" to target.timeInMillis)
    }

    /** Re-arm the daily chain after a run (called by the worker itself). */
    fun rearmDaily(context: Context) = enqueueDaily(context, replace = true)

    /** Trigger one apply immediately (the Refresh button / ticker). */
    fun applyNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<NyanpasuApplyWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_ONESHOT, ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * The lightweight ticker (v1.2.5): its ONLY job is to post an independent
 * one-shot apply job. It never fails (so the period never backs off) and
 * it never does network work itself.
 */
class NyanpasuTickerWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WepWawetLogger.appInfo("NyanpasuTickerWorker: tick — posting one-shot apply")
        NyanpasuScheduler.applyNow(applicationContext)
        return Result.success()
    }
}

/**
 * The actual apply pipeline: search → download → crop → set wallpaper →
 * history + notification. Runs for both manual Refresh (from the UI's own
 * coroutine, see NyanpasuScreen) and scheduled runs.
 */
class NyanpasuApplyWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val homeOn = NyanpasuPrefs.readHomeOn(ctx)
        val lockOn = NyanpasuPrefs.readLockOn(ctx)
        val lockSame = NyanpasuPrefs.readLockSame(ctx)
        if (!homeOn && !lockOn) {
            WepWawetLogger.appInfo("NyanpasuApplyWorker: both screens off — nothing to do")
            return Result.success()
        }
        try {
            // v0.8.10 (v1.0.0 prefetch): scheduled runs consume the
            // prefetched image when it's fresh, then queue the next one.
            var url: String? = null
            var bytes: ByteArray? = null
            if (NyanpasuPrefs.isPrefetchFresh(ctx)) {
                val meta = NyanpasuPrefs.readPrefetchMeta(ctx)
                val cached = NyanpasuPrefs.prefetchFile(ctx).readBytes()
                if (meta != null && cached.size > 1024) {
                    url = meta.first
                    bytes = cached
                    NyanpasuPrefs.clearPrefetch(ctx)
                    WepWawetLogger.appInfo("NyanpasuApplyWorker: using prefetched image",
                        "bytes" to cached.size)
                }
            }
            if (bytes == null) {
                val fetched = NyanpasuSearch.searchOne(
                    context = ctx,
                    strictTags = NyanpasuPrefs.readStrictTags(ctx),
                    softTags = NyanpasuPrefs.readSoftTags(ctx),
                    styleValue = NyanpasuPrefs.readStyle(ctx),
                    r18Mode = NyanpasuPrefs.readR18(ctx),
                    recentUrls = NyanpasuPrefs.readRecentUrls(ctx),
                )
                url = fetched
                bytes = NyanpasuSearch.downloadImage(fetched)
            }
            NyanpasuPrefs.pushRecentUrl(ctx, url!!)
            val bitmap = NyanpasuApplier.decode(bytes, NyanpasuApplier.maxDecodeDimension(ctx))
                ?: return Result.retry()

            var lockBitmap: Bitmap? = null
            if (lockOn && !lockSame) {
                // Dual mode — a separate image for the lock screen
                // (v1.0.0's 粉蓝异色双图).
                val lockUrl = NyanpasuSearch.searchOne(
                    context = ctx,
                    strictTags = NyanpasuPrefs.readStrictTags(ctx),
                    softTags = NyanpasuPrefs.readSoftTags(ctx),
                    styleValue = NyanpasuPrefs.readStyle(ctx),
                    r18Mode = NyanpasuPrefs.readR18(ctx),
                    recentUrls = NyanpasuPrefs.readRecentUrls(ctx) + url,
                )
                val lockBytes = NyanpasuSearch.downloadImage(lockUrl)
                NyanpasuPrefs.pushRecentUrl(ctx, lockUrl)
                lockBitmap = NyanpasuApplier.decode(lockBytes, NyanpasuApplier.maxDecodeDimension(ctx))
            }

            val homeCrop = NyanpasuApplier.cropForScreen(ctx, bitmap)
            val lockCrop = lockBitmap?.let { NyanpasuApplier.cropForScreen(ctx, it) }
            val result = NyanpasuApplier.apply(ctx, homeCrop, homeOn, lockOn, lockCrop)
            NyanpasuApplier.pushHistory(ctx, homeCrop)
            if (result.ok) {
                notifyApplied(ctx)
                // Queue the next prefetch so the following run is fast.
                try {
                    val nextUrl = NyanpasuSearch.searchOne(
                        context = ctx,
                        strictTags = NyanpasuPrefs.readStrictTags(ctx),
                        softTags = NyanpasuPrefs.readSoftTags(ctx),
                        styleValue = NyanpasuPrefs.readStyle(ctx),
                        r18Mode = NyanpasuPrefs.readR18(ctx),
                        recentUrls = NyanpasuPrefs.readRecentUrls(ctx),
                    )
                    val nextBytes = NyanpasuSearch.downloadImage(nextUrl)
                    if (nextBytes.size > 1024) {
                        NyanpasuPrefs.prefetchFile(ctx).writeBytes(nextBytes)
                        NyanpasuPrefs.writePrefetchMeta(ctx, nextUrl)
                    }
                } catch (e: Exception) {
                    WepWawetLogger.appInfo("NyanpasuApplyWorker: prefetch failed (non-fatal)",
                        "exception" to e.javaClass.simpleName)
                }
            } else {
                notifyFailed(ctx)
            }
            return if (result.ok) Result.success() else Result.retry()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: NyanpasuSearch.NoImageException) {
            WepWawetLogger.appWarn("NyanpasuApplyWorker: no image found",
                "message" to (e.message ?: ""))
            // v1.0 keeps the current wallpaper on a no-match — retry later.
            return Result.retry()
        } catch (e: Exception) {
            WepWawetLogger.appError("NyanpasuApplyWorker: failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            return Result.retry()
        } finally {
            // Re-arm the daily chain if that's the active mode.
            if (NyanpasuPrefs.readSchedule(ctx) == NyanpasuPrefs.SCHEDULE_DAILY_7) {
                NyanpasuScheduler.rearmDaily(ctx)
            }
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    "daily_auto_wallpaper",
                    "Daily auto wallpaper",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) })
        }
    }

    private fun notifyApplied(ctx: Context) = try {
        ensureChannel(ctx)
        val notif = NotificationCompat.Builder(ctx, "daily_auto_wallpaper")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Daily wallpaper updated")
            .setContentText("A fresh image was applied to your screens.")
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(ctx)
            .notify(9001, notif)
    } catch (_: SecurityException) {
        // Notifications denied — fine.
    }

    private fun notifyFailed(ctx: Context) = try {
        ensureChannel(ctx)
        val notif = NotificationCompat.Builder(ctx, "daily_auto_wallpaper")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Daily wallpaper update failed")
            .setContentText("Keeping the current wallpaper. Will retry on schedule.")
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(ctx)
            .notify(9002, notif)
    } catch (_: SecurityException) {
    }
}
