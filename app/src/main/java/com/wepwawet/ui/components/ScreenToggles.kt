package com.wepwawet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
 * v0.9.1: THE UNIVERSAL Home/Lock toggles — the follow-up review's shape:
 *
 *  • NO separate top bar (the v0.9.0 strip shifted every screen down) —
 *    these compact pills live INSIDE each main screen's EXISTING top bar,
 *    on the right of the title.
 *  • ON/OFF ONLY — "no more different colors only on or off". The old
 *    pink/blue tri-state (which also drove the two-different-images dual
 *    mode) is collapsed: ON means that screen gets the wallpaper, OFF
 *    means it doesn't. Both ON → one image on both screens.
 *  • Each toggle sets ITS OWN screen (Home → the home screen, Lock → the
 *    lock screen) everywhere the store is read (Discover, Library,
 *    preview, rules) — "it should be universal".
 *  • No "Screens:" label — the pills' icons + labels say it.
 *
 * The state stays in DiscoverPrefs (every apply path already reads it):
 * ON is stored as SCREEN_ON; readScreens migrates any legacy pink/blue
 * value to plain ON, so dual mode can never trigger again.
 */
@Composable
fun ScreenToggles(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var screens by remember { mutableStateOf(DiscoverPrefs.readScreens(context)) }

    fun toggle(which: Int) {
        val (home, lock) = screens
        screens = if (which == 0) {
            val next = if (home == DiscoverPrefs.SCREEN_OFF) DiscoverPrefs.SCREEN_ON
            else DiscoverPrefs.SCREEN_OFF
            next to lock
        } else {
            val next = if (lock == DiscoverPrefs.SCREEN_OFF) DiscoverPrefs.SCREEN_ON
            else DiscoverPrefs.SCREEN_OFF
            home to next
        }
        DiscoverPrefs.writeScreens(context, screens.first, screens.second)
        WepWawetLogger.appInfo("ScreenToggles: toggled",
            "home" to (screens.first != DiscoverPrefs.SCREEN_OFF),
            "lock" to (screens.second != DiscoverPrefs.SCREEN_OFF))
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenToggle(
            label = "Home",
            icon = { Icon(Icons.Filled.Smartphone, null, Modifier.size(13.dp)) },
            on = screens.first != DiscoverPrefs.SCREEN_OFF,
        ) { toggle(0) }
        ScreenToggle(
            label = "Lock",
            icon = { Icon(Icons.Filled.Lock, null, Modifier.size(13.dp)) },
            on = screens.second != DiscoverPrefs.SCREEN_OFF,
        ) { toggle(1) }
    }
}

/** One compact on/off pill for a top bar's action slot. */
@Composable
private fun ScreenToggle(
    label: String,
    icon: @Composable () -> Unit,
    on: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        icon()
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (on) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 3.dp),
        )
        if (on) {
            Box(
                modifier = Modifier
                    .padding(start = 3.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}
