package com.wepwawet.discover

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.provider.MediaStore
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Discover — image processing + system wallpaper application.
 *
 * Based on Nyanpasu Wallpaper v1.0.0 by FranklinNexus (MIT License):
 *   • centerCrop to the physical screen;
 *   • WallpaperManager write retry (OEMs occasionally drop the first
 *     setBitmap call — 3 attempts with a short delay);
 *   • software-bitmap normalization for WallpaperManager;
 *   • 5-deep apply history + save-to-gallery;
 *   • v1.0.0 tri-state target model (see [applyScreens]): same color on
 *     home+lock = one bitmap for both, different colors = two bitmaps.
 */
object DiscoverApplier {

    private const val TAG = "DiscoverApplier"
    private const val HISTORY_LIMIT = 5

    // ════════════════════════════════════════════════════════════════
    // Decode / crop
    // ════════════════════════════════════════════════════════════════

    /** Decode with a sampling size hint so 12 MP sources don't OOM. */
    fun decode(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** v1.0.0: decode cap ≈ 2× the screen, clamped to 2048–4096. */
    fun maxDecodeDimension(context: Context): Int {
        val dm = context.resources.displayMetrics
        val longest = maxOf(dm.widthPixels, dm.heightPixels)
        return (longest * 2).coerceIn(2048, 4096)
    }

    /**
     * Center-crop to the physical screen, like v1.0.0's ImageProcessor.
     * `zoom`/`panX`/`panY` come from the WYSIWYG preview: 1f zoom and
     * 0f/0f pan reproduce the plain center crop.
     */
    fun cropForScreen(
        context: Context,
        source: Bitmap,
        zoom: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f,
        /** v0.8.18: false = the bitmap is ALREADY user-cropped — keep its
         *  exact aspect (no center-crop), just scale to screen width. */
        matchScreenAspect: Boolean = true,
    ): Bitmap {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val bitmap = downscaleIfNeeded(source, maxDecodeDimension(context))

        if (!matchScreenAspect) {
            // Respect the user's crop: scale to screen width, keep aspect.
            val scale = screenWidth.toFloat() / bitmap.width.toFloat()
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            val tw = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val th = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val target = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(bitmap, matrix, paint)
            return target
        }

        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()

        var scale: Float
        var dx = 0f
        var dy = 0f
        if (bitmapRatio > screenRatio) {
            scale = screenHeight.toFloat() / bitmap.height.toFloat()
            dx = (screenWidth - bitmap.width * scale) * 0.5f
        } else {
            scale = screenWidth.toFloat() / bitmap.width.toFloat()
            dy = (screenHeight - bitmap.height * scale) * 0.5f
        }
        // WYSIWYG: user zoom multiplies the scale; pans shift the crop
        // window (clamped so the screen stays covered).
        scale *= zoom.coerceAtLeast(1f)
        if (zoom > 1f) {
            dx = (dx * zoom - panX).coerceIn(
                (screenWidth - bitmap.width * scale).coerceAtMost(0f), 0f)
            dy = (dy * zoom - panY).coerceIn(
                (screenHeight - bitmap.height * scale).coerceAtMost(0f), 0f)
        }

        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx + 0.5f, dy + 0.5f)
        val target = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(bitmap, matrix, paint)
        return target
    }

