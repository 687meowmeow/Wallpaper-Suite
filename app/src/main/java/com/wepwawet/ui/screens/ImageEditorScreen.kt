package com.wepwawet.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Canvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.imageLoader
import coil.request.ImageRequest
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.playlist.SuitePlaylistStore
import com.wepwawet.ui.components.SlimTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v0.8.17: the edit request travels through this holder instead of nav
 * route arguments — the previous uri-as-path-segment scheme broke loading
 * ("the edit menu doesnt load anything"): SAF document ids already contain
 * %-escapes, and encode/decode layers mangled them. A plain holder (the
 * same pattern as PreviewRequestHolder) has no encoding at all.
 */
object ImageEditRequestHolder {
    @Volatile
    private var pending: Pair<String, String>? = null // uri to displayName

    fun set(imageUri: String, displayName: String) {
        pending = imageUri to displayName
    }

    fun peek(): Pair<String, String>? = pending
}

/**
 * v0.8.16: the image EDITOR — "replace the rename on the image menu where
 * you apply and change it to an edit. the editing should have cropping
 * like you would see in other wallpaper apps and preset aspect ratios to
 * crop to including the phones aspect ratio. it shouldnt change the actual
 * image file."
 *
 *  • Full-bleed preview with a draggable / resizable crop rectangle
 *    (corner + edge handles, drag-inside to move, rule-of-thirds grid).
 *  • Preset aspect chips: Free, Phone (this display's ratio), 1:1, 4:3,
 *    3:4, 16:9, 9:16 — a preset locks the ratio while dragging.
 *  • The ORIGINAL file in the user's folder is never touched: saving
 *    writes a derived JPEG (filesDir/image_crops) recorded in
 *    [ImageCropStore] together with the normalized crop rect, so re-edit
 *    starts over from the pristine source at full quality. Playlist
 *    entries that referenced the previous crop are rewritten.
 */
@Composable
fun ImageEditorScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val request = remember { ImageEditRequestHolder.peek() }
    val imageUri = request?.first.orEmpty()
    val displayName = request?.second.orEmpty()

    var loadFailed by remember { mutableStateOf(false) }

    // ---- Load the working bitmap (the pristine source, capped at 4096px) ----
    val bitmap by produceState<Bitmap?>(initialValue = null, imageUri) {
        if (imageUri.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val data: Any = if (imageUri.startsWith("/"))
                    File(imageUri) else android.net.Uri.parse(imageUri)
                val request = ImageRequest.Builder(context)
                    .data(data)
                    .allowHardware(false) // the crop needs pixel access
                    .size(4096)
                    .build()
                val drawable = context.imageLoader.execute(request).drawable
                    ?: return@runCatching null
                (drawable as? BitmapDrawable)?.bitmap
                    ?: run {
                        // Animated sources (GIF): crop a still of the
                        // current frame.
                        val w = drawable.intrinsicWidth.coerceAtLeast(1)
                        val h = drawable.intrinsicHeight.coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            val canvas = Canvas(it)
                            drawable.setBounds(0, 0, w, h)
                            drawable.draw(canvas)
                        }
                    }
            }.getOrElse {
                WepWawetLogger.appWarn("ImageEditor: load failed",
                    "exception" to it.javaClass.name,
                    "message" to (it.message ?: ""))
                null
            }
        }
        if (value == null) loadFailed = true
    }

    // The saved crop (if any) restores the previous rectangle — fractions
    // of the source bitmap, so they map straight onto this session.
    val savedCrop = remember(imageUri) { ImageCropStore.read(context)[imageUri] }
    var rect by remember(bitmap) {
        mutableStateOf(
            savedCrop?.let { Rect(it.left, it.top, it.right, it.bottom) }
                ?: Rect(0f, 0f, 1f, 1f)
        )
    }

    // ---- Aspect presets (incl. the PHONE's own aspect ratio) ----
    val presets = remember { cropPresets(context) }
    var activePreset by remember { mutableStateOf(0) } // Free by default
    val lockRatio = presets[activePreset].second

    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Edit — ${displayName.substringBeforeLast('.')}",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val bmp = bitmap
            when {
                bmp != null -> Unit // fall through to the crop surface
                loadFailed || imageUri.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (imageUri.isEmpty()) "Nothing to edit — open an image and choose Edit."
                            else "Couldn\'t load this image.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    return@Column
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    return@Column
                }
            }

            // ---- Crop surface (shared with BitmapCropDialog) ----
            CropSurface(
                bmp = bmp,
                rect = rect,
                lockRatio = lockRatio,
                onRectChange = { rect = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
            )


            // ---- Preset chips ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEachIndexed { index, (label, _) ->
                    FilterChip(
                        selected = activePreset == index,
                        onClick = {
                            activePreset = index
                            rect = fitPresetRect(rect, presets[index].second)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Drag the corners to resize — drag inside to move." +
                    if (lockRatio != null) " Ratio locked." else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )

            // ---- Actions ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        rect = Rect(0f, 0f, 1f, 1f)
                        activePreset = 0
                    },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }
                if (savedCrop != null) {
                    TextButton(
                        onClick = {
                            val removed = ImageCropStore.clear(context, imageUri)
                            if (removed != null) {
                                SuitePlaylistStore.renameFilepathEverywhere(
                                    context, removed.cropFilePath, imageUri)
                            }
                            onBack()
                        },
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Remove crop")
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        scope.launch {
                            try {
                                saveCrop(
                                    context = context,
                                    imageUri = imageUri,
                                    bmp = bmp,
                                    rect = rect,
                                    oldCropPath = savedCrop?.cropFilePath,
                                )
                                onBack()
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (saving) "Saving…" else "Save crop")
                }
            }
        }
    }
}

