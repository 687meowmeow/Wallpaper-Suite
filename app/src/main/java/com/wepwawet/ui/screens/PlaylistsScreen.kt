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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
 *
 * v0.8.13: this is a bottom-navigation TAB — [onBack] is nullable and
 * null when opened as a tab (no back arrow, like the other tabs).
 */
@Composable
fun PlaylistsScreen(
    onBack: (() -> Unit)?,
    onOpenEditor: (playlistId: String) -> Unit,
) {
    val context = LocalContext.current
    var playlists by remember { mutableStateOf<List<SuitePlaylistStore.Playlist>>(emptyList()) }
    var showNewDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SuitePlaylistStore.Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<SuitePlaylistStore.Playlist?>(null) }
    // v0.8.15: the active id is read ONCE per reload — the old per-item
    // isActive() call re-read and re-parsed the whole store JSON for every
    // row on every recomposition (main-thread I/O).
    var activePlaylistId by remember { mutableStateOf("") }

    fun reload() {
        playlists = SuitePlaylistStore.readAll(context)
        activePlaylistId = SuitePlaylistStore.activePlaylistId(context)
    }

    LaunchedEffect(Unit) { reload() }

    // v0.8.16: reload on every resume too — the manager must never show a
    // stale list ("the buttons in the playlist menu dont update live"):
    // playlists get created/renamed/added-to from the library picker, the
    // Discover tab and the editor, and the screen can return from any of
    // them with the store changed underneath.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                        val active = playlist.id == activePlaylistId
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
                                    val orderText =
                                        if (playlist.orderMode == SuitePlaylistStore.ORDER_RANDOM)
                                            "random" else "ordered"
                                    Text(
                                        "${playlist.entries.size} wallpapers · $orderText · " +
                                            "${com.wepwawet.rules.SuiteRulesStore
                                                .readPlaylistRules(context, playlist.id)
                                                .rules.size} automations",
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
        // v0.8.13: Enter in the field creates the playlist too — the user's
        // literal flow was "type in the name and enter".
        fun createNow() {
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) {
                val created = SuitePlaylistStore.create(context, trimmed)
                showNewDialog = false
                WepWawetLogger.appInfo("PlaylistsScreen: created",
                    "name" to trimmed, "id" to created.id)
                onOpenEditor(created.id)
            }
        }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    label = { Text("Playlist name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { createNow() }),
                )
            },
            confirmButton = {
                TextButton(onClick = { createNow() }) { Text("Create") }
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
