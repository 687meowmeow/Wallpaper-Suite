package com.wepwawet.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.wepwawet.log.WepWawetLogger
import io.wallpaperengine.weclient.WEWallpaperService
import io.wallpaperengine.weclient.WeEnginePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * v0.10.0 — THE ONE APPLY PATH.
 *
 * Research-confirmed design (this replaces THREE divergent apply paths):
 *  • Every wallpaper change goes through the app's OWN live wallpaper
 *    service, for BOTH screens. Normal operation NEVER calls
 *    WallpaperManager.setBitmap — every setBitmap installs a NEW system
 *    wallpaper (a new WallpaperManager ID — that is what filled the
 *    OnePlus "Wallpaper & style" screen with entries) and it REPLACES the
 *    live wallpaper, which stops the playlist and rules until the user
 *    sets the live wallpaper again.
 *  • A live wallpaper CAN own the lock screen on current Android (the
 *    old "a third-party live wallpaper can never own the lock screen"
 *    comment was wrong): the system picker offers Home / Lock / Both,
 *    and each engine learns its screen from Engine.getWallpaperFlags()
 *    (API 34+) — FLAG_SYSTEM for home, FLAG_LOCK for lock.
 *  • When our live wallpaper is not the active one, applies do NOT
 *    silently fall back to setBitmap — they report not-live, and the UI
 *    shows the ONE-TAP prompt ([setLiveWallpaperIntent], the
 *    ACTION_CHANGE_LIVE_WALLPAPER flow with our component preselected).
 *
 * The toggle model (the user's words): each toggle turns a screen on or
 * off as a target. A change only touches ON screens; both on = same
 * image; an off screen keeps exactly its image. Getting different images
 * on the two screens is: turn one off, set, swap the toggles, set.
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

    // ═══ Live-wallpaper coverage ═════════════════════════════════════

    /** The live wallpaper component of THIS app. */
    fun ourComponent(context: Context): ComponentName =
        ComponentName(context, WEWallpaperService::class.java)

    /** Is our live wallpaper the active one on the HOME screen right now? */
    fun ourLiveOnHome(context: Context): Boolean = ourLiveOn(context, WallpaperManager.FLAG_SYSTEM)

    /** Is our live wallpaper the active one on the LOCK screen right now? */
    fun ourLiveOnLock(context: Context): Boolean = ourLiveOn(context, WallpaperManager.FLAG_LOCK)

    private fun ourLiveOn(context: Context, which: Int): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val wm = context.getSystemService(Context.WALLPAPER_SERVICE) as? WallpaperManager
            wm?.getWallpaperInfo(which)?.component?.packageName == context.packageName
        } else {
            // Pre-34: one live wallpaper concept; the home answer stands
            // for both (the per-screen split needs the API-34 flags).
            val wm = context.getSystemService(Context.WALLPAPER_SERVICE) as? WallpaperManager
            which == WallpaperManager.FLAG_SYSTEM &&
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
     * Is EVERY switched-on screen owned by our live wallpaper? (The
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
     * Success is ENGINE-CONFIRMED: the service reports which engines
     * actually loaded the file (a dead engine is a failure, never a
     * silent no-op).
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

        if (!wantHome && !wantLock) {
            val why = if (togglesHome || togglesLock)
                "the rule's target screens are switched off"
            else "both Home and Lock are switched off — nothing changes"
            WepWawetLogger.appInfo("$TAG: apply skipped — $why", "path" to path.takeLast(56))
            return@withContext ApplyOutcome(
                homeRequested = false, lockRequested = false,
                homeOk = false, lockOk = false, reason = why)
        }

        // Persist FIRST (per screen — an off screen's selection is never
        // touched), then ask the engines to load; they confirm or fail.
        if (wantHome) {
            WeEnginePrefs.writeSelectedForScreen(context, "home", path)
        }
        if (wantLock) {
            WeEnginePrefs.writeSelectedForScreen(context, "lock", path)
        }
        val svc = WEWallpaperService.instance
        if (svc == null) {
            val why = "the live wallpaper isn't active — open the app and set it once"
            WepWawetLogger.appWarn("$TAG: apply failed — live service not running",
                "path" to path.takeLast(56))
            return@withContext ApplyOutcome(
                wantHome, wantLock, false, false, why)
        }
        val confirmed = svc.applySelectedToEngines(wantHome, wantLock)
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
            "target" to target.ifEmpty { "(toggles)" })
        outcome
    }

    private val appliedCounter = AtomicLong(0)

    /**
     * Apply a BITMAP (Discover's WYSIWYG crop, a cropped gallery image…):
     * the bitmap is written to a small on-disk ring
     * (filesDir/router_applied, newest 10 kept) and routed as a path —
     * the live engine then renders it exactly like any other image,
     * center-cropped to the screen it serves.
     */
    suspend fun applyBitmap(
        context: Context,
        bitmap: Bitmap,
        target: String = "",
    ): ApplyOutcome = withContext(Dispatchers.IO) {
        val path = try {
            val dir = File(context.filesDir, APPLIED_DIR).apply { mkdirs() }
            val ts = System.currentTimeMillis()
            val seq = appliedCounter.incrementAndGet()
            val file = File(dir, "applied_${ts}_$seq.jpg")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            // Keep the ring small — the applied bitmaps are wallpapers,
            // not history (Discover keeps its own 5-deep history ring).
            dir.listFiles { f -> f.name.startsWith("applied_") }
                ?.sortedBy { it.name }
                ?.dropLast(APPLIED_RING_LIMIT - 1)
                ?.forEach { runCatching { it.delete() } }
            file.absolutePath
        } catch (e: Exception) {
            WepWawetLogger.appError("$TAG: applied-bitmap save failed",
                "exception" to e.javaClass.name)
            return@withContext ApplyOutcome(
                true, target == "lock" || target.isEmpty(), false, false,
                "could not save the image")
        }
        applyPath(context, path, target)
    }

    /** One screen's current selection (for UI status lines). */
    fun selectedForScreen(context: Context, screen: String): String? =
        WeEnginePrefs.readSelectedForScreen(context, screen)
}
