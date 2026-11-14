package com.wepwawet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wepwawet.rules.RuleActivityLog
import com.wepwawet.ui.components.SlimTopBar
import kotlinx.coroutines.delay

/**
 * v0.8.25: THE ACTIVITY VIEW — recent playlist-system events with what
 * happened and why (fired / applied / skipped + the reason), read from the
 * persisted [RuleActivityLog] journal. "Anything that can silently fail
 * must either work or tell the user why in the UI" — this screen is that
 * surface; every skip path in the hub and engine writes to it.
 */
@Composable
fun PlaylistActivityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<RuleActivityLog.Entry>>(emptyList()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var refreshTick by remember { mutableStateOf(0) }

    // Live while open (the journal is tiny; a 2-second poll is invisible).
    LaunchedEffect(refreshTick) {
        entries = RuleActivityLog.read(context)
        names = com.wepwawet.playlist.SuitePlaylistStore.readAll(context)
            .associate { it.id to it.name }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            entries = RuleActivityLog.read(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SlimTopBar(
            title = "Activity",
            onBack = onBack,
            actions = {
                IconButton(onClick = {
                    RuleActivityLog.clear(context)
                    entries = emptyList()
                }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear")
                }
            },
        )
        if (entries.isEmpty()) {
            Text(
                "Nothing yet.\n\nEvery rule fired, skipped (with the reason) and every wallpaper applied shows up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // v0.8.26 (crash fix): keys are the journal's unique
                // sequence stamps — timestamps collide (two playlists
                // skipped by one event, same millisecond).
                items(entries, key = { it.seq }) { e ->
                    ActivityRow(e, names[e.playlist] ?: "")
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(e: RuleActivityLog.Entry, playlistName: String) {
    val (icon, tint) = when (e.kind) {
        "fired" -> Icons.Filled.Bolt to MaterialTheme.colorScheme.primary
        "applied" -> Icons.Filled.Image to MaterialTheme.colorScheme.tertiary
        "skipped" -> Icons.Filled.SkipNext to MaterialTheme.colorScheme.onSurfaceVariant
        "event" -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.secondary
        "error" -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
        else -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        tonalElevation = 0.dp,
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.14f),
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    icon, contentDescription = e.kind,
                    tint = tint,
                    modifier = Modifier.padding(6.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(e.rule.ifEmpty { activityLabel(e.kind) })
                        if (playlistName.isNotEmpty()) append("  ·  $playlistName")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(0.dp))
                Text(
                    e.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                activityRelativeTime(e.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun activityLabel(kind: String): String = when (kind) {
    "fired" -> "Rule fired"
    "applied" -> "Wallpaper applied"
    "skipped" -> "Skipped"
    "event" -> "Trigger due"
    "error" -> "Error"
    else -> "Info"
}

private fun activityRelativeTime(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}
