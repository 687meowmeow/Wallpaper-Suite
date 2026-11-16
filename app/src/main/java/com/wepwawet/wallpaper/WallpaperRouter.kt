package com.wepwawet.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.wepwawet.log.WepWawetLogger
import io.wallpaperengine.weclient.WEWallpaperService
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weutil.WallpaperFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * v0.8.28 — THE ONE APPLY PATH (hardened).
 *
 * Every wallpaper change routes through the app's OWN live wallpaper on
 * both screens; normal operation NEVER calls WallpaperManager.setBitmap
 * (every setBitmap installs a NEW system wallpaper — a new
 * WallpaperManager ID, what filled the OnePlus "Wallpaper & style" list
 * — and REPLACES the live service, killing the playlist and rules).
 *
 * The v0.8.28 rewrite fixes the field-reported breakages:
 *  • a screen's SAVED SELECTION is committed only AFTER its engine
 *    CONFIRMED the load (a failed apply used to leave the saved
 *    selection pointing at a broken file, which the engine then retried
 *    on every screen-on and restart);
 *  • the ENGINE TOPOLOGY is the ground truth: a "both at once" set
 *    gives ONE SHARED engine (flags SYSTEM|LOCK, one surface mirrored on
 *    both screens) — a single-screen apply through it would change BOTH
 *    screens, so it is BLOCKED with the honest reason and the split
 *    flow instead of silently changing the screen the user turned off;
 *  • coverage ("is our live wallpaper on the lock screen?") is decided
 *    from the engines that actually exist and the flags they report —
 *    WallpaperManager.getWallpaperInfo(FLAG_LOCK) answers null when the
 *    lock screen simply follows the home wallpaper, which used to make
 *    the banner claim a covered lock screen wasn't;
 *  • files are only applied after a cheap reachability check, and ring
 *    cleanup NEVER deletes a file any screen selection, playlist, or
 *    rule still references.
 */
object WallpaperRouter {

    private const val TAG = "WallpaperRouter"
    private const val APPLIED_DIR = "router_applied"
    private const val APPLIED_RING_LIMIT = 10

    /** What one apply actually achieved, per screen — honest, engine-confirmed. */
    data class ApplyOutcome(
        val homeRequested: Boolean,
        val lockRequested: Boolean,
        val homeOk: Boolean,
        val lockOk: Boolean,
        /** "" or the human reason nothing changed (both off / not live / …). */
        val reason: String,
        /** v0.8.29: a single-screen apply blocked by the ONE shared
         *  engine — the apply surfaces show the guided split dialog. */
        val blockedBySharedEngine: Boolean = false,
    ) {
        val anyOk: Boolean get() = homeOk || lockOk
        val allOk: Boolean get() = (homeOk || !homeRequested) && (lockOk || !lockRequested)
        fun describe(): String = when {
            reason.isNotEmpty() -> reason
            homeOk && lockOk -> "applied to home + lock"
            homeOk -> "applied to the home screen"
            lockOk -> "applied to the lock screen"
            else -> "no screen showed it"
        }
    }

    // ═══ Coverage: engine ground truth first, system answer second ═══

    /** Bumped on every engine attach/detach/flags change. */
    private val topologyVersion = AtomicInteger(0)
    private val topologyListeners = CopyOnWriteArraySet<() -> Unit>()

    /** The service calls this whenever an engine attaches/detaches/re-flags. */
    @JvmStatic
    fun notifyTopologyChanged() {
        topologyVersion.incrementAndGet()
        val svc = WEWallpaperService.instance
        WepWawetLogger.appInfo("$TAG: engine topology changed",
            "engines" to (svc?.engineFlagsSnapshot() ?: "(no service)"))
        for (l in topologyListeners) {
            try {
                l.invoke()
            } catch (t: Throwable) {
                WepWawetLogger.appWarn("$TAG: topology listener failed",
                    "exception" to (t.javaClass.name))
            }
        }
    }

