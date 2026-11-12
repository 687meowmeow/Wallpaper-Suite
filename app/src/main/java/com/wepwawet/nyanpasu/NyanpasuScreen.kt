package com.wepwawet.nyanpasu

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar
import com.wepwawet.ui.screens.ImageFolderAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Discover — the booru auto-wallpaper screen.
 *
 * Feature-complete port of Nyanpasu Wallpaper v1.0.0 by FranklinNexus
 * (MIT License — attribution kept here in source only; no user-visible
 * string carries the name), with the SAFE later-version reliability fixes
 * (DNS-over-HTTPS fallback, ticker/one-shot scheduling) and NONE of the
 * later image-choice regressions (see [NyanpasuSearch]).
 *
 * v1.0.0 controls on this screen (all present):
 *  • the tools row — Undo, Home (tri-state), Lock (tri-state), Save
 *  • Update — fetches a fresh image AND applies it (one tap, 1.0's
 *    btnUpdate semantics)
 *  • Home/Lock tri-state: Off → Pink → Blue. Same color = one image for
 *    both screens; different colors = two independent images
 *  • Home/Lock preview switch in dual mode (tap the indicator)
 *  • Pure / Mix / NSFW content tiers
 *  • style slider (plain labels — the kaomoji dressing was removed per
 *    user request)
 *  • tag chips: add via input; TAP a chip to flip strict/soft; X removes
 *  • auto-refresh schedule (Off / 7:00 daily / 6 h / 12 h / 24 h)
 *  • history (last 5) + restore
 *  • pinch/drag preview (cosmetic zoom, like 1.0's PhotoView)
 *  • prefetch of the next image (12h freshness)
 *
 * v0.8.11 rebuild: NO Card containers, NO borders, NO kaomoji / mascot /
 * confetti / easter egg / welcome dialog (removed per user request), and
 * logging on every action.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NyanpasuScreen(
    onBack: (() -> Unit)? = null,
    // v0.8.16: the Auto refresh card deep-links into the playlist manager
    // (auto refresh is handled by the playlist system now).
    onOpenPlaylists: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // ---- settings state ----
    var strictTags by remember { mutableStateOf(NyanpasuPrefs.readStrictTags(context)) }
    var softTags by remember { mutableStateOf(NyanpasuPrefs.readSoftTags(context)) }
    var styleValue by remember { mutableStateOf(NyanpasuPrefs.readStyle(context)) }
    var r18Mode by remember { mutableStateOf(NyanpasuPrefs.readR18(context)) }
    var screens by remember { mutableStateOf(NyanpasuPrefs.readScreens(context)) }
    val homeState = screens.first
    val lockState = screens.second

    // ---- preview state ----
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewLockBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewingLock by remember { mutableStateOf(false) }
    // v0.8.14: true when the preview shows a freshly refreshed image that
    // has NOT been applied yet — Set then commits it as-is.
    var previewIsFresh by remember { mutableStateOf(false) }

    // ---- work state ----
    var fetching by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(NyanpasuApplier.readHistory(context)) }
    // v0.8.16: the built-in Discover playlist (name to entry count) shown
    // in the Auto refresh card.
    var discoverPlaylistInfo by remember { mutableStateOf<Pair<String, Int>?>(null) }

    // ---- v0.8.12: save-to-folder state ----
    var showSaveToFolder by remember { mutableStateOf(false) }
    var saveFolders by remember { mutableStateOf<List<ImageFolderAssets.SavedFolder>>(emptyList()) }
    var savingToFolder by remember { mutableStateOf(false) }

    /** The image to save — the visible preview, else the newest history entry. */
    suspend fun imageForSaving(): Bitmap? {
        previewBitmap?.let { return it }
        return history.firstOrNull()?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(it.file.absolutePath)
                }.getOrNull()
            }
        }
    }

    /** v0.8.12: save the current image into a picked library folder. */
    fun saveToFolder(treeUri: String) {
        if (savingToFolder) return
        savingToFolder = true
        scope.launch {
            try {
                val bmp = imageForSaving()
                if (bmp == null) {
                    snackbar.showSnackbar("Nothing to save yet")
                } else {
                    val ok = withContext(Dispatchers.IO) {
                        ImageFolderAssets.saveImageToFolder(context, treeUri, bmp)
                    }
                    snackbar.showSnackbar(
                        if (ok) "Saved to ${ImageFolderAssets.folderDisplayName(treeUri)}"
                        else "Save failed (see logs)")
                    if (ok) WepWawetLogger.appInfo(
                        "DiscoverScreen: saved to folder",
                        "folder" to ImageFolderAssets.folderDisplayName(treeUri))
                }
            } finally {
                savingToFolder = false
                showSaveToFolder = false
            }
        }
    }

    // Pick a NEW folder and save the current image into it right away.
    val saveFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            ImageFolderAssets.takePersistableGrants(context, uri)
            ImageFolderAssets.addFolder(context, uri.toString())
            saveFolders = ImageFolderAssets.readFolders(context)
            saveToFolder(uri.toString())
        }
    }

    // v0.8.16: the preview is a FIXED compact strip (no aspect math, no
    // zoom) and it sits BELOW the action rows — "the discover tab still
    // has the image too big and its under the buttons". 45% → 32% → 22%
    // caps all still read as a giant card; 160dp with the buttons first
    // is unambiguous.
    val previewHeight = 160.dp

    fun persistTags() = NyanpasuPrefs.writeTags(context, strictTags, softTags)

    /**
     * v0.8.16: background refill of the preload queue — keeps 3 images
     * downloaded at all times so Refresh swaps INSTANTLY. Single-flight
     * (a refill already running is never doubled up).
     */
    var refilling = false
    fun refillQueue() {
        if (refilling) return
        refilling = true
        scope.launch {
            try {
                while (NyanpasuPrefs.queueDepth(context) < 3) {
                    val nextUrl = withContext(Dispatchers.IO) {
                        runCatching {
                            NyanpasuSearch.searchOne(
                                context = context,
                                strictTags = strictTags,
                                softTags = softTags,
                                styleValue = styleValue,
                                r18Mode = r18Mode,
                                recentUrls = NyanpasuPrefs.readRecentUrls(context),
                            )
                        }.getOrNull()
                    } ?: break
                    val nextBytes = withContext(Dispatchers.IO) {
                        runCatching { NyanpasuSearch.downloadImage(nextUrl) }.getOrNull()
                    }
                    if (nextBytes == null || nextBytes.size <= 1024) break
                    withContext(Dispatchers.IO) {
                        NyanpasuPrefs.appendQueuedImage(context, nextUrl, nextBytes)
                    }
                    WepWawetLogger.appInfo("DiscoverScreen: preloaded image queued",
                        "bytes" to nextBytes.size,
                        "depth" to NyanpasuPrefs.queueDepth(context))
                }
            } finally {
                refilling = false
            }
        }
    }

    /**
     * Shared fetch: queue-aware image search → preview (plus the lock
     * complement in dual mode). Sets [previewIsFresh]; applies NOTHING.
     */
    suspend fun fetchAndShow(
        homeState: Int,
        lockState: Int,
        strictTags: List<String>,
        softTags: List<String>,
        styleValue: Int,
        r18Mode: Int,
    ) {
        val maxDim = NyanpasuApplier.maxDecodeDimension(context)
        var url: String? = null
        var bytes: ByteArray? = null
        // v0.8.16: PRELOADED QUEUE — Refresh pops an already-downloaded
        // image (instant swap, no "Downloading…" stage) and the refill
        // below restocks in the background ("it should have them preloaded
        // to ensure it can change it instantly").
        NyanpasuPrefs.consumeQueuedImage(context)?.let { (cached, cachedUrl) ->
            url = cachedUrl
            bytes = cached
            WepWawetLogger.appInfo("DiscoverScreen: using preloaded image",
                "bytes" to cached.size)
        }
        if (bytes == null) {
            val fetched = withContext(Dispatchers.IO) {
                NyanpasuSearch.searchOne(
                    context = context,
                    strictTags = strictTags,
                    softTags = softTags,
                    styleValue = styleValue,
                    r18Mode = r18Mode,
                    recentUrls = NyanpasuPrefs.readRecentUrls(context),
                )
            }
            statusLine = "Downloading…"
            url = fetched
            bytes = withContext(Dispatchers.IO) { NyanpasuSearch.downloadImage(fetched) }
        }
        val imgBytes = bytes!!
        NyanpasuPrefs.pushRecentUrl(context, url!!)
        val bmp = withContext(Dispatchers.IO) { NyanpasuApplier.decode(imgBytes, maxDim) }
        if (bmp == null) {
            statusLine = "Downloaded image failed to decode"
            WepWawetLogger.appWarn("DiscoverScreen: decode failed",
                "url" to url!!.take(100), "bytes" to imgBytes.size)
            return
        }
        previewBitmap = bmp
        previewIsFresh = true
        previewingLock = false
        // Keep the queue full for the NEXT Refresh.
        refillQueue()

        // Dual mode (pink/blue): a separate image for the lock screen.
        if (NyanpasuPrefs.isDualMode(homeState, lockState)) {
            statusLine = "Matching a lock-screen image…"
            val lockUrl = withContext(Dispatchers.IO) {
                NyanpasuSearch.searchOne(
                    context = context,
                    strictTags = strictTags,
                    softTags = softTags,
                    styleValue = styleValue,
                    r18Mode = r18Mode,
                    recentUrls = NyanpasuPrefs.readRecentUrls(context) + url!!,
                )
            }
            val lockBytes = withContext(Dispatchers.IO) { NyanpasuSearch.downloadImage(lockUrl) }
            NyanpasuPrefs.pushRecentUrl(context, lockUrl)
            val lockBmp = withContext(Dispatchers.IO) {
                NyanpasuApplier.decode(lockBytes, maxDim)
            }
            previewLockBitmap = lockBmp
            WepWawetLogger.appInfo("DiscoverScreen: lock image fetched",
                "url" to lockUrl.take(100),
                "dims" to (lockBmp?.let { "${it.width}x${it.height}" } ?: "null"))
        } else {
            previewLockBitmap = null
        }
    }

    /**
     * SET (v0.8.15): applies the SHOWN preview to the selected screens +
     * history. Refreshing is a separate action now — Discover never sets
     * the wallpaper on its own ("the discover tab should not automatically
     * set the wallpaper and setting it should be seperate from
     * refreshing").
     */
    fun applyShown() {
        val bmp = previewBitmap ?: return
        applying = true
        statusLine = "Applying…"
        scope.launch {
            try {
                val lockBmp = if (NyanpasuPrefs.isDualMode(homeState, lockState))
                    previewLockBitmap else null
                val homeCrop = withContext(Dispatchers.IO) {
                    NyanpasuApplier.cropForScreen(context, bmp)
                }
                val lockCrop = lockBmp?.let {
                    withContext(Dispatchers.IO) { NyanpasuApplier.cropForScreen(context, it) }
                }
                val result = NyanpasuApplier.applyScreens(
                    context, homeCrop, homeState, lockState, lockCrop)
                if (result.ok) {
                    NyanpasuApplier.pushHistory(context, homeCrop)
                    history = NyanpasuApplier.readHistory(context)
                    previewIsFresh = false
                    statusLine = "Wallpaper set"
                    snackbar.showSnackbar("Wallpaper set")
                    WepWawetLogger.appInfo("DiscoverScreen: applied",
                        "home_state" to homeState,
                        "lock_state" to lockState,
                        "separate_lock" to (lockCrop != null))

                    // v0.8.16: AUTO REFRESH = THE PLAYLIST SYSTEM. Every
                    // Set adds the image to the built-in "Discover"
                    // playlist (FIFO, 20 entries); rotating them is the
                    // playlist engine's job, on the interval picked in the
                    // playlist editor — ONE rotation system for the whole
                    // app, no separate background worker.
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = java.io.File(context.filesDir, "discover").apply { mkdirs() }
                            val img = java.io.File(dir, "discover_${System.currentTimeMillis()}.jpg")
                            java.io.FileOutputStream(img).use { out ->
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
                            }
                            val playlist =
                                com.wepwawet.playlist.SuitePlaylistStore.discoverPlaylist(context)
                            playlist.entries.add(
                                com.wepwawet.playlist.SuitePlaylistStore.Entry(
                                    filepath = img.absolutePath,
                                    title = "Discover ${playlist.entries.size + 1}",
                                    type = "Image"))
                            // FIFO trim: keep the newest 20, delete dropped files.
                            while (playlist.entries.size > 20) {
                                val dropped = playlist.entries.removeAt(0)
                                runCatching { java.io.File(dropped.filepath).delete() }
                            }
                            com.wepwawet.playlist.SuitePlaylistStore.upsert(context, playlist)
                            WepWawetLogger.appInfo("DiscoverScreen: added to Discover playlist",
                                "entries" to playlist.entries.size,
                                "file" to img.name)
                        }
                    }
                    discoverPlaylistInfo = withContext(Dispatchers.IO) {
                        val pl = com.wepwawet.playlist.SuitePlaylistStore.discoverPlaylist(context)
                        pl.name to pl.entries.size
                    }
                } else {
                    statusLine = "Wallpaper write failed (home=${result.homeOk}, lock=${result.lockOk})"
                }

                // Keep the preload queue full for the next Refresh (v0.8.16).
                refillQueue()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                statusLine = "Failed: ${e.message ?: e.javaClass.simpleName}"
                WepWawetLogger.appWarn("DiscoverScreen: apply failed",
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
            } finally {
                applying = false
            }
        }
    }

    /**
     * v0.8.14: REFRESH — fetch a fresh image into the preview WITHOUT
     * applying it (the browse flow the user asked for: "there is no refresh
     * button"). Update/Set then commits the shown image.
     */
    fun refreshPreview() {
        if (fetching || applying) return
        fetching = true
        statusLine = "Searching…"
        WepWawetLogger.appInfo("DiscoverScreen: refresh start (preview only)",
            "home_state" to homeState,
            "lock_state" to lockState,
            "dual" to NyanpasuPrefs.isDualMode(homeState, lockState))
        scope.launch {
            try {
                fetchAndShow(
                    homeState = homeState,
                    lockState = lockState,
                    strictTags = strictTags,
                    softTags = softTags,
                    styleValue = styleValue,
                    r18Mode = r18Mode,
                )
                statusLine = "New image — press Set to apply"
            } catch (e: NyanpasuSearch.NoImageException) {
                statusLine = "No image found — try different tags"
                WepWawetLogger.appWarn("DiscoverScreen: no image found",
                    "message" to (e.message ?: ""))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                statusLine = "Failed: ${e.message ?: e.javaClass.simpleName}"
                WepWawetLogger.appWarn("DiscoverScreen: refresh failed",
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
            } finally {
                fetching = false
            }
        }
    }

    /**
     * Undo (1.0's btnUndo): re-applies the most recent history entry and
     * pops it off the ring.
     */
    fun undoWallpaper() {
        val entry = history.firstOrNull() ?: run {
            scope.launch { snackbar.showSnackbar("No history yet") }
            return
        }
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeFile(entry.file.absolutePath)
                } ?: run {
                    snackbar.showSnackbar("History file not found")
                    return@launch
                }
                val crop = withContext(Dispatchers.IO) {
                    NyanpasuApplier.cropForScreen(context, bmp)
                }
                val result = NyanpasuApplier.applyScreens(context, crop, homeState, lockState, null)
                if (result.ok) {
                    // Pop the restored entry off the ring.
                    runCatching { entry.file.delete() }
                    history = NyanpasuApplier.readHistory(context)
                    previewBitmap = bmp
                    previewIsFresh = false
                    zoom = 1f; panX = 0f; panY = 0f
                    statusLine = "Previous wallpaper restored"
                    snackbar.showSnackbar("Previous wallpaper restored")
                    WepWawetLogger.appInfo("DiscoverScreen: undone from history",
                        "file" to entry.file.name)
                } else {
                    snackbar.showSnackbar("Restore failed (home=${result.homeOk}, lock=${result.lockOk})")
                }
            } catch (e: Exception) {
                snackbar.showSnackbar("Restore failed: ${e.message}")
            }
        }
    }

    /**
     * Save (1.0's btnSave): the visible image (or the newest history
     * entry) into the system gallery.
     */
    fun saveCurrentToGallery() {
        scope.launch {
            val bmp = previewBitmap ?: history.firstOrNull()?.let {
                withContext(Dispatchers.IO) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(it.file.absolutePath)
                    }.getOrNull()
                }
            }
            if (bmp == null) {
                snackbar.showSnackbar("Nothing to save yet")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                NyanpasuApplier.saveToGallery(context, bmp)
            }
            snackbar.showSnackbar(
                if (ok) "Saved to Pictures/WallpaperSuite" else "Save failed")
            if (ok) WepWawetLogger.appInfo("DiscoverScreen: saved to gallery")
        }
    }

    /** Cycle a screen's tri-state Off → Pink → Blue → Off. */
    fun cycleScreen(isHome: Boolean) {
        val (h, l) = screens
        val wasDual = NyanpasuPrefs.isDualMode(h, l)
        val newPair = if (isHome) ((h + 1) % 3) to l else h to ((l + 1) % 3)
        screens = newPair
        NyanpasuPrefs.writeScreens(context, newPair.first, newPair.second)
        WepWawetLogger.appInfo("DiscoverScreen: screen toggled",
            "which" to if (isHome) "home" else "lock",
            "home_state" to newPair.first,
            "lock_state" to newPair.second,
            "mode" to when {
                NyanpasuPrefs.isDualMode(newPair.first, newPair.second) -> "dual"
                NyanpasuPrefs.isSyncMode(newPair.first, newPair.second) -> "sync"
                else -> "single"
            })
        // Entering dual mode with a home image but no lock image → fetch
        // the lock complement in the background (1.0's complement work).
        val nowDual = NyanpasuPrefs.isDualMode(newPair.first, newPair.second)
        if (nowDual && !wasDual && previewBitmap != null && previewLockBitmap == null && !fetching) {
            scope.launch {
                try {
                    val lockUrl = withContext(Dispatchers.IO) {
                        NyanpasuSearch.searchOne(
                            context = context,
                            strictTags = strictTags,
                            softTags = softTags,
                            styleValue = styleValue,
                            r18Mode = r18Mode,
                            recentUrls = NyanpasuPrefs.readRecentUrls(context),
                        )
                    }
                    val lockBytes = withContext(Dispatchers.IO) { NyanpasuSearch.downloadImage(lockUrl) }
                    NyanpasuPrefs.pushRecentUrl(context, lockUrl)
                    previewLockBitmap = withContext(Dispatchers.IO) {
                        NyanpasuApplier.decode(lockBytes, NyanpasuApplier.maxDecodeDimension(context))
                    }
                    WepWawetLogger.appInfo("DiscoverScreen: dual complement fetched",
                        "url" to lockUrl.take(100))
                } catch (e: Exception) {
                    WepWawetLogger.appInfo("DiscoverScreen: dual complement failed (non-fatal)",
                        "exception" to e.javaClass.simpleName)
                }
            }
        }
    }

    // v0.8.16: the schedule pref/worker are RETIRED — auto refresh is the
    // playlist system's job now (see the Auto refresh card). The legacy
    // unique work is cancelled on open so upgrading devices stop the old
    // background rotation.

    // On open: show the LAST applied image (1.0's preview.loadPreview).
    // First ever launch with no history → fetch + apply one automatically.
    LaunchedEffect(Unit) {
        // v0.8.15: on open we only ever SHOW something — the last applied
        // image, or a freshly fetched one on first launch. Discover NEVER
        // sets the wallpaper by itself anymore; that is exclusively the
        // Set button ("the discover tab should not automatically set the
        // wallpaper").
        if (previewBitmap == null) {
            val newest = history.firstOrNull()
            if (newest != null) {
                previewBitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(newest.file.absolutePath)
                    }.getOrNull()
                }
            } else if (!fetching) {
                refreshPreview()
            }
        }
        // v0.8.16: keep the preload queue topped up + surface the Discover
        // playlist state, and retire the legacy background worker for
        // anyone upgrading ("auto refresh is handled by the playlist
        // system" now).
        refillQueue()
        discoverPlaylistInfo = withContext(Dispatchers.IO) {
            val pl = com.wepwawet.playlist.SuitePlaylistStore.discoverPlaylist(context)
            pl.name to pl.entries.size
        }
        runCatching {
            val wm = androidx.work.WorkManager.getInstance(context)
            wm.cancelUniqueWork("nyanpasu_periodic_ticker")
            wm.cancelUniqueWork("nyanpasu_apply_once")
            wm.cancelUniqueWork("nyanpasu_daily_chain")
        }
    }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Discover",
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ===== v0.8.16 LAYOUT: actions FIRST, compact preview BELOW =====
            // "the discover tab still has the image too big and its under
            // the buttons" — the buttons lead, the preview supports. It is
            // a fixed 160dp strip (Crop-filled, no zoom/pan: the
            // transformable was three rounds of clipping/scroll pain for a
            // preview this size).

            // ===== Tools row (v1.0.0: Undo / Home / Lock — all LABELED) =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = { undoWallpaper() }) {
                    Icon(Icons.Filled.Undo, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Undo")
                }
                TriStateButton(
                    label = "Home",
                    state = homeState,
                    onCycle = { cycleScreen(isHome = true) },
                    modifier = Modifier.weight(1f),
                )
                TriStateButton(
                    label = "Lock",
                    state = lockState,
                    onCycle = { cycleScreen(isHome = false) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Home and Lock cycle Off → Pink → Blue. Same color = one image " +
                    "for both screens; different colors = separate images.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ===== Set / Refresh (v0.8.15: SEPARATE actions) =====
            // "the discover tab should not automatically set the wallpaper
            // and setting it should be seperate from refreshing" — Refresh
            // only fetches a new image into the preview; Set commits the
            // shown image. Both are labeled, side by side.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { applyShown() },
                    enabled = !applying && previewBitmap != null,
                    modifier = Modifier.weight(1.3f),
                ) {
                    if (applying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Wallpaper, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (applying) "Setting…" else "Set as wallpaper",
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = { refreshPreview() },
                    enabled = !fetching && !applying,
                    modifier = Modifier.weight(1f),
                ) {
                    if (fetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh", maxLines = 1)
                }
            }
            if (statusLine != null) {
                Text(
                    statusLine!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (fetching || applying) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            // ===== Preview (compact, below the buttons) =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val shown =
                    if (previewingLock && previewLockBitmap != null) previewLockBitmap else previewBitmap
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "Wallpaper preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Dual-mode preview switch (tap the label).
                    if (NyanpasuPrefs.isDualMode(homeState, lockState) &&
                        previewLockBitmap != null
                    ) {
                        Text(
                            if (previewingLock) "Lock preview — tap to switch"
                            else "Home preview — tap to switch",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { previewingLock = !previewingLock }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                } else if (fetching) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                } else {
                    Text(
                        statusLine ?: "Tap Refresh to find a wallpaper",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // ===== Save row (v0.8.12: gallery + folder — visible buttons) =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { saveCurrentToGallery() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save to gallery", maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        saveFolders = ImageFolderAssets.readFolders(context)
                        showSaveToFolder = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save to folder", maxLines = 1)
                }
            }

            // ===== Tags (tap a chip to flip strict/soft) =====
            Text("Tags", style = MaterialTheme.typography.titleMedium)
            TagEditor(
                strictTags = strictTags,
                softTags = softTags,
                onToggleStrict = { tag ->
                    if (tag in strictTags) {
                        strictTags = strictTags - tag
                        softTags = softTags + tag
                    } else {
                        softTags = softTags - tag
                        strictTags = strictTags + tag
                    }
                    persistTags()
                    WepWawetLogger.appInfo("DiscoverScreen: tag toggled",
                        "tag" to tag, "strict" to (tag in strictTags))
                },
                onRemove = { tag ->
                    strictTags = strictTags - tag
                    softTags = softTags - tag
                    persistTags()
                    WepWawetLogger.appInfo("DiscoverScreen: tag removed", "tag" to tag)
                },
                onAdd = { tag ->
                    if (tag in strictTags || tag in softTags) {
                        // Re-adding an existing tag makes it strict.
                        softTags = softTags - tag
                        strictTags = strictTags + tag
                    } else {
                        strictTags = strictTags + tag
                    }
                    persistTags()
                    WepWawetLogger.appInfo("DiscoverScreen: tag added", "tag" to tag)
                },
            )
            Text(
                "Strict tags always apply; soft tags join 38% of queries. Niche names " +
                    "(Genshin, Blue Archive, Touhou, Star Rail, ZZZ, Arknights, FGO, " +
                    "Hololive) map to their canonical tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ===== Style =====
            Text("Style", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = styleValue.toFloat(),
                onValueChange = {
                    styleValue = it.toInt()
                    NyanpasuPrefs.writeStyle(context, styleValue)
                },
                valueRange = 0f..100f,
            )
            Text(
                when {
                    styleValue < 35 -> "Petite"
                    styleValue > 65 -> "Full"
                    else -> "Balanced"
                } + " — only applied when a source supports the tag group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ===== Content tier =====
            Text("Content", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = r18Mode == NyanpasuPrefs.R18_PURE,
                    onClick = {
                        r18Mode = NyanpasuPrefs.R18_PURE
                        NyanpasuPrefs.writeR18(context, r18Mode)
                        WepWawetLogger.appInfo("DiscoverScreen: content mode", "mode" to "pure")
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                ) { Text("Pure") }
                SegmentedButton(
                    selected = r18Mode == NyanpasuPrefs.R18_MIX,
                    onClick = {
                        r18Mode = NyanpasuPrefs.R18_MIX
                        NyanpasuPrefs.writeR18(context, r18Mode)
                        WepWawetLogger.appInfo("DiscoverScreen: content mode", "mode" to "mix")
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                ) { Text("Mix") }
                SegmentedButton(
                    selected = r18Mode == NyanpasuPrefs.R18_NSFW,
                    onClick = {
                        r18Mode = NyanpasuPrefs.R18_NSFW
                        NyanpasuPrefs.writeR18(context, r18Mode)
                        WepWawetLogger.appInfo("DiscoverScreen: content mode", "mode" to "nsfw")
                    },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                ) { Text("NSFW") }
            }

            // ===== Auto refresh (v0.8.16: THE PLAYLIST SYSTEM) =====
            // "the auto refresh should be handled by the playlist system" —
            // the old WorkManager scheduler is gone. Every Set builds the
            // built-in "Discover" playlist; activating it (and picking its
            // interval) happens in Playlists, exactly like every other
            // playlist.
            Text("Auto refresh", style = MaterialTheme.typography.titleMedium)
            val plInfo = discoverPlaylistInfo
            Text(
                if (plInfo != null && plInfo.second > 0)
                    "Every image you Set is added to the \"${plInfo.first}\" " +
                        "playlist (${plInfo.second} image${if (plInfo.second == 1) "" else "s"}). " +
                        "Activate it in Playlists to rotate them — its interval " +
                        "lives there, next to your other playlists."
                else
                    "Every image you Set is added to the built-in \"Discover\" " +
                        "playlist. Activate it in Playlists to rotate them — " +
                        "its interval lives there, next to your other playlists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { onOpenPlaylists() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.QueueMusic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Playlists")
            }

            // ===== History =====
            Text("History", style = MaterialTheme.typography.titleMedium)
            if (history.isEmpty()) {
                Text(
                    "The last 5 applied wallpapers appear here — tap one to re-apply it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (entry in history) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    try {
                                        val bmp = withContext(Dispatchers.IO) {
                                            android.graphics.BitmapFactory.decodeFile(
                                                entry.file.absolutePath)
                                        } ?: return@launch
                                        val crop = withContext(Dispatchers.IO) {
                                            NyanpasuApplier.cropForScreen(context, bmp)
                                        }
                                        val result = NyanpasuApplier.applyScreens(
                                            context, crop, homeState, lockState, null)
                                        if (result.ok) {
                                            previewBitmap = bmp
                                            previewIsFresh = false
                                            zoom = 1f; panX = 0f; panY = 0f
                                            statusLine = "Restored"
                                            snackbar.showSnackbar("Restored")
                                            WepWawetLogger.appInfo(
                                                "DiscoverScreen: restored from history",
                                                "file" to entry.file.name)
                                        }
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar("Restore failed: ${e.message}")
                                    }
                                }
                            },
                        ) {
                            HistoryThumb(entry)
                            Text(
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                                    .format(java.util.Date(entry.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Text(
                "Fresh wallpapers from anime image boards — tags first, " +
                    "always something new.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )
        }
    }

    // ===== v0.8.12: Save-to-folder picker (Discover → library folders) =====
    if (showSaveToFolder) {
        AlertDialog(
            onDismissRequest = { if (!savingToFolder) showSaveToFolder = false },
            title = { Text("Save to folder") },
            text = {
                Column {
                    Text(
                        "The current image is saved as a JPEG — it then shows " +
                            "up in the Wallpapers section under that folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (saveFolders.isEmpty()) {
                        Text(
                            "No folders yet — pick one below.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        for (folder in saveFolders) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !savingToFolder) {
                                        saveToFolder(folder.treeUri)
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    folder.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                saveFolderPicker.launch(null)
                            } catch (t: Throwable) {
                                WepWawetLogger.appWarn(
                                    "DiscoverScreen: folder picker failed",
                                    "exception" to t.javaClass.name)
                            }
                        },
                        enabled = !savingToFolder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Pick a folder…")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showSaveToFolder = false },
                    enabled = !savingToFolder,
                ) { Text("Cancel") }
            },
        )
    }
}

/** Nyanpasu 1.0's brand colors for the tri-state buttons. */
private val PinkState = Color(0xFFFF80AB)
private val BlueState = Color(0xFF64B5F6)

/**
 * v1.0.0's tri-state Home/Lock button: gray (off) → pink → blue, pill
 * shaped, white check glyph when active.
 */
@Composable
private fun TriStateButton(
    label: String,
    state: Int,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when (state) {
        NyanpasuPrefs.SCREEN_PINK -> PinkState
        NyanpasuPrefs.SCREEN_BLUE -> BlueState
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (state > NyanpasuPrefs.SCREEN_OFF) Color.White
    else MaterialTheme.colorScheme.onSurfaceVariant
    Button(
        onClick = onCycle,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (state > NyanpasuPrefs.SCREEN_OFF) {
            Spacer(Modifier.width(6.dp))
            Text("✓", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * v1.0.0's tag chips: a wrapping FlowRow. Tap = flip strict (filled) ↔
 * soft (outline); the X trailing icon removes the tag.
 *
 * v0.8.12: the add field is THE prominent "place to put the tags" — a
 * full-width labeled field with a visible, LABELED Add button, and it
 * accepts several tags at once (comma separated).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(
    strictTags: List<String>,
    softTags: List<String>,
    onToggleStrict: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var field by remember { mutableStateOf("") }

    fun addField() {
        field.split(',', '，')
            .map { it.trim().take(30) }
            .filter { it.isNotEmpty() }
            .forEach { onAdd(it) }
        field = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The input — one clear place to put the tags.
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it.take(120) },
                singleLine = true,
                label = { Text("Tags (comma separated)") },
                modifier = Modifier.weight(1f),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { addField() },
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { addField() },
                enabled = field.isNotBlank(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
        if (strictTags.isNotEmpty() || softTags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Strict first (they carry the intent), soft after.
                for (tag in strictTags + softTags) {
                    val isStrict = tag in strictTags
                    FilterChip(
                        selected = isStrict,
                        onClick = { onToggleStrict(tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove $tag",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onRemove(tag) },
                            )
                        },
                    )
                }
            }
            Text(
                "Tap a chip to switch between strict (filled) and soft (outline).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryThumb(entry: NyanpasuApplier.HistoryEntry) {
    // Light-weight decode: subsample to ~128 px for the strip.
    // No border (user request).
    val bmp = remember(entry.file.absolutePath) {
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 16 }
        android.graphics.BitmapFactory.decodeFile(entry.file.absolutePath, opts)
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Restore ${entry.timestamp}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.History, contentDescription = null) }
    }
}
