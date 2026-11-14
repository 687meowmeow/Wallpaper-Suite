package com.wepwawet.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Canvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.clipToBounds
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
            }.onFailure { WepWawetLogger.appWarn("ImageEditorScreen: ImageEditorScreen — the runCatching block failed", "exception" to it.javaClass.simpleName, "message" to (it.message ?: "")) }.getOrElse {
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
    var cropState by remember(bitmap) {
        mutableStateOf(
            savedCrop?.let {
                InteractiveCropState(
                    pendingRestore = Rect(it.left, it.top, it.right, it.bottom))
            } ?: InteractiveCropState()
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
            InteractiveCropSurface(
                bmp = bmp,
                state = cropState,
                lockRatio = lockRatio,
                onStateChange = { cropState = it },
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
                            cropState = cropState.withPreset(presets[index].second)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Drag the frame's edges and corners to resize it — drag or " +
                    "pinch the image underneath to move and zoom it." +
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
                        cropState = InteractiveCropState().withPreset(null)
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
                                    rect = cropState.srcRectNormalized(
                                        bmp.width, bmp.height),
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
 * v0.9.0 redesign: THE INTERACTIVE CROP — the crop frame can be resized by
 * dragging its EDGES and CORNERS, and the IMAGE UNDER THE FRAME can be
 * moved (one finger) and pinch-zoomed (two fingers), exactly like the
 * user asked ("I can't drag the crop borders and I can't move the image
 * under the crop frame"). The image is clamped to always COVER the frame,
 * and the result — the image region visible through the frame — is what
 * actually gets applied.
 *
 * State model ([InteractiveCropState]): the frame in VIEWPORT fractions,
 * plus the image transform (scale = image px → viewport px, offset =
 * image top-left in viewport px). The surface mutates the caller's state
 * through copies (Compose value semantics).
 */
data class InteractiveCropState(
    // The crop frame, in fractions of the viewport.
    val frameLeft: Float = 0.06f,
    val frameTop: Float = 0.06f,
    val frameRight: Float = 0.94f,
    val frameBottom: Float = 0.94f,
    // The image transform.
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Set once the first layout initialized the transform. */
    val initialized: Boolean = false,
    /** The viewport size (px) the surface last saw — srcRect needs it. */
    val viewportW: Float = 0f,
    val viewportH: Float = 0f,
    /** A preset apply request (tick changes trigger it; ratio null = Free). */
    val applyPresetTick: Int = 0,
    val presetRatio: Float? = null,
    /** A saved crop to restore (image-space fractions), applied once. */
    val pendingRestore: Rect? = null,
)

/** The frame rect in viewport px. */
private fun frameRectPx(s: InteractiveCropState, w: Float, h: Float) = Rect(
    s.frameLeft * w, s.frameTop * h, s.frameRight * w, s.frameBottom * h,
)

/** The smallest scale at which the image still covers the frame. */
private fun minScaleFor(s: InteractiveCropState, imgW: Int, imgH: Int): Float {
    val f = frameRectPx(s, s.viewportW, s.viewportH)
    if (imgW <= 0 || imgH <= 0) return 1f
    return maxOf(f.width / imgW, f.height / imgH).coerceAtLeast(0.01f)
}

/** Clamp the transform so the image covers the frame exactly (no blank). */
private fun clampImage(
    s: InteractiveCropState,
    imgW: Int,
    imgH: Int,
): InteractiveCropState {
    if (s.viewportW <= 0f || s.viewportH <= 0f) return s
    var scale = s.scale.coerceAtLeast(minScaleFor(s, imgW, imgH))
    val f = frameRectPx(s, s.viewportW, s.viewportH)
    val drawnW = imgW * scale
    val drawnH = imgH * scale
    // The image must span past the frame on both axes.
    val ox = s.offsetX.coerceIn(f.right - drawnW, f.left)
    val oy = s.offsetY.coerceIn(f.bottom - drawnH, f.top)
    return s.copy(scale = scale, offsetX = ox, offsetY = oy)
}

/** Center the image on the frame at its minimum covering scale. */
private fun fitImageToFrame(
    s: InteractiveCropState,
    imgW: Int,
    imgH: Int,
): InteractiveCropState {
    if (s.viewportW <= 0f || s.viewportH <= 0f || imgW <= 0 || imgH <= 0) return s
    val scale = minScaleFor(s, imgW, imgH)
    val f = frameRectPx(s, s.viewportW, s.viewportH)
    val cx = (f.left + f.right) / 2f
    val cy = (f.top + f.bottom) / 2f
    return s.copy(
        scale = scale,
        offsetX = cx - imgW * scale / 2f,
        offsetY = cy - imgH * scale / 2f,
    )
}

/**
 * The image region under the frame, as fractions of the bitmap (0..1) —
 * THIS is the crop that gets applied (saveCrop / Bitmap.createBitmap).
 */
fun InteractiveCropState.srcRectNormalized(imgW: Int, imgH: Int): Rect {
    if (imgW <= 0 || imgH <= 0 || scale <= 0f) return Rect(0f, 0f, 1f, 1f)
    val f = frameRectPx(this, viewportW, viewportH)
    val l = ((f.left - offsetX) / scale / imgW).coerceIn(0f, 1f)
    val t = ((f.top - offsetY) / scale / imgH).coerceIn(0f, 1f)
    val r = ((f.right - offsetX) / scale / imgW).coerceIn(0f, 1f)
    val b = ((f.bottom - offsetY) / scale / imgH).coerceIn(0f, 1f)
    return Rect(
        minOf(l, r), minOf(t, b), maxOf(l, r), maxOf(t, b),
    )
}

/** Ask the surface to apply a preset (null = Free → the full frame). */
fun InteractiveCropState.withPreset(ratio: Float?): InteractiveCropState =
    copy(applyPresetTick = applyPresetTick + 1, presetRatio = ratio)

/** The frame fractions for a preset ratio inside the CURRENT viewport. */
private fun presetFrame(
    ratio: Float?,
    viewportW: Float,
    viewportH: Float,
): Rect {
    if (ratio == null || viewportW <= 0f || viewportH <= 0f) {
        return Rect(0.02f, 0.02f, 0.98f, 0.98f)
    }
    // Pixel-space fit: frameW/frameH = ratio (square viewport px).
    var w = 0.92f
    var h = w * viewportH / (ratio * viewportW)
    if (h > 0.92f) {
        h = 0.92f
        w = h * ratio * viewportW / viewportH
    }
    val cx = 0.5f
    val cy = 0.5f
    return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

@Composable
fun InteractiveCropSurface(
    bmp: Bitmap,
    state: InteractiveCropState,
    lockRatio: Float?,
    onStateChange: (InteractiveCropState) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }

        // Live state for the gesture closures (pointerInput restarts only
        // on key changes — a plain capture would go stale mid-gesture).
        val liveState by rememberUpdatedState(state)
        val liveOnChange by rememberUpdatedState(onStateChange)

        // One-shot state work (initialize / apply a preset / re-fit after a
        // viewport change) — in a SideEffect, never mid-composition.
        var lastPresetTick by remember { mutableStateOf(0) }
        androidx.compose.runtime.SideEffect {
            if (viewportW > 0f && viewportH > 0f) {
                val s = state
                if (!s.initialized) {
                    val base = s.copy(
                        viewportW = viewportW, viewportH = viewportH,
                    )
                    onStateChange(fitImageToFrame(base, bmp.width, bmp.height)
                        .copy(initialized = true))
                } else if (s.pendingRestore != null) {
                    // Restore a saved crop: a centered frame with the
                    // saved region's aspect, scaled so the frame shows
                    // EXACTLY that region of the image.
                    val src = s.pendingRestore
                    val srcW = (src.right - src.left).coerceIn(0.01f, 1f)
                    val srcH = (src.bottom - src.top).coerceIn(0.01f, 1f)
                    val f = presetFrame(srcW / srcH, viewportW, viewportH)
                    var next = s.copy(
                        viewportW = viewportW, viewportH = viewportH,
                        frameLeft = f.left, frameTop = f.top,
                        frameRight = f.right, frameBottom = f.bottom,
                        pendingRestore = null,
                    )
                    val scale = maxOf(
                        (f.right - f.left) * viewportW / (srcW * bmp.width),
                        (f.bottom - f.top) * viewportH / (srcH * bmp.height),
                    ).coerceAtLeast(0.01f)
                    val srcCx = (src.left + src.right) / 2f * bmp.width
                    val srcCy = (src.top + src.bottom) / 2f * bmp.height
                    next = next.copy(
                        scale = scale,
                        offsetX = viewportW / 2f - srcCx * scale,
                        offsetY = viewportH / 2f - srcCy * scale,
                    )
                    onStateChange(clampImage(next, bmp.width, bmp.height))
                } else if (s.applyPresetTick != lastPresetTick) {
                    lastPresetTick = s.applyPresetTick
                    val f = presetFrame(s.presetRatio, viewportW, viewportH)
                    var next = s.copy(
                        viewportW = viewportW, viewportH = viewportH,
                        frameLeft = f.left, frameTop = f.top,
                        frameRight = f.right, frameBottom = f.bottom,
                    )
                    next = fitImageToFrame(next, bmp.width, bmp.height)
                    onStateChange(next)
                } else if (s.viewportW != viewportW || s.viewportH != viewportH) {
                    // The viewport changed (rotation): re-fit.
                    onStateChange(fitImageToFrame(
                        s.copy(viewportW = viewportW, viewportH = viewportH),
                        bmp.width, bmp.height))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .androidx.compose.ui.draw.clipToBounds()
                // ── Detector 1: the FRAME handles. A drag that starts on a
                //    corner/edge of the frame RESIZES it (consumed, so
                //    detector 2 leaves it alone). A drag anywhere else —
                //    including INSIDE the frame — consumes nothing and
                //    falls through to the image pan/pinch.
                .pointerInput(bmp, lockRatio) {
                    val minPx = 36.dp.toPx()
                    val cornerPx = 30.dp.toPx()
                    val edgePx = 24.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val st0 = liveState
                        if (st0.viewportW <= 0f || st0.viewportH <= 0f) {
                            return@awaitEachGesture
                        }
                        val frame = frameRectPx(st0, st0.viewportW, st0.viewportH)
                        val handle = hitTest(down.position, frame, cornerPx, edgePx)
                        if (handle == CropHandle.NONE || handle == CropHandle.MOVE) {
                            return@awaitEachGesture
                        }
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) break
                            if (!dragging) {
                                val moved = (change.position - down.position).getDistance()
                                if (moved < viewConfiguration.touchSlop) continue
                                dragging = true
                            }
                            // ABSOLUTE finger tracking — no accumulation drift.
                            val cur = liveState
                            val next = dragRect(
                                handle = handle,
                                fingerX = change.position.x,
                                fingerY = change.position.y,
                                prev = frameRectPx(cur, cur.viewportW, cur.viewportH),
                                dispW = cur.viewportW,
                                dispH = cur.viewportH,
                                ratio = lockRatio,
                                minPx = minPx,
                            )
                            var ns = cur.copy(
                                frameLeft = (next.left / cur.viewportW).coerceIn(0f, 1f),
                                frameTop = (next.top / cur.viewportH).coerceIn(0f, 1f),
                                frameRight = (next.right / cur.viewportW).coerceIn(0f, 1f),
                                frameBottom = (next.bottom / cur.viewportH).coerceIn(0f, 1f),
                            )
                            // A grown frame may need more zoom to stay covered.
                            ns = clampImage(ns, bmp.width, bmp.height)
                            liveOnChange(ns)
                            change.consume()
                        }
                    }
                }
                // ── Detector 2: the IMAGE — pan with one finger, pinch-zoom
                //    with two, always clamped to cover the frame.
                .pointerInput(bmp) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val st = liveState
                        if (st.viewportW <= 0f || st.viewportH <= 0f) {
                            return@detectTransformGestures
                        }
                        val minScale = minScaleFor(st, bmp.width, bmp.height)
                        val newScale = (st.scale * gestureZoom).coerceAtLeast(minScale)
                        // Keep the image point under the centroid fixed,
                        // then apply the pan.
                        val relX = centroid.x - st.offsetX
                        val relY = centroid.y - st.offsetY
                        val g = if (st.scale > 0f) newScale / st.scale else 1f
                        var ns = st.copy(
                            scale = newScale,
                            offsetX = centroid.x - relX * g + pan.x,
                            offsetY = centroid.y - relY * g + pan.y,
                        )
                        ns = clampImage(ns, bmp.width, bmp.height)
                        if (ns != st) liveOnChange(ns)
                    }
                },
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            state.offsetX.roundToInt(),
                            state.offsetY.roundToInt(),
                        )
                    }
                    .size(
                        with(density) { (bmp.width * state.scale).toDp() },
                        with(density) { (bmp.height * state.scale).toDp() },
                    ),
            )
            // Draw-only overlay: dim outside the frame + border + handles
            // + rule of thirds.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val f = frameRectPx(state, size.width, size.height)
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, f.top))
                drawRect(dim, topLeft = Offset(0f, f.bottom),
                    size = Size(size.width, size.height - f.bottom))
                drawRect(dim, topLeft = Offset(0f, f.top),
                    size = Size(f.left, f.height))
                drawRect(dim, topLeft = Offset(f.right, f.top),
                    size = Size(size.width - f.right, f.height))
                val third = Color.White.copy(alpha = 0.28f)
                for (i in 1..2) {
                    val x = f.left + (f.right - f.left) * i / 3f
                    val y = f.top + (f.bottom - f.top) * i / 3f
                    drawLine(third, Offset(x, f.top), Offset(x, f.bottom), 1.dp.toPx())
                    drawLine(third, Offset(f.left, y), Offset(f.right, y), 1.dp.toPx())
                }
                drawRect(
                    Color.White,
                    topLeft = Offset(f.left, f.top),
                    size = Size(f.width, f.height),
                    style = Stroke(width = 2.dp.toPx()),
                )
                val arm = 18.dp.toPx()
                val thick = 3.dp.toPx()
                fun mark(cx: Float, cy: Float, dx: Float, dy: Float) {
                    drawLine(Color.White, Offset(cx, cy), Offset(cx + dx * arm, cy), thick)
                    drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + dy * arm), thick)
                }
                mark(f.left, f.top, 1f, 1f)
                mark(f.right, f.top, -1f, 1f)
                mark(f.left, f.bottom, 1f, -1f)
                mark(f.right, f.bottom, -1f, -1f)
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
    var cropState by remember { mutableStateOf(InteractiveCropState()) }
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
            InteractiveCropSurface(
                bmp = bitmap,
                state = cropState,
                lockRatio = lockRatio,
                onStateChange = { cropState = it },
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
                            cropState = cropState.withPreset(presets[index].second)
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Drag the frame's edges and corners to resize it — drag or " +
                    "pinch the image underneath to move and zoom it." +
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
                        val src = cropState.srcRectNormalized(bitmap.width, bitmap.height)
                        val l = (src.left * bitmap.width).roundToInt()
                            .coerceIn(0, bitmap.width - 2)
                        val t = (src.top * bitmap.height).roundToInt()
                            .coerceIn(0, bitmap.height - 2)
                        val r = (src.right * bitmap.width).roundToInt()
                            .coerceIn(l + 1, bitmap.width)
                        val b = (src.bottom * bitmap.height).roundToInt()
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
        // Edge handles: FREE mode resizes the one axis alone. v0.8.22: a
        // LOCKED ratio now ALSO resizes via the edges ("you cant drag the
        // borders" — the locked mode used to ignore edge drags entirely):
        // the box keeps the ratio, the opposite edge stays anchored, and
        // the perpendicular axis stays centered on the current center
        // (clamped into the image).
        CropHandle.L -> if (ratio == null) {
            l = fingerX.coerceIn(0f, (rr - minPx).coerceAtLeast(0f))
        } else {
            var w = (rr - fingerX).coerceIn(minPx, rr.coerceAtLeast(minPx))
            var h = (w / ratio).coerceAtMost(dispH)
            if (h * ratio <= dispW) w = h * ratio
            val cy = (t + b) / 2f
            var nt = cy - h / 2f
            var nb = nt + h
            if (nt < 0f) { nt = 0f; nb = h }
            if (nb > dispH) { nb = dispH; nt = nb - h }
            l = rr - w; t = nt; b = nb
        }
        CropHandle.R -> if (ratio == null) {
            rr = fingerX.coerceIn((l + minPx).coerceAtMost(dispW), dispW)
        } else {
            var w = (fingerX - l).coerceIn(minPx, (dispW - l).coerceAtLeast(minPx))
            var h = (w / ratio).coerceAtMost(dispH)
            if (h * ratio <= dispW) w = h * ratio
            val cy = (t + b) / 2f
            var nt = cy - h / 2f
            var nb = nt + h
            if (nt < 0f) { nt = 0f; nb = h }
            if (nb > dispH) { nb = dispH; nt = nb - h }
            rr = l + w; t = nt; b = nb
        }
        CropHandle.T -> if (ratio == null) {
            t = fingerY.coerceIn(0f, (b - minPx).coerceAtLeast(0f))
        } else {
            var h = (b - fingerY).coerceIn(minPx, b.coerceAtLeast(minPx))
            var w = (h * ratio).coerceAtMost(dispW)
            if (w / ratio <= dispH) h = w / ratio
            val cx = (l + rr) / 2f
            var nl = cx - w / 2f
            var nr = nl + w
            if (nl < 0f) { nl = 0f; nr = w }
            if (nr > dispW) { nr = dispW; nl = nr - w }
            t = b - h; l = nl; rr = nr
        }
        CropHandle.B -> if (ratio == null) {
            b = fingerY.coerceIn((t + minPx).coerceAtMost(dispH), dispH)
        } else {
            var h = (fingerY - t).coerceIn(minPx, (dispH - t).coerceAtLeast(minPx))
            var w = (h * ratio).coerceAtMost(dispW)
            if (w / ratio <= dispH) h = w / ratio
            val cx = (l + rr) / 2f
            var nl = cx - w / 2f
            var nr = nl + w
            if (nl < 0f) { nl = 0f; nr = w }
            if (nr > dispW) { nr = dispW; nl = nr - w }
            b = t + h; l = nl; rr = nr
        }
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