// ── Shared crop pieces ────────────────────────────────────────────────────

/** The preset aspect list — Free + this display's own ratio + classics. */
fun cropPresets(context: android.content.Context): List<Pair<String, Float?>> {
    val m = context.resources.displayMetrics
    return listOf(
        "Free" to null,
        "Phone" to (m.widthPixels.toFloat() / m.heightPixels),
        "1:1" to 1f,
        "4:3" to 4f / 3f,
        "3:4" to 3f / 4f,
        "16:9" to 16f / 9f,
        "9:16" to 9f / 16f,
    )
}

/**
 * The crop surface used by BOTH the folder-image editor and the Discover
 * "crop before setting" dialog: the image fit-centered at its exact aspect
 * (dispW x dispH) so crop fractions map linearly, with the drag handler
 * reading the LIVE rect state (a lambda capturing a plain val would keep
 * dragging a STALE rectangle) and a draw-only overlay (dim + border +
 * handles + thirds).
 */
@Composable
private fun CropSurface(
    bmp: Bitmap,
    rect: Rect,
    lockRatio: Float?,
    onRectChange: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val availW = maxWidth - 16.dp
        val availH = maxHeight - 16.dp
        val scale = minOf(
            availW.value / bmp.width,
            availH.value / bmp.height,
        ).coerceAtLeast(0.01f)
        val dispW = (bmp.width * scale).dp
        val dispH = (bmp.height * scale).dp

        // v0.8.18 CRITICAL FIX: the gesture lambdas below live inside
        // pointerInput, which only restarts when its KEYS change — a plain
        // captured `rect` parameter would stay FROZEN at the value from
        // when pointerInput launched, so every drag re-read the ORIGINAL
        // rect ("you cant move the cropping borders or where it is in the
        // image"). rememberUpdatedState gives the lambdas the LIVE value.
        val liveRect by rememberUpdatedState(rect)
        val liveOnChange by rememberUpdatedState(onRectChange)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(dispW, dispH)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF101014))
                .pointerInput(lockRatio, dispW, dispH) {
                    val minPx = 36.dp.toPx()
                    val cornerPx = 28.dp.toPx()
                    val edgePx = 22.dp.toPx()
                    // v0.8.19 (crop fix): ABSOLUTE finger tracking. The old
                    // handler fed each frame's DELTA into the corner math
                    // (`finger = corner + frameDelta`), which double-counted
                    // the axis the ratio fit snapped back on the previous
                    // frame and lost the touch-slop distance entirely —
                    // drags felt dead, corners "didn't work", and a locked
                    // ratio made it worse every frame. Now the drag tracks
                    // the finger's ABSOLUTE position: at drag start we
                    // remember which handle was grabbed and how far the
                    // finger sat from it; every frame re-derives the rect
                    // from (finger − grabOffset) directly — the same math
                    // on every frame, no accumulation drift, slop included.
                    var active = CropHandle.NONE
                    var grabDx = 0f
                    var grabDy = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            val r = androidx.compose.ui.geometry.Rect(
                                liveRect.left * dispW.value,
                                liveRect.top * dispH.value,
                                liveRect.right * dispW.value,
                                liveRect.bottom * dispH.value,
                            )
                            active = hitTest(offset, r, cornerPx, edgePx)
                            // Where the finger sits relative to the grabbed
                            // anchor point (the MOVE rect's top-left, or the
                            // grabbed corner itself).
                            when (active) {
                                CropHandle.MOVE -> {
                                    grabDx = offset.x - r.left
                                    grabDy = offset.y - r.top
                                }
                                CropHandle.TL -> { grabDx = offset.x - r.left; grabDy = offset.y - r.top }
                                CropHandle.TR -> { grabDx = offset.x - r.right; grabDy = offset.y - r.top }
                                CropHandle.BL -> { grabDx = offset.x - r.left; grabDy = offset.y - r.bottom }
                                CropHandle.BR -> { grabDx = offset.x - r.right; grabDy = offset.y - r.bottom }
                                CropHandle.L -> { grabDx = offset.x - r.left; grabDy = 0f }
                                CropHandle.R -> { grabDx = offset.x - r.right; grabDy = 0f }
                                CropHandle.T -> { grabDx = 0f; grabDy = offset.y - r.top }
                                CropHandle.B -> { grabDx = 0f; grabDy = offset.y - r.bottom }
                                CropHandle.NONE -> Unit
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (active == CropHandle.NONE) return@detectDragGestures
                            val finger = change.position
                            val prev = androidx.compose.ui.geometry.Rect(
                                liveRect.left * dispW.value,
                                liveRect.top * dispH.value,
                                liveRect.right * dispW.value,
                                liveRect.bottom * dispH.value,
                            )
                            val px = dragRect(
                                handle = active,
                                // The finger position minus the grab offset:
                                // where the grabbed anchor should now be.
                                fingerX = finger.x - grabDx,
                                fingerY = finger.y - grabDy,
                                prev = prev,
                                dispW = dispW.value,
                                dispH = dispH.value,
                                ratio = lockRatio,
                                minPx = minPx,
                            )
                            liveOnChange(
                                androidx.compose.ui.geometry.Rect(
                                    (px.left / dispW.value).coerceIn(0f, 1f),
                                    (px.top / dispH.value).coerceIn(0f, 1f),
                                    (px.right / dispW.value).coerceIn(0f, 1f),
                                    (px.bottom / dispH.value).coerceIn(0f, 1f),
                                )
                            )
                        },
                        onDragEnd = { active = CropHandle.NONE },
                        onDragCancel = { active = CropHandle.NONE },
                    )
                },
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            // Draw-only overlay: dim + border + handles + thirds.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = Rect(
                    rect.left * size.width,
                    rect.top * size.height,
                    rect.right * size.width,
                    rect.bottom * size.height,
                )
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(dim, topLeft = Offset.Zero,
                    size = Size(size.width, r.top))
                drawRect(dim, topLeft = Offset(0f, r.bottom),
                    size = Size(size.width, size.height - r.bottom))
                drawRect(dim, topLeft = Offset(0f, r.top),
                    size = Size(r.left, r.height))
                drawRect(dim, topLeft = Offset(r.right, r.top),
                    size = Size(size.width - r.right, r.height))
                val third = Color.White.copy(alpha = 0.28f)
                for (i in 1..2) {
                    val x = r.left + (r.right - r.left) * i / 3f
                    val y = r.top + (r.bottom - r.top) * i / 3f
                    drawLine(third, Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                    drawLine(third, Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
                }
                drawRect(
                    Color.White,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    style = Stroke(width = 2.dp.toPx()),
                )
                val arm = 18.dp.toPx()
                val thick = 3.dp.toPx()
                fun mark(cx: Float, cy: Float, dx: Float, dy: Float) {
                    drawLine(Color.White, Offset(cx, cy), Offset(cx + dx * arm, cy), thick)
                    drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + dy * arm), thick)
                }
                mark(r.left, r.top, 1f, 1f)
                mark(r.right, r.top, -1f, 1f)
                mark(r.left, r.bottom, 1f, -1f)
                mark(r.right, r.bottom, -1f, -1f)
            }
        }
    }
}

