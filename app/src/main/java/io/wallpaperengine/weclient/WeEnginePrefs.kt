@file:Suppress("unused", "MemberVisibilityCanBePrivate", "SameParameterValue")

package io.wallpaperengine.weclient

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.wallpaperengine.wedata.GeneralSettingsInfo
import io.wallpaperengine.wedata.PlaylistData
import io.wallpaperengine.wedata.PlaylistDataWallpaper
import io.wallpaperengine.wedata.Property
import io.wallpaperengine.weutil.VirtualFileRange
import io.wallpaperengine.weutil.WallpaperInfoSparse
import io.wallpaperengine.wrapper.SceneLib
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Engine-side utility + persistence layer, ported from the official
 * client's `io.wallpaperengine.weutil.Util` companion object.
 *
 * IMPORTANT: every SharedPreferences KEY is kept byte-identical to the
 * official client so behavior (and a potential migration from the official
 * app's data) matches:
 *  - `selectedWallpaper` — current wallpaper (path relative to filesDir)
 *  - `playlistConfig` — Gson [PlaylistData]
 *  - `externalWallpaperFiles` — Gson list of local (SAF) wallpapers
 *  - `hiddenOfficialWallpapers` — Gson list of hidden official wallpapers
 *  - `wp_properties_<path>` — per-wallpaper stored property JSON
 *  - `general_*` — the general settings
 */
object WeEnginePrefs {

    const val PREF_KEY_SELECTED_WALLPAPER = "selectedWallpaper"
    const val PREF_KEY_HAS_SHOWN_ALIGN_OVERLAY = "hasShownAlignOverlay"
    const val PREF_KEY_HAS_SHOWN_LOCKSCREEN_WARNING = "hasShownLockscreenWarning"

    // v0.8.3: the official client's "don't ask again" key for the
    // audio-responsive permission prompt (Util.PREF_KEY_IGNORE_AUDIO_RESPONSE).
    const val PREF_KEY_IGNORE_AUDIO_RESPONSE = "ignoreAudioResponse"

    /** mpkgs imported via SAF / PC land here (relative to filesDir). */
    const val DOWNLOAD_FOLDER = "downloads/"

    // =====================================================================
    // SharedPreferences primitives
    // =====================================================================

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    fun getStoredBoolean(context: Context, key: String, defaultValue: Boolean = false): Boolean =
        prefs(context).getBoolean(key, defaultValue)

    fun storeBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).commit()
    }

    fun getStoredInt(context: Context, key: String, defaultValue: Int): Int =
        prefs(context).getInt(key, defaultValue)

    fun storeInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).commit()
    }

    fun getStoredString(context: Context, key: String): String? =
        prefs(context).getString(key, null)

    fun storeString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).commit()
    }

    // =====================================================================
    // Per-wallpaper stored properties
    // =====================================================================

    fun readWallpaperProperties(context: Context, filePath: String): String? =
        prefs(context).getString("wp_properties_$filePath", null)

    fun storeWallpaperProperties(context: Context, filePath: String, properties: String) {
        prefs(context).edit().putString("wp_properties_$filePath", properties).commit()
    }

    // =====================================================================
    // General settings
    // =====================================================================

    fun readGeneralSettings(context: Context): GeneralSettingsInfo {
        val info = GeneralSettingsInfo()
        info.maxFps = readGeneralSettingsInt(context, WallpaperProperties.GENERAL_PROP_FPS, 30)
        info.touchInputEnabled = readGeneralSettingsBoolean(context, WallpaperProperties.GENERAL_PROP_TOUCH_INPUT, true)
        info.powerSavingEnabled = readGeneralSettingsBoolean(context, WallpaperProperties.GENERAL_PROP_POWER_SAVE, true)
        info.logToFileEnabled = readGeneralSettingsBoolean(context, WallpaperProperties.GENERAL_PROP_LOG_ERRORS, false)
        info.colorsApi = readGeneralSettingsString(context, WallpaperProperties.GENERAL_PROP_COLORS_API, "enabled")
        return info
    }

    private fun readGeneralSettingsInt(context: Context, key: String, defaultValue: Int): Int {
        val p = prefs(context)
        return if (!p.contains(key)) defaultValue else p.getInt(key, defaultValue)
    }

    private fun readGeneralSettingsBoolean(context: Context, key: String, defaultValue: Boolean): Boolean {
        val p = prefs(context)
        return if (!p.contains(key)) defaultValue else p.getBoolean(key, defaultValue)
    }

    private fun readGeneralSettingsString(context: Context, key: String, defaultValue: String): String {
        val p = prefs(context)
        return if (!p.contains(key)) defaultValue else p.getString(key, defaultValue) ?: defaultValue
    }

    // =====================================================================
    // Playlist / local wallpapers / official blacklist
    // =====================================================================

    class LocalWallpaperEntry {
        var uri: String = ""
        var lastModifiedDate: Long = 0
    }

    fun storePlaylist(context: Context, data: PlaylistData) {
        prefs(context).edit().putString("playlistConfig", PlaylistData.toJson(data)).commit()
    }

    fun readPlaylist(context: Context): PlaylistData =
        PlaylistData.fromJson(prefs(context).getString("playlistConfig", "{}"))

    fun storeLocalWallpapers(context: Context, localWallpapers: ArrayList<LocalWallpaperEntry>) {
        prefs(context).edit().putString("externalWallpaperFiles", Gson().toJson(localWallpapers)).commit()
    }

    fun readLocalWallpapers(context: Context): ArrayList<LocalWallpaperEntry> {
        val raw = prefs(context).getString("externalWallpaperFiles", "[]") ?: "[]"
        val type = object : TypeToken<ArrayList<LocalWallpaperEntry>>() {}.type
        return try {
            Gson().fromJson(raw, type) ?: ArrayList()
        } catch (e: Exception) {
            ArrayList()
        }
    }

    fun storeOfficialBlacklist(context: Context, paths: ArrayList<String>) {
        prefs(context).edit().putString("hiddenOfficialWallpapers", Gson().toJson(paths)).commit()
    }

    fun readOfficialBlacklist(context: Context): ArrayList<String> {
        val raw = prefs(context).getString("hiddenOfficialWallpapers", "[]") ?: "[]"
        val type = object : TypeToken<ArrayList<String>>() {}.type
        return try {
            Gson().fromJson(raw, type) ?: ArrayList()
        } catch (e: Exception) {
            ArrayList()
        }
    }

    // =====================================================================
    // Property map helpers (exact semantics of the official Util)
    // =====================================================================

    fun updatePropertyValues(properties: HashMap<String, Property>, newProperties: HashMap<String, Property>) {
        for ((key, newProp) in newProperties) {
            val existing = properties[key] ?: continue
            if (newProp.value != null) {
                existing.value = newProp.value
                existing.valueLabel = newProp.valueLabel
            }
        }
    }

    fun extendProperties(properties: HashMap<String, Property>, newProperties: HashMap<String, Property>) {
        properties.putAll(newProperties)
    }

    fun addPropertyOrUpdateValue(properties: HashMap<String, Property>, newProperties: HashMap<String, Property>) {
        for ((key, newProp) in newProperties) {
            val existing = properties[key]
            if (existing != null) {
                existing.value = newProp.value
                existing.valueLabel = newProp.valueLabel
            } else {
                properties[key] = newProp
            }
        }
    }

    // ===== Any -> primitive converters (gson-style leniency) =====

    fun anyToFloat(v: Any?): Float = anyToFloat(v, null)

    fun anyToFloat(v: Any?, defaultValue: Float?): Float {
        return when (v) {
            is Float -> v
            is Double -> v.toFloat()
            is Int -> v.toFloat()
            is Long -> v.toFloat()
            is String -> v.toFloatOrNull() ?: (defaultValue ?: 0f)
            is Number -> v.toFloat()
            else -> defaultValue ?: 0f
        }
    }

    fun anyToInt(v: Any?): Int = anyToInt(v, null)

    fun anyToInt(v: Any?, defaultValue: Int?): Int {
        return when (v) {
            is Int -> v
            is Double -> v.toInt()
            is Float -> v.toInt()
            is Long -> v.toInt()
            is String -> v.toIntOrNull() ?: (defaultValue ?: 0)
            is Number -> v.toInt()
            else -> defaultValue ?: 0
        }
    }

    fun anyToBoolean(v: Any?, defaultValue: Boolean? = null): Boolean {
        return when (v) {
            is Boolean -> v
            is Float -> v != 0.0f
            is Double -> v != 0.0
            is Int -> v != 0
            is Long -> v != 0L
            is String -> v.toBooleanStrictOrNull() ?: (defaultValue ?: false)
            else -> defaultValue ?: false
        }
    }

    fun anyToString(v: Any?): String = v?.toString() ?: ""

    /**
     * Official color conversion (Util.normalizedVec3ToColor24): parses a
     * "r g b" normalized vector string into an ARGB int with opaque alpha.
     * If [v] is already a String it overrides [defaultValue].
     */
    fun normalizedVec3ToColor24(v: Any?, defaultValue: String): Int {
        var source = defaultValue
        if (v is String) source = v
        var j = 4278190080L // 0xFF000000
        var shift = 16
        for (part in source.split(" ")) {
            j = j or (minOf(255L, (part.toFloatOrNull() ?: 0f).let { (it * 255f).toLong() }) shl shift)
            shift -= 8
        }
        return j.toInt()
    }

    /**
     * Official color conversion (Util.color24ToNormalizedVec3): formats an
     * ARGB int back into the "r g b" normalized vector string the engine
     * and PC both use for color properties.
     */
    fun color24ToNormalizedVec3(color: Int): String {
        val sb = StringBuilder()
        var shift = 16
        while (shift >= 0) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(((color shr shift) and 255) / 255.0f)
            shift -= 8
        }
        return sb.toString()
    }

    fun anyToMatrix(v: Any?, out: FloatArray) {
        val parts = v?.toString()?.trim()?.split(Regex("\\s+")) ?: emptyList()
        for (i in 0 until minOf(16, parts.size)) {
            out[i] = parts[i].toFloatOrNull() ?: 0f
        }
    }

    fun matrixToString(v: FloatArray): String =
        v.joinToString(" ") { it.toString() }

    // =====================================================================
    // Wallpaper metadata helpers
    // =====================================================================

    fun getWallpaperType(context: Context, lib: SceneLib, wallpaper: WallpaperInfoSparse): String? {
        val type = lib.getWallpaperType(wallpaper.filePath)
        if (type != null) return type
        return try {
            val mime = context.contentResolver.getType(Uri.parse(wallpaper.filePath))?.lowercase(Locale.ROOT) ?: ""
            when {
                mime.startsWith("image") -> "Image"
                mime.startsWith("video") -> "Video"
                else -> type
            }
        } catch (e: Exception) {
            type
        }
    }

    fun getWallpaperResolution(context: Context, lib: SceneLib, wallpaper: WallpaperInfoSparse): Point? {
        val wallpaperType = lib.getWallpaperType(wallpaper.filePath)
        if (wallpaperType == "Scene") {
            return lib.getWallpaperResolution(wallpaper.filePath)
        }
        // local image wallpapers
        if (wallpaper.isLocal) {
            try {
                val uri = Uri.parse(wallpaper.filePath)
                val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT) ?: ""
                if (mime.startsWith("image")) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(input, null, opts)
                        if (opts.outWidth > 0) return Point(opts.outWidth, opts.outHeight)
                    }
                }
            } catch (e: Exception) {
            }
        }
        // video wallpapers — read through the mpkg VFS or the local URI
        try {
            val retriever = MediaMetadataRetriever()
            try {
                if (wallpaperType == "Video") {
                    val range = lib.getWallpaperFileVirtualOffset(wallpaper.filePath)
                    if (range != null) {
                        retriever.setDataSource(
                            mpkgVideoReader(
                                RandomAccessFile(File(context.filesDir, wallpaper.filePath).absolutePath, "r"),
                                range,
                            )
                        )
                    }
                } else {
                    retriever.setDataSource(context, Uri.parse(wallpaper.filePath))
                }
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) return Point(w, h)
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
        }
        return null
    }

    fun getWallpaperFPS(context: Context, lib: SceneLib, filePath: String): Int {
        val wallpaperType = lib.getWallpaperType(filePath)
        if (wallpaperType == "Scene") return 0
        try {
            val retriever = MediaMetadataRetriever()
            try {
                if (wallpaperType == "Video") {
                    val range = lib.getWallpaperFileVirtualOffset(filePath)
                    if (range != null) {
                        retriever.setDataSource(
                            mpkgVideoReader(
                                RandomAccessFile(File(context.filesDir, filePath).absolutePath, "r"),
                                range,
                            )
                        )
                    }
                } else {
                    retriever.setDataSource(context, Uri.parse(filePath))
                }
                // METADATA_KEY_VIDEO_FRAME_COUNT (32) / METADATA_KEY_DURATION (9)
                val frames = retriever.extractMetadata(32)?.toIntOrNull() ?: 0
                val durationMs = retriever.extractMetadata(9)?.toIntOrNull() ?: 0
                if (frames > 0 && durationMs > 0) {
                    return Math.rint(frames / (durationMs / 1000.0)).toInt()
                }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
        }
        return 0
    }

    /**
     * A [MediaDataSource] that exposes the `[vOffset, vOffset + vSize)` byte
     * range of an .mpkg container as a virtual standalone file.
     */
    fun mpkgVideoReader(reader: RandomAccessFile, offsets: VirtualFileRange): MediaDataSource {
        return object : MediaDataSource() {
            override fun close() {
                reader.close()
            }

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                reader.seek(position + offsets.vOffset)
                return reader.read(buffer, offset, size)
            }

            override fun getSize(): Long = offsets.vSize
        }
    }

    /** Preview thumbnail (≈384px) for the library grid. */
    fun getWallpaperPreviewBitmap(context: Context, lib: SceneLib, wallpaper: WallpaperInfoSparse): Bitmap? {
        if (wallpaper.isLocal) {
            val uri = Uri.parse(wallpaper.filePath)
            return try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.getScaledFrameAtTime(1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 384)
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        cropBitmap(BitmapFactory.decodeStream(input), 384)
                    }
                } catch (e2: Exception) {
                    null
                }
            }
        }
        val bytes = lib.getWallpaperInfoPreviewBitmap(wallpaper.filePath) ?: return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    fun cropBitmap(bitmap: Bitmap, croppedSize: Int): Bitmap? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val minSide = min(bitmap.width, bitmap.height).toDouble()
        return if (bitmap.width.toDouble() / bitmap.height == 1.0) {
            Bitmap.createScaledBitmap(bitmap, croppedSize, croppedSize, false)
        } else {
            Bitmap.createBitmap(
                bitmap,
                ((bitmap.width - minSide) / 2).toInt(),
                ((bitmap.height - minSide) / 2).toInt(),
                minSide.toInt(),
                minSide.toInt(),
            )
        }
    }

    fun createDurationSummary(minutes: Int): String =
        "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"

    // =====================================================================
    // Apply / reset wallpaper
    // =====================================================================

    /**
     * Applies [wallpaper]: stores it as the selected wallpaper and either
     * reloads the running live wallpaper service or (when the app's
     * wallpaper is not active yet) launches the system live-wallpaper
     * picker pre-selected with our component.
     *
     * @param forceOpenPicker always open the system picker (used by the
     *   official client after imports).
     * @return true when the wallpaper was hot-reloaded without the picker.
     */
    fun callApplyWallpaper(
        context: Context,
        wallpaper: WallpaperInfoSparse,
        forceOpenPicker: Boolean,
    ): Boolean {
        storeString(context, PREF_KEY_SELECTED_WALLPAPER, wallpaper.filePath)
        val service = WEWallpaperService.instance
        if (!forceOpenPicker && service != null && service.isLiveWallpaperActive) {
            service.requestWallpaperReload()
            return true
        }
        val intent = Intent("android.service.wallpaper.CHANGE_LIVE_WALLPAPER")
        intent.putExtra(
            "android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT",
            ComponentName(context, WEWallpaperService::class.java),
        )
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            false
        } catch (e: Exception) {
            false
        }
    }

    fun resetToSystemWallpaper(context: Context) {
        try {
            WallpaperManager.getInstance(context).clearWallpaper()
        } catch (e: Exception) {
        }
    }

    // =====================================================================
    // Power saving (battery optimization parity)
    // =====================================================================

    /**
     * Receiver for power-save mode changes with the official client's
     * vendor hacks: Xiaomi (`POWER_SAVE_MODE_OPEN`) and Huawei
     * (`SmartModeStatus`) expose their battery saver through Settings.System
     * instead of PowerManager.
     */
    fun makePowerSavingReceiver(callback: (Boolean) -> Unit): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    val isPowerSaveMode = when {
                        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ->
                            context?.contentResolver?.let {
                                Settings.System.getInt(it, "POWER_SAVE_MODE_OPEN") == 1
                            } ?: false
                        Build.MANUFACTURER.equals("Huawei", ignoreCase = true) ->
                            context?.contentResolver?.let {
                                Settings.System.getInt(it, "SmartModeStatus") == 4
                            } ?: false
                        else -> {
                            val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                            pm?.isPowerSaveMode ?: false
                        }
                    }
                    callback(isPowerSaveMode)
                } catch (e: Exception) {
                }
            }
        }
    }

    fun makePowerSavingIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction("android.os.action.POWER_SAVE_MODE_CHANGED")
            addAction("huawei.intent.action.POWER_MODE_CHANGED_ACTION")
            addAction("miui.intent.action.POWER_SAVE_MODE_CHANGED")
        }
    }

    // =====================================================================
    // Misc
    // =====================================================================

    fun isRotationLandscape(rotation: Int): Boolean = rotation == 1 || rotation == 3

    fun ensureDirectoriesExist(file: File) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) {
            // Try to create the full chain (mkdirs, like the official's
            // single-level mkdir but more robust on fresh installs).
            parent.mkdirs()
        }
    }

    /**
     * Alignment drag update — port of Util.updateAlignment (mode 0..4).
     */
    fun updateAlignment(
        alignmentMode: Int,
        lastAlignment: Float,
        delta: android.graphics.PointF,
        isWide: Boolean,
        canvasWidth: Float,
        canvasHeight: Float,
        screenWidth: Float,
        screenHeight: Float,
    ): Float {
        // v0.8.4: EXACT port of Util$Companion.updateAlignment from the
        // decompiled official client — the previous formula was invented and
        // made wide wallpapers impossible to move (the user-visible
        // "moving the wallpaper doesn't work" bug).
        //
        // mode 0 = "cover", mode 1 = "fill": the drag delta is converted into
        // a 0..100 alignment position through the amount of oversized canvas
        // that is actually off-screen on the dragged axis. Every other mode
        // (2/4 — free transforms are handled by FreeAlignTouchHandler) keeps
        // the position and only clamps.
        var alignment = lastAlignment
        when (alignmentMode) {
            0 -> {
                alignment += if (isWide) {
                    (delta.x / ((canvasWidth * (screenHeight / canvasHeight)) - screenWidth)) * 100f
                } else {
                    -100f * (delta.y / ((canvasHeight * (screenWidth / canvasWidth)) - screenHeight))
                }
            }
            1 -> {
                alignment += if (isWide) {
                    (delta.y / (screenHeight - (canvasHeight * (screenWidth / canvasWidth)))) * 100f
                } else {
                    -100f * (delta.x / (screenWidth - (canvasWidth * (screenHeight / canvasHeight))))
                }
            }
            else -> {
                // unchanged — free-align modes own their transform matrix
            }
        }
        return max(0f, min(100f, alignment))
    }

    class DayOfWeekInterval {
        var start: Int = 0
        var duration: Int = 0
    }

    fun computeDayOfWeekInterval(playlistPosition: Int, numberOfItems: Int): DayOfWeekInterval {
        var duration = 7
        val itemCount = max(1, min(7, numberOfItems))
        val interval = DayOfWeekInterval()
        for (i in 0 until itemCount) {
            interval.start = 7 - duration
            interval.duration = if (i < itemCount - 1) duration / (itemCount - i) else duration
            duration -= interval.duration
            if (i == playlistPosition) break
        }
        return interval
    }

    fun computeNewPlaylistItemLength(allButLastItemLength: Int, numberOfNewItems: Int): Int =
        (1440 - allButLastItemLength) / (numberOfNewItems + 1)

    @Suppress("DEPRECATION")
    fun getDisplayResolution(context: Context): Point {
        val point = Point()
        try {
            val dm = context.resources.displayMetrics
            point.x = dm.widthPixels
            point.y = dm.heightPixels
        } catch (t: Throwable) {
        }
        return point
    }
}
