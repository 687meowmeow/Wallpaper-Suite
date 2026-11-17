package com.wepwawet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.wallpaper.WallpaperRouter

/**
 * v0.8.29 — THE GUIDED SPLIT FLOW, at the moment it matters.
 *
 * The user's design (their words): the app must detect the shared/separate
 * engine situation, and the guided split flow must be offered "when I
 * want different images" — i.e. exactly when a single-screen apply is
 * blocked because ONE shared engine serves both screens. It is NOT a
 * permanent banner at the top of every screen (removed — it took space
 * and appeared unrequested).
 *
 * Shown by every apply surface that receives a blocked outcome.
 */
@Composable
fun SharedEngineSplitDialog(
    reason: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Different wallpapers need separate engines")
        },
        text = {
            Column {
                Text(
                    "Both screens currently share ONE wallpaper engine — they always " +
                        "show the same image, and a change to one screen would change both, " +
                        "so nothing was changed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "To use a different wallpaper on each screen:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "To use a different wallpaper on each screen:\n" +
                        "1. Set Wallpaper Suite choosing “Home screen”.\n" +
                        "2. Set it again choosing “Lock screen”.\n\n" +
                        "Or, right now: apply any image choosing “Lock” — it becomes the " +
                        "Lock screen's static wallpaper while Home keeps its live one. " +
                        "(Animated Lock wallpapers need separate engines.)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (reason.isNotEmpty()) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    if (WallpaperRouter.canPromptLiveWallpaper(context)) {
                        context.startActivity(WallpaperRouter.setLiveWallpaperIntent(context))
                        WepWawetLogger.appInfo("SplitDialog: one-tap live-wallpaper flow opened")
                    } else {
                        WepWawetLogger.appError("SplitDialog: one-tap flow unavailable on this device")
                    }
                } catch (e: Exception) {
                    WepWawetLogger.appError("SplitDialog: one-tap flow FAILED to open",
                        "exception" to (e.message ?: e.javaClass.name))
                }
                onDismiss()
            }) {
                Text("Set them now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        },
    )
}
