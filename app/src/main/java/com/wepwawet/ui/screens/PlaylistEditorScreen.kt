package com.wepwawet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wepwawet.ui.components.SlimTopBar
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weclient.WallpaperProperties
import io.wallpaperengine.wedata.PlaylistData
import io.wallpaperengine.wedata.PlaylistDataWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wallpaper playlist editor — mode (random / sorted / time-of-day /
 * day-of-week), change interval, item order and per-item durations.
 * The live wallpaper service picks this configuration up on its next
 * playlist sync (5s tick / on settings change).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistEditorScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var playlist by remember { mutableStateOf(PlaylistData()) }
    var mode by remember { mutableStateOf(WallpaperProperties.PLAYLIST_MODE_RANDOM) }
    var durationMinutes by remember { mutableStateOf(WallpaperProperties.PLAYLIST_DEFAULT_DURATION) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            playlist = WeEnginePrefs.readPlaylist(context)
            mode = playlist.mode.ifEmpty {
                WeEnginePrefs.getStoredString(context, WallpaperProperties.PLAYLIST_MODE)
                    ?: WallpaperProperties.PLAYLIST_MODE_RANDOM
            }
            durationMinutes = WeEnginePrefs.getStoredInt(
                context, WallpaperProperties.PLAYLIST_DURATION,
                WallpaperProperties.PLAYLIST_DEFAULT_DURATION,
            )
        }
    }

    fun persist() {
        // v0.8.3: the official model stores the mode INSIDE the playlist
        // JSON (PlaylistData.mode) — keep the legacy pref in sync for older
        // readers, but the JSON is the source of truth now.
        playlist.mode = mode
        WeEnginePrefs.storePlaylist(context, playlist)
        WeEnginePrefs.storeString(context, WallpaperProperties.PLAYLIST_MODE, mode)
        WeEnginePrefs.storeInt(context, WallpaperProperties.PLAYLIST_DURATION, durationMinutes)
        io.wallpaperengine.weclient.WEWallpaperService.instance
            ?.requestPlaylistSettingsChanged()
    }

    Scaffold(
        topBar = {
            SlimTopBar(title = "Playlist", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Mode", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaylistChip("Random", WallpaperProperties.PLAYLIST_MODE_RANDOM, mode) {
                    mode = it; persist()
                }
                PlaylistChip("Sorted", WallpaperProperties.PLAYLIST_MODE_SORTED, mode) {
                    mode = it; persist()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaylistChip("Time of day", WallpaperProperties.PLAYLIST_MODE_TIME_OF_DAY, mode) {
                    mode = it; persist()
                }
                PlaylistChip("Day of week", WallpaperProperties.PLAYLIST_MODE_DAY_OF_WEEK, mode) {
                    mode = it; persist()
                }
            }

            Text(
                "Change interval: $durationMinutes min",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = durationMinutes.toFloat(),
                onValueChange = { durationMinutes = it.toInt() },
                onValueChangeFinished = { persist() },
                valueRange = 1f..240f,
            )

            Text(
                "Wallpapers (${playlist.wallpapers.size})",
                style = MaterialTheme.typography.titleSmall,
            )

            if (playlist.wallpapers.isEmpty()) {
                Text(
                    "Add wallpapers from their preview screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(playlist.wallpapers, key = { it.filepath }) { item ->
                        PlaylistRow(
                            item = item,
                            canMoveUp = playlist.wallpapers.indexOf(item) > 0,
                            canMoveDown = playlist.wallpapers.indexOf(item) < playlist.wallpapers.size - 1,
                            showDuration = mode == WallpaperProperties.PLAYLIST_MODE_TIME_OF_DAY,
                            onMoveUp = {
                                val idx = playlist.wallpapers.indexOf(item)
                                if (idx > 0) {
                                    playlist.wallpapers.removeAt(idx)
                                    playlist.wallpapers.add(idx - 1, item)
                                    playlist = PlaylistData().apply {
                                        wallpapers = ArrayList(playlist@playlist.wallpapers)
                                    }
                                    persist()
                                }
                            },
                            onMoveDown = {
                                val idx = playlist.wallpapers.indexOf(item)
                                if (idx < playlist.wallpapers.size - 1) {
                                    playlist.wallpapers.removeAt(idx)
                                    playlist.wallpapers.add(idx + 1, item)
                                    playlist = PlaylistData().also { fresh ->
                                        fresh.wallpapers = ArrayList(playlist.wallpapers)
                                    }
                                    persist()
                                }
                            },
                            onRemove = {
                                playlist.wallpapers.removeAll { it.filepath == item.filepath }
                                playlist = PlaylistData().also { fresh ->
                                    fresh.wallpapers = ArrayList(playlist.wallpapers)
                                }
                                persist()
                            },
                            onDurationChange = { minutes ->
                                item.duration = minutes
                                persist()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlaylistChip(
    label: String,
    value: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = current == value,
        onClick = { onSelect(value) },
        label = { Text(label) },
    )
}

@Composable
private fun PlaylistRow(
    item: PlaylistDataWallpaper,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    showDuration: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onDurationChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.filepath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            if (showDuration) {
                Text(
                    "from ${item.duration / 60}:${(item.duration % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = item.duration.toFloat(),
                    onValueChange = { onDurationChange(it.toInt()) },
                    valueRange = 10f..720f,
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove")
        }
    }
}
