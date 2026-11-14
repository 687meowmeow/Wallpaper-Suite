@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.wallpaperengine.wrapper

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Point
import android.graphics.PointF
import com.wepwawet.BuildConfig
import io.wallpaperengine.weutil.VirtualFileRange
import io.wallpaperengine.weutil.WallpaperInfoSparse

/**
 * JNI wrapper for the Wallpaper Engine native scene library (`libscenejni.so`).
 *
 * Ported 1:1 from the official Wallpaper Engine Android client
 * (`io.wallpaperengine.wrapper.SceneLib`, decompiled with jadx from the
 * freely downloadable APK distributed at wallpaperengine.io/android).
 *
 * CRITICAL CONSTRAINTS — do not change:
 *  - The class must live at exactly `io.wallpaperengine.wrapper.SceneLib`
 *    (the native library resolves `Java_io_wallpaperengine_wrapper_SceneLib_*`
 *    symbols by name).
 *  - Every `external fun` signature below must stay byte-identical to the
 *    official client's: the JNI entry points parse their arguments and
 *    construct/return `io.wallpaperengine.weutil.WallpaperInfoSparse`,
 *    `io.wallpaperengine.weutil.VirtualFileRange`, `android.graphics.Point`
 *    and `android.graphics.PointF` objects directly by class/field name.
 *  - `enumerateWallpapers()` scans `<filesDir>/wallpapers` (official
 *    wallpapers, extracted from assets on first init) and
 *    `<filesDir>/downloads` (mpkgs imported from the PC client or SAF).
 *
 * Rendering lifecycle per wallpaper engine context:
 *   [initLibrary] once per process → [initContext] → [initScene] →
 *   [resizeScene] whenever the surface changes → [updateScene] per frame →
 *   [shutdownScene` / [destroyContext] on teardown.
 */
class SceneLib {

    companion object {
        /** Process-wide "engine initialized" latch (static, like the original). */
        @JvmStatic
        var isInitialized: Boolean = false
            private set

        /**
         * The [AssetManager] shared with the native engine so it can read the
         * bundled fonts / materials / models / shaders / locale / wallpapers
         * straight out of the APK.
         */
        @JvmStatic
        var sharedAssetManager: AssetManager? = null

        init {
            System.loadLibrary("scenejni")
        }
    }

    // =====================================================================
    // Native interface — signatures MUST match the official client exactly.
    // =====================================================================

    private external fun init(internalStoragePath: String, assetTime: String, assetManager: AssetManager)

    external fun initContext(androidContext: Context): Int

    external fun destroyContext(context: Int)

    external fun initScene(context: Int, file: String)

    external fun cancelLoadingScene(context: Int)

    external fun shutdownScene(context: Int)

    external fun updateScene(context: Int)

    external fun resizeScene(context: Int, width: Int, height: Int)

    external fun applySceneProperties(context: Int, jsonProperties: String)

    external fun getSceneProperties(context: Int): String?

    external fun getSceneCanvasSize(context: Int, size: PointF): Boolean

    external fun getSceneFeatureFlags(context: Int): Int

    external fun enumerateWallpapers(): Array<WallpaperInfoSparse>

    external fun isWallpaperVersionValid(filePath: String): Boolean

    external fun getWallpaperInfoSparse(filePath: String): WallpaperInfoSparse?

    external fun getWallpaperInfoPreviewBitmap(filePath: String): ByteArray?

    external fun getWallpaperProjectString(filePath: String, key: String): String?

    external fun getWallpaperType(filePath: String): String?

    external fun getWallpaperResolution(filePath: String): Point?

    external fun getWallpaperFileVirtualOffset(filePath: String): VirtualFileRange?

    external fun setLanguage(tag: String)

    external fun getLocalization(token: String): String?

    external fun getUserLocalizations(filePath: String): String?

    external fun setLogToFileEnabled(enabled: Boolean)

    external fun sendAudioData(context: Int, fft64: FloatArray)

    external fun sendNormalizedParallaxOffset(
        context: Int,
        enabled: Boolean,
        x: Float,
        y: Float,
        maxDistance: Float,
        speedFactor: Float,
    )

    external fun sendTouchInput(context: Int, down: Boolean, x: Float, y: Float)

    external fun sendForceInput(context: Int, x: Float, y: Float)

    external fun sendGravityInput(context: Int, gravityX: Float, gravityY: Float, gravityZ: Float)

    external fun sendFreeAlignmentXForm(context: Int, xForm: FloatArray)

    // =====================================================================
    // Kotlin-side initialization (ported from initLibrary()).
    // =====================================================================

    /**
     * Initializes the engine once per process: shares the app's
     * [AssetManager], and hands the engine the internal-storage root plus an
     * asset timestamp used to invalidate extracted asset caches.
     *
     * The official client passes `BuildConfig.BUILD_TIME / 1000` (epoch
     * seconds). We generate the same thing at build time via the
     * `ENGINE_ASSET_TIME` build config field.
     */
    @Synchronized
    fun initLibrary(context: Context) {
        if (!isInitialized) {
            isInitialized = true
            val assets = context.assets
            sharedAssetManager = assets
            val assetTime = BuildConfig.ENGINE_ASSET_TIME
            val absolutePath = context.filesDir.absolutePath
            init(absolutePath, assetTime, assets)
        }
    }
}
