@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.wallpaperengine.weclient

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import io.wallpaperengine.wedata.GeneralSettingsInfo
import io.wallpaperengine.wedata.PlaylistData
import io.wallpaperengine.wedata.PlaylistDataWallpaper
import io.wallpaperengine.wedata.Property
import io.wallpaperengine.weutil.AudioRecorder
import io.wallpaperengine.weutil.ParallaxController
import io.wallpaperengine.weutil.WallpaperSurfaceHack
import io.wallpaperengine.weviews.SceneWallpaperView
import io.wallpaperengine.weviews.VideoWallpaperView
import io.wallpaperengine.weviews.WallpaperControls
import io.wallpaperengine.wrapper.SceneLib
import com.wepwawet.log.WepWawetLogger
import java.util.Calendar

/**
 * The live wallpaper service — full port of the official client's
 * `io.wallpaperengine.weclient.WEWallpaperService`.
 *
 * Battery optimization parity (the official behavior):
 *  - paused whenever the wallpaper is not visible (launcher hidden);
 *  - paused while the system is in power-save mode, when the user enabled
 *    "Pause while Saving Battery";
 *  - scene frame rate capped by the general FPS limit;
 *  - video wallpapers run at their native FPS via Surface.setFrameRate on
 *    Android 12+;
 *  - playlist switching only while unpaused.
 */
class WEWallpaperService : WallpaperService() {

    companion object {
        /** Singleton used by the UI to poke the running engine. */
        @JvmStatic
        var instance: WEWallpaperService? = null
    }

