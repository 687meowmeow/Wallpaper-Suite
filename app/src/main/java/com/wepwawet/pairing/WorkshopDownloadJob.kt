package com.wepwawet.pairing

import android.content.Context
import android.os.PowerManager
import com.wepwawet.WepWawetApplication
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton holding the state of in-flight (or last-completed) workshop
 * downloads. The state is exposed as a [StateFlow] so any Composable can
 * observe it — including after the user has navigated away from the screen
 * that originally started the download.
 *
 * The download itself is launched on [WepWawetApplication.appScope], which
 * survives Activity/Composable destruction. So the user can:
 *  - Start a download from the detail screen
 *  - Navigate back to the browse list
 *  - Come back later and the download will either still be running, or have
 *    finished and saved the .mpkg to Downloads.
 *
 * v0.7.20: CONCURRENT DOWNLOADS — multiple downloads can now run at the
 * same time. The old singleton had a single `Job?` + `State.isRunning`
 * guard that serialized all downloads (launching a second while the first
 * was running was rejected). Now each item has its own [State] in
 * [_states] and its own [Job] in [jobs], keyed by workshop item ID. The
 * detail screen observes [stateFor] for its specific item, so the
 * "Send to phone" button is enabled per-item (not globally).
 */
object WorkshopDownloadJob {

    // v0.7.4: settings keys duplicated from SettingsScreen (whose constants
    // are private). Kept in sync with SettingsScreen's PREFS_NAME /
    // KEY_PKG_CACHE_ENABLED — same pattern WepWawetLogger uses for the
    // verbose_log key.
    private const val WEPWAWET_SETTINGS_PREFS = "wepwawet_settings"
    private const val WEPWAWET_KEY_PKG_CACHE_ENABLED = "pkg_cache_enabled"

