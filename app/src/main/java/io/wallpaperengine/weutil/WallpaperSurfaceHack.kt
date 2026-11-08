@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.wallpaperengine.weutil

import android.view.SurfaceHolder

/**
 * Static pass-through used to hand a WallpaperService.Engine's
 * [SurfaceHolder] to a [android.opengl.GLSurfaceView] that is never attached
 * to a window (port of the official client's Util.holderDelegateHack).
 *
 * The engine sets the delegate right before constructing the view; the
 * view's overridden `getHolder()` consumes and clears it.
 */
object WallpaperSurfaceHack {
    @JvmStatic
    @Volatile
    var holderDelegate: SurfaceHolder? = null
}
