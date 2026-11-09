@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.wallpaperengine.weutil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream

/**
 * File-loading helper the NATIVE engine calls back into through JNI
 * (`io/wallpaperengine/weutil/SupportFileLoader`, static methods with exact
 * signatures — DO NOT rename the object, the methods, or [BitmapFileResponse]).
 *
 * Two entry points the engine actually calls (per the .so's symbol strings):
 *  - `loadBitmapFromUri(String, Context)` — decodes an image referenced by a
 *    SAF content URI (local image wallpapers / user textures)
 *  - `loadBitmapFromVFS(String, int, int)` — decodes an image at a byte range
 *    of an .mpkg container (scene textures stored inside the package)
 *
 * `loadDataFromUri` / `isUriValid` / `getUriFileExtension` are kept for
 * feature parity with the official client.
 */
object SupportFileLoader {

    /**
     * Raw decoded bitmap: dimensions plus the raw (RGB_565/ARGB_8888 —
     * whatever Android decodes) pixel buffer. Field names MUST stay
     * `w`, `h`, `data` — the native side reads them by name.
     */
    class BitmapFileResponse {
        var w: Int = 0
        var h: Int = 0
        var data: ByteArray = ByteArray(0)
    }

    @JvmStatic
    fun isUriValid(uri: String, context: Context): Boolean {
        return try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun getUriFileExtension(uri: String, context: Context): String {
        return try {
            context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    var idx = cursor.getColumnIndex("_display_name")
                    if (idx < 0) idx = 0
                    return cursor.getString(idx) ?: ""
                }
                ""
            } ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun loadBitmapFromUri(uri: String, context: Context): BitmapFileResponse? {
        return try {
            val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            input.use {
                val bitmap = BitmapFactory.decodeStream(it) ?: return null
                val response = BitmapFileResponse()
                response.w = bitmap.width
                response.h = bitmap.height
                val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(buffer)
                buffer.rewind()
                response.data = buffer.array()
                bitmap.recycle()
                response
            }
        } catch (t: Throwable) {
            null
        }
    }

    @JvmStatic
    fun loadBitmapFromVFS(vfsSource: String, offset: Int, size: Int): BitmapFileResponse? {
        return try {
            val input: InputStream = RangedInputStream(FileInputStream(vfsSource), offset, size)
            input.use {
                val bitmap = BitmapFactory.decodeStream(it) ?: return null
                val response = BitmapFileResponse()
                response.w = bitmap.width
                response.h = bitmap.height
                val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(buffer)
                buffer.rewind()
                response.data = buffer.array()
                bitmap.recycle()
                response
            }
        } catch (t: Throwable) {
            null
        }
    }

    @JvmStatic
    fun loadDataFromUri(uri: String, context: Context): ByteArray? {
        return try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }
}
