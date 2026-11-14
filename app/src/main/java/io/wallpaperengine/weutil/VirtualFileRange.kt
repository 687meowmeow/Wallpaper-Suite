package io.wallpaperengine.weutil

/**
 * A byte range inside an .mpkg container, returned by the native engine's
 * `getWallpaperFileVirtualOffset()`.
 *
 * Ported 1:1 from the official client. The NATIVE code writes these fields
 * through JNI by exact name — DO NOT rename.
 *
 * Used to play videos / read images directly out of the mpkg container
 * without extracting them (MediaDataSource over a RandomAccessFile).
 */
class VirtualFileRange {
    var vOffset: Long = 0
    var vSize: Long = 0
}
