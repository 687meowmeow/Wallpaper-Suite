package com.wepwawet.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
    // v0.8.19 (random-pull rework): the one-shot "pull a random image and
    // freeze it into the list" button is GONE. In its place: a DYNAMIC
    // RANDOM SLOT ("it adds a random wallpaper that is randomy every time
    // it is ran") with the playlist's OWN tag settings ("it should also
    // have its own individual tag settings and stuff just like the
    // discover page"). See the Random slot section in the list below.
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

    // ── v0.8.19: the per-playlist random-slot settings (null = inherit
    // Discover's current value). ──
    var randomSettingsOpen by remember { mutableStateOf(false) }

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

            // ── v0.8.19/20: the DYNAMIC RANDOM SLOTS ──
            // "i want the current random system in the playlists to be
            // replaced by instead of getting a random wallpaper once and
            // adding it. it adds a random wallpaper that is randomy every
            // time it is ran. it should also have its own individual tag
            // settings and stuff just like the discover page." (v0.8.19)
            // v0.8.20: "you should be able to add more than one random" —
            // a stepper sets HOW MANY random slots the rotation has; each
            // slot pulls its own fresh image when the rotation lands on it.
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Random slots", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Each slot pulls a FRESH image every time the rotation " +
                                    "lands on it — never the same one twice.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // The slot-count stepper: − / count / ＋.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    save {
                                        randomSlotCount =
                                            (randomSlotCount - 1).coerceAtLeast(0)
                                    }
                                },
                                enabled = current.randomSlotCount > 0,
                            ) { Icon(Icons.Filled.Remove, contentDescription = "Fewer random slots") }
                            Text(
                                "${current.randomSlotCount}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 10.dp),
                            )
                            IconButton(
                                onClick = {
                                    save {
                                        randomSlotCount =
                                            (randomSlotCount + 1).coerceAtMost(9)
                                    }
                                },
                                enabled = current.randomSlotCount < 9,
                            ) { Icon(Icons.Filled.Add, contentDescription = "More random slots") }
                        }
                    }
                    if (current.randomSlotCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { randomSettingsOpen = !randomSettingsOpen }) {
                            Icon(Icons.Filled.Tune, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (randomSettingsOpen) "Hide slot settings" else "Slot tag settings")
                        }
                    }
                }
            }
            if (current.randomSlotCount > 0 && randomSettingsOpen) {
                // The slot's OWN tag settings — the same knobs as the
                // Discover page. Blank/null = inherit Discover's value.
                item {
                    var strictLine by remember(current.id) {
                        mutableStateOf(current.randomStrictTags?.joinToString(", ") ?: "")
                    }
                    var softLine by remember(current.id) {
                        mutableStateOf(current.randomSoftTags?.joinToString(", ") ?: "")
                    }
                    Column {
                        OutlinedTextField(
                            value = strictLine,
                            onValueChange = {
                                strictLine = it
                                save {
                                    randomStrictTags = it.split(',')
                                        .map { s -> s.trim() }
                                        .filter { s -> s.isNotEmpty() }
                                        .ifEmpty { null }
                                }
                            },
                            singleLine = true,
                            label = { Text("Strict tags (blank = Discover's)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = softLine,
                            onValueChange = {
                                softLine = it
                                save {
                                    randomSoftTags = it.split(',')
                                        .map { s -> s.trim() }
                                        .filter { s -> s.isNotEmpty() }
                                        .ifEmpty { null }
                                }
                            },
                            singleLine = true,
                            label = { Text("Soft tags (blank = Discover's)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Content tier (blank = Discover's)",
                            style = MaterialTheme.typography.labelMedium)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = current.randomR18Mode == null,
                                onClick = { save { randomR18Mode = null } },
                                shape = SegmentedButtonDefaults.itemShape(0, 4),
                            ) { Text("Auto") }
                            SegmentedButton(
                                selected = current.randomR18Mode == DiscoverPrefs.R18_PURE,
                                onClick = { save { randomR18Mode = DiscoverPrefs.R18_PURE } },
                                shape = SegmentedButtonDefaults.itemShape(1, 4),
                            ) { Text("Pure") }
                            SegmentedButton(
                                selected = current.randomR18Mode == DiscoverPrefs.R18_MIX,
                                onClick = { save { randomR18Mode = DiscoverPrefs.R18_MIX } },
                                shape = SegmentedButtonDefaults.itemShape(2, 4),
                            ) { Text("Mix") }
                            SegmentedButton(
                                selected = current.randomR18Mode == DiscoverPrefs.R18_NSFW,
                                onClick = { save { randomR18Mode = DiscoverPrefs.R18_NSFW } },
                                shape = SegmentedButtonDefaults.itemShape(3, 4),
                            ) { Text("NSFW") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Style: ${current.randomStyleValue ?: "(Discover's)"}",
                            style = MaterialTheme.typography.labelMedium)
                        var styleSlider by remember(current.id) {
                            mutableStateOf((current.randomStyleValue
                                ?: DiscoverPrefs.readStyle(context)).toFloat())
                        }
                        Slider(
                            value = styleSlider,
                            onValueChange = { styleSlider = it },
                            onValueChangeFinished = {
                                save { randomStyleValue = styleSlider.toInt() }
                            },
                            valueRange = 0f..100f,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Sources (blank = Discover's)",
                            style = MaterialTheme.typography.labelMedium)
                        // The playlist's own source picks; nothing ticked +
                        // "inherit" ON = Discover's set. The first tick
                        // switches to explicit.
                        val slotSources = current.randomSources?.toSet()
                        DiscoverPrefs.ALL_SOURCES.forEach { src ->
                            val checked = slotSources?.contains(src) ?: false
                            FilterChip(
                                selected = checked,
                                onClick = {
                                    val base = current.randomSources?.toMutableSet()
                                        ?: run {
                                            // First explicit pick: start from
                                            // Discover's current selection so
                                            // one tap doesn't empty everything.
                                            DiscoverPrefs.readSources(context)
                                                .toMutableSet()
                                        }
                                    if (checked) base.remove(src) else base.add(src)
                                    save { randomSources = base.sorted() }
                                },
                                label = { Text(DiscoverPrefs.sourceLabel(src)) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        if (current.randomSources != null) {
                            TextButton(onClick = { save { randomSources = null } }) {
                                Text("Back to Discover's sources")
                            }
                        }
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
