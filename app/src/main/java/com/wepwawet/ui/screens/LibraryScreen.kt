package com.wepwawet.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weutil.WallpaperInfoSparse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The library grid — parity with the official client's browse screen plus
 * the pin feature the user asked for:
 *
 *  - Search (the official SearchView: live title filter, cleared on close).
 *  - Sort menu: Newest / Oldest / Alphabetically Ascending / Descending
 *    (official SortMethod; NEWEST by lastModifiedDate desc is the default).
 *  - Pin: long-press a wallpaper → "Pin to top"; pinned wallpapers float
 *    above every sort, keeping the sort order within the pinned group.
 *  - The card matches content_browse_item.xml: square thumbnail with the
 *    title overlaid at the bottom on a 60% black scrim, three lines max —
 *    no type label below the name (user request; the official grid has
 *    none).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenSettings: () -> Unit,
    onOpenWallpaper: (WallpaperInfoSparse) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenWorkshopItem: (itemId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var wallpapers by remember { mutableStateOf<List<WallpaperInfoSparse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<WallpaperInfoSparse?>(null) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    // Search + sort (official WallpaperViewModel filter state)
    var searching by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }
    var sortMethod by remember { mutableStateOf(SortMethod.NEWEST) }

    // Pins
    var pinned by remember { mutableStateOf<Set<String>>(emptySet()) }
    var contextMenuFor by remember { mutableStateOf<WallpaperInfoSparse?>(null) }
    var playlistTarget by remember { mutableStateOf<WallpaperInfoSparse?>(null) }

    // ===== v0.9.0: Groups =====
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    var groupOf by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activeGroup by remember { mutableStateOf<String?>(null) }
    // The wallpaper whose "Add to group" dialog is open.
    var groupTarget by remember { mutableStateOf<WallpaperInfoSparse?>(null) }
    // New-group creation dialog (from the "＋" chip).
    var showNewGroupDialog by remember { mutableStateOf(false) }
    // Rename/delete dialog for a group (long-press a group chip).
    var manageGroupName by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            WepWawetLogger.engineDebug("LibraryScreen: reloading")
            wallpapers = EngineLibrary.enumerateWallpapers(context)
            selectedPath = EngineLibrary.getSelectedWallpaper(context)
            pinned = LibraryPrefs.readPinned(context)
            groups = LibraryPrefs.readGroups(context)
            groupOf = LibraryPrefs.readGroupOf(context)
            loading = false
        }
    }

    LaunchedEffect(refreshKey) {
        reload()
    }

    // v0.8.0: consume a staged ACTION_VIEW import (opened from a file manager).
    LaunchedEffect(Unit) {
        val staged = com.wepwawet.engine.ImportRequestHandler.consume()
        if (staged != null) {
            val mime = context.contentResolver.getType(staged) ?: ""
            val displayName = queryDisplayName(context, staged)
                ?: staged.lastPathSegment ?: "wallpaper.mpkg"
            val result = if (mime.startsWith("image") || mime.startsWith("video")) {
                EngineLibrary.importLocalMedia(context, staged)
            } else {
                EngineLibrary.importMpkgStream(context, staged, displayName)
            }
            reload()
            when (result) {
                is EngineLibrary.ImportResult.Success ->
                    onOpenWallpaper(result.wallpaper)
                is EngineLibrary.ImportResult.Failed ->
                    snackbar.showSnackbar("Import failed — ${result.name}: ${result.reason}")
            }
        }
    }

    // Re-check the active wallpaper whenever we come back to the screen
    // (the preview screen may have applied a different one).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                selectedPath = EngineLibrary.getSelectedWallpaper(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var firstImported: WallpaperInfoSparse? = null
            var failure: String? = null
            for (uri in uris) {
                val mime = context.contentResolver.getType(uri) ?: ""
                val displayName = queryDisplayName(context, uri)
                    ?: uri.lastPathSegment ?: "wallpaper.mpkg"
                val result = if (mime.startsWith("image") || mime.startsWith("video")) {
                    EngineLibrary.importLocalMedia(context, uri)
                } else {
                    EngineLibrary.importMpkgStream(context, uri, displayName)
                }
                when (result) {
                    is EngineLibrary.ImportResult.Success ->
                        if (firstImported == null) firstImported = result.wallpaper
                    is EngineLibrary.ImportResult.Failed ->
                        failure = "${result.name}: ${result.reason}"
                }
            }
            reload()
            if (failure != null) {
                snackbar.showSnackbar("Import failed — $failure")
            } else if (firstImported != null) {
                onOpenWallpaper(firstImported!!)
            }
        }
    }

    // The official BrowseFragment filter: trim + lowercase title contains.
    // v0.9.0: plus the GROUP filter (activeGroup == null → all wallpapers).
    val visibleWallpapers = remember(wallpapers, filterText, sortMethod, pinned, activeGroup, groupOf) {
        val query = filterText.trim().lowercase(Locale.ROOT)
        val filtered = when {
            query.isEmpty() && activeGroup == null -> wallpapers
            activeGroup != null -> wallpapers.filter {
                (query.isEmpty() || it.title.lowercase(Locale.ROOT).contains(query)) &&
                    groupOf[it.filePath] == activeGroup
            }
            else -> wallpapers.filter {
                it.title.lowercase(Locale.ROOT).contains(query)
            }
        }
        val sorted = when (sortMethod) {
            SortMethod.NEWEST -> filtered.sortedByDescending { it.lastModifiedDate }
            SortMethod.OLDEST -> filtered.sortedBy { it.lastModifiedDate }
            SortMethod.ALPHAASC -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
            SortMethod.ALPHADESC -> filtered.sortedByDescending { it.title.lowercase(Locale.ROOT) }
        }
        // Pins float to the top, preserving the sort within each group.
        sorted.sortedByDescending { it.filePath in pinned }
    }

    Scaffold(
        topBar = {
            if (searching) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            // official: collapsing the search clears the filter
                            searching = false
                            filterText = ""
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                        OutlinedTextField(
                            value = filterText,
                            onValueChange = { filterText = it },
                            placeholder = { Text("Search wallpapers") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (filterText.isNotEmpty()) {
                            IconButton(onClick = { filterText = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    }
                }
            } else {
                var sortMenuOpen by remember { mutableStateOf(false) }
                SlimTopBar(
                    title = "Wallpapers",
                    onBack = null,
                    actions = {
                        // v0.8.9: Import lives here — the two stacked button
                        // rows that used to occupy the top of this screen are
                        // gone (bottom navigation now carries Workshop /
                        // Folders / Daily), which returns ~120dp of height to
                        // the wallpaper grid.
                        IconButton(onClick = {
                            try {
                                importLauncher.launch(
                                    arrayOf(
                                        "video/*", "image/jpg", "image/jpeg", "image/png",
                                        "image/webp", "image/gif", "application/*",
                                    )
                                )
                            } catch (t: Throwable) {
                            }
                        }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Import wallpapers",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { searching = true }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.Sort,
                                    contentDescription = "Sort",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                            ) {
                                for (method in SortMethod.entries) {
                                    DropdownMenuItem(
                                        text = { Text(method.label) },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = sortMethod == method,
                                                onClick = null,
                                            )
                                        },
                                        onClick = {
                                            sortMethod = method
                                            sortMenuOpen = false
                                            WepWawetLogger.engineDebug("LibraryScreen: sort changed",
                                                "method" to method.name)
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onOpenDownloads) {
                            Icon(
                                Icons.Filled.Verified,
                                contentDescription = "Downloads",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ===== Group filter chips =====
            // "All" + one chip per group + a "＋" chip to create a group.
            // Tap a chip to filter; long-press a group chip to rename/delete.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = activeGroup == null,
                    onClick = { activeGroup = null },
                    label = { Text("All (${wallpapers.size})") },
                )
                for (group in groups) {
                    val count = groupOf.count { it.value == group }
                    FilterChip(
                        selected = activeGroup == group,
                        onClick = { activeGroup = if (activeGroup == group) null else group },
                        // Long-press → rename/delete dialog. detectTapGestures
                        // with only onLongPress doesn't consume taps, so the
                        // chip's own onClick keeps handling those (M3 1.3's
                        // FilterChip onClick is non-nullable).
                        modifier = Modifier.pointerInput(group) {
                            detectTapGestures(onLongPress = { manageGroupName = group })
                        },
                        label = { Text("$group ($count)") },
                        leadingIcon = if (activeGroup == group) {
                            { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.height(16.dp)) }
                        } else null,
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { showNewGroupDialog = true },
                    label = { Text("＋ New group") },
                )
            }

            when {
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                visibleWallpapers.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (filterText.isBlank()) "No wallpapers yet"
                            else "No wallpapers match \"${filterText.trim()}\"",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (filterText.isBlank())
                                "Browse the Workshop tab or import .mpkg / video / image files " +
                                    "with the + button up top."
                            else "Try a different search or change the sort.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        // v0.8.6: SMALL spacing (4dp) + 4dp horizontal
                        // content padding — the user clarified "when I
                        // said no margin I meant a small one like in the
                        // real app". The previous v0.8.4 change set these
                        // to 0dp which made the previews sit flush against
                        // each other and the screen edges (too tight).
                        // 4dp matches the official browse grid's gap.
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 4.dp,
                            end = 4.dp,
                            top = 4.dp,
                            bottom = 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(visibleWallpapers, key = { it.filePath }) { wallpaper ->
                            LibraryWallpaperCard(
                                wallpaper = wallpaper,
                                isActive = selectedPath == wallpaper.filePath,
                                isPinned = wallpaper.filePath in pinned,
                                onClick = {
                                    WepWawetLogger.engine("LibraryScreen: wallpaper tapped",
                                        "title" to wallpaper.title,
                                        "file" to wallpaper.filePath,
                                        "is_local" to wallpaper.isLocal)
                                    onOpenWallpaper(wallpaper)
                                },
                                onLongClick = { contextMenuFor = wallpaper },
                            )
                        }
                    }
                }
            }
        }
    }

    // ===== Context menu (long-press) =====
    contextMenuFor?.let { target ->
        val isPinned = target.filePath in pinned
        ModalBottomSheet(onDismissRequest = { contextMenuFor = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ContextAction(
                    icon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                    label = "Add to playlist",
                ) {
                    contextMenuFor = null
                    playlistTarget = target
                }
                ContextAction(
                    icon = {
                        Icon(
                            Icons.Filled.PushPin, contentDescription = null,
                            tint = if (isPinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    label = if (isPinned) "Unpin from top" else "Pin to top",
                ) {
                    val newPath = target.filePath
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            LibraryPrefs.setPinned(context, newPath, !isPinned)
                        }
                        pinned = LibraryPrefs.readPinned(context)
                        contextMenuFor = null
                        WepWawetLogger.engine("LibraryScreen: pin changed",
                            "file" to newPath,
                            "pinned" to !isPinned)
                    }
                }
                // ===== v0.9.0: Add to group =====
                val targetGroup = groupOf[target.filePath]
                ContextAction(
                    icon = {
                        Icon(
                            Icons.Filled.Label, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    label = if (targetGroup != null)
                        "Group: $targetGroup (change)"
                    else "Add to group",
                ) {
                    contextMenuFor = null
                    groupTarget = target
                }
                // Workshop page when the import filename carries the item id
                Regex("^downloads/(\\d{6,}) - ").find(target.filePath)?.groupValues
                    ?.get(1)?.let { itemId ->
                        ContextAction(
                            icon = {
                                Icon(
                                    Icons.Filled.Verified, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            label = "Open workshop page",
                        ) {
                            contextMenuFor = null
                            onOpenWorkshopItem(itemId)
                        }
                    }
                ContextAction(
                    icon = { Icon(Icons.Filled.Close, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error) },
                    label = "Remove from library",
                ) {
                    pendingDelete = target
                    contextMenuFor = null
                }
            }
        }
    }

    // ===== Playlist add =====
    playlistTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistTarget = null },
            title = { Text("Playlist") },
            text = {
                Text("Add \"${target.title.ifEmpty { "wallpaper" }}\" to the wallpaper playlist?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val playlist = WeEnginePrefs.readPlaylist(context)
                                val entry = io.wallpaperengine.wedata.PlaylistDataWallpaper()
                                entry.filepath = target.filePath
                                entry.duration = 60
                                playlist.wallpapers.removeAll { it.filepath == target.filePath }
                                playlist.wallpapers.add(entry)
                                WeEnginePrefs.storePlaylist(context, playlist)
                            }
                            playlistTarget = null
                            snackbar.showSnackbar("Added to playlist")
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { playlistTarget = null }) { Text("Cancel") }
            },
        )
    }

    pendingDelete?.let { victim ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove wallpaper?") },
            text = { Text("${victim.title.ifEmpty { victim.filePath }} will be removed from your library.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                EngineLibrary.deleteWallpaper(context, victim)
                            }
                            if (!ok) {
                                // official message parity: "Failed deleting cache for <file>"
                                WepWawetLogger.engineWarn(
                                    "Failed deleting cache for ${victim.filePath}")
                            }
                            // a deleted wallpaper must not stay pinned
                            withContext(Dispatchers.IO) {
                                LibraryPrefs.setPinned(context, victim.filePath, false)
                                LibraryPrefs.assignToGroup(context, victim.filePath, null)
                            }
                            pendingDelete = null
                            reload()
                        }
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    // ===== v0.9.0: Group dialogs =====

    // New group creation (the "＋ New group" chip).
    if (showNewGroupDialog) {
        var newGroupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewGroupDialog = false },
            title = { Text("New group") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it.take(30) },
                    singleLine = true,
                    label = { Text("Group name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val created = LibraryPrefs.createGroup(context, newGroupName)
                        if (created) {
                            groups = LibraryPrefs.readGroups(context)
                            activeGroup = newGroupName.trim().take(30)
                        }
                        showNewGroupDialog = false
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewGroupDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Assign a wallpaper to a group (context menu → "Add to group").
    groupTarget?.let { target ->
        val currentGroup = groupOf[target.filePath]
        AlertDialog(
            onDismissRequest = { groupTarget = null },
            title = { Text("Add to group") },
            text = {
                Column {
                    Text(
                        target.title.ifEmpty { target.filePath.substringAfterLast('/') },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (groups.isEmpty()) {
                        Text("No groups yet — create one below.")
                    } else {
                        for (group in groups) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        LibraryPrefs.assignToGroup(context, target.filePath, group)
                                        groupOf = LibraryPrefs.readGroupOf(context)
                                        groupTarget = null
                                        WepWawetLogger.engine("LibraryScreen: assigned to group",
                                            "file" to target.filePath,
                                            "group" to group)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = currentGroup == group,
                                    onClick = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(group, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    var inlineNewGroup by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = inlineNewGroup,
                            onValueChange = { inlineNewGroup = it.take(30) },
                            singleLine = true,
                            label = { Text("New group") },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                if (LibraryPrefs.createGroup(context, inlineNewGroup)) {
                                    val name = inlineNewGroup.trim().take(30)
                                    LibraryPrefs.assignToGroup(context, target.filePath, name)
                                    groups = LibraryPrefs.readGroups(context)
                                    groupOf = LibraryPrefs.readGroupOf(context)
                                    groupTarget = null
                                }
                            },
                        ) { Text("Add") }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (currentGroup != null) {
                        TextButton(
                            onClick = {
                                LibraryPrefs.assignToGroup(context, target.filePath, null)
                                groupOf = LibraryPrefs.readGroupOf(context)
                                groupTarget = null
                            },
                        ) { Text("Remove from group") }
                    }
                    TextButton(onClick = { groupTarget = null }) { Text("Done") }
                }
            },
        )
    }

    // Rename / delete a group (long-press a group chip).
    manageGroupName?.let { group ->
        var renameTo by remember(group) { mutableStateOf(group) }
        AlertDialog(
            onDismissRequest = { manageGroupName = null },
            title = { Text("Group \"$group\"") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameTo,
                        onValueChange = { renameTo = it.take(30) },
                        singleLine = true,
                        label = { Text("Rename group") },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Deleting the group keeps its wallpapers — they just return to \"All\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            if (LibraryPrefs.renameGroup(context, group, renameTo)) {
                                if (activeGroup == group) activeGroup = renameTo.trim().take(30)
                                groups = LibraryPrefs.readGroups(context)
                                groupOf = LibraryPrefs.readGroupOf(context)
                            }
                            manageGroupName = null
                        },
                    ) { Text("Rename") }
                    TextButton(
                        onClick = {
                            LibraryPrefs.deleteGroup(context, group)
                            if (activeGroup == group) activeGroup = null
                            groups = LibraryPrefs.readGroups(context)
                            groupOf = LibraryPrefs.readGroupOf(context)
                            manageGroupName = null
                        },
                    ) { Text("Delete group") }
                }
            },
            dismissButton = {
                TextButton(onClick = { manageGroupName = null }) { Text("Cancel") }
            },
        )
    }
}

/** The official SortMethod enum with its display labels. */
private enum class SortMethod(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    ALPHAASC("Alphabetically Ascending"),
    ALPHADESC("Alphabetically Descending"),
}

/** Pinned-wallpaper persistence (our addition over the official client). */
private object LibraryPrefs {
    private const val PREFS = "wallpapersuite_library"
    private const val KEY_PINNED = "pinned_wallpapers"

    // ===== v0.9.0: wallpaper GROUPS (user request: "to the wallpaper =====
    // ===== menu add the ability to make groups").                   =====
    // A group is just a name; each wallpaper maps to at most one group
    // (filePath → groupName). Stored with one prefs key per wallpaper —
    // simple, and removing a deleted wallpaper is a one-line remove().
    private const val KEY_GROUPS = "wallpaper_groups"
    private const val KEY_GROUP_OF_PREFIX = "wallpaper_group_of:"

    fun readPinned(context: android.content.Context): Set<String> =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(KEY_PINNED, emptySet()) ?: emptySet()

    fun setPinned(context: android.content.Context, filePath: String, pinned: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()).toMutableSet()
        if (pinned) current.add(filePath) else current.remove(filePath)
        prefs.edit().putStringSet(KEY_PINNED, current).apply()
    }

    fun readGroups(context: android.content.Context): List<String> =
        (context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(KEY_GROUPS, emptySet()) ?: emptySet())
            .filter { it.isNotBlank() }
            .sorted()

    private fun writeGroups(context: android.content.Context, groups: Collection<String>) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_GROUPS, groups.toSet()).apply()
    }

    fun createGroup(context: android.content.Context, name: String): Boolean {
        val trimmed = name.trim().take(30)
        if (trimmed.isEmpty()) return false
        val groups = readGroups(context).toMutableSet()
        if (groups.contains(trimmed)) return false
        groups.add(trimmed)
        writeGroups(context, groups)
        return true
    }

    fun renameGroup(context: android.content.Context, oldName: String, newName: String): Boolean {
        val trimmed = newName.trim().take(30)
        if (trimmed.isEmpty() || trimmed == oldName) return false
        val groups = readGroups(context).toMutableSet()
        if (groups.contains(trimmed)) return false
        groups.remove(oldName)
        groups.add(trimmed)
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val edit = prefs.edit().putStringSet(KEY_GROUPS, groups)
        // Re-key every member's assignment.
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_GROUP_OF_PREFIX) && value == oldName) {
                edit.putString(key, trimmed)
            }
        }
        edit.apply()
        return true
    }

    fun deleteGroup(context: android.content.Context, name: String) {
        val groups = readGroups(context).toMutableSet()
        groups.remove(name)
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val edit = prefs.edit().putStringSet(KEY_GROUPS, groups)
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_GROUP_OF_PREFIX) && value == name) {
                edit.remove(key)
            }
        }
        edit.apply()
    }

    fun readGroupOf(context: android.content.Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_GROUP_OF_PREFIX) && value is String) {
                result[key.removePrefix(KEY_GROUP_OF_PREFIX)] = value
            }
        }
        return result
    }

    fun groupOf(context: android.content.Context, filePath: String): String? =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_GROUP_OF_PREFIX + filePath, null)

    fun assignToGroup(context: android.content.Context, filePath: String, group: String?) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (group == null) {
            prefs.edit().remove(KEY_GROUP_OF_PREFIX + filePath).apply()
        } else {
            prefs.edit().putString(KEY_GROUP_OF_PREFIX + filePath, group).apply()
        }
    }
}

