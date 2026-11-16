package com.wepwawet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.wallpaper.WallpaperRouter

/** What the banner has to say right now (computed + logged together). */
private data class BannerState(
    /** An ON screen our live wallpaper is not covering. */
    val missingHome: Boolean,
    val missingLock: Boolean,
    /** Opening the system one-tap flow failed on this device. */
    val promptUnavailable: Boolean,
)

/**
 * v0.8.28 — THE LIVE-WALLPAPER BANNERS, refreshed.
 *
 * The old banner computed its state ONCE at composition and never
 * refreshed when the user came back from the system picker, claimed a
 * covered lock screen wasn't covered (it trusted
 * getWallpaperInfo(FLAG_LOCK), which answers null when the lock screen
 * follows the home wallpaper), only lived on Discover, and swallowed
 * launch errors with runCatching.
 *
 * Now: coverage comes from the ENGINE ground truth first (the engines
 * that exist + their flags), the system answer second; the state
 * recomputes on every resume, every engine attach/detach/flags change,
 * and every system wallpaper change; the banner shows on every screen
 * that can apply a wallpaper; each decision and display is logged; the
 * one-tap flow reports its failures instead of swallowing them.
 *
 * v0.8.29: the banner reports MISSING COVERAGE only. The shared-engine
 * split advice is NOT a banner anymore — it appears as
 * [SharedEngineSplitDialog] exactly when a single-screen apply is
 * blocked, which is when the user actually wants it.
 */
@Composable
fun LiveWallpaperPromptBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    // Refresh on resume (back from the system picker).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Refresh whenever an engine attaches/detaches or is re-flagged.
    DisposableEffect(Unit) {
        val listener: () -> Unit = { refresh++ }
        WallpaperRouter.addOnTopologyChangedListener(listener)
        onDispose { WallpaperRouter.removeOnTopologyChangedListener(listener) }
    }

    val state = remember(refresh) { computeBannerState(context) }
    if (state == null) return

    when {
        // ── A screen the toggles have ON is not covered ────────────────
        state.missingHome || state.missingLock -> Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append("Wallpaper Suite isn't the live wallpaper on ")
                        append(
                            when {
                                state.missingHome && state.missingLock -> "Home or Lock"
                                state.missingHome -> "the Home screen"
                                else -> "the Lock screen"
                            })
                        append(" — wallpapers can't change there. Set it once:")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Pick \"Set wallpaper\" → Home screen and lock screen.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { launchOneTap(context, "coverage") }) {
                Text("Set once")
            }
        }

        // ── The one-tap flow cannot even run here ──────────────────────
        state.promptUnavailable -> Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "This device can't open the live-wallpaper picker from here — set it from " +
                    "System settings → Wallpaper & style.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun computeBannerState(context: android.content.Context): BannerState? {
    val screens = com.wepwawet.discover.DiscoverPrefs.readScreens(context)
    val homeOn = com.wepwawet.discover.DiscoverPrefs.homeRequired(screens.first)
    val lockOn = com.wepwawet.discover.DiscoverPrefs.lockRequired(screens.second)
    val homeLive = WallpaperRouter.ourLiveOnHome(context)
    val lockLive = WallpaperRouter.ourLiveOnLock(context)
    val canPrompt = WallpaperRouter.canPromptLiveWallpaper(context)
    val svc = io.wallpaperengine.weclient.WEWallpaperService.instance
    // v0.8.29: the SHARED-ENGINE advice no longer has a banner — it
    // appears as a dialog exactly when a single-screen apply is blocked
    // (SharedEngineSplitDialog). The banner only reports missing
    // coverage now.
    val show = (homeOn && !homeLive) || (lockOn && !lockLive) || !canPrompt
    val state = if (show) BannerState(
        missingHome = homeOn && !homeLive,
        missingLock = lockOn && !lockLive,
        promptUnavailable = !canPrompt,
    ) else null
    // v0.8.28: every banner DECISION is logged with what it saw — the
    // next exported log shows exactly why it did or did not appear.
    WepWawetLogger.appInfo("Banner: coverage decision",
        "home_toggle_on" to homeOn,
        "lock_toggle_on" to lockOn,
        "home_live" to homeLive,
        "lock_live" to lockLive,
        "banner_shown" to (state != null),
        "engines" to (svc?.engineFlagsSnapshot() ?: "(no service)"))
    return state
}

private fun launchOneTap(context: android.content.Context, why: String) {
    // v0.8.28: no runCatching — a failed launch is logged AND surfaced.
    try {
        if (!WallpaperRouter.canPromptLiveWallpaper(context)) {
            WepWawetLogger.appError("Banner: one-tap flow unavailable on this device",
                "reason" to why)
            return
        }
        context.startActivity(WallpaperRouter.setLiveWallpaperIntent(context))
        WepWawetLogger.appInfo("Banner: one-tap live-wallpaper flow opened",
            "reason" to why)
    } catch (e: Exception) {
        WepWawetLogger.appError("Banner: one-tap flow FAILED to open",
            "reason" to why,
            "exception" to (e.message ?: e.javaClass.name))
    }
}
