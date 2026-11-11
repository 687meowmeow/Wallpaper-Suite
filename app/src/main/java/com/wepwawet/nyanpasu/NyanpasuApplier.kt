package com.wepwawet.nyanpasu

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

/**
 * Nyanpasu clone — image processing + system wallpaper application.
 *
 * Based on Nyanpasu Wallpaper v1.0.0 by FranklinNexus (MIT License):
 *   • centerCrop to the physical screen (WYSIWYG: the same math the
 *     preview uses, so what you see is what gets applied);
 *   • WallpaperManager write retry (OEMs occasionally drop the first
 *     setBitmap call — 3 attempts with a short delay);
 *   • software-bitmap normalization for WallpaperManager;
 *   • 5-deep apply history + save-to-gallery.
 */
object NyanpasuApplier {

    private const val TAG = "NyanpasuApplier"
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
    ): Bitmap {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val bitmap = downscaleIfNeeded(source, maxDecodeDimension(context))
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
        val ok: Boolean get() = homeOk && lockOk
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

    private fun historyDir(context: Context): File =
        File(context.filesDir, "nyanpasu/history").apply { mkdirs() }

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
