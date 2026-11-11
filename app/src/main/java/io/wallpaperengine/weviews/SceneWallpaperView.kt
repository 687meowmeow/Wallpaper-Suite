@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.wallpaperengine.weviews

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
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
import io.wallpaperengine.weutil.WallpaperSurfaceHack
import io.wallpaperengine.wrapper.SceneLib
import com.wepwawet.log.WepWawetLogger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView that renders Scene wallpapers through the native Wallpaper
 * Engine (port of the official client's `SceneWallpaperView`).
 *
 * Lifecycle (driven by [io.wallpaperengine.weclient.WEWallpaperService]):
 *  - constructed while `WallpaperSurfaceHack.holderDelegate` holds the
 *    live-wallpaper engine's SurfaceHolder, so the view renders into the
 *    wallpaper surface even though it is not attached to any window;
 *  - `onCreated(lib)` installs the [Renderer];
 *  - `loadWallpaper(file)` queues a scene load on the GL thread;
 *  - `onDestroyWallpaper()` shuts the scene + context down.
 *
 * Frame pacing: [Renderer.onDrawFrame] calls `updateScene` then sleeps to
 * respect the general FPS limit (exact port of the official pacing code).
 */
class SceneWallpaperView(context: Context) : GLSurfaceView(context), WallpaperControls {

    /** v0.8.10: window for the renderer's duplicate scene-request guard. */
    private companion object {
        const val DUPLICATE_REQUEST_WINDOW_MS = 1500L
    }

    var lib: SceneLib? = null
    private var customHandler: Handler? = null
    private var loadCallback: Runnable? = null
    // v0.8.6: first-frame callback — fires after the renderer has drawn
    // its first complete frame (i.e. after onDrawFrame has run once with
    // a valid sceneContext). Used by WallpaperPreviewScreen to keep the
    // static preview bitmap visible until the GL surface has actually
    // drawn something, fixing the "preview is black when it shouldn't be"
    // bug where the bitmap was hidden the instant the load callback fired
    // but before the first GL frame was drawn.
    //
    // v0.8.7 (bug check): both fields are written from the MAIN thread
    // (setFirstFrameCallback is called in WallpaperPreviewScreen.onLoaded)
    // and read/written from the GL RENDER thread (onDrawFrame,
    // requestLoadScene via queueEvent) — a data race without @Volatile.
    // A stale firstFrameFired=true on the GL thread would skip firing the
    // freshly-registered callback; a stale null callback reference would
    // drop the first-frame event entirely (preview stuck on the static
    // bitmap).
    @Volatile private var firstFrameCallback: Runnable? = null
    @Volatile private var firstFrameFired: Boolean = false

    // v0.8.9: black-render detection. Some scene wallpapers render a solid
    // black frame in the in-app preview (e.g. Brown Dust x-ray 3767714202 —
    // its TEXI-flag-0x20 full-mip textures render fine as the APPLIED
    // wallpaper but black in the preview's GL surface; the v0.9.0 attempt
    // to rebuild those textures broke the applied wallpaper and was
    // reverted). Instead of touching the packed bytes, the renderer now
    // PROBES its own framebuffer on frames 30 and 180: if every sampled
    // pixel is (near-)black on BOTH probes, the registered callback fires
    // so the preview screen can fall back to the static preview image.
    // A scene that draws any content disarms the probe immediately.
    @Volatile private var blackFrameCallback: Runnable? = null

    /**
     * Register the fallback to invoke when the live GL preview renders
     * nothing but black. Pass null to disarm. Probing is cheap: two
     * one-shot 12-patch (8×8 px) glReadPixels sweeps.
     */
    fun setBlackFrameCallback(callback: Runnable?) {
        blackFrameCallback = callback
    }

    // v0.8.8 (xray-freeze diagnosis): the v0.8.6/v0.8.7 instrumentation
    // could NOT distinguish "the render loop stopped" from "frames keep
    // rendering but the scene is frozen": the frame-SPIKE detector only
    // measures deltas BETWEEN consecutive onDrawFrame calls, so a full
    // stall (updateScene never returning / the GL thread dead) logged
    // nothing at all. These three new probes close that gap:
    //   1. glFrameCount — @Volatile monotonic frame counter, written on the
    //      GL thread, readable from anywhere.
    //   2. A 5-second render heartbeat logged from onDrawFrame (frames +
    //      average frame time). If the heartbeat stops after a touch, the
    //      GL thread is stuck INSIDE native code (updateScene or the
    //      sendTouchInput call itself).
    //   3. sendTouchInput enter/exit traces on the GL thread + a
    //      between-touches stall check on the main thread — together they
    //      name the exact native call that never returned.
    @Volatile var glFrameCount: Long = 0L
        private set
    private var heartbeatFrameBase = 0L
    private var heartbeatLastNs = 0L
    @Volatile private var lastTouchFrameCount = -1L
    @Volatile private var lastTouchTimeMs = 0L
    // v0.8.8: touch-trace rate limiting (see sendTouchInput).
    @Volatile private var lastTracedTouchDown: Boolean? = null
    @Volatile private var lastTouchTraceNs = 0L

    private var supportsAudioProcessing: Boolean = false
    private var supportsAlignment: Boolean = false
    private var alignmentPosition: Float = 50.0f
    private var alignmentPositionLandscape: Float = 50.0f
    private var alignmentMode: Int = 0
    private val canvasSize = PointF(1.0f, 1.0f)
    private var properties: HashMap<String, Property>? = null
    private var defaultProperties: HashMap<String, Property>? = null
    private val startTouch = PointF()
    private val fftCache = FloatArray(64)
    private var holderDelegate: SurfaceHolder? = null
    private var bitmapCallbackDelay: Int = 0
    private var bitmapCallback: ((Bitmap?) -> Unit)? = null
    private var maxFpsNano: Double = 0.0
    private var enablePreviewTouchInput: Boolean = true
    private var lastDrawTime: Long = 0
    private var screenRotation: Int = 0
    private val sensorMatrix = floatArrayOf(1.0f, 1.0f, -1.0f, 0.0f, 0.0f, 0.0f)
    private var freeXFormPortrait = FloatArray(16)
    private var freeXFormLandscape = FloatArray(16)
    private var freeXForm = FloatArray(16)
    private val freeAlignTouchHandler = FreeAlignTouchHandler(context, "scene")
    var sceneRenderer: Renderer? = null
        private set

    init {
        Matrix.setIdentityM(freeXFormPortrait, 0)
        Matrix.setIdentityM(freeXFormLandscape, 0)
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        preserveEGLContextOnPause = true
        if (holderDelegate == null) {
            setOnTouchListener { view, event -> onTouchEventInternal(view, event) }
        }
    }

    fun setCustomHandler(handler: Handler?) {
        customHandler = handler
    }

    private fun getActiveHandler(): Handler? = handler ?: customHandler

    // =====================================================================
    // Touch input (preview alignment drag + free-align gestures)
    // =====================================================================

    private fun onTouchEventInternal(view: View, motionEvent: MotionEvent): Boolean {
        val action = motionEvent.actionMasked
        // v0.8.6: log touch events at TRACE level so the xray-circle-freeze
        // bug can be diagnosed — the freeze happens when the xray effect's
        // touch-up event triggers a shader recompile in the native engine,
        // and without touch timing in the log we can't correlate the freeze
        // to the engine's shader activity. The logs are gated by the verbose
        // toggle (TRACE level is suppressed when verbose is off).
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                WepWawetLogger.engineDebug("SceneView: touch DOWN",
                    "x" to motionEvent.x.toInt(), "y" to motionEvent.y.toInt())
                noteTouchForStallCheck("DOWN")
                if (enablePreviewTouchInput) {
                    sendTouchInput(motionEvent.x, motionEvent.y, true)
                }
            }
            MotionEvent.ACTION_UP -> {
                WepWawetLogger.engineDebug("SceneView: touch UP",
                    "x" to motionEvent.x.toInt(), "y" to motionEvent.y.toInt())
                noteTouchForStallCheck("UP")
                if (enablePreviewTouchInput) {
                    sendTouchInput(motionEvent.x, motionEvent.y, false)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (enablePreviewTouchInput) {
                    sendTouchInput(motionEvent.x, motionEvent.y, true)
                }
            }
        }
        if (alignmentMode == WallpaperProperties.VALUE_FREE_INDEX_SCENE) {
            if (supportsAlignment) {
                val xForm = freeXForm.clone()
                freeAlignTouchHandler.configure(
                    xForm,
                    canvasSize.x,
                    canvasSize.y,
                    width.toFloat(),
                    height.toFloat(),
                    1.0f,
                    1.0f,
                )
                freeAlignTouchHandler.onTouchEvent(motionEvent)
                synchronized(this) {
                    System.arraycopy(xForm, 0, freeXForm, 0, 12)
                }
                queueEvent {
                    val propKey = if (WeEnginePrefs.isRotationLandscape(screenRotation)) {
                        WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX_LANDSCAPE
                    } else {
                        WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX
                    }
                    val property = properties?.get(propKey)
                    property?.value = WeEnginePrefs.matrixToString(freeXForm)
                    lib?.sendFreeAlignmentXForm(sceneRenderer?.sceneContext ?: 0, xForm)
                }
            }
        } else {
            if (action == MotionEvent.ACTION_DOWN) {
                startTouch.set(motionEvent.rawX, motionEvent.rawY)
            } else if (action == MotionEvent.ACTION_MOVE) {
                val point = PointF(motionEvent.rawX, motionEvent.rawY)
                val delta = PointF(point.x - startTouch.x, point.y - startTouch.y)
                startTouch.set(point.x, point.y)
                if (supportsAlignment && canvasSize.x > 1.0f && canvasSize.y > 1.0f) {
                    val canvasAspect = canvasSize.x / canvasSize.y
                    val viewAspect = width.toFloat() / height.toFloat()
                    if (WeEnginePrefs.isRotationLandscape(screenRotation)) {
                        alignmentPositionLandscape = WeEnginePrefs.updateAlignment(
                            alignmentMode, alignmentPositionLandscape, delta,
                            canvasAspect >= viewAspect,
                            canvasSize.x, canvasSize.y, width.toFloat(), height.toFloat(),
                        )
                    } else {
                        alignmentPosition = WeEnginePrefs.updateAlignment(
                            alignmentMode, alignmentPosition, delta,
                            canvasAspect >= viewAspect,
                            canvasSize.x, canvasSize.y, width.toFloat(), height.toFloat(),
                        )
                    }
                    queueEvent {
                        properties?.get(WallpaperProperties.PROP_ALIGNMENT_POSITION)?.value = alignmentPosition
                        properties?.get(WallpaperProperties.PROP_ALIGNMENT_POSITION_LANDSCAPE)?.value =
                            alignmentPositionLandscape
                        lib?.applySceneProperties(
                            sceneRenderer?.sceneContext ?: 0,
                            "{\"alignmentposition\":{\"value\":$alignmentPosition}," +
                                "\"alignmentpositionlandscape\":{\"value\":$alignmentPositionLandscape}}",
                        )
                    }
                }
            }
        }
        return true
    }

    /**
     * v0.8.8 (xray-freeze diagnosis): between-touches render-stall check,
     * runs on the MAIN thread (so it still fires when the GL thread is the
     * thing that's hung). If not a single frame was drawn between the
     * previous touch and this one (and at least 1.5 s elapsed), the render
     * loop is stalled — with the GL-thread enter/exit traces above this
     * names the exact native call that never returned.
     *
     * The 1.5 s floor avoids false positives on legitimately slow scenes
     * (a heartbeat line with avg_frame_ms > 1500 covers that case instead).
     */
    private fun noteTouchForStallCheck(phase: String) {
        val nowMs = System.currentTimeMillis()
        val frames = glFrameCount
        if (lastTouchFrameCount >= 0 && frames == lastTouchFrameCount &&
            nowMs - lastTouchTimeMs >= 1500
        ) {
            WepWawetLogger.engineWarn(
                "SceneView: render loop STALLED — no frames since the previous touch",
                "phase" to phase,
                "frames_total" to frames,
                "gap_ms" to (nowMs - lastTouchTimeMs),
            )
        }
        lastTouchFrameCount = frames
        lastTouchTimeMs = nowMs
    }

    // =====================================================================
    // SurfaceHolder delegation (wallpaper surface while not attached)
    // =====================================================================

    override fun getHolder(): SurfaceHolder {
        synchronized(WallpaperSurfaceHack) {
            if (holderDelegate == null) {
                holderDelegate = WallpaperSurfaceHack.holderDelegate
                WallpaperSurfaceHack.holderDelegate = null
            }
        }
        return holderDelegate ?: super.getHolder()
    }

    fun onCreated(lib: SceneLib) {
        this.lib = lib
        val renderer = Renderer()
        sceneRenderer = renderer
        setRenderer(renderer)
        WepWawetLogger.engine("SceneView: renderer installed")
    }

    // =====================================================================
    // WallpaperControls
    // =====================================================================

    override fun onPauseWallpaper() {
        onPause()
    }

    override fun onResumeWallpaper() {
        onResume()
    }

    override fun onStopWallpaper() {
        queueEvent {
            sceneRenderer?.shutdown()
        }
    }

    override fun onDestroyWallpaper() {
        sceneRenderer?.shutdown()
        loadCallback = null
        onDetachedFromWindow()
        getHolder().removeCallback(this)
    }

    override fun loadWallpaper(file: String) {
        queueEvent {
            sceneRenderer?.requestLoadScene(file)
        }
    }

    override fun applyWallpaperProperties(properties: String) {
        val map = Property.fromJson(properties)
        if (map.containsKey(WallpaperProperties.PROP_ALIGNMENT_POSITION)) {
            alignmentPosition = WeEnginePrefs.anyToFloat(map[WallpaperProperties.PROP_ALIGNMENT_POSITION]?.value)
        }
        if (map.containsKey(WallpaperProperties.PROP_ALIGNMENT_POSITION_LANDSCAPE)) {
            alignmentPositionLandscape =
                WeEnginePrefs.anyToFloat(map[WallpaperProperties.PROP_ALIGNMENT_POSITION_LANDSCAPE]?.value)
        }
        var hasFreeXForm = false
        if (map.containsKey(WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX)) {
            WeEnginePrefs.anyToMatrix(
                map[WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX]?.value, freeXFormPortrait
            )
            hasFreeXForm = true
        }
        if (map.containsKey(WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX_LANDSCAPE)) {
            WeEnginePrefs.anyToMatrix(
                map[WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX_LANDSCAPE]?.value, freeXFormLandscape
            )
            hasFreeXForm = true
        }
        if (map.containsKey(WallpaperProperties.PROP_ALIGNMENT)) {
            alignmentMode = WeEnginePrefs.anyToInt(map[WallpaperProperties.PROP_ALIGNMENT]?.value)
        }
        synchronized(this) {
            val props = this.properties
            if (props != null) {
                WeEnginePrefs.updatePropertyValues(props, map)
            }
        }
        queueEvent {
            sceneRenderer?.applyWallpaperProperties(properties)
            if (hasFreeXForm) {
                lib?.sendFreeAlignmentXForm(sceneRenderer?.sceneContext ?: 0, freeXForm)
            }
        }
    }

    override fun setGeneralSettings(general: GeneralSettingsInfo) {
        enablePreviewTouchInput = general.touchInputEnabled
        maxFpsNano = if (general.maxFps >= 1) 1.0e9 / general.maxFps.toDouble() else 0.0
    }

    override fun getWallpaperProperties(): HashMap<String, Property> = properties ?: HashMap()

    override fun getDefaultWallpaperProperties(): HashMap<String, Property> = defaultProperties ?: HashMap()

    override fun isAlignmentAvailable(): Boolean {
        val aspect = canvasSize.x / canvasSize.y
        return if (supportsAlignment) {
            aspect <= 0.4090909f || aspect >= 0.6f || alignmentMode != 0
        } else {
            false
        }
    }

    override fun requestBitmap(callback: (Bitmap?) -> Unit) {
        queueEvent {
            bitmapCallbackDelay = 10
            bitmapCallback = callback
        }
    }

    override fun setScreenRotation(rotation: Int) {
        if (screenRotation == rotation) return
        val orientation: String
        synchronized(this) {
            screenRotation = rotation
            when (rotation) {
                0 -> {
                    sensorMatrix[0] = 1.0f; sensorMatrix[1] = 1.0f; sensorMatrix[2] = -1.0f
                    sensorMatrix[3] = 0.0f; sensorMatrix[4] = 0.0f; sensorMatrix[5] = 0.0f
                }
                1 -> {
                    sensorMatrix[0] = 0.0f; sensorMatrix[1] = 0.0f; sensorMatrix[2] = -1.0f
                    sensorMatrix[3] = 1.0f; sensorMatrix[4] = -1.0f; sensorMatrix[5] = 0.0f
                }
                2 -> {
                    sensorMatrix[0] = -1.0f; sensorMatrix[1] = -1.0f; sensorMatrix[2] = -1.0f
                    sensorMatrix[3] = 0.0f; sensorMatrix[4] = 0.0f; sensorMatrix[5] = 0.0f
                }
                3 -> {
                    sensorMatrix[0] = 0.0f; sensorMatrix[1] = 0.0f; sensorMatrix[2] = -1.0f
                    sensorMatrix[3] = -1.0f; sensorMatrix[4] = 1.0f; sensorMatrix[5] = 0.0f
                }
            }
            orientation = if (WeEnginePrefs.isRotationLandscape(screenRotation)) {
                WallpaperProperties.VALUE_ALIGNMENT_ORIENTATION_LANDSCAPE
            } else {
                WallpaperProperties.VALUE_ALIGNMENT_ORIENTATION_PORTRAIT
            }
            properties?.get(WallpaperProperties.PROP_ALIGNMENT_ORIENTATION)?.value = orientation
            freeXForm = if (WeEnginePrefs.isRotationLandscape(screenRotation)) freeXFormLandscape else freeXFormPortrait
        }
        queueEvent {
            synchronized(this) {
                lib?.sendFreeAlignmentXForm(sceneRenderer?.sceneContext ?: 0, freeXForm)
                lib?.applySceneProperties(
                    sceneRenderer?.sceneContext ?: 0,
                    "{\"alignmentorientation\":{\"value\":\"$orientation\"}}",
                )
            }
        }
    }

    override fun cancelLoadingWallpaper() {
        sceneRenderer?.cancelLoadingScene()
    }

    override fun setLoadCallback(runnable: Runnable?) {
        loadCallback = runnable
    }

    /**
     * v0.8.6: Set a callback that fires after the renderer has drawn its
     * first complete frame. Used by [com.wepwawet.ui.screens.WallpaperPreviewScreen]
     * to keep the static preview bitmap visible until the GL surface has
     * actually drawn something (fixes the "preview is black" bug where the
     * bitmap was hidden the instant the load callback fired but before the
     * first GL frame was drawn).
     *
     * The callback fires exactly once per scene load. Calling
     * [requestLoadScene] again resets the flag so the callback fires again
     * for the new scene.
     */
    fun setFirstFrameCallback(runnable: Runnable?) {
        firstFrameCallback = runnable
        // Reset so the new callback fires on the next first frame.
        firstFrameFired = false
    }

    override fun isSupportingAudioProcessing(): Boolean = supportsAudioProcessing

    override fun sendAudioData(fft64: FloatArray) {
        synchronized(fftCache) {
            System.arraycopy(fft64, 0, fftCache, 0, minOf(fft64.size, fftCache.size))
        }
        queueEvent {
            synchronized(fftCache) {
                lib?.sendAudioData(sceneRenderer?.sceneContext ?: 0, fftCache)
            }
        }
    }

    override fun sendNormalizedParallaxOffset(offset: PointF?, maxDistance: Float, speedFactor: Float) {
        queueEvent {
            val ctx = sceneRenderer?.sceneContext ?: 0
            if (offset == null) {
                lib?.sendNormalizedParallaxOffset(ctx, false, 0.0f, 0.0f, maxDistance, speedFactor)
            } else {
                lib?.sendNormalizedParallaxOffset(
                    ctx,
                    true,
                    (offset.x * sensorMatrix[0]) + (offset.y * sensorMatrix[4]),
                    (offset.y * sensorMatrix[1]) + (offset.x * sensorMatrix[3]),
                    maxDistance,
                    speedFactor,
                )
            }
        }
    }

    override fun sendTouchInput(x: Float, y: Float, down: Boolean) {
        queueEvent {
            // v0.8.8 (xray-freeze diagnosis): trace the native call on the
            // GL thread. If the engine never returns from sendTouchInput,
            // the "exit" line is missing from the log — naming the hang
            // site directly. Also logs the sceneContext: a 0 here means the
            // touches are being delivered to a dead context (silent no-op).
            //
            // Rate limit: DOWN/UP transitions are ALWAYS traced (the freeze
            // correlates with them); ACTION_MOVE is traced at most once per
            // 200 ms so a drag doesn't flood the ring buffer at 120 Hz.
            val trace = lastTracedTouchDown != down ||
                System.nanoTime() - lastTouchTraceNs >= 200_000_000L
            if (trace) {
                lastTracedTouchDown = down
                lastTouchTraceNs = System.nanoTime()
                WepWawetLogger.engineDebug("SceneView: sendTouchInput native enter",
                    "down" to down,
                    "x" to x.toInt(),
                    "y" to y.toInt(),
                    "scene_context" to (sceneRenderer?.sceneContext ?: 0))
            }
            lib?.sendTouchInput(sceneRenderer?.sceneContext ?: 0, down, x, y)
            if (trace) {
                WepWawetLogger.engineDebug("SceneView: sendTouchInput native exit",
                    "down" to down)
            }
        }
    }

    override fun sendForceInput(force2D: PointF) {
        queueEvent {
            lib?.sendForceInput(sceneRenderer?.sceneContext ?: 0, force2D.x, force2D.y)
        }
    }

    override fun sendGravityInput(gravityX: Float, gravityY: Float, gravityZ: Float) {
        queueEvent {
            val ctx = sceneRenderer?.sceneContext ?: 0
            lib?.sendGravityInput(
                ctx,
                (sensorMatrix[0] * gravityX) + (sensorMatrix[4] * gravityY),
                (gravityY * sensorMatrix[1]) + (gravityX * sensorMatrix[3]),
                (sensorMatrix[2] * gravityZ) + (gravityZ * sensorMatrix[5]),
            )
        }
    }

    // =====================================================================
    // Renderer
    // =====================================================================

    inner class Renderer : GLSurfaceView.Renderer {

        var sceneContext: Int = 0
            private set
        private var surfaceValid: Boolean = false
        private var pendingScene: String? = null
        // v0.8.10: duplicate-request guard state — the file this renderer
        // last STARTED loading and when (elapsedRealtime clock).
        private var currentSceneFile: String? = null
        private var currentSceneLoadedAt: Long = 0L
        private var wallpaperWidth: Int = 0
        private var wallpaperHeight: Int = 0

        private fun loadScene(packageFile: String) {
            WepWawetLogger.engine("SceneView: loading scene",
                "file" to packageFile)
            if (sceneContext == 0) {
                val lib = lib ?: return
                sceneContext = lib.initContext(this@SceneWallpaperView.context)
                WepWawetLogger.engine("SceneView: engine context created",
                    "context" to sceneContext)
            }
            val lib = lib ?: return
            lib.shutdownScene(sceneContext)
            lib.initScene(sceneContext, packageFile)
            if (width > 0 && height > 0) {
                lib.resizeScene(sceneContext, width, height)
            }
            lib.getSceneCanvasSize(sceneContext, canvasSize)
            val featureFlags = lib.getSceneFeatureFlags(sceneContext)
            supportsAudioProcessing = (featureFlags and 1) != 0
            supportsAlignment = (featureFlags and 2) != 0
            val parallaxAvailable = (featureFlags and 4) != 0
            val physicsAvailable = (featureFlags and 8) != 0
            WepWawetLogger.engine("SceneView: scene initialized",
                "file" to packageFile,
                "canvas" to "${canvasSize.x}x${canvasSize.y}",
                "flags_audio" to supportsAudioProcessing,
                "flags_alignment" to supportsAlignment,
                "flags_parallax" to parallaxAvailable,
                "flags_physics" to physicsAvailable)

            val defaults = Property.fromJson(PropertyList.defaultSceneProperties)
            if (parallaxAvailable || physicsAvailable) {
                defaults[WallpaperProperties.PROP_PARALLAX]?.value = 1.0
            }
            if (supportsAlignment) {
                WeEnginePrefs.extendProperties(defaults, Property.fromJson(PropertyList.alignmentModes))
            }
            if (supportsAudioProcessing) {
                WeEnginePrefs.extendProperties(defaults, Property.fromJson(PropertyList.audioRecordingProperties))
            }
            if (physicsAvailable) {
                WeEnginePrefs.extendProperties(defaults, Property.fromJson(PropertyList.environmentPhysicsProperties))
            }

            val sceneProperties = lib.getSceneProperties(sceneContext)
            if (sceneProperties != null) {
                WeEnginePrefs.addPropertyOrUpdateValue(defaults, Property.fromJson(sceneProperties))
            }

            defaultProperties = defaults
            val working = HashMap<String, Property>()
            for ((key, value) in defaults) {
                working[key] = Property(value)
            }
            properties = working

            synchronized(this@SceneWallpaperView) {
                val stored = WeEnginePrefs.readWallpaperProperties(context, packageFile)
                if (stored != null) {
                    WeEnginePrefs.updatePropertyValues(working, Property.fromJson(stored))
                }
                val orientation = if (WeEnginePrefs.isRotationLandscape(screenRotation)) {
                    WallpaperProperties.VALUE_ALIGNMENT_ORIENTATION_LANDSCAPE
                } else {
                    WallpaperProperties.VALUE_ALIGNMENT_ORIENTATION_PORTRAIT
                }
                working[WallpaperProperties.PROP_ALIGNMENT_ORIENTATION]?.value = orientation
                val json = Gson().toJson(working)
                lib.applySceneProperties(sceneContext, json)

                working[WallpaperProperties.PROP_ALIGNMENT_POSITION]?.value?.let {
                    alignmentPosition = WeEnginePrefs.anyToFloat(it)
                }
                working[WallpaperProperties.PROP_ALIGNMENT_POSITION_LANDSCAPE]?.value?.let {
                    alignmentPositionLandscape = WeEnginePrefs.anyToFloat(it)
                }
                WeEnginePrefs.anyToMatrix(
                    working[WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX]?.value, freeXFormPortrait
                )
                WeEnginePrefs.anyToMatrix(
                    working[WallpaperProperties.PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX_LANDSCAPE]?.value,
                    freeXFormLandscape
                )
                if (supportsAlignment) {
                    working[WallpaperProperties.PROP_ALIGNMENT]?.value?.let {
                        alignmentMode = WeEnginePrefs.anyToInt(it)
                    }
                    freeXForm =
                        if (WeEnginePrefs.isRotationLandscape(screenRotation)) freeXFormLandscape else freeXFormPortrait
                    lib.sendFreeAlignmentXForm(sceneContext, freeXForm)
                }
            }

            getActiveHandler()?.post {
                loadCallback?.run()
            }
            WepWawetLogger.engine("SceneView: scene load complete — properties ready",
                "properties" to working.size)
        }

        fun requestLoadScene(packageFile: String) {
            // v0.8.6: reset the first-frame flag so the callback fires
            // again for the new scene (e.g. after a lifecycle stop/restart
            // reload). Without this, a reload wouldn't fire the callback
            // and the static preview bitmap would stay hidden from the
            // first load — showing a black screen during the reload.
            //
            // v0.8.10 (device log): the preview screen can request the SAME
            // file twice in quick succession (before the surface is valid,
            // then again once the controls arrive) — the log showed every
            // wallpaper initializing twice ~120 ms apart, doubling load
            // time and memory churn. A duplicate request for the file this
            // renderer started loading within the last 1.5 s is a no-op.
            // Real reloads (returning from the SAF texture picker, the
            // onStart bridge) come much later and still reload in full.
            val now = SystemClock.elapsedRealtime()
            if (packageFile == currentSceneFile &&
                now - currentSceneLoadedAt < DUPLICATE_REQUEST_WINDOW_MS
            ) {
                WepWawetLogger.engineDebug("SceneView: duplicate scene request ignored",
                    "file" to packageFile,
                    "ms_since_load" to (now - currentSceneLoadedAt))
                return
            }
            currentSceneFile = packageFile
            currentSceneLoadedAt = now
            firstFrameFired = false
            if (surfaceValid) {
                loadScene(packageFile)
            } else {
                WepWawetLogger.engine("SceneView: surface not valid yet — scene pending",
                    "file" to packageFile)
                pendingScene = packageFile
            }
        }

        fun cancelLoadingScene() {
            if (sceneContext != 0) {
                lib?.cancelLoadingScene(sceneContext)
            }
        }

        fun shutdown() {
            if (sceneContext != 0) {
                lib?.shutdownScene(sceneContext)
                lib?.destroyContext(sceneContext)
                sceneContext = 0
                surfaceValid = false
                pendingScene = null
                currentSceneFile = null
            }
        }

        fun applyWallpaperProperties(properties: String) {
            if (sceneContext != 0) {
                lib?.applySceneProperties(sceneContext, properties)
            }
        }

        override fun onDrawFrame(gl: GL10) {
            if (sceneContext != 0) {
                lib?.updateScene(sceneContext)
                glFrameCount++
                // v0.8.8 (xray-freeze diagnosis): 5-second render heartbeat.
                // A healthy render loop logs this continuously while the
                // surface is visible. If these lines STOP after a touch (and
                // the main-thread stall check fires instead), the GL thread
                // is stuck inside native code; if they CONTINUE while the
                // image is visually frozen, the scene's time/animation state
                // is frozen instead — two completely different root causes,
                // now distinguishable from the log alone.
                val heartbeatNow = System.nanoTime()
                if (heartbeatLastNs == 0L) {
                    heartbeatLastNs = heartbeatNow
                    heartbeatFrameBase = glFrameCount
                } else if (heartbeatNow - heartbeatLastNs >= 5_000_000_000L) {
                    val frames = glFrameCount - heartbeatFrameBase
                    val elapsedMs = (heartbeatNow - heartbeatLastNs) / 1_000_000L
                    WepWawetLogger.engine("SceneView: render heartbeat",
                        "frames" to frames,
                        "avg_frame_ms" to if (frames > 0) (elapsedMs / frames) else -1)
                    heartbeatLastNs = heartbeatNow
                    heartbeatFrameBase = glFrameCount
                }
                // v0.8.6: fire the first-frame callback after the first
                // successful updateScene. The GL surface has now drawn
                // something visible, so the WallpaperPreviewScreen can
                // safely hide the static preview bitmap. Before this fix,
                // the bitmap was hidden the instant the load callback fired
                // (which runs at the end of loadScene, BEFORE the first
                // onDrawFrame) — the user saw a black screen for ~100-500ms
                // until the first GL frame was drawn.
                if (!firstFrameFired) {
                    firstFrameFired = true
                    WepWawetLogger.engine("SceneView: first frame drawn")
                    getActiveHandler()?.post { firstFrameCallback?.run() }
                }
                // v0.8.9: black-render probe (see setBlackFrameCallback).
                // Runs on frames 30 (~0.5 s) and 180 (~3 s) — the second
                // probe gives slow async texture uploads time to land.
                if (blackFrameCallback != null && wallpaperWidth > 0 && wallpaperHeight > 0 &&
                    (glFrameCount == 30L || glFrameCount == 180L)) {
                    val black = isFramebufferBlack()
                    WepWawetLogger.engine("SceneView: black-render probe",
                        "frame" to glFrameCount,
                        "black" to black)
                    if (!black) {
                        blackFrameCallback = null // scene draws content — disarm
                    } else if (glFrameCount >= 180L) {
                        val cb = blackFrameCallback
                        blackFrameCallback = null
                        getActiveHandler()?.post { cb?.run() }
                    }
                }
                if (bitmapCallbackDelay > 0) {
                    bitmapCallbackDelay--
                    if (bitmapCallbackDelay <= 0 && bitmapCallback != null &&
                        wallpaperWidth != 0 && wallpaperHeight != 0
                    ) {
                        val callback = bitmapCallback
                        bitmapCallback = null
                        callback?.invoke(GLUtil.getBitmapFromGL(gl, wallpaperWidth, wallpaperHeight))
                    }
                }
                val nanoTime = System.nanoTime()
                val frameTime = nanoTime - lastDrawTime
                // v0.8.6: log frame-time spikes — a real freeze is a frame
                // that took far longer than the target interval. Correlating
                // these spikes with touch UP events (logged in
                // onTouchEventInternal) and engine log entries (captured by
                // EngineLogTailer) will pinpoint the cause of the "xray
                // circle goes away then freezes" bug.
                //
                // v0.8.7 (bug check): the 100ms fixed threshold was wrong —
                // frameTime INCLUDES the previous frame's intentional
                // FPS-limit sleep (lastDrawTime is stamped before the sleep
                // runs), so any maxFps ≤ 10 would fire the warning on EVERY
                // frame. The threshold is now adaptive: 4× the target frame
                // interval, floored at 200ms — a genuine hitch is always
                // above that, and normal paced frames never are.
                if (lastDrawTime != 0L) {
                    val thresholdNs = (maxFpsNano.toLong() * 4L).coerceAtLeast(200_000_000L)
                    if (frameTime > thresholdNs) {
                        WepWawetLogger.engineWarn("SceneView: frame time spike (freeze)",
                            "frame_ms" to (frameTime / 1_000_000L),
                            "max_fps_pacing_ns" to maxFpsNano.toLong())
                    }
                }
                lastDrawTime = nanoTime
                if (maxFpsNano >= 1.0) {
                    try {
                        val sleepNs = (maxFpsNano - frameTime).toLong()
                        if (sleepNs > 0) {
                            val ms = sleepNs / 1_000_000
                            val ns = sleepNs - (ms * 1_000_000)
                            Thread.sleep(ms, ns.toInt())
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
            wallpaperWidth = width
            wallpaperHeight = height
            WepWawetLogger.engine("SceneView: surface changed",
                "width" to width, "height" to height)
            if (sceneContext != 0) {
                lib?.resizeScene(sceneContext, width, height)
            }
            if (!surfaceValid) {
                surfaceValid = true
                pendingScene?.let {
                    loadScene(it)
                    pendingScene = null
                }
            }
        }

        /**
         * v0.8.9: sample the current framebuffer on a 4×3 grid of 8×8 px
         * patches and report whether EVERY sampled pixel is near-black
         * (< 10 per channel — generous enough for faint vignettes, strict
         * enough that a letterboxed scene or a single bright particle
         * counts as content). Runs on the GL thread inside onDrawFrame,
         * where glReadPixels against the just-presented frame is legal.
         */
        private fun isFramebufferBlack(): Boolean {
            return try {
                val patch = 8
                val buf = java.nio.ByteBuffer.allocateDirect(patch * patch * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                var blackPixels = 0
                var totalPixels = 0
                for (gy in 0 until 3) {
                    for (gx in 0 until 4) {
                        val x = (((gx + 0.5) / 4.0) * wallpaperWidth).toInt()
                            .coerceIn(0, (wallpaperWidth - patch).coerceAtLeast(0))
                        val y = (((gy + 0.5) / 3.0) * wallpaperHeight).toInt()
                            .coerceIn(0, (wallpaperHeight - patch).coerceAtLeast(0))
                        buf.rewind()
                        android.opengl.GLES20.glReadPixels(
                            x, y, patch, patch,
                            android.opengl.GLES20.GL_RGBA,
                            android.opengl.GLES20.GL_UNSIGNED_BYTE, buf)
                        buf.rewind()
                        while (buf.remaining() >= 4) {
                            val r = buf.get().toInt() and 0xFF
                            val g = buf.get().toInt() and 0xFF
                            val b = buf.get().toInt() and 0xFF
                            buf.get() // alpha — ignored
                            totalPixels++
                            if (r < 10 && g < 10 && b < 10) blackPixels++
                        }
                    }
                }
                totalPixels > 0 && (blackPixels * 100L / totalPixels) >= 98L
            } catch (t: Throwable) {
                false
            }
        }

        override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
            WepWawetLogger.engine("SceneView: GL surface created")
            surfaceValid = true
            pendingScene?.let {
                loadScene(it)
                pendingScene = null
            }
        }
    }
}
