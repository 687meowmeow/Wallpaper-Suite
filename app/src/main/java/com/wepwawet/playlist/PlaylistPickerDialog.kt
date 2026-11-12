package com.wepwawet.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import com.wepwawet.log.WepWawetLogger

/**
 * The "add a wallpaper to a playlist" picker, shared by the library
 * context menu and the preview screen's playlist button.
 *
 * Lists every saved suite playlist (radio-style: tap to add), plus
 * "New playlist…" (inline name field) and "Manage playlists" (opens the
 * playlist manager screen).
 */
@Composable
fun PlaylistPickerDialog(
    wallpaperTitle: String,
    onDismiss: () -> Unit,
    onManagePlaylists: () -> Unit,
    onPicked: (playlistId: String) -> Unit,
) {
    val context = LocalContext.current
    var playlists by remember { mutableStateOf<List<SuitePlaylistStore.Playlist>>(emptyList()) }
    var showNewField by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        playlists = SuitePlaylistStore.readAll(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                Text(
                    wallpaperTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (playlists.isEmpty() && !showNewField) {
                    Text("No playlists yet — create one below.")
                } else {
                    LazyColumn(modifier = Modifier.height(220.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPicked(playlist.id)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${playlist.entries.size} wallpapers · " +
                                            if (playlist.orderMode == SuitePlaylistStore.ORDER_RANDOM)
                                                "random" else "ordered",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (showNewField) {
                    // v0.8.13: Enter creates too (the user's literal flow).
                    fun createNow() {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            val created = SuitePlaylistStore.create(context, trimmed)
                            WepWawetLogger.appInfo("PlaylistPicker: playlist created",
                                "name" to trimmed, "id" to created.id)
                            onPicked(created.id)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it.take(40) },
                            singleLine = true,
                            label = { Text("Playlist name") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { createNow() }),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { createNow() }) { Text("Create") }
                    }
                } else {
                    Row {
                        TextButton(onClick = { showNewField = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("New playlist")
                        }
                        TextButton(onClick = onManagePlaylists) { Text("Manage") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