    /** Snapshot of the current (or last) download's state. */
    data class State(
        val isRunning: Boolean = false,
        /** 0..1 — chunk-level progress reported by JavaSteam. May stay at 0
         *  for small downloads where the entire payload fits in one chunk. */
        val progress: Float = 0f,
        /** Human-readable status line (e.g. "Connected to Steam — authenticating..."). */
        val status: String = "",
        /** Workshop item id this download is for. */
        val workshopId: String? = null,
        /** Workshop item title — for the UI to show. */
        val workshopTitle: String? = null,
        /** Set when the download succeeded — final user-facing message. */
        val resultMessage: String? = null,
        /** Set when the download failed — final user-facing error message. */
        val errorMessage: String? = null,
        /** Epoch ms when the download started (for elapsed-time UI). */
        val startedAt: Long = 0L,
        /** Epoch ms when the download ended (success or failure). */
        val endedAt: Long = 0L,
        /** True after at least one chunk-level progress callback has fired —
         *  used by the UI to decide between determinate vs indeterminate bar. */
        val hasChunkProgress: Boolean = false,
        /** Final saved file path (success only) — informational. */
        val savedFilePath: String? = null,
        /** Final saved file size in bytes (success only). */
        val savedFileSize: Long = 0L,
        /** v0.7.1 (round 10): When the downloaded .pkg is a video, this is
         *  set to the downloaded file path so the UI can show a dialog asking
         *  "Do you want to trim the video?" before proceeding with pack + queue.
         *  Null for non-video wallpapers (the normal pack + queue flow runs
         *  immediately). */
        val pendingVideoFile: String? = null,
        /** v0.7.1 (round 10): The workshop item associated with pendingVideoFile. */
        val pendingVideoItem: WorkshopItemTyped? = null,
        /**
         * v0.9.0: What happens when the user finishes the pending-video
         * dialog. "send" = the classic send-to-phone flow (pack → queue to
         * the paired phone). "library" = the integrated-engine flow (pack →
         * import into this app's library) — the Download button's path,
         * which lost its trim prompt when the library flow was introduced
         * in v0.8.1 and the user explicitly asked for it back.
         */
        val pendingVideoMode: String = "send",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // v0.7.20: Per-item state map for concurrent downloads. Each item gets
    // its own State entry, so the detail screen can observe just its item's
    // download progress without being affected by other concurrent downloads.
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    // v0.7.20: Per-item job map. Each concurrent download has its own Job,
    // keyed by workshop item ID. Replaced the old single `private var job:
    // Job?` that serialized all downloads.
    private val jobs = ConcurrentHashMap<String, Job>()

    /** v0.7.24 (H-D6): remove a completed job's own map entry (no-op if a
     *  newer job already replaced it). Called from launchSafely's finally. */
    internal fun removeJobEntry(itemId: String, job: Job?) {
        if (job != null) jobs.remove(itemId, job)
    }

    // v0.7.20: Cached StateFlows for stateFor() — creating a new StateFlow
    // per recomposition would leak, so we cache one per item ID.
    private val stateFlows = ConcurrentHashMap<String, StateFlow<State>>()

    /**
     * v0.7.20: Returns a [StateFlow] for a specific workshop item's download
     * state. The detail screen calls this to observe per-item progress /
     * status / pending-video state. Multiple concurrent downloads each have
     * their own independent state flow.
     */
    fun stateFor(itemId: String): StateFlow<State> =
        stateFlows.computeIfAbsent(itemId) { id ->
            _states
                // v0.7.24 (H-D7 BUG FIX): was SharingStarted.Eagerly — every
                // item whose detail screen was ever opened kept a
                // permanently-active upstream coroutine on appScope forever
                // (browsing 500 wallpapers = 500 leaked coroutines + flows).
                // WhileSubscribed stops the upstream when no UI collects it
                // (with a 5s grace window across recompositions), while the
                // cached StateFlow itself still replays the last value to any
                // new collector instantly.
                .map { it[id] ?: State() }
                .stateIn(WepWawetApplication.appScope, SharingStarted.WhileSubscribed(5000), State())
        }

    /**
     * v0.7.20: Returns the pending video file path + item for a specific
     * workshop item, or null if the item has no pending video. Used by the
     * NavHost when navigating to the VideoTrimScreen (the video file path
     * can't be passed as a nav arg because paths contain slashes).
     */
    fun pendingVideoFor(itemId: String): Pair<String, WorkshopItemTyped>? {
        val s = _states.value[itemId] ?: return null
        val path = s.pendingVideoFile ?: return null
        val item = s.pendingVideoItem ?: return null
        return path to item
    }

    /** v0.9.0: The pending-video mode for an item ("send" | "library"). */
    fun pendingVideoModeFor(itemId: String): String =
        _states.value[itemId]?.pendingVideoMode ?: "send"

    /**
     * v0.8.11: the item ids of downloads CURRENTLY in flight (or waiting
     * on a trim decision) — the ONLY cache files "Clear cache" must not
     * touch. Every cache artifact of a workshop item is either named
     * `<id>_<sendId>_*.pkg/.mpkg`, `<id>_trimmed.mp4` or lives under
     * `workshop_downloads/<id>/`, so an id match is enough.
     *
     * The old clear-cache guard skipped every `*_debug.pkg`/`*_send.pkg`
     * file by PATTERN — i.e. it also skipped the leftovers of every
     * COMPLETED download, so the cache never shrank ("the clear cache
     * button doesn't work anymore"). This is the precise replacement.
     */
    fun activeDownloadItemIds(): Set<String> =
        _states.value.values
            .filter { (it.isRunning && it.workshopId != null) || it.pendingVideoFile != null }
            .mapNotNull { s -> s.workshopId ?: s.pendingVideoItem?.id }
            .toSet()

    /**
     * v0.7.20: Update a specific item's state.
     *
     * v0.7.24 (H-D9): the legacy `_state` mirror previously wrote the
     * calling item's state on EVERY update — with concurrent downloads, two
     * items alternated overwriting it, so any remaining `state` collector
     * saw the progress flicker between items. The mirror is now only
     * maintained for single-download sessions (exactly one item with a job
     * in the map); when multiple downloads are in flight the mirror is
     * cleared to the neutral State rather than flickering. All real
     * consumers were migrated to [stateFor] in v0.7.20-v0.7.23; the mirror
     * exists only for compatibility.
     */
    private fun updateState(itemId: String, update: (State) -> State) {
        _states.update { current ->
            val existing = current[itemId] ?: State(workshopId = itemId)
            current + (itemId to update(existing))
        }
        val running = jobs.count { it.value.isActive }
        _state.value = when {
            running <= 1 -> _states.value[itemId] ?: State()
            else -> State()
        }
    }

    // === v0.5.9-alpha (R8 fix): mirror state into DownloadManager so the
    //     Downloads screen + WallpaperCard "Downloading…" overlay + "✓" badge
    //     actually populate. Previously (v0.5.0-v0.5.8) WallpaperDetailScreen
    //     used inline scope.launch coroutines and NEVER called
    //     DownloadManager.start/complete/fail — the Downloads top-bar icon
    //     navigated to an empty screen. Now that the screen delegates to
    //     launchSendToPhone/launchDebugDownload (v0.4.14 pattern, restored
    //     in R1 fix), we wire DownloadManager at these strategic points.
    //
    // === v0.5.32-alpha (audit §1 fix): EXTEND the mirror to PROGRESS +
    //     STATUS updates, not just start/complete/fail/cancel. The original
    //     R8 mirror was start/complete/fail/cancel only, with the rationale
    //     that "the granular progress bar is already shown on the detail
    //     screen". But the Downloads screen (DownloadManagerScreen) ALSO
    //     renders `download.progress` as a progress bar + percentage text,
    //     and the WallpaperCard overlay ALSO renders `activeDownload.progress`
    //     as a progress bar + status message. So with the R8-only mirror,
    //     both UI surfaces got stuck at the initial "0% / Connecting to
    //     Steam…" state for the entire download duration until the entry
    //     was removed by completeDownload()/failDownload(). The user
    //     perceived this as "the download is frozen".
    //
    //     Fix: call DownloadManager.updateProgress() from the onProgress
    //     and onStatusUpdate callbacks of WorkshopDownloader.downloadWorkshopPkg,
    //     AND from each `_state.update { ... }` call in the packing /
    //     saving / queueing phases (so the Downloads screen sees the same
    //     phase transitions as the detail screen). ===
    private fun onDownloadStarted(item: WorkshopItemTyped) {
        com.wepwawet.data.DownloadManager.startDownload(item)
        // v0.8.6: start the foreground DownloadService so the download
        // survives the app being backgrounded and the user sees a sticky
        // progress notification. The service auto-stops when the active
        // downloads map becomes empty.
        runCatching {
            com.wepwawet.pairing.DownloadService.startForDownload(
                com.wepwawet.WepWawetApplication.instance)
        }
    }

    /**
     * Mirror a progress + status update into [com.wepwawet.data.DownloadManager].
     *
     * Called from the onProgress / onStatusUpdate callbacks of
     * [WorkshopDownloader.downloadWorkshopPkg] AND from each phase
     * transition in [launchDebugDownload] / [launchSendToPhone] (downloading →
     * packing → saving / queueing).
     *
     * If the item is no longer in [com.wepwawet.data.DownloadManager.activeDownloads]
     * (e.g., the user pressed Cancel from the Downloads screen, or the
     * download has already completed/failed), [com.wepwawet.data.DownloadManager.updateProgress]
     * is a no-op — see its `current[itemId] ?: return@update current` guard.
     * So this is safe to call even after the active entry has been removed.
     */
    private fun onDownloadProgress(
        itemId: String,
        progress: Float,
        status: com.wepwawet.data.DownloadStatus,
        statusMessage: String,
    ) {
        com.wepwawet.data.DownloadManager.updateProgress(
            itemId = itemId,
            progress = progress,
            status = status,
            statusMessage = statusMessage,
        )
    }

    private fun onDownloadCompleted(item: WorkshopItemTyped, fileSizeBytes: Long, deviceName: String) {
        com.wepwawet.data.DownloadManager.completeDownload(item.id, fileSizeBytes, deviceName)
    }

    /**
     * v0.8.0: copies a finished .mpkg into the integrated engine's library
     * (`filesDir/downloads/`) so it shows up on the main page immediately —
     * every completed workshop download is now auto-imported, no PC round-trip
     * needed. Failures are logged and never break the download flow.
     *
     * v0.8.1: returns whether the engine ACCEPTED the wallpaper (version
     * check + copy). A rejected version deletes the target and returns false
     * so the caller can surface a proper failure.
     */
    /**
     * v0.8.18 (review B14): THE single parser for the library's workshop
     * import naming convention ("downloads/<itemId> - <title>.mpkg", built
     * by [autoImportToLibrary] below). The pattern used to be hand-copied in
     * three places (LibraryScreen, EngineLibrary.deleteWallpaper,
     * WallpaperPreviewScreen) — any naming change had to be replicated three
     * times, and a miss silently broke the "Open workshop page" button and
     * the delete-cache cleanup for that site.
     *
     * @return the workshop item id parsed from a library-relative path, or
     *   null when the path is not a workshop import (folder images, local
     *   imports, suite images).
     */
    fun workshopItemIdOf(filePath: String): String? =
        WORKSHOP_IMPORT_ID_REGEX.find(filePath)?.groupValues?.get(1)

    /** "downloads/3767714202 - Name.mpkg" — the import-naming convention. */
    private val WORKSHOP_IMPORT_ID_REGEX = Regex("^downloads/(\\d{6,}) - ")

    private fun autoImportToLibrary(context: Context, mpkgFile: File, itemId: String, title: String): Boolean {
        return runCatching {
            val engine = com.wepwawet.engine.EngineLibrary.sceneLib(context)
            // v0.8.16: UNICODE-PRESERVING — the old [^\w \-()] stripped every
            // non-ASCII character, so a Japanese-titled wallpaper imported as
            // "shaggy SU" instead of its real name. Only characters that are
            // actually unsafe in filenames are removed now.
            val sanitized = title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "")
                .trim().take(60).ifEmpty { "wallpaper" }
            val name = "${itemId} - $sanitized.mpkg"
            val target = File(context.filesDir, "downloads/$name")
            target.parentFile?.mkdirs()
            mpkgFile.copyTo(target, overwrite = true)
            if (!engine.isWallpaperVersionValid("downloads/$name")) {
                WepWawetLogger.workshopWarn("autoImport: engine rejected version",
                    "file" to name)
                target.delete()
                false
            } else {
                WepWawetLogger.engine("autoImport: wallpaper added to library",
                    "file" to name,
                    "size" to target.length())
                // v0.8.2: post-import verification — the preview screen's
                // "Workshop page" button reads the project.json workshopurl
                // through the engine. Verify it resolves right here so a pack
                // failure is visible in the logs at import time (the preview
                // also has a filename fallback, but this tells us whether the
                // packer or the fallback is doing the work).
                runCatching {
                    val url = engine.getWallpaperProjectString("downloads/$name", "workshopurl")
                    if (url == null) {
                        WepWawetLogger.engineWarn("autoImport: workshopurl not readable from the package",
                            "file" to name,
                            "fallback" to "preview will use the item id from the import filename")
                    } else {
                        WepWawetLogger.engine("autoImport: workshopurl verified",
                            "file" to name,
                            "url" to url)
                    }
                }
                true
            }
        }.getOrElse { e ->
            WepWawetLogger.workshopWarn("autoImport failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
        }
    }

    private fun onDownloadFailed(item: WorkshopItemTyped, errorMessage: String) {
        com.wepwawet.data.DownloadManager.failDownload(item.id, errorMessage)
    }

    private fun onDownloadCancelled(itemId: String?) {
        if (itemId != null) {
            com.wepwawet.data.DownloadManager.cancelDownload(itemId)
        }
    }

    /**
     * v0.8.1: THE Download button. Downloads the real .pkg via JavaSteam on
     * the device, packs it into the .mpkg the engine plays, and imports it
     * straight into the integrated Wallpaper Engine's library
     * (`filesDir/downloads/`) — the wallpaper shows up on the main page ready
     * to preview and apply. This replaces the old "send to phone" +
     * "debug download" pair for the default (pairing-off) flow: no PC round
     * trip, no pairing service, no public Downloads copy.
     *
     * Kept the historical name [launchDebugDownload] so every call site and
     * the pending-video interplay stay untouched — only the destination
     * changed (library import instead of a public Downloads write).
     *
     * v0.7.20: Returns `false` if THIS ITEM already has a download running
     * (other items can download concurrently).
     *
     * @param context app/context (used for cache dir, Steam credentials)
     * @param item workshop item to download
     * @return true if a new download was started
     */
    fun launchDebugDownload(
        context: Context,
        item: WorkshopItemTyped,
    ): Boolean {
        val itemId = item.id
        // v0.7.20: Per-item guard — only reject if THIS item is already
        // downloading. Other items can download concurrently.
        if (jobs[itemId]?.isActive == true) {
            WepWawetLogger.workshop("WorkshopDownloadJob: launch rejected — this item already running",
                "requested_id" to itemId)
            return false
        }

        val appContext = context.applicationContext
        val steamUser = SteamCredentialsStore.getUsername(appContext)
        val steamPass = SteamCredentialsStore.getPassword(appContext)
        if (steamUser == null || steamPass == null) {
            updateState(itemId) { it.copy(
                errorMessage = "Steam login not set. Open Settings → Steam account first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }

        val sendId = System.currentTimeMillis()
        val pkgFile = File(appContext.cacheDir, "${itemId}_${sendId}_debug.pkg")
        val downloader = WorkshopDownloader(steamUser, steamPass)

        updateState(itemId) { it.copy(
            isRunning = true,
            progress = 0f,
            status = "Downloading via Steam…",
            workshopId = itemId,
            workshopTitle = item.title,
            resultMessage = null,
            errorMessage = null,
            startedAt = System.currentTimeMillis(),
            endedAt = 0L,
            hasChunkProgress = false,
            savedFilePath = null,
            savedFileSize = 0L,
            // v0.7.4: clear any stale pending-video state (same as the send
            // path) so a leftover trim-dialog file can't resurface.
            pendingVideoFile = null,
            pendingVideoItem = null,
        ) }
        // v0.5.9-alpha (R8): mirror the start into DownloadManager so the
        // Downloads screen + WallpaperCard "Downloading…" overlay populate.
        onDownloadStarted(item)

        WepWawetLogger.workshop("WorkshopDownloadJob: launching download → library",
            "id" to itemId,
            "title" to item.title.take(80),
            "pkg_cache_path" to pkgFile.absolutePath)

        jobs[itemId] = WepWawetApplication.appScope.launchSafely(itemId) {
            var downloaderRef: WorkshopDownloader? = downloader
            try {
                WepWawetLogger.workshop("WorkshopDownloadJob: coroutine STARTED",
                    "id" to item.id,
                    "thread" to Thread.currentThread().name)
            // v0.7.4: same .pkg cache wiring as the send path (see
            // launchSendToPhone) — reuse a cached scene .pkg when the
            // "Cache .pkg files" setting is enabled.
            val pkgCacheEnabled = appContext.getSharedPreferences(
                WEPWAWET_SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getBoolean(WEPWAWET_KEY_PKG_CACHE_ENABLED, false)
            val cachedPkg = File(File(appContext.cacheDir, "pkg_cache"), "${item.id}.pkg")
            // v0.8.9: the cache is only valid for real SCENE packages (PKGV
            // magic). A preset item cached by the v0.9.0 bug (the Steam
            // preview.gif was copied here as a bogus "<id>.pkg") must never
            // be reused — it would pack the preview image as the wallpaper
            // again. PKGV layout: u32 marker 8, then "PKGV" at offset 4.
            fun isScenePkgCache(file: File): Boolean {
                if (!file.exists() || file.length() < 8) return false
                return try {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        val h = ByteArray(8)
                        raf.readFully(h)
                        h[0] == 0x08.toByte() && h[4] == 'P'.code.toByte() &&
                            h[5] == 'K'.code.toByte() && h[6] == 'G'.code.toByte() &&
                            h[7] == 'V'.code.toByte()
                    }
                } catch (_: Exception) {
                    false
                }
            }
            val canReuseCache = pkgCacheEnabled && cachedPkg.exists() &&
                cachedPkg.length() > 0L && !detectVideoByMagicBytes(cachedPkg) &&
                isScenePkgCache(cachedPkg)
            val downloadedPkg: File? = if (canReuseCache) {
                WepWawetLogger.workshop("WorkshopDownloadJob: reusing cached .pkg (skip Steam download)",
                    "id" to item.id,
                    "path" to cachedPkg.absolutePath,
                    "size" to cachedPkg.length())
                updateState(itemId) { s -> s.copy(status = "Using cached .pkg — packing…") }
                cachedPkg
            } else {
            downloader.downloadWorkshopPkg(
                context = appContext,
                workshopId = item.id,
                outputFile = pkgFile,
                onProgress = { pct ->
                    val coerced = pct.coerceIn(0f, 1f)
                    updateState(itemId) { s -> s.copy(
                        progress = coerced,
                        hasChunkProgress = true,
                    ) }
                    // v0.5.32 (audit §1): mirror the progress + a fresh
                    // status message into DownloadManager so the Downloads
                    // screen + WallpaperCard overlay update live (previously
                    // they got stuck at the initial "0% / Connecting to
                    // Steam…" state for the entire download duration).
                    val statusMsg = if (coerced > 0f)
                        "Downloading… ${(coerced * 100).toInt()}%"
                    else "Connecting to Steam…"
                    onDownloadProgress(
                        itemId = item.id,
                        progress = coerced,
                        status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                        statusMessage = statusMsg,
                    )
                    // Log every 10% increment so we can see progress in logs
                    // (not every callback, which would be very spammy for 6MB
                    // files arriving in many small chunks).
                    val pctInt = (coerced * 100).toInt()
                    if (pctInt % 10 == 0) {
                        WepWawetLogger.workshop("WorkshopDownloadJob: progress",
                            "pct" to pctInt,
                            "raw" to pct)
                    }
                },
                onStatusUpdate = { status ->
                    updateState(itemId) { s -> s.copy(status = status) }
                    // v0.5.32 (audit §1): mirror the status string too so
                    // the Downloads screen's statusMessage text stays in
                    // sync with the detail screen.
                    onDownloadProgress(
                        itemId = item.id,
                        // v0.7.20: read per-item progress (was _state.value.progress
                        // which under concurrent downloads would read the wrong item).
                        progress = _states.value[itemId]?.progress ?: 0f,
                        status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                        statusMessage = status,
                    )
                    WepWawetLogger.workshop("WorkshopDownloadJob: status update",
                        "status" to status.take(150))
                },
            )
            }

            WepWawetLogger.workshop("WorkshopDownloadJob: downloadWorkshopPkg RETURNED",
                "id" to item.id,
                "result_null" to (downloadedPkg == null),
                "result_exists" to (downloadedPkg?.exists() ?: false),
                "result_size" to (downloadedPkg?.length() ?: -1L),
                "output_path" to (downloadedPkg?.absolutePath ?: "null"))

            if (downloadedPkg == null || !downloadedPkg.exists() || downloadedPkg.length() == 0L) {
                // === v0.8.9: PRESET workshop items land here. ===
                // A preset's own download has no .pkg/media — project.json only
                // NAMES a base wallpaper ("dependency") + saved property values
                // ("preset"). WE semantics: play the dependency with the preset's
                // values. Implement the same: download the base and pack it with
                // the values applied.
                //
                // v0.8.9 BUG FIX: the v0.9.0 code reused `downloaderRef` for
                // the base download — but downloadWorkshopPkg's finally block
                // already ran cleanup() (Steam logoff + disconnect + callback
                // thread teardown) before returning null, so the base download
                // could NEVER have connected. A FRESH WorkshopDownloader is
                // created for the base pass (new CM session, own cleanup).
                val presetInstallDir = File(appContext.cacheDir, "workshop_downloads/${item.id}")
                val presetProject = runCatching {
                    downloaderRef?.parseProjectJson(presetInstallDir)
                }.getOrNull()
                if (presetProject?.dependency != null &&
                    presetProject.dependency.toLongOrNull() != null) {
                    val baseId = presetProject.dependency
                    WepWawetLogger.workshop("WorkshopDownloadJob: PRESET detected — downloading base wallpaper",
                        "preset_id" to item.id,
                        "dependency" to baseId,
                        "preset_values" to presetProject.presetValues.size,
                        "type" to (presetProject.type ?: "?"))
                    updateState(itemId) { s -> s.copy(
                        status = "Preset — downloading base wallpaper $baseId…",
                        progress = 0.2f) }
                    onDownloadProgress(
                        itemId = item.id,
                        progress = 0.2f,
                        status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                        statusMessage = "Preset — downloading base wallpaper…")
                    val basePkgFile = File(appContext.cacheDir, "${item.id}_${sendId}_base.pkg")
                    val baseDownloader = WorkshopDownloader(steamUser, steamPass)
                    val baseDownloaded = baseDownloader.downloadWorkshopPkg(
                        context = appContext,
                        workshopId = baseId,
                        outputFile = basePkgFile,
                        onProgress = { pct ->
                            val coerced = pct.coerceIn(0f, 1f)
                            updateState(itemId) { s -> s.copy(progress = coerced) }
                            onDownloadProgress(
                                itemId = item.id,
                                progress = coerced,
                                status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                                statusMessage = "Downloading base wallpaper… ${(coerced * 100).toInt()}%")
                        },
                        onStatusUpdate = { status ->
                            updateState(itemId) { s -> s.copy(status = "Base: $status") }
                        },
                    )
                    if (baseDownloaded != null && baseDownloaded.exists() && baseDownloaded.length() > 0L) {
                        val baseInstallDir = File(appContext.cacheDir, "workshop_downloads/$baseId")
                        val baseMpkg = File(appContext.cacheDir, "${item.id}_${sendId}_base.mpkg")
                        updateState(itemId) { s -> s.copy(
                            status = "Packing preset (base + values) → .mpkg…",
                            progress = 0.85f) }
                        val pm2 = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                        val wakeLock2 = pm2.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WepWawet:MpkgPacker")
                        wakeLock2.setReferenceCounted(false)
                        wakeLock2.acquire(10 * 60 * 1000L)
                        val presetMpkgSize = try {
                            com.wepwawet.pairing.MpkgPacker.packFromPkg(
                                item = item,
                                pkgFile = baseDownloaded,
                                outputFile = baseMpkg,
                                installDir = baseInstallDir,
                                presetValues = presetProject.presetValues,
                                presetTitle = presetProject.title ?: item.title,
                                // v0.8.9: the preset's own files (anything its
                                // values point at) layer over the base's.
                                presetFilesDir = presetInstallDir,
                            )
                        } finally {
                            if (wakeLock2.isHeld) wakeLock2.release()
                        }
                        runCatching { baseDownloaded.delete() }
                        runCatching { baseInstallDir.deleteRecursively() }
                        runCatching { presetInstallDir.deleteRecursively() }
                        if (presetMpkgSize > 0L && baseMpkg.exists()) {
                            val importOk = autoImportToLibrary(appContext, baseMpkg, item.id, item.title)
                            runCatching { baseMpkg.delete() }
                            if (importOk) {
                                val msg = "Preset added to your library ✓ (${formatBytes(presetMpkgSize)}) — base wallpaper $baseId with preset values"
                                WepWawetLogger.workshop("WorkshopDownloadJob: preset added to library",
                                    "preset_id" to item.id,
                                    "base_id" to baseId,
                                    "values_applied" to presetProject.presetValues.size,
                                    "size" to presetMpkgSize)
                                updateState(itemId) { s -> s.copy(
                                    isRunning = false,
                                    progress = 1f,
                                    status = msg,
                                    resultMessage = msg,
                                    endedAt = System.currentTimeMillis(),
                                ) }
                                onDownloadCompleted(item, presetMpkgSize, "Library")
                                return@launchSafely
                            } else {
                                val err = "Failed to import preset into the wallpaper library (see logs)"
                                updateState(itemId) { s -> s.copy(
                                    isRunning = false,
                                    status = err,
                                    errorMessage = err,
                                    endedAt = System.currentTimeMillis()) }
                                onDownloadFailed(item, err)
                                return@launchSafely
                            }
                        } else {
                            val err = "Failed to pack the preset's base wallpaper → .mpkg"
                            updateState(itemId) { s -> s.copy(
                                isRunning = false,
                                status = err,
                                errorMessage = err,
                                endedAt = System.currentTimeMillis()) }
                            onDownloadFailed(item, err)
                            return@launchSafely
                        }
                    } else {
                        val err = "Preset's base wallpaper ($baseId) could not be downloaded"
                        WepWawetLogger.workshopWarn("WorkshopDownloadJob: preset base download failed",
                            "preset_id" to item.id,
                            "dependency" to baseId)
                        updateState(itemId) { s -> s.copy(
                            isRunning = false,
                            status = err,
                            errorMessage = err,
                            endedAt = System.currentTimeMillis()) }
                        onDownloadFailed(item, err)
                        return@launchSafely
                    }
                }
                val err = "Failed to download .pkg via Steam (see logs)"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: pkg download failed/null",
                    "id" to item.id)
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
                return@launchSafely
            }

            updateState(itemId) { s -> s.copy(status = "Packing content → .mpkg…", progress = 0.85f) }
            // v0.5.32 (audit §1): mirror the packing phase transition.
            onDownloadProgress(
                itemId = item.id,
                progress = 0.85f,
                status = com.wepwawet.data.DownloadStatus.PACKING,
                statusMessage = "Packing content → .mpkg…",
            )
            val cacheMpkg = File(appContext.cacheDir, "${item.id}_${sendId}_debug.mpkg")
            val installDir = File(appContext.cacheDir, "workshop_downloads/${item.id}")
            // === v0.4.9: Detect video via MAGIC BYTES, not file extension.
            // The downloaded file is always named *.pkg by WorkshopDownloader
            // (the outputFile parameter is ${item.id}_${sendId}_debug.pkg), so
            // checking the extension .mp4 NEVER works. Instead, check the
            // file's magic bytes:
            //   - MP4 files have "ftyp" at offset 4
            //   - PKGV (.pkg) files have "PKGV" at offset 4
            val isVideo = detectVideoByMagicBytes(downloadedPkg)
            // v0.7.4 (parity G2): image-type downloads (JPEG/PNG/WebP/BMP/GIF)
            // pack as a Scene via packSceneWallpaper instead of failing the
            // .pkg parse.
            val isImage = !isVideo && detectImageByMagicBytes(downloadedPkg)

            // === v0.9.0: the clip option is BACK in the library flow. ===
            // v0.8.1 redirected the Download button into the integrated
            // engine library but kept the trim prompt ONLY on the send-to-phone
            // path — so video wallpapers lost their "clip before adding"
            // option entirely. Pause here (exactly like the send path does)
            // and let the detail screen's dialog offer Clip / Add Full.
            // Offered only for containers the trim UI can actually play +
            // cut (ExoPlayer + MediaExtractor): MP4 family and WebM/MKV.
            // GIF/AVI/WMV go straight to their transcoders instead.
            if (isVideo && isTrimmableVideo(downloadedPkg)) {
                WepWawetLogger.workshop("WorkshopDownloadJob: video paused for clip decision (library flow)",
                    "id" to item.id,
                    "file" to downloadedPkg.name,
                    "size" to downloadedPkg.length())
                updateState(itemId) { s -> s.copy(
                    pendingVideoFile = downloadedPkg.absolutePath,
                    pendingVideoItem = item,
                    pendingVideoMode = "library",
                    isRunning = false,
                    status = "Video downloaded — clip it or add the full video",
                ) }
                // The finally block below calls downloader.cleanup() but the
                // video file must SURVIVE for the trim screen — it lives in
                // cacheDir, not the install dir, so cleanup() is safe.
                return@launchSafely
            }

            WepWawetLogger.workshop("WorkshopDownloadJob: packing content → .mpkg",
                "input_file" to downloadedPkg.absolutePath,
                "input_size" to downloadedPkg.length(),
                "mpkg_output" to cacheMpkg.absolutePath,
                "is_video" to isVideo,
                "is_image" to isImage)
            // Acquire a PARTIAL_WAKE_LOCK during packing to prevent CPU from
            // sleeping. ETC2 encoding is CPU-intensive and can take minutes
            // for large textures — without a wake lock, the device may throttle
            // or sleep mid-pack, drastically increasing pack time.
            // v0.7.24 (M-D15): appContext (not `context`) — the parameter can
            // be an Activity; holding its getSystemService result is fine here
            // (POWER_SERVICE returns a system singleton) but the pattern was
            // inconsistent with the other two call sites and risks holding an
            // Activity reference in a coroutine that outlives it.
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WepWawet:MpkgPacker")
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(10 * 60 * 1000L) // 10 minute timeout
            WepWawetLogger.workshop("WorkshopDownloadJob: acquired wake lock for packing",
                "timeout_ms" to (10 * 60 * 1000L))
            val mpkgSize = try {
                // v0.7.21 BUG FIX: was `runCatching { ... }.getOrElse { e -> ... }`
                // which swallowed CancellationException (runCatching catches Throwable).
                // Now uses explicit try/catch that re-throws CancellationException
                // so structured concurrency is preserved.
                try {
                    if (isVideo) {
                        WepWawetLogger.workshop("WorkshopDownloadJob: calling packVideoWallpaper",
                            "video_file" to downloadedPkg.name,
                            "video_size" to downloadedPkg.length())
                        val previewFile = File(installDir, "preview.jpg")
                            .takeIf { it.exists() }
                            ?: File(installDir, "preview.gif")
                                .takeIf { it.exists() }
                        com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                            item = item,
                            videoFile = downloadedPkg,
                            outputFile = cacheMpkg,
                            previewFile = previewFile,
                        )
                    } else if (isImage) {
                        // v0.7.4 (parity G2): image-type Workshop downloads pack
                        // as a Scene (single fullscreen quad).
                        WepWawetLogger.workshop("WorkshopDownloadJob: calling packSceneWallpaper (image item)",
                            "image_file" to downloadedPkg.name,
                            "image_size" to downloadedPkg.length())
                        com.wepwawet.pairing.MpkgPacker.packSceneWallpaper(
                            item = item,
                            imageFile = downloadedPkg,
                            outputFile = cacheMpkg,
                        )
                    } else {
                        WepWawetLogger.workshop("WorkshopDownloadJob: calling packFromPkg",
                            "pkg_file" to downloadedPkg.name,
                            "pkg_size" to downloadedPkg.length())
                        com.wepwawet.pairing.MpkgPacker.packFromPkg(
                            item = item,
                            pkgFile = downloadedPkg,
                            outputFile = cacheMpkg,
                            installDir = installDir,
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val err = "Failed to pack → .mpkg: ${e.message}"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob: pack failed",
                        "exception" to e.javaClass.name, "message" to (e.message ?: ""),
                        "is_video" to isVideo)
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadFailed(item, err)
                    // v0.7.4: don't delete the CACHE entry on failure — only
                    // the freshly-downloaded temp file.
                    if (downloadedPkg !== cachedPkg) runCatching { downloadedPkg.delete() }
                    return@launchSafely
                }
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    WepWawetLogger.workshop("WorkshopDownloadJob: released wake lock")
                }
            }

            WepWawetLogger.workshop("WorkshopDownloadJob: pack done",
                "mpkg_output_size" to mpkgSize,
                "mpkg_output_exists" to cacheMpkg.exists(),
                "is_video" to isVideo)

            if (mpkgSize == 0L || !cacheMpkg.exists()) {
                val err = "Failed to pack .pkg → .mpkg (empty output)"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: empty mpkg output",
                    "size" to mpkgSize,
                    "exists" to cacheMpkg.exists(),
                    "pkg_input_size" to downloadedPkg.length())
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
                if (downloadedPkg !== cachedPkg) runCatching { downloadedPkg.delete() }
                return@launchSafely
            }

            updateState(itemId) { s -> s.copy(status = "Importing into your wallpaper library…", progress = 0.95f) }
            // v0.5.32 (audit §1): mirror the import phase transition so the
            // Downloads screen shows what's happening.
            onDownloadProgress(
                itemId = item.id,
                progress = 0.95f,
                status = com.wepwawet.data.DownloadStatus.PACKING,
                statusMessage = "Importing into your wallpaper library…",
            )

            // v0.8.1: THE destination — the integrated engine's library.
            // (v0.8.0 called this after a public-Downloads write; the public
            // copy is gone now: the button downloads straight into the
            // Wallpaper Engine running inside this app, exactly as asked.)
            // v0.8.16: unicode-preserving sanitizer (see autoImportToLibrary).
            val sanitized = item.title.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "")
                .trim().take(60).ifEmpty { "wallpaper" }
            val libraryName = "${item.id} - $sanitized.mpkg"
            val importOk = autoImportToLibrary(appContext, cacheMpkg, item.id, item.title)

            // v0.7.4: store the .pkg in the cache when the setting is enabled
            // (and this was a fresh scene download), then clean up the temp
            // copy — mirrors the send path.
            if (downloadedPkg != null && downloadedPkg !== cachedPkg) {
                if (pkgCacheEnabled && !isVideo) {
                    runCatching {
                        cachedPkg.parentFile?.mkdirs()
                        downloadedPkg.copyTo(cachedPkg, overwrite = true)
                    }
                }
                runCatching { downloadedPkg.delete() }
            }
            runCatching { cacheMpkg.delete() }
            // === Delete install directory (was deferred from WorkshopDownloader
            // so packFromPkg/packVideoWallpaper could read standalone files like
            // project.json and preview.jpg from it) ===
            runCatching { installDir.deleteRecursively() }
            WepWawetLogger.workshop("WorkshopDownloadJob: install dir cleaned up",
                "path" to installDir.absolutePath)

            if (importOk) {
                val msg = "Added to your wallpaper library ✓ (${formatBytes(mpkgSize)}) — open it on the main page"
                WepWawetLogger.workshop("WorkshopDownloadJob: wallpaper added to library",
                    "file" to libraryName,
                    "size" to mpkgSize)
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    progress = 1f,
                    status = msg,
                    resultMessage = msg,
                    endedAt = System.currentTimeMillis(),
                    savedFilePath = "downloads/$libraryName",
                    savedFileSize = mpkgSize,
                ) }
                onDownloadCompleted(item, mpkgSize, "Library")
            } else {
                val err = "Failed to import into the wallpaper library (see logs)"
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
            }
            } finally {
                // === CRITICAL (v0.4.7): ALWAYS call cleanup() on the WorkshopDownloader,
                // even on success — otherwise the SteamClient's TCP connection to Steam
                // stays alive, blocking subsequent download attempts (Steam rejects
                // the second login with the same loginID from the same IP → AccessDenied).
                // ===
                WepWawetLogger.workshop("WorkshopDownloadJob: cleaning up SteamClient",
                    "id" to item.id)
                runCatching { downloaderRef?.cleanup() }
                downloaderRef = null
            }
        }
        return true
    }

    /**
     * Start "Send to phone" — produces the EXACT same .mpkg as [launchDebugDownload]
     * (same installDir handling, same MpkgPacker.packFromPkg call signature) and queues
     * it for transfer to the paired phone via [PairingService.queueWallpaper].
     *
     * === CRITICAL (v0.4.7): Previously, send-to-phone used a SEPARATE code path
     * in `WallpaperDetailScreen.onSendToPhoneClicked` that did NOT pass `installDir`
     * to `MpkgPacker.packFromPkg`. The debug download DID pass installDir, so the
     * two flows produced DIFFERENT .mpkg files — debug worked, send-to-phone
     * produced a black wallpaper on the phone (missing project.json + preview.jpg
     * standalone files that the WE Android app's renderer needs).
     *
     * Now: send-to-phone uses the SAME code path as debug download (this method),
     * just with a different final step (queue for transfer vs. save to Downloads).
     * The .mpkg byte content is identical, so the rendering is identical.
     *
     * User explicitly said: "send to phone should work smoothly without compromises.
     * it should make the same thing the debug download makes and sends it perfectly."
     *
     * NOTE: We do NOT save a backup .mpkg to Downloads (the user said "saving it
     * downloads defeats the point thats what debug download works" — the debug
     * download button exists separately for that purpose).
     *
     * @param context app/context (used for cache dir, Steam credentials)
     * @param item workshop item to download + send
     * @return true if a new download was started
     */
    fun launchSendToPhone(
        context: Context,
        item: WorkshopItemTyped,
    ): Boolean {
        val itemId = item.id
        if (jobs[itemId]?.isActive == true) {
            WepWawetLogger.workshop("WorkshopDownloadJob: launch rejected — this item already running",
                "requested_id" to itemId)
            return false
        }

        val appContext = context.applicationContext
        val steamUser = SteamCredentialsStore.getUsername(appContext)
        val steamPass = SteamCredentialsStore.getPassword(appContext)
        if (steamUser == null || steamPass == null) {
            updateState(itemId) { it.copy(
                errorMessage = "Steam login not set. Open Settings → Steam account first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }
        if (!PairingService.isPaired()) {
            updateState(itemId) { it.copy(
                errorMessage = "Not paired — open the Pairing tab first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }
        // v0.8.18 (review B1): isPaired() also returns true for a REMEMBERED
        // pairing (SharedPreferences) while the pairing service/server is
        // stopped. The old flow ran the whole download → pack pipeline on the
        // strength of that remembered name, then queueWallpaper() silently
        // dropped the .mpkg (no live server) and the flow still reported
        // success. Gate on a live server instead — a dead proxy must fail
        // fast, before any bytes are downloaded.
        if (PairingService.pairingServer == null) {
            updateState(itemId) { it.copy(
                errorMessage = "Pairing service is not running — open the " +
                    "Pairing tab (or the pairing toggle in Settings) and " +
                    "reconnect the phone first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }

        val sendId = System.currentTimeMillis()
        val pkgFile = File(appContext.cacheDir, "${item.id}_${sendId}_send.pkg")
        val downloader = WorkshopDownloader(steamUser, steamPass)

        updateState(itemId) { it.copy(
            isRunning = true,
            progress = 0f,
            status = "Sending to phone — downloading real .pkg via JavaSteam…",
            workshopId = item.id,
            workshopTitle = item.title,
            resultMessage = null,
            errorMessage = null,
            startedAt = System.currentTimeMillis(),
            endedAt = 0L,
            hasChunkProgress = false,
            savedFilePath = null,
            savedFileSize = 0L,
            // v0.7.4: clear any stale pending-video state left over from a
            // previous download — otherwise the trim dialog could reappear
            // for a file that no longer belongs to this session.
            pendingVideoFile = null,
            pendingVideoItem = null,
        ) }
        // v0.5.9-alpha (R8): mirror the start into DownloadManager so the
        // Downloads screen + WallpaperCard "Downloading…" overlay populate.
        onDownloadStarted(item)

        WepWawetLogger.workshop("WorkshopDownloadJob: launching send-to-phone",
            "id" to item.id,
            "title" to item.title.take(80),
            "pkg_cache_path" to pkgFile.absolutePath,
            "paired_device" to (PairingService.pairedDeviceName ?: "null"))

        jobs[itemId] = WepWawetApplication.appScope.launchSafely(itemId) {
            var downloaderRef: WorkshopDownloader? = downloader
            try {
                WepWawetLogger.workshop("WorkshopDownloadJob.send: coroutine STARTED",
                    "id" to item.id,
                    "thread" to Thread.currentThread().name)
                // v0.7.4: WIRE UP the "Cache .pkg files" setting (it was
                // previously persisted but read by NO ONE — a pure UI no-op).
                // When enabled, a previously-downloaded scene .pkg for this
                // item is reused and the fresh download is copied to the
                // stable cache path after a successful pack. Only SCENE
                // packages are cached — video files flow through the trim
                // dialog lifecycle where they get consumed/deleted.
                val pkgCacheEnabled = appContext.getSharedPreferences(
                    WEPWAWET_SETTINGS_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(WEPWAWET_KEY_PKG_CACHE_ENABLED, false)
                val cachedPkg = File(File(appContext.cacheDir, "pkg_cache"), "${item.id}.pkg")
                // v0.8.9: PKGV-magic guard (same as the library path) — a
                // bogus cache entry (e.g. a v0.9.0 preset's preview.gif)
                // must never be reused as scene content.
                fun isScenePkgCache(file: File): Boolean {
                    if (!file.exists() || file.length() < 8) return false
                    return try {
                        java.io.RandomAccessFile(file, "r").use { raf ->
                            val h = ByteArray(8)
                            raf.readFully(h)
                            h[0] == 0x08.toByte() && h[4] == 'P'.code.toByte() &&
                                h[5] == 'K'.code.toByte() && h[6] == 'G'.code.toByte() &&
                                h[7] == 'V'.code.toByte()
                        }
                    } catch (_: Exception) {
                        false
                    }
                }
                val canReuseCache = pkgCacheEnabled && cachedPkg.exists() &&
                    cachedPkg.length() > 0L && !detectVideoByMagicBytes(cachedPkg) &&
                    isScenePkgCache(cachedPkg)
                val downloadedPkg: File? = if (canReuseCache) {
                    WepWawetLogger.workshop("WorkshopDownloadJob.send: reusing cached .pkg (skip Steam download)",
                        "id" to item.id,
                        "path" to cachedPkg.absolutePath,
                        "size" to cachedPkg.length())
                    updateState(itemId) { s -> s.copy(status = "Using cached .pkg — packing…") }
                    cachedPkg
                } else {
                    downloader.downloadWorkshopPkg(
                    context = appContext,
                    workshopId = item.id,
                    outputFile = pkgFile,
                    onProgress = { pct ->
                        val coerced = pct.coerceIn(0f, 1f)
                        updateState(itemId) { s -> s.copy(
                            progress = (coerced * 0.8f),
                            hasChunkProgress = true,
                        ) }
                        // v0.5.32 (audit §1): mirror progress + status
                        // message into DownloadManager so the Downloads
                        // screen + WallpaperCard overlay update live.
                        val mirroredProgress = coerced * 0.8f
                        val statusMsg = if (coerced > 0f)
                            "Downloading… ${(coerced * 100).toInt()}%"
                        else "Connecting to Steam…"
                        onDownloadProgress(
                            itemId = item.id,
                            progress = mirroredProgress,
                            status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                            statusMessage = statusMsg,
                        )
                        val pctInt = (coerced * 100).toInt()
                        if (pctInt % 10 == 0) {
                            WepWawetLogger.workshop("WorkshopDownloadJob.send: progress",
                                "pct" to pctInt)
                        }
                    },
                    onStatusUpdate = { status ->
                        updateState(itemId) { s -> s.copy(status = status) }
                        // v0.5.32 (audit §1): mirror the status string too.
                        onDownloadProgress(
                            itemId = item.id,
                            // v0.7.20: read per-item progress (was _state.value.progress).
                            progress = _states.value[itemId]?.progress ?: 0f,
                            status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                            statusMessage = status,
                        )
                        WepWawetLogger.workshop("WorkshopDownloadJob.send: status",
                            "status" to status.take(150))
                    },
                )
                }

                WepWawetLogger.workshop("WorkshopDownloadJob.send: downloadWorkshopPkg RETURNED",
                    "id" to item.id,
                    "result_null" to (downloadedPkg == null),
                    "result_size" to (downloadedPkg?.length() ?: -1L))

                if (downloadedPkg == null || !downloadedPkg.exists() || downloadedPkg.length() == 0L) {
                    // v0.8.9: preset items have no content of their own — the
                    // send-to-phone flow doesn't resolve their base wallpaper;
                    // give a clear message instead of the generic failure.
                    val presetProject = runCatching {
                        downloaderRef?.parseProjectJson(
                            File(appContext.cacheDir, "workshop_downloads/${item.id}"))
                    }.getOrNull()
                    val err = if (presetProject?.dependency != null &&
                        presetProject.dependency.toLongOrNull() != null) {
                        "This is a preset wallpaper — use Download (adds it to your " +
                            "library with its base wallpaper) instead of send-to-phone"
                    } else {
                        "Failed to download .pkg via Steam (see logs)"
                    }
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: pkg download failed/null",
                        "id" to item.id,
                        "is_preset" to (presetProject?.dependency != null))
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadFailed(item, err)
                    return@launchSafely
                }

                // === Pack to .mpkg using SAME code path as debug download
                // (with installDir!) so the resulting .mpkg is byte-equivalent
                // to what debug download produces. The installDir contains
                // project.json + preview.jpg standalone files that the WE
                // Android app's renderer needs — without them, the wallpaper
                // renders as a black image.
                //
                // v0.4.9: Detect video via MAGIC BYTES (not extension — the
                // outputFile is always .pkg-named by WorkshopDownloader).
                //   - MP4 files have "ftyp" at offset 4
                //   - PKGV (.pkg) files have "PKGV" at offset 4
                // ===
                updateState(itemId) { s -> s.copy(
                    status = "Packing content → .mpkg (might take a while)…",
                    progress = 0.85f,
                ) }
                // v0.5.32 (audit §1): mirror the packing phase transition
                // (same as the debug download path).
                onDownloadProgress(
                    itemId = item.id,
                    progress = 0.85f,
                    status = com.wepwawet.data.DownloadStatus.PACKING,
                    statusMessage = "Packing content → .mpkg (might take a while)…",
                )
                val cacheMpkg = File(appContext.cacheDir, "${item.id}_${sendId}_send.mpkg")
                val installDir = File(appContext.cacheDir, "workshop_downloads/${item.id}")
                val isVideo = detectVideoByMagicBytes(downloadedPkg)
                // v0.7.4 (parity G2): Image-type wallpapers (JPEG/PNG/WebP/BMP/GIF
                // without animation intent from the download) arrive as raw image
                // files — they pack as a Scene via packSceneWallpaper instead of
                // failing the .pkg parse. Only checked when NOT a video.
                val isImage = !isVideo && detectImageByMagicBytes(downloadedPkg)
                WepWawetLogger.workshop("WorkshopDownloadJob.send: packing content → .mpkg",
                    "input_file" to downloadedPkg.absolutePath,
                    "input_size" to downloadedPkg.length(),
                    "mpkg_output" to cacheMpkg.absolutePath,
                    "install_dir" to installDir.absolutePath,
                    "install_dir_exists" to installDir.exists(),
                    "is_video" to isVideo,
                    "is_image" to isImage)

                // v0.7.1 (round 10): If the downloaded file is a video, pause
                // BEFORE packing and set pendingVideoFile so the UI can show
                // a dialog asking "Do you want to trim the video?". The user
                // can either:
                //   a) Tap "Trim" → navigate to VideoTrimScreen with the
                //      downloaded video file. The trim screen handles pack +
                //      queue after trimming.
                //   b) Tap "Send Full" → call continueVideoSend() which
                //      resumes the pack + queue flow below (skipping trim).
                //   c) Tap "Cancel" → call cancelVideoSend() which deletes
                //      the downloaded file and resets state.
                if (isVideo) {
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,  // Not "running" anymore — waiting for user decision
                        status = "Video downloaded — do you want to trim?",
                        progress = 0.85f,
                        pendingVideoFile = downloadedPkg.absolutePath,
                        pendingVideoItem = item,
                    ) }
                    // Mirror into DownloadManager
                    onDownloadProgress(
                        itemId = item.id,
                        progress = 0.85f,
                        status = com.wepwawet.data.DownloadStatus.PACKING,
                        statusMessage = "Video downloaded — waiting for trim decision",
                    )
                    // Return here — the UI will call continueVideoSend() or
                    // the VideoTrimScreen will handle the rest.
                    WepWawetLogger.workshop("WorkshopDownloadJob.send: video downloaded — waiting for trim decision",
                        "video_file" to downloadedPkg.absolutePath,
                        "video_size" to downloadedPkg.length())
                    return@launchSafely
                }

                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WepWawet:MpkgPacker")
                wakeLock.setReferenceCounted(false)
                wakeLock.acquire(10 * 60 * 1000L)
                WepWawetLogger.workshop("WorkshopDownloadJob.send: acquired wake lock for packing",
                    "timeout_ms" to (10 * 60 * 1000L))
                val mpkgSize = try {
                    // v0.7.21 BUG FIX: was runCatching — now explicit try/catch
                    // that re-throws CancellationException.
                    try {
                        if (isVideo) {
                            WepWawetLogger.workshop("WorkshopDownloadJob.send: calling packVideoWallpaper",
                                "video_file" to downloadedPkg.name,
                                "video_size" to downloadedPkg.length())
                            val previewFile = File(installDir, "preview.jpg")
                                .takeIf { it.exists() }
                                ?: File(installDir, "preview.gif")
                                    .takeIf { it.exists() }
                            com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                                item = item,
                                videoFile = downloadedPkg,
                                outputFile = cacheMpkg,
                                previewFile = previewFile,
                            )
                        } else if (isImage) {
                            // v0.7.4 (parity G2): Image wallpapers pack as a
                            // minimal Scene (single fullscreen quad + the image
                            // as an RGBA8888 .tex) — the same representation the
                            // phone renders for image-type content.
                            WepWawetLogger.workshop("WorkshopDownloadJob.send: calling packSceneWallpaper (image item)",
                                "image_file" to downloadedPkg.name,
                                "image_size" to downloadedPkg.length())
                            com.wepwawet.pairing.MpkgPacker.packSceneWallpaper(
                                item = item,
                                imageFile = downloadedPkg,
                                outputFile = cacheMpkg,
                            )
                        } else {
                            WepWawetLogger.workshop("WorkshopDownloadJob.send: calling packFromPkg",
                                "pkg_file" to downloadedPkg.name,
                                "pkg_size" to downloadedPkg.length())
                            com.wepwawet.pairing.MpkgPacker.packFromPkg(
                                item = item,
                                pkgFile = downloadedPkg,
                                outputFile = cacheMpkg,
                                installDir = installDir,
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val err = "Failed to pack → .mpkg: ${e.message}"
                        WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: pack failed",
                            "exception" to e.javaClass.name, "message" to (e.message ?: ""),
                            "is_video" to isVideo)
                        updateState(itemId) { s -> s.copy(
                            isRunning = false,
                            status = err,
                            errorMessage = err,
                            endedAt = System.currentTimeMillis(),
                        ) }
                        onDownloadFailed(item, err)
                        // v0.7.4: don't delete the CACHE entry on failure.
                        if (downloadedPkg !== cachedPkg) runCatching { downloadedPkg.delete() }
                        return@launchSafely
                    }
                } finally {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                        WepWawetLogger.workshop("WorkshopDownloadJob.send: released wake lock")
                    }
                }

                WepWawetLogger.workshop("WorkshopDownloadJob.send: pack done",
                    "mpkg_output_size" to mpkgSize,
                    "mpkg_output_exists" to cacheMpkg.exists(),
                    "is_video" to isVideo)

                if (mpkgSize == 0L || !cacheMpkg.exists()) {
                    val err = "Failed to pack .pkg → .mpkg (empty output)"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: empty mpkg output",
                        "size" to mpkgSize, "exists" to cacheMpkg.exists())
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadFailed(item, err)
                    if (downloadedPkg !== cachedPkg) runCatching { downloadedPkg.delete() }
                    return@launchSafely
                }

                // === Queue for transfer to phone ===
                updateState(itemId) { s -> s.copy(
                    status = "Queuing for transfer to phone…",
                    progress = 0.95f,
                ) }
                // v0.5.32 (audit §1): mirror the "queueing for transfer"
                // phase transition. Uses the QUEUING status (distinct from
                // PACKING) so the Downloads screen can show a different
                // status string while the file waits for the phone to
                // pick it up via the WePairingServer heartbeat-driven
                // transfer loop.
                onDownloadProgress(
                    itemId = item.id,
                    progress = 0.95f,
                    status = com.wepwawet.data.DownloadStatus.QUEUING,
                    statusMessage = "Queuing for transfer to phone…",
                )
                val deviceName = PairingService.pairedDeviceName ?: "phone"
                WepWawetLogger.workshop("WorkshopDownloadJob.send: queueing for transfer",
                    "file" to cacheMpkg.name,
                    "size" to cacheMpkg.length(),
                    "device" to deviceName)
                // v0.8.18 (review B1): check the queue result — a false here
                // means the pairing server went away mid-download. The old
                // code queued blindly, reported "Queued … for transfer" as a
                // SUCCESS and deleted the source .pkg — a silent drop.
                val queued = PairingService.queueWallpaper(cacheMpkg, item.title)
                if (!queued) {
                    val err = "Pairing server stopped — the wallpaper was NOT sent " +
                        "(the .mpkg was kept at ${cacheMpkg.name}; tap Send again " +
                        "after reconnecting the phone)"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: queue REJECTED — server gone",
                        "file" to cacheMpkg.name,
                        "size" to cacheMpkg.length())
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                        savedFilePath = cacheMpkg.absolutePath,
                        savedFileSize = cacheMpkg.length(),
                    ) }
                    onDownloadFailed(item, err)
                    // Keep cacheMpkg (the user can retry the send once the
                    // phone reconnects) and still clean up the raw install
                    // dir — the .mpkg is the artifact that matters now.
                    runCatching { installDir.deleteRecursively() }
                    return@launchSafely
                }

                // v0.7.0: Auto-open the Wallpaper Engine Android app so the user
                // can see the wallpaper transfer happen. The WE app will display
                // the "Receiving wallpaper..." progress dialog.
                PairingService.launchWallpaperEngineApp(appContext)

                // === v0.7.4: Delete the install directory (mirrors the debug
                // download path, which already did this). It contains the raw
                // depot files (project.json, preview.jpg, models, scripts…)
                // that are no longer needed once the .mpkg is built — leaving
                // them behind leaked megabytes per send-to-phone in
                // cacheDir/workshop_downloads/<id>/ until Android's cache
                // cleaner ran. (WorkshopDownloader also wipes it BEFORE the
                // next download of the same id, but that doesn't help users
                // who send several different wallpapers.) ===
                runCatching { installDir.deleteRecursively() }
                WepWawetLogger.workshop("WorkshopDownloadJob.send: install dir cleaned up",
                    "path" to installDir.absolutePath)

                // === IMPORTANT: do NOT delete cacheMpkg here! ===
                // The PairingService.queueWallpaper only holds a File reference,
                // not the bytes — the file must still exist when the WePairingServer
                // command loop picks it up on the next heartbeat (~3s) and
                // transfers it via AES-encrypted chunks. Deleting it here would
                // cause "file not readable" in queueWallpaper → silent transfer
                // failure → black wallpaper on phone.
                //
                // The cache file will be cleaned up:
                //   - When the next send-to-phone for the SAME item starts
                //     (cacheDir reuses the path prefix, so the old file is
                //     overwritten naturally on the next pack)
                //   - When Android's cache cleaner runs (cacheDir is auto-cleaned
                //     under memory pressure)
                //   - Manually by the user via "Clear cache" in Android Settings

                // v0.7.4: store the .pkg in the stable cache when the
                // "Cache .pkg files" setting is enabled (scene wallpapers
                // only — videos are consumed by the trim flow), then clean up
                // the timestamped temp copy as before.
                if (downloadedPkg !== cachedPkg) {
                    if (pkgCacheEnabled && !isVideo) {
                        runCatching {
                            cachedPkg.parentFile?.mkdirs()
                            downloadedPkg.copyTo(cachedPkg, overwrite = true)
                        }
                        WepWawetLogger.workshop("WorkshopDownloadJob.send: .pkg stored in cache for faster re-packing",
                            "path" to cachedPkg.absolutePath,
                            "size" to cachedPkg.length())
                    }
                    runCatching { downloadedPkg.delete() }
                }

                val msg = "Queued ${formatBytes(cacheMpkg.length())} for transfer to $deviceName"
                WepWawetLogger.workshop("WorkshopDownloadJob.send: QUEUED",
                    "file" to cacheMpkg.name,
                    "size" to cacheMpkg.length(),
                    "device" to deviceName)
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    progress = 1f,
                    status = msg,
                    resultMessage = msg,
                    endedAt = System.currentTimeMillis(),
                    savedFilePath = cacheMpkg.absolutePath,
                    savedFileSize = cacheMpkg.length(),
                ) }
                onDownloadCompleted(item, cacheMpkg.length(), deviceName)
                // v0.8.0: auto-import into the integrated engine's library
                // (copy — the queued transfer still owns the cache file).
                autoImportToLibrary(appContext, cacheMpkg, item.id, item.title)
            } finally {
                // === CRITICAL (v0.4.7): ALWAYS call cleanup() on the WorkshopDownloader,
                // even on success — otherwise the SteamClient's TCP connection to Steam
                // stays alive, blocking subsequent download attempts (Steam rejects
                // the second login with the same loginID from the same IP → AccessDenied).
                // ===
                WepWawetLogger.workshop("WorkshopDownloadJob.send: cleaning up SteamClient",
                    "id" to item.id)
                runCatching { downloaderRef?.cleanup() }
                downloaderRef = null
            }
        }
        return true
    }

    /**
     * v0.7.1 (round 10): Continue the video send flow WITHOUT trimming.
     * Called when the user taps "Send Full Video" in the trim dialog.
     * Packs the downloaded video into a .mpkg and queues it for transfer
     * — exactly the same code as the normal send-to-phone flow for video.
     */
    fun continueVideoSend(context: Context, itemId: String) {
        val pendingFile = _states.value[itemId]?.pendingVideoFile
        val pendingItem = _states.value[itemId]?.pendingVideoItem
        if (pendingFile == null || pendingItem == null) {
            WepWawetLogger.workshopWarn("WorkshopDownloadJob.continueVideoSend: no pending video",
                "id" to itemId,
                "pending_file" to (pendingFile ?: "null"))
            return
        }
        val appContext = context.applicationContext
        val videoFile = File(pendingFile)
        if (!videoFile.exists()) {
            WepWawetLogger.workshopWarn("WorkshopDownloadJob.continueVideoSend: video file gone",
                "path" to pendingFile)
            updateState(itemId) { it.copy(
                pendingVideoFile = null,
                pendingVideoItem = null,
                errorMessage = "Video file not found",
            ) }
            return
        }
        // Clear the pending state — we're resuming
        updateState(itemId) { it.copy(
            pendingVideoFile = null,
            pendingVideoItem = null,
            isRunning = true,
            status = "Packing video → .mpkg…",
        ) }
        val sendId = System.currentTimeMillis()
        val cacheMpkg = File(appContext.cacheDir, "${pendingItem.id}_${sendId}_send.mpkg")
        val installDir = File(appContext.cacheDir, "workshop_downloads/${pendingItem.id}")
        // v0.7.25 (audit M-D9): never orphan an in-flight job for this item —
        // a second "continue" tap (or a retry racing the first) used to
        // overwrite jobs[itemId] while the previous pack was still running,
        // leaving it uncancellable and able to fight over the same cache
        // files. Cancel the superseded entry first, like every other entry
        // point does.
        jobs[itemId]?.cancel()
        jobs[itemId] = WepWawetApplication.appScope.launchSafely(itemId) {
            try {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WepWawet:MpkgPacker")
                wakeLock.setReferenceCounted(false)
                wakeLock.acquire(10 * 60 * 1000L)
                val mpkgSize = try {
                    val previewFile = File(installDir, "preview.jpg")
                        .takeIf { it.exists() }
                        ?: File(installDir, "preview.gif")
                            .takeIf { it.exists() }
                    com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                        item = pendingItem,
                        videoFile = videoFile,
                        outputFile = cacheMpkg,
                        previewFile = previewFile,
                    )
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
                // Queue for transfer
                updateState(itemId) { s -> s.copy(
                    status = "Queuing for transfer…",
                    progress = 0.95f,
                ) }
                onDownloadProgress(
                    itemId = pendingItem.id,
                    progress = 0.95f,
                    status = com.wepwawet.data.DownloadStatus.QUEUING,
                    statusMessage = "Queuing for transfer to phone…",
                )
                val deviceName = PairingService.pairedDeviceName ?: "phone"
                // v0.8.18 (review B1): check the queue result — on a false
                // (server gone mid-flow) report an honest FAILURE instead of
                // "Queued …", and never delete the source video.
                val queued = PairingService.queueWallpaper(cacheMpkg, pendingItem.title)
                if (!queued) {
                    val err = "Pairing server stopped — the video was NOT sent " +
                        "(the .mpkg was kept at ${cacheMpkg.name})"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob.continueVideoSend: queue REJECTED — server gone",
                        "file" to cacheMpkg.name,
                        "size" to cacheMpkg.length())
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                        savedFilePath = cacheMpkg.absolutePath,
                        savedFileSize = cacheMpkg.length(),
                    ) }
                    onDownloadFailed(pendingItem, err)
                    // The source video survives (retryable), but the install
                    // dir is no longer needed — the .mpkg carries the video.
                    runCatching { installDir.deleteRecursively() }
                    return@launchSafely
                }
                PairingService.launchWallpaperEngineApp(appContext)
                // v0.7.23 BUG WD-2 / v0.8.18 (review B1): the source video
                // is only deleted after queueWallpaper returned TRUE above —
                // the file is provably in the transfer queue.
                runCatching { videoFile.delete() }
                // v0.7.4: clean up the install dir here too (the video path in
                // launchSendToPhone returns early before the normal cleanup —
                // this is the continuation of that flow, so it owns the cleanup).
                runCatching { installDir.deleteRecursively() }
                WepWawetLogger.workshop("WorkshopDownloadJob.continueVideoSend: install dir cleaned up",
                    "path" to installDir.absolutePath)
                val msg = "Queued ${formatBytes(cacheMpkg.length())} for transfer to $deviceName"
                WepWawetLogger.workshop("WorkshopDownloadJob.continueVideoSend: QUEUED",
                    "file" to cacheMpkg.name, "size" to cacheMpkg.length())
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    progress = 1f,
                    status = msg,
                    resultMessage = msg,
                    endedAt = System.currentTimeMillis(),
                    savedFilePath = cacheMpkg.absolutePath,
                    savedFileSize = cacheMpkg.length(),
                ) }
                onDownloadCompleted(pendingItem, cacheMpkg.length(), deviceName)
                // v0.8.0: auto-import into the integrated engine's library.
                autoImportToLibrary(appContext, cacheMpkg, pendingItem.id, pendingItem.title)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v0.7.21 BUG FIX: re-throw CancellationException — was swallowed
                // by catch (e: Exception) which broke structured concurrency.
                throw e
            } catch (e: Exception) {
                WepWawetLogger.workshopWarn("WorkshopDownloadJob.continueVideoSend: failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = "Failed: ${e.message}",
                    errorMessage = "Failed: ${e.message}",
                    endedAt = System.currentTimeMillis(),
                ) }
            }
        }
    }

    /**
     * v0.9.0: Continue a LIBRARY-mode pending video — the "Add Full Video"
     * button on the detail screen's clip dialog. Packs + imports the full
     * video into the integrated engine library instead of queueing for the
     * pairing transfer.
     */
    fun continueVideoToLibrary(context: Context, itemId: String) {
        val pendingFile = _states.value[itemId]?.pendingVideoFile
        val pendingItem = _states.value[itemId]?.pendingVideoItem
        if (pendingFile == null || pendingItem == null) {
            WepWawetLogger.workshopWarn("WorkshopDownloadJob.continueVideoToLibrary: no pending video",
                "id" to itemId)
            return
        }
        val videoFile = File(pendingFile)
        if (!videoFile.exists()) {
            WepWawetLogger.workshopWarn("WorkshopDownloadJob.continueVideoToLibrary: video file gone",
                "path" to pendingFile)
            updateState(itemId) { it.copy(
                pendingVideoFile = null,
                pendingVideoItem = null,
                pendingVideoMode = "send",
                errorMessage = "Video file not found",
            ) }
            return
        }
        updateState(itemId) { it.copy(
            pendingVideoFile = null,
            pendingVideoItem = null,
            pendingVideoMode = "send",
            isRunning = true,
            status = "Packing video → .mpkg…",
        ) }
        // v0.8.16: hand the item (with the workshop TITLE) over explicitly —
        // the state was just cleared above, so importVideoToLibrary's
        // pendingVideoItem read would come back null and the wallpaper
        // would import as "<id> - wallpaper.mpkg" instead of the item's
        // real name.
        importVideoToLibrary(context, itemId, videoFile, deleteSource = true, overrideItem = pendingItem)
    }

    /**
     * v0.9.0: Pack a (full or trimmed) video into the integrated engine
     * library and update the download state accordingly. Shared by
     * [continueVideoToLibrary] ("Add Full Video") and VideoTrimScreen's
     * library mode ("Clip & Add").
     *
     * @param deleteSource delete the source video + install dir afterwards
     *   (the trim screen passes deleteSource=false for a FAILED trim where
     *   the original may still be wanted, and runs its own cleanup).
     * @param overrideItem v0.8.16: the caller's workshop item (with the
     *   real TITLE). continueVideoToLibrary used to CLEAR pendingVideoItem
     *   before calling this, so the fallback below ran titleless and the
     *   wallpaper landed in the library as "<id> - wallpaper.mpkg" —
     *   nothing like the workshop item's name (log 20261112-151734: item
     *   3828707511 packed with title=EMPTY). The chain now recovers the
     *   title from the pending item, the state's workshopTitle, or the
     *   DownloadManager record before giving up.
     */
    fun importVideoToLibrary(
        context: Context,
        itemId: String,
        videoFile: File,
        deleteSource: Boolean = true,
        overrideItem: WorkshopItemTyped? = null,
    ) {
        val appContext = context.applicationContext
        val pendingItem = overrideItem
            ?: _states.value[itemId]?.pendingVideoItem
            ?: _states.value[itemId]?.workshopTitle?.takeIf { it.isNotBlank() }?.let { t ->
                WorkshopItemTyped(
                    item = com.wepwawet.data.model.WorkshopItem(publishedfileid = itemId, title = t),
                    type = com.wepwawet.data.model.WallpaperType.VIDEO,
                )
            }
            ?: com.wepwawet.data.DownloadManager.completedDownloads.value
                .firstOrNull { it.itemId == itemId }?.title
                ?.takeIf { it.isNotBlank() }?.let { t ->
                    WorkshopItemTyped(
                        item = com.wepwawet.data.model.WorkshopItem(publishedfileid = itemId, title = t),
                        type = com.wepwawet.data.model.WallpaperType.VIDEO,
                    )
                }
            // Fallback for a direct call after the pending state was cleared:
            ?: WorkshopItemTyped(
                item = com.wepwawet.data.model.WorkshopItem(publishedfileid = itemId),
                type = com.wepwawet.data.model.WallpaperType.VIDEO,
            )
        val item = pendingItem
        // Surface the pack phase immediately (the coroutine below takes a
        // moment to spin up; without this the state shows nothing happening).
        updateState(itemId) { it.copy(
            isRunning = true,
            status = "Packing video → .mpkg…",
        ) }
        onDownloadProgress(
            itemId = itemId,
            progress = 0.85f,
            status = com.wepwawet.data.DownloadStatus.PACKING,
            statusMessage = "Packing video → .mpkg…",
        )
        val sendId = System.currentTimeMillis()
        val cacheMpkg = File(appContext.cacheDir, "${itemId}_${sendId}_debug.mpkg")
        val installDir = File(appContext.cacheDir, "workshop_downloads/$itemId")
        jobs[itemId]?.cancel()
        jobs[itemId] = WepWawetApplication.appScope.launchSafely(itemId) {
            try {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WepWawet:MpkgPacker")
                wakeLock.setReferenceCounted(false)
                wakeLock.acquire(10 * 60 * 1000L)
                val mpkgSize = try {
                    val previewFile = File(installDir, "preview.jpg")
                        .takeIf { it.exists() }
                        ?: File(installDir, "preview.gif")
                            .takeIf { it.exists() }
                    com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                        item = item,
                        videoFile = videoFile,
                        outputFile = cacheMpkg,
                        previewFile = previewFile,
                    )
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
                if (mpkgSize <= 0L || !cacheMpkg.exists()) {
                    throw IllegalStateException("packVideoWallpaper produced no output")
                }
                updateState(itemId) { s -> s.copy(
                    status = "Importing into your wallpaper library…",
                    progress = 0.95f) }
                val importOk = autoImportToLibrary(appContext, cacheMpkg, itemId, item.title)
                if (deleteSource) {
                    runCatching { videoFile.delete() }
                    runCatching { installDir.deleteRecursively() }
                }
                runCatching { cacheMpkg.delete() }
                if (importOk) {
                    val msg = "Added to your wallpaper library ✓ (${formatBytes(mpkgSize)}) — open it on the main page"
                    WepWawetLogger.workshop("WorkshopDownloadJob.importVideoToLibrary: added to library",
                        "file" to "$itemId - ${item.title}.mpkg",
                        "size" to mpkgSize)
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        progress = 1f,
                        status = msg,
                        resultMessage = msg,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadCompleted(item, mpkgSize, "Library")
                } else {
                    val err = "Failed to import into the wallpaper library (see logs)"
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis()) }
                    onDownloadFailed(item, err)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                WepWawetLogger.workshopWarn("WorkshopDownloadJob.importVideoToLibrary: failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = "Failed: ${e.message}",
                    errorMessage = "Failed: ${e.message}",
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, "Failed: ${e.message}")
            }
        }
    }

    /**
     * v0.7.1 (round 10): Cancel a pending video send (user tapped the X
     * in the trim dialog corner). Deletes the downloaded video file and
     * resets state.
     *
     * v0.7.3 — Bug fix: previously, cancelling the trim dialog left the
     * download status stuck on the stale "Video downloaded — waiting for
     * trim decision" message that [launchSendToPhone] sets via
     * `onDownloadProgress(..., statusMessage = "Video downloaded — waiting
     * for trim decision")` when it detects the file is a video. The
     * DownloadManager entry was never cleared because cancelVideoSend did
     * NOT call `onDownloadCancelled` (the mirror that removes the entry
     * from DownloadManager.activeDownloads). The local WorkshopDownloadJob
     * status was updated to "Cancelled" but the DownloadManager screen
     * still showed the waiting-for-trim-decision status indefinitely.
     *
     * Fix: call `onDownloadCancelled(workshopId)` so the DownloadManager
     * entry is removed too, reset progress to 0 (it was sitting at 0.85f
     * from the trim-wait state — leaving it at 0.85f made the Downloads
     * screen show a frozen 85% bar), and clear any stale result/error
     * messages so a fresh send-to-phone starts clean.
     */
    fun cancelVideoSend(itemId: String) {
        val pendingFile = _states.value[itemId]?.pendingVideoFile
        WepWawetLogger.workshop("WorkshopDownloadJob.cancelVideoSend",
            "id" to itemId,
            "pending_file" to (pendingFile ?: "null"))
        if (pendingFile != null) {
            runCatching { File(pendingFile).delete() }
        }
        // v0.7.3: Mirror the cancel into DownloadManager so the Downloads
        // screen's entry for this item is removed — otherwise it stays
        // in activeDownloads with the stale "waiting for trim decision"
        // statusMessage and 85% progress until the next event clears it.
        onDownloadCancelled(itemId)
        updateState(itemId) { it.copy(
            isRunning = false,
            pendingVideoFile = null,
            pendingVideoItem = null,
            pendingVideoMode = "send",
            progress = 0f,  // Reset — was sitting at 0.85f from the trim-wait state
            status = "Cancelled",
            // v0.7.3: Clear stale result/error messages so the next
            // send-to-phone for this item starts fresh and doesn't
            // show a stale "Sent ✓" or error pill from a prior run.
            resultMessage = null,
            errorMessage = null,
            endedAt = System.currentTimeMillis(),
        ) }
    }

    /**
     * v0.7.4: Called by VideoTrimScreen after it has successfully queued a
     * video (trimmed or full). Clears the pending-video state WITHOUT deleting
     * the file (the trim screen owns file cleanup) so the "Video Downloaded"
     * dialog doesn't reappear after navigating back, and a second "Trim Video"
     * tap can't navigate to a file that was already consumed.
     */
    fun onVideoConsumedByTrimScreen(itemId: String) {
        val s = _states.value[itemId]
        if (s?.pendingVideoFile != null || s?.pendingVideoItem != null) {
            updateState(itemId) { it.copy(
                pendingVideoFile = null,
                pendingVideoItem = null,
                pendingVideoMode = "send",
            ) }
            WepWawetLogger.workshop("WorkshopDownloadJob.onVideoConsumedByTrimScreen: pending video state cleared")
        }
    }

    fun cancel() {
        val allIds = jobs.keys.toList()
        WepWawetLogger.workshop("WorkshopDownloadJob: cancelling all",
            "count" to allIds.size)
        allIds.forEach { id -> cancel(id) }
    }

    fun cancel(itemId: String) {
        WepWawetLogger.workshop("WorkshopDownloadJob: cancelling",
            "id" to itemId)
        jobs[itemId]?.cancel()
        jobs.remove(itemId)
        onDownloadCancelled(itemId)
        // v0.7.23 BUG WD-1: Clear pending-video state + delete the file so
        // the trim dialog doesn't reappear after a cancel from the Downloads
        // screen.
        val pendingFile = _states.value[itemId]?.pendingVideoFile
        if (pendingFile != null) {
            runCatching { File(pendingFile).delete() }
        }
        updateState(itemId) { it.copy(
            isRunning = false,
            status = "Cancelled",
            errorMessage = "Cancelled",
            progress = 0f,
            endedAt = System.currentTimeMillis(),
            // v0.7.23 BUG WD-1: clear pending-video state.
            pendingVideoFile = null,
            pendingVideoItem = null,
        ) }
    }

    /** Mark the current download as failed with the given message. Called by
     *  [launchSafely] if the coroutine throws — keeps the UI from hanging on
     *  "Running…" forever after a crash. */
    fun markCrashed(itemId: String, message: String) {
        WepWawetLogger.workshopWarn("WorkshopDownloadJob: coroutine crashed",
            "msg" to message)
        updateState(itemId) { s -> s.copy(
            isRunning = false,
            status = message,
            errorMessage = message,
            endedAt = System.currentTimeMillis(),
        ) }
        // v0.5.9-alpha (R8): mirror the crash into DownloadManager so the
        // Downloads screen shows the failure instead of a stale "Downloading…".
        com.wepwawet.data.DownloadManager.failDownload(itemId, "Coroutine crashed: $message")
    }

    /** Reset to a clean idle state (called by UI when user dismisses the result). */
    fun reset() {
        _state.value = State()
    }

    /**
     * v0.7.20: Reset a specific item's download state — removes its entry
     * from the per-item [_states] map and drops its cached StateFlow so a
     * future call to [stateFor] for this item starts fresh. Called by the
     * UI when the user dismisses a single item's result panel.
     */
    fun reset(itemId: String) {
        _states.update { it - itemId }
        stateFlows.remove(itemId)
    }

    // v0.7.24 (M-D2/L-D1): Locale.US + shared with WorkshopDownloader's
    // identical (removed-duplicate) copy.
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * Detect whether a downloaded file is a video (MP4) or a scene (.pkg) by
 * checking its magic bytes.
 *
 * v0.4.9: Previously we checked the file EXTENSION (.mp4 vs .pkg), but
 * WorkshopDownloader always names the output file `*.pkg` regardless of
 * content type (the outputFile parameter is `${item.id}_${sendId}_send.pkg`).
 * So the extension check ALWAYS returned false for videos, causing
 * packFromPkg to be called on a .mp4 file, which failed with
 * "PkgReader implausible magic_len magic_len=469762048".
 *
 * Now we check the actual file content:
 *   - MP4/M4V/MOV files have "ftyp" box at offset 4 → bytes[4..7] = "ftyp"
 *   - PKGV (.pkg) files have "PKGV" at offset 4 → bytes[4..7] = "PKGV"
 *
 * @return true if the file is a video, false if it's a scene .pkg
 */
