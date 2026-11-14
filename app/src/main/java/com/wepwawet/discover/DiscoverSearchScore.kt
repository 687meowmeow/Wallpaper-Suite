package com.wepwawet.discover

/**
 * v0.8.25 (Nyanpasu merge): the image-variant quality scorer, extracted
 * from DiscoverSearch into a pure object — same logic, unit-testable
 * without Android.
 *
 * Ported from the newest Nyanpasu's SearchImageQuality: a quality FLOOR
 * (no gif/webm/mp4/zip; min 600px short side and 1000px long side) plus a
 * score: resolution toward an ideal ~4MP with a soft over-size penalty,
 * phone-ratio crop retention, a portrait bonus, and an original-variant
 * bonus. Null = hard reject; unknown dimensions score low but survive
 * (variety beats rejection).
 */
object DiscoverSearchScore {

    fun scoreImageVariant(
        url: String,
        width: Int,
        height: Int,
        ext: String,
        isOriginal: Boolean,
    ): Int? {
        if (url.isBlank()) return null
        val e = ext.ifBlank {
            url.substringBefore('?').substringAfterLast('.', "")
        }.lowercase()
        if (e in setOf("gif", "webm", "mp4", "zip")) return null
        val variantBonus = if (isOriginal) 6 else 4
        if (width <= 0 || height <= 0) return variantBonus - 12
        val short = minOf(width, height)
        val long = maxOf(width, height)
        if (short < 600 || long < 1_000) return null
        val mp = width.toDouble() * height.toDouble() / 1_000_000.0
        val resolution = if (mp <= 4.0) (mp / 4.0 * 40.0).toInt()
        else (40.0 - ((mp - 4.0) * 0.7).coerceAtMost(15.0)).toInt()
        val ratio = width.toDouble() / height.toDouble()
        val target = 9.0 / 19.5
        val crop = minOf(ratio / target, target / ratio).coerceIn(0.0, 1.0)
        val cropScore = (crop * 24.0).toInt()
        val portrait = if (height >= width) 6 else 0
        return resolution + cropScore + portrait + variantBonus
    }
}

/**
 * Test access to DiscoverSourceHealth's session state (the tracker itself
 * is an object with real state — tests reset it first).
 */
object DiscoverSourceHealthTestAccess {
    fun reset() {
        DiscoverSourceHealth.resetForTests()
        DiscoverDedupTestAccess.reset()
    }

    fun record(url: String, success: Boolean, elapsedMs: Long) {
        if (success) DiscoverSourceHealth.markHostOk(url, elapsedMs)
        else DiscoverSourceHealth.markHostFailed(url)
    }

    fun rank(urls: List<String>): List<String> = DiscoverSourceHealth.rank(urls)
}

/** Test access to DiscoverDedup's session lifecycle (object with state). */
object DiscoverDedupTestAccess {
    fun reset() {
        DiscoverDedup.resetForTests()
    }

    fun reserve(url: String): Boolean = DiscoverDedup.reserveForTest(url)

    fun claim(url: String): Boolean = DiscoverDedup.claim(url)

    fun claimShownFromQueue(url: String): Boolean = DiscoverDedup.claimShownFromQueue(url)

    fun markShown(url: String) = DiscoverDedup.markShownForTest(url)
}
