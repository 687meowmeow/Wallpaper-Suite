package io.wallpaperengine.weutil

import android.graphics.Bitmap
import android.opengl.GLException
import java.nio.IntBuffer
import javax.microedition.khronos.opengles.GL10

/**
 * GL readback helper, ported 1:1 from the official client — grabs the
 * current framebuffer as an ARGB_8888 bitmap (flipped vertically, with the
 * red/blue channels swapped back) for thumbnails and WallpaperColors.
 */
object GLUtil {

    fun getBitmapFromGL(gl: GL10, w: Int, h: Int): Bitmap? {
        return try {
            val pixelCount = w * h
            val pixels = IntArray(pixelCount)
            val flipped = IntArray(pixelCount)
            val buffer = IntBuffer.wrap(pixels)
            buffer.position(0)
            gl.glReadPixels(0, 0, w, h, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, buffer)
            for (y in 0 until h) {
                val srcRow = y * w
                val dstRow = (h - y - 1) * w
                for (x in 0 until w) {
                    val p = pixels[srcRow + x]
                    flipped[dstRow + x] = (p and 0xFF00FF00.toInt()) or
                            ((p shl 16) and 0x00FF0000) or
                            ((p shr 16) and 0x000000FF)
                }
            }
            Bitmap.createBitmap(flipped, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: GLException) {
            null
        }
    }
}
