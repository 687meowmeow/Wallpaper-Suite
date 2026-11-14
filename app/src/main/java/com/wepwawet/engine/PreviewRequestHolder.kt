package com.wepwawet.engine

import io.wallpaperengine.weutil.WallpaperInfoSparse

/**
 * Passes the tapped library wallpaper from [com.wepwawet.ui.screens.LibraryScreen]
 * to the preview route (Compose Navigation can't serialize engine objects
 * through route args).
 */
object PreviewRequestHolder {
    @Volatile
    private var pending: WallpaperInfoSparse? = null

    fun set(wallpaper: WallpaperInfoSparse) {
        pending = wallpaper
    }

    fun peek(): WallpaperInfoSparse? = pending
}
