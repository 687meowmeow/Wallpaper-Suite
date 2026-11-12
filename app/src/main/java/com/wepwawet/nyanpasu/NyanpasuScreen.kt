package com.wepwawet.nyanpasu

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar
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
fun NyanpasuScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // ---- settings state ----
    var strictTags by remember { mutableStateOf(NyanpasuPrefs.readStrictTags(context)) }
    var softTags by remember { mutableStateOf(NyanpasuPrefs.readSoftTags(context)) }
    var styleValue by remember { mutableStateOf(NyanpasuPrefs.readStyle(context)) }
    var r18Mode by remember { mutableStateOf(NyanpasuPrefs.readR18(context)) }
    var scheduleMode by remember { mutableStateOf(NyanpasuPrefs.readSchedule(context)) }
    var screens by remember { mutableStateOf(NyanpasuPrefs.readScreens(context)) }
    val homeState = screens.first
    val lockState = screens.second

    // ---- preview state ----
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewLockBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewingLock by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    // ---- work state ----
    var fetching by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(NyanpasuApplier.readHistory(context)) }

    val metrics = context.resources.displayMetrics
    val screenAspect = metrics.widthPixels.toFloat() / metrics.heightPixels

    fun persistTags() = NyanpasuPrefs.writeTags(context, strictTags, softTags)

    /**
     * Fetch a fresh image (using the prefetch when fresh) and APPLY it —
     * one tap, exactly like 1.0's Update button. Dual mode fetches a
     * separate lock-screen image too. Afterwards the NEXT image is
     * prefetched in the background.
     */
    fun updateWallpaper() {
        if (fetching || applying) return
        fetching = true
        statusLine = "Searching…"
        WepWawetLogger.appInfo("DiscoverScreen: update start",
            "home_state" to homeState,
            "lock_state" to lockState,
            "dual" to NyanpasuPrefs.isDualMode(homeState, lockState))
        scope.launch {
            try {
                val maxDim = NyanpasuApplier.maxDecodeDimension(context)
                var url: String? = null
                var bytes: ByteArray? = null
                if (NyanpasuPrefs.isPrefetchFresh(context)) {
                    val meta = NyanpasuPrefs.readPrefetchMeta(context)
                    val cached = NyanpasuPrefs.prefetchFile(context).readBytes()
                    if (meta != null && cached.size > 1024) {
                        url = meta.first
                        bytes = cached
                        NyanpasuPrefs.clearPrefetch(context)
                        WepWawetLogger.appInfo("DiscoverScreen: using prefetched image",
                            "bytes" to cached.size,
                            "age_ms" to (System.currentTimeMillis() - meta.second))
                    }
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
                    return@launch
                }
                previewBitmap = bmp
                zoom = 1f; panX = 0f; panY = 0f
                previewingLock = false

                // Dual mode (pink/blue): a separate image for the lock screen.
                var lockBmp: Bitmap? = null
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
                    lockBmp = withContext(Dispatchers.IO) {
                        NyanpasuApplier.decode(lockBytes, maxDim)
                    }
                    previewLockBitmap = lockBmp
                    WepWawetLogger.appInfo("DiscoverScreen: lock image fetched",
                        "url" to lockUrl.take(100),
                        "dims" to (lockBmp?.let { "${it.width}x${it.height}" } ?: "null"))
                } else {
                    previewLockBitmap = null
                }

                // Apply — one tap = new wallpaper on the selected screens.
                applying = true
                statusLine = "Applying…"
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
                    statusLine = "Wallpaper updated"
                    snackbar.showSnackbar("Wallpaper updated")
                    WepWawetLogger.appInfo("DiscoverScreen: applied",
                        "home_state" to homeState,
                        "lock_state" to lockState,
                        "separate_lock" to (lockCrop != null))
                } else {
                    statusLine = "Wallpaper write failed (home=${result.homeOk}, lock=${result.lockOk})"
                }

                // Prefetch the NEXT image in the background (v1.0.0).
                scope.launch {
                    try {
                        val nextUrl = withContext(Dispatchers.IO) {
                            NyanpasuSearch.searchOne(
                                context = context,
                                strictTags = strictTags,
                                softTags = softTags,
                                styleValue = styleValue,
                                r18Mode = r18Mode,
                                recentUrls = NyanpasuPrefs.readRecentUrls(context),
                            )
                        }
                        val nextBytes = withContext(Dispatchers.IO) { NyanpasuSearch.downloadImage(nextUrl) }
                        if (nextBytes.size > 1024) {
                            NyanpasuPrefs.prefetchFile(context).writeBytes(nextBytes)
                            NyanpasuPrefs.writePrefetchMeta(context, nextUrl)
                            WepWawetLogger.appInfo("DiscoverScreen: prefetched next image",
                                "bytes" to nextBytes.size)
                        }
                    } catch (e: Exception) {
                        WepWawetLogger.appInfo("DiscoverScreen: prefetch failed (non-fatal)",
                            "exception" to e.javaClass.simpleName)
                    }
                }
            } catch (e: NyanpasuSearch.NoImageException) {
                statusLine = "No image found — try different tags"
                WepWawetLogger.appWarn("DiscoverScreen: no image found",
                    "message" to (e.message ?: ""))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The user left the screen — never swallow cancellation.
                throw e
            } catch (e: Exception) {
                statusLine = "Failed: ${e.message ?: e.javaClass.simpleName}"
                WepWawetLogger.appWarn("DiscoverScreen: update failed",
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
            } finally {
                fetching = false
                applying = false
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

    // Schedule changes re-arm the worker immediately.
    LaunchedEffect(scheduleMode) {
        NyanpasuPrefs.writeSchedule(context, scheduleMode)
        NyanpasuScheduler.reschedule(context)
    }

    // On open: show the LAST applied image (1.0's preview.loadPreview).
    // First ever launch with no history → fetch + apply one automatically.
    LaunchedEffect(Unit) {
        if (previewBitmap == null) {
            val newest = history.firstOrNull()
            if (newest != null) {
                previewBitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(newest.file.absolutePath)
                    }.getOrNull()
                }
            } else if (!fetching) {
                updateWallpaper()
            }
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
        panX = (panX + panChange.x).coerceIn(-3000f, 3000f)
        panY = (panY + panChange.y).coerceIn(-3000f, 3000f)
        if (zoom <= 1.001f) { panX = 0f; panY = 0f }
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
            // ===== Preview (borderless — no frame, no card) =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(screenAspect.coerceIn(0.4f, 1f))
                    .transformable(transformState),
                contentAlignment = Alignment.Center,
            ) {
                val shown =
                    if (previewingLock && previewLockBitmap != null) previewLockBitmap else previewBitmap
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "Wallpaper preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayerZoom(zoom, panX, panY),
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
                    Spacer(Modifier.height(10.dp))
                    Text(
                        statusLine ?: "Searching…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            statusLine ?: "Tap Update to find a wallpaper",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // ===== Tools row (v1.0.0: Undo / Home / Lock / Save) =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { undoWallpaper() }) {
                    Icon(Icons.Filled.Undo, contentDescription = "Undo")
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
                FilledTonalIconButton(onClick = { saveCurrentToGallery() }) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = "Save to gallery")
                }
            }
            Text(
                "Home and Lock cycle Off → Pink → Blue. Same color = one image " +
                    "for both screens; different colors = separate images.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ===== Update (1.0's btnUpdate — fetch + apply in one tap) =====
            Button(
                onClick = { updateWallpaper() },
                enabled = !fetching && !applying,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (fetching || applying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(when {
                    fetching -> statusLine ?: "Working…"
                    applying -> "Applying…"
                    else -> "Update wallpaper"
                })
            }
            if (fetching || applying) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
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

            // ===== Auto refresh =====
            Text("Auto refresh", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Off" to NyanpasuPrefs.SCHEDULE_OFF,
                    "Daily 7:00" to NyanpasuPrefs.SCHEDULE_DAILY_7,
                    "6 h" to NyanpasuPrefs.SCHEDULE_H6,
                    "12 h" to NyanpasuPrefs.SCHEDULE_H12,
                    "24 h" to NyanpasuPrefs.SCHEDULE_H24,
                ).forEach { (label, mode) ->
                    FilterChip(
                        selected = scheduleMode == mode,
                        onClick = {
                            scheduleMode = mode
                            WepWawetLogger.appInfo("DiscoverScreen: schedule changed",
                                "mode" to mode)
                        },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    WepWawetLogger.appInfo("DiscoverScreen: run-once requested")
                    NyanpasuScheduler.applyNow(context)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run once now (background)")
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
}

/** Apply the pinch/drag transform to the preview image. */
private fun Modifier.graphicsLayerZoom(zoom: Float, panX: Float, panY: Float): Modifier =
    this.then(
        Modifier.graphicsLayer {
            scaleX = zoom
            scaleY = zoom
            translationX = panX
            translationY = panY
        }
    )

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
 */
@Composable
private fun TagEditor(
    strictTags: List<String>,
    softTags: List<String>,
    onToggleStrict: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var field by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it.take(30) },
                singleLine = true,
                label = { Text("Add tag") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val trimmed = field.trim()
                    if (trimmed.isNotEmpty()) {
                        onAdd(trimmed)
                        field = ""
                    }
                },
            ) { Icon(Icons.Filled.Add, contentDescription = "Add tag") }
        }
        Text(
            "Tap a chip to switch between strict (filled) and soft (outline).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
