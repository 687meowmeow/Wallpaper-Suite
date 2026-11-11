package com.wepwawet.nyanpasu

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Daily — the auto-wallpaper screen.
 *
 * Search-engine behavior reimplemented from Nyanpasu Wallpaper v1.0.0
 * by FranklinNexus (MIT License — attribution kept here in source only;
 * no user-visible string carries the name). The 1.0 image philosophy is
 * preserved verbatim (see [NyanpasuSearch]): the generous tag ladder
 * (strict → soft-hinted → slider-only → untagged fallback) with NO
 * quality gating — an image is ALWAYS preferred over no image.
 *
 * v0.8.9: renamed to "Daily wallpaper" and merged into the app's Material
 * 3 look — a bottom-nav tab (no back arrow), auto-fetch on open (the old
 * "Tap Refresh" black box is gone), a themed placeholder while searching,
 * and the same cards/typography as the rest of WallpaperSuite.
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
    var history by remember { mutableStateOf(NyanpasuApplier.readHistory(context)) }

    val metrics = context.resources.displayMetrics
    val screenAspect = metrics.widthPixels.toFloat() / metrics.heightPixels

    fun persistTags() = NyanpasuPrefs.writeTags(context, strictTags, softTags)

    fun fetchNew() {
        if (fetching) return
        fetching = true
        statusLine = null
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    NyanpasuSearch.searchOne(
                        context = context,
                        strictTags = strictTags,
                        softTags = softTags,
                        styleValue = styleValue,
                        r18Mode = r18Mode,
                        recentUrls = NyanpasuPrefs.readRecentUrls(context),
                    )
                }
                val bytes = withContext(Dispatchers.IO) { NyanpasuSearch.downloadImage(url) }
                NyanpasuPrefs.pushRecentUrl(context, url)
                val bmp = withContext(Dispatchers.IO) {
                    NyanpasuApplier.decode(bytes, NyanpasuApplier.maxDecodeDimension(context))
                }
                if (bmp == null) {
                    statusLine = "Downloaded image failed to decode"
                    return@launch
                }
                previewSourceUrl = url
                previewBitmap = bmp
                zoom = 1f; panX = 0f; panY = 0f
                statusLine = null
                WepWawetLogger.appInfo("NyanpasuScreen: fetched",
                    "url" to url.take(100),
                    "dims" to "${bmp.width}x${bmp.height}")
            } catch (e: NyanpasuSearch.NoImageException) {
                statusLine = "No image found — try different tags"
            } catch (e: Exception) {
                statusLine = "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                fetching = false
            }
        }
    }

    fun applyCurrent(useLockBitmap: Bitmap? = null) {
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
                val result = NyanpasuApplier.apply(context, crop, homeOn, lockOn, useLockBitmap)
                if (result.ok) {
                    NyanpasuApplier.pushHistory(context, crop)
                    history = NyanpasuApplier.readHistory(context)
                    snackbar.showSnackbar("Wallpaper set ✓")
                    WepWawetLogger.appInfo("NyanpasuScreen: applied",
                        "zoom" to zoom,
                        "home" to homeOn,
                        "lock" to lockOn)
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
                    snackbar.showSnackbar("Restored the previous wallpaper ✓")
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
        }
    }

    // Schedule changes re-arm the worker immediately.
    LaunchedEffect(scheduleMode) {
        NyanpasuPrefs.writeSchedule(context, scheduleMode)
        NyanpasuScheduler.reschedule(context)
    }

    // v0.8.9: auto-fetch on open — the screen used to show a black box
    // with "Tap Refresh to find a wallpaper" until the user acted; a
    // wallpaper app should present a wallpaper.
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
                title = "Daily wallpaper",
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ===== WYSIWYG preview =====
            // v0.8.9: the placeholder is themed (surfaceVariant + icon) —
            // the old plain-Color.Black box read as a broken screen.
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
                val bmp = previewBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Wallpaper preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayerZoom(zoom, panX, panY),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        if (fetching) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Finding a wallpaper…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                statusLine ?: "Tap Refresh to find a wallpaper",
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

            // ===== Mascot quip / status (tap the quip 10× for the easter egg) =====
            statusLine?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } ?: Text(
                quip(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable {
                    // v1.0.0 easter egg homage: 10 taps on the mascot's line.
                    mascotTaps++
                    if (mascotTaps >= 10) {
                        mascotTaps = 0
                        statusLine = "✨ You found the easter egg — the wallpaper gods smile upon you."
                    }
                },
            )

            // ===== Refresh + apply =====
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { fetchNew() },
                    enabled = !fetching,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (fetching) "Searching…" else "Refresh")
                }
                Button(
                    onClick = { applyCurrent() },
                    enabled = !applying && previewBitmap != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (applying) "Applying…" else "Set as wallpaper")
                }
            }
            if (applying) LinearProgressIndicator(Modifier.fillMaxWidth())

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
                            styleValue < 35 -> "Small 🔹"
                            styleValue > 65 -> "Large 🔹"
                            else -> "Neutral 🔹"
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
                            onClick = { r18Mode = NyanpasuPrefs.R18_PURE; NyanpasuPrefs.writeR18(context, r18Mode) },
                            shape = SegmentedButtonDefaults.itemShape(0, 3),
                        ) { Text("Pure") }
                        SegmentedButton(
                            selected = r18Mode == NyanpasuPrefs.R18_MIX,
                            onClick = { r18Mode = NyanpasuPrefs.R18_MIX; NyanpasuPrefs.writeR18(context, r18Mode) },
                            shape = SegmentedButtonDefaults.itemShape(1, 3),
                        ) { Text("Mix") }
                        SegmentedButton(
                            selected = r18Mode == NyanpasuPrefs.R18_NSFW,
                            onClick = { r18Mode = NyanpasuPrefs.R18_NSFW; NyanpasuPrefs.writeR18(context, r18Mode) },
                            shape = SegmentedButtonDefaults.itemShape(2, 3),
                        ) { Text("NSFW") }
                    }
                }
            }

            // ===== Screens =====
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
                                "Different-color mode: the lock screen gets its own image.",
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
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
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

/** Light status flavor for the quip line. */
private val QUIPS = listOf(
    "Fresh pixels incoming.",
    "Zoom in — the good part is always in the crop.",
    "Locked and loaded. Home screen, that is.",
    "Soft tags are like seasoning — 38% of the time, perfect.",
    "One Refresh a day keeps the boring wallpaper away.",
    "Your crop was saved. You're welcome.",
    "Late night refresh? No judgment.",
    "A new wallpaper a day keeps monotony away.",
)

private fun quip(): String = QUIPS.random()