/**
 * v0.8.17: the Discover "crop before setting" dialog — "you should also
 * have the option to crop it from there if you push set as wallpaper".
 * An in-memory bitmap (no file round-trip), the same handles/presets as
 * the editor, and a Crop & set action that hands the CROPPED bitmap back
 * to the caller to apply.
 */
@Composable
fun BitmapCropDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onApply: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    var rect by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    val presets = remember { cropPresets(context) }
    var activePreset by remember { mutableStateOf(0) }
    val lockRatio = presets[activePreset].second

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF2101014))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Crop before setting",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            CropSurface(
                bmp = bitmap,
                rect = rect,
                lockRatio = lockRatio,
                onRectChange = { rect = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEachIndexed { index, (label, _) ->
                    FilterChip(
                        selected = activePreset == index,
                        onClick = {
                            activePreset = index
                            rect = fitPresetRect(rect, presets[index].second)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Drag the corners to resize — drag inside to move." +
                    if (lockRatio != null) " Ratio locked." else "",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val l = (rect.left * bitmap.width).roundToInt()
                            .coerceIn(0, bitmap.width - 2)
                        val t = (rect.top * bitmap.height).roundToInt()
                            .coerceIn(0, bitmap.height - 2)
                        val r = (rect.right * bitmap.width).roundToInt()
                            .coerceIn(l + 1, bitmap.width)
                        val b = (rect.bottom * bitmap.height).roundToInt()
                            .coerceIn(t + 1, bitmap.height)
                        if (r - l >= 8 && b - t >= 8) {
                            onApply(Bitmap.createBitmap(bitmap, l, t, r - l, b - t))
                        }
                    },
                    modifier = Modifier.weight(1.5f),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Crop & set")
                }
            }
        }
    }
}