@Composable
private fun ContextAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                var idx = cursor.getColumnIndex("_display_name")
                if (idx < 0) idx = 0
                cursor.getString(idx)
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        null
    }
}

/**
 * The library card — visual parity with content_browse_item.xml: a square
 * thumbnail, the title overlaid at the bottom on a 60% black scrim
 * (wallpaper_title_background #99000000), white text, no type subtitle.
 * A small pin marker replaces the old badges for pinned wallpapers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryWallpaperCard(
    wallpaper: WallpaperInfoSparse,
    isActive: Boolean,
    isPinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    var preview by remember(wallpaper.filePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(wallpaper.filePath) {
        preview = withContext(Dispatchers.IO) {
            runCatching {
                WeEnginePrefs.getWallpaperPreviewBitmap(
                    context, EngineLibrary.sceneLib(context), wallpaper,
                )
            }.onFailure {
                WepWawetLogger.engineWarn("LibraryScreen: preview thumbnail failed",
                    "file" to wallpaper.filePath,
                    "exception" to (it.message ?: it.javaClass.name))
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // v0.8.6: rounded corners + clip so the small 4dp grid gaps
            // look clean (square corners with gaps looks like a bug;
            // rounded corners with gaps looks intentional like the
            // official browse grid).
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        val bmp = preview
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = wallpaper.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        // Download progress (official pbDownload / tvPending)
        if (wallpaper.progress in 1..99) {
            LinearProgressIndicator(
                progress = { wallpaper.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 20.dp),
            )
        }

        // Pinned marker (our pin feature)
        if (isPinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        Color(0x99000000),
                        RoundedCornerShape(50),
                    )
                    .padding(4.dp),
            )
        }

        // Active marker: a subtle check chip — the official grid has no
        // badge, but showing WHICH wallpaper is applied is too useful to
        // drop entirely; kept minimal (icon only, top-left).
        if (isActive) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = "Active",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(
                        Color(0x99000000),
                        RoundedCornerShape(50),
                    )
                    .padding(4.dp),
            )
        }

        // Title overlay at the bottom (official tvTitle: 60% black
        // background, white text, up to 3 lines, 5dp padding).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0x99000000)),
        ) {
            Text(
                wallpaper.title.ifEmpty { wallpaper.filePath.substringAfterLast('/') },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }
}
