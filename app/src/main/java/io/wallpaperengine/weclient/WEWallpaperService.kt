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

    // ── v0.9.0 (rules engine): the small surface the RULES ENGINE drives.
    //  The state lives in the ENGINE (playlist, handler, controls); these
    //  are null-safe delegates so RuleEngine never touches the engine class.
    fun currentWallpaperFile(): String? = currentEngine?.rulesCurrentFile()

    /** How long the current wallpaper has been showing, in minutes (rules). */
    fun currentWallpaperShownMinutes(): Long = currentEngine?.rulesShownMinutes() ?: 0L

    /** v0.9.0 (rules): mute audio-responsive effects (the engine block). */
    @Volatile
    var rulesAudioMuted: Boolean = false

    /** Pause or resume the playlist rotation (rule action). */
    fun setRotationPaused(paused: Boolean) {
        currentEngine?.rulesSetRotationPaused(paused)
    }

    /** v0.9.0 redesign: is the rotation paused by a rule right now? */
    fun isRotationPaused(): Boolean = currentEngine?.rulesIsRotationPaused() ?: false

    /** Hold the current wallpaper for [minutes] (rule action). */
    fun holdWallpaperFor(minutes: Int) {
        currentEngine?.rulesHoldFor(minutes * 60_000L)
    }

    /** Force one rotation NOW (rule action: restart playlist). */
    fun forcePlaylistRotation() {
        currentEngine?.rulesForceRotation()
    }

    /** v0.9.0 redesign: is a rule hold currently blocking rotation? */
    fun isRotationHeld(): Boolean = currentEngine?.rulesIsRotationHeld() ?: false

    /** v0.9.0 redesign: re-check the Limit scopes NOW (a condition event
     *  fired, or the rules were edited). Never rotates on its own — it
     *  only switches when the current wallpaper left the allowed set. */
    fun requestScopeReevaluation() {
        currentEngine?.rulesReevaluateScopes()
    }

    /** v0.9.0 (rules): apply saved wallpaper properties (preset block). */
    fun rulesApplyProperties(propertiesJson: String) {
        currentEngine?.rulesApplyProperties(propertiesJson)
    }

    /** v0.9.0 (rules): the active engine's controls (preset capture). */
    fun rulesWallpaperControls(): io.wallpaperengine.weviews.WallpaperControls? =
        currentEngine?.wallpaperControls

    /** v0.9.0 (rules): set the FPS limit (engine block). */
    fun rulesSetFps(fps: Int) {
        currentEngine?.rulesSetFps(fps)
    }

    /** v0.9.0 (rules): pause or resume the renderer (engine block). */
    fun rulesSetRendererPaused(paused: Boolean) {
        currentEngine?.rulesSetRendererPaused(paused)
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
                        WeEnginePrefs.storeString(
                            this@WEWallpaperService, WeEnginePrefs.PREF_KEY_SELECTED_WALLPAPER, next)
                        reloadActiveWallpaper()
                        // Stamp the switch time so the interval restarts
                        // and the tick doesn't immediately rotate again.
                        onWallpaperChangedExternally()
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
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                WeEnginePrefs.storeString(
                                    svc, WeEnginePrefs.PREF_KEY_SELECTED_WALLPAPER, path)
                                svc.currentEngine?.playlistReloadAfterExternalChange()
                                // Stamp the switch so the interval restarts.
                                svc.currentEngine?.onWallpaperChangedExternally()
                            }
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
                    WeEnginePrefs.storeString(this@WEWallpaperService, WeEnginePrefs.PREF_KEY_SELECTED_WALLPAPER, newWallpaper)
                    reloadActiveWallpaper()
                    // v0.8.15: stamp the switch time. Without this the
                    // interval gate compared against the stale
                    // lastChangeTime forever, so after the first interval
                    // elapsed the playlist rotated on EVERY 5-second tick.
                    playlist.onWallpaperChangedExternally()
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
            reloadActiveWallpaper()
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
            parallaxController.linkWallpaper(videoWallpaperView)
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
