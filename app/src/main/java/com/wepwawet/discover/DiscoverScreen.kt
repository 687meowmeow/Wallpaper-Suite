package com.wepwawet.discover

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.wepwawet.ui.screens.BitmapCropDialog
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
 * later image-choice regressions (see [DiscoverSearch]).
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
fun DiscoverScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // ---- settings state ----
    var strictTags by remember { mutableStateOf(DiscoverPrefs.readStrictTags(context)) }
    var softTags by remember { mutableStateOf(DiscoverPrefs.readSoftTags(context)) }
    var styleValue by remember { mutableStateOf(DiscoverPrefs.readStyle(context)) }
    var r18Mode by remember { mutableStateOf(DiscoverPrefs.readR18(context)) }
    // v0.8.18: the booru source picker ("add the ability to choose the
    // booru sources for the discover page").
    var sources by remember { mutableStateOf(DiscoverPrefs.readSources(context)) }
    var screens by remember { mutableStateOf(DiscoverPrefs.readScreens(context)) }
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
    var history by remember { mutableStateOf(DiscoverApplier.readHistory(context)) }
    // v0.8.17: the Set-as-wallpaper choice ("crop it from there if you push
    // set as wallpaper") + the crop dialog itself.
    var showSetChoice by remember { mutableStateOf(false) }
    var showCropDialog by remember { mutableStateOf(false) }

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

    // v0.8.18: "+ folder" pops the shared MENU first ("make the create
    // folder button come up with menu asking if you want to use an already
    // existing or make one") — FolderAddMenuDialog offers use-existing /
    // create-new, and CreateFolderFlow (both in FolderPickers.kt) owns the
    // parent picker + name + hidden-from-gallery steps.
    var showFolderMenu by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }

    // v0.8.19 (preview window): the preview is a WINDOW at the PHONE's
    // aspect ratio ("i want the image to be in a window that is the aspect
    // ratio of the phone. you should be able to manuver it in this
    // window") — full width, phone aspect, the image inside it pan/zoom
    // (drag to move, pinch to zoom), and Set applies exactly the VISIBLE
    // region (WYSIWYG). A 42%-screen height sanity cap only letterboxes
    // absurdly wide aspects; the window itself keeps the phone ratio.
    val phoneAspect = run {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        if (w > 0f && h > 0f) w / h else 9f / 19.5f
    }

    // v0.8.20 (fixed window): the preview window's HEIGHT is FIXED —
    // 42% of the physical screen ("the image in discover is too big. it
    // covers up the buttons. it should be in fixed window above the
    // buttons"). The window keeps the PHONE's aspect ratio and is
    // CENTERED horizontally (letterboxed when the phone is wider than
    // the window), so the window itself can never grow past the budget
    // and every action row below stays on screen.
    val fixedWindowHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        (context.resources.displayMetrics.heightPixels * 0.42f).toDp()
    }
    // The window's width at the phone aspect, capped by the available
    // width (the horizontal padding eats 32dp; the cap keeps the aspect
    // exact on very wide screens by letterboxing instead).
    val fixedWindowWidth = (fixedWindowHeight * phoneAspect.coerceIn(0.4f, 2.5f))

    // v0.8.19: the pan/zoom state of the preview window. zoom ≥ 1; pan
    // fractions −1..1 of the available slack on each axis.
    var previewZoom by remember { mutableStateOf(1f) }
    var previewPanX by remember { mutableStateOf(0f) }
    var previewPanY by remember { mutableStateOf(0f) }
    /** Reset the window whenever a NEW image is shown. */
    fun resetPreviewWindow() {
        previewZoom = 1f
        previewPanX = 0f
        previewPanY = 0f
    }

    fun persistTags() = DiscoverPrefs.writeTags(context, strictTags, softTags)

    /**
     * v0.8.16: background refill of the preload queue — keeps 3 images
     * downloaded at all times so Refresh swaps INSTANTLY. Single-flight
     * (a refill already running is never doubled up).
     *
     * v0.8.18 (review B8): the single-flight flag is a remembered
     * AtomicBoolean, NOT a plain local `var`. The old local was recreated
     * by every recomposition, so a recomposition while a refill was in
     * flight (routine — the preview/status state changes during the loop)
     * produced a fresh `refilling = false` closure and a SECOND concurrent
     * refill loop could start (double booru requests, wasted bandwidth).
     * `remember` shares one flag across all recompositions.
     */
    /**
     * The CURRENT search parameters, as a queue signature. v0.8.18 — THE
     * TAGS FIX: the preload queue used to be parameter-blind, so changing
     * tags and tapping Refresh still popped an image preloaded under the
     * OLD (or empty) tags and tags appeared dead. Queued slots are now
     * stamped with this signature; only matching slots are ever popped.
     */
    fun currentSignature() = DiscoverPrefs.searchSignature(
        strictTags, softTags, styleValue, r18Mode, sources)

    val refilling = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun refillQueue() {
        if (!refilling.compareAndSet(false, true)) return
        scope.launch {
            try {
                val signature = currentSignature()
                // v0.8.19: the queue target comes from the prefs
                // constant (8 — see DiscoverPrefs.QUEUE_SIZE).
                while (DiscoverPrefs.queueDepth(context, signature) <
                    DiscoverPrefs.QUEUE_TARGET) {
                    val nextUrl = withContext(Dispatchers.IO) {
                        runCatching {
                            DiscoverSearch.searchOne(
                                context = context,
                                strictTags = strictTags,
                                softTags = softTags,
                                styleValue = styleValue,
                                r18Mode = r18Mode,
                                recentUrls = DiscoverPrefs.readRecentUrls(context),
                                sources = sources,
                            )
                        }.getOrNull()
                    } ?: break
                    val nextBytes = withContext(Dispatchers.IO) {
                        runCatching { DiscoverSearch.downloadImage(nextUrl) }.getOrNull()
                    }
                    if (nextBytes == null || nextBytes.size <= 1024) break
                    withContext(Dispatchers.IO) {
                        DiscoverPrefs.appendQueuedImage(context, nextUrl, nextBytes, signature)
                    }
                    WepWawetLogger.appInfo("DiscoverScreen: preloaded image queued",
                        "bytes" to nextBytes.size,
                        "depth" to DiscoverPrefs.queueDepth(context, signature))
                }
            } finally {
                refilling.set(false)
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
        sources: Set<String>,
    ) {
        val maxDim = DiscoverApplier.maxDecodeDimension(context)
        var url: String? = null
        var bytes: ByteArray? = null
        // v0.8.16: PRELOADED QUEUE — Refresh pops an already-downloaded
        // image (instant swap, no "Downloading…" stage) and the refill
        // below restocks in the background ("it should have them preloaded
        // to ensure it can change it instantly"). v0.8.18: only slots
        // fetched under the SAME search parameters are eligible — a tag or
        // source change makes every old slot stale (dropped on sight).
        val signature = DiscoverPrefs.searchSignature(
            strictTags, softTags, styleValue, r18Mode, sources)
        DiscoverPrefs.consumeQueuedImage(context, signature)?.let { (cached, cachedUrl) ->
            url = cachedUrl
            bytes = cached
            WepWawetLogger.appInfo("DiscoverScreen: using preloaded image",
                "bytes" to cached.size)
        }
        if (bytes == null) {
            val fetched = withContext(Dispatchers.IO) {
                DiscoverSearch.searchOne(
                    context = context,
                    strictTags = strictTags,
                    softTags = softTags,
                    styleValue = styleValue,
                    r18Mode = r18Mode,
                    recentUrls = DiscoverPrefs.readRecentUrls(context),
                    sources = sources,
                )
            }
            statusLine = "Downloading…"
            url = fetched
            bytes = withContext(Dispatchers.IO) { DiscoverSearch.downloadImage(fetched) }
        }
        val imgBytes = bytes!!
        DiscoverPrefs.pushRecentUrl(context, url!!)
        val bmp = withContext(Dispatchers.IO) { DiscoverApplier.decode(imgBytes, maxDim) }
        if (bmp == null) {
            statusLine = "Downloaded image failed to decode"
            WepWawetLogger.appWarn("DiscoverScreen: decode failed",
                "url" to url!!.take(100), "bytes" to imgBytes.size)
            return
        }
        previewBitmap = bmp
        previewIsFresh = true
        previewingLock = false
        // v0.8.19: every NEW image starts with a centered, unzoomed window.
        resetPreviewWindow()
        // Keep the queue full for the NEXT Refresh.
        refillQueue()

        // Dual mode (pink/blue): a separate image for the lock screen.
        if (DiscoverPrefs.isDualMode(homeState, lockState)) {
            statusLine = "Matching a lock-screen image…"
            val lockUrl = withContext(Dispatchers.IO) {
                DiscoverSearch.searchOne(
                    context = context,
                    strictTags = strictTags,
                    softTags = softTags,
                    styleValue = styleValue,
                    r18Mode = r18Mode,
                    recentUrls = DiscoverPrefs.readRecentUrls(context) + url!!,
                    sources = sources,
                )
            }
            val lockBytes = withContext(Dispatchers.IO) { DiscoverSearch.downloadImage(lockUrl) }
            DiscoverPrefs.pushRecentUrl(context, lockUrl)
            val lockBmp = withContext(Dispatchers.IO) {
                DiscoverApplier.decode(lockBytes, maxDim)
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
    /**
     * v0.8.17: applyShown grew override parameters for the CROP-FIRST flow
     * ("you should also have the option to crop it from there if you push
     * set as wallpaper") — the crop dialog hands back a cropped bitmap that
     * replaces the home (or lock) image for this apply only.
     */
    fun applyBitmap(homeOverride: Bitmap? = null, lockOverride: Bitmap? = null) {
        val bmp = homeOverride ?: previewBitmap ?: return
        applying = true
        statusLine = "Applying…"
        scope.launch {
            try {
                val lockBmp = if (DiscoverPrefs.isDualMode(homeState, lockState))
                    (lockOverride ?: previewLockBitmap) else null
                // v0.8.19 (preview window): a plain Set applies exactly the
                // VISIBLE region of the preview window (WYSIWYG — the
                // window pans/zooms the image, Set crops what's visible at
                // the phone's aspect). A bitmap the user CROPPED in the
                // crop dialog is applied as-is instead (its aspect is the
                // choice they made).
                val homeCrop = withContext(Dispatchers.IO) {
                    if (homeOverride != null) {
                        DiscoverApplier.cropForScreen(context, bmp, matchScreenAspect = false)
                    } else {
                        DiscoverApplier.cropVisibleWindow(
                            context, bmp, phoneAspect, previewZoom, previewPanX, previewPanY)
                    }
                }
                val lockCrop = lockBmp?.let {
                    withContext(Dispatchers.IO) {
                        if (lockOverride != null) {
                            DiscoverApplier.cropForScreen(context, it, matchScreenAspect = false)
                        } else {
                            DiscoverApplier.cropVisibleWindow(
                                context, it, phoneAspect, previewZoom, previewPanX, previewPanY)
                        }
                    }
                }
                val result = DiscoverApplier.applyScreens(
                    context, homeCrop, homeState, lockState, lockCrop)
                if (result.ok) {
                    // v0.8.18: ok = ANY requested screen was written — the
                    // old strict AND meant a device whose lock write fails
                    // never recorded history even though the home wallpaper
                    // visibly changed ("the history doesnt work").
                    DiscoverApplier.pushHistory(context, homeCrop)
                    history = DiscoverApplier.readHistory(context)
                    previewIsFresh = false
                    statusLine = if (result.allOk) "Wallpaper set"
                        else "Wallpaper set (one screen failed)"
                    snackbar.showSnackbar(
                        if (result.allOk) "Wallpaper set"
                        else "Wallpaper set — one screen failed (see logs)")
                    WepWawetLogger.appInfo("DiscoverScreen: applied",
                        "home_state" to homeState,
                        "lock_state" to lockState,
                        "separate_lock" to (lockCrop != null),
                        "cropped_home" to (homeOverride != null),
                        "cropped_lock" to (lockOverride != null),
                        "home_ok" to result.homeOk,
                        "lock_ok" to result.lockOk)
                } else {
                    statusLine = "Wallpaper write failed (home=${result.homeOk}, lock=${result.lockOk})"
                    snackbar.showSnackbar("Wallpaper write failed (see logs)")
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

    fun applyShown() = applyBitmap()

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
            "dual" to DiscoverPrefs.isDualMode(homeState, lockState))
        scope.launch {
            try {
                fetchAndShow(
                    homeState = homeState,
                    lockState = lockState,
                    strictTags = strictTags,
                    softTags = softTags,
                    styleValue = styleValue,
                    r18Mode = r18Mode,
                    sources = sources,
                )
                statusLine = "New image — press Set to apply"
            } catch (e: DiscoverSearch.NoImageException) {
                statusLine = when {
                    sources.isEmpty() ->
                        "Pick at least one booru source below"
                    else -> "No image found — try different tags"
                }
                WepWawetLogger.appWarn("DiscoverScreen: no image found",
                    "message" to (e.message ?: ""),
                    "sources" to sources.size)
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
                    DiscoverApplier.cropForScreen(context, bmp)
                }
                val result = DiscoverApplier.applyScreens(context, crop, homeState, lockState, null)
                if (result.ok) {
                    // Pop the restored entry off the ring.
                    runCatching { entry.file.delete() }
                    history = DiscoverApplier.readHistory(context)
                    previewBitmap = bmp
                    previewIsFresh = false
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

    /** Cycle a screen's tri-state Off → Pink → Blue → Off. */
    fun cycleScreen(isHome: Boolean) {
        val (h, l) = screens
        val wasDual = DiscoverPrefs.isDualMode(h, l)
        val newPair = if (isHome) ((h + 1) % 3) to l else h to ((l + 1) % 3)
        screens = newPair
        DiscoverPrefs.writeScreens(context, newPair.first, newPair.second)
        WepWawetLogger.appInfo("DiscoverScreen: screen toggled",
            "which" to if (isHome) "home" else "lock",
            "home_state" to newPair.first,
            "lock_state" to newPair.second,
            "mode" to when {
                DiscoverPrefs.isDualMode(newPair.first, newPair.second) -> "dual"
                DiscoverPrefs.isSyncMode(newPair.first, newPair.second) -> "sync"
                else -> "single"
            })
        // Entering dual mode with a home image but no lock image → fetch
        // the lock complement in the background (1.0's complement work).
        val nowDual = DiscoverPrefs.isDualMode(newPair.first, newPair.second)
        if (nowDual && !wasDual && previewBitmap != null && previewLockBitmap == null && !fetching) {
            scope.launch {
                try {
                    val lockUrl = withContext(Dispatchers.IO) {
                        DiscoverSearch.searchOne(
                            context = context,
                            strictTags = strictTags,
                            softTags = softTags,
                            styleValue = styleValue,
                            r18Mode = r18Mode,
                            recentUrls = DiscoverPrefs.readRecentUrls(context),
                            sources = sources,
                        )
                    }
                    val lockBytes = withContext(Dispatchers.IO) { DiscoverSearch.downloadImage(lockUrl) }
                    DiscoverPrefs.pushRecentUrl(context, lockUrl)
                    previewLockBitmap = withContext(Dispatchers.IO) {
                        DiscoverApplier.decode(lockBytes, DiscoverApplier.maxDecodeDimension(context))
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
        // v0.8.17: keep the preload queue topped up, retire the legacy
        // background worker from the scheduler days, and dissolve the
        // short-lived v0.8.16 "Discover" playlist for upgraders (auto
        // refresh has no Discover-side machinery anymore).
        refillQueue()
        withContext(Dispatchers.IO) {
            runCatching {
                com.wepwawet.playlist.SuitePlaylistStore
                    .removeDiscoverPlaylistIfPresent(context)
            }
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
            // ===== v0.8.20 FIXED PREVIEW WINDOW: the window's height is
            // FIXED at 42% of the screen (phone aspect, centered) — the
            // action rows below can never be pushed off screen. The image
            // floats inside, pan/zoomable; Set applies exactly the VISIBLE
            // region.
            val shownBmp =
                if (previewingLock && previewLockBitmap != null) previewLockBitmap else previewBitmap
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = fixedWindowHeight),
                contentAlignment = Alignment.Center,
            ) {
                // The exact window size: phone aspect inside the fixed
                // height budget, capped by the available width.
                val winW = minOf(maxWidth, fixedWindowWidth)
                Box(
                    modifier = Modifier
                        .size(width = winW, height = fixedWindowHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                if (shownBmp != null) {
                    // The pan/zoomable image. The math here mirrors
                    // DiscoverApplier.cropVisibleWindow EXACTLY, so what Set
                    // applies is exactly what this window shows.
                    //
                    // Units: dispW/dispH are multipliers of the WINDOW size
                    // (the image layer fills the Box, then scales). The
                    // aspect is preserved in PIXELS: drawn width =
                    // dispW·Wpx, drawn height = dispW·Wpx/imgAspect, so
                    // scaleY = dispW·windowAspect/imgAspect — NOT
                    // dispW/imgAspect (that would distort the image by the
                    // window's aspect).
                    val imgAspect = shownBmp.width.toFloat() / shownBmp.height
                    // The scale (at zoom 1) that COVERS the window:
                    //   tall image → dispW = 1 (full width; height covers);
                    //   wide image → dispW = imgAspect/winAspect (height
                    //   covers). Verified against cropVisibleWindow's math.
                    val cover = maxOf(1f, imgAspect.coerceAtLeast(0.01f) / phoneAspect)
                    val dispW = cover * previewZoom
                    val dispH = dispW * phoneAspect / imgAspect.coerceAtLeast(0.01f)
                    // Pan (fraction −1..1 of the slack per axis) → top-left
                    // offset in window fractions.
                    val slackW = maxOf(0f, dispW - 1f)
                    val slackH = maxOf(0f, dispH - 1f)
                    val offX = 0.5f - previewPanX * slackW / 2f - dispW / 2f
                    val offY = 0.5f - previewPanY * slackH / 2f - dispH / 2f
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    if (gestureZoom != 1f) {
                                        previewZoom =
                                            (previewZoom * gestureZoom).coerceIn(1f, 6f)
                                    }
                                    if (pan != Offset.Zero) {
                                        // Pan px → window fractions.
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        if (w > 0f && h > 0f) {
                                            // Drag moves the IMAGE with the
                                            // finger; the pan fraction runs
                                            // −1..1 of the axis slack.
                                            val fw = pan.x / w
                                            val fh = pan.y / h
                                            previewPanX =
                                                (previewPanX - fw / maxOf(slackW, 1e-6f) * 2f)
                                                    .coerceIn(-1f, 1f)
                                            previewPanY =
                                                (previewPanY - fh / maxOf(slackH, 1e-6f) * 2f)
                                                    .coerceIn(-1f, 1f)
                                        }
                                    }
                                }
                            },
                    ) {
                        Image(
                            bitmap = shownBmp.asImageBitmap(),
                            contentDescription = "Wallpaper preview",
                            // The layer fills the Box; the graphicsLayer
                            // below gives it the aspect-correct drawn size.
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = dispW
                                    scaleY = dispH
                                    translationX = offX * size.width
                                    translationY = offY * size.height
                                },
                        )
                    }
                    // Dual-mode preview switch (tap the label).
                    if (DiscoverPrefs.isDualMode(homeState, lockState) &&
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
                    // v0.8.19: the zoom badge — shows the current zoom and
                    // resets the window on tap (pan/zoom back to center).
                    if (previewZoom > 1.001f || previewPanX != 0f || previewPanY != 0f) {
                        Text(
                            "%.1f× — tap to reset".format(previewZoom),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { resetPreviewWindow() }
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
            }

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
            // Refresh only fetches a new image into the preview; Set commits
            // the shown image — and now OFFERS A CROP FIRST (v0.8.17).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showSetChoice = true },
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

            // ===== Save row (v0.8.18: folder only) =====
            // "remove the suite images and just have it ask you to choose
            // or make a folder when you try to import an image" — the suite
            // button is gone; the save dialog lists folders and its
            // "+ folder" offers existing-or-create.
            OutlinedButton(
                onClick = {
                    saveFolders = ImageFolderAssets.readFolders(context)
                    showSaveToFolder = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Save to folder")
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

            // ===== Sources (v0.8.18: "add the ability to choose the booru
            // sources for the discover page") — multi-select chips; the
            // search ladder only queries the ticked ones. =====
            Text("Sources", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (id in DiscoverPrefs.ALL_SOURCES) {
                    FilterChip(
                        selected = id in sources,
                        onClick = {
                            sources = if (id in sources) sources - id
                                else sources + id
                            DiscoverPrefs.writeSources(context, sources)
                            WepWawetLogger.appInfo("DiscoverScreen: source toggled",
                                "source" to id,
                                "enabled" to (id in sources),
                                "total" to sources.size)
                            // Re-stock the queue for the new selection.
                            refillQueue()
                        },
                        label = { Text(DiscoverPrefs.sourceLabel(id)) },
                    )
                }
            }
            Text(
                "Only the ticked sites are searched. Gelbooru and Behoimi are off " +
                    "by default (Gelbooru now needs an API key; Behoimi is offline).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ===== Style =====
            Text("Style", style = MaterialTheme.typography.titleMedium)
            // v0.8.18 (review B10): persistence moved to onValueChangeFinished —
            // the old onValueChange write hit SharedPreferences on EVERY drag
            // frame (dozens of disk writes + a log line per gesture). The
            // drag itself now updates memory only, like PlaylistEditorScreen's
            // interval slider and the engine FPS slider already do.
            Slider(
                value = styleValue.toFloat(),
                onValueChange = { styleValue = it.toInt() },
                onValueChangeFinished = {
                    DiscoverPrefs.writeStyle(context, styleValue)
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
                    selected = r18Mode == DiscoverPrefs.R18_PURE,
                    onClick = {
                        r18Mode = DiscoverPrefs.R18_PURE
                        DiscoverPrefs.writeR18(context, r18Mode)
                        WepWawetLogger.appInfo("DiscoverScreen: content mode", "mode" to "pure")
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                ) { Text("Pure") }
                SegmentedButton(
                    selected = r18Mode == DiscoverPrefs.R18_MIX,
                    onClick = {
                        r18Mode = DiscoverPrefs.R18_MIX
                        DiscoverPrefs.writeR18(context, r18Mode)
                        WepWawetLogger.appInfo("DiscoverScreen: content mode", "mode" to "mix")
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                ) { Text("Mix") }
                SegmentedButton(
                    selected = r18Mode == DiscoverPrefs.R18_NSFW,
                    onClick = {
                        r18Mode = DiscoverPrefs.R18_NSFW
                        DiscoverPrefs.writeR18(context, r18Mode)
                        WepWawetLogger.appInfo("DiscoverScreen: content mode", "mode" to "nsfw")
                    },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                ) { Text("NSFW") }
            }

            // v0.8.17: the Auto refresh section is GONE from Discover
            // ("remove auto refresh entirely from the section. dont have it
            // be a normal play list") — no Discover-side rotation machinery
            // and no special playlist. Rotation belongs to the playlist
            // system: save images you like (suite/folder), then add them
            // to a playlist from the library like anything else.

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
                                            DiscoverApplier.cropForScreen(context, bmp)
                                        }
                                        val result = DiscoverApplier.applyScreens(
                                            context, crop, homeState, lockState, null)
                                        if (result.ok) {
                                            previewBitmap = bmp
                                            previewIsFresh = false
                                            statusLine = "Restored"
                                            snackbar.showSnackbar(
                                                if (result.allOk) "Restored"
                                                else "Restored — one screen failed")
                                            WepWawetLogger.appInfo(
                                                "DiscoverScreen: restored from history",
                                                "file" to entry.file.name,
                                                "home_ok" to result.homeOk,
                                                "lock_ok" to result.lockOk)
                                        } else {
                                            // v0.8.18: restores used to fail
                                            // SILENTLY ("the history doesnt
                                            // work" part two).
                                            snackbar.showSnackbar("Restore failed (see logs)")
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
                    // v0.8.18: "+ folder" opens the shared MENU first —
                    // "make the create folder button come up with menu
                    // asking if you want to use an already existing or make
                    // one".
                    OutlinedButton(
                        onClick = { showFolderMenu = true },
                        enabled = !savingToFolder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("＋ folder")
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

    // ===== v0.8.17: Set-as-wallpaper choice ("you should also have the
    // option to crop it from there if you push set as wallpaper") =====
    if (showSetChoice) {
        AlertDialog(
            onDismissRequest = { if (!applying) showSetChoice = false },
            title = { Text("Set as wallpaper") },
            text = {
                Column {
                    Text("Crop the image first, or set it as-is.")
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = {
                            showSetChoice = false
                            showCropDialog = true
                        },
                        enabled = !applying,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Crop first…")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSetChoice = false
                        applyShown()
                    },
                    enabled = !applying,
                ) { Text("Set directly") }
            },
            dismissButton = {
                TextButton(onClick = { showSetChoice = false }) { Text("Cancel") }
            },
        )
    }

    // ===== v0.8.17: the crop-before-setting dialog (shared surface with
    // the image editor: handles, presets incl. Phone, rule of thirds) ====
    if (showCropDialog) {
        val cropBmp =
            (if (previewingLock) previewLockBitmap else previewBitmap)
        if (cropBmp != null) {
            BitmapCropDialog(
                bitmap = cropBmp,
                onDismiss = { showCropDialog = false },
                onApply = { cropped ->
                    showCropDialog = false
                    // The crop replaces the image the user was LOOKING at
                    // (home, or lock in dual mode) for this apply only.
                    if (previewingLock) applyBitmap(lockOverride = cropped)
                    else applyBitmap(homeOverride = cropped)
                },
            )
        } else {
            showCropDialog = false
        }
    }

    // ===== v0.8.18: the "+ folder" MENU (shared) + the create-folder flow
    // (shared, in FolderPickers.kt) — the current image is saved into the
    // folder that comes out of either path. =====
    if (showFolderMenu) {
        com.wepwawet.ui.screens.FolderAddMenuDialog(
            onDismiss = { showFolderMenu = false },
            onChooseExisting = {
                showFolderMenu = false
                try {
                    saveFolderPicker.launch(null)
                } catch (t: Throwable) {
                    WepWawetLogger.appWarn(
                        "DiscoverScreen: folder picker failed",
                        "exception" to t.javaClass.name)
                }
            },
            onCreateNew = {
                showFolderMenu = false
                showCreateFolder = true
            },
        )
    }
    if (showCreateFolder) {
        com.wepwawet.ui.screens.CreateFolderFlow(
            visible = true,
            onDismiss = { showCreateFolder = false },
            onCreated = { dirUri ->
                showCreateFolder = false
                saveFolders = ImageFolderAssets.readFolders(context)
                // Save the current image straight into the new folder.
                saveToFolder(dirUri)
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
        DiscoverPrefs.SCREEN_PINK -> PinkState
        DiscoverPrefs.SCREEN_BLUE -> BlueState
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (state > DiscoverPrefs.SCREEN_OFF) Color.White
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
        if (state > DiscoverPrefs.SCREEN_OFF) {
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
private fun HistoryThumb(entry: DiscoverApplier.HistoryEntry) {
    // Light-weight decode: subsample to ~128 px for the strip.
    // No border (user request).
    // v0.8.18 (review B22): the decode moved OFF the composition thread —
    // the old `remember { decodeFile(...) }` ran BitmapFactory on the main
    // thread during composition (composition-time IO). The thumbnail loads
    // asynchronously now; the placeholder shows until it arrives.
    var bmp by remember(entry.file.absolutePath) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(entry.file.absolutePath) {
        bmp = withContext(Dispatchers.IO) {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 16 }
            android.graphics.BitmapFactory.decodeFile(entry.file.absolutePath, opts)
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp!!.asImageBitmap(),
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
