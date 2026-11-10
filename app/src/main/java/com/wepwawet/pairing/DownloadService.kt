package com.wepwawet.pairing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wepwawet.MainActivity
import com.wepwawet.data.DownloadManager
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps workshop downloads alive in the background
 * and shows a sticky notification with the current download progress.
 *
 * v0.8.6: the user's explicit request was "make it so downloads continue
 * in the background and have a notification to indicate its active in the
 * background." The download itself already runs on
 * `WepWawetApplication.appScope` (see [WorkshopDownloadJob]) which
 * survives Activity destruction — but without a foreground service,
 * Android may still kill the process under memory pressure, especially
 * when the user has navigated away from the app. A foreground service
 * with a sticky notification prevents that.
 *
 * Lifecycle:
 *  - Call [startForDownload] when a download begins. The service starts
 *    itself as a foreground service (with a notification) and subscribes
 *    to [DownloadManager.activeDownloads]. While there are active
 *    downloads, the notification is updated with the latest progress.
 *  - When the active downloads map becomes empty, the service
 *    auto-stops itself (the notification disappears).
 *  - Call [stop] to force-stop (rarely needed — the auto-stop handles
 *    the normal case).
 *
 * The service uses the `specialUse` foreground service type (same as
 * [PairingService]) because Android 14+'s `dataSync` type has a 6-hour
 * cap and a workshop download + pack can occasionally exceed that for
 * very large wallpapers.
 *
 * Thread-safety: the service runs on the main thread; the download
 * progress collection happens on `serviceScope` (main dispatcher). The
 * notification update is cheap (a few hundred bytes of parcelled data)
 * and can safely run on the main thread.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "wepwawet_downloads"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_ACTION = "action"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"

        @Volatile
        private var instance: DownloadService? = null

        /**
         * Start the foreground service to keep downloads alive. Safe to
         * call multiple times — the service is a singleton (per-process)
         * and subsequent starts just update the notification.
         */
        fun startForDownload(context: Context) {
            val intent = Intent(context.applicationContext, DownloadService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException on Android 12+
                // background starts — log and move on. The download itself
                // still runs on appScope; we just don't get the foreground
                // priority / notification.
                WepWawetLogger.workshopWarn("$TAG: startForegroundService rejected",
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
            }
        }

        /**
         * Stop the service. Normally not needed — the service auto-stops
         * when the active downloads map becomes empty. Use this for an
         * explicit "cancel all downloads" UI action.
         */
        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, DownloadService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_STOP)
            context.applicationContext.startService(intent)
        }
    }

    private var collectJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                WepWawetLogger.workshop("$TAG: stop requested")
                stopSelfSafely()
                return START_NOT_STICKY
            }
            "cancel" -> {
                // User tapped "Cancel" on the notification — cancel the
                // specific download identified by item_id.
                val itemId = intent?.getStringExtra("item_id")
                if (!itemId.isNullOrBlank()) {
                    WepWawetLogger.workshop("$TAG: cancel requested",
                        "item_id" to itemId)
                    WorkshopDownloadJob.cancel(itemId)
                }
                // Don't stop the service — the collectLatest loop will
                // auto-stop when activeDownloads becomes empty.
                return START_STICKY
            }
            else -> {
                // Start foreground immediately (within the ANR / ForegroundServiceDidNotStart
                // window) so the system doesn't kill us.
                startForegroundSafely()
                startCollecting()
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        WepWawetLogger.workshop("$TAG: created")
    }

    override fun onDestroy() {
        collectJob?.cancel()
        collectJob = null
        serviceScope.cancel()
        instance = null
        WepWawetLogger.workshop("$TAG: destroyed")
        super.onDestroy()
    }

    private fun startCollecting() {
        if (collectJob?.isActive == true) return
        collectJob = serviceScope.launch {
            DownloadManager.activeDownloads.collectLatest { active ->
                if (active.isEmpty()) {
                    // No active downloads — auto-stop the service.
                    WepWawetLogger.workshop("$TAG: no active downloads — stopping")
                    stopSelfSafely()
                    return@collectLatest
                }
                // Update the notification with the latest progress.
                val notification = buildNotification(active)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun startForegroundSafely() {
        val notification = buildInitialNotification()
        try {
            // v0.8.6: ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            // requires API 34+ (UPSIDE_DOWN_CAKE). On older Android, the
            // 2-arg startForeground is used (the type is declared in the
            // manifest and applied automatically on API 34+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // SecurityException on some OEMs when the app is in background
            // and the user hasn't granted POST_NOTIFICATIONS — log and
            // stop the service so we don't sit around doing nothing.
            WepWawetLogger.workshopWarn("$TAG: startForeground failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            stopSelfSafely()
        }
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Workshop wallpaper downloads"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WallpaperSuite")
            .setContentText("Preparing download…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(buildContentIntent())
            .build()
    }

    private fun buildNotification(active: Map<String, com.wepwawet.data.DownloadProgress>): Notification {
        val count = active.size
        val first = active.values.first()
        val title = if (count == 1) {
            "Downloading: ${first.title.take(40)}"
        } else {
            "Downloading $count wallpapers"
        }
        // Average progress across all active downloads.
        val avgProgress = active.values.map { it.progress }.average().toFloat()
        val progressInt = (avgProgress * 100).toInt().coerceIn(0, 100)
        val statusText = first.statusMessage.take(60)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(buildContentIntent())
            .setProgress(100, progressInt, progressInt == 0)

        // Add a "Cancel" action for the first active download (the user
        // can cancel from the notification without opening the app).
        val cancelIntent = Intent(this, DownloadService::class.java)
            .putExtra(EXTRA_ACTION, "cancel")
            .putExtra("item_id", first.itemId)
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            cancelPendingIntent,
        )
        return builder.build()
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
