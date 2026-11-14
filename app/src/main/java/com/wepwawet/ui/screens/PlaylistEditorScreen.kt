package com.wepwawet.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.playlist.SuitePlaylistStore
import com.wepwawet.ui.components.SlimTopBar
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weutil.WallpaperInfoSparse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Folder
import com.wepwawet.discover.DiscoverPrefs

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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showAddPicker by remember { mutableStateOf(false) }
    // v0.8.21: the random slot being edited in its OWN separate menu
    // ("it should open a separate menu when editing the random") — the
    // marker of the entry, null = closed.
    var editingSlotMarker by remember { mutableStateOf<String?>(null) }
    // v0.9.0: the group-block entry whose editor dialog is open.
    var editingBlockMarker by remember { mutableStateOf<String?>(null) }
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
        // v0.8.22 CRITICAL FIX (the crash + every "not live" report): the
        // old body mutated `current` — the VERY INSTANCE held by the
        // `playlist` state — in place, then re-read from disk. The re-read
        // is structurally EQUAL to the (already mutated) state value, and
        // mutableStateOf uses structural equality, so the assignment
        // changed NOTHING: no recomposition ever ran, the editor's rows
        // and buttons kept their STALE captured indices (tapping remove on
        // a stale row → IndexOutOfBoundsException: "Index 1 out of bounds
        // for length 1"), and every control looked dead until the screen
        // was closed and reopened ("when you push a button it shows the
        // change" — it never did). The mutation now runs on a FRESH
        // instance, so the new state value genuinely differs and the UI
        // updates the instant any button is pushed.
        val next = SuitePlaylistStore.read(context, playlistId) ?: return
        next.mutate()
        SuitePlaylistStore.upsert(context, next)
        playlist = SuitePlaylistStore.read(context, playlistId)
    }

    // v0.8.22: TRIM A VIDEO WALLPAPER AFTER ADDING ("there should be an
    // option to trim a video wallpaper after adding") — the video entry
    // being trimmed in the full-screen overlay below.
    var trimTarget by remember { mutableStateOf<SuitePlaylistStore.Entry?>(null) }

    // v0.9.0 (rules): the RULES section state — the store mirror + the
    // rule-editor overlay target. The rules live INSIDE the playlist editor
    // (one screen feel): the overlay below takes over the screen and exits
    // back here.
    var rulesData by remember {
        mutableStateOf(com.wepwawet.rules.SuiteRulesStore.readPlaylistRules(context, playlistId))
    }
    var editingRuleId by remember { mutableStateOf<String?>(null) }
    var showStarterPicker by remember { mutableStateOf(false) }
    var ruleRunResult by remember { mutableStateOf<String?>(null) }

    fun saveRules(mutate: com.wepwawet.rules.RuleModel.PlaylistRules.() -> Unit) {
        // Same live-state discipline as save(): mutate a FRESH copy so the
        // new state value genuinely differs and the UI updates instantly.
        val next = com.wepwawet.rules.SuiteRulesStore
            .readPlaylistRules(context, playlistId)
        next.mutate()
        com.wepwawet.rules.SuiteRulesStore.writePlaylistRules(context, next)
        rulesData = com.wepwawet.rules.SuiteRulesStore
            .readPlaylistRules(context, playlistId)
    }

    // The trim overlay takes over the whole screen (back = discard, the
    // entry is untouched; Save Clip re-points the ENTRY at the clip — the
    // original file is kept for other playlists).
    trimTarget?.let { target ->
        VideoTrimScreen(
            videoFile = java.io.File(target.filepath),
            itemTitle = target.title.ifBlank { "Video" },
            itemId = "playlist",
            onBack = { trimTarget = null },
            onSendComplete = { trimTarget = null },
            mode = "playlist",
            onClipSaved = { clip ->
                val oldPath = target.filepath
                save {
                    val i = entries.indexOfFirst { it.filepath == oldPath }
                    if (i >= 0) {
                        val e = entries[i]
                        val title =
                            if (e.title.endsWith(" (clip)")) e.title
                            else "${e.title.ifBlank { "Video" }} (clip)"
                        entries[i] = e.copy(filepath = clip.absolutePath, title = title)
                    }
                }
                WepWawetLogger.appInfo("PlaylistEditor: entry trimmed",
                    "old" to oldPath.takeLast(48),
                    "clip" to clip.name)
                trimTarget = null
            },
        )
        return
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
            // v0.8.21: random slots are ENTRIES ("you should be able to
            // move the random slots position as if it was a wallpaper") —
            // they sit in the SAME list with the same move/remove arrows,
            // and tapping one opens its OWN separate editor (below).
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Wallpapers (${current.entries.size})",
                        style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = { showAddPicker = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                        TextButton(onClick = {
                            save {
                                entries.add(
                                    com.wepwawet.playlist.RandomPullEngine.newSlotEntry())
                            }
                        }) {
                            Icon(Icons.Filled.Casino, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Add random")
                        }
                    }
                }
            }

            if (current.entries.isEmpty()) {
                item {
                    Text(
                        "Add any mix of animated and static wallpapers and random slots.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(current.entries, key = { it.filepath }) { entry ->
                    val index = current.entries.indexOfFirst { it.filepath == entry.filepath }
                    // v0.8.21: a random-slot entry renders as a Random row
                    // with the SAME arrows/remover as image entries (moving
                    // it moves its POSITION in the rotation), and tapping
                    // it opens its own SEPARATE editor menu.
                    val isRandom =
                        com.wepwawet.playlist.RandomPullEngine.isSlot(entry.filepath)
                    // v0.9.0: a GROUP BLOCK — the whole group as one entry.
                    val isBlock =
                        com.wepwawet.playlist.GroupBlocks.isGroupBlock(entry.filepath)
                    val blockGroup = entry.groupRef
                        ?: com.wepwawet.playlist.GroupBlocks.groupName(entry.filepath)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { m ->
                                when {
                                    isRandom -> m.clickable { editingSlotMarker = entry.filepath }
                                    isBlock -> m.clickable { editingBlockMarker = entry.filepath }
                                    else -> m
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isRandom) {
                                    Icon(
                                        Icons.Filled.Casino,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                if (isBlock) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    if (isBlock) "Group: $blockGroup"
                                    else entry.title.ifEmpty {
                                        entry.filepath.substringAfterLast('/')
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (isRandom) "Random — fresh image each turn (tap to edit)"
                                else if (isBlock)
                                    "Whole group — " +
                                        com.wepwawet.playlist.GroupBlocks.orderLabel(
                                            entry.blockOrder
                                                ?: com.wepwawet.playlist.GroupBlocks.ORDER_MANUAL) +
                                        " (tap to edit)"
                                else entry.type.ifEmpty { "wallpaper" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // v0.8.22: TRIM a video wallpaper after adding.
                        if (!isRandom && isTrimVideoFile(entry.filepath)) {
                            IconButton(onClick = { trimTarget = entry }) {
                                Icon(
                                    Icons.Filled.ContentCut,
                                    contentDescription = "Trim video",
                                )
                            }
                        }
                        // v0.8.22: every row action re-resolves the entry
                        // by its filepath INSIDE save (never a captured
                        // index) — a stale captured index is what produced
                        // "Index 1 out of bounds for length 1".
                        IconButton(
                            onClick = {
                                save {
                                    val i = entries.indexOfFirst { it.filepath == entry.filepath }
                                    if (i >= 0) entries.removeAt(i)
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove")
                        }
                        IconButton(
                            onClick = {
                                save {
                                    val i = entries.indexOfFirst { it.filepath == entry.filepath }
                                    if (i > 0) {
                                        val moved = entries.removeAt(i)
                                        entries.add(i - 1, moved)
                                    }
                                }
                            },
                            enabled = index > 0,
                        ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
                        IconButton(
                            onClick = {
                                save {
                                    val i = entries.indexOfFirst { it.filepath == entry.filepath }
                                    if (i in 0 until entries.size - 1) {
                                        val moved = entries.removeAt(i)
                                        entries.add(i + 1, moved)
                                    }
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
                        // v0.8.22: bounds-checked inside save (stale-index-proof).
                        IconButton(onClick = {
                            save {
                                if (ruleIndex in timeRules.indices) {
                                    timeRules.removeAt(ruleIndex)
                                }
                            }
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete rule",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item { HorizontalDivider() }

            // ── v0.9.0: RULES — the playlist reacts to the phone ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Rules (${rulesData.rules.size})",
                        style = MaterialTheme.typography.titleMedium)
                    Row {
                        TextButton(onClick = { showStarterPicker = true }) {
                            Text("Starters")
                        }
                        TextButton(onClick = {
                            val rule = com.wepwawet.rules.RuleModel.Rule(
                                name = "Rule ${rulesData.rules.size + 1}")
                            saveRules { rules.add(rule) }
                            editingRuleId = rule.id
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("New rule")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "A rule reacts to what happens on your phone — pick triggers, " +
                        "optionally gate it, and stack action blocks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // First match wins / every match runs.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("When several rules match:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        saveRules {
                            mode = if (mode == "first") "all" else "first"
                        }
                    }) {
                        Text(
                            if (rulesData.mode == "first") "First match wins"
                            else "Every match runs"
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (rulesData.rules.isEmpty()) {
                item {
                    Text(
                        "No rules yet — add one, or copy a ready-made starter " +
                            "(night wallpaper, charging, Wi-Fi).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(rulesData.rules, key = { it.id }) { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { editingRuleId = rule.id }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                triggerSummary(rule) +
                                    (if (rule.lastFiredAt > 0) "  ·  fired " +
                                        relativeTime(rule.lastFiredAt) else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Run it now.
                        IconButton(onClick = {
                            scope.launch {
                                ruleRunResult = com.wepwawet.rules.RuleEngine
                                    .fireRuleManual(context, playlistId, rule)
                            }
                        }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Run now") }
                        androidx.compose.material3.Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on ->
                                saveRules {
                                    rules.firstOrNull { it.id == rule.id }?.enabled = on
                                }
                            },
                        )
                    }
                }
            }

            ruleRunResult?.let { result ->
                item {
                    Text(
                        "Last run: $result",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // ── Add wallpapers picker (multi-select from the library) ──
    // v0.8.15: the picker is a THUMBNAIL GRID of everything addable —
    // engine wallpapers (Scene/Video .mpkg downloads + local imports) AND
    // folder images ("the add on the playlist menu should actually show
    // the images and look good and be more than animated ones"). The old
    // picker was a checkbox list of titles only.
    data class PickItem(
        val filepath: String,
        val title: String,
        val type: String,
        val isContentUri: Boolean,
        // v0.8.22: the source folder for folder images (group-link filter).
        val folderUri: String? = null,
    )

    if (showAddPicker) {
        var folderImages by remember {
            mutableStateOf<List<Pair<String, ImageFolderAssets.ImageEntry>>>(emptyList())
        }
        // v0.8.16: search + group filter ("in the playlists menu the add
        // should have a search and you should also be able to select a
        // group. this should look good"). v0.8.22: a group filter also
        // matches its LINKED folder's images (the unified group = manual
        // members + the linked folder's live scan).
        var pickerSearch by remember { mutableStateOf("") }
        var pickerGroup by remember { mutableStateOf<String?>(null) }
        // v0.9.0: sort the picker ("let me sort and filter by group").
        var pickerSort by remember { mutableStateOf("default") }
        val groups = remember { LibraryPrefs.readGroups(context) }
        val groupOf = remember { LibraryPrefs.readGroupOf(context) }
        val groupFolders = remember { LibraryPrefs.readGroupFolders(context) }
        LaunchedEffect(Unit) {
            libraryWallpapers = withContext(Dispatchers.IO) {
                EngineLibrary.enumerateWallpapers(context)
            }
            folderImages = withContext(Dispatchers.IO) {
                ImageFolderAssets.readFolders(context).flatMap { folder ->
                    ImageFolderAssets.enumerateFolderImages(context, folder.treeUri)
                        .map { folder.treeUri to it }
                }
            }
        }
        val pickItems = remember(libraryWallpapers, folderImages) {
            // v0.8.16: folder images resolve to their CROPPED copies when
            // the user has edited them — the playlist must rotate what the
            // user sees.
            val cropMap = ImageCropStore.read(context)
            val walls = libraryWallpapers.map { w ->
                PickItem(
                    filepath = w.filePath,
                    title = w.title.ifEmpty { w.filePath.substringAfterLast('/') },
                    type = w.type.ifEmpty { "wallpaper" },
                    isContentUri = w.isLocal || w.filePath.startsWith("content://"),
                )
            }
            val images = folderImages.map { (treeUri, e) ->
                val resolved = cropMap[e.uri]?.cropFilePath ?: e.uri
                PickItem(
                    filepath = resolved,
                    title = e.displayName.substringBeforeLast('.'),
                    type = "Image",
                    isContentUri = !resolved.startsWith("/"),
                    folderUri = treeUri,
                )
            }
            (walls + images).distinctBy { it.filepath }
        }
        val visibleItems = remember(pickItems, pickerSearch, pickerGroup, groupFolders, pickerSort) {
            val q = pickerSearch.trim().lowercase()
            // v0.8.22: the group filter matches the group's MANUAL members
            // and its LINKED folder's images — the same grid the library
            // shows for that group.
            val link = pickerGroup?.let { groupFolders[it] }
            val filtered = pickItems.filter { item ->
                (pickerGroup == null || groupOf[item.filepath] == pickerGroup ||
                    (link != null && item.folderUri == link)) &&
                    (q.isEmpty() || item.title.lowercase().contains(q))
            }
            when (pickerSort) {
                "az" -> filtered.sortedBy { it.title.lowercase() }
                "za" -> filtered.sortedByDescending { it.title.lowercase() }
                else -> filtered
            }
        }
        val alreadyIn = current.entries.map { it.filepath }.toSet()
        val selected = remember(pickItems) {
            mutableStateOf(alreadyIn.toMutableSet())
        }
        // v0.9.0: the picker is SLIGHTLY BIGGER ("make that add wallpapers
        // popup slightly bigger than it is now") — near-full width + a
        // taller grid, via usePlatformDefaultWidth = false.
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showAddPicker = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .heightIn(max = 640.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add wallpapers",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    if (pickItems.isEmpty()) {
                        Text("Loading library…")
                    } else {
                        OutlinedTextField(
                            value = pickerSearch,
                            onValueChange = { pickerSearch = it },
                            singleLine = true,
                            label = { Text("Search") },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (groups.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = pickerGroup == null,
                                    onClick = { pickerGroup = null },
                                    label = { Text("All") },
                                )
                                groups.forEach { group ->
                                    FilterChip(
                                        selected = pickerGroup == group,
                                        onClick = {
                                            pickerGroup = if (pickerGroup == group) null else group
                                        },
                                        label = { Text(group) },
                                    )
                                }
                            }
                            // v0.9.0: "or add the whole group as a block with
                            // one tap" — one tap per group adds a LINKED block
                            // entry (the block follows the group's members).
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "Block:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                groups.forEach { group ->
                                    val alreadyBlocked =
                                        current.entries.any {
                                            it.groupRef == group
                                        }
                                    androidx.compose.material3.AssistChip(
                                        onClick = {
                                            save {
                                                if (entries.none { it.groupRef == group }) {
                                                    entries.add(
                                                        com.wepwawet.playlist.GroupBlocks
                                                            .newBlockEntry(group)
                                                    )
                                                }
                                            }
                                            WepWawetLogger.appInfo(
                                                "PlaylistEditor: group block added",
                                                "group" to group)
                                            showAddPicker = false
                                        },
                                        enabled = !alreadyBlocked,
                                        label = {
                                            Text(
                                                if (alreadyBlocked) "✓ $group"
                                                else "＋ $group"
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${selected.value.size} selected · " +
                                    "${visibleItems.size} shown",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            // v0.9.0: sort cycler — Default → A→Z → Z→A.
                            TextButton(onClick = {
                                pickerSort = when (pickerSort) {
                                    "default" -> "az"
                                    "az" -> "za"
                                    else -> "default"
                                }
                            }) {
                                Text(
                                    when (pickerSort) {
                                        "az" -> "Sort: A→Z"
                                        "za" -> "Sort: Z→A"
                                        else -> "Sort: Default"
                                    }
                                )
                            }
                            // One tap selects every VISIBLE item — with a
                            // group chip active, that's the whole group.
                            TextButton(
                                onClick = {
                                    selected.value = selected.value
                                        .toMutableSet()
                                        .apply { addAll(visibleItems.map { it.filepath }) }
                                },
                            ) { Text(if (pickerGroup == null) "Select all" else "Select group") }
                            TextButton(
                                onClick = { selected.value = alreadyIn.toMutableSet() },
                            ) { Text("Reset") }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            gridItems(visibleItems, key = { it.filepath }) { item ->
                                val checked = item.filepath in selected.value
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .then(
                                            if (checked)
                                                Modifier.border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(10.dp),
                                                )
                                            else Modifier
                                        )
                                        .clickable {
                                            selected.value =
                                                selected.value.toMutableSet().apply {
                                                    if (checked) remove(item.filepath)
                                                    else add(item.filepath)
                                                }
                                        }
                                        .padding(4.dp),
                                ) {
                                    Box {
                                        PlaylistPickThumb(
                                            filepath = item.filepath,
                                            isContentUri = item.isContentUri,
                                        )
                                        if (checked) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(4.dp)
                                                    .size(20.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary,
                                                        CircleShape,
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    Text(
                                        item.type,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            TextButton(onClick = { showAddPicker = false }) { Text("Cancel") }
                            TextButton(
                                onClick = {
                                    val chosen = selected.value
                                    save {
                                        // v0.9.0: group blocks are kept — they
                                        // are playlist citizens, not pick items.
                                        entries.removeAll {
                                            it.filepath !in chosen &&
                                                !com.wepwawet.playlist.GroupBlocks
                                                    .isGroupBlock(it.filepath)
                                        }
                                        for (item in pickItems) {
                                            if (item.filepath in chosen &&
                                                entries.none { it.filepath == item.filepath }
                                            ) {
                                                entries.add(
                                                    SuitePlaylistStore.Entry(
                                                        filepath = item.filepath,
                                                        title = item.title,
                                                        type = item.type,
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
                        }
                    }
                }
            }
        }
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

    // ── v0.8.21: the random slot's OWN separate editor menu ──
    // ("it should open a separate menu when editing the random"). One
    // Save commits the whole slot at once — the running rotation picks
    // the change up immediately (upsert → service sync).
    editingSlotMarker?.let { marker ->
        val slot = current.entries.firstOrNull { it.filepath == marker }
        if (slot == null) {
            editingSlotMarker = null
        } else {
            SlotEditorDialog(
                slot = slot,
                onDismiss = { editingSlotMarker = null },
                onSave = { strict, soft, style, r18, sources ->
                    save {
                        val i = entries.indexOfFirst { it.filepath == marker }
                        if (i >= 0) {
                            entries[i].randomStrictTags = strict
                            entries[i].randomSoftTags = soft
                            entries[i].randomStyleValue = style
                            entries[i].randomR18Mode = r18
                            entries[i].randomSources = sources
                        }
                    }
                    WepWawetLogger.appInfo("PlaylistEditor: random slot saved",
                        "slot" to marker.takeLast(18),
                        "strict" to (strict?.size ?: 0),
                        "soft" to (soft?.size ?: 0))
                    editingSlotMarker = null
                },
            )
        }
    }

    // ── v0.9.0: the starter-rule picker (a few ready-made rules to copy).
    if (showStarterPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStarterPicker = false },
            title = { Text("Copy a starter rule") },
            text = {
                Column {
                    Text(
                        "Ready-made starting rules — copied into this playlist, " +
                            "then yours to adapt with taps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    for ((label, _) in com.wepwawet.rules.RuleStarter.starters()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val copy = when (label) {
                                        "Night wallpaper" -> com.wepwawet.rules.RuleStarter.nightRule()
                                        "Charging" -> com.wepwawet.rules.RuleStarter.chargingRule()
                                        else -> com.wepwawet.rules.RuleStarter.wifiRule()
                                    }
                                    saveRules { rules.add(copy) }
                                    showStarterPicker = false
                                    editingRuleId = copy.id
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStarterPicker = false }) { Text("Cancel") }
            },
        )
    }

    // ── v0.9.0: the RULE EDITOR — a full-screen overlay INSIDE the
    // playlist editor (one screen feel). It edits the rule in the store
    // and exits back here.
    editingRuleId?.let { ruleId ->
        com.wepwawet.ui.screens.RuleEditorScreen(
            playlistId = playlistId,
            ruleId = ruleId,
            onExit = {
                rulesData = com.wepwawet.rules.SuiteRulesStore
                    .readPlaylistRules(context, playlistId)
                editingRuleId = null
            },
            onDeleted = {
                saveRules { rules.removeAll { it.id == ruleId } }
                editingRuleId = null
            },
        )
        return
    }

    // ── v0.9.0: the group-block's OWN editor ── order mode + the manual
    // member order. The block stays linked to the group: members sync with
    // the group automatically; only the ORDER is the block's own.
    editingBlockMarker?.let { marker ->
        val block = current.entries.firstOrNull { it.filepath == marker }
        if (block == null) {
            editingBlockMarker = null
        } else {
            BlockEditorDialog(
                block = block,
                onDismiss = { editingBlockMarker = null },
                onSave = { order, manual ->
                    save {
                        val i = entries.indexOfFirst { it.filepath == marker }
                        if (i >= 0) {
                            entries[i].blockOrder = order
                            entries[i].blockManualMembers = manual
                        }
                    }
                    WepWawetLogger.appInfo("PlaylistEditor: group block saved",
                        "group" to (block.groupRef ?: "?"),
                        "order" to order,
                        "manual_members" to manual.size)
                    editingBlockMarker = null
                },
            )
        }
    }
}

/** 870 → "14:30". */
private fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** v0.9.0: one line describing what starts a rule (the rule list's subtitle). */
fun triggerSummary(rule: com.wepwawet.rules.RuleModel.Rule): String {
    if (rule.triggers.isEmpty()) return "No triggers yet"
    val labels = rule.triggers.take(3).map { t ->
        when (t.type) {
            com.wepwawet.rules.RuleModel.TRIG_TIME_WINDOW ->
                "${formatMinute(t.minuteStart)}–${formatMinute(t.minuteEnd)}"
            com.wepwawet.rules.RuleModel.TRIG_TIME_OF_DAY -> "at ${formatMinute(t.timeMinute)}"
            com.wepwawet.rules.RuleModel.TRIG_DAY_OF_WEEK ->
                "on " + listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                    .getOrElse(t.dayOfWeek) { "any day" }
            com.wepwawet.rules.RuleModel.TRIG_DATE -> "on a date"
            com.wepwawet.rules.RuleModel.TRIG_INTERVAL -> "every ${t.intervalMinutes} min"
            com.wepwawet.rules.RuleModel.TRIG_BATTERY_BELOW -> "battery < ${t.batteryLevel}%"
            com.wepwawet.rules.RuleModel.TRIG_BATTERY_ABOVE -> "battery > ${t.batteryLevel}%"
            com.wepwawet.rules.RuleModel.TRIG_WIFI_CONNECT ->
                if (t.networkSsid.isEmpty()) "Wi-Fi connects" else "joins ${t.networkSsid}"
            com.wepwawet.rules.RuleModel.TRIG_WIFI_DISCONNECT ->
                if (t.networkSsid.isEmpty()) "Wi-Fi disconnects" else "leaves ${t.networkSsid}"
            com.wepwawet.rules.RuleModel.TRIG_BT_DEVICE_IN ->
                if (t.deviceName.isEmpty()) "a device connects" else "${t.deviceName} connects"
            com.wepwawet.rules.RuleModel.TRIG_BT_DEVICE_OUT ->
                if (t.deviceName.isEmpty()) "a device disconnects" else "${t.deviceName} disconnects"
            else -> triggerLabel(t.type)
        }
    }
    return labels.joinToString(" / ") +
        (if (rule.triggers.size > 3) " +${rule.triggers.size - 3}" else "")
}

/** The plain label for a trigger type (also used by the rule editor). */
fun triggerLabel(type: String): String = when (type) {
    com.wepwawet.rules.RuleModel.TRIG_TIME_WINDOW -> "Time window"
    com.wepwawet.rules.RuleModel.TRIG_TIME_OF_DAY -> "Time of day"
    com.wepwawet.rules.RuleModel.TRIG_DAY_OF_WEEK -> "Day of week"
    com.wepwawet.rules.RuleModel.TRIG_DATE -> "Date"
    com.wepwawet.rules.RuleModel.TRIG_INTERVAL -> "Repeating interval"
    com.wepwawet.rules.RuleModel.TRIG_SCREEN_ON -> "Screen turns on"
    com.wepwawet.rules.RuleModel.TRIG_SCREEN_OFF -> "Screen turns off"
    com.wepwawet.rules.RuleModel.TRIG_UNLOCKED -> "Device unlocked"
    com.wepwawet.rules.RuleModel.TRIG_CHARGER_IN -> "Charger plugged in"
    com.wepwawet.rules.RuleModel.TRIG_CHARGER_OUT -> "Charger unplugged"
    com.wepwawet.rules.RuleModel.TRIG_BATTERY_BELOW -> "Battery drops below"
    com.wepwawet.rules.RuleModel.TRIG_BATTERY_ABOVE -> "Battery rises above"
    com.wepwawet.rules.RuleModel.TRIG_SAVER_ON -> "Battery saver turns on"
    com.wepwawet.rules.RuleModel.TRIG_SAVER_OFF -> "Battery saver turns off"
    com.wepwawet.rules.RuleModel.TRIG_WIFI_CONNECT -> "Wi-Fi connects"
    com.wepwawet.rules.RuleModel.TRIG_WIFI_DISCONNECT -> "Wi-Fi disconnects"
    com.wepwawet.rules.RuleModel.TRIG_MOBILE_DATA -> "Mobile data active"
    com.wepwawet.rules.RuleModel.TRIG_HEADPHONES_IN -> "Headphones connect"
    com.wepwawet.rules.RuleModel.TRIG_HEADPHONES_OUT -> "Headphones disconnect"
    com.wepwawet.rules.RuleModel.TRIG_BT_DEVICE_IN -> "Bluetooth device connects"
    com.wepwawet.rules.RuleModel.TRIG_BT_DEVICE_OUT -> "Bluetooth device disconnects"
    com.wepwawet.rules.RuleModel.TRIG_ORIENTATION -> "Orientation changes"
    com.wepwawet.rules.RuleModel.TRIG_WALLPAPER_CHANGED -> "Wallpaper changed"
    com.wepwawet.rules.RuleModel.TRIG_WORKSHOP_DOWNLOADED -> "Workshop download finished"
    com.wepwawet.rules.RuleModel.TRIG_WORKSHOP_UPDATE -> "Workshop wallpaper updated"
    com.wepwawet.rules.RuleModel.TRIG_DISCOVER_QUEUE_LOW -> "Discover queue ran low"
    com.wepwawet.rules.RuleModel.TRIG_MANUAL_BUTTON -> "Button on this screen"
    com.wepwawet.rules.RuleModel.TRIG_NOTIFICATION_BUTTON -> "Notification button"
    com.wepwawet.rules.RuleModel.TRIG_QS_TILE -> "Quick Settings tile"
    com.wepwawet.rules.RuleModel.TRIG_FROM_RULE -> "Started by another rule"
    else -> type
}

/** "3 min ago"-style relative time (the rule list's last-fired note). */
fun relativeTime(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000L
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} d ago"
    }
}

/**
 * v0.8.22: can this entry's video be trimmed in place? A raw video FILE
 * on local storage (folder-imported/local videos) can be stream-copied;
 * engine .mpkg packages are containers and are out of reach here.
 */
private fun isTrimVideoFile(path: String): Boolean =
    path.startsWith("/") && listOf(
        ".mp4", ".webm", ".mkv", ".avi", ".mov", ".m4v", ".3gp", ".ts", ".flv",
    ).any { path.endsWith(it, ignoreCase = true) }

/**
 * v0.9.0: the SEPARATE group-block editor — the block's ORDER MODE and,
 * in manual mode, the dragged member order. The member LIST itself stays
 * linked to the group (add/remove in the group and the playlist follows;
 * only the ordering is the block's own).
 */
@Composable
private fun BlockEditorDialog(
    block: SuitePlaylistStore.Entry,
    onDismiss: () -> Unit,
    onSave: (order: String, manual: List<String>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var order by remember(block.filepath) {
        mutableStateOf(block.blockOrder ?: com.wepwawet.playlist.GroupBlocks.ORDER_MANUAL)
    }
    // The current members, loaded async (the resolve touches the engine
    // library + folder scans); refresh on open + on demand.
    var members by remember(block.filepath) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loaded by remember(block.filepath) { mutableStateOf(false) }
    var manual by remember(block.filepath) {
        mutableStateOf(block.blockManualMembers ?: emptyList())
    }
    androidx.compose.runtime.LaunchedEffect(block.filepath) {
        val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.wepwawet.playlist.GroupBlocks.resolve(context, block)
        }
        members = resolved.members
        // Reconcile the stored manual order with the live member set.
        val paths = resolved.paths
        val stored = manual
        val known = HashSet(paths)
        manual = stored.filter { it in known } + paths.filter { it !in stored }
        loaded = true
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group: ${block.groupRef ?: "?"}") },
        text = {
            Column {
                Text(
                    "The block stays linked to this group — members added or removed " +
                        "later follow automatically. This only sets how the block is ordered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("Order", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    for (mode in com.wepwawet.playlist.GroupBlocks.ORDER_MODES) {
                        androidx.compose.material3.FilterChip(
                            selected = order == mode,
                            onClick = { order = mode },
                            label = {
                                Text(com.wepwawet.playlist.GroupBlocks.orderLabel(mode))
                            },
                        )
                    }
                }
                if (order == com.wepwawet.playlist.GroupBlocks.ORDER_MANUAL) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Manual order (${members.size}) — drag with the arrows",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (!loaded) {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (members.isEmpty()) {
                                Text(
                                    "This group is empty — add wallpapers to it in the " +
                                        "library first (its images will appear here).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            for ((mi, m) in members.withIndex()) {
                                val rowPath = manual.getOrNull(mi) ?: m.first
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        m.second.ifEmpty { rowPath.substringAfterLast('/') },
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            if (mi > 0) {
                                                val list = manual.toMutableList()
                                                val moved = list.removeAt(mi)
                                                list.add(mi - 1, moved)
                                                manual = list
                                            }
                                        },
                                        enabled = mi > 0,
                                    ) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                                    IconButton(
                                        onClick = {
                                            if (mi < manual.size - 1) {
                                                val list = manual.toMutableList()
                                                val moved = list.removeAt(mi)
                                                list.add(mi + 1, moved)
                                                manual = list
                                            }
                                        },
                                        enabled = mi < manual.size - 1,
                                    ) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(order, manual) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * v0.8.21: the SEPARATE random-slot editor ("it should open a separate
 * menu when editing the random"). The same knobs the Discover page
 * exposes — strict/soft tags, style, content tier, sources — where blank
 * or Auto INHERITS Discover's current value. One Save commits the whole
 * slot (the live rotation picks the change up immediately).
 */
@Composable
private fun SlotEditorDialog(
    slot: SuitePlaylistStore.Entry,
    onDismiss: () -> Unit,
    onSave: (
        strict: List<String>?,
        soft: List<String>?,
        style: Int?,
        r18: Int?,
        sources: List<String>?,
    ) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Draft state (keyed on the slot): typed values commit on Save.
    var strictLine by remember(slot.filepath) {
        mutableStateOf(slot.randomStrictTags?.joinToString(", ") ?: "")
    }
    var softLine by remember(slot.filepath) {
        mutableStateOf(slot.randomSoftTags?.joinToString(", ") ?: "")
    }
    var styleAuto by remember(slot.filepath) {
        mutableStateOf(slot.randomStyleValue == null)
    }
    var styleValue by remember(slot.filepath) {
        mutableStateOf(
            (slot.randomStyleValue ?: DiscoverPrefs.readStyle(context)).toFloat())
    }
    var r18 by remember(slot.filepath) { mutableStateOf(slot.randomR18Mode) }
    var sources by remember(slot.filepath) {
        mutableStateOf(slot.randomSources?.toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Casino, contentDescription = null) },
        title = { Text("Edit random") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(
                    androidx.compose.foundation.rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = strictLine,
                    onValueChange = { strictLine = it },
                    singleLine = true,
                    label = { Text("Strict tags (blank = Discover's)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = softLine,
                    onValueChange = { softLine = it },
                    singleLine = true,
                    label = { Text("Soft tags (blank = Discover's)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Content tier", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = r18 == null,
                        onClick = { r18 = null },
                        shape = SegmentedButtonDefaults.itemShape(0, 4),
                    ) { Text("Auto") }
                    SegmentedButton(
                        selected = r18 == DiscoverPrefs.R18_PURE,
                        onClick = { r18 = DiscoverPrefs.R18_PURE },
                        shape = SegmentedButtonDefaults.itemShape(1, 4),
                    ) { Text("Pure") }
                    SegmentedButton(
                        selected = r18 == DiscoverPrefs.R18_MIX,
                        onClick = { r18 = DiscoverPrefs.R18_MIX },
                        shape = SegmentedButtonDefaults.itemShape(2, 4),
                    ) { Text("Mix") }
                    SegmentedButton(
                        selected = r18 == DiscoverPrefs.R18_NSFW,
                        onClick = { r18 = DiscoverPrefs.R18_NSFW },
                        shape = SegmentedButtonDefaults.itemShape(3, 4),
                    ) { Text("NSFW") }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (styleAuto) "Style (Discover's)" else "Style ${styleValue.toInt()}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (styleAuto) "Auto" else "Manual",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Switch(
                            checked = !styleAuto,
                            onCheckedChange = { on -> styleAuto = !on },
                        )
                    }
                }
                Slider(
                    value = styleValue,
                    onValueChange = { styleValue = it },
                    enabled = !styleAuto,
                    valueRange = 0f..100f,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sources" + if (sources == null) " (Discover's)" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
                DiscoverPrefs.ALL_SOURCES.forEach { src ->
                    val checked = sources?.contains(src) ?: false
                    FilterChip(
                        selected = checked,
                        onClick = {
                            val base = sources?.toMutableSet()
                                ?: run {
                                    // First explicit pick: start from Discover's
                                    // current selection so one tap doesn't
                                    // empty everything.
                                    DiscoverPrefs.readSources(context).toMutableSet()
                                }
                            if (checked) base.remove(src) else base.add(src)
                            sources = base.toSet()
                        },
                        label = { Text(DiscoverPrefs.sourceLabel(src)) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                if (sources != null) {
                    TextButton(onClick = { sources = null }) {
                        Text("Back to Discover's sources")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        strictLine.split(',')
                            .map { s -> s.trim() }
                            .filter { s -> s.isNotEmpty() }
                            .ifEmpty { null },
                        softLine.split(',')
                            .map { s -> s.trim() }
                            .filter { s -> s.isNotEmpty() }
                            .ifEmpty { null },
                        if (styleAuto) null else styleValue.toInt(),
                        r18,
                        sources?.sorted(),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

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

/**
 * v0.8.15: one thumbnail tile for the playlist picker grid. Content URIs
 * (local imports + folder images) load through COIL — the same loader the
 * library grid uses; engine .mpkg wallpapers decode their preview bitmap
 * exactly like the library cards do (WeEnginePrefs.getWallpaperPreviewBitmap).
 */
@Composable
private fun PlaylistPickThumb(
    filepath: String,
    isContentUri: Boolean,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(8.dp)
    when {
        // v0.8.16: cropped folder images are plain files; local imports are
        // content uris — both load straight through COIL.
        isContentUri || filepath.startsWith("/") -> SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(
                    if (filepath.startsWith("/")) java.io.File(filepath)
                    else android.net.Uri.parse(filepath)
                )
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape),
        )
        // Engine .mpkg wallpapers — the library-card preview decoder.
        else -> {
            var preview by remember(filepath) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(filepath) {
                preview = withContext(Dispatchers.IO) {
                    runCatching {
                        val info = WallpaperInfoSparse()
                        info.filePath = filepath
                        WeEnginePrefs.getWallpaperPreviewBitmap(
                            context, EngineLibrary.sceneLib(context), info)
                    }
                        .onFailure {
                            WepWawetLogger.appWarn("PlaylistEditorScreen: swallowed failure at line 1149",
                                "exception" to it.javaClass.simpleName,
                                "message" to (it.message ?: ""))
                        }
                    .getOrNull()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = preview
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
