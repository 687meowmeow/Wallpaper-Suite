package com.wepwawet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wepwawet.discover.DiscoverPrefs
import com.wepwawet.log.WepWawetLogger

/**
 * v0.9.0 redesign: THE UNIVERSAL Home/Lock toggles.
 *
 * "The Lock and Home toggles should be universal and sit at the top of the
 * app… Each of the two toggles cycles through Off, Pink, and Blue every
 * time I tap it. If Home and Lock are set to the same color, one image is
 * used for both screens. If they are set to different colors, each screen
 * gets its own separate image. Wherever I pick or apply a wallpaper in the
 * app, that selection should follow this system."
 *
 * The state is the Discover tri-state pair (DiscoverPrefs.readScreens) —
 * the same store every apply path already reads. This strip renders at the
 * top of the app's main tabs (WepWawetNavHost), and Discover's own copy of
 * the buttons is gone.
 */
@Composable
fun UniversalScreenToggles(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var screens by remember { mutableStateOf(DiscoverPrefs.readScreens(context)) }

    fun cycle(which: Int) {
        // Off → Pink → Blue → Off (v1.0.0 WallpaperTargetMode).
        val next = when (if (which == 0) screens.first else screens.second) {
            DiscoverPrefs.SCREEN_OFF -> DiscoverPrefs.SCREEN_PINK
            DiscoverPrefs.SCREEN_PINK -> DiscoverPrefs.SCREEN_BLUE
            else -> DiscoverPrefs.SCREEN_OFF
        }
        screens = if (which == 0) next to screens.second else screens.first to next
        DiscoverPrefs.writeScreens(context, screens.first, screens.second)
        WepWawetLogger.appInfo("ScreenToggles: cycled",
            "home" to screens.first,
            "lock" to screens.second,
            "dual" to DiscoverPrefs.isDualMode(screens.first, screens.second))
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Screens:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            ScreenToggle(
                label = "Home",
                icon = { Icon(Icons.Filled.Smartphone, null, Modifier.size(14.dp)) },
                state = screens.first,
            ) { cycle(0) }
            ScreenToggle(
                label = "Lock",
                icon = { Icon(Icons.Filled.Lock, null, Modifier.size(14.dp)) },
                state = screens.second,
            ) { cycle(1) }
        }
    }
}

/** Nyanpasu 1.0's tri-state colors for the toggles. */
private val PinkState = Color(0xFFFF80AB)
private val BlueState = Color(0xFF64B5F6)

/**
 * One tri-state pill: gray (off) → pink → blue. A white check glyph shows
 * when active. Same visuals as Discover's original tri-state buttons.
 */
@Composable
private fun ScreenToggle(
    label: String,
    icon: @Composable () -> Unit,
    state: Int,
    onClick: () -> Unit,
) {
    val active = state > DiscoverPrefs.SCREEN_OFF
    val tint = when (state) {
        DiscoverPrefs.SCREEN_PINK -> PinkState
        DiscoverPrefs.SCREEN_BLUE -> BlueState
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(tint)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        icon()
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) Color.White
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (active) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}
