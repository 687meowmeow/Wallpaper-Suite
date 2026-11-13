package com.wepwawet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.wepwawet.log.CrashHandler
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WepWawet Application entry point.
 *
 * Responsibilities (each step is independently try-caught so one failure does
 * not block the rest — this is critical because if any of these throw, the
 * app would never finish starting and the user would see "crashes on boot
 * without logs being made"):
 *
 *  1. Install the global CrashHandler FIRST so we capture any subsequent crash
 *  2. Write a "boot breadcrumb" to Downloads/WepWawet/boot.txt so the user
 *     can verify the app actually started onCreate — if even this fails, we
 *     know the crash is in the Application.attachBaseContext or class loading
 *     (which we can't catch here).
 *  3. Initialize the WepWawetLogger ring buffer + WorkManager job
 *  4. Create the foreground service notification channels
 *  5. Log a startup-complete event with build info
 *
 * Every step writes to logcat (Log.i / Log.e) as a fallback if file IO fails,
 * so even if our logger can't initialize we still have a breadcrumb in logcat
 * that the user can capture via `adb logcat` or bugreport.
 */
class WepWawetApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    /**
     * v0.5.0: Custom Coil ImageLoader with GIF playback + disk cache.
     *
     * - GIF decoder: enables animated GIF preview thumbnails (Steam preview URLs
     *   are often .gif files — without this, only the first frame is shown).
     * - Disk cache: 100 MB persistent cache in app cache dir → faster re-loads.
     * - Memory cache: 25% of available app memory → fast scrolling.
     * - Crossfade: 200ms fade-in animation for smoother image loading.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // GIF decoder for Android 9.0+ (ImageDecoder-based, API 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                }
                // GIF decoder for all versions (movie-based fallback)
                add(GifDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 25% of available memory
                    .build()
            }
            .crossfade(true)
            .crossfade(200) // 200ms fade-in
            .build()
    }

    override fun onCreate() {
        // v0.5.32 (audit M4): Initialize appScope FIRST, before any
        // other initialization step. Previously appScope was initialized
        // at line 159 (the very LAST step of onCreate) — if any earlier
        // step (CrashHandler.install, WepWawetLogger.init,
        // createNotificationChannels) ever touched appScope (directly or
        // transitively), the app would crash with
        // UninitializedPropertyAccessException *before* the user-visible
        // UI initialized.
        //
        // In practice nothing hits appScope during onCreate today, so this
        // was latent — but it's fragile. A future change (e.g., a
        // CrashHandler that wants to log via a coroutine) would crash on
        // boot with no obvious cause. Moving appScope init to the very
        // first line of onCreate eliminates the risk.
        // v0.7.24 (H-U8 BUG FIX): super.onCreate() must run FIRST — it
        // initializes ContentProviders (including WorkManager's default
        // initializer); any provider's onCreate that touched
        // WepWawetApplication.instance hit an
        // UninitializedPropertyAccessException because instance was assigned
        // only after the CrashHandler block. Field inits that must precede
        // super.onCreate() (nothing does, per audit) stay after it.
        super.onCreate()

        appScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                // v0.7.24 (H-U7): uncaught exceptions in appScope children
                // (e.g. a WorkshopDownloadJob packing NPE) propagate to the
                // thread's UncaughtExceptionHandler and CRASH the app from
                // the background. A logged handler keeps the process alive.
                CoroutineExceptionHandler { _, t ->
                    Log.e(TAG, "Uncaught exception in appScope", t)
                    runCatching {
                        com.wepwawet.log.WepWawetLogger.appError(
                            "Uncaught exception in appScope",
                            exception = t)
                    }
                }
        )

        // Set singleton instance (after super.onCreate — see H-U8).
        instance = this

        // Step 1: Install crash handler FIRST so we never miss a crash from the rest of onCreate.
        // This must succeed even if the rest of onCreate fails — wrap in try-catch and log
        // via logcat (the only always-available sink at this point).
        runCatching {
            CrashHandler.install(this)
        }.onFailure { t ->
            Log.e(TAG, "CrashHandler.install failed", t)
        }

        // v0.6.1: Load persisted download history from SharedPreferences
        // v0.7.24 (C-A1 BUG FIX): wrapped — the class KDoc promises every step
        // is independently try-caught, but this call was bare: one throw and
        // the logger init / notification channels / breadcrumb never ran,
        // booting the app half-initialized.
        runCatching {
            com.wepwawet.data.DownloadManager.initialize(this)
        }.onFailure { t ->
            Log.e(TAG, "DownloadManager.initialize failed", t)
        }

        // v0.7.4: load the user's page-size setting into SteamApiService so the
        // "Page size" slider in Settings actually affects queries (it was
        // previously persisted but never read — the API always used 30).
        runCatching {
            com.wepwawet.data.SteamApiService.initPageSize(this)
        }.onFailure { t ->
            Log.e(TAG, "SteamApiService.initPageSize failed", t)
        }

        // v0.9.0: the public-Downloads boot.txt breadcrumb is GONE (user
        // request — "remove the boot txt making"). The in-app logger + the
        // Logs screen's Download button cover diagnostics; a boot.txt in
        // the user's Downloads folder was noise.

        // Step 3: Initialize the logger. If this fails, we can't use WepWawetLogger
        // for the rest of onCreate — fall back to logcat.
        var loggerOk = false
        runCatching {
            WepWawetLogger.init(this)
            loggerOk = true
        }.onFailure { t ->
            Log.e(TAG, "WepWawetLogger.init failed", t)
        }

        // Step 4: Create notification channels for foreground services.
        // Required for Android 8.0+ — without a channel, foreground service
        // notifications silently fail to display, and the system kills the
        // service after 5 seconds for not showing a notification.
        runCatching {
            createNotificationChannels()
        }.onFailure { t ->
            if (loggerOk) WepWawetLogger.appError("createNotificationChannels failed",
                exception = t)
            else Log.e(TAG, "createNotificationChannels failed", t)
        }

        // v0.8.19: kick the Discover preload queue ON APP START ("have
        // them preload on app start") — background, quiet, single-flight;
        // the first Discover visit of a session pops an already-downloaded
        // image instead of hitting the network.
        runCatching {
            com.wepwawet.discover.DiscoverPreload.kick(this)
        }.onFailure { t ->
            Log.e(TAG, "DiscoverPreload.kick failed", t)
        }

        // Step 5: Log a startup-complete event with build info.
        if (loggerOk) {
            runCatching {
                WepWawetLogger.appInfo("WepWawetApplication.onCreate complete",
                    "app_version" to BuildConfig.VERSION_NAME,
                    "git_sha" to BuildConfig.GIT_SHA,
                    "build_time" to BuildConfig.BUILD_TIME,
                    "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android_version" to Build.VERSION.SDK_INT.toString(),
                    "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                )
            }
        } else {
            // Logger failed — write directly to logcat as a last resort
            Log.i(TAG, "onCreate complete (logger failed — logcat only): " +
                "version=${BuildConfig.VERSION_NAME} sha=${BuildConfig.GIT_SHA}")
        }

        // NOTE: appScope was initialized at the TOP of onCreate (audit M4).
        // The old init at this point has been removed.
    }

    // v0.9.0: writeBootBreadcrumb / appendToBootBreadcrumb / findBootFile were
    // REMOVED — the public-Downloads boot.txt breadcrumb is gone (user request).
    // Crash-before-onCreate diagnostics come from logcat + the CrashHandler.

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        // v0.7.4: create the channel ID the PairingService actually uses
        // ("wepwawet_pairing" — see PairingService.CHANNEL_ID). This used to
        // create "wepwawet.proxy", a DIFFERENT id that no notification was ever
        // posted to — a dead channel alongside the real one the service creates
        // itself. Creating the same id here is idempotent (first creation wins)
        // and just means the channel exists even before the service first runs.
        nm.createNotificationChannel(
            NotificationChannel(
                "wepwawet_pairing",
                getString(R.string.notif_proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_proxy_channel_desc)
                setShowBadge(false)
            }
        )

        // v0.8.6: the DownloadService channel — created here too (same
        // rationale as the pairing channel above: the channel exists even
        // before the service's first run; creation is idempotent).
        nm.createNotificationChannel(
            NotificationChannel(
                "wepwawet_downloads",
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Workshop wallpaper downloads"
                setShowBadge(false)
            }
        )
    }

    /**
     * WorkManager Configuration — uses our explicit min-shred-logging initializer
     * (we don't want WorkManager's default to spam logcat).
     */
    // v0.7.24 (M-U19): cached — the getter built a new Configuration on
    // every access (WorkManager reads it multiple times at init).
    override val workManagerConfiguration: Configuration by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
    }

    companion object {
        private const val TAG = "WepWawet-App"
        // (v0.7.4: the unused CHANNEL_PROXY = "wepwawet.proxy" constant was
        //  removed — the app now creates the "wepwawet_pairing" channel id that
        //  PairingService actually posts to. See createNotificationChannels.)

        /**
         * Singleton Application instance. Set in onCreate(). Used by
         * MpkgPacker.texCacheLookup/texCacheStore to access cacheDir
         * without passing Context through the call chain.
         */
        // v0.7.24 (M-U18): @Volatile — readers on other threads (native
        // upcall, binder thread) must observe the write from onCreate.
        @Volatile lateinit var instance: WepWawetApplication
            private set

        /**
         * App-scoped CoroutineScope. Initialized in onCreate. Use for
         * background work that must outlive any Activity/Composable
         * (e.g. WorkshopDownloadJob launches downloads here so the user
         * can navigate away without cancelling the download).
         */
        lateinit var appScope: CoroutineScope
            private set
    }
}
