package com.wepwawet.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.wepwawet.discover.DiscoverPrefs
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.playlist.GroupBlocks
import com.wepwawet.playlist.RandomPullEngine
import com.wepwawet.playlist.SuitePlaylistStore
import com.wepwawet.rules.RuleModel
import com.wepwawet.rules.SuiteRulesStore
import com.wepwawet.ui.components.SlimTopBar
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weutil.WallpaperInfoSparse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The playlist editor — ONE focused screen per the v0.9.0 redesign spec:
 * its wallpapers, and what makes it change. Nothing else.
 *
 *  • NAME       in the top bar; tap it to rename (40 chars, debounced save)
 *  • ACTIVE     a badge when this is the active playlist, one tap to set
 *  • ORDER      Random / In order, compact under the top bar
 *  • WALLPAPERS dense rows (thumb / random-slot / group-block) with a drag
 *               handle to reorder and a ⋮ menu (Edit, Trim, Remove)
 *  • CHANGES WHEN…  the playlist's automations — every row is
 *               "When [trigger] → Do [action]", with an enable switch, a
 *               run-now button and its result INLINE on the row
 *
 * There is NO interval field: how often the playlist changes is just a
 * trigger ("Every N …" → Next wallpaper) in Changes when…. A playlist with
 * no automations never changes by itself.
 *
 * Everything persists the moment it changes (fresh-copy save discipline —
 * see save()); mutations find entries by filepath, never a captured index.
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
    var editingSlotMarker by remember { mutableStateOf<String?>(null) }
    var editingBlockMarker by remember { mutableStateOf<String?>(null) }
    var libraryWallpapers by remember { mutableStateOf<List<WallpaperInfoSparse>>(emptyList()) }
    var showRename by remember { mutableStateOf(false) }
    // The ＋ dropdowns.
    var showPlusMenu by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var showTriggerSheet by remember { mutableStateOf(false) }
    // The ⋮ row menu target + the run-now results (ruleId → text).
    var rowMenuFor by remember { mutableStateOf<String?>(null) }
    var runResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var runningRuleId by remember { mutableStateOf<String?>(null) }

    // The playlist loads SYNCHRONOUSLY in the remember initializer — the
    // create button has already written the store by the time this
    // composable runs (same thread ordering), so there is no async gap
    // that could flash "This playlist no longer exists."
    var playlist by remember(playlistId) {
        mutableStateOf(SuitePlaylistStore.read(context, playlistId))
    }
    var activeId by remember { mutableStateOf(SuitePlaylistStore.activePlaylistId(context)) }

    val current = playlist ?: run {
        Scaffold(topBar = { SlimTopBar(title = "Playlist", onBack = onBack) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            ) {
                Text("This playlist no longer exists.")
            }
        }
        return
    }

    /** Fresh-copy save: mutate a NEW instance so the state really changes. */
    fun save(mutate: SuitePlaylistStore.Playlist.() -> Unit) {
        val next = SuitePlaylistStore.read(context, playlistId) ?: return
        next.mutate()
        SuitePlaylistStore.upsert(context, next)
        playlist = SuitePlaylistStore.read(context, playlistId)
    }

    fun saveRules(mutate: RuleModel.PlaylistRules.() -> Unit) {
        val next = SuiteRulesStore.readPlaylistRules(context, playlistId)
        next.mutate()
        SuiteRulesStore.writePlaylistRules(context, next)
    }

    var rulesData by remember {
        mutableStateOf(SuiteRulesStore.readPlaylistRules(context, playlistId))
    }
    var editingRuleId by remember { mutableStateOf<String?>(null) }

    // The trim overlay takes over the whole screen (back = discard).
    var trimTarget by remember { mutableStateOf<SuitePlaylistStore.Entry?>(null) }
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
                trimTarget = null
            },
        )
        return
    }

    // ── Drag-reorder state (the WALLPAPERS rows) ──
    var draggingPath by remember { mutableStateOf<String?>(null) }

    fun moveEntry(path: String, target: Int) {
        save {
            val from = entries.indexOfFirst { it.filepath == path }
            if (from >= 0 && target != from && target in entries.indices) {
                val moved = entries.removeAt(from)
                entries.add(target.coerceIn(0, entries.size), moved)
            }
        }
    }

    Scaffold(
        topBar = {
            // The NAME lives in the top bar; tapping it opens the rename
            // flow. The ACTIVE badge (or a one-tap "Set active") sits
            // beside it.
            val isActive = activeId == current.id
            SlimTopBar(
                title = "",
                onBack = onBack,
                titleContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showRename = true }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            current.name.ifEmpty { "Playlist" },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (isActive) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (!isActive) {
                        TextButton(onClick = {
                            SuitePlaylistStore.setActive(context, current.id)
                            activeId = current.id
                        }) { Text("Set active") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Play order (compact, right under the top bar) ──
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
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

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // ═══════════════ WALLPAPERS ═══════════════
                item(key = "wallpapers-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Wallpapers (${current.entries.size})",
                            style = MaterialTheme.typography.titleMedium)
                        Box {
                            TextButton(onClick = { showPlusMenu = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                            }
                            PlaylistPlusDropdown(
                                expanded = showPlusMenu,
                                onDismiss = { showPlusMenu = false },
                                onWallpapers = { showPlusMenu = false; showAddPicker = true },
                                onRandomSlot = {
                                    showPlusMenu = false
                                    val entry = RandomPullEngine.newSlotEntry()
                                    save { entries.add(entry) }
                                    editingSlotMarker = entry.filepath
                                },
                                onWholeGroup = { showPlusMenu = false; showGroupPicker = true },
                            )
                        }
                    }
                }

                if (current.entries.isEmpty()) {
                    item(key = "wallpapers-empty") {
                        Text(
                            "No wallpapers yet — tap ＋ to add some.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(current.entries, key = { it.filepath }) { entry ->
                        val entryIndex = current.entries
                            .indexOfFirst { it.filepath == entry.filepath }
                        PlaylistEntryRow(
                            entry = entry,
                            index = entryIndex,
                            dragging = draggingPath == entry.filepath,
                            onOpenMenu = { rowMenuFor = entry.filepath },
                            onTap = {
                                when {
                                    RandomPullEngine.isSlot(entry.filepath) ->
                                        editingSlotMarker = entry.filepath
                                    GroupBlocks.isGroupBlock(entry.filepath) ->
                                        editingBlockMarker = entry.filepath
                                }
                            },
                            onDragStart = { draggingPath = entry.filepath },
                            onDragEnd = { draggingPath = null },
                            onMoveTo = { target -> moveEntry(entry.filepath, target) },
                        )
                        // The ⋮ menu (per row).
                        if (rowMenuFor == entry.filepath) {
                            PlaylistRowMenu(
                                entry = entry,
                                onDismiss = { rowMenuFor = null },
                                onEdit = {
                                    rowMenuFor = null
                                    when {
                                        RandomPullEngine.isSlot(entry.filepath) ->
                                            editingSlotMarker = entry.filepath
                                        GroupBlocks.isGroupBlock(entry.filepath) ->
                                            editingBlockMarker = entry.filepath
                                    }
                                },
                                onTrim = { rowMenuFor = null; trimTarget = entry },
                                onRemove = {
                                    rowMenuFor = null
                                    save {
                                        val i = entries.indexOfFirst { it.filepath == entry.filepath }
                                        if (i >= 0) entries.removeAt(i)
                                    }
                                },
                            )
                        }
                    }
                }

                item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

                // ═══════════════ CHANGES WHEN… ═══════════════
                item(key = "changes-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Changes when…", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showTriggerSheet = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                    }
                }

                if (rulesData.rules.isEmpty()) {
                    item(key = "changes-empty") {
                        Text(
                            "Nothing changes this playlist by itself yet — add a " +
                                "trigger (e.g. Every 15 minutes → Next wallpaper).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // First match wins / every match runs — only with 2+.
                    if (rulesData.rules.size >= 2) {
                        item(key = "changes-mode") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "When several match:",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    saveRules { mode = if (mode == "first") "all" else "first" }
                                    rulesData = SuiteRulesStore.readPlaylistRules(context, playlistId)
                                }) {
                                    Text(
                                        if (rulesData.mode == "first") "First match wins"
                                        else "Every match runs"
                                    )
                                }
                            }
                        }
                    }
                    items(rulesData.rules, key = { it.id }) { rule ->
                        PlaylistRuleRow(
                            rule = rule,
                            running = runningRuleId == rule.id,
                            runResult = runResults[rule.id],
                            onToggle = { on ->
                                saveRules {
                                    rules.firstOrNull { it.id == rule.id }?.enabled = on
                                }
                                rulesData = SuiteRulesStore.readPlaylistRules(context, playlistId)
                            },
                            onRun = {
                                if (runningRuleId != null) return@PlaylistRuleRow
                                runningRuleId = rule.id
                                scope.launch {
                                    val result = com.wepwawet.rules.RuleEngine
                                        .fireRuleManual(context, playlistId, rule)
                                    runResults = runResults + (rule.id to result)
                                    runningRuleId = null
                                    rulesData = SuiteRulesStore.readPlaylistRules(context, playlistId)
                                }
                            },
                            onOpen = { editingRuleId = rule.id },
                            onDuplicate = {
                                saveRules {
                                    rules.add(RuleModel.copyRule(rule))
                                }
                                rulesData = SuiteRulesStore.readPlaylistRules(context, playlistId)
                            },
                            onDelete = {
                                saveRules { rules.removeAll { it.id == rule.id } }
                                rulesData = SuiteRulesStore.readPlaylistRules(context, playlistId)
                            },
                        )
                    }
                }
            }
        }
    }

    // ── Rename dialog ──
    if (showRename) {
        var nameField by remember(current.id) { mutableStateOf(current.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it.take(40) },
                    singleLine = true,
                    label = { Text("Playlist name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    save { name = nameField.trim().ifEmpty { name } }
                    showRename = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }

    // ── The add-wallpapers picker (Save only ADDS; 4-column dense grid) ──
    data class PickItem(
        val filepath: String,
        val title: String,
        val type: String,
        val isContentUri: Boolean,
        val folderUri: String? = null,
    )

    if (showAddPicker) {
        var folderImages by remember {
            mutableStateOf<List<Pair<String, ImageFolderAssets.ImageEntry>>>(emptyList())
        }
        var pickerSearch by remember { mutableStateOf("") }
        var pickerGroup by remember { mutableStateOf<String?>(null) }
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
        // v0.9.0 redesign: the selection starts EMPTY — Save only ADDS the
        // newly checked items; already-in items render dimmed with an
        // "Added" mark and are not selectable.
        val selected = remember(pickItems) { mutableStateOf(setOf<String>()) }
        Dialog(
            onDismissRequest = { showAddPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .heightIn(max = 640.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Add wallpapers", style = MaterialTheme.typography.titleLarge)
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
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${selected.value.size} selected · ${visibleItems.size} shown",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
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
                            TextButton(
                                onClick = {
                                    selected.value = visibleItems
                                        .map { it.filepath }
                                        .filter { it !in alreadyIn }
                                        .toSet()
                                },
                            ) { Text(if (pickerGroup == null) "Select all" else "Select group") }
                            TextButton(onClick = { selected.value = setOf() }) {
                                Text("Clear")
                            }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            gridItems(visibleItems, key = { it.filepath }) { item ->
                                val added = item.filepath in alreadyIn
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
                                        .then(
                                            if (added) Modifier.alpha(0.4f) else Modifier
                                        )
                                        .clickable(enabled = !added) {
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
                                            size = 0.dp,
                                        )
                                        if (added) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(4.dp),
                                            ) {
                                                Text(
                                                    "Added",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 4.dp),
                                                )
                                            }
                                        } else if (checked) {
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
                                        modifier = Modifier.padding(top = 2.dp),
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
                                    WepWawetLogger.appInfo("PlaylistEditor: entries added",
                                        "count" to chosen.size)
                                    showAddPicker = false
                                },
                                enabled = selected.value.isNotEmpty(),
                            ) { Text("Add ${selected.value.size}") }
                        }
                    }
                }
            }
        }
    }

    // ── The whole-group picker (＋ → Whole group…) ──
    if (showGroupPicker) {
        val groups = remember { LibraryPrefs.readGroups(context) }
        AlertDialog(
            onDismissRequest = { showGroupPicker = false },
            title = { Text("Add a whole group") },
            text = {
                Column {
                    if (groups.isEmpty()) {
                        Text(
                            "No groups yet — make one in the library first.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    for (group in groups) {
                        val alreadyBlocked = current.entries.any { it.groupRef == group }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !alreadyBlocked) {
                                    save {
                                        if (entries.none { it.groupRef == group }) {
                                            entries.add(GroupBlocks.newBlockEntry(group))
                                        }
                                    }
                                    WepWawetLogger.appInfo(
                                        "PlaylistEditor: group block added", "group" to group)
                                    showGroupPicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                group,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (alreadyBlocked) {
                                Text(
                                    "Added",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGroupPicker = false }) { Text("Cancel") }
            },
        )
    }

    // ── The add-trigger sheet (Changes when… ＋) ──
    if (showTriggerSheet) {
        PlaylistTriggerSheet(
            onDismiss = { showTriggerSheet = false },
            onPick = { type ->
                showTriggerSheet = false
                val rule = RuleModel.Rule(
                    name = triggerLabel(type),
                    triggers = ArrayList(listOf(RuleModel.Trigger(type = type))),
                    actions = ArrayList(listOf(
                        RuleModel.Block(type = RuleModel.BLOCK_NEXT))),
                )
                saveRules { rules.add(rule) }
                rulesData = SuiteRulesStore.readPlaylistRules(context, playlistId)
                editingRuleId = rule.id
            },
            onStarter = { starter ->
                showTriggerSheet = false
                saveRules { rules.add(starter.second()) }
                rulesData = SuiteRulesStore.readPlaylistRules(context, playlistId)
            },
        )
    }

    // ── The rule editor overlay (Changes when… rows open it) ──
    editingRuleId?.let { ruleId ->
        RuleEditorScreen(
            playlistId = playlistId,
            ruleId = ruleId,
            onExit = {
                rulesData = SuiteRulesStore.readPlaylistRules(context, playlistId)
                editingRuleId = null
            },
            onDeleted = {
                saveRules { rules.removeAll { it.id == ruleId } }
                rulesData = SuiteRulesStore.readPlaylistRules(context, playlistId)
                editingRuleId = null
            },
        )
        return
    }

    // ── The random-slot editor ──
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

    // ── The group-block editor ──
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

// ═════════════════════════ Row pieces ═════════════════════════════════════

/** The ＋ dropdown next to the Wallpapers header. */
@Composable
private fun PlaylistPlusDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onWallpapers: () -> Unit,
    onRandomSlot: () -> Unit,
    onWholeGroup: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Wallpapers…") },
            leadingIcon = { Icon(Icons.Filled.Add, null) },
            onClick = onWallpapers,
        )
        DropdownMenuItem(
            text = { Text("Random slot") },
            leadingIcon = { Icon(Icons.Filled.Casino, null) },
            onClick = onRandomSlot,
        )
        DropdownMenuItem(
            text = { Text("Whole group…") },
            leadingIcon = { Icon(Icons.Filled.Folder, null) },
            onClick = onWholeGroup,
        )
    }
}