    /** The banner registers one of these to refresh on engine changes. */
    fun addOnTopologyChangedListener(listener: () -> Unit) {
        topologyListeners.add(listener)
    }

    fun removeOnTopologyChangedListener(listener: () -> Unit) {
        topologyListeners.remove(listener)
    }

    fun topologyVersion(): Int = topologyVersion.get()

    /** The live wallpaper component of THIS app. */
    fun ourComponent(context: Context): ComponentName =
        ComponentName(context, WEWallpaperService::class.java)

    /**
     * Is our live wallpaper showing on the HOME screen? The ENGINE the
     * process is hosting is the ground truth; when no engine is attached
     * (a process-restart race) the system's answer decides.
     */
    fun ourLiveOnHome(context: Context): Boolean = coverage(context, "home")

    /** Is our live wallpaper showing on the LOCK screen? */
    fun ourLiveOnLock(context: Context): Boolean = coverage(context, "lock")

    private fun coverage(context: Context, screen: String): Boolean {
        val svc = WEWallpaperService.instance
        val topology = svc?.engineTopology()
        val fromEngines = when (topology) {
            WEWallpaperService.EngineTopology.SEPARATE,
            WEWallpaperService.EngineTopology.SHARED -> true // both screens
            WEWallpaperService.EngineTopology.HOME_ONLY -> screen == "home"
            WEWallpaperService.EngineTopology.LOCK_ONLY -> screen == "lock"
            else -> null // no engine in-process: ask the system
        }
        val fromSystem = systemSaysOurs(context, screen)
        val covered = fromEngines ?: fromSystem
        WepWawetLogger.appInfo("$TAG: coverage decision",
            "screen" to screen,
            "covered" to covered,
            "from_engines" to (fromEngines?.toString() ?: "n/a"),
            "from_system" to fromSystem,
            "engines" to (svc?.engineFlagsSnapshot() ?: "(no service)"))
        return covered
    }