/**
 * v0.7.4 (parity G2): Detect whether the downloaded Workshop file is a VIDEO
 * by sniffing its container magic — NOT just "ftyp at offset 4" anymore.
 *
 * The previous MP4-only check mis-routed every WebM/MKV/AVI/WMV/GIF video
 * wallpaper into the .pkg path, where PkgReader failed with "no entries"
 * (WorkshopDownloader names every download *.pkg regardless of content, so
 * extensions are meaningless). A video wallpaper in ANY container the WE PC
 * pipeline knows how to deliver (MP4 family, WebM/MKV, AVI, WMV/ASF, GIF,
 * FLV, MPEG-TS) now routes to [MpkgPacker.packVideoWallpaper], which runs
 * the matching on-device transcoder before packing.
 *
 * Still images (JPEG/PNG/WebP/BMP) are NOT videos — see
 * [detectImageByMagicBytes] for the Image-wallpaper path.
 */
private fun detectVideoByMagicBytes(file: File): Boolean {
    if (!file.exists() || file.length() < 12) return false
    val kind = VideoContainerSniffer.sniff(file)
    val isVideo = VideoContainerSniffer.isVideoKind(kind)
    WepWawetLogger.workshop("detectVideoByMagicBytes",
        "file" to file.name,
        "sniffed" to VideoContainerSniffer.label(kind),
        "is_video" to isVideo)
    return isVideo
}

