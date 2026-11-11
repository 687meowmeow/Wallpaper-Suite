package com.wepwawet.nyanpasu

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Anime — the auto-wallpaper screen (renamed from "Daily wallpaper" in
 * v0.8.10 per user request).
 *
 * Feature set ported from Nyanpasu Wallpaper v1.0.0 by FranklinNexus (MIT
 * License — attribution kept here in source only; no user-visible string
 * carries the name), plus the SAFE later-version reliability fixes. The
 * image-CHOICE philosophy is v1.0.0's verbatim (see [NyanpasuSearch]):
 * the generous tag ladder with NO quality gating — an image is ALWAYS
 * preferred over no image. The later versions' SearchImageQuality
 * (hard min-resolution floors, 9:19.5 aspect targeting, GIF rejection,
 * best-score determinism) is deliberately NOT ported — that is exactly
 * the change that ruined image choice.
 *
 * v1.0.0 features on this screen:
 *  • mascot speech bubble — random quips every 30-60 s, reacts to mode
 *    changes and refresh, 10-tap easter egg
 *  • one-time welcome dialog (quick guide)
 *  • Home/Lock screen toggles + same-or-different image (dual mode)
 *  • Home/Lock preview switch in dual mode (tap the indicator)
 *  • Pure / Mix / NSFW content tiers
 *  • style slider with kaomoji labels
 *  • strict/soft tag editors
 *  • auto-refresh schedule (Off / 7:00 daily / 6 h / 12 h / 24 h)
 *  • history (last 5) + restore + save-to-gallery
 *  • kaomoji status line
 *  • confetti burst when a wallpaper is applied
 *  • prefetch: the NEXT image downloads in the background after an
 *    apply/refresh, so the following Refresh is instant
 *  • WYSIWYG pinch/drag preview whose transform IS the applied crop
 *
 * v0.8.10 additions: DNS-over-HTTPS fallback (see [DohDns]) so filtered
 * networks stop producing the empty screen, and logging on every action.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var homeOn by remember { mutableStateOf(NyanpasuPrefs.readHomeOn(context)) }
    var lockOn by remember { mutableStateOf(NyanpasuPrefs.readLockOn(context)) }
    var lockSame by remember { mutableStateOf(NyanpasuPrefs.readLockSame(context)) }
    var scheduleMode by remember { mutableStateOf(NyanpasuPrefs.readSchedule(context)) }

    // ---- preview state (WYSIWYG: zoom/pan drives the applied crop) ----
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewLockBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewingLock by remember { mutableStateOf(false) }
    var previewSourceUrl by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var previewBoxSize by remember { mutableStateOf(IntSize.Zero) }

    // ---- work state ----
    var fetching by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var mascotTaps by remember { mutableStateOf(0) }
    var mascotQuip by remember { mutableStateOf(Kaomoji.idleQuip()) }
    var nextQuipAt by remember { mutableLongStateOf(System.currentTimeMillis() + Random.nextLong(15_000, 30_000)) }
    var showWelcome by remember { mutableStateOf(!NyanpasuPrefs.readWelcomeShown(context)) }
    var showEasterEgg by remember { mutableStateOf(false) }
    var confettiSeed by remember { mutableLongStateOf(0L) }
    var history by remember { mutableStateOf(NyanpasuApplier.readHistory(context)) }

    val metrics = context.resources.displayMetrics
    val screenAspect = metrics.widthPixels.toFloat() / metrics.heightPixels

    fun persistTags() = NyanpasuPrefs.writeTags(context, strictTags, softTags)

    /**
     * Fetch a fresh wallpaper. Uses the prefetched image when it's fresh
     * (v1.0.0's PrefetchCoordinator behavior) and kicks off the NEXT
     * prefetch in the background.
     */
    fun fetchNew(useCache: Boolean = true) {
        if (fetching) return
        fetching = true
        statusLine = Kaomoji.searching()
        WepWawetLogger.appInfo("AnimeScreen: fetch start", "use_prefetch" to useCache)
        scope.launch {
            try {
                val maxDim = NyanpasuApplier.maxDecodeDimension(context)
                var url: String? = null
                var bytes: ByteArray? = null
                if (useCache && NyanpasuPrefs.isPrefetchFresh(context)) {
                    val meta = NyanpasuPrefs.readPrefetchMeta(context)
                    val cached = NyanpasuPrefs.prefetchFile(context).readBytes()
                    if (meta != null && cached.size > 1024) {
                        url = meta.first
                        bytes = cached
                        NyanpasuPrefs.clearPrefetch(context)
                        WepWawetLogger.appInfo("AnimeScreen: using prefetched image",
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
                    url = fetched
                    bytes = withContext(Dispatchers.IO) { NyanpasuSearch.downloadImage(fetched) }
                }
                val imgBytes = bytes!!
                NyanpasuPrefs.pushRecentUrl(context, url!!)
                val bmp = withContext(Dispatchers.IO) { NyanpasuApplier.decode(imgBytes, maxDim) }
                if (bmp == null) {
                    statusLine = "Downloaded image failed to decode"
                    mascotQuip = Kaomoji.errorQuip()
                    WepWawetLogger.appWarn("AnimeScreen: decode failed",
                        "url" to url!!.take(100), "bytes" to imgBytes.size)
                    return@launch
                }
                previewBitmap = bmp
                previewSourceUrl = url
                zoom = 1f; panX = 0f; panY = 0f
                statusLine = null
                mascotQuip = Kaomoji.foundQuip()
                WepWawetLogger.appInfo("AnimeScreen: fetched",
                    "url" to url!!.take(100),
                    "dims" to "${bmp.width}x${bmp.height}")

                // Dual mode (v1.0.0 粉蓝异色): a SEPARATE image for the lock
                // screen when the screens are on and set to different.
                if (lockOn && !lockSame) {
                    statusLine = "Matching a lock-screen image… (・ω・)"
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
                    previewLockBitmap = withContext(Dispatchers.IO) {
                        NyanpasuApplier.decode(lockBytes, maxDim)
                    }
                    WepWawetLogger.appInfo("AnimeScreen: lock image fetched",
                        "url" to lockUrl.take(100),
                        "dims" to (previewLockBitmap?.let { "${it.width}x${it.height}" } ?: "null"))
                } else {
                    previewLockBitmap = null
                }
                statusLine = null

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
                            WepWawetLogger.appInfo("AnimeScreen: prefetched next image",
                                "bytes" to nextBytes.size)
                        }
                    } catch (e: Exception) {
                        WepWawetLogger.appInfo("AnimeScreen: prefetch failed (non-fatal)",
                            "exception" to e.javaClass.simpleName)
                    }
                }
            } catch (e: NyanpasuSearch.NoImageException) {
                statusLine = "No image found — try different tags"
                mascotQuip = Kaomoji.errorQuip()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The user left the screen — never swallow cancellation.
                throw e
            } catch (e: Exception) {
                statusLine = "Failed: ${e.message ?: e.javaClass.simpleName}"
                mascotQuip = Kaomoji.errorQuip()
                WepWawetLogger.appWarn("AnimeScreen: fetch failed",
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
            } finally {
                fetching = false
            }
        }
    }

    fun applyCurrent() {
        val bmp = previewBitmap ?: run {
            scope.launch { snackbar.showSnackbar("Fetch an image first") }
            return
        }
        if (applying) return
        applying = true
        scope.launch {
            try {
                // WYSIWYG: the preview's zoom/pan (mapped from preview px to
                // screen px) is exactly the crop the screen receives.
                val scaleToScreen = if (previewBoxSize.width > 0)
                    metrics.widthPixels.toFloat() / previewBoxSize.width else 1f
                val crop = withContext(Dispatchers.IO) {
                    NyanpasuApplier.cropForScreen(
                        context, bmp, zoom, panX * scaleToScreen, panY * scaleToScreen)
                }
                val lockCrop = if (lockOn && !lockSame) {
                    previewLockBitmap?.let { lock ->
                        withContext(Dispatchers.IO) {
                            NyanpasuApplier.cropForScreen(context, lock, 1f, 0f, 0f)
                        }
                    }
                } else null
                val result = NyanpasuApplier.apply(context, crop, homeOn, lockOn, lockCrop)
                if (result.ok) {
                    NyanpasuApplier.pushHistory(context, crop)
                    history = NyanpasuApplier.readHistory(context)
                    confettiSeed = System.currentTimeMillis()
                    mascotQuip = Kaomoji.appliedQuip()
                    snackbar.showSnackbar("Wallpaper set ✓ ${Kaomoji.happy()}")
                    WepWawetLogger.appInfo("AnimeScreen: applied",
                        "zoom" to zoom,
                        "home" to homeOn,
                        "lock" to lockOn,
                        "separate_lock" to (lockCrop != null))
                } else {
                    snackbar.showSnackbar(
                        "Wallpaper write failed (home=${result.homeOk}, lock=${result.lockOk})")
                }
            } finally {
                applying = false
            }
        }
    }

    fun undoTo(entry: NyanpasuApplier.HistoryEntry) {
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeFile(entry.file.absolutePath)
                } ?: return@launch
                val result = NyanpasuApplier.apply(context, bmp, homeOn, lockOn, null)
                if (result.ok) {
                    previewBitmap = bmp
                    previewSourceUrl = "(history)"
                    zoom = 1f; panX = 0f; panY = 0f
                    mascotQuip = Kaomoji.restoredQuip()
                    snackbar.showSnackbar("Restored the previous wallpaper ✓")
                    WepWawetLogger.appInfo("AnimeScreen: restored from history",
                        "file" to entry.file.name)
                }
            } catch (e: Exception) {
                snackbar.showSnackbar("Restore failed: ${e.message}")
            }
        }
    }

    fun saveCurrentToGallery() {
        val bmp = previewBitmap ?: run {
            scope.launch { snackbar.showSnackbar("Nothing to save yet") }
            return
        }
        scope.launch {
            val scaleToScreen = if (previewBoxSize.width > 0)
                metrics.widthPixels.toFloat() / previewBoxSize.width else 1f
            val crop = withContext(Dispatchers.IO) {
                NyanpasuApplier.cropForScreen(
                    context, bmp, zoom, panX * scaleToScreen, panY * scaleToScreen)
            }
            val ok = NyanpasuApplier.saveToGallery(context, crop)
            snackbar.showSnackbar(if (ok) "Saved to Pictures/WallpaperSuite ✓" else "Save failed")
            if (ok) mascotQuip = Kaomoji.savedQuip()
        }
    }

    // Schedule changes re-arm the worker immediately.
    LaunchedEffect(scheduleMode) {
        NyanpasuPrefs.writeSchedule(context, scheduleMode)
        NyanpasuScheduler.reschedule(context)
    }

    // v1.0.0 mascot: a fresh quip every 30-60 s while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            if (System.currentTimeMillis() >= nextQuipAt && !fetching && !applying) {
                mascotQuip = Kaomoji.idleQuip()
                nextQuipAt = System.currentTimeMillis() + Random.nextLong(30_000, 60_000)
            }
        }
    }

    // Auto-fetch on open — a wallpaper app should present a wallpaper.
    LaunchedEffect(Unit) {
        if (previewBitmap == null && !fetching) fetchNew()
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
                title = "Anime wallpapers",
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ===== WYSIWYG preview =====
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(screenAspect.coerceIn(0.4f, 1f))
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .onSizeChanged { previewBoxSize = it }
                        .transformable(transformState),
                    contentAlignment = Alignment.Center,
                ) {
                    val shown = if (previewingLock && previewLockBitmap != null) previewLockBitmap else previewBitmap
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Wallpaper preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayerZoom(zoom, panX, panY),
                        )
                        // v1.0.0 preview indicator: tap to switch Home/Lock
                        // preview in dual mode (主屏预览 / 锁屏预览).
                        if (lockOn && !lockSame && previewLockBitmap != null) {
                            Text(
                                if (previewingLock) "Lock preview — tap to switch" else "Home preview — tap to switch",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable { previewingLock = !previewingLock }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            if (fetching) {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    statusLine ?: "Summoning… ⌛",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    statusLine ?: "Waiting for magic… (｡•́︿•̀｡)\nTap Refresh to find a wallpaper",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                Text(
                    "Pinch to zoom, drag to adjust — what you see is what gets applied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ===== Mascot quip (v1.0.0's 看板娘 line, app-branded) =====
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable {
                            // v1.0.0 easter egg: 10 taps on the mascot's line.
                            mascotTaps++
                            if (mascotTaps >= 10) {
                                mascotTaps = 0
                                showEasterEgg = true
                                WepWawetLogger.appInfo("AnimeScreen: easter egg unlocked")
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        statusLine ?: mascotQuip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                // ===== Refresh + apply =====
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { mascotQuip = Kaomoji.searching(); fetchNew() },
                        enabled = !fetching,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (fetching) "Searching…" else "Refresh ✨")
                    }
                    Button(
                        onClick = { applyCurrent() },
                        enabled = !applying && previewBitmap != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (applying) "Applying…" else "Set as wallpaper")
                    }
                }
                if (applying || fetching) LinearProgressIndicator(Modifier.fillMaxWidth())

                // ===== Tags =====
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tags", style = MaterialTheme.typography.titleMedium)
                        TagEditor(
                            title = "Strict tags 🔒 (required intent)",
                            tags = strictTags,
                            onAdd = { strictTags = strictTags + it; persistTags() },
                            onRemove = { strictTags = strictTags - it; persistTags() },
                        )
                        TagEditor(
                            title = "Soft tags 🎲 (hints)",
                            tags = softTags,
                            onAdd = { softTags = softTags + it; persistTags() },
                            onRemove = { softTags = softTags - it; persistTags() },
                        )
                        Text(
                            "Strict tags are searched first; soft tags join 38% of queries. " +
                                "Niche names (Genshin, Blue Archive, Touhou, Star Rail, ZZZ, " +
                                "Arknights, FGO, Hololive) map to their canonical booru tags.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ===== Style slider =====
                Card {
                    Column(Modifier.padding(16.dp)) {
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
                                styleValue < 35 -> "Small ( ˘•ω•˘ )"
                                styleValue > 65 -> "Large ( ˶ˆᗜˆ˵ )"
                                else -> "Neutral ( ˶˘ ³˘)˶"
                            } + " — only applied when a source supports the tag group.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ===== Content tier =====
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Content", style = MaterialTheme.typography.titleMedium)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = r18Mode == NyanpasuPrefs.R18_PURE,
                                onClick = {
                                    r18Mode = NyanpasuPrefs.R18_PURE
                                    NyanpasuPrefs.writeR18(context, r18Mode)
                                    mascotQuip = "Safe mode engaged! 🛡️"
                                },
                                shape = SegmentedButtonDefaults.itemShape(0, 3),
                            ) { Text("Pure") }
                            SegmentedButton(
                                selected = r18Mode == NyanpasuPrefs.R18_MIX,
                                onClick = {
                                    r18Mode = NyanpasuPrefs.R18_MIX
                                    NyanpasuPrefs.writeR18(context, r18Mode)
                                    mascotQuip = "Mix mode — leaning cute, nothing explicit (⁄ ⁄•⁄ω⁄•⁄ ⁄)"
                                },
                                shape = SegmentedButtonDefaults.itemShape(1, 3),
                            ) { Text("Mix") }
                            SegmentedButton(
                                selected = r18Mode == NyanpasuPrefs.R18_NSFW,
                                onClick = {
                                    r18Mode = NyanpasuPrefs.R18_NSFW
                                    NyanpasuPrefs.writeR18(context, r18Mode)
                                    mascotQuip = "Ahem… adult mode on. 🔥"
                                },
                                shape = SegmentedButtonDefaults.itemShape(2, 3),
                            ) { Text("NSFW") }
                        }
                    }
                }

                // ===== Screens (v1.0.0 Home/Lock with same-or-different) =====
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Screens", style = MaterialTheme.typography.titleMedium)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Home screen")
                            Switch(checked = homeOn, onCheckedChange = {
                                homeOn = it
                                NyanpasuPrefs.writeScreens(context, homeOn, lockOn, lockSame)
                            })
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Lock screen")
                            Switch(checked = lockOn, onCheckedChange = {
                                lockOn = it
                                NyanpasuPrefs.writeScreens(context, homeOn, lockOn, lockSame)
                            })
                        }
                        if (lockOn) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Same image on both")
                                Switch(checked = lockSame, onCheckedChange = {
                                    lockSame = it
                                    NyanpasuPrefs.writeScreens(context, homeOn, lockOn, lockSame)
                                })
                            }
                            if (!lockSame) {
                                Text(
                                    "Dual mode: the lock screen gets its own image — " +
                                        "fetch finds both, tap the preview's label to switch views.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ===== Schedule =====
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    onClick = { scheduleMode = mode },
                                    label = { Text(label) },
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { NyanpasuScheduler.applyNow(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Run once now (background)")
                        }
                    }
                }

                // ===== History + save =====
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("History", style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { saveCurrentToGallery() }) {
                                Icon(Icons.Filled.SaveAlt, contentDescription = "Save to gallery")
                            }
                        }
                        if (history.isEmpty()) {
                            Text(
                                "The last 5 applied wallpapers appear here — tap one to restore it.",
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
                                        modifier = Modifier.clickable { undoTo(entry) },
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
                    }
                }

                Text(
                    "Fresh wallpapers from anime image boards — strict tags first, " +
                        "soft tags as hints, always something new.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                )
            }

            // ===== Confetti (v1.0.0's ribbon burst on success) =====
            if (confettiSeed != 0L) {
                ConfettiOverlay(seed = confettiSeed) { confettiSeed = 0L }
            }
        }
    }

    // ===== One-time welcome dialog (v1.0.0 quick guide) =====
    if (showWelcome) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Anime wallpapers 🌸") },
            text = {
                Text(
                    "Fresh wallpapers from anime image boards, right in WallpaperSuite.\n\n" +
                        "✨ Quick guide:\n" +
                        "• Refresh finds a new image; pinch/drag the preview to frame it\n" +
                        "• What you see in the preview is exactly what gets applied\n" +
                        "• Home/Lock can share one image or use two different ones\n" +
                        "• Add tags for preferences — strict tags always apply, soft tags 38% of the time\n" +
                        "• Auto refresh can change the wallpaper on a schedule\n\n" +
                        "Tip: tap the speech bubble 10 times for a surprise~ (¬‿¬)"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showWelcome = false
                    NyanpasuPrefs.writeWelcomeShown(context)
                    WepWawetLogger.appInfo("AnimeScreen: welcome dialog dismissed")
                }) { Text("Let's go ✨") }
            },
        )
    }

    // ===== Easter egg dialog (v1.0.0's 10-tap mascot secret) =====
    if (showEasterEgg) {
        AlertDialog(
            onDismissRequest = { showEasterEgg = false },
            title = { Text("You found it! (✧ω✧)") },
            text = {
                Text(
                    "Ten taps on the bubble — dedication!\n" +
                        "The wallpaper gods smile upon you.\n\n" +
                        "This one's from WallpaperSuite with ❤ — " +
                        "go enjoy your fresh pixels."
                )
            },
            confirmButton = {
                TextButton(onClick = { showEasterEgg = false }) { Text("Back to wallpapers") }
            },
        )
    }
}

