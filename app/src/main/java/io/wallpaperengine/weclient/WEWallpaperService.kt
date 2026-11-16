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
import kotlinx.coroutines.launch
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

    // ═══ v0.8.28: ONE ENGINE PER SCREEN ══════════════════════════════
    // Android creates one engine per screen our live wallpaper owns
    // (Engine.getWallpaperFlags(), API 34+: FLAG_SYSTEM = home,
    // FLAG_LOCK = lock). The old single currentEngine pointer meant the
    // second engine OVERWROTE the first and a destroy of either nulled
    // it while the other was still alive — requestWallpaperReload then
    // silently did nothing. Each screen's engine is tracked separately;
    // every apply reaches exactly the screens it targets.
    private var homeEngine: GLWallpaperEngine? = null
    private var lockEngine: GLWallpaperEngine? = null
    /** One engine serving BOTH screens (system-set "both" via one engine). */
    private var sharedEngine: GLWallpaperEngine? = null

    /** The engine that owns the playlist rotation state (home first). */
    private val rotationOwner: GLWallpaperEngine?
        get() = homeEngine ?: sharedEngine ?: lockEngine

    /**
     * v0.8.28: the ENGINE TOPOLOGY — the ground truth for every
     * per-screen decision. A "both at once" set gives ONE shared engine
     * (flags = SYSTEM|LOCK): one surface, both screens mirror it, and
     * different images per screen are IMPOSSIBLE through it. Setting the
     * live wallpaper separately on home and on lock gives one engine per
     * screen (the system's per-screen destinations, Android 14+).
     */
    enum class EngineTopology { NONE, SHARED, SEPARATE, HOME_ONLY, LOCK_ONLY }

    fun engineTopology(): EngineTopology = when {
        homeEngine != null && lockEngine != null -> EngineTopology.SEPARATE
        homeEngine != null -> EngineTopology.HOME_ONLY
        lockEngine != null -> EngineTopology.LOCK_ONLY
        sharedEngine != null -> EngineTopology.SHARED
        else -> EngineTopology.NONE
    }

    /** The topology as a plain string for logs and the UI. */
    fun engineTopologyLabel(): String = when (engineTopology()) {
        EngineTopology.SEPARATE -> "separate home+lock engines"
        EngineTopology.HOME_ONLY -> "home engine only"
        EngineTopology.LOCK_ONLY -> "lock engine only"
        EngineTopology.SHARED -> "ONE shared engine (both screens)"
        EngineTopology.NONE -> "no engine"
    }

    /** A snapshot of the engine flags — coverage decisions log this. */
    fun engineFlagsSnapshot(): String {
        return "home=${homeEngine?.rawWallpaperFlags() ?: "none"} " +
            "lock=${lockEngine?.rawWallpaperFlags() ?: "none"} " +
            "shared=${sharedEngine?.rawWallpaperFlags() ?: "none"} " +
            "topology=${engineTopologyLabel()}"
    }

    private fun allEngines(): List<GLWallpaperEngine> =
        listOfNotNull(homeEngine, lockEngine, sharedEngine)

    private fun attachEngine(e: GLWallpaperEngine, screen: String) {
        when (screen) {
            "lock" -> lockEngine = e
            "both" -> sharedEngine = e
            else -> homeEngine = e
        }
        e.screenLabel = screen
        WepWawetLogger.engine("Service: engine attached",
            "screen" to screen,
            "flags" to e.rawWallpaperFlags(),
            "home_engines" to (homeEngine != null),
            "lock_engines" to (lockEngine != null),
            "shared" to (sharedEngine != null),
            "topology" to engineTopologyLabel())
        // v0.8.28: coverage banners and per-screen rules re-evaluate the
        // moment the engine set changes.
        com.wepwawet.wallpaper.WallpaperRouter.notifyTopologyChanged()
    }

    private fun detachEngine(e: GLWallpaperEngine) {
        if (homeEngine === e) homeEngine = null
        if (lockEngine === e) lockEngine = null
        if (sharedEngine === e) sharedEngine = null
        WepWawetLogger.engine("Service: engine detached",
            "screen" to e.screenLabel,
            "remaining" to allEngines().size,
            "topology" to engineTopologyLabel())
        com.wepwawet.wallpaper.WallpaperRouter.notifyTopologyChanged()
    }

    /** Classify an engine by its wallpaper flags ("home"|"lock"|"both"). */
    private fun engineScreenOf(e: Engine): String {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            return try {
                when (e.getWallpaperFlags()) {
                    android.app.WallpaperManager.FLAG_LOCK -> "lock"
                    android.app.WallpaperManager.FLAG_SYSTEM or
                        android.app.WallpaperManager.FLAG_LOCK -> "both"
                    else -> "home"
                }
            } catch (t: Throwable) {
                WepWawetLogger.engineWarn("Service: getWallpaperFlags failed",
                    "exception" to (t.message ?: t.javaClass.name))
                "home"
            }
        }
        // Pre-34: no per-screen flags — the single engine serves home.
        return "home"
    }

    /**
     * v0.8.28: load a CANDIDATE path on the wanted screens WITHOUT
     * committing their selections. The router calls this first, and only
     * commits a screen's saved selection after its engine CONFIRMED the
     * load — a failed apply leaves the saved selection (and the screen)
     * exactly as they were.
     */
    suspend fun loadCandidateToEngines(
        path: String,
        wantHome: Boolean,
        wantLock: Boolean,
    ): Pair<Boolean, Boolean> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val homeNow = homeEngine
        val lockNow = lockEngine
        val sharedNow = sharedEngine
        var homeDef: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
        var lockDef: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
        if (wantHome) {
            homeDef = homeNow?.loadPathWithConfirm(path)
                ?: sharedNow?.loadPathWithConfirm(path)
        }
        if (wantLock) {
            lockDef = lockNow?.loadPathWithConfirm(path)
                ?: (if (homeNow == null) sharedNow?.loadPathWithConfirm(path) else null)
        }
        val homeOk = if (!wantHome) false else homeDef?.let {
            kotlinx.coroutines.withTimeoutOrNull(8000L) { it.await() }
        } ?: false
        val lockOk = if (!wantLock) false else lockDef?.let {
            kotlinx.coroutines.withTimeoutOrNull(8000L) { it.await() }
        } ?: false
        if (wantHome && homeDef == null && homeNow == null && sharedNow == null) {
            WepWawetLogger.engineError("Service: no HOME engine — is the live wallpaper set on home?",
                "path" to path.takeLast(56))
        }
        if (wantLock && lockDef == null && lockNow == null &&
            (homeNow != null || sharedNow == null)
        ) {
            WepWawetLogger.engineError("Service: no LOCK engine — set the live wallpaper on the lock screen separately",
                "path" to path.takeLast(56))
        }
        Pair(homeOk, lockOk)
    }

    /** Reload each wanted screen's engine with its SAVED selection. */
    suspend fun applySelectedToEngines(wantHome: Boolean, wantLock: Boolean): Pair<Boolean, Boolean> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            var homeDef: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
            var lockDef: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
            val homeNow = homeEngine
            val lockNow = lockEngine
            val sharedNow = sharedEngine
            if (wantHome) {
                homeDef = homeNow?.reloadActiveWallpaper()
                    ?: sharedNow?.reloadActiveWallpaper()
            }
            if (wantLock) {
                lockDef = lockNow?.reloadActiveWallpaper()
                    ?: (if (homeNow == null) sharedNow?.reloadActiveWallpaper() else null)
            }
            val homeOk = if (!wantHome) false else homeDef?.let {
                kotlinx.coroutines.withTimeoutOrNull(8000L) { it.await() }
            } ?: false
            val lockOk = if (!wantLock) false else lockDef?.let {
                kotlinx.coroutines.withTimeoutOrNull(8000L) { it.await() }
            } ?: false
            Pair(homeOk, lockOk)
        }

    /**
     * v0.8.28: restore one screen's PREVIOUS wallpaper after a failed
     * candidate (the failed load may have torn the old content down).
     * Best effort — the old selection is what goes back on screen.
     */
    fun restoreScreenSelection(screen: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            when (screen) {
                "lock" -> (lockEngine ?: sharedEngine)?.reloadActiveWallpaper()
                else -> (homeEngine ?: sharedEngine)?.reloadActiveWallpaper()
            }
        }
    }

    /**
     * v0.8.28: every ENGINE-side rotation (the playlist tick, the
     * activation switch, a random slot's fresh pull) applies through HERE
     * — the Home/Lock toggles decide which screens change; an off screen
     * keeps exactly its image. MUST run on the main looper.
     */
    fun applyPathToOnScreens(path: String) {
        val screens = com.wepwawet.discover.DiscoverPrefs.readScreens(this)
        val homeOn = com.wepwawet.discover.DiscoverPrefs.homeRequired(screens.first)
        val lockOn = com.wepwawet.discover.DiscoverPrefs.lockRequired(screens.second)
        if (!homeOn && !lockOn) {
            WepWawetLogger.engine("Service: rotation idle — both screens are off",
                "path" to path.takeLast(56))
            return
        }
        if (homeOn) WeEnginePrefs.writeSelectedForScreen(this, "home", path)
        if (lockOn) WeEnginePrefs.writeSelectedForScreen(this, "lock", path)
        stampRotationClock()
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            reloadEnginesFor(homeOn, lockOn)
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                reloadEnginesFor(homeOn, lockOn)
            }
        }
    }

    /** v0.8.28: stamp the rotation clock (the interval restarts on change). */
    fun stampRotationClock() {
        rotationOwner?.onWallpaperChangedExternally()
    }

    /** Ask each wanted screen's engine to load its (freshly set) selection. */
    private fun reloadEnginesFor(homeOn: Boolean, lockOn: Boolean) {
        if (homeOn && homeEngine == null && lockEngine == null && sharedEngine == null) {
            WepWawetLogger.engineWarn("Service: no engine attached — the live wallpaper is not active")
            return
        }
        if (homeOn) {
            val e = homeEngine ?: sharedEngine
            if (e == null) {
                WepWawetLogger.engineWarn("Service: HOME engine missing — is the live wallpaper set on home?")
            } else {
                e.reloadActiveWallpaper()
            }
        }
        if (lockOn) {
            val e = lockEngine ?: (if (homeEngine == null) sharedEngine else null)
            if (e == null) {
                WepWawetLogger.engineWarn("Service: LOCK engine missing — is the live wallpaper set on the lock screen?")
            } else {
                e.reloadActiveWallpaper()
            }
        }
    }

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
        for (e in allEngines()) e.onConfigurationChanged()
    }

    override fun onCreateEngine(): Engine {
        return GLWallpaperEngine()
    }

    val isLiveWallpaperActive: Boolean
        get() = allEngines().isNotEmpty()

    /**
     * v0.8.28: reload EVERY attached engine with its own screen's
     * selection (an engine already showing its selection is a no-op —
     * see the same-file skip in the engine's loadWallpaper).
     */
    fun requestWallpaperReload() {
        // v0.8.26 (THE SCREEN-ON-ROTATION FIX), still true: posted to the
        // MAIN looper — the wallpaper VIEW is a GLSurfaceView subclass and
        // a View constructor on a thread with no Looper throws.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            for (e in allEngines()) {
                e.reloadActiveWallpaper()
                e.onWallpaperChangedExternally()
            }
        }
    }

    fun requestWallpaperApplyGeneralProperties() {
        val generalSettings = WeEnginePrefs.readGeneralSettings(this)
        for (e in allEngines()) {
            e.reloadTouchEventsEnabled(generalSettings.touchInputEnabled)
            e.reloadApplyGeneralSettings()
        }
    }

    fun requestWallpaperApplyStoredProperties() {
        for (e in allEngines()) e.reloadApplyStoredProperties()
    }

    // ── v0.9.0 (rules engine): the small surface the RULES ENGINE drives.
    //  The state lives in the ENGINE (playlist, handler, controls); these
    //  are null-safe delegates so RuleEngine never touches the engine class.
    fun currentWallpaperFile(): String? = rotationOwner?.rulesCurrentFile()

    /** How long the current wallpaper has been showing, in minutes (rules). */
    fun currentWallpaperShownMinutes(): Long = rotationOwner?.rulesShownMinutes() ?: 0L

    /** v0.9.0 (rules): mute audio-responsive effects (the engine block). */
    @Volatile
    var rulesAudioMuted: Boolean = false

    /** Pause or resume the playlist rotation (rule action). */
    fun setRotationPaused(paused: Boolean) {
        rotationOwner?.rulesSetRotationPaused(paused)
    }

    /** v0.9.0 redesign: is the rotation paused by a rule right now? */
    fun isRotationPaused(): Boolean = rotationOwner?.rulesIsRotationPaused() ?: false

    /** Hold the current wallpaper for [minutes] (rule action). */
    fun holdWallpaperFor(minutes: Int) {
        rotationOwner?.rulesHoldFor(minutes * 60_000L)
    }

    /** Force one rotation NOW (rule action: restart playlist). */
    fun forcePlaylistRotation() {
        rotationOwner?.rulesForceRotation()
    }

    /** v0.9.0 redesign: is a rule hold currently blocking rotation? */
    fun isRotationHeld(): Boolean = rotationOwner?.rulesIsRotationHeld() ?: false

    /** v0.9.0 redesign: re-check the Limit scopes NOW (a condition event
     *  fired, or the rules were edited). Never rotates on its own — it
     *  only switches when the current wallpaper left the allowed set. */
    fun requestScopeReevaluation() {
        rotationOwner?.rulesReevaluateScopes()
    }

    /** v0.9.0 (rules): apply saved wallpaper properties (preset block) —
     *  every attached screen (each engine renders its own). */
    fun rulesApplyProperties(propertiesJson: String) {
        for (e in allEngines()) e.rulesApplyProperties(propertiesJson)
    }

    /** v0.9.0 (rules): the rotation owner's controls (preset capture). */
    fun rulesWallpaperControls(): io.wallpaperengine.weviews.WallpaperControls? =
        rotationOwner?.wallpaperControls

    /** v0.9.0 (rules): set the FPS limit (engine block) — every screen. */
    fun rulesSetFps(fps: Int) {
        for (e in allEngines()) e.rulesSetFps(fps)
    }

    /** v0.9.0 (rules): pause or resume the renderer (engine block) —
     *  every screen. */
    fun rulesSetRendererPaused(paused: Boolean) {
        for (e in allEngines()) e.rulesSetRendererPaused(paused)
    }

    fun requestPlaylistSettingsChanged() {
        // The rotation owner re-syncs its playlist; the other engines
        // only need their own screen's selection re-read.
        rotationOwner?.refreshPlaylistSettings()
        for (e in allEngines()) {
            if (e !== rotationOwner) e.reloadActiveWallpaper()
        }
    }

    fun getCurrentWallpaperFile(): String? = rotationOwner?.currentFile

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

        // ═══ v0.8.28 ═════════════════════════════════════════════════
        /** Which screen this engine renders ("home" | "lock" | "both"). */
        var screenLabel: String = "home"
            internal set

        /** The flags this engine was classified by (logging only). */
        fun rawWallpaperFlags(): Int = try {
            if (android.os.Build.VERSION.SDK_INT >= 34) getWallpaperFlags() else -1
        } catch (t: Throwable) { -1 }

        // Honest apply confirmations: a load reports success only when
        // the view actually finished loading the file.
        private var loadConfirm: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
        private var confirmPath: String? = null
        /** The path CONFIRMED rendered (drives the same-file no-op skip). */
        private var shownPath: String? = null

        private fun beginLoadConfirm(path: String): kotlinx.coroutines.CompletableDeferred<Boolean> {
            // A confirm already waiting on the SAME path is reused (the
            // surface wasn't ready; the pending load will answer it).
            val existing = loadConfirm
            if (existing != null && confirmPath == path && !existing.isCompleted) {
                return existing
            }
            existing?.complete(false) // superseded by a newer request
            val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
            loadConfirm = d
            confirmPath = path
            return d
        }

        private fun completeLoadConfirm(ok: Boolean) {
            val d = loadConfirm
            loadConfirm = null
            confirmPath = null
            if (ok) shownPath = currentFile else shownPath = null
            d?.complete(ok)
        }

        /**
         * v0.8.28 (THE FALSE SUCCESS): the view reported the content
         * FAILED (decode error, prepare error, unreadable file). The
         * pending confirmation completes FALSE — an apply never reports
         * OK for a file that is not on screen — and nothing is
         * considered shown anymore.
         */
        fun failLoadConfirm() {
            WepWawetLogger.engineError("Service: wallpaper load FAILED on the $screenLabel screen",
                "file" to (currentFile ?: "(none)").toString().takeLast(56))
            completeLoadConfirm(false)
        }
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
                // v0.9.0 (battery): SLEEP UNTIL the next moment work is
                // actually possible, instead of polling every 1-5 seconds
                // forever. The old fixed-interval polling woke the CPU
                // 12-60 times/minute just to re-check a gate that almost
                // always said "not yet". The next moment is the EARLIEST of
                // the next interval rotation, the next suite time-rule
                // boundary, and the self-heal cap (see nextWakeDelayMs).
                handler.postDelayed(this, playlist.nextWakeDelayMs())
            }
        }

        init {
            generalSettings = WeEnginePrefs.readGeneralSettings(this@WEWallpaperService)
            if (generalSettings.touchInputEnabled) {
                setTouchEventsEnabled(true)
                areTouchEventsEnabled = true
            }
            lib.initLibrary(this@WEWallpaperService)
            // v0.8.6: re-enable the engine's own log-to-file — the native
            // engine (libscenejni.so) writes shader compile errors, texture
            // load failures and scene-init diagnostics to
            // filesDir/logs/applog.txt when this is on. WepWawetLogger's
            // EngineLogTailer watches that file and feeds new lines into
            // the main ring buffer as ENGINE-domain entries, so the engine's
            // logs appear automatically in the in-app Logs screen (the
            // user's explicit request: "no save to log because it should
            // automatically be in the main log"). The previous v0.8.5
            // change disabled this and lost all engine-side diagnostics.
            // v0.8.8: create filesDir/logs/ FIRST — the engine does not
            // create the parent directory itself, and without it every
            // engine log write silently failed (see
            // EngineLibrary.ensureEngineLogDir).
            com.wepwawet.engine.EngineLibrary.ensureEngineLogDir(this@WEWallpaperService)
            lib.setLogToFileEnabled(true)
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
            // v0.8.28: ONLY the rotation owner runs the playlist tick —
            // with a home engine AND a lock engine, ONE logical rotation
            // drives both screens (through applyPathToOnScreens); two
            // independent ticks would race and double-rotate.
            val isOwner = this@WEWallpaperService.rotationOwner === this
            playlist.setActive(!shouldPause && isOwner)
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

            // ── v0.8.11: WallpaperSuite multi-playlist ──
            // When a suite playlist is ACTIVE it drives the rotation
            // INSTEAD of the official single playlist: random/ordered
            // order, an interval, and time rules that pin a wallpaper
            // (or a group) to a window of the day.
            private var suiteMode: Boolean = false
            private var suiteEntries: List<String> = emptyList()
            private var suiteOrderRandom: Boolean = true
            private var suitePlaylist: com.wepwawet.playlist.SuitePlaylistStore.Playlist? = null

            // v0.8.15: the ACTIVE suite playlist id as of the last
            // syncSettings — lets syncSettingsAndApplyChanges detect an
            // activation switch (null until the first sync so a fresh
            // engine never counts as a switch).
            private var lastSyncedActiveId: String? = null

            // v0.9.0 (rules): a rule can PAUSE the rotation outright
            // ("pause rotation / resume rotation" blocks). While paused the
            // tick keeps waking (so a resume applies instantly) but never
            // rotates.
            @Volatile
            var rotationPaused: Boolean = false

            // v0.8.20 (all buttons live): a fingerprint of every
            // rotation-relevant field of the last synced playlist — any
            // difference means an in-place edit that must apply now.
            private var lastSyncedPlaylistFingerprint: String = ""

            /**
             * v0.8.15: sync + LIVE apply. Called whenever the store changes
             * while the service runs (requestPlaylistSettingsChanged).
             * When the ACTIVE playlist changed, the new playlist's next
             * wallpaper is applied IMMEDIATELY — previously the switch
             * waited for the 5-second tick's interval gate, and ticks are
             * paused while the app is foregrounded, so toggling a playlist
             * inside the app changed nothing until the user went home
             * ("the button to enable a playlist doesnt update live").
             */
            fun syncSettingsAndApplyChanges() {
                val prevActiveId = lastSyncedActiveId
                val prevFingerprint = lastSyncedPlaylistFingerprint
                syncSettings()
                if (isPreview) return
                if (!suiteMode) return
                // v0.8.20 (all buttons live): apply IMMEDIATELY on ANY
                // meaningful change to the ACTIVE playlist — not just an
                // activation switch. The old gate (active-id change only)
                // left every in-place edit (random slots added/removed,
                // order mode, interval, entries, time rules) waiting for
                // the next interval tick, and ticks pause while the app is
                // foregrounded — so nothing ever changed until the user
                // left the app ("the random button in the playlist menu is
                // not live. all buttons must be live"). The fingerprint
                // covers every rotation-relevant field; the INTERVAL is
                // synced by syncSuiteSettings but deliberately excluded —
                // it only steers future ticks, and including it made every
                // slider finish rotate the wallpaper.
                val changed =
                    lastSyncedActiveId != prevActiveId || lastSyncedPlaylistFingerprint != prevFingerprint
                if (changed && prevActiveId != null) {
                    val next = suitePickNew()
                    if (next.isNotEmpty()) {
                        // v0.8.28: the toggles-aware apply — only ON
                        // screens change; the rotation stamps itself.
                        this@WEWallpaperService.applyPathToOnScreens(next)
                        WepWawetLogger.engine("Service: playlist change — applied now",
                            "file" to next.takeLast(60))
                    }
                }
            }

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
                val suiteActive =
                    com.wepwawet.playlist.SuitePlaylistStore.activePlaylist(this@WEWallpaperService)
                // v0.8.21: random slots ARE entries (unique markers), so
                // a plain entries check covers slot-only playlists too (the
                // old gate meant enabling the random slot on an empty
                // playlist never activated the rotation at all).
                if (suiteActive != null && suiteActive.entries.isNotEmpty()) {
                    lastSyncedActiveId = suiteActive.id
                    syncSuiteSettings(suiteActive)
                    return
                }
                lastSyncedActiveId = ""
                suiteMode = false
                suitePlaylist = null
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

            /**
             * v0.8.11: load the ACTIVE suite playlist into the runtime
             * fields. The official single-playlist structures are cleared
             * so no stale state leaks between modes.
             */
            private fun syncSuiteSettings(playlist: com.wepwawet.playlist.SuitePlaylistStore.Playlist) {
                suiteMode = true
                suitePlaylist = playlist
                // v0.8.21: random slots ARE entries (unique markers) — the
                // candidate list needs no synthetic extras any more.
                suiteEntries = playlist.entries.map { it.filepath }
                suiteOrderRandom =
                    playlist.orderMode == com.wepwawet.playlist.SuitePlaylistStore.ORDER_RANDOM
                // v0.9.0 redesign: NO duration rotation for suite playlists.
                // The playlist changes ONLY when a rule advances it (the
                // "Every N" trigger → Next wallpaper block, delivered by
                // the rules engine) — a playlist with no automations never
                // changes by itself. duration stays for the official
                // single-playlist mode only; here it is parked at "never".
                duration = Int.MAX_VALUE
                suiteHoldUntil = 0L
                suiteRandomPullInFlight = false
                isValid = suiteEntries.isNotEmpty()
                // v0.8.20/21 (all buttons live): the fingerprint of every
                // rotation-relevant field — any in-place entry edit changes
                // it and syncSettingsAndApplyChanges applies immediately.
                // RULES are deliberately NOT in the fingerprint: changing
                // a trigger or scope must not itself rotate the wallpaper
                // (the spec is explicit); scopes are re-evaluated separately
                // by reevaluateScopes().
                lastSyncedPlaylistFingerprint =
                    "${playlist.id}|${playlist.orderMode}|" +
                        playlist.entries.joinToString(";") { it.toString() }
                randomPool.clear()
                fullSortedList.clear()
                timePlaylist.clear()
                fullSortedList.addAll(suiteEntries)
                sortedPosition = suiteEntries.indexOf(currentFile).let { if (it < 0) 0 else it }
                WepWawetLogger.engine(
                    "Service: suite playlist active",
                    "name" to playlist.name,
                    "entries" to suiteEntries.size,
                    "order" to playlist.orderMode,
                    "random_slots" to suiteEntries.count {
                        com.wepwawet.playlist.RandomPullEngine.isSlot(it)
                    })
            }

            // ── v0.8.19/21: the dynamic random-slot state ──
            private var suiteRandomPullInFlight: Boolean = false

            /**
             * v0.8.19/21: pick + apply the next wallpaper for the current
             * step. Random-slot entries (unique markers) participate as
             * ordinary candidates. Returns the picked file path, or "" when
             * the pick is a RANDOM SLOT whose fresh image is still
             * downloading — the caller then keeps the current wallpaper (the
             * pull completes asynchronously and applies itself).
             */
            private fun suitePickNewAndMaybePull(): String {
                val set = suiteSetForNow()
                if (set.isEmpty()) return ""
                val candidates = ArrayList<String>(set.size)
                candidates.addAll(set)
                if (candidates.size == 1) {
                    return applyCandidate(candidates[0])
                }
                val currentIndex = candidates.indexOf(currentFile)
                val picked = when {
                    suiteOrderRandom -> {
                        val pool = candidates.filter { it != currentFile }
                        if (pool.isEmpty()) candidates[0]
                        else pool[kotlin.random.Random.nextInt(pool.size)]
                    }
                    else -> {
                        val next = if (currentIndex >= 0) (currentIndex + 1) % candidates.size else 0
                        candidates[next]
                    }
                }
                return applyCandidate(picked)
            }

            /** Apply one candidate: a plain entry loads directly; a slot pulls. */
            private fun applyCandidate(candidate: String): String {
                if (!com.wepwawet.playlist.RandomPullEngine.isSlot(candidate)) {
                    return candidate
                }
                // A RANDOM SLOT: pull a fresh image NOW through THIS slot's
                // own settings (single-flight — a pull already in flight
                // keeps the current wallpaper).
                if (suiteRandomPullInFlight) return ""
                val playlist = suitePlaylist ?: return ""
                // Markers are unique, so the entry found is THE slot picked.
                val slot = playlist.entries.firstOrNull { it.filepath == candidate }
                    ?: return ""
                val svc = this@WEWallpaperService
                suiteRandomPullInFlight = true
                WepWawetLogger.engine("Service: random slot — pulling a fresh image",
                    "playlist" to playlist.name,
                    "slot" to candidate.takeLast(18))
                com.wepwawet.WepWawetApplication.appScope.launch {
                    try {
                        val path = com.wepwawet.playlist.RandomPullEngine
                            .pull(svc, playlist, slot)
                        if (path != null) {
                            // v0.8.28: toggles-aware apply (writes the
                            // per-screen selections + reloads + stamps).
                            svc.applyPathToOnScreens(path)
                        }
                    } finally {
                        suiteRandomPullInFlight = false
                    }
                }
                return ""
            }

            /**
             * v0.9.0 redesign: the rotation set — the FIRST matching LIMIT
             * SCOPE, or the whole playlist when none matches.
             *
             * A scope is a rule of THIS playlist that contains a Limit
             * block ("Only use these wallpapers while…"). Its condition is
             * the rule's GATE (evaluated live by RuleEngine.evalCond — a
             * state, not an event). Scopes are checked in list order; the
             * first whose gate holds wins; when none hold, the whole
             * playlist is the set. Paths not in the playlist any more are
             * dropped; group blocks resolve to their live members.
             */
            private fun suiteSetForNow(): List<String> {
                val playlist = suitePlaylist ?: return emptyList()
                val source = scopePathsForNow(playlist) ?: suiteEntries
                // v0.9.0: GROUP BLOCKS — a "group://block/<name>" marker
                // resolves to the group's CURRENT members in the block's
                // order (manual/newest/oldest/az/random), so a rotation that
                // lands on the block picks from inside it, and later group
                // edits flow into the playlist automatically.
                if (source.none { com.wepwawet.playlist.GroupBlocks.isGroupBlock(it) }) {
                    return source
                }
                val out = ArrayList<String>(source.size * 2)
                for (path in source) {
                    if (!com.wepwawet.playlist.GroupBlocks.isGroupBlock(path)) {
                        out.add(path)
                        continue
                    }
                    val entry = playlist.entries.firstOrNull { it.filepath == path }
                        ?: continue
                    out.addAll(com.wepwawet.playlist.GroupBlocks
                        .resolve(this@WEWallpaperService, entry).paths)
                }
                return out
            }

            /** The first matching scope's paths, or null when none matches. */
            private fun scopePathsForNow(
                playlist: com.wepwawet.playlist.SuitePlaylistStore.Playlist,
            ): List<String>? {
                val ruleSet = com.wepwawet.rules.SuiteRulesStore
                    .readPlaylistRules(this@WEWallpaperService, playlist.id)
                val memberPaths = playlist.entries.map { it.filepath }.toHashSet()
                for (rule in ruleSet.rules) {
                    if (!rule.enabled) continue
                    val limit = rule.actions.firstOrNull {
                        it.type == com.wepwawet.rules.RuleModel.BLOCK_LIMIT
                    } ?: continue
                    val gate = rule.gate
                    if (gate != null && !com.wepwawet.rules.RuleEngine.evalCond(
                            this@WEWallpaperService, playlist.id, gate, null)
                    ) continue
                    val paths = limit.paths.filter { it in memberPaths }
                    if (paths.isNotEmpty()) return paths
                }
                return null
            }

            /**
             * v0.9.0 redesign: re-evaluate the scopes and switch ONLY when
             * the current wallpaper just left the allowed set (a boundary
             * crossing or a condition event). Never rotates on its own.
             */
            fun reevaluateScopes() {
                if (!suiteMode || isPreview) return
                if (rotationPaused || System.currentTimeMillis() < suiteHoldUntil) return
                playlistUpdate()
            }

            /** Pick the next wallpaper inside the current set per the order mode. */
            private fun suitePickNew(): String {
                return suitePickNewAndMaybePull()
            }

            /**
             * v0.9.0 (rules): hold the current wallpaper for [ms].
             * Official mode: the gate `now - lastChangeTime > duration`
             * only passes once the hold elapses (lastChangeTime set to
             * `now + ms - duration`). Suite mode: duration is parked at
             * Int.MAX_VALUE (no duration rotation) and the hold is its own
             * [suiteHoldUntil] timestamp the scope switch and the rules
             * engine's Next-step both respect.
             */
            @Volatile
            private var suiteHoldUntil: Long = 0L

            fun holdFor(ms: Long) {
                if (suiteMode) {
                    suiteHoldUntil = System.currentTimeMillis() + ms
                } else {
                    lastChangeTime = System.currentTimeMillis() + ms - duration
                }
            }

            /** v0.9.0 redesign: is a rule hold currently active? */
            fun isRotationHeld(): Boolean =
                System.currentTimeMillis() < suiteHoldUntil

            /** v0.9.0 (rules): read-only views for the rules engine. */
            fun rulesCurrentFile(): String? = currentFile

            fun rulesShownMinutes(): Long =
                (System.currentTimeMillis() - lastChangeTime) / 60_000L

            fun onWallpaperChangedExternally() {
                lastChangeTime = System.currentTimeMillis()
                currentFile?.let { file ->
                    when (mode) {
                        WallpaperProperties.PLAYLIST_MODE_RANDOM -> {
                            if (randomPool.containsKey(file)) {
                                val count = randomPool[file] ?: 0
                                randomPool[file] = count - 1
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
                // v0.8.18 (review B25): ONE Calendar instance — the old two
                // getInstance() calls could straddle an hour boundary (the
                // 5-second tick makes that possible at the top of any hour),
                // producing a minute-of-day off by up to 60 minutes for one
                // tick. Same single-instance pattern getDayOfWeekWallpaper
                // below and SuitePlaylistStore.currentMinuteOfDay already use.
                val cal = Calendar.getInstance()
                val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 +
                        cal.get(Calendar.MINUTE)
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

            /** v0.9.0: the self-heal cap for [nextWakeDelayMs] — clocks
             *  shift (DST, manual time, timezone), playlists are edited
             *  while the tick sleeps, and modes change; one re-check at this
             *  granularity is still 1000x fewer wakes than the old poll. */
            private val WAKE_SELF_HEAL_MS = 10 * 60_000L

            /**
             * v0.9.0 (battery): how long the playlist tick may SLEEP before
             * it is next needed — the earliest of:
             *  • the next interval rotation (duration since the last
             *    change),
             *  • the next suite time-rule window boundary (a boundary can
             *    hand the rotation to a different entry set — the tick must
             *    be awake for it),
             *  • the self-heal cap [WAKE_SELF_HEAL_MS].
             * Floored at 1s (a due rotation can never busy-loop) and capped
             * so a sleeping tick still self-heals.
             */
            fun nextWakeDelayMs(): Long {
                val now = System.currentTimeMillis()
                var wakeAt = now + WAKE_SELF_HEAL_MS
                if (!suiteMode) {
                    // Official single playlist: the interval rotation still
                    // rules the wake schedule.
                    val rotationIn = duration - (now - lastChangeTime)
                    if (rotationIn > 0 && now + rotationIn < wakeAt) {
                        wakeAt = now + rotationIn
                    }
                } else {
                    // v0.9.0 redesign: suite playlists wake only at the
                    // next SCOPE time boundary (a window start/end that can
                    // hand the rotation to a different entry set). Scopes
                    // with non-time conditions re-evaluate when the trigger
                    // hub reports their event — never by polling. Interval
                    // advancement needs no wake: the hub's own computed
                    // schedule fires the rules.
                    val pl = suitePlaylist
                    if (pl != null) {
                        val boundary = nextScopeBoundaryMs(pl, now)
                        if (boundary != null && boundary < wakeAt) wakeAt = boundary
                    }
                }
                return (wakeAt - now).coerceIn(1_000L, WAKE_SELF_HEAL_MS)
            }

            /**
             * The next minute-of-day boundary (start or end, wrapping past
             * midnight) across every TIME-RANGE condition of this
             * playlist's LIMIT scopes — the moments the allowed set can
             * change on the clock. Full-day windows (start == end) never
             * cross a boundary.
             */
            private fun nextScopeBoundaryMs(
                playlist: com.wepwawet.playlist.SuitePlaylistStore.Playlist,
                now: Long,
            ): Long? {
                val ruleSet = com.wepwawet.rules.SuiteRulesStore
                    .readPlaylistRules(this@WEWallpaperService, playlist.id)
                var best: Long? = null
                for (rule in ruleSet.rules) {
                    if (!rule.enabled) continue
                    if (rule.actions.none {
                            it.type == com.wepwawet.rules.RuleModel.BLOCK_LIMIT
                        }) continue
                    for (minute in scopeBoundaryMinutes(rule.gate)) {
                        val at = nextMinuteOfDayOccurrenceMs(minute, now)
                        if (best == null || at < best) best = at
                    }
                }
                return best
            }

            /** The start/end minutes of every time-range cond in the tree. */
            private fun scopeBoundaryMinutes(cond: com.wepwawet.rules.RuleModel.Cond?): IntArray {
                if (cond == null) return IntArray(0)
                val out = ArrayList<Int>()
                if (cond.type == com.wepwawet.rules.RuleModel.COND_TIME_RANGE &&
                    cond.minuteStart != cond.minuteEnd
                ) {
                    out.add(cond.minuteStart)
                    out.add(cond.minuteEnd)
                }
                for (child in cond.children) {
                    out.addAll(scopeBoundaryMinutes(child).toList())
                }
                return out.toIntArray()
            }

            /** The next local occurrence of a minute-of-day, 250ms in. */
            private fun nextMinuteOfDayOccurrenceMs(minuteOfDay: Int, now: Long): Long {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = now
                cal.set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                cal.set(java.util.Calendar.MINUTE, minuteOfDay % 60)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 250)
                if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }

            fun shouldUpdateWallpaper(): Boolean {
                if (!isActive() || isPreview) return false
                // v0.9.0 (rules): a paused rotation never rotates.
                if (rotationPaused) return false
                if (suiteMode) {
                    // v0.9.0 redesign: NO duration rotation — the playlist
                    // advances ONLY when a rule's Next-wallpaper block runs
                    // (the "Every N" trigger). The tick's one job here is
                    // SCOPE ENFORCEMENT: a boundary or condition change that
                    // no longer allows the current wallpaper switches it
                    // immediately (into the new scope's set).
                    val set = suiteSetForNow()
                    if (set.isEmpty()) return false
                    val current = currentFile
                    if (current != null && current !in set &&
                        !com.wepwawet.playlist.RandomPullEngine.isSlotFile(current)
                    ) return true
                    return false
                }
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
                if (suiteMode) return suitePickNew()
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
                    // v0.8.28: the toggles-aware apply (writes the
                    // per-screen selections of ON screens, reloads their
                    // engines, and stamps the rotation clock itself).
                    this@WEWallpaperService.applyPathToOnScreens(newWallpaper)
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

        // ── v0.9.0 (rules engine): the engine-level halves of the rule
        // actions. The service-level delegates above call these; everything
        // (playlist, handler, controls) lives right here.
        fun rulesCurrentFile(): String? = playlist.rulesCurrentFile()

        fun rulesShownMinutes(): Long = playlist.rulesShownMinutes()

        fun rulesSetRotationPaused(paused: Boolean) {
            handler.post {
                playlist.rotationPaused = paused
                WepWawetLogger.engine(
                    "Service: rotation " + (if (paused) "paused" else "resumed") + " by rule")
                if (!paused) playlistUpdate()
            }
        }

        fun rulesHoldFor(ms: Long) {
            handler.post {
                playlist.holdFor(ms)
                WepWawetLogger.engine("Service: wallpaper held by rule",
                    "minutes" to (ms / 60_000L))
            }
        }

        fun rulesForceRotation() {
            handler.post {
                playlist.syncSettingsAndApplyChanges()
                playlistUpdate()
            }
        }

        fun rulesIsRotationHeld(): Boolean = playlist.isRotationHeld()

        fun rulesIsRotationPaused(): Boolean = playlist.rotationPaused

        fun rulesReevaluateScopes() {
            handler.post { playlist.reevaluateScopes() }
        }

        fun rulesApplyProperties(propertiesJson: String) {
            handler.post {
                wallpaperControls?.applyWallpaperProperties(propertiesJson)
            }
        }

        fun rulesSetFps(fps: Int) {
            handler.post {
                val info = io.wallpaperengine.wedata.GeneralSettingsInfo()
                info.maxFps = fps
                wallpaperControls?.setGeneralSettings(info)
            }
        }

        fun rulesSetRendererPaused(paused: Boolean) {
            handler.post {
                if (paused) wallpaperControls?.onPauseWallpaper()
                else wallpaperControls?.onResumeWallpaper()
            }
        }

        fun refreshPlaylistSettings() {
            handler.post {
                // v0.8.15: syncSettingsAndApplyChanges — activating a
                // playlist now switches the wallpaper immediately instead
                // of waiting for a tick (which is paused while the app is
                // foregrounded).
                playlist.syncSettingsAndApplyChanges()
            }
        }

        fun onWallpaperChangedExternally() {
            handler.post {
                playlist.onWallpaperChangedExternally()
            }
        }

        /**
         * v0.8.19 (random-pull rework): the freshly pulled random-slot
         * image was written as the selected wallpaper — reload + load it
         * (the same reloadActiveWallpaper path playlistUpdate uses).
         */
        fun playlistReloadAfterExternalChange() {
            // v0.8.26: same View-thread rule as requestWallpaperReload —
            // post the load; callers may be coroutine workers.
            handler.post { reloadActiveWallpaper() }
        }

        /**
         * v0.8.28: load an explicit CANDIDATE path on THIS engine and
         * confirm it — the selection pref is untouched (the router
         * commits it only after a confirmed load).
         */
        fun loadPathWithConfirm(path: String): kotlinx.coroutines.CompletableDeferred<Boolean> {
            if (path == shownPath && wallpaper != null) {
                WepWawetLogger.engine("Service: candidate already showing — confirmed no-op",
                    "file" to path.takeLast(56),
                    "screen" to screenLabel)
                val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
                d.complete(true)
                return d
            }
            val confirm = beginLoadConfirm(path)
            if (holder == null) {
                pendingFile = path
            } else {
                loadWallpaper(path)
            }
            return confirm
        }

        /**
         * Load THIS engine's screen selection. Returns a confirmation the
         * router awaits: true once the view actually shows the file
         * (instantly true when it is already showing — the same-file
         * no-op), false when there is nothing to show.
         */
        fun reloadActiveWallpaper(): kotlinx.coroutines.CompletableDeferred<Boolean> {
            val myScreenKey = if (screenLabel == "lock") "lock" else "home"
            val storedString = WeEnginePrefs.readSelectedForScreen(this@WEWallpaperService, myScreenKey)
            currentFile = storedString
            WepWawetLogger.engine("Service: reloadActiveWallpaper",
                "screen" to screenLabel,
                "selected" to (storedString ?: "(none — nothing applied yet)"),
                "holder_ready" to (holder != null))
            if (storedString == null) {
                val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
                d.complete(false)
                return d
            }
            return if (holder != null) {
                loadWallpaperWithConfirm(storedString)
            } else {
                pendingFile = storedString
                beginLoadConfirm(storedString)
            }
        }

        fun reloadApplyGeneralSettings() {
            if (wallpaper != null) {
                val oldColorsApi = generalSettings.colorsApi
                generalSettings = WeEnginePrefs.readGeneralSettings(this@WEWallpaperService)
                // v0.8.6: keep the engine log file enabled (see onCreate).
                // v0.8.8: ensure filesDir/logs/ exists (see onCreate note).
                com.wepwawet.engine.EngineLibrary.ensureEngineLogDir(this@WEWallpaperService)
                lib.setLogToFileEnabled(true)
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
            // v0.9.0 (rules): the "mute audio-responsive effects" block —
            // a muted wallpaper keeps rendering, it just stops reacting.
            if (rulesAudioMuted) return
            wallpaperControls?.sendAudioData(fft)
        }

        /**
         * v0.8.28 (SMOOTH CHANGES):
         *  • the same path that is already SHOWING is a confirmed no-op
         *    (no reload, no flash — this kills the wake + rule double
         *    load);
         *  • a reload of the same KIND (image↔image, image↔video, scene↔
         *    scene) reuses the EXISTING view — no teardown, the view
         *    swaps its content and cross-fades (VideoWallpaperView fades
         *    every reload; a scene keeps rendering until the new scene's
         *    first frame);
         *  • a KIND change builds the new view but the OLD one keeps its
         *    last frame on the surface until the new one finished loading
         *    — no blank frame between destroy and load ever again.
         */
        private fun loadWallpaperWithConfirm(file: String): kotlinx.coroutines.CompletableDeferred<Boolean> {
            if (file == shownPath && wallpaper != null) {
                WepWawetLogger.engine("Service: loadWallpaper skipped — already showing",
                    "file" to file.takeLast(56),
                    "screen" to screenLabel)
                val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
                d.complete(true)
                return d
            }
            val confirm = beginLoadConfirm(file)
            loadWallpaper(file)
            return confirm
        }

        fun loadWallpaper(file: String) {
            WepWawetLogger.engine("Service: loadWallpaper",
                "file" to file,
                "screen" to screenLabel)
            audioRecorder?.stopAudioListener()
            audioRecorder = null

            val wallpaperType = lib.getWallpaperType(file)
            WepWawetLogger.engine("Service: resolved wallpaper type",
                "file" to file,
                "type" to (wallpaperType ?: "null (local/SAF)"))

            val existing = wallpaper
            val existingIsVideoView = existing is VideoWallpaperView
            val existingIsSceneView = existing is SceneWallpaperView
            val newIsScene = wallpaperType == "Scene"
            val sameKind =
                (newIsScene && existingIsSceneView) || (!newIsScene && existingIsVideoView)

            // The load-callback: content READY. Confirms the load and, on
            // a kind change, releases the retired view (its last frame
            // stayed on the surface the whole time).
            val retired: WallpaperControls? = if (sameKind) null else existing?.let { it as WallpaperControls }
            val onLoaded = Runnable {
                wallpaperControls?.let { controls ->
                    applyProperties(controls.getWallpaperProperties())
                }
                parallaxController.repeatLastInput()
                if (shouldBePaused()) {
                    parallaxController.pause()
                    wallpaperControls?.onPauseWallpaper()
                }
                retired?.onStopWallpaper()
                retired?.onDestroyWallpaper()
                completeLoadConfirm(true)
                handler.postDelayed({
                    powerSavingReceiver.onReceive(this@WEWallpaperService, null)
                    updatePausedState()
                }, 100L)
            }

            if (sameKind && existing != null) {
                // SAME-VIEW reload: no rebuild, no re-attach — the view
                // swaps its content internally and cross-fades.
                currentFile = file
                wallpaperControls?.setLoadCallback(onLoaded)
                wallpaperControls?.setLoadFailedCallback {
                    this@GLWallpaperEngine.failLoadConfirm()
                }
                wallpaperControls?.loadWallpaper(file)
                refreshWallpaperColors()
                return
            }

            currentFile = file
            if (newIsScene) {
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
            wallpaperControls?.let { parallaxController.linkWallpaper(it) }
            wallpaperControls?.setLoadCallback(onLoaded)
            // v0.8.28: content failures (decode/prepare/unreadable)
            // confirm FALSE — never a silent success again.
            wallpaperControls?.setLoadFailedCallback {
                this@GLWallpaperEngine.failLoadConfirm()
            }

            if (!isPreview) {
                wallpaperControls?.setScreenRotation(screenRotation)
            }
            reloadApplyGeneralSettings()
            wallpaperControls?.loadWallpaper(file)

            // A kind change REPLACED the view: attach the new one to the
            // live surface (the retired view above kept the screen filled
            // until the new view's content is ready).
            if (retired != null) {
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
                // v0.8.28: probe the file's own FPS ONLY for real videos —
                // the old "not a scene" check made every IMAGE take the
                // video path and log a getWallpaperFPS failure warning at
                // every startup. Images/GIFs use the general limit with
                // scene-like compatibility.
                val contentKind = io.wallpaperengine.weutil.WallpaperFile.sniff(
                    this@WEWallpaperService, file)
                if (wallpaperType != "Scene" &&
                    contentKind == io.wallpaperengine.weutil.WallpaperFile.Kind.VIDEO
                ) {
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
                    WepWawetLogger.engineWarn("Service: setFrameRate failed",
                        "exception" to (t.message ?: t.javaClass.name))
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
                }
                // v0.8.28: classify THIS engine by its screen and track
                // it separately — the second engine no longer overwrites
                // the first, and neither destroy nulls the other.
                val screen = this@WEWallpaperService.engineScreenOf(this)
                this@WEWallpaperService.attachEngine(this, screen)
                refreshPlaylistSettings()
            }
            try {
                // Flag literal 4 = RECEIVER_NOT_EXPORTED on API 33+ (the official client
                // passes exactly this on every API level).
                registerReceiver(powerSavingReceiver, WeEnginePrefs.makePowerSavingIntentFilter(), 4)
            } catch (t: Throwable) {
                WepWawetLogger.appWarn("weEngine: onCreate — onCreate failed (ignored)",
                    "exception" to (t.message ?: t.javaClass.name))
            }
        }

        override fun onSurfaceCreated(holder: android.view.SurfaceHolder) {
            super.onSurfaceCreated(holder)
            this.holder = getSurfaceHolder()
            WepWawetLogger.engine("Service: wallpaper surface created",
                "screen" to screenLabel,
                "surface_frame" to (this.holder?.surfaceFrame?.toString() ?: "null"))
            val pending = pendingFile
            if (pending != null) {
                this.pendingFile = null
                loadWallpaper(pending)
            } else {
                // v0.8.28: a surviving view already showing the selection
                // makes this a confirmed no-op (same-file skip) — waking
                // the screen no longer reloads or flashes anything.
                reloadActiveWallpaper()
            }
        }

        override fun onSurfaceDestroyed(holder: android.view.SurfaceHolder) {
            // v0.8.28 (THE SCREEN-OFF REBUILD): the surface goes away on
            // screen off; the VIEW and its content SURVIVE (the GL context
            // is preserved). The old teardown here forced a full rebuild +
            // reload on every wake — the blank flash AND the double load.
            // The view pauses itself (it is a holder callback) and
            // re-attaches when the surface returns.
            super.onSurfaceDestroyed(holder)
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            // v0.8.28: the system re-assigned this engine's screen —
            // re-classify, then load the NEW screen's selection.
            val newScreen = this@WEWallpaperService.engineScreenOf(this)
            if (newScreen != screenLabel) {
                WepWawetLogger.engine("Service: engine screen changed",
                    "from" to screenLabel, "to" to newScreen)
                this@WEWallpaperService.detachEngine(this)
                this@WEWallpaperService.attachEngine(this, newScreen)
                if (holder != null) {
                    reloadActiveWallpaper()
                }
            }
        }

        override fun onDestroy() {
            try {
                unregisterReceiver(powerSavingReceiver)
            } catch (t: Throwable) {
                WepWawetLogger.engineWarn("Service: power receiver unregister failed",
                    "exception" to (t.message ?: t.javaClass.name))
            }
            super.onDestroy()
            if (wallpaper != null) {
                wallpaperControls?.onStopWallpaper()
                wallpaperControls?.onDestroyWallpaper()
                wallpaper = null
            }
            // v0.8.28: only THIS engine's pointer goes away — the other
            // screen's engine (a separate instance) stays fully alive.
            completeLoadConfirm(false)
            this@WEWallpaperService.detachEngine(this)
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