/**
 * v0.9.0: Can this video be offered to the trim/clip screen? The trim UI
 * plays the file with ExoPlayer and cuts it with MediaExtractor/MediaMuxer
 * — both handle the MP4 family and WebM/MKV containers. GIF/AVI/WMV/FLV/TS
 * must flow straight to their transcoders instead (GifToMp4Transcoder /
 * VideoTranscoder), where trimming isn't offered.
 */
private fun isTrimmableVideo(file: File): Boolean {
    if (!file.exists() || file.length() < 12) return false
    val kind = VideoContainerSniffer.sniff(file)
    val trimmable = kind == VideoContainerSniffer.Kind.MP4 ||
        kind == VideoContainerSniffer.Kind.WEBM_MKV
    WepWawetLogger.workshop("isTrimmableVideo",
        "file" to file.name,
        "sniffed" to VideoContainerSniffer.label(kind),
        "trimmable" to trimmable)
    return trimmable
}

/**
 * v0.7.4 (parity G2): Detect whether the downloaded Workshop file is a still
 * IMAGE (JPEG/PNG/WebP/BMP — including GIF, which BitmapFactory decodes to
 * its first frame for packing as a Scene wallpaper via
 * [MpkgPacker.packSceneWallpaper]).
 *
 * Image-type Workshop wallpapers download as project.json + the raw image
 * file (no .pkg at all). Before this fix they fell into the .pkg path and
 * failed with "no entries in .pkg" — now they pack as a Scene whose single
 * quad is the image, which is what the WE Android renderer displays.
 */