/** Apply the WYSIWYG transform to the preview image. */
private fun Modifier.graphicsLayerZoom(zoom: Float, panX: Float, panY: Float): Modifier =
    this.then(
        Modifier.graphicsLayer {
            scaleX = zoom
            scaleY = zoom
            translationX = panX
            translationY = panY
        }
    )

@Composable
private fun TagEditor(
    title: String,
    tags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var field by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (tag in tags) {
                    AssistChip(
                        onClick = { onRemove(tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove $tag",
                                modifier = Modifier.size(14.dp),
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
                    if (trimmed.isNotEmpty() && trimmed !in tags) {
                        onAdd(trimmed)
                        field = ""
                    }
                },
            ) { Icon(Icons.Filled.Add, contentDescription = null) }
        }
    }
}

@Composable
private fun HistoryThumb(entry: NyanpasuApplier.HistoryEntry) {
    // Light-weight decode: subsample to ~128 px for the strip.
    // v0.8.10: no border — the user asked for the border thing to go.
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
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.History, contentDescription = null) }
    }
}

/**
 * v1.0.0's confetti burst — 26 particles in the app palette that fall and
 * spin for ~1.6 s after a successful apply. Purely decorative.
 */
@Composable
private fun ConfettiOverlay(seed: Long, onDone: () -> Unit) {
    val progress = remember(seed) { Animatable(0f) }
    LaunchedEffect(seed) {
        progress.animateTo(1f, tween(durationMillis = 1600))
        onDone()
    }
    val colors = listOf(
        Color(0xFFF48FB1), Color(0xFF81D4FA), Color(0xFFFFF176),
        Color(0xFFA5D6A7), Color(0xFFCE93D8), Color(0xFFFFAB91),
    )
    val particles = remember(seed) {
        List(26) { i ->
            Triple(
                Random(seed + i).nextFloat(),          // x fraction
                Random(seed + i + 100).nextFloat(),    // delay fraction
                colors[i % colors.size],
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        for ((xFrac, delayFrac, color) in particles) {
            val p = ((progress.value - delayFrac * 0.25f) / 0.75f).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) continue
            Box(
                modifier = Modifier
                    .offset(
                        x = (xFrac * 360f + (p * 40f * (if (xFrac > 0.5f) 1f else -1f))).dp,
                        y = (p * 640f).dp,
                    )
                    .size(8.dp)
                    .graphicsLayer { rotationZ = p * 540f; alpha = 1f - p }
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/** v1.0.0's mascot lines + kaomoji status flavors (app-branded). */
private object Kaomoji {
    private val idle = listOf(
        "Fresh pixels incoming. (｡•̀ᴗ-)✧",
        "A wallpaper a day keeps monotony away. (ﾉ≧∀≦)ﾉ",
        "Soft tags are like seasoning — 38% of the time, perfect.",
        "Zoom in — the good part is always in the crop.",
        "Browsing the boards so you don't have to~",
        "Late night refresh? No judgment. ( ˘ω˘ )",
    )
    private val searchingLines = listOf(
        "Summoning… ⌛",
        "Downloading… (๑•̀ㅂ•́)و✧",
        "Searching the boards… (ノ°▽°)ノ",
    )
    private val found = listOf(
        "Found a good one! (ﾉ>ω<)ﾉ",
        "All done! (ﾉ>ω<)ﾉ",
        "Fresh from the oven~ (´｡• ᵕ •｡`)",
    )
    private val applied = listOf(
        "Wallpaper delivered! ( ´ ▽ ` )ﾉ",
        "Applied! Enjoy the fresh view ✨",
    )
    private val error = listOf(
        "Oops! Something went wrong (╥﹏╥)",
        "No luck this time… (´;ω;`)",
    )

    fun idleQuip() = idle.random()
    fun searching() = searchingLines.random()
    fun foundQuip() = found.random()
    fun appliedQuip() = applied.random()
    fun errorQuip() = error.random()
    fun restoredQuip() = "Previous wallpaper restored! 🔙"
    fun savedQuip() = "Saved! Your gallery thanks you. 📸"
    fun happy() = "(ﾉ>ω<)ﾉ"
}
