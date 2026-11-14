package io.wallpaperengine.weutil

import java.io.Serializable

/**
 * Sparse wallpaper metadata returned by the native engine's
 * `enumerateWallpapers()` / `getWallpaperInfoSparse()`.
 *
 * Ported 1:1 from the official client. The NATIVE code constructs and reads
 * instances of this class through JNI by exact class + field names —
 * DO NOT rename the class, the fields, or change their types.
 *
 * @property title display title (from the mpkg project.json "title" key)
 * @property filePath path RELATIVE to the app's filesDir (e.g. "downloads/x.mpkg")
 * @property progress transfer progress (100 when fully imported)
 * @property type wallpaper type string: "Scene" / "Video" / "Application" / ...
 * @property lastModifiedDate file mtime (epoch millis)
 * @property isLocal true when this is a SAF-registered local image/video
 * @property isOfficial true for the bundled default wallpapers
 * @property isError true when the engine could not parse the file
 */
class WallpaperInfoSparse : Serializable {
    var title: String = ""
    var filePath: String = ""
    var progress: Int = 100
    var type: String = ""
    var lastModifiedDate: Long = 0
    var isLocal: Boolean = false
    var isOfficial: Boolean = false
    var isError: Boolean = false
}