// ── Geometry helpers ──────────────────────────────────────────────────────

/** The largest rect with `ratio` that fits inside the image, centered on
 *  the current crop's center (full image for ratio == null). */
private fun fitPresetRect(current: Rect, ratio: Float?): Rect {
    if (ratio == null) return Rect(0f, 0f, 1f, 1f)
    var w = 1f
    var h = w / ratio
    if (h > 1f) {
        h = 1f
        w = h * ratio
    }
    val cx = (current.left + current.right) / 2f
    val cy = (current.top + current.bottom) / 2f
    var l = cx - w / 2f
    var t = cy - h / 2f
    var r = cx + w / 2f
    var b = cy + h / 2f
    val dx = when {
        l < 0f -> -l
        r > 1f -> 1f - r
        else -> 0f
    }
    val dy = when {
        t < 0f -> -t
        b > 1f -> 1f - b
        else -> 0f
    }
    l += dx; r += dx; t += dy; b += dy
    return Rect(l, t, r, b)
}

/** Which part of the crop rect the finger grabbed. */
private enum class CropHandle { NONE, MOVE, TL, TR, BL, BR, L, R, T, B }

private fun hitTest(touch: Offset, r: Rect, corner: Float, edge: Float): CropHandle {
    val nearX = { v: Float -> abs(touch.x - v) <= corner }
    val nearY = { v: Float -> abs(touch.y - v) <= corner }
    return when {
        nearX(r.left) && nearY(r.top) -> CropHandle.TL
        nearX(r.right) && nearY(r.top) -> CropHandle.TR
        nearX(r.left) && nearY(r.bottom) -> CropHandle.BL
        nearX(r.right) && nearY(r.bottom) -> CropHandle.BR
        abs(touch.x - r.left) <= edge && touch.y > r.top && touch.y < r.bottom -> CropHandle.L
        abs(touch.x - r.right) <= edge && touch.y > r.top && touch.y < r.bottom -> CropHandle.R
        abs(touch.y - r.top) <= edge && touch.x > r.left && touch.x < r.right -> CropHandle.T
        abs(touch.y - r.bottom) <= edge && touch.x > r.left && touch.x < r.right -> CropHandle.B
        touch.x > r.left && touch.x < r.right &&
            touch.y > r.top && touch.y < r.bottom -> CropHandle.MOVE
        else -> CropHandle.NONE
    }
}

/**
 * v0.8.19 (crop fix): apply one drag step from the finger's ABSOLUTE
 * position. `fingerX`/`fingerY` are where the grabbed anchor (the corner, or
 * the rect's top-left for MOVE) should sit NOW, already adjusted by the grab
 * offset captured at drag start. The rect is re-derived from the anchors
 * every frame — no delta accumulation, no drift, and the touch-slop distance
 * is included because the offset was captured when the gesture began.
 *
 * The OPPOSITE corner stays anchored; a locked ratio clamps the box into the
 * space between the anchor and the image edges (never under [minPx] when
 * space allows).
 */
