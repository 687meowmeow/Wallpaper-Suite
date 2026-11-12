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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.playlist.SuitePlaylistStore
import com.wepwawet.ui.components.SlimTopBar
import io.wallpaperengine.weutil.WallpaperInfoSparse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The playlist editor — one saved playlist.
 *
 *  • NAME         free-text, saved on change
 *  • ORDER        Random (shuffled rotation) or Ordered (the preset
 *                 order of the list below; reorder with the arrows)
 *  • INTERVAL    how often the rotation advances (minutes)
 *  • WALLPAPERS  any mix of animated (Scene/Video) and static — added
 *                 from the library grid with a multi-select picker
 *  • TIME RULES  "from HH:mm to HH:mm use this wallpaper or group" —
 *                 a rule pins the rotation to its entries while the
 *                 clock is inside its window; a single-entry rule simply
 *                 fixes one wallpaper for that window. Windows may wrap
 *                 past midnight.
 *
 * Everything is persisted through [SuitePlaylistStore] the moment it
 * changes; the live wallpaper service picks changes up immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistEditorScreen(
    playlistId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showAddPicker by remember { mutableStateOf(false) }
    var editingRuleIndex by remember { mutableStateOf<Int?>(null) } // -1 = new rule
    var libraryWallpapers by remember { mutableStateOf<List<WallpaperInfoSparse>>(emptyList()) }

    // v0.8.13 (creation bug): the playlist is loaded SYNCHRONOUSLY in the
    // remember initializer, not in a LaunchedEffect. The old async gap
    // rendered the "This playlist no longer exists." fallback for at least
    // one frame — and if the effect lost the race (or the store read
    // hiccupped) that fallback was all the user ever saw after creating a
    // playlist. The store is a tiny synchronous file read (the create
    // button itself already did the identical read on the main thread), so
    // doing it here is both safe and race-free: by the time this composable
    // runs, create() has fully written the store (same thread ordering).
    var playlist by remember(playlistId) {
        mutableStateOf(SuitePlaylistStore.read(context, playlistId))
    }
    if (playlist == null) {
        WepWawetLogger.appWarn("PlaylistEditor: playlist not found", "id" to playlistId)
    }

    val current = playlist ?: run {
        // Deleted / unknown playlist — nothing to edit.
        Scaffold(topBar = { SlimTopBar(title = "Playlist", onBack = onBack) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            ) {
                Text("This playlist no longer exists.")
            }
        }
        return
    }

    fun save(mutate: SuitePlaylistStore.Playlist.() -> Unit) {
        current.mutate()
        SuitePlaylistStore.upsert(context, current)
        // Re-read so the UI reflects the persisted object.
        playlist = SuitePlaylistStore.read(context, playlistId)
    }

    Scaffold(
        topBar = { SlimTopBar(title = current.name.ifEmpty { "Playlist" }, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Name ──
            item {
                var nameField by remember(current.id) {
                    mutableStateOf(current.name)
                }
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it.take(40) },
                    singleLine = true,
                    label = { Text("Playlist name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (nameField != current.name) {
                    LaunchedEffect(nameField) {
                        kotlinx.coroutines.delay(400)
                        save { name = nameField }
                    }
                }
            }

            // ── Order ──
            item {
                Text("Order", style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = current.orderMode == SuitePlaylistStore.ORDER_RANDOM,
                        onClick = { save { orderMode = SuitePlaylistStore.ORDER_RANDOM } },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Random") }
                    SegmentedButton(
                        selected = current.orderMode == SuitePlaylistStore.ORDER_ORDERED,
                        onClick = { save { orderMode = SuitePlaylistStore.ORDER_ORDERED } },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("In order") }
                }
                Text(
                    "\"In order\" follows the list below — the arrows move an entry " +
                        "up or down. \"Random\" shuffles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Interval ──
            item {
                var intervalSlider by remember(current.id) {
                    mutableStateOf(current.intervalMinutes.toFloat())
                }
                Text("Change every ${intervalSlider.toInt()} min",
                    style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = intervalSlider,
                    onValueChange = { intervalSlider = it },
                    onValueChangeFinished = {
                        save { intervalMinutes = intervalSlider.toInt().coerceIn(1, 240) }
                    },
                    valueRange = 1f..240f,
                )
            }

            // ── Entries ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Wallpapers (${current.entries.size})",
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showAddPicker = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }

            if (current.entries.isEmpty()) {
                item {
                    Text(
                        "Add any mix of animated and static wallpapers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(current.entries, key = { it.filepath }) { entry ->
                    val index = current.entries.indexOfFirst { it.filepath == entry.filepath }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title.ifEmpty {
                                    entry.filepath.substringAfterLast('/')
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                entry.type.ifEmpty { "wallpaper" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { save { entries.removeAt(index) } },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove")
                        }
                        IconButton(
                            onClick = {
                                if (index > 0) save {
                                    val moved = entries.removeAt(index)
                                    entries.add(index - 1, moved)
                                }
                            },
                            enabled = index > 0,
                        ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
                        IconButton(
                            onClick = {
                                if (index < current.entries.size - 1) save {
                                    val moved = entries.removeAt(index)
                                    entries.add(index + 1, moved)
                                }
                            },
                            enabled = index < current.entries.size - 1,
                        ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
                    }
                }
            }

            item { HorizontalDivider() }

            // ── Time rules ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Time rules (${current.timeRules.size})",
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = { editingRuleIndex = -1 },
                        enabled = current.entries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add rule")
                    }
                }
                Text(
                    "Pin a wallpaper — or a group — to a time window, e.g. " +
                        "22:00–06:00. While the clock is inside a window the " +
                        "rotation only uses that rule's wallpapers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (current.timeRules.isEmpty()) {
                item {
                    Text(
                        if (current.entries.isEmpty())
                            "Add wallpapers first, then create time rules."
                        else "No time rules — the playlist rotates around the clock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(current.timeRules.size) { ruleIndex ->
                    val rule = current.timeRules[ruleIndex]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingRuleIndex = ruleIndex },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${formatMinute(rule.startMinute)} – ${formatMinute(rule.endMinute)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${rule.entryFilePaths.size} wallpapers pinned",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { save { timeRules.removeAt(ruleIndex) } }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete rule",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // ── Add wallpapers picker (multi-select from the library) ──
    if (showAddPicker) {
        LaunchedEffect(Unit) {
            libraryWallpapers = withContext(Dispatchers.IO) {
                EngineLibrary.enumerateWallpapers(context)
            }
        }
        val alreadyIn = current.entries.map { it.filepath }.toSet()
        val selected = remember {
            mutableStateOf(alreadyIn.toMutableSet())
        }
        AlertDialog(
            onDismissRequest = { showAddPicker = false },
            title = { Text("Add wallpapers") },
            text = {
                Column {
                    if (libraryWallpapers.isEmpty()) {
                        Text("Loading library…")
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                        ) {
                            items(libraryWallpapers, key = { it.filePath }) { wallpaper ->
                                val path = wallpaper.filePath
                                val checked = path in selected.value
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selected.value = selected.value.toMutableSet().apply {
                                                if (checked) remove(path) else add(path)
                                            }
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            selected.value = selected.value.toMutableSet().apply {
                                                if (isChecked) add(path) else remove(path)
                                            }
                                        },
                                    )
                                    Column {
                                        Text(
                                            wallpaper.title.ifEmpty {
                                                path.substringAfterLast('/')
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            wallpaper.type.ifEmpty { "wallpaper" } +
                                                if (wallpaper.isLocal) " · local" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val chosen = selected.value
                        save {
                            entries.removeAll { it.filepath !in chosen }
                            for (wallpaper in libraryWallpapers) {
                                if (wallpaper.filePath in chosen &&
                                    entries.none { it.filepath == wallpaper.filePath }
                                ) {
                                    entries.add(
                                        SuitePlaylistStore.Entry(
                                            filepath = wallpaper.filePath,
                                            title = wallpaper.title,
                                            type = wallpaper.type,
                                        )
                                    )
                                }
                            }
                        }
                        WepWawetLogger.appInfo("PlaylistEditor: entries set",
                            "count" to chosen.size)
                        showAddPicker = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddPicker = false }) { Text("Cancel") }
            },
        )
    }

    // ── Time rule editor ──
    editingRuleIndex?.let { ruleIndex ->
        val existing = if (ruleIndex >= 0) current.timeRules.getOrNull(ruleIndex) else null
        TimeRuleEditorDialog(
            playlist = current,
            existing = existing,
            onDismiss = { editingRuleIndex = null },
            onSave = { startMinute, endMinute, paths ->
                save {
                    if (ruleIndex >= 0 && ruleIndex < timeRules.size) {
                        timeRules[ruleIndex] = SuitePlaylistStore.TimeRule(
                            startMinute, endMinute, paths.toMutableList())
                    } else {
                        timeRules.add(SuitePlaylistStore.TimeRule(
                            startMinute, endMinute, paths.toMutableList()))
                    }
                }
                WepWawetLogger.appInfo("PlaylistEditor: time rule saved",
                    "start" to startMinute,
                    "end" to endMinute,
                    "entries" to paths.size)
                editingRuleIndex = null
            },
        )
    }
}

/** 870 → "14:30". */
private fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/**
 * The time-rule editor: ONE Material3 [TimePicker] with a From/To
 * switch (two stacked pickers would overflow the dialog), plus a
 * multi-select of the playlist's entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRuleEditorDialog(
    playlist: SuitePlaylistStore.Playlist,
    existing: SuitePlaylistStore.TimeRule?,
    onDismiss: () -> Unit,
    onSave: (startMinute: Int, endMinute: Int, entryFilePaths: List<String>) -> Unit,
) {
    var editingStart by remember { mutableStateOf(true) }
    val startState = rememberTimePickerState(
        initialHour = (existing?.startMinute ?: 22 * 60) / 60,
        initialMinute = (existing?.startMinute ?: 22 * 60) % 60,
        is24Hour = true,
    )
    val endState = rememberTimePickerState(
        initialHour = (existing?.endMinute ?: 6 * 60) / 60,
        initialMinute = (existing?.endMinute ?: 6 * 60) % 60,
        is24Hour = true,
    )
    val chosen = remember {
        mutableStateOf(
            (existing?.entryFilePaths ?: emptyList()).toMutableSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New time rule" else "Edit time rule") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = editingStart,
                        onClick = { editingStart = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) {
                        Text("From %02d:%02d".format(startState.hour, startState.minute))
                    }
                    SegmentedButton(
                        selected = !editingStart,
                        onClick = { editingStart = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) {
                        Text("To %02d:%02d".format(endState.hour, endState.minute))
                    }
                }
                Spacer(Modifier.height(8.dp))
                TimePicker(state = if (editingStart) startState else endState)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Wallpapers in this window:",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.height(140.dp)) {
                    items(playlist.entries, key = { it.filepath }) { entry ->
                        val checked = entry.filepath in chosen.value
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chosen.value = chosen.value.toMutableSet().apply {
                                        if (checked) remove(entry.filepath)
                                        else add(entry.filepath)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    chosen.value = chosen.value.toMutableSet().apply {
                                        if (isChecked) add(entry.filepath)
                                        else remove(entry.filepath)
                                    }
                                },
                            )
                            Text(
                                entry.title.ifEmpty { entry.filepath.substringAfterLast('/') },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (chosen.value.isEmpty()) return@TextButton
                    val start = startState.hour * 60 + startState.minute
                    val end = endState.hour * 60 + endState.minute
                    onSave(start, end, chosen.value.toList())
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
