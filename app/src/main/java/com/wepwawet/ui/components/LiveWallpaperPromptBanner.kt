package com.wepwawet.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wepwawet.wallpaper.WallpaperRouter

/**
 * v0.10.0 — THE ONE-TAP LIVE-WALLPAPER PROMPT.
 *
 * Every apply now goes through the app's own live wallpaper (one system
 * entry, both screens — setBitmap is gone from normal operation, it was
 * installing a NEW system wallpaper on every change and REPLACING the
 * live service, killing the playlist and rules). When our live wallpaper
 * is NOT the active one on a screen the toggles have ON, nothing can
 * apply — this banner says so and offers the ONE-TAP fix: the system's
 * "set live wallpaper" flow with our component preselected (choose
 * "Set wallpaper" → Home screen and lock screen once, and this banner
 * never appears again).
 */
@Composable
fun LiveWallpaperPromptBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val screens = com.wepwawet.discover.DiscoverPrefs.readScreens(context)
    val homeOn = com.wepwawet.discover.DiscoverPrefs.homeRequired(screens.first)
    val lockOn = com.wepwawet.discover.DiscoverPrefs.lockRequired(screens.second)
    val homeLive = WallpaperRouter.ourLiveOnHome(context)
    val lockLive = WallpaperRouter.ourLiveOnLock(context)
    val homeNeeds = homeOn && !homeLive
    val lockNeeds = lockOn && !lockLive
    if (!homeNeeds && !lockNeeds) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            buildString {
                append("Wallpaper Suite isn't the live wallpaper on ")
                append(
                    when {
                        homeNeeds && lockNeeds -> "Home or Lock"
                        homeNeeds -> "the Home screen"
                        else -> "the Lock screen"
                    })
                append(" — wallpapers can't change there. Set it once:")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp, top = 6.dp),
        )
        Button(onClick = {
            if (WallpaperRouter.canPromptLiveWallpaper(context)) {
                runCatching {
                    context.startActivity(WallpaperRouter.setLiveWallpaperIntent(context))
                }
            }
        }) {
            Text("Set once")
        }
    }
}
