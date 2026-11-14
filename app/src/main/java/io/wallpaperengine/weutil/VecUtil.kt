package io.wallpaperengine.weutil

import android.graphics.PointF

/**
 * Small vector helpers (port of the official client's `VecUtil`).
 */
object VecUtil {
    const val MAX_ALIGNMENT_RATIO = 0.6f
    const val MIN_ALIGNMENT_RATIO = 0.4090909f
    const val PARALLAX_MAX_DIST = 0.5f
    const val PARALLAX_MAX_DIST_SCROLL = 1.0f

    fun clampDistance(point: PointF, maxDistance: Float) {
        val length = point.length()
        if (length > maxDistance) {
            point.x /= length
            point.y /= length
            point.y *= maxDistance
            point.x *= maxDistance
        }
    }
}
