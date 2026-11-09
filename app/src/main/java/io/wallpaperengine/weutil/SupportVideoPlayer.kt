@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.wallpaperengine.weutil

import android.content.Context
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.view.Surface
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Video player the NATIVE engine instantiates and drives through JNI for
 * scene wallpapers that use video textures
 * (`io/wallpaperengine/weutil/SupportVideoPlayer`).
 *
 * The native side constructs instances with the no-arg constructor and calls
 * the methods below BY NAME via GetMethodID — DO NOT rename the class or any
 * method. Behavior ported 1:1 from the official client:
 *  - the video is played muted + looping into an external-OES SurfaceTexture
 *    bound to a GL texture the engine samples;
 *  - [startPlayback] reads the video straight out of an .mpkg container via a
 *    [MediaDataSource] over the [baseOffset]/[size] byte range, falling back
 *    to playing `file` as a plain URI (SAF content or absolute path).
 */
class SupportVideoPlayer : SurfaceTexture.OnFrameAvailableListener {

    private val video: MediaPlayer = MediaPlayer()
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    @Volatile
    private var updateSurface: Boolean = false

    @Suppress("unused")
    private val textureId: Int = 0

    @Synchronized
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        updateSurface = true
    }

    /**
     * Binds the player to GL texture [textureId] (GL_TEXTURE_EXTERNAL_OES).
     * Called from the native render thread.
     */
    fun init(textureId: Int) {
        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener(this)
        this.surfaceTexture = st
    }

    /** Latches the newest frame (updateTexImage) when one is pending. */
    fun update() {
        synchronized(this) {
            if (updateSurface) {
                surfaceTexture?.updateTexImage()
                updateSurface = false
            }
        }
    }

    fun isPlaying(): Boolean = video.isPlaying

    fun getDuration(): Int = video.duration

    fun setCurrentPosition(time: Long) {
        video.seekTo(time, MediaPlayer.SEEK_CLOSEST)
    }

    fun getCurrentPosition(): Int = video.currentPosition

    fun setPlaybackRate(rate: Float) {
        val params = PlaybackParams()
        params.speed = rate
        video.playbackParams = params
    }

    fun setLoop(shouldLoop: Boolean) {
        video.isLooping = shouldLoop
    }

    fun play() {
        try {
            video.start()
        } catch (e: Exception) {
        }
    }

    fun pause() {
        try {
            video.pause()
        } catch (e: Exception) {
        }
    }

    fun stop() {
        try {
            video.stop()
            video.reset()
        } catch (e: Exception) {
        }
    }

    fun release() {
        stop()
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        surface?.release()
        surface = null
    }

    /**
     * Starts playback of `file`. When [baseOffset]/[size] describe a byte
     * range inside an .mpkg container, the player reads through a
     * [MediaDataSource] so no extraction is needed.
     */
    fun startPlayback(androidContext: Context, file: String, baseOffset: Long, size: Long) {
        video.isLooping = true
        video.setVolume(0f, 0f)
        try {
            try {
                val randomAccessFile = RandomAccessFile(file, "r")
                val fd = randomAccessFile.fd
                if (fd.valid()) {
                    video.setDataSource(object : MediaDataSource() {
                        @Throws(IOException::class)
                        override fun close() {
                            randomAccessFile.close()
                        }

                        @Throws(IOException::class)
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size2: Int): Int {
                            randomAccessFile.seek(position + baseOffset)
                            return randomAccessFile.read(buffer, offset, size2)
                        }

                        override fun getSize(): Long {
                            return size
                        }
                    })
                    surface?.release()
                    val newSurface = Surface(surfaceTexture)
                    surface = newSurface
                    video.setSurface(newSurface)
                    video.prepare()
                    video.start()
                }
            } catch (t: Throwable) {
                video.setDataSource(androidContext, Uri.parse(file))
            }
        } catch (t: Throwable) {
        }
    }

    fun getVideoDimensions(): Point {
        val point = Point(64, 64)
        try {
            point.x = video.metrics.getInt("android.media.mediaplayer.width")
            point.y = video.metrics.getInt("android.media.mediaplayer.height")
        } catch (t: Throwable) {
        }
        return point
    }
}
