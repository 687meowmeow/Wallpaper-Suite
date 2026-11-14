package io.wallpaperengine.weutil

import java.io.IOException
import java.io.InputStream

/**
 * InputStream limited to `[offset, offset + maxSize)` of the original stream.
 * Ported 1:1 from the official client (used by [SupportFileLoader] for
 * reading images straight out of an .mpkg container).
 */
class RangedInputStream(
    private val original: InputStream,
    offset: Int,
    private val maxSize: Int,
) : InputStream() {

    private var bytesLeft: Int

    init {
        bytesLeft = maxSize
        var toSkip = offset.toLong()
        while (toSkip > 0) {
            val skipped = original.skip(toSkip)
            if (skipped <= 0) break
            toSkip -= skipped
        }
    }

    @Throws(IOException::class)
    override fun read(): Int {
        return if (bytesLeft <= 0) -1 else original.read().also { if (it >= 0) bytesLeft-- }
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesLeft <= 0) return -1
        val toRead = minOf(len, bytesLeft)
        val n = original.read(b, off, toRead)
        if (n > 0) bytesLeft -= n
        return n
    }

    @Throws(IOException::class)
    override fun close() {
        original.close()
    }
}
