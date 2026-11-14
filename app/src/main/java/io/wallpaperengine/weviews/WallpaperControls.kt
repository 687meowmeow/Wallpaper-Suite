package io.wallpaperengine.weviews

import android.graphics.Bitmap
import android.graphics.PointF
import io.wallpaperengine.wedata.GeneralSettingsInfo
import io.wallpaperengine.wedata.Property

/**
 * The common control surface the live-wallpaper engine drives Scene and
 * Video renderers through (port of the official client's
 * `io.wallpaperengine.weviews.WallpaperControls`).
 */
interface WallpaperControls {
    fun loadWallpaper(file: String)

    fun applyWallpaperProperties(properties: String)

    fun setGeneralSettings(general: GeneralSettingsInfo)

    fun getWallpaperProperties(): HashMap<String, Property>

    fun getDefaultWallpaperProperties(): HashMap<String, Property>

    fun isAlignmentAvailable(): Boolean

    fun requestBitmap(callback: (Bitmap?) -> Unit)

    fun setScreenRotation(rotation: Int)

    fun onPauseWallpaper()

    fun onResumeWallpaper()

    fun onStopWallpaper()

    fun onDestroyWallpaper()

    fun cancelLoadingWallpaper()

    fun setLoadCallback(runnable: Runnable?)

    fun isSupportingAudioProcessing(): Boolean

    fun sendAudioData(fft64: FloatArray)

    fun sendNormalizedParallaxOffset(offset: PointF?, maxDistance: Float, speedFactor: Float)

    fun sendTouchInput(x: Float, y: Float, down: Boolean)

    fun sendGravityInput(gravityX: Float, gravityY: Float, gravityZ: Float)

    fun sendForceInput(force2D: PointF)
}