    private var screenRotation: Int = 0
    private var currentEngine: GLWallpaperEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        screenRotation = currentDisplayRotation()
    }

    override fun onDestroy() {
        super.onDestroy()
        synchronized(Companion) {
            if (instance == this) {
                instance = null
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screenRotation = currentDisplayRotation()
        currentEngine?.onConfigurationChanged()
    }

    override fun onCreateEngine(): Engine {
        return GLWallpaperEngine()
    }

    val isLiveWallpaperActive: Boolean
        get() = currentEngine != null

    fun requestWallpaperReload() {
        currentEngine?.reloadActiveWallpaper()
        currentEngine?.onWallpaperChangedExternally()
    }

    fun requestWallpaperApplyGeneralProperties() {
        val generalSettings = WeEnginePrefs.readGeneralSettings(this)
        currentEngine?.reloadTouchEventsEnabled(generalSettings.touchInputEnabled)
        currentEngine?.reloadApplyGeneralSettings()
    }

    fun requestWallpaperApplyStoredProperties() {
        currentEngine?.reloadApplyStoredProperties()
    }

    fun requestPlaylistSettingsChanged() {
        currentEngine?.refreshPlaylistSettings()
    }

    fun getCurrentWallpaperFile(): String? = currentEngine?.currentFile

    private fun currentDisplayRotation(): Int {
        return try {
            @Suppress("DEPRECATION")
            val display: Display? = if (Build.VERSION.SDK_INT >= 30) {
                (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            } else {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            }
            display?.rotation ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    /** One "starts at minute X" row of a time-of-day playlist. */
    private class TimeOfDayEntry {
        var filepath: String = ""
        var startTime: Int = 0
    }

    // =====================================================================
    // Engine
    // =====================================================================

    inner class GLWallpaperEngine : Engine(), AudioRecorder.Listener {

        val lib = SceneLib()
        private val handler = Handler(Looper.getMainLooper())
        private val parallaxController = ParallaxController(this@WEWallpaperService)
        private var generalSettings = GeneralSettingsInfo()
        private var audioRecorder: AudioRecorder? = null
        var holder: android.view.SurfaceHolder? = null
            private set
        private var pendingFile: String? = null
        var currentFile: String? = null
            private set
        var wallpaper: GLSurfaceView? = null
            private set
        var wallpaperControls: WallpaperControls? = null
            private set
        private var currentWallpaperColors: WallpaperColors? = null
        private var areTouchEventsEnabled: Boolean = false
        private var currentlyVisible: Boolean = false
        private var currentlyPowerSaving: Boolean = false
        private val playlist = Playlist()

        val powerSavingReceiver: BroadcastReceiver = WeEnginePrefs.makePowerSavingReceiver { powerSaving ->
            currentlyPowerSaving = powerSaving
            updatePausedState()
        }

        private val runnablePlaylist = object : Runnable {
            override fun run() {
                playlistUpdate()
                handler.postDelayed(this, 5000L)
            }
        }

        init {
            generalSettings = WeEnginePrefs.readGeneralSettings(this@WEWallpaperService)
            if (generalSettings.touchInputEnabled) {
                setTouchEventsEnabled(true)
                areTouchEventsEnabled = true
            }
            lib.initLibrary(this@WEWallpaperService)
            lib.setLogToFileEnabled(generalSettings.logToFileEnabled)
            // v0.8.2 CRITICAL FIX: do NOT call isPreview() here. The framework's
            // WallpaperService.Engine.isPreview() reads mIWallpaperEngine, which
            // is only assigned by attach() AFTER onCreateEngine() returns
            // (AOSP WallpaperService$IWallpaperEngineWrapper.doAttachEngine:
            // onCreateEngine() runs first, engine.attach(this) after). Calling
            // it from the constructor is a guaranteed NullPointerException —
            // this exact NPE crashed the engine whenever the system wallpaper
            // picker opened (crash-1794175014068). The official client never
            // calls isPreview() in its constructor; it first uses it in
            // onCreate(SurfaceHolder), where the wrapper is attached.
            WepWawetLogger.engine("WEWallpaperService: engine created",
                "touch_input" to generalSettings.touchInputEnabled,
                "max_fps" to generalSettings.maxFps,
                "power_save" to generalSettings.powerSavingEnabled,
                "log_to_file" to generalSettings.logToFileEnabled)
        }

        private fun shouldBePaused(): Boolean {
            return if (currentlyVisible) {
                currentlyPowerSaving && generalSettings.powerSavingEnabled
            } else {
                true
            }
        }

        fun updatePausedState() {
            val shouldPause = shouldBePaused()
            if (wallpaper != null) {
                if (shouldPause) {
                    parallaxController.pause()
                    wallpaperControls?.onPauseWallpaper()
                } else {
                    wallpaperControls?.onResumeWallpaper()
                    parallaxController.resume()
                }
            }
            playlist.setActive(!shouldPause)
        }

        // ===== Playlist (ported state machine) =====

        private inner class Playlist {
            private val usedWallpapers = HashMap<String, Int>()
            private val randomPool = HashMap<String, Int>()
            private val fullSortedList = ArrayList<String>()
            private val timePlaylist = ArrayList<TimeOfDayEntry>()
            private var sortedPosition: Int = 0
            private var lastChangeTime: Long = 0
            private var duration: Int = 5000
            private var mode: String = ""
            private var isValid: Boolean = false
            private var playlistActive: Boolean = false
            private var playlistData = PlaylistData()

            private fun reloadRandomPool() {
                randomPool.clear()
                for (wallpaper in playlistData.wallpapers) {
                    randomPool[wallpaper.filepath] = 0
                }
            }

            private fun removeWallpapersFromPool(used: HashMap<String, Int>) {
                val iterator = randomPool.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val useCount = used[entry.key] ?: 0
                    if (useCount >= entry.value) {
                        iterator.remove()
                    }
                }
                if (randomPool.isEmpty()) {
                    reloadRandomPool()
                }
            }

            fun syncSettings() {
                playlistData = WeEnginePrefs.readPlaylist(this@WEWallpaperService)
                // v0.8.3: the mode lives INSIDE the playlist JSON (official
                // model); the legacy pref is the fallback for old installs.
                var storedString = playlistData.mode.ifEmpty {
                    WeEnginePrefs.getStoredString(
                        this@WEWallpaperService, WallpaperProperties.PLAYLIST_MODE
                    ) ?: ""
                }
                if (storedString.isEmpty()) {
                    storedString = WallpaperProperties.PLAYLIST_MODE_RANDOM
                }
                mode = storedString
                duration = WeEnginePrefs.getStoredInt(
                    this@WEWallpaperService, WallpaperProperties.PLAYLIST_DURATION,
                    WallpaperProperties.PLAYLIST_DEFAULT_DURATION,
                ) * 60000
                randomPool.clear()
                fullSortedList.clear()
                timePlaylist.clear()
                var timeCursor = 0
                isValid = playlistData.wallpapers.size > 1
                when (mode) {
                    WallpaperProperties.PLAYLIST_MODE_RANDOM -> {
                        reloadRandomPool()
                        removeWallpapersFromPool(usedWallpapers)
                    }
                    WallpaperProperties.PLAYLIST_MODE_SORTED -> {
                        for (wallpaper in playlistData.wallpapers) {
                            fullSortedList.add(wallpaper.filepath)
                        }
                        sortedPosition = fullSortedList.indexOf(currentFile)
                        if (sortedPosition < 0) sortedPosition = 0
                    }
                    WallpaperProperties.PLAYLIST_MODE_DAY_OF_WEEK -> {
                        for (wallpaper in playlistData.wallpapers) {
                            fullSortedList.add(wallpaper.filepath)
                        }
                        sortedPosition = -1
                    }
                    WallpaperProperties.PLAYLIST_MODE_TIME_OF_DAY -> {
                        for (wallpaper in playlistData.wallpapers) {
                            val entry = TimeOfDayEntry()
                            entry.filepath = wallpaper.filepath
                            entry.startTime = timeCursor
                            timeCursor += wallpaper.duration
                            timePlaylist.add(entry)
                        }
                        if (sortedPosition < 0) sortedPosition = 1
                    }
                }
            }

            fun onWallpaperChangedExternally() {
                lastChangeTime = System.currentTimeMillis()
                if (currentFile != null) {
                    when (mode) {
                        WallpaperProperties.PLAYLIST_MODE_RANDOM -> {
                            if (randomPool.containsKey(currentFile)) {
                                val count = randomPool[currentFile] ?: 0
                                randomPool[currentFile!!] = count - 1
                            }
                        }
                        WallpaperProperties.PLAYLIST_MODE_SORTED -> {
                            sortedPosition = fullSortedList.indexOf(currentFile)
                            if (sortedPosition < 0) sortedPosition = 0
                        }
                        WallpaperProperties.PLAYLIST_MODE_DAY_OF_WEEK,
                        WallpaperProperties.PLAYLIST_MODE_TIME_OF_DAY,
                            -> {
                            sortedPosition = -1
                        }
                    }
                }
            }

            fun setActive(active: Boolean) {
                playlistActive = active
                if (isPreview) return
                handler.removeCallbacks(runnablePlaylist)
                if (active) {
                    playlistUpdate()
                    handler.postDelayed(runnablePlaylist, 1000L)
                }
            }

            fun isActive(): Boolean = playlistActive && isValid

            fun getTimeOfDayWallpaper(): String {
                val minutes = (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) * 60) +
                        Calendar.getInstance().get(Calendar.MINUTE)
                if (sortedPosition in 0 until timePlaylist.size) {
                    val current = timePlaylist[sortedPosition]
                    if (current.startTime > minutes ||
                        (sortedPosition + 1 < timePlaylist.size && timePlaylist[sortedPosition + 1].startTime <= minutes)
                    ) {
                        sortedPosition = -1
                    }
                }
                if (sortedPosition in 0 until timePlaylist.size) {
                    return timePlaylist[sortedPosition].filepath
                }
                for (i in timePlaylist.size - 1 downTo 0) {
                    if (timePlaylist[i].startTime <= minutes) {
                        return timePlaylist[i].filepath
                    }
                }
                return ""
            }

            fun getDayOfWeekWallpaper(): String {
                val calendar = Calendar.getInstance()
                val dayOfWeek = (((calendar.get(Calendar.DAY_OF_WEEK) + 7) -
                        (calendar.firstDayOfWeek - 1)) - 1) % 7
                if (sortedPosition in 0 until fullSortedList.size) {
                    val interval = WeEnginePrefs.computeDayOfWeekInterval(sortedPosition, fullSortedList.size)
                    if (dayOfWeek < interval.start || dayOfWeek >= interval.start + interval.duration) {
                        sortedPosition = -1
                    } else {
                        return fullSortedList[sortedPosition]
                    }
                }
                for (i in fullSortedList.indices) {
                    val interval = WeEnginePrefs.computeDayOfWeekInterval(i, fullSortedList.size)
                    if (dayOfWeek >= interval.start && dayOfWeek < interval.start + interval.duration) {
                        sortedPosition = i
                        return fullSortedList[i]
                    }
                }
                return ""
            }

            fun shouldUpdateWallpaper(): Boolean {
                if (!isActive() || isPreview) return false
                val currentTime = System.currentTimeMillis()
                return when (mode) {
                    WallpaperProperties.PLAYLIST_MODE_RANDOM,
                    WallpaperProperties.PLAYLIST_MODE_SORTED,
                    -> (currentTime - lastChangeTime) > duration

                    WallpaperProperties.PLAYLIST_MODE_TIME_OF_DAY -> true
                    WallpaperProperties.PLAYLIST_MODE_DAY_OF_WEEK -> true
                    else -> false
                }
            }

            fun getNewWallpaper(): String {
                when (mode) {
                    WallpaperProperties.PLAYLIST_MODE_RANDOM -> {
                        if (randomPool.isEmpty()) reloadRandomPool()
                        if (randomPool.isNotEmpty()) {
                            val keys = randomPool.keys.toList()
                            val selected = keys[ kotlin.random.Random.nextInt(keys.size) ]
                            usedWallpapers[selected] = (usedWallpapers[selected] ?: 0) + 1
                            return selected
                        }
                    }
                    WallpaperProperties.PLAYLIST_MODE_SORTED -> {
                        if (fullSortedList.isNotEmpty()) {
                            sortedPosition = (sortedPosition + 1) % fullSortedList.size
                            return fullSortedList[sortedPosition]
                        }
                    }
                    WallpaperProperties.PLAYLIST_MODE_TIME_OF_DAY -> {
                        val wallpaper = getTimeOfDayWallpaper()
                        if (wallpaper.isNotEmpty()) return wallpaper
                    }
                    WallpaperProperties.PLAYLIST_MODE_DAY_OF_WEEK -> {
                        val wallpaper = getDayOfWeekWallpaper()
                        if (wallpaper.isNotEmpty()) return wallpaper
                    }
                }
                return ""
            }
        }

        private fun playlistUpdate() {
            if (playlist.shouldUpdateWallpaper()) {
                val newWallpaper = playlist.getNewWallpaper()
                if (newWallpaper.isNotEmpty()) {
                    WeEnginePrefs.storeString(this@WEWallpaperService, WeEnginePrefs.PREF_KEY_SELECTED_WALLPAPER, newWallpaper)
                    reloadActiveWallpaper()
                }
            }
        }

        // ===== Wallpaper lifecycle =====

        fun reloadTouchEventsEnabled(enableTouch: Boolean) {
            areTouchEventsEnabled = enableTouch
            setTouchEventsEnabled(enableTouch)
        }

        fun refreshWallpaperColors() {
            if (isPreview) return
            val controls = wallpaperControls ?: return
            controls.requestBitmap { bitmap ->
                if (bitmap != null && bitmap.width > 8 && bitmap.height > 8) {
                    handler.post {
                        val scaled = Bitmap.createScaledBitmap(
                            bitmap, bitmap.width / 8, bitmap.height / 8, false,
                        )
                        synchronized(Companion) {
                            currentWallpaperColors = WallpaperColors.fromBitmap(scaled)
                        }
                        notifyColorsChanged()
                    }
                }
            }
        }

        fun refreshPlaylistSettings() {
            handler.post {
                playlist.syncSettings()
            }
        }

        fun onWallpaperChangedExternally() {
            handler.post {
                playlist.onWallpaperChangedExternally()
            }
        }

        fun reloadActiveWallpaper() {
            val storedString = WeEnginePrefs.getStoredString(
                this@WEWallpaperService, WeEnginePrefs.PREF_KEY_SELECTED_WALLPAPER
            )
            currentFile = storedString
            WepWawetLogger.engine("Service: reloadActiveWallpaper",
                "selected" to (storedString ?: "(none — nothing applied yet)"),
                "holder_ready" to (holder != null))
            if (storedString != null) {
                if (holder != null) {
                    loadWallpaper(storedString)
                } else {
                    pendingFile = storedString
                }
            }
        }

        fun reloadApplyGeneralSettings() {
            if (wallpaper != null) {
                val oldColorsApi = generalSettings.colorsApi
                generalSettings = WeEnginePrefs.readGeneralSettings(this@WEWallpaperService)
                lib.setLogToFileEnabled(generalSettings.logToFileEnabled)
                wallpaperControls?.setGeneralSettings(generalSettings)
                updatePausedState()
                if (oldColorsApi != generalSettings.colorsApi) {
                    notifyColorsChanged()
                }
            }
        }

        fun reloadApplyStoredProperties() {
            if (wallpaper != null) {
                val file = currentFile ?: return
                val wallpaperProperties = WeEnginePrefs.readWallpaperProperties(this@WEWallpaperService, file)
                if (wallpaperProperties != null) {
                    wallpaperControls?.applyWallpaperProperties(wallpaperProperties)
                    refreshWallpaperColors()
                }
            }
        }

        fun onConfigurationChanged() {
            if (wallpaper == null || isPreview) return
            wallpaperControls?.setScreenRotation(screenRotation)
        }

        private fun applyProperties(properties: HashMap<String, Property>) {
            if (wallpaper == null) return
            parallaxController.applyProperties(properties)
            if (properties.containsKey(WallpaperProperties.PROP_AUDIO_PROCESSING)) {
                val audioProcessing = WeEnginePrefs.anyToBoolean(
                    properties[WallpaperProperties.PROP_AUDIO_PROCESSING]?.value, false
                )
                if (audioProcessing) {
                    if (audioRecorder == null) {
                        val recorder = AudioRecorder()
                        audioRecorder = recorder
                        recorder.listener = this
                        recorder.startAudioListener()
                    }
                    return
                }
            }
            audioRecorder?.stopAudioListener()
            audioRecorder = null
        }

        override fun onAudioData(fft: FloatArray) {
            if (wallpaper == null) return
            wallpaperControls?.sendAudioData(fft)
        }

        fun loadWallpaper(file: String) {
            WepWawetLogger.engine("Service: loadWallpaper",
                "file" to file)
            audioRecorder?.stopAudioListener()
            audioRecorder = null

            val hadWallpaper = wallpaper != null
            if (hadWallpaper) {
                wallpaperControls?.onStopWallpaper()
                wallpaperControls?.onDestroyWallpaper()
                wallpaper = null
            }

            val wallpaperType = lib.getWallpaperType(file)
            WepWawetLogger.engine("Service: resolved wallpaper type",
                "file" to file,
                "type" to (wallpaperType ?: "null (local/SAF)"))
            if (wallpaperType == "Scene") {
                val sceneWallpaperView: SceneWallpaperView
                synchronized(Companion) {
                    WallpaperSurfaceHack.holderDelegate = holder
                    sceneWallpaperView = SceneWallpaperView(this@WEWallpaperService)
                    sceneWallpaperView.setCustomHandler(handler)
                }
                sceneWallpaperView.onCreated(lib)
                wallpaperControls = sceneWallpaperView
                wallpaper = sceneWallpaperView
            } else {
                val videoWallpaperView: VideoWallpaperView
                synchronized(Companion) {
                    WallpaperSurfaceHack.holderDelegate = holder
                    videoWallpaperView = VideoWallpaperView(this@WEWallpaperService)
                    videoWallpaperView.setCustomHandler(handler)
                    videoWallpaperView.setLocalPlayer(wallpaperType == null)
                }
                // v0.8.1 FIX (the blank/black wallpaper bug): the official
                // client calls setLib(this.lib) right here — without it the
                // video view can never resolve the .mpkg byte range for its
                // MediaPlayer (getWallpaperFileVirtualOffset needs the SceneLib
                // instance) and every package Video wallpaper renders black.
                videoWallpaperView.lib = lib
                wallpaperControls = videoWallpaperView
                wallpaper = videoWallpaperView
            }

            wallpaper?.setVisibility(GLSurfaceView.VISIBLE)
            parallaxController.linkWallpaper(wallpaperControls!!)
            wallpaperControls?.setLoadCallback {
                wallpaperControls?.let { controls ->
                    applyProperties(controls.getWallpaperProperties())
                }
                parallaxController.repeatLastInput()
                if (shouldBePaused()) {
                    parallaxController.pause()
                    wallpaperControls?.onPauseWallpaper()
                }
                handler.postDelayed({
                    powerSavingReceiver.onReceive(this@WEWallpaperService, null)
                    updatePausedState()
                }, 100L)
            }

            if (!isPreview) {
                wallpaperControls?.setScreenRotation(screenRotation)
            }
            reloadApplyGeneralSettings()
            wallpaperControls?.loadWallpaper(file)

            if (hadWallpaper) {
                val surfaceFrame = holder?.surfaceFrame
                if (surfaceFrame != null) {
                    val width = surfaceFrame.right - surfaceFrame.left
                    val height = surfaceFrame.bottom - surfaceFrame.top
                    val surfaceHolder = holder
                    if (surfaceHolder != null) {
                        wallpaper?.surfaceCreated(surfaceHolder)
                        wallpaper?.surfaceChanged(surfaceHolder, 3, width, height)
                    }
                }
            }

            refreshWallpaperColors()

            if (Build.VERSION.SDK_INT >= 31) {
                var maxFps = generalSettings.maxFps
                var frameRateCompatibility = 0
                if (wallpaperType != "Scene") {
                    var wallpaperFPS = WeEnginePrefs.getWallpaperFPS(this@WEWallpaperService, lib, file)
                    if (wallpaperFPS <= 0) {
                        wallpaperFPS = generalSettings.maxFps
                    }
                    maxFps = wallpaperFPS
                    frameRateCompatibility = 1
                }
                try {
                    val surface: Surface? = holder?.surface
                    if (surface != null) {
                        // Exactly the official call: setFrameRate(fps, compatibility, strategy=ALWAYS)
                        // where compatibility is 0 (DEFAULT) for scenes and 1 for videos.
                        surface.setFrameRate(maxFps.toFloat(), frameRateCompatibility, 1)
                    }
                } catch (t: Throwable) {
                }
            }
        }

        override fun onCreate(surfaceHolder: android.view.SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            // Safe here: the engine wrapper is attached before onCreate callbacks
            // fire (the official client also reads isPreview() in onCreate).
            WepWawetLogger.engine("WEWallpaperService: engine onCreate",
                "is_preview" to isPreview)
            if (!isPreview) {
                synchronized(Companion) {
                    instance = this@WEWallpaperService
                    currentEngine = this
                }
                refreshPlaylistSettings()
            }
            try {
                // Flag literal 4 = RECEIVER_NOT_EXPORTED on API 33+ (the official client
                // passes exactly this on every API level).
                registerReceiver(powerSavingReceiver, WeEnginePrefs.makePowerSavingIntentFilter(), 4)
            } catch (t: Throwable) {
            }
        }

        override fun onSurfaceCreated(holder: android.view.SurfaceHolder) {
            super.onSurfaceCreated(holder)
            this.holder = getSurfaceHolder()
            WepWawetLogger.engine("Service: wallpaper surface created",
                "surface_frame" to (this.holder?.surfaceFrame?.toString() ?: "null"))
            val pending = pendingFile
            if (pending != null) {
                loadWallpaper(pending)
                this.pendingFile = null
            } else {
                reloadActiveWallpaper()
            }
        }

        override fun onSurfaceDestroyed(holder: android.view.SurfaceHolder) {
            if (wallpaper != null) {
                wallpaperControls?.onStopWallpaper()
                wallpaperControls?.onDestroyWallpaper()
                wallpaper = null
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            try {
                unregisterReceiver(powerSavingReceiver)
            } catch (t: Throwable) {
            }
            super.onDestroy()
            if (wallpaper != null) {
                wallpaperControls?.onStopWallpaper()
                wallpaperControls?.onDestroyWallpaper()
                wallpaper = null
            }
            synchronized(Companion) {
                if (currentEngine == this) {
                    currentEngine = null
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            handler.post {
                currentlyVisible = visible
                updatePausedState()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            handler.post {
                if (wallpaper == null || !areTouchEventsEnabled) return@post
                val x = event.x
                val y = event.y
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        wallpaperControls?.sendTouchInput(x, y, true)
                    MotionEvent.ACTION_UP ->
                        wallpaperControls?.sendTouchInput(x, y, false)
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            handler.post {
                parallaxController.onOffsetsChanged(xOffset.coerceIn(0.0f, 1.0f))
            }
        }

        override fun onComputeColors(): WallpaperColors? {
            synchronized(Companion) {
                val colorsApi = generalSettings.colorsApi
                return when (colorsApi) {
                    "disabled" -> null
                    "", "enabled" -> currentWallpaperColors
                    else -> {
                        if (Build.VERSION.SDK_INT >= 31) {
                            var colorHint = 0
                            var fallback: Color = Color.valueOf(android.graphics.Color.BLACK)
                            when (colorsApi) {
                                "hintdark" -> {
                                    fallback = Color.valueOf(android.graphics.Color.WHITE)
                                    colorHint = WallpaperColors.HINT_SUPPORTS_DARK_TEXT
                                }
                                "hintdarktheme" -> {
                                    fallback = Color.valueOf(android.graphics.Color.WHITE)
                                    colorHint = WallpaperColors.HINT_SUPPORTS_DARK_THEME
                                }
                                "hintbright" -> {
                                    fallback = Color.valueOf(android.graphics.Color.BLACK)
                                    colorHint = 0
                                }
                            }
                            val primary = currentWallpaperColors?.primaryColor ?: fallback
                            val secondary = currentWallpaperColors?.secondaryColor
                            val tertiary = currentWallpaperColors?.tertiaryColor
                            if (secondary != null && tertiary != null) {
                                WallpaperColors(primary, secondary, tertiary, colorHint)
                            } else {
                                WallpaperColors(primary, null, null, colorHint)
                            }
                        } else {
                            currentWallpaperColors
                        }
                    }
                }
            }
        }
    }
}
