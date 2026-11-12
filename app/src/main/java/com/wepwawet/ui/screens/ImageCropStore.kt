package com.wepwawet.ui.screens

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wepwawet.log.WepWawetLogger
import java.io.File

/**
 * v0.8.16: per-image CROP overrides for folder images ("the editing should
 * have cropping like you would see in other wallpaper apps … it shouldnt
 * change the actual image file").
 *
 * The ORIGINAL image in the user's SAF folder is NEVER modified. Saving a
 * crop writes a derived JPEG under filesDir/image_crops/ and records the
 * normalized crop rect (fractions of the ORIGINAL bitmap, so re-editing
 * always starts from the untouched source and quality never degrades).
 * Everything that displays, sets or playlists the image resolves through
 * [resolve] and gets the cropped copy instead.
 */
object ImageCropStore {

    data class Saved(
        /** Absolute path of the cropped JPEG. */
        val cropFilePath: String,
        /** Crop rect as fractions (0..1) of the ORIGINAL bitmap. */
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private val gson = Gson()
    private val mapType = object : TypeToken<MutableMap<String, Saved>>() {}.type

    fun cropDir(context: Context): File =
        File(context.filesDir, "image_crops").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.getSharedPreferences("image_crop_overrides", Context.MODE_PRIVATE)

    fun read(context: Context): Map<String, Saved> {
        val raw = prefs(context).getString("overrides", null) ?: return emptyMap()
        return runCatching {
            gson.fromJson<MutableMap<String, Saved>>(raw, mapType) ?: mutableMapOf()
        }.getOrElse { emptyMap() }
    }

    fun save(context: Context, originalUri: String, saved: Saved) {
        val map = read(context).toMutableMap()
        map[originalUri] = saved
        prefs(context).edit()
            .putString("overrides", gson.toJson(map))
            .apply()
        WepWawetLogger.appInfo("ImageCropStore: crop saved",
            "uri" to originalUri.takeLast(48),
            "file" to saved.cropFilePath.substringAfterLast('/'))
    }

    fun clear(context: Context, originalUri: String): Saved? {
        val map = read(context).toMutableMap()
        val removed = map.remove(originalUri)
        prefs(context).edit()
            .putString("overrides", gson.toJson(map))
            .apply()
        removed?.let {
            runCatching { File(it.cropFilePath).delete() }
            WepWawetLogger.appInfo("ImageCropStore: crop removed",
                "uri" to originalUri.takeLast(48))
        }
        return removed
    }

    /** The path to display/set/playlist: the crop when one exists, else the original. */
    fun resolve(context: Context, originalUri: String): String =
        read(context)[originalUri]?.cropFilePath ?: originalUri
}