private fun detectImageByMagicBytes(file: File): Boolean {
    if (!file.exists() || file.length() < 12) return false
    val kind = VideoContainerSniffer.sniff(file)
    val isImage = VideoContainerSniffer.isImageKind(kind)
    WepWawetLogger.workshop("detectImageByMagicBytes",
        "file" to file.name,
        "sniffed" to VideoContainerSniffer.label(kind),
        "is_image" to isImage)
    return isImage
}

/**
 * Launches a coroutine on this scope but captures any uncaught exception into
 * the [WorkshopDownloadJob] state (so a crash doesn't leave the UI showing
 * "Running…" forever).
 */
private fun CoroutineScope.launchSafely(itemId: String, block: suspend CoroutineScope.() -> Unit): Job =
    this.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coop-cancelled — WorkshopDownloadJob.cancel already updated state
            throw e
        } catch (e: Throwable) {
            WorkshopDownloadJob.markCrashed(itemId, "Crashed: ${e.message ?: e.javaClass.name}")
        } finally {
            // v0.7.24 (H-D6): remove the entry when the job completes (success,
            // failure, or crash). Dead Job references previously lingered in
            // the map forever (cancel() removed its own entry, but normal
            // completion never did) — harmless for isActive checks, but an
            // unbounded leak of Job objects across a long session.
            // remove(key, value) semantics: if a NEW download for the same
            // item already replaced our entry, leave ITS entry alone.
            WorkshopDownloadJob.removeJobEntry(itemId, coroutineContext[kotlinx.coroutines.Job])
        }
    }