    private fun downscaleIfNeeded(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /**
     * v0.8.19 (preview window): crop the VISIBLE region of the pan/zoom
     * preview out of the source bitmap.
     *
     * The Discover preview is now a fixed window at the PHONE's aspect
     * ratio ("i want the image to be in a window that is the aspect ratio
     * of the phone. you should be able to manuver it in this window"). The
     * image floats inside it: `zoom` scales the image about the window
     * center, `panX`/`panY` shift it (both in WINDOW-fraction units,
     * −1..1). This maps the window's visible content straight onto the
     * physical screen.
     *
     * Rendering contract (mirrored by the preview UI):
     *  • base: the image is fit so it COVERS the window (like
     *    ContentScale.Crop at zoom 1);
     *  • zoom ≥ 1 multiplies that cover scale;
     *  • pan (in px) = panFraction × (windowSize − scaledImageSize) / 2,
     *    clamped so the window stays covered — the same clamp the
     *    preview's transformable state enforces.
     */
    fun cropVisibleWindow(
        context: Context,
        source: Bitmap,
        windowAspect: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
    ): Bitmap {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val bitmap = downscaleIfNeeded(source, maxDecodeDimension(context))

        // The crop window in BITMAP pixels: the largest windowAspect rect
        // that fits the bitmap (the "cover at zoom 1" base).
        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        var cropW: Int
        var cropH: Int
        if (bitmapRatio > windowAspect) {
            cropH = bitmap.height
            cropW = (bitmap.height * windowAspect).toInt()
        } else {
            cropW = bitmap.width
            cropH = (bitmap.width / windowAspect).toInt()
        }
        // zoom ≥ 1 shrinks the crop window about its center.
        val z = zoom.coerceAtLeast(1f)
        cropW = (cropW / z).toInt().coerceAtLeast(8)
        cropH = (cropH / z).toInt().coerceAtLeast(8)

        // Pan: fraction −1..1 of the available slack on each axis, clamped.
        val slackX = (bitmap.width - cropW).coerceAtLeast(0)
        val slackY = (bitmap.height - cropH).coerceAtLeast(0)
        val cx = (bitmap.width / 2f + panX * slackX / 2f - cropW / 2f)
            .roundToInt().coerceIn(0, bitmap.width - cropW)
        val cy = (bitmap.height / 2f + panY * slackY / 2f - cropH / 2f)
            .roundToInt().coerceIn(0, bitmap.height - cropH)

        val cropped = Bitmap.createBitmap(bitmap, cx, cy, cropW, cropH)
        // Scale the window's content to the physical screen.
        val scale = screenWidth.toFloat() / cropped.width.toFloat()
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        val tw = (cropped.width * scale).toInt().coerceAtLeast(1)
        val th = (cropped.height * scale).toInt().coerceAtLeast(1)
        val target = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(cropped, matrix, paint)
        return target
    }

    /** WallpaperManager on some OEMs only accepts software bitmaps. */
    private fun forWallpaperManager(bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return bitmap
        if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, true)
    }

    // ════════════════════════════════════════════════════════════════
    // Apply (home / lock / both)
    // ════════════════════════════════════════════════════════════════

    class ApplyResult(val homeOk: Boolean, val lockOk: Boolean) {
        /** v0.8.18: history-worthy when ANY screen write succeeded — the old
         *  strict AND meant a device whose lock write fails never recorded
         *  history even though the home wallpaper visibly changed ("the
         *  history doesnt work"). */
        val ok: Boolean get() = homeOk || lockOk
        /** Strict success: every requested screen was written. */
        val allOk: Boolean get() = homeOk && lockOk
    }

    suspend fun apply(
        context: Context,
        bitmap: Bitmap,
        homeOn: Boolean,
        lockOn: Boolean,
        lockBitmap: Bitmap? = null,
    ): ApplyResult = withContext(Dispatchers.IO) {
        var homeOk = true
        var lockOk = true
        if (homeOn) {
            homeOk = setWallpaperWithRetry(context, bitmap, WallpaperManager.FLAG_SYSTEM)
        }
        if (lockOn) {
            val lockSrc = lockBitmap ?: bitmap
            lockOk = setWallpaperWithRetry(context, lockSrc, WallpaperManager.FLAG_LOCK)
        }
        WepWawetLogger.appInfo("$TAG: apply result",
            "home_ok" to homeOk,
            "lock_ok" to lockOk,
            "separate_lock" to (lockBitmap != null))
        ApplyResult(homeOk, lockOk)
    }