private fun dragRect(
    handle: CropHandle,
    fingerX: Float,
    fingerY: Float,
    prev: Rect,
    dispW: Float,
    dispH: Float,
    ratio: Float?,
    minPx: Float,
): Rect {
    var l = prev.left; var t = prev.top; var rr = prev.right; var b = prev.bottom
    when (handle) {
        CropHandle.MOVE -> {
            // v0.8.19: the MOVE anchor drives the top-left DIRECTLY. The
            // old delta-coerce (`dx.coerceIn(-l, dispW - rr)`) was a hard 0
            // whenever the crop equaled the full image (l=0, rr=dispW), so
            // dragging inside a fresh full-image crop did NOTHING ("the
            // image doesnt work" to move). Clamping the absolute position
            // has the same effect with no dead zone.
            val sizeW = rr - l
            val sizeH = b - t
            val newL = fingerX.coerceIn(0f, (dispW - sizeW).coerceAtLeast(0f))
            val newT = fingerY.coerceIn(0f, (dispH - sizeH).coerceAtLeast(0f))
            l = newL; t = newT; rr = newL + sizeW; b = newT + sizeH
        }
        CropHandle.TL -> if (ratio != null) {
            val box = lockedCornerRect(
                anchorX = rr, anchorY = b, signX = -1f, signY = -1f,
                fingerX = fingerX, fingerY = fingerY,
                availW = rr, availH = b, ratio = ratio, minPx = minPx)
            l = box.left; t = box.top; rr = box.right; b = box.bottom
        } else {
            l = fingerX.coerceIn(0f, (rr - minPx).coerceAtLeast(0f))
            t = fingerY.coerceIn(0f, (b - minPx).coerceAtLeast(0f))
        }
        CropHandle.TR -> if (ratio != null) {
            val box = lockedCornerRect(
                anchorX = l, anchorY = b, signX = +1f, signY = -1f,
                fingerX = fingerX, fingerY = fingerY,
                availW = dispW - l, availH = b, ratio = ratio, minPx = minPx)
            l = box.left; t = box.top; rr = box.right; b = box.bottom
        } else {
            rr = fingerX.coerceIn(l + minPx, dispW)
            t = fingerY.coerceIn(0f, (b - minPx).coerceAtLeast(0f))
        }
        CropHandle.BL -> if (ratio != null) {
            val box = lockedCornerRect(
                anchorX = rr, anchorY = t, signX = -1f, signY = +1f,
                fingerX = fingerX, fingerY = fingerY,
                availW = rr, availH = dispH - t, ratio = ratio, minPx = minPx)
            l = box.left; t = box.top; rr = box.right; b = box.bottom
        } else {
            l = fingerX.coerceIn(0f, (rr - minPx).coerceAtLeast(0f))
            b = fingerY.coerceIn(t + minPx, dispH)
        }
        CropHandle.BR -> if (ratio != null) {
            val box = lockedCornerRect(
                anchorX = l, anchorY = t, signX = +1f, signY = +1f,
                fingerX = fingerX, fingerY = fingerY,
                availW = dispW - l, availH = dispH - t, ratio = ratio, minPx = minPx)
            l = box.left; t = box.top; rr = box.right; b = box.bottom
        } else {
            rr = fingerX.coerceIn(l + minPx, dispW)
            b = fingerY.coerceIn(t + minPx, dispH)
        }
        // Edge handles are FREE-mode only (a locked ratio can't resize
        // one axis alone).
        CropHandle.L -> if (ratio == null) l = fingerX.coerceIn(0f, (rr - minPx).coerceAtLeast(0f))
        CropHandle.R -> if (ratio == null) rr = fingerX.coerceIn((l + minPx).coerceAtMost(dispW), dispW)
        CropHandle.T -> if (ratio == null) t = fingerY.coerceIn(0f, (b - minPx).coerceAtLeast(0f))
        CropHandle.B -> if (ratio == null) b = fingerY.coerceIn((t + minPx).coerceAtMost(dispH), dispH)
        CropHandle.NONE -> Unit
    }
    return Rect(
        l.coerceIn(0f, dispW),
        t.coerceIn(0f, dispH),
        rr.coerceIn(0f, dispW),
        b.coerceIn(0f, dispH),
    )
}

