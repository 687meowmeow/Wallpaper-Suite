package com.wepwawet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.playlist.SuitePlaylistStore
import com.wepwawet.ui.components.SlimTopBar

/**
 * Playlists — the manager for WallpaperSuite's saved playlists.
 *
 * Every playlist the user makes is persisted (see [SuitePlaylistStore])
 * and editable: rename, delete, set which one is ACTIVE (drives the live
 * wallpaper rotation), and open the editor for entries / order /
 * interval / time rules.
 */
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onOpenEditor: (playlistId: String) -> Unit,
) {
    val context = LocalContext.current
    var playlists by remember { mutableStateOf<List<SuitePlaylistStore.Playlist>>(emptyList()) }
    var showNewDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SuitePlaylistStore.Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<SuitePlaylistStore.Playlist?>(null) }

    fun reload() {
        playlists = SuitePlaylistStore.readAll(context)
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = { SlimTopBar(title = "Playlists", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Saved playlists", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The active playlist rotates your wallpapers — " +
                            "any mix of animated and static.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showNewDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (playlists.isEmpty()) {
                Text(
                    "No playlists yet. Create one, then add wallpapers to it " +
                        "from the library's long-press menu or the preview screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        val active = SuitePlaylistStore.isActive(context, playlist.id)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenEditor(playlist.id) }
                                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = active,
                                    onClick = {
                                        SuitePlaylistStore.setActive(
                                            context, if (active) null else playlist.id)
                                        reload()
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val rulesText = when {
                                        playlist.timeRules.isEmpty() -> ""
                                        else -> " · ${playlist.timeRules.size} time rules"
                                    }
                                    val orderText =
                                        if (playlist.orderMode == SuitePlaylistStore.ORDER_RANDOM)
                                            "random" else "ordered"
                                    Text(
                                        "${playlist.entries.size} wallpapers · $orderText$rulesText",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { renameTarget = playlist }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                                }
                                IconButton(onClick = { deleteTarget = playlist }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    // ── New playlist dialog ──
    if (showNewDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    label = { Text("Playlist name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            val created = SuitePlaylistStore.create(context, trimmed)
                            showNewDialog = false
                            WepWawetLogger.appInfo("PlaylistsScreen: created", "name" to trimmed)
                            onOpenEditor(created.id)
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── Rename dialog ──
    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    label = { Text("Playlist name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        SuitePlaylistStore.rename(context, target.id, name)
                        renameTarget = null
                        reload()
                    },
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    // ── Delete dialog ──
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete playlist?") },
            text = {
                Text("\"${target.name}\" will be deleted. The wallpapers " +
                    "themselves stay in your library.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        SuitePlaylistStore.delete(context, target.id)
                        deleteTarget = null
                        reload()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}
