package com.wepwawet.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapDrawable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.unit.dp
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
    /** The ORIGINAL folder-image uri (what ImageCropStore keys on). */
    imageUri: String,
    displayName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- Load the working bitmap (the pristine source, capped at 4096px) ----
    val bitmap by produceState<Bitmap?>(initialValue = null, imageUri) {
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
    val metrics = context.resources.displayMetrics
    val phoneRatio = metrics.widthPixels.toFloat() / metrics.heightPixels
    val presets = remember(phoneRatio) {
        listOf(
            "Free" to null,
            "Phone" to phoneRatio,
            "1:1" to 1f,
            "4:3" to 4f / 3f,
            "3:4" to 3f / 4f,
            "16:9" to 16f / 9f,
            "9:16" to 9f / 16f,
        )
    }
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
            if (bmp == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            // ---- Crop surface: the image fit-centered at its exact
            // aspect (dispW x dispH), so fractions map linearly. The drag
            // handler lives HERE (not in the overlay) so every step reads
            // the live `rect` state — a lambda capturing a plain val would
            // keep dragging a STALE rectangle. ----
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
            ) {
                val availW = maxWidth - 16.dp
                val availH = maxHeight - 16.dp
                val scale = minOf(
                    availW.value / bmp.width,
                    availH.value / bmp.height,
                ).coerceAtLeast(0.01f)
                val dispW = (bmp.width * scale).dp
                val dispH = (bmp.height * scale).dp

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
                            var active = CropHandle.NONE
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val r = Rect(
                                        rect.left * dispW.value,
                                        rect.top * dispH.value,
                                        rect.right * dispW.value,
                                        rect.bottom * dispH.value,
                                    )
                                    active = hitTest(offset, r, cornerPx, edgePx)
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    if (active == CropHandle.NONE) return@detectDragGestures
                                    val r = Rect(
                                        rect.left * dispW.value,
                                        rect.top * dispH.value,
                                        rect.right * dispW.value,
                                        rect.bottom * dispH.value,
                                    )
                                    val px = dragRect(
                                        handle = active,
                                        drag = drag,
                                        r = r,
                                        dispW = dispW.value,
                                        dispH = dispH.value,
                                        ratio = lockRatio,
                                        minPx = minPx,
                                    )
                                    rect = Rect(
                                        (px.left / dispW.value).coerceIn(0f, 1f),
                                        (px.top / dispH.value).coerceIn(0f, 1f),
                                        (px.right / dispW.value).coerceIn(0f, 1f),
                                        (px.bottom / dispH.value).coerceIn(0f, 1f),
                                    )
                                },
                                onDragEnd = { active = CropHandle.NONE },
                                onDragCancel = { active = CropHandle.NONE },
                            )
                        },
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = displayName,
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
                        drawRect(dim, Offset.Zero, Offset(size.width, r.top))
                        drawRect(dim, Offset(0f, r.bottom), Offset(size.width, size.height))
                        drawRect(dim, Offset(0f, r.top), Offset(r.left, r.bottom))
                        drawRect(dim, Offset(r.right, r.top), Offset(size.width, r.bottom))
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

/** Apply one drag step to the crop rect (display px), respecting the lock. */
private fun dragRect(
    handle: CropHandle,
    drag: Offset,
    r: Rect,
    dispW: Float,
    dispH: Float,
    ratio: Float?,
    minPx: Float,
): Rect {
    var l = r.left; var t = r.top; var rr = r.right; var b = r.bottom
    when (handle) {
        CropHandle.MOVE -> {
            val dx = drag.x.coerceIn(-l, dispW - rr)
            val dy = drag.y.coerceIn(-t, dispH - b)
            l += dx; rr += dx; t += dy; b += dy
        }
        // Corner drags with a locked ratio: the OPPOSITE corner stays
        // anchored; the width follows the finger and the height follows
        // the ratio, clamped into the image bounds.
        CropHandle.TL -> {
            if (ratio != null) {
                var w = (rr - (l + drag.x)).coerceAtLeast(minPx)
                var h = w / ratio
                if (b - h < 0f) { h = b; w = h * ratio }
                if (rr - w < 0f) { w = rr; h = w / ratio }
                l = rr - w; t = b - h
            } else {
                l = (l + drag.x).coerceIn(0f, rr - minPx)
                t = (t + drag.y).coerceIn(0f, b - minPx)
            }
        }
        CropHandle.TR -> {
            if (ratio != null) {
                var w = ((rr + drag.x) - l).coerceAtLeast(minPx)
                var h = w / ratio
                if (b - h < 0f) { h = b; w = h * ratio }
                if (l + w > dispW) { w = dispW - l; h = w / ratio }
                rr = l + w; t = b - h
            } else {
                rr = (rr + drag.x).coerceIn(l + minPx, dispW)
                t = (t + drag.y).coerceIn(0f, b - minPx)
            }
        }
        CropHandle.BL -> {
            if (ratio != null) {
                var w = (rr - (l + drag.x)).coerceAtLeast(minPx)
                var h = w / ratio
                if (t + h > dispH) { h = dispH - t; w = h * ratio }
                if (rr - w < 0f) { w = rr; h = w / ratio }
                l = rr - w; b = t + h
            } else {
                l = (l + drag.x).coerceIn(0f, rr - minPx)
                b = (b + drag.y).coerceIn(t + minPx, dispH)
            }
        }
        CropHandle.BR -> {
            if (ratio != null) {
                var w = ((rr + drag.x) - l).coerceAtLeast(minPx)
                var h = w / ratio
                if (t + h > dispH) { h = dispH - t; w = h * ratio }
                if (l + w > dispW) { w = dispW - l; h = w / ratio }
                rr = l + w; b = t + h
            } else {
                rr = (rr + drag.x).coerceIn(l + minPx, dispW)
                b = (b + drag.y).coerceIn(t + minPx, dispH)
            }
        }
        // Edge handles are FREE-mode only (a locked ratio can't resize
        // one axis alone).
        CropHandle.L -> if (ratio == null) l = (l + drag.x).coerceIn(0f, rr - minPx)
        CropHandle.R -> if (ratio == null) rr = (rr + drag.x).coerceIn(l + minPx, dispW)
        CropHandle.T -> if (ratio == null) t = (t + drag.y).coerceIn(0f, b - minPx)
        CropHandle.B -> if (ratio == null) b = (b + drag.y).coerceIn(t + minPx, dispH)
        CropHandle.NONE -> Unit
    }
    return Rect(
        l.coerceIn(0f, dispW),
        t.coerceIn(0f, dispH),
        rr.coerceIn(0f, dispW),
        b.coerceIn(0f, dispH),
    )
}

/** Write the cropped JPEG + record it (and re-point playlists). */
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
            val outFile = File(
                ImageCropStore.cropDir(context),
                "${abs(imageUri.hashCode())}.jpg")
            java.io.FileOutputStream(outFile).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
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
                "file" to outFile.name)
        }.onFailure {
            WepWawetLogger.appWarn("ImageEditor: crop save failed",
                "exception" to it.javaClass.name,
                "message" to (it.message ?: ""))
        }
    }
}