    /**
     * v1.0.0 WallpaperTargetMode apply: `homeState`/`lockState` are the
     * tri-state values (0 off / 1 pink / 2 blue).
     *   • both on, same color   → ONE bitmap on both screens (sync)
     *   • both on, colors differ → [bitmap] on home, [lockBitmap] on lock
     *   • one on                → that screen only
     */
    suspend fun applyScreens(
        context: Context,
        bitmap: Bitmap,
        homeState: Int,
        lockState: Int,
        lockBitmap: Bitmap? = null,
    ): ApplyResult {
        val homeOn = DiscoverPrefs.homeRequired(homeState)
        val lockOn = DiscoverPrefs.lockRequired(lockState)
        val separate = DiscoverPrefs.isDualMode(homeState, lockState)
        return apply(context, bitmap, homeOn, lockOn, if (separate) lockBitmap else null)
    }

    private suspend fun setWallpaperWithRetry(context: Context, bitmap: Bitmap, which: Int): Boolean {
        val wm = WallpaperManager.getInstance(context)
        val soft = forWallpaperManager(bitmap)
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                // visibleCropHint=null + allowBackup=true — v1.0.0 semantics.
                wm.setBitmap(soft, null, true, which)
                if (attempt > 0) {
                    WepWawetLogger.appInfo("$TAG: wallpaper write succeeded on retry",
                        "attempt" to attempt + 1,
                        "which" to which)
                }
                return true
            } catch (e: Exception) {
                lastError = e
                delay(300)
            }
        }
        WepWawetLogger.appError("$TAG: wallpaper write FAILED",
            "which" to which,
            "exception" to (lastError?.javaClass?.name ?: "null"))
        return false
    }

    // ════════════════════════════════════════════════════════════════
    // History (5 entries) + save to gallery
    // ════════════════════════════════════════════════════════════════

    data class HistoryEntry(val file: File, val timestamp: Long)

    /**
     * v0.8.19 (rename): the history lives under filesDir/discover/history
     * now ("rename the nyanpasu file in the app" — no on-disk name carries
     * the upstream project's name anymore). One-time migration moves any
     * legacy nyanpasu/history entries into the new directory.
     */
    private fun historyDir(context: Context): File {
        val dir = File(context.filesDir, "discover/history").apply { mkdirs() }
        val legacy = File(context.filesDir, "nyanpasu/history")
        if (legacy.isDirectory) {
            legacy.listFiles()?.forEach { f -> f.renameTo(File(dir, f.name)) }
            runCatching { legacy.parentFile?.let { File(it, "nyanpasu").delete() } }
        }
        return dir
    }

    /** Save an applied bitmap into the 5-deep history ring; returns its entry. */
    suspend fun pushHistory(context: Context, bitmap: Bitmap): HistoryEntry? =
        withContext(Dispatchers.IO) {
            try {
                val dir = historyDir(context)
                val ts = System.currentTimeMillis()
                val file = File(dir, "hist_$ts.jpg")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                // Trim the ring.
                dir.listFiles { f -> f.name.startsWith("hist_") }
                    ?.sortedByDescending { it.name }
                    ?.drop(HISTORY_LIMIT)
                    ?.forEach { it.delete() }
                HistoryEntry(file, ts)
            } catch (e: Exception) {
                WepWawetLogger.appError("$TAG: history push failed",
                    "exception" to e.javaClass.name)
                null
            }
        }

    fun readHistory(context: Context): List<HistoryEntry> =
        historyDir(context).listFiles { f -> f.name.startsWith("hist_") }
            ?.map { HistoryEntry(it, it.lastModified()) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()

    /** Save a bitmap to the system gallery (MediaStore, no permission on 10+). */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,
                        "wallpaper_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WallpaperSuite")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: return@withContext false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                true
            } catch (e: Exception) {
                WepWawetLogger.appError("$TAG: gallery save failed",
                    "exception" to e.javaClass.name)
                false
            }
        }
}