    private fun systemSaysOurs(context: Context, screen: String): Boolean = try {
        val wm = context.getSystemService(Context.WALLPAPER_SERVICE) as? WallpaperManager
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            if (screen != "lock") {
                wm?.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)
                    ?.component?.packageName == context.packageName
            } else {
                // v0.8.29 (THE LOCK ANSWER): getWallpaperInfo(FLAG_LOCK)
                // answers NULL when the lock screen simply FOLLOWS the
                // home wallpaper (the system keeps no separate lock
                // wallpaper then) — that null is NOT "not ours". The
                // truth table:
                //   • a LIVE lock wallpaper → ours iff it is our package;
                //   • a STATIC lock wallpaper exists → not ours;
                //   • neither → lock follows home → ours iff HOME is.
                val lockInfo = wm?.getWallpaperInfo(WallpaperManager.FLAG_LOCK)
                when {
                    lockInfo != null ->
                        lockInfo.component.packageName == context.packageName
                    else -> {
                        val hasStaticLock = try {
                            wm?.getWallpaperFile(WallpaperManager.FLAG_LOCK) != null
                        } catch (se: SecurityException) {
                            false
                        }
                        if (hasStaticLock) {
                            false
                        } else {
                            // Lock follows home.
                            wm?.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)
                                ?.component?.packageName == context.packageName
                        }
                    }
                }
            }
        } else {
            // Pre-34: one live-wallpaper concept; the system answer only
            // knows the home screen (an honest limitation, logged above).
            screen == "home" &&
                wm?.wallpaperInfo?.component?.packageName == context.packageName
        }
    } catch (e: Exception) {
        WepWawetLogger.appWarn("$TAG: wallpaper info read failed",
            "exception" to e.javaClass.simpleName)
        false
    }

    /**
     * The ONE-TAP prompt: ACTION_CHANGE_LIVE_WALLPAPER with our component
     * preselected — the system opens the live-wallpaper preview for the
     * user to confirm (choosing "Set wallpaper → Home screen and lock
     * screen" gives both screens to the live service).
     */
    fun setLiveWallpaperIntent(context: Context): Intent =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ourComponent(context))
        }

    /** Can the one-tap intent be handled on this device? */
    fun canPromptLiveWallpaper(context: Context): Boolean =
        context.packageManager.queryIntentActivities(
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER), 0).isNotEmpty()

    /**
     * v0.8.28: does the CURRENT topology block different-images-per-
     * screen? True when a SHARED engine is attached — one surface serves
     * both screens, so per-screen images need the split flow.
     */
    fun splitNeeded(context: Context): Boolean {
        val svc = WEWallpaperService.instance ?: return false
        return svc.engineTopology() == WEWallpaperService.EngineTopology.SHARED
    }

    /**
     * Is EVERY switched-on screen covered by our live wallpaper? (The
     * banner shows when not.) An off screen doesn't need coverage — it
     * keeps whatever it has.
     */
    fun coverageOkForOnScreens(context: Context): Boolean {
        val screens = com.wepwawet.discover.DiscoverPrefs.readScreens(context)
        val homeOn = com.wepwawet.discover.DiscoverPrefs.homeRequired(screens.first)
        val lockOn = com.wepwawet.discover.DiscoverPrefs.lockRequired(screens.second)
        val homeOk = !homeOn || ourLiveOnHome(context)
        val lockOk = !lockOn || ourLiveOnLock(context)
        return homeOk && lockOk
    }

    // ═══ The apply path ═══════════════════════════════════════════════

    /**
     * Apply a wallpaper PATH to the screens allowed by the universal
     * Home/Lock toggles. [target] is the rule block's own target
     * ("home"/"lock"/"both"/"" = follow the toggles) — a screen applies
     * only when BOTH the block and the toggle allow it. With both toggles
     * off NOTHING changes and the outcome says why.
     *
     * v0.8.28 semantics:
     *  1. reachability first — a missing file fails before any screen is
     *     touched;
     *  2. the file is loaded as a CANDIDATE; a screen's saved selection
     *     is committed only after its engine CONFIRMED the load, and a
     *     failed screen gets its previous selection restored on screen;
     *  3. a SHARED engine blocks single-screen applies (one surface would
     *     change both screens) with the split-flow reason;
     *  4. the Home/Lock toggle state is logged with every apply.
     */
    suspend fun applyPath(
        context: Context,
        path: String,
        target: String = "",
    ): ApplyOutcome = withContext(Dispatchers.IO) {
        val screens = com.wepwawet.discover.DiscoverPrefs.readScreens(context)
        val togglesHome = com.wepwawet.discover.DiscoverPrefs.homeRequired(screens.first)
        val togglesLock = com.wepwawet.discover.DiscoverPrefs.lockRequired(screens.second)
        // The block's own target narrows the toggles; the toggles are the
        // master gate — nothing applies to a screen whose toggle is off.
        // (A saved "bothDifferent" target is the retired dual mode — it
        // maps to "both": both ON screens get the image.)
        val t = if (target == "bothDifferent") "both" else target
        val blockHome = t.isEmpty() || t == "home" || t == "both"
        val blockLock = t.isEmpty() || t == "lock" || t == "both"
        val wantHome = togglesHome && blockHome
        val wantLock = togglesLock && blockLock

        WepWawetLogger.appInfo("$TAG: apply requested",
            "path" to path.takeLast(56),
            "target" to t.ifEmpty { "(toggles)" },
            "home_toggle_on" to togglesHome,
            "lock_toggle_on" to togglesLock,
            "want_home" to wantHome,
            "want_lock" to wantLock)

        if (!wantHome && !wantLock) {
            val why = if (togglesHome || togglesLock)
                "the rule's target screens are switched off"
            else "both Home and Lock are switched off — nothing changes"
            WepWawetLogger.appInfo("$TAG: apply skipped — $why", "path" to path.takeLast(56))
            return@withContext ApplyOutcome(
                homeRequested = false, lockRequested = false,
                homeOk = false, lockOk = false, reason = why)
        }

        // v0.8.28: reachability FIRST — never route a broken/missing file
        // into an engine and never save it as a screen's selection.
        if (!WallpaperFile.exists(context, path)) {
            val why = "the file is missing or unreadable"
            WepWawetLogger.appError("$TAG: apply failed — $why",
                "path" to path.takeLast(56))
            return@withContext ApplyOutcome(wantHome, wantLock, false, false, why)
        }

        val svc = WEWallpaperService.instance
        if (svc == null) {
            val why = "the live wallpaper isn't active — open the app and set it once"
            WepWawetLogger.appError("$TAG: apply failed — live service not running",
                "path" to path.takeLast(56))
            return@withContext ApplyOutcome(wantHome, wantLock, false, false, why)
        }

        // v0.8.28 (THE SHARED ENGINE): one engine serving BOTH screens is
        // one surface — a single-screen apply would change BOTH screens.
        // Block it honestly; the split flow makes per-screen images work.
        val topology = svc.engineTopology()
        if ((wantHome xor wantLock) && topology == WEWallpaperService.EngineTopology.SHARED) {
            val why = "both screens share ONE wallpaper engine — a single-screen change " +
                "would change both. Split them first (Set once → choose Home screen, " +
                "then set it again choosing Lock screen)"
            WepWawetLogger.appError("$TAG: single-screen apply blocked — shared engine",
                "path" to path.takeLast(56),
                "topology" to svc.engineTopologyLabel())
            return@withContext ApplyOutcome(
                wantHome, wantLock, false, false, why,
                blockedBySharedEngine = true)
        }

        // Load the candidate WITHOUT committing; remember the previous
        // selections so a failed screen goes back to what it showed.
        val prevHome = WeEnginePrefs.readSelectedForScreen(context, "home")
        val prevLock = WeEnginePrefs.readSelectedForScreen(context, "lock")
        val confirmed = svc.loadCandidateToEngines(path, wantHome, wantLock)

        // Commit ONLY the confirmed screens; restore the failed ones.
        if (confirmed.first) {
            WeEnginePrefs.writeSelectedForScreen(context, "home", path)
        } else if (wantHome) {
            WepWawetLogger.appError("$TAG: HOME load failed — restoring the previous selection",
                "previous" to (prevHome ?: "(none)").toString().takeLast(56))
            svc.restoreScreenSelection("home")
        }
        if (confirmed.second) {
            WeEnginePrefs.writeSelectedForScreen(context, "lock", path)
        } else if (wantLock) {
            WepWawetLogger.appError("$TAG: LOCK load failed — restoring the previous selection",
                "previous" to (prevLock ?: "(none)").toString().takeLast(56))
            svc.restoreScreenSelection("lock")
        }
        if (confirmed.first || confirmed.second) {
            // Stamp the rotation clock — the interval restarts on change.
            svc.stampRotationClock()
        }
        val outcome = ApplyOutcome(
            wantHome, wantLock,
            confirmed.first, confirmed.second, "")
        WepWawetLogger.appInfo("$TAG: apply ${if (outcome.allOk) "ok" else "incomplete"}",
            "path" to path.takeLast(56),
            "home_ok" to confirmed.first,
            "lock_ok" to confirmed.second,
            "topology" to svc.engineTopologyLabel(),
            "target" to t.ifEmpty { "(toggles)" })
        outcome
    }

    private val appliedCounter = AtomicLong(0)

    /**
     * Apply a BITMAP: when [sourcePath] is given AND the bitmap is that
     * file's unmodified content, the ORIGINAL file is applied directly —
     * no re-encode (transparency and animation survive). Genuinely new
     * pixels (a crop) are encoded by the SOURCE's format — PNG keeps
     * transparency (the old JPEG-at-92 killed it).
     */
    suspend fun applyBitmap(
        context: Context,
        bitmap: Bitmap,
        target: String = "",
        sourcePath: String? = null,
        sourceModified: Boolean = true,
    ): ApplyOutcome = withContext(Dispatchers.IO) {
        val path = if (sourcePath != null && !sourceModified &&
            WallpaperFile.exists(context, sourcePath)
        ) {
            WepWawetLogger.appInfo("$TAG: applying the ORIGINAL file (no re-encode)",
                "path" to sourcePath.takeLast(56))
            sourcePath
        } else {
            try {
                val dir = File(context.filesDir, APPLIED_DIR).apply { mkdirs() }
                val ts = System.currentTimeMillis()
                val seq = appliedCounter.incrementAndGet()
                val isPngishSource = sourcePath != null &&
                    (sourcePath.endsWith(".png", true) ||
                        sourcePath.endsWith(".gif", true) ||
                        sourcePath.endsWith(".webp", true))
                val (name, format) = if (isPngishSource)
                    "applied_${ts}_$seq.png" to Bitmap.CompressFormat.PNG
                else
                    "applied_${ts}_$seq.jpg" to Bitmap.CompressFormat.JPEG
                val file = File(dir, name)
                file.outputStream().use { out ->
                    bitmap.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 92, out)
                }
                pruneAppliedRing(context, dir)
                file.absolutePath
            } catch (e: Exception) {
                WepWawetLogger.appError("$TAG: applied-bitmap save failed",
                    "exception" to e.javaClass.name)
                return@withContext ApplyOutcome(
                    true, target == "lock" || target.isEmpty() || target == "both",
                    false, false, "could not save the image")
            }
        }
        applyPath(context, path, target)
    }

    /**
     * v0.8.28: keep the ring small, but NEVER delete a file that any
     * screen's selection, any playlist entry, or any rule still
     * references — a screen can keep pointing at an older applied image
     * for a long time while the other screen's applies push it out of
     * the ring (the deleted file broke the next screen-on load).
     */
    private fun pruneAppliedRing(context: Context, dir: File) {
        val all = dir.listFiles { f -> f.name.startsWith("applied_") }
            ?.sortedBy { it.name } ?: return
        val deletable = all.dropLast(APPLIED_RING_LIMIT - 1)
        for (f in deletable) {
            if (isPathReferenced(context, f.absolutePath)) {
                WepWawetLogger.appInfo("$TAG: ring prune kept a REFERENCED file",
                    "file" to f.name)
                continue
            }
            runCatching { f.delete() }
                .onFailure {
                    WepWawetLogger.appWarn("$TAG: ring prune delete failed",
                        "file" to f.name,
                        "exception" to (it.message ?: it.javaClass.name))
                }
        }
    }

    /** Does any screen selection, playlist, or rule still reference the path? */
    fun isPathReferenced(context: Context, absPath: String): Boolean {
        return try {
            if (WeEnginePrefs.readSelectedForScreen(context, "home") == absPath) return true
            if (WeEnginePrefs.readSelectedForScreen(context, "lock") == absPath) return true
            // Playlist entries (including group blocks' embedded paths).
            if (absPath in com.wepwawet.playlist.SuitePlaylistStore
                    .allEntryPaths(context)) return true
            // Rules: any block parameter or pick cursor pointing at it.
            val rulesFile = java.io.File(context.filesDir, "suite_rules.json")
            if (rulesFile.isFile && rulesFile.readText().contains(absPath)) return true
            false
        } catch (t: Throwable) {
            // A failed check must never cause a referenced-file deletion.
            true
        }
    }

    /** One screen's current selection (for UI status lines). */
    fun selectedForScreen(context: Context, screen: String): String? =
        WeEnginePrefs.readSelectedForScreen(context, screen)
}
