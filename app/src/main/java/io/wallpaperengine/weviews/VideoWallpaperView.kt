@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.wallpaperengine.weviews

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import com.google.gson.Gson
import io.wallpaperengine.weclient.WallpaperProperties
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.wedata.GeneralSettingsInfo
import io.wallpaperengine.wedata.Property
import io.wallpaperengine.wedata.PropertyList
import io.wallpaperengine.weutil.FreeAlignTouchHandler
import io.wallpaperengine.weutil.GLUtil
import io.wallpaperengine.weutil.VecUtil
import io.wallpaperengine.weutil.WallpaperSurfaceHack
import io.wallpaperengine.wrapper.SceneLib
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Renders Video / Image / GIF wallpapers (including local SAF files) with
 * OpenGL ES 3, an external-OES texture fed by MediaPlayer / canvas drawing,
 * optional HSV color correction, alignment (cover/fill/free) and parallax.
 *
 * Full 1:1 port of the official client's `VideoWallpaperView`, including the
 * shaders, the frame pacing semaphore, the 5s watchdog that reloads a video
 * that stopped producing frames, and the exact scale/alignment math.
 */
class VideoWallpaperView(context: Context) :
    GLSurfaceView(context),
    GLSurfaceView.Renderer,
    SurfaceTexture.OnFrameAvailableListener,
    WallpaperControls {

    companion object {
        private const val GL_TEXTURE_EXTERNAL_OES = 36197

        private val VERTICES_DATA = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 0.0f, 1.0f, 1.0f,
        )

        private val VERTEX_SHADER = """
            #version 300 es
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            in vec4 aPosition;
            in vec2 aTextureCoord;
            out vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0, 1)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            #extension GL_OES_EGL_image_external : require
            #extension GL_OES_EGL_image_external_essl3 : enable
            precision mediump float;
            in vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            out vec4 oColor;
            void main() {
                oColor = texture(sTexture, vTextureCoord);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_CC = """
            #version 300 es
            #extension GL_OES_EGL_image_external : require
            #extension GL_OES_EGL_image_external_essl3 : enable
            precision mediump float;
            vec3 hsv2rgb(vec3 c)
            {
                vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }

            vec3 rgb2hsv(vec3 RGB)
            {
                vec4 P = (RGB.g < RGB.b) ? vec4(RGB.bg, -1.0, 2.0/3.0) : vec4(RGB.gb, 0.0, -1.0/3.0);
                vec4 Q = (RGB.r < P.x) ? vec4(P.xyw, RGB.r) : vec4(RGB.r, P.yzx);
                float C = Q.x - min(Q.w, Q.y);
                float H = abs((Q.w - Q.y) / (6.0 * C + 1e-10) + Q.z);

                vec3 HCV = vec3(H, C, Q.x);
                float S = HCV.y / (HCV.z + 1e-10);
                return vec3(HCV.x, S, HCV.z);
            }

            in vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform vec4 g_Params;
            out vec4 oColor;
            void main() {
                vec4 albedo = texture(sTexture, vTextureCoord);
                albedo.rgb = mix(vec3(0.5), albedo.rgb, g_Params.y);

                vec3 hsv = rgb2hsv(albedo.xyz);
                hsv.z *= g_Params.x;
                hsv.y *= g_Params.z;
                hsv.x += g_Params.w;
                albedo.rgb = hsv2rgb(hsv);
                oColor = albedo;
            }
        """.trimIndent()

        private val VERTEX_SHADER_100 = """
            #version 100
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0, 1)).xy;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_100 = """
            #version 100
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_CC_100 = """
            #version 100
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            vec3 hsv2rgb(vec3 c)
            {
                vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }

            vec3 rgb2hsv(vec3 RGB)
            {
                vec4 P = (RGB.g < RGB.b) ? vec4(RGB.bg, -1.0, 2.0/3.0) : vec4(RGB.gb, 0.0, -1.0/3.0);
                vec4 Q = (RGB.r < P.x) ? vec4(P.xyw, RGB.r) : vec4(RGB.r, P.yzx);
                float C = Q.x - min(Q.w, Q.y);
                float H = abs((Q.w - Q.y) / (6.0 * C + 1e-10) + Q.z);

                vec3 HCV = vec3(H, C, Q.x);
                float S = HCV.y / (HCV.z + 1e-10);
                return vec3(HCV.x, S, HCV.z);
            }

            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform vec4 g_Params;
            void main() {
                vec4 albedo = texture2D(sTexture, vTextureCoord);
                albedo.rgb = mix(vec3(0.5), albedo.rgb, g_Params.y);

                vec3 hsv = rgb2hsv(albedo.xyz);
                hsv.z *= g_Params.x;
                hsv.y *= g_Params.z;
                hsv.x += g_Params.w;
                albedo.rgb = hsv2rgb(hsv);
                gl_FragColor = albedo;
            }
        """.trimIndent()

        private fun createShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) {
                throw RuntimeException("Cannot create shader $type\n$source")
            }
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("MOVIE_SHADER", "Failed to compile shader source")
                Log.e("MOVIE_SHADER", GLES30.glGetShaderInfoLog(shader))
                GLES30.glDeleteShader(shader)
                throw RuntimeException("Could not compile shader $source\n$type")
            }
            return shader
        }

        private fun createProgram(vertexShaderSource: String, fragmentShaderSource: String): Int {
            val vertexShader = createShader(GLES30.GL_VERTEX_SHADER, vertexShaderSource)
            val fragmentShader = createShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderSource)
            val program = GLES30.glCreateProgram()
            if (program == 0) {
                throw RuntimeException("Cannot create program")
            }
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)
            val linked = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
            if (linked[0] != 1) {
                val info = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw RuntimeException("Cannot link program $program, Info: $info")
            }
            return program
        }
    }

    // ===== state (ported field-for-field) =====
    var lib: SceneLib? = null
    private var isLocalPlayer: Boolean = false
    private var customHandler: Handler? = null
    private var loadCallback: Runnable? = null
    private var bitmapCallback: ((Bitmap?) -> Unit)? = null
    private var isLoaded: Boolean = false
    private var isPaused: Boolean = false
    private var isProducingFrames: Boolean = false
    private var isSurfaceValid: Boolean = false
    private var wProperties: HashMap<String, Property>? = null
    private var wDefaultProperties: HashMap<String, Property>? = null
    private var clearColor: Int = 0
    private var alignmentPosition: Float = 0f
    private var alignmentPositionLandscape: Float = 0f
    private var alignmentMode: Int = 0
    private var rate: Float = 1.0f
    private var flipHorizontally: Boolean = false
    private var wecEnabled: Boolean = false
    private var wecBrightness: Float = 1.0f
    private var wecContrast: Float = 1.0f
    private var wecSaturation: Float = 1.0f
    private var wecHueShift: Float = 0f
    private var pixelArtOptimization: Boolean = false
    private var parallaxOffset: PointF? = null
    private var parallaxMaxDistance: Float = 0.5f
    private var parallaxSpeedFactor: Float = 1.0f
    private var parallaxOffsetSmoothed = PointF(0.0f, 0.0f)
    private val lastSentRawParallax = PointF()
    private var transformsDirty: Boolean = true
    private var shaderProgramDirty: Boolean = true
    private var lastNanoTime: Long = 0
    private var program: Int = 0
    private var programCC: Int = 0
    private var textureId: Int = 0
    private var vbo: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var stMatrixHandle: Int = 0
    private var positionHandle: Int = 0
    private var textureHandle: Int = 0
    private var ccSimpleHandle: Int = 0
    private var updateSurface: Boolean = false
    private var surfaceTexture: SurfaceTexture? = null
    private val frameSemaphore = Semaphore(1)
    private val mvMatrix = FloatArray(16)
    private val pMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val mvpMatrixPrlxCpy = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private var freeXFormPortrait = FloatArray(16)
    private var freeXFormLandscape = FloatArray(16)
    private var freeXForm = FloatArray(16)
    private val freeAlignTouchHandler = FreeAlignTouchHandler(context, "video")
    private val vertices: FloatBuffer =
        ByteBuffer.allocateDirect(VERTICES_DATA.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(VERTICES_DATA).position(0) }
    private val startTouch = PointF()
    private var pendingFile: String? = null
    private var currentFile: String? = null
    private var pendingReloadCheck: Boolean = false
    private var holderDelegate: SurfaceHolder? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private val wallpaperScale = PointF(1.0f, 1.0f)
    private val wallpaperTranslation = PointF(0.0f, 0.0f)
    private val viewportScale = PointF(1.0f, 1.0f)
    private var screenRotation: Int = 0
    private val parallaxMatrix = floatArrayOf(1.0f, 1.0f, 0.0f, 0.0f)
    private var wallpaperView: WallpaperDrawable? = null

    private val reloadWallpaper = Runnable {
        if (currentFile == null || !isSurfaceValid) return@Runnable
        pendingReloadCheck = false
        if (isPaused) return@Runnable
        loadWallpaper(currentFile!!)
    }

    init {
        Matrix.setIdentityM(freeXFormPortrait, 0)
        Matrix.setIdentityM(freeXFormLandscape, 0)
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        preserveEGLContextOnPause = true
        setOnTouchListener { _, event -> onTouchEventInternal(event) }
        setRenderer(this)
    }

    fun setLocalPlayer(z: Boolean) {
        isLocalPlayer = z
    }

    fun setCustomHandler(handler: Handler?) {
        customHandler = handler
    }

    private fun getActiveHandler(): Handler? = handler ?: customHandler

    private fun getWallpaperSpaceMatrix(m: FloatArray) {
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, wallpaperTranslation.x, wallpaperTranslation.y, 0.0f)
        Matrix.scaleM(m, 0, wallpaperScale.x, wallpaperScale.y, 1.0f)
    }

    private fun rotatePoint(m: FloatArray, point: PointF) {
        val x = point.x
        val y = point.y
        point.x = (m[0] * x) + (-m[4] * y)
        point.y = (x * -m[1]) + (y * m[5])
    }

    // =====================================================================
    // Touch (alignment drag / free transform)
    // =====================================================================

    private fun onTouchEventInternal(motionEvent: MotionEvent): Boolean {
        if (alignmentMode == WallpaperProperties.VALUE_FREE_INDEX) {
            val xForm = freeXForm.clone()
            val drawable = wallpaperView
            freeAlignTouchHandler.configure(
                xForm,
                drawable?.width()?.toFloat() ?: 1f,
                drawable?.height()?.toFloat() ?: 1f,
                surfaceWidth.toFloat(),
                surfaceHeight.toFloat(),
                viewportScale.x,
                viewportScale.y,
            )
            freeAlignTouchHandler.onTouchEvent(motionEvent)
            synchronized(this) {
                System.arraycopy(xForm, 0, freeXForm, 0, 12)
            }
            queueEvent {
                val key = if (WeEnginePrefs.isRotationLandscape(screenRotation)) {
                    WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX_LANDSCAPE
                } else {
                    WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX
                }
                wProperties?.get(key)?.value = WeEnginePrefs.matrixToString(freeXForm)
                synchronized(this) {
                    transformsDirty = true
                    if (surfaceTexture != null) {
                        wallpaperView?.invalidate()
                    }
                }
                if (isLoaded && parallaxOffset != null) {
                    parallaxOffset!!.x = lastSentRawParallax.x
                    parallaxOffset!!.y = lastSentRawParallax.y
                    updateParallaxOffsets()
                }
            }
        } else {
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> startTouch.set(motionEvent.rawX, motionEvent.rawY)
                MotionEvent.ACTION_MOVE -> {
                    val point = PointF(motionEvent.rawX, motionEvent.rawY)
                    val delta = PointF(point.x - startTouch.x, point.y - startTouch.y)
                    startTouch.set(point.x, point.y)
                    if (isLoaded && isAlignmentAvailable()) {
                        val drawable = wallpaperView ?: return true
                        val videoWidth = drawable.width().toFloat()
                        val videoHeight = drawable.height().toFloat()
                        val videoAspect = videoWidth / videoHeight
                        val viewAspect = width.toFloat() / height.toFloat()
                        if (WeEnginePrefs.isRotationLandscape(screenRotation)) {
                            alignmentPositionLandscape =
                                (WeEnginePrefs.updateAlignment(
                                    alignmentMode, (alignmentPositionLandscape * 100f) + 50f, delta,
                                    videoAspect >= viewAspect,
                                    videoWidth, videoHeight, width.toFloat(), height.toFloat(),
                                ) * 0.01f) - 0.5f
                        } else {
                            alignmentPosition =
                                (WeEnginePrefs.updateAlignment(
                                    alignmentMode, (alignmentPosition * 100f) + 50f, delta,
                                    videoAspect >= viewAspect,
                                    videoWidth, videoHeight, width.toFloat(), height.toFloat(),
                                ) * 0.01f) - 0.5f
                        }
                        scaleVideo()
                        synchronized(this) {
                            wProperties?.get(WallpaperProperties.PROP_ALIGNMENT_POSITION)?.value =
                                (alignmentPosition * 100f) + 50f
                            wProperties?.get(WallpaperProperties.PROP_ALIGNMENT_POSITION_LANDSCAPE)?.value =
                                (alignmentPositionLandscape * 100f) + 50f
                        }
                        if (isLoaded) {
                            queueEvent {
                                parallaxOffset?.let {
                                    it.x = lastSentRawParallax.x
                                    it.y = lastSentRawParallax.y
                                    updateParallaxOffsets()
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    override fun getHolder(): SurfaceHolder {
        synchronized(WallpaperSurfaceHack) {
            if (holderDelegate == null) {
                holderDelegate = WallpaperSurfaceHack.holderDelegate
                WallpaperSurfaceHack.holderDelegate = null
            }
        }
        return holderDelegate ?: super.getHolder()
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(this) {
            updateSurface = true
        }
        frameSemaphore.release()
    }

    // =====================================================================
    // GLSurfaceView.Renderer
    // =====================================================================

    override fun onDrawFrame(gl: GL10) {
        var transformsWereDirty = false
        synchronized(this) {
            if (updateSurface) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)
                updateSurface = false
                transformsWereDirty = transformsDirty
                transformsDirty = false
                GLES30.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
                if (pixelArtOptimization) {
                    GLES30.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                } else {
                    GLES30.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                }
                if (pendingReloadCheck) {
                    pendingReloadCheck = false
                    getActiveHandler()?.removeCallbacks(reloadWallpaper)
                }
            }
        }

        if (shaderProgramDirty) {
            shaderProgramDirty = false
            val activeProgram: Int
            if (wecEnabled) {
                if (programCC == 0) {
                    programCC = try {
                        createProgram(VERTEX_SHADER, FRAGMENT_SHADER_CC)
                    } catch (e: Exception) {
                        createProgram(VERTEX_SHADER_100, FRAGMENT_SHADER_CC_100)
                    }
                    ccSimpleHandle = uniform("g_Params", programCC)
                }
                activeProgram = programCC
                GLES30.glUseProgram(activeProgram)
                GLES30.glUniform4f(ccSimpleHandle, wecBrightness, wecContrast, wecSaturation, wecHueShift)
            } else {
                activeProgram = program
                GLES30.glUseProgram(activeProgram)
            }
            positionHandle = attr("aPosition", activeProgram)
            textureHandle = attr("aTextureCoord", activeProgram)
            mvpMatrixHandle = uniform("uMVPMatrix", activeProgram)
            stMatrixHandle = uniform("uSTMatrix", activeProgram)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glEnableVertexAttribArray(positionHandle)
            GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 20, 0)
            GLES30.glEnableVertexAttribArray(textureHandle)
            GLES30.glVertexAttribPointer(textureHandle, 2, GLES30.GL_FLOAT, false, 20, 12)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        if (transformsWereDirty) {
            getWallpaperSpaceMatrix(mvMatrix)
            if (alignmentMode == WallpaperProperties.VALUE_FREE_INDEX) {
                Matrix.multiplyMM(mvMatrix, 0, freeXForm, 0, mvMatrix.clone(), 0)
            }
            if (flipHorizontally) {
                Matrix.scaleM(mvMatrix, 0, -1.0f, 1.0f, 1.0f)
            }
            Matrix.setIdentityM(pMatrix, 0)
            Matrix.scaleM(pMatrix, 0, viewportScale.x, viewportScale.y, 1.0f)
            Matrix.multiplyMM(mvpMatrix, 0, pMatrix, 0, mvMatrix, 0)
            GLES30.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
            GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        }

        if (parallaxOffset != null) {
            val now = System.nanoTime()
            val deltaMs = (now - lastNanoTime) / 1_000_000.0f
            lastNanoTime = now
            val smoothing = min(0.25f, deltaMs) * parallaxSpeedFactor
            val hasMaxDistance = parallaxMaxDistance > 0.0f
            val raw = parallaxOffset!!
            var delta = PointF(raw.x, raw.y)
            delta.offset(-parallaxOffsetSmoothed.x, -parallaxOffsetSmoothed.y)
            delta.x *= smoothing
            delta.y *= smoothing
            if (flipHorizontally) {
                delta.x = -delta.x
            }
            val newSmoothed = PointF(parallaxOffsetSmoothed.x, parallaxOffsetSmoothed.y)
            newSmoothed.offset(delta.x, delta.y)
            parallaxOffsetSmoothed = newSmoothed
            if (hasMaxDistance) {
                VecUtil.clampDistance(parallaxOffsetSmoothed, parallaxMaxDistance * 0.1f)
            }
            System.arraycopy(mvpMatrix, 0, mvpMatrixPrlxCpy, 0, 14)
            if (alignmentMode == WallpaperProperties.VALUE_FREE_INDEX) {
                val rotated = PointF(parallaxOffsetSmoothed.x, parallaxOffsetSmoothed.y)
                if (flipHorizontally) {
                    rotated.x = -rotated.x
                    rotatePoint(freeXForm, rotated)
                    rotated.x = -rotated.x
                } else {
                    rotatePoint(freeXForm, rotated)
                }
                Matrix.translateM(
                    mvpMatrixPrlxCpy, 0, rotated.x,
                    (rotated.y * surfaceHeight) / surfaceWidth, 0.0f,
                )
            } else {
                Matrix.translateM(
                    mvpMatrixPrlxCpy, 0, parallaxOffsetSmoothed.x,
                    (parallaxOffsetSmoothed.y * surfaceHeight) / surfaceWidth, 0.0f,
                )
            }
            if (hasMaxDistance && alignmentMode != WallpaperProperties.VALUE_FREE_INDEX) {
                Matrix.scaleM(mvpMatrixPrlxCpy, 0, 1.1f, 1.1f, 1.0f)
            }
            GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrixPrlxCpy, 0)
        }

        if (alignmentMode == WallpaperProperties.VALUE_FILL_INDEX ||
            alignmentMode == WallpaperProperties.VALUE_FREE_INDEX
        ) {
            GLES30.glClearColor(
                ((clearColor shr 16) and 255) / 255.0f,
                ((clearColor shr 8) and 255) / 255.0f,
                (clearColor and 255) / 255.0f,
                1.0f,
            )
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glFinish()

        bitmapCallback?.let { callback ->
            if (surfaceWidth != 0 && surfaceHeight != 0) {
                callback(GLUtil.getBitmapFromGL(gl, surfaceWidth, surfaceHeight))
                bitmapCallback = null
            }
        }

        var waitForFrame = false
        synchronized(this) {
            if (parallaxOffset == null && isProducingFrames) {
                waitForFrame = true
            }
        }
        if (waitForFrame) {
            try {
                frameSemaphore.tryAcquire(50, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        synchronized(this) {
            isSurfaceValid = true
            pendingFile?.let { loadWallpaper(it) }
        }
        if (isLoaded) {
            scaleVideo()
        }
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        program = try {
            createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (e: Exception) {
            createProgram(VERTEX_SHADER_100, FRAGMENT_SHADER_100)
        }
        positionHandle = attr("aPosition", program)
        textureHandle = attr("aTextureCoord", program)
        mvpMatrixHandle = uniform("uMVPMatrix", program)
        stMatrixHandle = uniform("uSTMatrix", program)
        createTexture()
        GLES30.glUseProgram(program)
        synchronized(this) {
            isSurfaceValid = true
            pendingFile?.let { loadWallpaper(it) }
        }
    }

    @Synchronized
    private fun createTexture() {
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, VERTICES_DATA.size * 4, vertices, GLES30.GL_STATIC_DRAW)
        val vertexArrays = IntArray(1)
        GLES30.glGenVertexArrays(1, vertexArrays, 0)
        GLES30.glBindVertexArray(vertexArrays[0])
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(textureHandle)
        GLES30.glVertexAttribPointer(textureHandle, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun attr(name: String, program: Int): Int {
        val location = GLES30.glGetAttribLocation(program, name)
        if (location != -1) return location
        throw RuntimeException("Error Attribute: $name not found!")
    }

    private fun uniform(name: String, program: Int): Int {
        val location = GLES30.glGetUniformLocation(program, name)
        if (location != -1) return location
        throw RuntimeException("Error Uniform: $name not found!")
    }

    // =====================================================================
    // WallpaperControls
    // =====================================================================

    override fun cancelLoadingWallpaper() {
    }

    override fun isSupportingAudioProcessing(): Boolean = false

    override fun sendAudioData(fft64: FloatArray) {
    }

    override fun sendForceInput(force2D: PointF) {
    }

    override fun sendGravityInput(gravityX: Float, gravityY: Float, gravityZ: Float) {
    }

    override fun sendTouchInput(x: Float, y: Float, down: Boolean) {
    }

    override fun setGeneralSettings(general: GeneralSettingsInfo) {
    }

    @Synchronized
    override fun loadWallpaper(file: String) {
        isProducingFrames = false
        if (!isSurfaceValid) {
            pendingFile = file
            return
        }
        if (pendingReloadCheck) {
            getActiveHandler()?.removeCallbacks(reloadWallpaper)
        }
        if (isLoaded) {
            wallpaperView?.stop()
            wallpaperView?.destroy()
        }
        pendingFile = null
        isLoaded = false
        currentFile = file

        val type = try {
            context.contentResolver.getType(Uri.parse(file))?.lowercase(Locale.ROOT) ?: ""
        } catch (e: Exception) {
            ""
        }
        val isGif = type.startsWith("image/gif")
        wallpaperView = when {
            isGif -> WallpaperGifDrawable()
            type.startsWith("image/") -> WallpaperImageDrawable()
            else -> VideoDrawable()
        }
        wallpaperView?.load(file)

        val defaults = Property.fromJson(PropertyList.defaultVideoProperties)
        if (isGif) {
            WeEnginePrefs.extendProperties(defaults, Property.fromJson(PropertyList.defaultGIFProperties))
        }
        wDefaultProperties = defaults

        synchronized(this) {
            val working = HashMap<String, Property>()
            for ((key, value) in defaults) {
                working[key] = Property(value)
            }
            wProperties = working
            val stored = WeEnginePrefs.readWallpaperProperties(context, file)
            if (stored != null) {
                WeEnginePrefs.updatePropertyValues(working, Property.fromJson(stored))
            }
            applyPropertiesInternal(working)
        }

        if (isPaused) {
            wallpaperView?.pause()
        } else {
            wallpaperView?.play()
        }
        synchronized(this) {
            isLoaded = true
            isProducingFrames = true
        }
        getActiveHandler()?.post {
            loadCallback?.run()
        }
        scaleVideo()
    }

    @Synchronized
    private fun applyPropertiesInternal(properties: HashMap<String, Property>) {
        var needsParallaxReset = false
        if (properties.containsKey(WallpaperProperties.PROP_RATE)) {
            rate = WeEnginePrefs.anyToFloat(properties[WallpaperProperties.PROP_RATE]?.value) * 0.01f
        }
        if (properties.containsKey(WallpaperProperties.PROP_SCHEME_COLOR)) {
            clearColor = normalizedVec3ToColor24(properties[WallpaperProperties.PROP_SCHEME_COLOR]?.value, "0 0 0")
        }
        if (properties.containsKey(WallpaperProperties.PROP_ALIGNMENT_POSITION)) {
            alignmentPosition = (WeEnginePrefs.anyToFloat(
                properties[WallpaperProperties.PROP_ALIGNMENT_POSITION]?.value
            ) * 0.01f) - 0.5f
            transformsDirty = true
            needsParallaxReset = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_ALIGNMENT_POSITION_LANDSCAPE)) {
            alignmentPositionLandscape = (WeEnginePrefs.anyToFloat(
                properties[WallpaperProperties.PROP_ALIGNMENT_POSITION_LANDSCAPE]?.value
            ) * 0.01f) - 0.5f
            transformsDirty = true
            needsParallaxReset = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX)) {
            WeEnginePrefs.anyToMatrix(
                properties[WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX]?.value, freeXFormPortrait
            )
            transformsDirty = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX_LANDSCAPE)) {
            WeEnginePrefs.anyToMatrix(
                properties[WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX_LANDSCAPE]?.value,
                freeXFormLandscape,
            )
            transformsDirty = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_ALIGNMENT)) {
            alignmentMode = WeEnginePrefs.anyToInt(properties[WallpaperProperties.PROP_ALIGNMENT]?.value)
            scaleVideo()
            needsParallaxReset = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_ALIGNMENT_FLIP_H)) {
            flipHorizontally = WeEnginePrefs.anyToBoolean(
                properties[WallpaperProperties.PROP_ALIGNMENT_FLIP_H]?.value, false
            )
            transformsDirty = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_WEC_ENABLED)) {
            wecEnabled = WeEnginePrefs.anyToBoolean(properties[WallpaperProperties.PROP_WEC_ENABLED]?.value, false)
            shaderProgramDirty = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_WEC_BRIGHTNESS)) {
            val v = WeEnginePrefs.anyToFloat(properties[WallpaperProperties.PROP_WEC_BRIGHTNESS]?.value, 50f) / 50f
            wecBrightness = v.pow(2.0f)
            transformsDirty = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_WEC_CONTRAST)) {
            val v = WeEnginePrefs.anyToFloat(properties[WallpaperProperties.PROP_WEC_CONTRAST]?.value, 50f) / 50f
            wecContrast = v.pow(0.5f)
            transformsDirty = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_WEC_SATURATION)) {
            val v = WeEnginePrefs.anyToFloat(properties[WallpaperProperties.PROP_WEC_SATURATION]?.value, 50f) / 50f
            wecSaturation = v.pow(0.5f)
            transformsDirty = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_WEC_HUE_SHIFT)) {
            wecHueShift = (WeEnginePrefs.anyToFloat(
                properties[WallpaperProperties.PROP_WEC_HUE_SHIFT]?.value, 50f
            ) / 100f) - 0.5f
            transformsDirty = true
        }
        if (properties.containsKey(WallpaperProperties.PROP_PIXEL_ART_OPTIMIZATION)) {
            pixelArtOptimization = WeEnginePrefs.anyToBoolean(
                properties[WallpaperProperties.PROP_PIXEL_ART_OPTIMIZATION]?.value, true
            )
            transformsDirty = true
        }
        wallpaperView?.applyProperties(properties)
        if (needsParallaxReset) {
            queueEvent {
                parallaxOffset?.let {
                    it.x = lastSentRawParallax.x
                    it.y = lastSentRawParallax.y
                    updateParallaxOffsets()
                }
            }
        }
    }

    private fun normalizedVec3ToColor24(v: Any?, default: String): Int {
        val parts = (v?.toString() ?: default).trim().split(Regex("\\s+"))
        if (parts.size < 3) return 0
        fun ch(i: Int): Int {
            val f = parts[i].toFloatOrNull() ?: return 0
            return ((f.coerceIn(0f, 1f)) * 255f).toInt().coerceIn(0, 255)
        }
        return (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }

    private fun scaleVideo() {
        val drawable: WallpaperDrawable?
        synchronized(this) {
            drawable = wallpaperView
        }
        val videoWidth = drawable?.width() ?: 0
        val videoHeight = drawable?.height() ?: 0
        if (videoWidth == 0 || surfaceWidth == 0) return

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val alignment = if (WeEnginePrefs.isRotationLandscape(screenRotation)) {
            alignmentPositionLandscape
        } else {
            alignmentPosition
        }
        val horizontalOverflow = (videoWidth - (surfaceWidth * (videoHeight.toFloat() / surfaceHeight))) /
                videoHeight.toFloat()
        val verticalOverflow = (videoHeight - (surfaceHeight * (videoWidth.toFloat() / surfaceWidth))) /
                videoWidth.toFloat()

        when (alignmentMode) {
            WallpaperProperties.VALUE_COVER_INDEX -> {
                if (videoAspect >= surfaceAspect) {
                    wallpaperTranslation.x = alignment * 2.0f * horizontalOverflow
                    wallpaperTranslation.y = 0.0f
                    wallpaperScale.x = videoAspect
                    wallpaperScale.y = 1.0f
                    viewportScale.x = 1.0f / surfaceAspect
                    viewportScale.y = 1.0f
                } else {
                    wallpaperTranslation.x = 0.0f
                    wallpaperTranslation.y = alignment * 2.0f * verticalOverflow
                    wallpaperScale.x = 1.0f
                    wallpaperScale.y = 1.0f / videoAspect
                    viewportScale.x = 1.0f
                    viewportScale.y = surfaceAspect
                }
            }
            WallpaperProperties.VALUE_FILL_INDEX -> {
                if (videoAspect >= surfaceAspect) {
                    wallpaperScale.x = 1.0f
                    wallpaperScale.y = 1.0f / videoAspect
                    viewportScale.x = 1.0f
                    viewportScale.y = surfaceAspect
                    wallpaperTranslation.y = alignment * 2.0f * verticalOverflow
                    wallpaperTranslation.x = 0.0f
                } else {
                    wallpaperScale.y = 1.0f
                    wallpaperScale.x = videoAspect
                    viewportScale.y = 1.0f
                    viewportScale.x = 1.0f / surfaceAspect
                    wallpaperTranslation.x = alignment * 2.0f * horizontalOverflow
                    wallpaperTranslation.y = 0.0f
                }
            }
            WallpaperProperties.VALUE_FREE_INDEX -> {
                if (videoAspect >= surfaceAspect) {
                    wallpaperTranslation.x = 0.0f
                    wallpaperTranslation.y = 0.0f
                    wallpaperScale.x = videoAspect
                    wallpaperScale.y = 1.0f
                    viewportScale.x = 1.0f / surfaceAspect
                    viewportScale.y = 1.0f
                } else {
                    wallpaperTranslation.x = 0.0f
                    wallpaperTranslation.y = 0.0f
                    wallpaperScale.x = 1.0f
                    wallpaperScale.y = 1.0f / videoAspect
                    viewportScale.x = 1.0f
                    viewportScale.y = surfaceAspect
                }
            }
        }
        synchronized(this) {
            transformsDirty = true
            if (surfaceTexture != null) {
                wallpaperView?.invalidate()
            }
        }
    }

    override fun applyWallpaperProperties(properties: String) {
        val map = Property.fromJson(properties)
        if (isLoaded) {
            applyPropertiesInternal(map)
        }
        synchronized(this) {
            wProperties?.let { WeEnginePrefs.updatePropertyValues(it, map) }
        }
    }

    @Synchronized
    override fun getWallpaperProperties(): HashMap<String, Property> {
        return wProperties ?: HashMap()
    }

    override fun getDefaultWallpaperProperties(): HashMap<String, Property> {
        return wDefaultProperties ?: HashMap()
    }

    override fun isAlignmentAvailable(): Boolean {
        val drawable = wallpaperView ?: return false
        val aspect = drawable.width().toFloat() / drawable.height().toFloat()
        return aspect <= 0.4090909f || aspect >= 0.6f || alignmentMode != 0
    }

    override fun requestBitmap(callback: (Bitmap?) -> Unit) {
        bitmapCallback = callback
    }

    override fun setScreenRotation(rotation: Int) {
        if (screenRotation == rotation) return
        synchronized(this) {
            screenRotation = rotation
            when (rotation) {
                0 -> {
                    parallaxMatrix[0] = 1.0f; parallaxMatrix[1] = 1.0f
                    parallaxMatrix[2] = 0.0f; parallaxMatrix[3] = 0.0f
                }
                1 -> {
                    parallaxMatrix[0] = 0.0f; parallaxMatrix[1] = 0.0f
                    parallaxMatrix[2] = 1.0f; parallaxMatrix[3] = -1.0f
                }
                2 -> {
                    parallaxMatrix[0] = -1.0f; parallaxMatrix[1] = -1.0f
                    parallaxMatrix[2] = 0.0f; parallaxMatrix[3] = 0.0f
                }
                3 -> {
                    parallaxMatrix[0] = 0.0f; parallaxMatrix[1] = 0.0f
                    parallaxMatrix[2] = -1.0f; parallaxMatrix[3] = 1.0f
                }
            }
        }
        queueEvent {
            synchronized(this) {
                freeXForm =
                    if (WeEnginePrefs.isRotationLandscape(screenRotation)) freeXFormLandscape else freeXFormPortrait
                transformsDirty = true
            }
            if (isLoaded) {
                scaleVideo()
            }
            wallpaperView?.invalidate()
        }
        frameSemaphore.release()
    }

    override fun onPauseWallpaper() {
        synchronized(this) {
            isProducingFrames = false
            if (isLoaded) {
                wallpaperView?.pause()
            }
            isPaused = true
        }
        frameSemaphore.release()
        onPause()
    }

    override fun onResumeWallpaper() {
        onResume()
        synchronized(this) {
            isPaused = false
            if (isLoaded) {
                wallpaperView?.play()
            }
            isProducingFrames = isLoaded
        }
        frameSemaphore.release()
    }

    override fun onStopWallpaper() {
        synchronized(this) {
            isProducingFrames = false
        }
        frameSemaphore.release()
        if (isLoaded) {
            wallpaperView?.stop()
        }
        isSurfaceValid = false
        pendingFile = null
    }

    override fun onDestroyWallpaper() {
        loadCallback = null
        synchronized(this) {
            isProducingFrames = false
        }
        frameSemaphore.release()
        if (isLoaded) {
            wallpaperView?.destroy()
        }
        onDetachedFromWindow()
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        getHolder().removeCallback(this)
    }

    override fun setLoadCallback(runnable: Runnable?) {
        loadCallback = runnable
    }

    private fun updateParallaxOffsets() {
        if (parallaxMaxDistance != 0.0f || surfaceWidth <= 0) return
        if ((alignmentMode == WallpaperProperties.VALUE_COVER_INDEX ||
                    alignmentMode == WallpaperProperties.VALUE_FREE_INDEX) && isLoaded
        ) {
            val drawable = wallpaperView ?: return
            val videoWidth = drawable.width().toFloat()
            val videoHeight = drawable.height().toFloat()
            val videoAspect = videoWidth / videoHeight
            val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
            val isLandscape = WeEnginePrefs.isRotationLandscape(screenRotation)
            var alignment = 0f
            if (alignmentMode == WallpaperProperties.VALUE_COVER_INDEX) {
                alignment = if (isLandscape) alignmentPositionLandscape else alignmentPosition
            }
            var distance = alignment + 0.5f
            if (distance > 0.5f) {
                distance = 1.0f - distance
            }
            var horizontalFactor = 0.2f
            var verticalFactor: Float
            if (videoAspect > surfaceAspect) {
                val scaledWidth = videoWidth * (surfaceHeight / videoHeight)
                horizontalFactor = ((scaledWidth - surfaceWidth) * distance) / scaledWidth
                verticalFactor = 0.2f
            } else if (surfaceAspect < videoAspect) {
                val scaledHeight = videoHeight * (surfaceWidth / videoWidth)
                verticalFactor = ((scaledHeight - surfaceHeight) * distance) / scaledHeight
            } else {
                verticalFactor = 0.2f
            }
            if (isLandscape) {
                val tmp = horizontalFactor
                horizontalFactor = verticalFactor
                verticalFactor = tmp
            }
            parallaxOffset?.let {
                it.x *= horizontalFactor
                it.y *= verticalFactor
            }
        }
    }

    override fun sendNormalizedParallaxOffset(offset: PointF?, maxDistance: Float, speedFactor: Float) {
        queueEvent {
            if (parallaxOffset == null) {
                parallaxOffsetSmoothed = PointF(0.0f, 0.0f)
            }
            parallaxMaxDistance = maxDistance
            parallaxSpeedFactor = speedFactor * 0.5f
            val transformed = offset?.let {
                PointF(
                    (it.x * parallaxMatrix[0]) + (it.y * parallaxMatrix[3]),
                    (it.y * parallaxMatrix[1]) + (it.x * parallaxMatrix[2]),
                )
            }
            parallaxOffset = transformed
            if (transformed != null) {
                lastSentRawParallax.x = transformed.x
                lastSentRawParallax.y = transformed.y
                updateParallaxOffsets()
            }
        }
    }

    // =====================================================================
    // Drawables
    // =====================================================================

    abstract class WallpaperDrawable {
        abstract fun load(file: String)
        abstract fun applyProperties(properties: HashMap<String, Property>)
        abstract fun pause()
        abstract fun play()
        abstract fun stop()
        abstract fun destroy()
        abstract fun width(): Int
        abstract fun height(): Int
        abstract fun invalidate()
    }

    inner class WallpaperImageDrawable : WallpaperDrawable() {
        private var bitmap: Bitmap? = null
        private var surface: Surface? = null
        private val rect = Rect()
        private var destroyed: Boolean = false

        override fun applyProperties(properties: HashMap<String, Property>) {
        }

        override fun pause() {
        }

        override fun play() {
        }

        override fun stop() {
        }

        override fun load(file: String) {
            try {
                context.contentResolver.openInputStream(Uri.parse(file))?.use { input ->
                    bitmap = BitmapFactory.decodeStream(input)
                }
            } catch (t: Throwable) {
            }
            val bmp = bitmap
            if (bmp != null) {
                val st = surfaceTexture ?: return
                st.setDefaultBufferSize(bmp.width, bmp.height)
                surface = Surface(st)
                rect.right = bmp.width
                rect.bottom = bmp.height
            }
            invalidate()
        }

        override fun destroy() {
            destroyed = true
            surface?.release()
            surface = null
        }

        override fun width(): Int = bitmap?.width ?: 1

        override fun height(): Int = bitmap?.height ?: 1

        override fun invalidate() {
            if (destroyed) return
            val bmp = bitmap ?: return
            val s = surface ?: return
            val canvas = s.lockCanvas(rect)
            canvas?.drawBitmap(bmp, 0.0f, 0.0f, null)
            s.unlockCanvasAndPost(canvas)
            this@VideoWallpaperView.onFrameAvailable(surfaceTexture)
        }
    }

    inner class WallpaperGifDrawable : WallpaperDrawable() {
        private var animatedDrawable: AnimatedImageDrawable? = null
        private var drawable: Drawable? = null
        private var surface: Surface? = null
        private val rect = Rect()
        private var destroyed: Boolean = false

        private val gifCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                if (destroyed) {
                    frameSemaphore.release()
                    return
                }
                queueEvent {
                    val s = surface
                    if (s != null) {
                        val canvas: Canvas? = s.lockCanvas(rect)
                        if (canvas != null) {
                            drawable?.draw(canvas)
                            s.unlockCanvasAndPost(canvas)
                        }
                    }
                    this@VideoWallpaperView.onFrameAvailable(surfaceTexture)
                }
                frameSemaphore.release()
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            }
        }

        override fun applyProperties(properties: HashMap<String, Property>) {
        }

        override fun stop() {
        }

        override fun load(file: String) {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(file))
            val decoded = ImageDecoder.decodeDrawable(source)
            drawable = decoded
            decoded.callback = gifCallback
            val st = surfaceTexture ?: return
            st.setDefaultBufferSize(decoded.minimumWidth, decoded.minimumHeight)
            val s = Surface(st)
            surface = s
            val canvas = s.lockCanvas(rect)
            decoded.draw(canvas)
            s.unlockCanvasAndPost(canvas)
            if (decoded is AnimatedImageDrawable) {
                animatedDrawable = decoded
                decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                decoded.start()
            }
            this@VideoWallpaperView.onFrameAvailable(surfaceTexture)
        }

        override fun pause() {
            animatedDrawable?.stop()
        }

        override fun play() {
            animatedDrawable?.let {
                it.start()
                this@VideoWallpaperView.onFrameAvailable(surfaceTexture)
                it.invalidateSelf()
            }
        }

        override fun destroy() {
            destroyed = true
            surface?.release()
        }

        override fun width(): Int = drawable?.minimumWidth ?: 1

        override fun height(): Int = drawable?.minimumHeight ?: 1

        override fun invalidate() {
            animatedDrawable?.invalidateSelf()
        }
    }

    inner class VideoDrawable : WallpaperDrawable() {
        private var surface: Surface? = null
        private val video = MediaPlayer()
        private var videoWasPrepared: Boolean = false

        override fun invalidate() {
        }

        override fun load(file: String) {
            video.isLooping = true
            video.setVolume(0.0f, 0.0f)
            if (isLocalPlayer) {
                try {
                    video.setDataSource(context, Uri.parse(file))
                } catch (t: Throwable) {
                }
            } else {
                val range = lib?.getWallpaperFileVirtualOffset(file) ?: return
                try {
                    video.setDataSource(
                        WeEnginePrefs.mpkgVideoReader(
                            RandomAccessFile(File(context.filesDir, file).absolutePath, "r"),
                            range,
                        )
                    )
                } catch (e: Exception) {
                }
            }
            surface?.release()
            val s = Surface(surfaceTexture)
            surface = s
            video.setSurface(s)
            try {
                video.prepare()
                videoWasPrepared = true
            } catch (t: Throwable) {
            }
        }

        override fun applyProperties(properties: HashMap<String, Property>) {
            if (properties.containsKey(WallpaperProperties.PROP_RATE)) {
                try {
                    if (videoWasPrepared) {
                        val params = video.playbackParams
                        params.speed = max(0.01f, rate)
                        video.playbackParams = params
                    }
                } catch (e: Exception) {
                }
            }
        }

        override fun pause() {
            if (video.isPlaying) {
                video.pause()
            }
            pendingReloadCheck = false
            getActiveHandler()?.removeCallbacks(reloadWallpaper)
        }

        override fun play() {
            if (!video.isPlaying) {
                video.start()
            }
            pendingReloadCheck = true
            getActiveHandler()?.removeCallbacks(reloadWallpaper)
            getActiveHandler()?.postDelayed(reloadWallpaper, 5000L)
        }

        override fun stop() {
            video.stop()
            video.reset()
            videoWasPrepared = false
            pendingReloadCheck = false
            getActiveHandler()?.removeCallbacks(reloadWallpaper)
        }

        override fun destroy() {
            video.release()
            surface?.release()
            surface = null
        }

        override fun width(): Int = video.videoWidth

        override fun height(): Int = video.videoHeight
    }
}
