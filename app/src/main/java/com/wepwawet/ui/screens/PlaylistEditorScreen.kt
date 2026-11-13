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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { m ->
                                if (isRandom) {
                                    m.clickable { editingSlotMarker = entry.filepath }
                                } else m
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
                                Text(
                                    entry.title.ifEmpty {
                                        entry.filepath.substringAfterLast('/')
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (isRandom) "Random — fresh image each turn (tap to edit)"
                                else entry.type.ifEmpty { "wallpaper" },
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
    )

    if (showAddPicker) {
        var folderImages by remember {
            mutableStateOf<List<ImageFolderAssets.ImageEntry>>(emptyList())
        }
        // v0.8.16: search + group filter ("in the playlists menu the add
        // should have a search and you should also be able to select a
        // group. this should look good").
        var pickerSearch by remember { mutableStateOf("") }
        var pickerGroup by remember { mutableStateOf<String?>(null) }
        val groups = remember { LibraryPrefs.readGroups(context) }
        val groupOf = remember { LibraryPrefs.readGroupOf(context) }
        LaunchedEffect(Unit) {
            libraryWallpapers = withContext(Dispatchers.IO) {
                EngineLibrary.enumerateWallpapers(context)
            }
            folderImages = withContext(Dispatchers.IO) {
                ImageFolderAssets.readFolders(context).flatMap { folder ->
                    ImageFolderAssets.enumerateFolderImages(context, folder.treeUri)
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
            val images = folderImages.map { e ->
                val resolved = cropMap[e.uri]?.cropFilePath ?: e.uri
                PickItem(
                    filepath = resolved,
                    title = e.displayName.substringBeforeLast('.'),
                    type = "Image",
                    isContentUri = !resolved.startsWith("/"),
                )
            }
            (walls + images).distinctBy { it.filepath }
        }
        val visibleItems = remember(pickItems, pickerSearch, pickerGroup) {
            val q = pickerSearch.trim().lowercase()
            pickItems.filter { item ->
                (pickerGroup == null || groupOf[item.filepath] == pickerGroup) &&
                    (q.isEmpty() || item.title.lowercase().contains(q))
            }
        }
        val alreadyIn = current.entries.map { it.filepath }.toSet()
        val selected = remember(pickItems) {
            mutableStateOf(alreadyIn.toMutableSet())
        }
        AlertDialog(
            onDismissRequest = { showAddPicker = false },
            title = { Text("Add wallpapers") },
            text = {
                Column {
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
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val chosen = selected.value
                        save {
                            entries.removeAll { it.filepath !in chosen }
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
}

/** 870 → "14:30". */
private fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

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
                    }.getOrNull()
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
