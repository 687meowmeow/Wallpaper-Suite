@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.wallpaperengine.weutil

import android.content.Context
import android.graphics.PointF
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Two-finger pan / pinch-zoom / rotate gesture handler for "free" wallpaper
 * alignment, ported 1:1 from the official client
 * (`io.wallpaperengine.weutil.FreeAlignTouchHandler`).
 *
 * Mutates the caller's 4x4 xForm matrix in place. `mode` is `"scene"` for
 * GL-rendered scenes (canvas-space math) or anything else for the video
 * player's clip-space math.
 */
class FreeAlignTouchHandler(private val context: Context, private val mode: String) {

    var freeXForm: FloatArray = FloatArray(16)
    var wallpaperWidth: Float = 1920f
    var wallpaperHeight: Float = 1080f
    var surfaceWidth: Float = 1920f
    var surfaceHeight: Float = 1080f
    var viewportScaleX: Float = 1f
    var viewportScaleY: Float = 1f
    @Volatile
    var scaleActive: Boolean = false
        private set

    private val panDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector
    private val mScaleGestureListener: ScaleListener

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scaleActive) {
                val adjustmentScale = adjustmentScale
                var x = -distanceX / adjustmentScale.x
                var y = distanceY / adjustmentScale.y
                val col0Len = sqrt((freeXForm[0] * freeXForm[0]) + (freeXForm[1] * freeXForm[1]))
                val col1Len = sqrt((freeXForm[4] * freeXForm[4]) + (freeXForm[5] * freeXForm[5]))
                Matrix.translateM(
                    freeXForm, 0,
                    ((x * freeXForm[0]) / col0Len) + ((y * -freeXForm[4]) / col1Len),
                    ((x * -freeXForm[1]) / col0Len) + ((y * freeXForm[5]) / col1Len),
                    0f,
                )
            }
            return true
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        var currentRotation: Float = 0f
        var lastRotation: Float = 0f
        var lastSize: Float = 1f
        var lastFocusX: Float = 0f
        var lastFocusY: Float = 0f

        fun handleRotation(event: MotionEvent) {
            if (event.pointerCount >= 2) {
                currentRotation = atan(
                    (event.getY(0) - event.getY(1)) / (event.getX(0) - event.getX(1))
                )
            }
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            val spanX = detector.currentSpanX
            val spanY = detector.currentSpanY
            lastSize = sqrt((spanX * spanX) + (spanY * spanY))
            lastRotation = currentRotation
            scaleActive = true
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            scaleActive = false
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val currentSpanX = detector.currentSpanX
            val currentSpanY = detector.currentSpanY
            val spanLen = sqrt((currentSpanX * currentSpanX) + (currentSpanY * currentSpanY))
            val focusX = detector.focusX
            val focusY = detector.focusY
            val dFocusX = focusX - lastFocusX
            val dFocusY = focusY - lastFocusY
            var scale = spanLen / lastSize
            val adjustmentScale = adjustmentScale
            val pan = PointF(dFocusX / adjustmentScale.x, dFocusY / -adjustmentScale.y)
            val col0Len = sqrt((freeXForm[0] * freeXForm[0]) + (freeXForm[1] * freeXForm[1]))
            val col1Len = sqrt((freeXForm[4] * freeXForm[4]) + (freeXForm[5] * freeXForm[5]))
            val scaledCol0 = col0Len * scale
            scale = when {
                scaledCol0 > 5.0f -> 5.0f / col0Len
                scaledCol0 < 0.05f -> 0.05f / col0Len
                else -> scale
            }
            Matrix.translateM(
                freeXForm, 0,
                ((pan.x * freeXForm[0]) / col0Len) + ((pan.y * -freeXForm[4]) / col1Len),
                ((pan.x * -freeXForm[1]) / col0Len) + ((pan.y * freeXForm[5]) / col1Len),
                0f,
            )

            val focus = PointF(focusX, focusY)
            val worldFocus = PointF()
            if (isScene()) {
                val canvasAspect = wallpaperWidth / wallpaperHeight
                val surfaceAspect = surfaceWidth / surfaceHeight
                focus.x = (focus.x / surfaceWidth) - 0.5f
                focus.y = 0.5f - (focus.y / surfaceHeight)
                worldFocus.x = wallpaperWidth * 0.5f
                worldFocus.y = wallpaperHeight * 0.5f
                if (canvasAspect >= surfaceAspect) {
                    worldFocus.x += focus.x * surfaceWidth * (wallpaperHeight / surfaceHeight)
                    worldFocus.y += focus.y * wallpaperHeight
                } else {
                    worldFocus.x += focus.x * wallpaperWidth
                    worldFocus.y += focus.y * surfaceHeight * (wallpaperWidth / surfaceWidth)
                }
            } else {
                worldFocus.x = ((focus.x / surfaceWidth) * 2.0f) - 1.0f
                worldFocus.y = 1.0f - (focus.y / (surfaceHeight * 0.5f))
            }

            val viewport = FloatArray(16)
            Matrix.setIdentityM(viewport, 0)
            Matrix.scaleM(viewport, 0, viewportScaleX, viewportScaleY, 1f)
            Matrix.invertM(viewport, 0, viewport.clone(), 0)
            val pivotMat = FloatArray(16)
            Matrix.setIdentityM(pivotMat, 0)
            setMatrixTranslation(pivotMat, worldFocus)
            Matrix.multiplyMM(pivotMat, 0, viewport, 0, pivotMat.clone(), 0)
            getMatrixTranslation(pivotMat, worldFocus)

            val prePivot = PointF(freeXForm[12], freeXForm[13])
            val rel = PointF(prePivot.x - worldFocus.x, prePivot.y - worldFocus.y)

            val rotationMat = FloatArray(16)
            Matrix.setIdentityM(rotationMat, 0)
            var deltaRotation = currentRotation - lastRotation
            if (abs(deltaRotation) > 1.5707964f) {
                deltaRotation += Math.PI.toFloat()
            }
            val degrees = deltaRotation * -28.647888f
            Matrix.rotateM(rotationMat, 0, degrees, 0f, 0f, 1f)
            setMatrixTranslation(pivotMat, rel)
            Matrix.rotateM(rotationMat, 0, degrees, 0f, 0f, 1f)
            Matrix.multiplyMM(pivotMat, 0, rotationMat, 0, pivotMat.clone(), 0)
            getMatrixTranslation(pivotMat, rel)
            lastRotation = currentRotation

            Matrix.multiplyMM(freeXForm, 0, rotationMat, 0, freeXForm.clone(), 0)
            Matrix.scaleM(freeXForm, 0, scale, scale, 1f)
            freeXForm[12] = worldFocus.x + (rel.x * scale)
            freeXForm[13] = worldFocus.y + (rel.y * scale)

            lastFocusX = focusX
            lastFocusY = focusY
            lastSize = spanLen
            return true
        }
    }

    init {
        Matrix.setIdentityM(freeXForm, 0)
        panDetector = GestureDetector(context, GestureListener())
        mScaleGestureListener = ScaleListener()
        scaleDetector = ScaleGestureDetector(context, mScaleGestureListener)
    }

    fun isScene(): Boolean = mode == "scene"

    fun configure(
        xForm: FloatArray,
        wallpaperWidth: Float,
        wallpaperHeight: Float,
        surfaceWidth: Float,
        surfaceHeight: Float,
        viewportScaleX: Float,
        viewportScaleY: Float,
    ) {
        freeXForm = xForm
        this.wallpaperWidth = wallpaperWidth
        this.wallpaperHeight = wallpaperHeight
        this.surfaceWidth = surfaceWidth
        this.surfaceHeight = surfaceHeight
        this.viewportScaleX = viewportScaleX
        this.viewportScaleY = viewportScaleY
    }

    fun onTouchEvent(event: MotionEvent) {
        panDetector.onTouchEvent(event)
        mScaleGestureListener.handleRotation(event)
        scaleDetector.onTouchEvent(event)
    }

    private val adjustmentScale: PointF
        get() {
            val point = PointF()
            if (isScene()) {
                point.x = surfaceHeight / wallpaperHeight
                point.y = surfaceHeight / wallpaperHeight
            } else {
                if (wallpaperWidth / wallpaperHeight >= surfaceWidth / surfaceHeight) {
                    point.x = surfaceWidth * 0.5f * (surfaceHeight / surfaceWidth)
                    point.y = surfaceHeight * 0.5f
                } else {
                    point.x = surfaceWidth * 0.5f
                    point.y = surfaceHeight * 0.5f * (surfaceWidth / surfaceHeight)
                }
            }
            val scale = sqrt(
                (freeXForm[0] * freeXForm[0]) + (freeXForm[1] * freeXForm[1]) + (freeXForm[2] * freeXForm[2])
            )
            point.x *= scale
            point.y *= scale
            return point
        }

    private fun setMatrixTranslation(m: FloatArray, point: PointF) {
        m[12] = point.x
        m[13] = point.y
    }

    private fun getMatrixTranslation(m: FloatArray, point: PointF) {
        point.x = m[12]
        point.y = m[13]
    }
}