/** The ⋮ menu of one wallpapers row. */
@Composable
private fun PlaylistRowMenu(
    entry: SuitePlaylistStore.Entry,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onTrim: () -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (RandomPullEngine.isSlot(entry.filepath) ||
            GroupBlocks.isGroupBlock(entry.filepath)
        ) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Filled.Tune, null) },
                onClick = onEdit,
            )
        }
        if (isTrimVideoFile(entry.filepath)) {
            DropdownMenuItem(
                text = { Text("Trim") },
                leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
                onClick = onTrim,
            )
        }
        DropdownMenuItem(
            text = { Text("Remove") },
            leadingIcon = { Icon(Icons.Filled.Close, null) },
            onClick = onRemove,
        )
    }
}

/**
 * One dense wallpapers row: drag handle ≡, a 38dp thumbnail (or a tinted
 * icon tile for random slots / group blocks), title + subtitle, ⋮.
 * Long-press-drag reorders (the LibraryScreen group-chip approach: the
 * TOTAL vertical travel maps to the target index, reordering live).
 */
@Composable
private fun PlaylistEntryRow(
    entry: SuitePlaylistStore.Entry,
    index: Int,
    dragging: Boolean,
    onOpenMenu: () -> Unit,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onMoveTo: (Int) -> Unit,
) {
    val isRandom = RandomPullEngine.isSlot(entry.filepath)
    val isBlock = GroupBlocks.isGroupBlock(entry.filepath)
    val blockGroup = entry.groupRef ?: GroupBlocks.groupName(entry.filepath)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowH = with(density) { 58.dp.toPx() }
    // Live values for the drag closure — the row's index CHANGES as the
    // live reorder moves it, and pointerInput (keyed on the path) must not
    // restart mid-gesture, so the closure reads these instead of captures.
    val liveIndex by androidx.compose.runtime.rememberUpdatedState(index)
    val liveMoveTo by androidx.compose.runtime.rememberUpdatedState(onMoveTo)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (dragging) MaterialTheme.colorScheme.surfaceVariant
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable { onTap() }
            .pointerInput(entry.filepath) {
                // Long-press-drag = reorder (the LibraryScreen group-chip
                // approach): the TOTAL vertical travel maps to the target
                // index, one row height per crossing, reordering live.
                var totalDragPx = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        totalDragPx = 0f
                        onDragStart()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragPx += dragAmount.y
                        val target = liveIndex + (totalDragPx / rowH).toInt()
                        liveMoveTo(target)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        when {
            isRandom || isBlock -> {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isRandom) Icons.Filled.Casino else Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            else -> {
                PlaylistPickThumb(
                    filepath = entry.filepath,
                    isContentUri = !entry.filepath.startsWith("/"),
                    size = 38.dp,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (isBlock) "Group: $blockGroup"
                else entry.title.ifEmpty { entry.filepath.substringAfterLast('/') },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isRandom) "Random — fresh each turn"
                else if (isBlock) {
                    val n = entry.blockManualMembers?.size
                    "Whole group · " + GroupBlocks.orderLabel(
                        entry.blockOrder ?: GroupBlocks.ORDER_MANUAL) +
                        (n?.let { " · $it members" } ?: "")
                } else entry.type.ifEmpty { "wallpaper" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isRandom || isBlock) {
            Text(
                "tap to edit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onOpenMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More")
        }
    }
}

/**
 * One "Changes when…" row: the automation as a sentence —
 * "When [triggers] → Do [action summary]" — with an enable switch, ▶ run
 * now (the result appears as small text on the row), tap to edit, and ⋮
 * (Duplicate / Delete).
 */
@Composable
private fun PlaylistRuleRow(
    rule: RuleModel.Rule,
    running: Boolean,
    runResult: String?,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (rule.enabled) "When ${triggerSummary(rule)}"
                    else "Off — ${triggerSummary(rule)}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "→ ${actionSummary(rule)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (runResult != null) {
                    Text(
                        runResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRun, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Run now")
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (rule.enabled) "Turn off" else "Turn on") },
                        onClick = { menuOpen = false; onToggle(!rule.enabled) },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/**
 * The add-automation sheet: triggers grouped Time / Device / Network /
 * Events / Buttons, searchable; picking one creates the row with the
 * default action "Next wallpaper" and opens it for editing. Starters are
 * offered at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistTriggerSheet(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onStarter: (starter: Pair<String, () -> RuleModel.Rule>) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                label = { Text("Search triggers") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            val q = search.trim().lowercase()
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for ((cat, types) in PLAYLIST_TRIGGER_CATEGORIES) {
                    val matching = types.filter {
                        q.isEmpty() || triggerLabel(it).lowercase().contains(q)
                    }
                    if (matching.isEmpty()) continue
                    Text(
                        cat,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    for (t in matching) {
                        Text(
                            triggerLabel(t),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(t) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
                // Copy a starter.
                Text(
                    "Copy a starter",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                for (pair in com.wepwawet.rules.RuleStarter.starters()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onStarter(pair) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(pair.first, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** The trigger groups for the add-automation sheet. */
private val PLAYLIST_TRIGGER_CATEGORIES = listOf(
    "Time" to listOf(
        RuleModel.TRIG_INTERVAL, RuleModel.TRIG_TIME_WINDOW,
        RuleModel.TRIG_TIME_OF_DAY, RuleModel.TRIG_DAY_OF_WEEK, RuleModel.TRIG_DATE,
    ),
    "Device" to listOf(
        RuleModel.TRIG_SCREEN_ON, RuleModel.TRIG_SCREEN_OFF, RuleModel.TRIG_UNLOCKED,
        RuleModel.TRIG_CHARGER_IN, RuleModel.TRIG_CHARGER_OUT,
        RuleModel.TRIG_BATTERY_BELOW, RuleModel.TRIG_BATTERY_ABOVE,
        RuleModel.TRIG_SAVER_ON, RuleModel.TRIG_SAVER_OFF,
        RuleModel.TRIG_ORIENTATION,
    ),
    "Network" to listOf(
        RuleModel.TRIG_WIFI_CONNECT, RuleModel.TRIG_WIFI_DISCONNECT,
        RuleModel.TRIG_MOBILE_DATA, RuleModel.TRIG_BT_DEVICE_IN,
        RuleModel.TRIG_BT_DEVICE_OUT,
    ),
    "Events" to listOf(
        RuleModel.TRIG_WALLPAPER_CHANGED, RuleModel.TRIG_WORKSHOP_DOWNLOADED,
        RuleModel.TRIG_WORKSHOP_UPDATE, RuleModel.TRIG_DISCOVER_QUEUE_LOW,
        RuleModel.TRIG_FROM_RULE,
    ),
    "Buttons" to listOf(
        RuleModel.TRIG_MANUAL_BUTTON, RuleModel.TRIG_NOTIFICATION_BUTTON,
        RuleModel.TRIG_QS_TILE,
    ),
    "Audio" to listOf(
        RuleModel.TRIG_HEADPHONES_IN, RuleModel.TRIG_HEADPHONES_OUT,
    ),
)

// ═════════════════════════ Dialogs ════════════════════════════════════════

/**
 * The random-slot editor — the Discover knobs (strict/soft tags, content
 * tier, style, sources; blank/Auto inherits Discover). v0.9.0 redesign:
 * the tag fields are CHIP INPUTS (type, Enter/comma to commit, × to
 * remove), grouped under Tags / Content / Style / Sources.
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
    var strict by remember(slot.filepath) {
        mutableStateOf(slot.randomStrictTags ?: emptyList())
    }
    var strictAuto by remember(slot.filepath) {
        mutableStateOf(slot.randomStrictTags == null)
    }
    var soft by remember(slot.filepath) {
        mutableStateOf(slot.randomSoftTags ?: emptyList())
    }
    var softAuto by remember(slot.filepath) {
        mutableStateOf(slot.randomSoftTags == null)
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
        title = { Text("Edit random slot") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("Tags", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                TagChipField(
                    chips = strict,
                    autoInherit = strictAuto,
                    autoLabel = "Strict tags (Auto = Discover's)",
                    onToggleAuto = { strictAuto = !strictAuto },
                    onChange = { strict = it },
                )
                Spacer(Modifier.height(8.dp))
                TagChipField(
                    chips = soft,
                    autoInherit = softAuto,
                    autoLabel = "Soft tags (Auto = Discover's)",
                    onToggleAuto = { softAuto = !softAuto },
                    onChange = { soft = it },
                )
                Spacer(Modifier.height(12.dp))
                Text("Content", style = MaterialTheme.typography.labelMedium)
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
                Text("Style", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (styleAuto) "Style (Discover's)" else "Style ${styleValue.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = !styleAuto, onCheckedChange = { styleAuto = !it })
                }
                androidx.compose.material3.Slider(
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
                                ?: DiscoverPrefs.readSources(context).toMutableSet()
                            if (checked) base.remove(src) else base.add(src)
                            sources = base.toSet()
                        },
                        label = { Text(DiscoverPrefs.sourceLabel(src)) },
                        modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
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
                        if (strictAuto) null else strict,
                        if (softAuto) null else soft,
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
 * A chip-input tag field: an Auto pill (inherit Discover), the committed
 * chips with × remove, and a one-line type-to-add field — Enter or comma
 * commits.
 */
@Composable
private fun TagChipField(
    chips: List<String>,
    autoInherit: Boolean,
    autoLabel: String,
    onToggleAuto: () -> Unit,
    onChange: (List<String>) -> Unit,
) {
    var field by remember { mutableStateOf("") }
    Column {
        FilterChip(
            selected = autoInherit,
            onClick = onToggleAuto,
            label = { Text(autoLabel) },
        )
        if (!autoInherit) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (tag in chips) {
                    androidx.compose.material3.InputChip(
                        selected = false,
                        onClick = { onChange(chips - tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove $tag",
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }
            OutlinedTextField(
                value = field,
                onValueChange = { raw ->
                    // A comma commits the pending tag.
                    if (raw.endsWith(",")) {
                        val tag = raw.removeSuffix(",").trim()
                        if (tag.isNotEmpty() && tag !in chips) onChange(chips + tag)
                        field = ""
                    } else {
                        field = raw
                    }
                },
                singleLine = true,
                label = { Text("Add a tag (comma to add)") },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        val tag = field.trim()
                        if (tag.isNotEmpty() && tag !in chips) onChange(chips + tag)
                        field = ""
                    },
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The group-block editor: order chips (manual / newest / oldest / A-Z /
 * random) + the manual member order DRAGGED with the same handle approach
 * as the main list (v0.9.0 redesign: the up/down arrows are gone).
 */
@Composable
private fun BlockEditorDialog(
    block: SuitePlaylistStore.Entry,
    onDismiss: () -> Unit,
    onSave: (order: String, manual: List<String>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var order by remember(block.filepath) {
        mutableStateOf(block.blockOrder ?: GroupBlocks.ORDER_MANUAL)
    }
    var members by remember(block.filepath) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loaded by remember(block.filepath) { mutableStateOf(false) }
    var manual by remember(block.filepath) {
        mutableStateOf(block.blockManualMembers ?: emptyList())
    }
    LaunchedEffect(block.filepath) {
        val resolved = withContext(Dispatchers.IO) {
            GroupBlocks.resolve(context, block)
        }
        members = resolved.members
        val paths = resolved.paths
        val stored = manual
        val known = HashSet(paths)
        manual = stored.filter { it in known } + paths.filter { it !in stored }
        loaded = true
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowH = with(density) { 44.dp.toPx() }
    var draggingIdx by remember { mutableStateOf(-1) }

    fun moveManual(from: Int, to: Int) {
        if (from == to || from !in manual.indices || to !in manual.indices) return
        val list = manual.toMutableList()
        val moved = list.removeAt(from)
        list.add(to, moved)
        manual = list
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group: ${block.groupRef ?: "?"}") },
        text = {
            Column {
                Text(
                    "The block stays linked to this group — members added or " +
                        "removed later follow automatically. This sets the order.",
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
                    for (mode in GroupBlocks.ORDER_MODES) {
                        FilterChip(
                            selected = order == mode,
                            onClick = { order = mode },
                            label = { Text(GroupBlocks.orderLabel(mode)) },
                        )
                    }
                }
                if (order == GroupBlocks.ORDER_MANUAL) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Manual order (${members.size}) — long-press a row and drag",
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
                                    "This group is empty — add wallpapers to it in " +
                                        "the library first.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            for ((mi, m) in members.withIndex()) {
                                val path = manual.getOrNull(mi) ?: m.first
                                val liveMi by androidx.compose.runtime.rememberUpdatedState(mi)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (draggingIdx == mi)
                                                MaterialTheme.colorScheme.surfaceVariant
                                            else androidx.compose.ui.graphics.Color.Transparent
                                        )
                                        .pointerInput(path) {
                                            var totalDragPx = 0f
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    totalDragPx = 0f
                                                    draggingIdx = mi
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    totalDragPx += dragAmount.y
                                                    val target =
                                                        liveMi + (totalDragPx / rowH).toInt()
                                                    moveManual(liveMi, target)
                                                },
                                                onDragEnd = { draggingIdx = -1 },
                                                onDragCancel = { draggingIdx = -1 },
                                            )
                                        }
                                        .padding(vertical = 4.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        m.second.ifEmpty { path.substringAfterLast('/') },
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
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

// ═════════════════════════ Shared pieces ══════════════════════════════════

/**
 * One thumbnail tile — size-configurable (the playlist picker's dense
 * 4-column grid and the editor's 38dp rows share it). Content URIs and
 * plain files load through COIL; engine .mpkg wallpapers decode their
 * preview bitmap like the library cards.
 */
@Composable
fun PlaylistPickThumb(
    filepath: String,
    isContentUri: Boolean,
    size: Dp = 96.dp,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(8.dp)
    when {
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
                .then(if (size > 0.dp) Modifier.size(size) else Modifier.fillMaxWidth())
                .then(if (size <= 0.dp) Modifier.aspectRatio(1f) else Modifier)
                .clip(shape),
        )
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
                            WepWawetLogger.appWarn(
                                "PlaylistPickThumb: engine preview decode failed",
                                    "exception" to it.javaClass.simpleName,
                                    "message" to (it.message ?: ""))
                        }
                        .getOrNull()
                }
            }
            Box(
                modifier = Modifier
                    .then(if (size > 0.dp) Modifier.size(size) else Modifier.fillMaxWidth())
                    .then(if (size <= 0.dp) Modifier.aspectRatio(1f) else Modifier)
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

/** 870 → "14:30". */
fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** One line describing what starts a rule. */
fun triggerSummary(rule: RuleModel.Rule): String {
    if (rule.triggers.isEmpty()) return "nothing yet"
    val labels = rule.triggers.take(3).map { t ->
        when (t.type) {
            RuleModel.TRIG_TIME_WINDOW ->
                "${formatMinute(t.minuteStart)}–${formatMinute(t.minuteEnd)}"
            RuleModel.TRIG_TIME_OF_DAY -> "at ${formatMinute(t.timeMinute)}"
            RuleModel.TRIG_DAY_OF_WEEK ->
                "on " + listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                    .getOrElse(t.dayOfWeek) { "any day" }
            RuleModel.TRIG_DATE -> "on a date"
            RuleModel.TRIG_INTERVAL -> "every ${t.intervalMinutes} min"
            RuleModel.TRIG_BATTERY_BELOW -> "battery < ${t.batteryLevel}%"
            RuleModel.TRIG_BATTERY_ABOVE -> "battery > ${t.batteryLevel}%"
            RuleModel.TRIG_WIFI_CONNECT ->
                if (t.networkSsid.isEmpty()) "Wi-Fi connects" else "joins ${t.networkSsid}"
            RuleModel.TRIG_WIFI_DISCONNECT ->
                if (t.networkSsid.isEmpty()) "Wi-Fi disconnects" else "leaves ${t.networkSsid}"
            RuleModel.TRIG_BT_DEVICE_IN ->
                if (t.deviceName.isEmpty()) "a device connects" else "${t.deviceName} connects"
            RuleModel.TRIG_BT_DEVICE_OUT ->
                if (t.deviceName.isEmpty()) "a device disconnects" else "${t.deviceName} disconnects"
            else -> triggerLabel(t.type)
        }
    }
    return labels.joinToString(" / ") +
        (if (rule.triggers.size > 3) " +${rule.triggers.size - 3}" else "")
}

/**
 * One line describing what a rule DOES. A Limit scope reads "Only use N
 * wallpapers while this is active" — the row shows its condition through
 * the trigger summary.
 */
fun actionSummary(rule: RuleModel.Rule): String {
    val limit = rule.actions.firstOrNull { it.type == RuleModel.BLOCK_LIMIT }
    if (limit != null) {
        return "Only use ${limit.paths.size} wallpapers while this is active"
    }
    if (rule.actions.isEmpty()) return "nothing yet"
    val labels = rule.actions.take(2).map { blockSummaryPlainPublic(it) }
    return labels.joinToString("; ") +
        (if (rule.actions.size > 2) " +${rule.actions.size - 2}" else "")
}

/** The plain label for a trigger type (also used by the rule editor). */
fun triggerLabel(type: String): String = when (type) {
    RuleModel.TRIG_TIME_WINDOW -> "Time window"
    RuleModel.TRIG_TIME_OF_DAY -> "Time of day"
    RuleModel.TRIG_DAY_OF_WEEK -> "Day of week"
    RuleModel.TRIG_DATE -> "Date"
    RuleModel.TRIG_INTERVAL -> "Every N (interval)"
    RuleModel.TRIG_SCREEN_ON -> "Screen turns on"
    RuleModel.TRIG_SCREEN_OFF -> "Screen turns off"
    RuleModel.TRIG_UNLOCKED -> "Device unlocked"
    RuleModel.TRIG_CHARGER_IN -> "Charger plugged in"
    RuleModel.TRIG_CHARGER_OUT -> "Charger unplugged"
    RuleModel.TRIG_BATTERY_BELOW -> "Battery drops below"
    RuleModel.TRIG_BATTERY_ABOVE -> "Battery rises above"
    RuleModel.TRIG_SAVER_ON -> "Battery saver turns on"
    RuleModel.TRIG_SAVER_OFF -> "Battery saver turns off"
    RuleModel.TRIG_WIFI_CONNECT -> "Wi-Fi connects"
    RuleModel.TRIG_WIFI_DISCONNECT -> "Wi-Fi disconnects"
    RuleModel.TRIG_MOBILE_DATA -> "Mobile data active"
    RuleModel.TRIG_HEADPHONES_IN -> "Headphones connect"
    RuleModel.TRIG_HEADPHONES_OUT -> "Headphones disconnect"
    RuleModel.TRIG_BT_DEVICE_IN -> "Bluetooth device connects"
    RuleModel.TRIG_BT_DEVICE_OUT -> "Bluetooth device disconnects"
    RuleModel.TRIG_ORIENTATION -> "Orientation changes"
    RuleModel.TRIG_WALLPAPER_CHANGED -> "Wallpaper changed"
    RuleModel.TRIG_WORKSHOP_DOWNLOADED -> "Workshop download finished"
    RuleModel.TRIG_WORKSHOP_UPDATE -> "Workshop wallpaper updated"
    RuleModel.TRIG_DISCOVER_QUEUE_LOW -> "Discover queue ran low"
    RuleModel.TRIG_MANUAL_BUTTON -> "Button on this screen"
    RuleModel.TRIG_NOTIFICATION_BUTTON -> "Notification button"
    RuleModel.TRIG_QS_TILE -> "Quick Settings tile"
    RuleModel.TRIG_FROM_RULE -> "Started by another rule"
    else -> type
}

/** "3 min ago"-style relative time. */
fun relativeTime(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000L
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} d ago"
    }
}

/** A compact one-line label for one action block (row summaries). */
fun blockSummaryPlainPublic(b: RuleModel.Block): String = when (b.type) {
    RuleModel.BLOCK_SHOW -> "Show ${displayPathShort(b.param)}"
    RuleModel.BLOCK_SHOW_SET -> "Show one of ${b.paths.size}"
    RuleModel.BLOCK_PICK_GROUP -> "Pick from group ${b.param.ifEmpty { "?" }}"
    RuleModel.BLOCK_PICK_PLAYLIST -> "Pick from a playlist"
    RuleModel.BLOCK_RANDOM -> "Random wallpaper"
    RuleModel.BLOCK_PULL_DISCOVER -> "Pull a fresh Discover image"
    RuleModel.BLOCK_NEXT -> "Next wallpaper"
    RuleModel.BLOCK_PREV -> "Previous wallpaper"
    RuleModel.BLOCK_SHUFFLE -> "Shuffle"
    RuleModel.BLOCK_PAUSE_ROTATION -> "Pause rotation"
    RuleModel.BLOCK_RESUME_ROTATION -> "Resume rotation"
    RuleModel.BLOCK_RESTART_PLAYLIST -> "Restart the playlist"
    RuleModel.BLOCK_HOLD_CURRENT -> "Hold current (${b.num} min)"
    RuleModel.BLOCK_SWITCH_PLAYLIST -> "Switch playlist"
    RuleModel.BLOCK_SET_FPS -> "Set FPS ${b.num}"
    RuleModel.BLOCK_AUDIO_MUTE -> if (b.param == "unmute") "Unmute audio" else "Mute audio"
    RuleModel.BLOCK_RENDERER_PAUSE ->
        if (b.param == "resume") "Resume renderer" else "Pause renderer"
    RuleModel.BLOCK_APPLY_PRESET -> "Apply preset ${b.param}"
    RuleModel.BLOCK_IF -> "If"
    RuleModel.BLOCK_CHOOSE -> "Choose"
    RuleModel.BLOCK_REPEAT -> "Repeat x${b.num}"
    RuleModel.BLOCK_WAIT -> "Wait ${b.num}s"
    RuleModel.BLOCK_STOP -> "Stop"
    RuleModel.BLOCK_RUN_RULE -> "Run another rule"
    RuleModel.BLOCK_NOTIFY -> "Notify"
    RuleModel.BLOCK_DISCOVER_REFRESH -> "Discover refresh"
    RuleModel.BLOCK_WORKSHOP_DOWNLOAD -> "Workshop download"
    RuleModel.BLOCK_SET_VALUE -> "Set value ${b.param}"
    RuleModel.BLOCK_ADD_VALUE -> "Add to ${b.param}"
    RuleModel.BLOCK_CLEAR_VALUE -> "Clear ${b.param}"
    RuleModel.BLOCK_SAVE_TO_FOLDER -> "Save pick to folder"
    RuleModel.BLOCK_ADD_TO_PLAYLIST -> "Add pick to playlist"
    RuleModel.BLOCK_LIMIT -> "Only use some wallpapers"
    else -> b.type
}

private fun displayPathShort(path: String): String =
    path.substringAfterLast('/').ifEmpty { path }

/** Can this entry's video be trimmed in place? (raw video files only) */
fun isTrimVideoFile(path: String): Boolean =
    path.startsWith("/") && listOf(
        ".mp4", ".webm", ".mkv", ".avi", ".mov", ".m4v", ".3gp", ".ts", ".flv",
    ).any { path.endsWith(it, ignoreCase = true) }