/**
 * v0.8.18: one ratio-locked corner step. The OPPOSITE corner stays
 * anchored; the dragged corner follows the finger, and the box takes the
 * largest ratio-correct size that fits both the finger position and the
 * available space (between the anchor and the image edges), never under
 * minPx when space allows.
 */
private fun lockedCornerRect(
    anchorX: Float,
    anchorY: Float,
    signX: Float,
    signY: Float,
    fingerX: Float,
    fingerY: Float,
    availW: Float,
    availH: Float,
    ratio: Float,
    minPx: Float,
): Rect {
    val maxW = availW.coerceAtLeast(1f)
    val maxH = availH.coerceAtLeast(1f)
    var w = (signX * (fingerX - anchorX)).coerceIn(minPx, maxW)
    var h = (signY * (fingerY - anchorY)).coerceIn(minPx, maxH)
    // Fit the ratio: the axis the finger moved LEAST constrains the box.
    if (w / h > ratio) w = h * ratio else h = w / ratio
    // Clamp the ratio-correct size back into the available space.
    if (w > maxW) { w = maxW; h = w / ratio }
    if (h > maxH) { h = maxH; w = h * ratio }
    // Never below minPx unless the space itself is smaller.
    if (w < minPx) {
        w = minPx.coerceAtMost(maxW)
        h = (w / ratio).coerceAtMost(maxH)
        w = h * ratio
    }
    if (h < minPx) {
        h = minPx.coerceAtMost(maxH)
        w = (h * ratio).coerceAtMost(maxW)
        h = w / ratio
    }
    val left = if (signX > 0) anchorX else anchorX - w
    val top = if (signY > 0) anchorY else anchorY - h
    return Rect(left, top, left + w, top + h)
}

/**
 * Write the cropped image + record it (and re-point playlists).
 *
 * v0.8.18 (review B17): the output format follows the SOURCE — a bitmap
 * with transparency (PNG/WebP sources) is saved as PNG so transparent
 * regions survive; everything else stays JPEG. The old unconditional JPEG
 * turned every transparent pixel solid black.
 */
private suspend fun saveCrop(
    context: android.content.Context,
    imageUri: String,
    bmp: Bitmap,
    rect: Rect,
    oldCropPath: String?,
) {
    withContext(Dispatchers.IO) {
        runCatching {
            val l = (rect.left * bmp.width).roundToInt().coerceIn(0, bmp.width - 2)
            val t = (rect.top * bmp.height).roundToInt().coerceIn(0, bmp.height - 2)
            val r = (rect.right * bmp.width).roundToInt().coerceIn(l + 1, bmp.width)
            val b = (rect.bottom * bmp.height).roundToInt().coerceIn(t + 1, bmp.height)
            val w = r - l
            val h = b - t
            if (w < 8 || h < 8) throw IllegalArgumentException("crop too small: ${w}x${h}")
            val cropped = Bitmap.createBitmap(bmp, l, t, w, h)
            val hasAlpha = cropped.hasAlpha()
            val outFile = File(
                ImageCropStore.cropDir(context),
                "${abs(imageUri.hashCode())}.${if (hasAlpha) "png" else "jpg"}")
            java.io.FileOutputStream(outFile).use { out ->
                cropped.compress(
                    if (hasAlpha) Bitmap.CompressFormat.PNG
                    else Bitmap.CompressFormat.JPEG,
                    92, out)
            }
            ImageCropStore.save(
                context, imageUri,
                ImageCropStore.Saved(
                    outFile.absolutePath, rect.left, rect.top, rect.right, rect.bottom))
            // Keep playlists pointing at what the user sees.
            if (oldCropPath != null && oldCropPath != outFile.absolutePath) {
                SuitePlaylistStore.renameFilepathEverywhere(
                    context, oldCropPath, outFile.absolutePath)
            }
            WepWawetLogger.appInfo("ImageEditor: crop saved",
                "out" to "${w}x${h}",
                "format" to (if (hasAlpha) "png" else "jpg"),
                "file" to outFile.name)
        }.onFailure {
            WepWawetLogger.appWarn("ImageEditor: crop save failed",
                "exception" to it.javaClass.name,
                "message" to (it.message ?: ""))
        }
    }
}
