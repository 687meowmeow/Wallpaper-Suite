package com.wepwawet.ui.screens
import kotlin.math.abs

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.wepwawet.data.SteamApiService
import com.wepwawet.data.WorkshopTagStore
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.CompactSearchBar
import com.wepwawet.ui.components.SlimTopBar
import com.wepwawet.ui.components.SlimTopBarIcon
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weutil.WallpaperInfoSparse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The library grid — the Wallpapers tab.
 *
 *  - Search (live title filter, cleared on close).
 *  - Sort menu: Newest / Oldest / Alphabetically Ascending / Descending.
 *  - Pin: long-press a wallpaper → "Pin to top".
 *  - GROUPS (v0.8.12, RESTORED — the user's manual wallpaper groups):
 *    long-press a wallpaper → "Add to group"; the group chips below the
 *    top bar filter the grid. Groups are created from the wallpaper
 *    context menu (no "＋" chip — removed per user request).
 *  - WORKSHOP TAGS (v0.8.12 — what "sort by tag" means): every workshop
 *    download remembers its Steam Workshop tags; the chips with the
 *    label glyph filter the grid by them. Missing tags are back-filled
 *    from the Steam API in the background.
 *  - FOLDERS: every picked SAF folder gets its own chip; its images are
 *    part of "All" too (user request) and can be set as wallpapers.
 *  - TILE SIZE (v0.8.12): one size for the whole section — wallpapers,
 *    folder images, every view — changeable from the sort menu.
 *  - Playlist: long-press a wallpaper → "Add to playlist"; the top-bar
 *    playlist button opens the playlist manager.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenSettings: () -> Unit,
    onOpenWallpaper: (WallpaperInfoSparse) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenWorkshopItem: (itemId: String) -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    // v0.8.16: opens the image editor (crop + aspect presets). Nullable so
    // previews without a nav host still compile.
    onEditImage: ((ImageFolderAssets.ImageEntry) -> Unit)? = null,
    steamApi: SteamApiService? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var wallpapers by remember { mutableStateOf<List<WallpaperInfoSparse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<WallpaperInfoSparse?>(null) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    // Search + sort (the top-bar sort drives EVERY view incl. folders)
    var searching by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }
    var sortMethod by remember { mutableStateOf(SortMethod.NEWEST) }

    // One tile size for the entire section (changeable, persisted)
    var tileSize by remember { mutableStateOf(LibraryPrefs.readTileSize(context)) }

    // Pins
    var pinned by remember { mutableStateOf<Set<String>>(emptySet()) }
    var contextMenuFor by remember { mutableStateOf<WallpaperInfoSparse?>(null) }
    // v0.8.15: (path, title, type) — works for wallpapers AND folder images.
    var playlistPickerFor by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    // v0.8.15: long-press menu for folder images (pin / group / playlist /
    // rename / delete) — mirrors the wallpaper context menu.
    var imageMenuFor by remember { mutableStateOf<ImageFolderAssets.ImageEntry?>(null) }
    // v0.8.17: "there should be a delete option for images when you hold
    // down" — the confirm dialog target.
    var deleteImageTarget by remember { mutableStateOf<ImageFolderAssets.ImageEntry?>(null) }
    // v0.8.16: crop overrides (original uri → cropped file path) — what the
    // grid/preview/Set/playlists actually load for an edited image.
    var cropOverrides by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // ===== GROUPS (the user's manual groups — restored v0.8.12) =====
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    var groupOf by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activeGroup by remember { mutableStateOf<String?>(null) }
    // The item whose "Add to group" dialog is open — v0.8.15: (path, title)
    // so folder images can be grouped too.
    var groupTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Rename/delete dialog for a group (long-press a group chip).
    var manageGroupName by remember { mutableStateOf<String?>(null) }
    // v0.8.19: hold-drag group chip REORDERING ("hold to drag them to move
    // their position at the top"). A long-press on a group chip arms the
    // drag for THAT chip; dragging then slides it across the row (the
    // pointer's x position picks the slot); release persists the order.
    var dragGroupName by remember { mutableStateOf<String?>(null) }
    var dragGroupIndex by remember { mutableStateOf(-1) }

    // ===== WORKSHOP TAGS (v0.8.12 "sort by tag"; v0.8.13 sheet) =====
    var workshopTags by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    // v0.8.13: MULTI-select tags, chosen in a bottom-sheet filter menu
    // instead of a long chip row that cluttered the top of the page (the
    // user asked for "the tags in a filter menu on the wallpapers like on
    // the workshop page").
    var activeTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showTagSheet by remember { mutableStateOf(false) }

    // ===== v0.8.13: the built-in ANIMATED group =====
    // "there should be an animated group for all the we ones" — every
    // Wallpaper Engine wallpaper (the engine library's .mpkg entries;
    // local SAF media is excluded).
    var animatedActive by remember { mutableStateOf(false) }

    // ===== FOLDERS (v0.8.22: the SCAN ENGINE for linked groups — no chips) =====
    // A folder only exists as a GROUP's link now (see LibraryPrefs.GroupInfo);
    // this list feeds the live scans below.
    var folders by remember { mutableStateOf<List<ImageFolderAssets.SavedFolder>>(emptyList()) }
    // group name → linked tree URI ("" entries = unlinked groups are absent).
    var groupFolders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // v0.8.13: restore-once guard — the saved group/folder/tag selection
    // is re-applied the first time reload() has populated groups+folders.
    var filterRestored by remember { mutableStateOf(false) }
    // EVERY folder's images — enumerated once per reload; the All view and
    // the per-folder views both filter this (user request: folder images
    // also show in All; one sort for everything).
    var allFolderImages by remember {
        mutableStateOf<List<Pair<ImageFolderAssets.SavedFolder, ImageFolderAssets.ImageEntry>>>(emptyList())
    }
    // v0.8.18: the "+" / "＋ Folder" menu ("make the create folder button
    // come up with menu asking if you want to use an already existing or
    // make one") and the shared create-folder flow.
    var showPlusMenu by remember { mutableStateOf(false) }
    var plusMenuForImport by remember { mutableStateOf(false) }
    // v0.8.22: the group a pending folder pick/create LINKS to (from the
    // manage menu's "Link to folder"); null = the active group / auto-name.
    var linkFolderForGroup by remember { mutableStateOf<String?>(null) }
    // v0.8.21: the "＋" chip's own dropdown (Make group / Add image
    // folder); the folder dialog itself stays for the existing-or-create
    // choice. Making a group is a name dialog — empty groups ARE valid
    // (wallpapers are assigned from each wallpaper's own menu).
    var showPlusDropdown by remember { mutableStateOf(false) }
    var showMakeGroup by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    // The folder image whose fullscreen preview dialog is open.
    var selectedImage by remember {
        mutableStateOf<Pair<ImageFolderAssets.SavedFolder, ImageFolderAssets.ImageEntry>?>(null)
    }
    var workingImage by remember { mutableStateOf(false) }
    // v0.8.14: the folder image being renamed.
    var renameImageTarget by remember { mutableStateOf<ImageFolderAssets.ImageEntry?>(null) }

    // v0.8.14: "Newest" = date ADDED TO THE APP. Workshop downloads take
    // their true download-completion time from the download history; every
    // other item falls back to the file date; folder images get their
    // first-seen time (addedDatesImages, registered during reload).
    var addedDatesDownloads by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var addedDatesImages by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    /** The "added to the app" date of an engine-library wallpaper. */
    fun addedDateOf(w: WallpaperInfoSparse): Long =
        addedDatesDownloads[workshopItemIdOf(w.filePath)] ?: w.lastModifiedDate

    /**
     * v0.8.18 (review B12): cheap, synchronous refresh of every
     * prefs-derived slice (pins, groups, group assignments, crop overrides,
     * download dates). This is all a pin/group/rename mutation invalidates —
     * the old single reload() hammer re-enumerated the engine library AND
     * every folder's images (hundreds of content-resolver queries) for each
     * of those one-tap actions, costing seconds with several folders.
     */
    fun refreshPrefs() {
        scope.launch {
            pinned = LibraryPrefs.readPinned(context)
            groups = LibraryPrefs.readGroups(context)
            groupOf = LibraryPrefs.readGroupOf(context)
            groupFolders = LibraryPrefs.readGroupFolders(context)
            // v0.8.16: edited images resolve to their cropped copies.
            cropOverrides = withContext(Dispatchers.IO) {
                ImageCropStore.read(context).mapValues { it.value.cropFilePath }
            }
            addedDatesDownloads = com.wepwawet.data.DownloadManager
                .completedDownloads.value.associate { it.itemId to it.completedAt }
        }
    }

    fun reload() {
        scope.launch {
            loading = true
            WepWawetLogger.engineDebug("LibraryScreen: reloading")
            wallpapers = EngineLibrary.enumerateWallpapers(context)
            selectedPath = EngineLibrary.getSelectedWallpaper(context)
            pinned = LibraryPrefs.readPinned(context)
            groups = LibraryPrefs.readGroups(context)
            groupOf = LibraryPrefs.readGroupOf(context)
            groupFolders = LibraryPrefs.readGroupFolders(context)
            // v0.8.16: edited images resolve to their cropped copies.
            cropOverrides = ImageCropStore.read(context).mapValues { it.value.cropFilePath }
            // True download-completion times (itemId → epoch millis) — the
            // "added to the app" date for workshop wallpapers.
            addedDatesDownloads = com.wepwawet.data.DownloadManager
                .completedDownloads.value.associate { it.itemId to it.completedAt }
            folders = ImageFolderAssets.readFolders(context)
            workshopTags = WorkshopTagStore.readAll(context)
            allFolderImages = withContext(Dispatchers.IO) {
                folders.flatMap { folder ->
                    ImageFolderAssets.enumerateFolderImages(context, folder.treeUri)
                        .map { folder to it }
                }
            }
            // v0.8.14: register + fetch "added to the app" dates for the
            // folder images (first-seen timestamps — see registerAddedDates).
            addedDatesImages = ImageFolderAssets.registerAddedDates(
                context, allFolderImages.map { it.second })
            WepWawetLogger.appInfo("LibraryScreen: folders enumerated",
                "folders" to folders.size,
                "images" to allFolderImages.size)
            loading = false
        }
    }

    /** The in-flight debounced full reload (see scheduleReload). */
    var enumerateJob by remember { mutableStateOf<Job?>(null) }

    /**
     * v0.8.18 (review B12): debounced full re-enumeration for events that
     * actually change disk contents (import, delete, folder add/remove).
     * A 250 ms debounce coalesces bursts — e.g. a multi-file import fires
     * one reload instead of one per file. Declared after reload() (Kotlin
     * local functions must be declared before use at the call site).
     */
    fun scheduleReload() {
        enumerateJob?.cancel()
        enumerateJob = scope.launch {
            delay(250)
            reload()
        }
    }

    // v0.8.12: the folder picker — adds a folder (persistable read+write
    // grant kept so Discover's "Save to folder" can write into it later).
    // v0.8.22: a picked folder becomes the LINK of the ACTIVE group (or a
    // fresh linked group when no group is active) — folders have no
    // standalone chips anymore.
    fun folderPicked(uri: android.net.Uri, forGroup: String?) {
        ImageFolderAssets.takePersistableGrants(context, uri)
        ImageFolderAssets.addFolder(context, uri.toString())
        val group = forGroup ?: run {
            // Auto-name the new group after the folder (de-conflicted).
            var name = ImageFolderAssets.folderDisplayName(uri.toString())
                .ifBlank { "Folder" }.trim().take(30)
            val taken = LibraryPrefs.readGroups(context).toMutableSet()
            if (name in taken) {
                var n = 2
                while ("$name ($n)" in taken) n++
                name = "$name ($n)".take(30)
            }
            LibraryPrefs.createGroup(context, name)
            name
        }
        LibraryPrefs.setGroupFolder(context, group, uri.toString())
        if (activeGroup != group) activeGroup = group
        WepWawetLogger.appInfo("LibraryScreen: folder linked to group",
            "group" to group,
            "uri" to uri.toString().take(120))
        refreshPrefs()
        scheduleReload()
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val target = linkFolderForGroup ?: activeGroup
            linkFolderForGroup = null
            folderPicked(uri, target)
        }
    }

    // v0.8.18: "+" while a folder chip is active → add images to THAT
    // folder, picked through the system GET_CONTENT chooser ("that one
    // android menu that asks if you want to use files or gallery"). The
    // returned URIs are transient-grant, but we copy the bytes straight
    // into the folder's tree, so that is all we need.
    val folderImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = urisFromGetContentResult(result.resultCode, result.data)
        if (uris.isNotEmpty()) {
            // v0.8.22: import into the ACTIVE group's linked folder.
            val target = activeGroup?.let { groupFolders[it] }
            if (target != null) {
                scope.launch {
                    val (copied, failed) = withContext(Dispatchers.IO) {
                        ImageFolderAssets.importImagesToFolder(context, target, uris)
                    }
                    // v0.8.18 (review B12): debounced — the import wrote
                    // new disk contents; the enumeration follows shortly.
                    scheduleReload()
                    snackbar.showSnackbar(
                        if (failed == 0 && copied > 0)
                            "Added $copied image${if (copied == 1) "" else "s"} to folder"
                        else "Added $copied, $failed failed")
                }
            }
        }
    }

    // v0.8.13: restore the last-open group / Animated / folder / tags
    // once the library has been enumerated (keyed on `loading` so it runs
    // AFTER the first reload populated groups+folders — keying on the
    // lists themselves fires first with the empty initial values). A
    // saved selection whose group or folder no longer exists falls back
    // to All.
    LaunchedEffect(loading) {
        if (loading || filterRestored) return@LaunchedEffect
        filterRestored = true
        val saved = LibraryPrefs.readSavedLibraryFilter(context) ?: return@LaunchedEffect
        if (saved.first != null && saved.first in groups) activeGroup = saved.first
        animatedActive = saved.second
        // v0.8.22: a saved FOLDER selection (pre-merge) maps to the group
        // its folder linked into — nothing the user had open is lost.
        if (saved.third != null && activeGroup == null) {
            groupFolders.entries.firstOrNull { it.value == saved.third }?.let {
                activeGroup = it.key
            }
        }
        activeTags = saved.fourth
        WepWawetLogger.engineDebug("LibraryScreen: restored last filter",
            "group" to (activeGroup ?: "none"),
            "animated" to saved.second,
            "folder" to (saved.third?.takeLast(40) ?: "none"),
            "tags" to saved.fourth.size)
    }

    // v0.8.13: persist the open filter on every change so the app starts
    // where the user left off ("remember what group you had open").
    LaunchedEffect(activeGroup, animatedActive, activeTags, filterRestored) {
        if (!filterRestored) return@LaunchedEffect
        LibraryPrefs.saveLibraryFilter(
            context, activeGroup, animatedActive, null, activeTags)
    }

    /**
     * Set a folder image as the plain SYSTEM wallpaper.
     *
     * v0.8.14: the ONE set path for basic images ("basic images shouldnt
     * use the wallpaper engine renderer — videos should"). The bitmap is
     * fetched through COIL — the SAME loader that renders the grid
     * thumbnails (GIF-aware, provider-robust) — because the previous
     * manual BitmapFactory decode could fail silently on some
     * providers/codecs, leaving the preview spinner forever and the Set
     * button permanently disabled. The old pack-to-Scene-mpkg / WE
     * renderer path is GONE for folder images.
     */
    fun setFolderImageAsSystemWallpaper(entry: ImageFolderAssets.ImageEntry) {
        if (workingImage) return
        workingImage = true
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    // v0.8.16: an edited image SETS its crop (the original
                    // file is never modified — the crop is a derived copy).
                    val resolved = ImageCropStore.resolve(context, entry.uri)
                    val data: Any = if (resolved.startsWith("/"))
                        java.io.File(resolved) else android.net.Uri.parse(resolved)
                    val request = ImageRequest.Builder(context)
                        .data(data)
                        .allowHardware(false) // the crop needs pixel access
                        .size(2048)
                        .build()
                    val drawable = context.imageLoader.execute(request).drawable
                        ?: return@withContext false
                    val bmp = (drawable as? BitmapDrawable)?.bitmap
                        ?: run {
                            // Animated drawables (GIF): draw the current
                            // frame into a software bitmap.
                            val w = drawable.intrinsicWidth.coerceAtLeast(1)
                            val h = drawable.intrinsicHeight.coerceAtLeast(1)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                val canvas = android.graphics.Canvas(it)
                                drawable.setBounds(0, 0, w, h)
                                drawable.draw(canvas)
                            }
                        }
                    val crop = com.wepwawet.discover.DiscoverApplier.cropForScreen(context, bmp)
                    // v0.8.18 (review B6): the Discover tab's Home/Lock
                    // tri-state is the app-wide model of which screens the
                    // user wants this app to manage. The old unconditional
                    // home+lock apply silently overwrote a lock screen the
                    // user had excluded in Discover. Default to both only
                    // when the user has never expressed a preference (both
                    // off), matching DiscoverPrefs' own legacy defaults.
                    val screens = com.wepwawet.discover.DiscoverPrefs.readScreens(context)
                    val noPreference = screens.first == com.wepwawet.discover.DiscoverPrefs.SCREEN_OFF &&
                        screens.second == com.wepwawet.discover.DiscoverPrefs.SCREEN_OFF
                    val homeOn = noPreference ||
                        com.wepwawet.discover.DiscoverPrefs.homeRequired(screens.first)
                    val lockOn = noPreference ||
                        com.wepwawet.discover.DiscoverPrefs.lockRequired(screens.second)
                    com.wepwawet.discover.DiscoverApplier.apply(
                        context, crop, homeOn = homeOn, lockOn = lockOn).ok
                }
                snackbar.showSnackbar(
                    if (ok) "Wallpaper set — \"${entry.displayName.substringBeforeLast('.')}\""
                    else "Wallpaper write failed")
                if (ok) WepWawetLogger.appInfo("LibraryScreen: folder image set (system)",
                    "file" to entry.displayName)
            } finally {
                workingImage = false
                selectedImage = null
            }
        }
    }

    /**
     * v0.8.14: rename a folder image in place (SAF renameDocument). The
     * extension is preserved; the grid reloads with the new document uri.
     */
    fun renameFolderImage(entry: ImageFolderAssets.ImageEntry, rawName: String) {
        val clean = rawName.trim().take(80)
        if (clean.isEmpty()) return
        val ext = entry.displayName.substringAfterLast('.', "").ifEmpty { "jpg" }
        val target = if (clean.endsWith(".$ext")) clean else "$clean.$ext"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    // renameDocument returns the NEW uri, or null when the
                    // provider refuses (e.g. a file with that name exists).
                    android.provider.DocumentsContract.renameDocument(
                        context.contentResolver, android.net.Uri.parse(entry.uri), target) != null
                } catch (t: Throwable) {
                    WepWawetLogger.appWarn("LibraryScreen: rename image failed",
                        "exception" to t.javaClass.name,
                        "message" to (t.message ?: ""))
                    false
                }
            }
            if (ok) {
                WepWawetLogger.appInfo("LibraryScreen: image renamed",
                    "from" to entry.displayName, "to" to target)
                selectedImage = null
                scheduleReload()
            }
            snackbar.showSnackbar(if (ok) "Renamed to $target" else "Rename failed")
        }
    }

    LaunchedEffect(refreshKey) {
        reload()
    }

    // v0.8.12: back-fill workshop tags for library wallpapers whose tags
    // aren't stored yet (downloads from before the tag store existed).
    LaunchedEffect(wallpapers) {
        val api = steamApi ?: return@LaunchedEffect
        val unknown = wallpapers
            .mapNotNull { workshopItemIdOf(it.filePath) }
            .distinct()
            .filter { WorkshopTagStore.get(context, it) == null }
        if (unknown.isEmpty()) return@LaunchedEffect
        WepWawetLogger.appInfo("LibraryScreen: fetching workshop tags",
            "count" to unknown.size)
        runCatching { api.getPublishedFileDetails(unknown.take(30)) }
            .onSuccess { result ->
                result.onSuccess { items ->
                    var stored = 0
                    for (item in items) {
                        WorkshopTagStore.put(context, item.id, item.tags)
                        stored++
                    }
                    if (stored > 0) {
                        workshopTags = WorkshopTagStore.readAll(context)
                        WepWawetLogger.appInfo("LibraryScreen: workshop tags stored",
                            "count" to stored)
                    }
                }
            }
            .onFailure {
                // v0.9.0: a swallowed backfill failure meant tags silently
                // never appeared ("added=0"-style hidden bug).
                WepWawetLogger.appWarn("LibraryScreen: workshop tag backfill failed",
                    "exception" to it.javaClass.simpleName,
                    "message" to (it.message ?: ""))
            }
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
            scheduleReload()
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
    DisposableEffect(lifecycleOwner) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                selectedPath = EngineLibrary.getSelectedWallpaper(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // v0.8.18: the general import now goes through the system GET_CONTENT
    // chooser too — the Files/Gallery disambiguation menu the user asked
    // for when importing.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = urisFromGetContentResult(result.resultCode, result.data)
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
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
            scheduleReload()
            if (failure != null) {
                snackbar.showSnackbar("Import failed — $failure")
            } else {
                // v0.9.0 (!! cleanup): the local keeps the smart cast.
                firstImported?.let { onOpenWallpaper(it) }
            }
        }
    }

    // ── Filtering + sorting (ONE sort for wallpapers AND folder images) ──

    val query = filterText.trim().lowercase(Locale.ROOT)

    // Workshop tag chips with counts (derived from the stored tags).
    val libraryTagCounts = remember(wallpapers, workshopTags) {
        val counts = linkedMapOf<String, Int>()
        for (w in wallpapers) {
            val id = workshopItemIdOf(w.filePath) ?: continue
            val tags = workshopTags[id] ?: continue
            for (t in tags) counts.merge(t, 1, Int::plus)
        }
        counts.entries.sortedWith(compareBy({ it.key.lowercase(Locale.ROOT) }))
            .map { it.key to it.value }
    }

    val filteredWallpapers = remember(wallpapers, filterText, activeGroup, activeTags, animatedActive, groupOf, workshopTags) {
        wallpapers.filter { w ->
            val matchesQuery = query.isEmpty() ||
                w.title.lowercase(Locale.ROOT).contains(query)
            val matchesGroup = activeGroup == null || groupOf[w.filePath] == activeGroup
            // The built-in Animated group: every Wallpaper Engine (engine
            // library) wallpaper — the .mpkg entries, not local SAF media.
            val matchesAnimated = !animatedActive || !w.isLocal
            val itemTags = workshopTags[workshopItemIdOf(w.filePath)]
            val matchesTags = activeTags.isEmpty() ||
                (itemTags?.any { it in activeTags } == true)
            matchesQuery && matchesGroup && matchesAnimated && matchesTags
        }
    }

    val sortedWallpapers = remember(filteredWallpapers, sortMethod, pinned, addedDatesDownloads) {
        val sorted = when (sortMethod) {
            // v0.8.14: Newest/Oldest = ADDED TO THE APP (download history
            // timestamp, falling back to the file date) — not the file's
            // own date.
            SortMethod.NEWEST -> filteredWallpapers.sortedByDescending { addedDateOf(it) }
            SortMethod.OLDEST -> filteredWallpapers.sortedBy { addedDateOf(it) }
            SortMethod.ALPHAASC -> filteredWallpapers.sortedBy {
                it.title.lowercase(Locale.ROOT).ifEmpty { it.filePath.lowercase(Locale.ROOT) }
            }
            SortMethod.ALPHADESC -> filteredWallpapers.sortedByDescending {
                it.title.lowercase(Locale.ROOT).ifEmpty { it.filePath.lowercase(Locale.ROOT) }
            }
        }
        // Pins float to the top, preserving the sort within each group.
        sorted.sortedByDescending { it.filePath in pinned }
    }

    // v0.8.22: the image slice of the grid. All view = every scanned
    // image; a GROUP view = the group's manual image members PLUS its
    // linked folder's live scan ("a linked group should show a live scan
    // of that directory ... combined with anything manually added").
    val activeGroupLink = activeGroup?.let { groupFolders[it] }
    val visibleFolderImages = remember(allFolderImages, activeGroup, activeGroupLink, filterText) {
        val src = if (activeGroup == null) {
            allFolderImages
        } else {
            allFolderImages.filter {
                groupOf[it.second.uri] == activeGroup || it.first.treeUri == activeGroupLink
            }
        }
        src.filter { query.isEmpty() || it.second.displayName.lowercase(Locale.ROOT).contains(query) }
    }

    // The merged grid. v0.8.15: a MANUAL group contains its wallpapers AND
    // its folder images ("you should be able to add images to groups aswell
    // by holding down and pin") — only the engine-concept views (the
    // built-in Animated group, workshop-tag filters) stay wallpapers-only,
    // and the folder view stays images-only. Pinned items of BOTH kinds
    // float above everything in All/groups.
    val gridItems = remember(
        sortedWallpapers, visibleFolderImages, activeGroup, activeTags,
        animatedActive, pinned, addedDatesDownloads, addedDatesImages, groupOf,
    ) {
        val images = visibleFolderImages.map {
            LibraryItem.Img(
                it.first, it.second,
                addedDatesImages[it.second.uri] ?: it.second.lastModified)
        }
        when {
            activeGroup != null -> {
                // Group view: the group's wallpapers + images (manual
                // members + the linked folder's live scan) under one sort.
                val groupImages = images
                val pinnedWalls = sortedWallpapers.filter { it.filePath in pinned }
                    .map { LibraryItem.Wall(it, addedDateOf(it)) }
                val pinnedImages = groupImages.filter { it.entry.uri in pinned }
                val rest = (sortedWallpapers.filter { it.filePath !in pinned }
                    .map { LibraryItem.Wall(it, addedDateOf(it)) } +
                    groupImages.filter { it.entry.uri !in pinned })
                    .sortedWith(libraryItemComparator(sortMethod))
                pinnedWalls + pinnedImages + rest
            }
            animatedActive || activeTags.isNotEmpty() ->
                // Engine-concept views: wallpapers only.
                sortedWallpapers.map { LibraryItem.Wall(it, addedDateOf(it)) }
            else -> {
                // All view: merge wallpapers + images under one sort.
                val pinnedWalls = sortedWallpapers.filter { it.filePath in pinned }
                    .map { LibraryItem.Wall(it, addedDateOf(it)) }
                val pinnedImages = images.filter { it.entry.uri in pinned }
                val rest = (sortedWallpapers.filter { it.filePath !in pinned }
                    .map { LibraryItem.Wall(it, addedDateOf(it)) } +
                    images.filter { it.entry.uri !in pinned })
                    .sortedWith(libraryItemComparator(sortMethod))
                pinnedWalls + pinnedImages + rest
            }
        }
    }

    Scaffold(
        topBar = {
            if (searching) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(48.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                // official: collapsing the search clears the filter
                                searching = false
                                filterText = ""
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close search",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        CompactSearchBar(
                            value = filterText,
                            onValueChange = { filterText = it },
                            placeholder = "Search wallpapers",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                var sortMenuOpen by remember { mutableStateOf(false) }
                SlimTopBar(
                    title = "Wallpapers",
                    onBack = null,
                    actions = {
                        // v0.8.18: the "+" is context-sensitive — while a
                        // v0.8.22: the top-bar "+" ADDS IMAGES to the ACTIVE
                        // GROUP — with a linked folder it imports straight
                        // into that folder (GET_CONTENT chooser → copy into
                        // the tree); a group without a link opens the
                        // choose/create/import menu, whose folder choice
                        // LINKS the folder to that group (files land there
                        // from then on). With no group active it opens the
                        // same menu (mpkg/video imports stay reachable via
                        // "Import other files").
                        SlimTopBarIcon(
                            onClick = {
                                val link = activeGroup?.let { groupFolders[it] }
                                if (link != null) {
                                    try {
                                        folderImagePicker.launch(
                                            getContentChooserIntent(
                                                mimeTypes = arrayOf("image/*"),
                                                allowMultiple = true))
                                    } catch (t: Throwable) {
                                        WepWawetLogger.appWarn(
                                            "LibraryScreen: image picker failed",
                                            "exception" to t.javaClass.name)
                                    }
                                } else {
                                    // v0.8.18 (v19 round): the top-bar "+"
                                    // opens the choose/create/import menu.
                                    plusMenuForImport = true
                                    showPlusMenu = true
                                }
                            },
                            contentDescription = if (activeGroup != null)
                                "Add images to group" else "Import wallpapers",
                            icon = Icons.Filled.Add,
                        )
                        SlimTopBarIcon(
                            onClick = { searching = true },
                            contentDescription = "Search",
                            icon = Icons.Filled.Search,
                        )
                        Box {
                            SlimTopBarIcon(
                                onClick = { sortMenuOpen = true },
                                contentDescription = "Sort",
                                icon = Icons.Filled.Sort,
                            )
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
                                            WepWawetLogger.engineDebug(
                                                "LibraryScreen: sort changed",
                                                "method" to method.name)
                                        },
                                    )
                                }
                                // ── Tile size (one size for the whole section) ──
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Tile size",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {},
                                    enabled = false,
                                )
                                for (size in LibraryTileSize.entries) {
                                    DropdownMenuItem(
                                        text = { Text(size.label) },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = tileSize == size,
                                                onClick = null,
                                            )
                                        },
                                        onClick = {
                                            tileSize = size
                                            LibraryPrefs.writeTileSize(context, size)
                                            sortMenuOpen = false
                                            WepWawetLogger.engineDebug(
                                                "LibraryScreen: tile size changed",
                                                "size" to size.name)
                                        },
                                    )
                                }
                            }
                        }
                        SlimTopBarIcon(
                            onClick = onOpenDownloads,
                            contentDescription = "Downloads",
                            icon = Icons.Filled.Download,
                        )
                        // v0.8.13: the playlists button is GONE from here —
                        // Playlists is a bottom-navigation tab now.
                        SlimTopBarIcon(
                            onClick = onOpenSettings,
                            contentDescription = "Settings",
                            icon = Icons.Filled.Settings,
                        )
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
            // ===== Filter chips: All + ANIMATED + GROUPS + FOLDERS + ＋ Folder =====
            // Tap a chip to filter; long-press a GROUP chip to rename/delete;
            // long-press a FOLDER chip to remove the folder. There is no
            // "＋ group" chip — groups are created from a wallpaper's
            // long-press menu (removed per user request).
            // v0.8.13: the workshop TAGS moved out of this row into the
            // "Tags" filter chip's bottom sheet ("the tags should be in a
            // filter menu on the wallpapers like on the workshop page to
            // avoid clutter at the top").
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = activeGroup == null &&
                        activeTags.isEmpty() && !animatedActive,
                    onClick = {
                        activeGroup = null
                        activeTags = emptySet()
                        animatedActive = false
                    },
                    label = {
                        Text("All (${wallpapers.size + allFolderImages.size})")
                    },
                )
                // v0.8.13: the built-in ANIMATED group — every Wallpaper
                // Engine wallpaper (engine-library .mpkg entries).
                FilterChip(
                    selected = animatedActive,
                    onClick = {
                        activeGroup = null
                        activeTags = emptySet()
                        animatedActive = !animatedActive
                        WepWawetLogger.engineDebug("LibraryScreen: animated group toggled",
                            "enabled" to animatedActive)
                    },
                    label = { Text("Animated (${wallpapers.count { !it.isLocal }})") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                for ((groupIndex, group) in groups.withIndex()) {
                    // v0.8.22: the chip count = manual members + the linked
                    // folder's scanned images.
                    val link = groupFolders[group]
                    val count = groupOf.count { it.value == group } +
                        if (link != null) allFolderImages.count {
                            it.first.treeUri == link
                        } else 0
                    FilterChip(
                        selected = activeGroup == group,
                        onClick = {
                            // v0.8.19: after a hold-drag the tap that ends
                            // the drag must NOT toggle the filter — the
                            // drag flag swallows this one click.
                            if (dragGroupName != null) { dragGroupName = null; return@FilterChip }
                            activeTags = emptySet()
                            animatedActive = false
                            activeGroup = if (activeGroup == group) null else group
                        },
                        // v0.8.21 (unified hold): ONE hold gesture, two
                        // outcomes — "you should be able to hold and a group
                        // chip and drag it and be able to hold and have the
                        // option to delete it". detectDragGesturesAfterLongPress
                        // fires onDragStart the MOMENT the long press is
                        // detected (movement optional): keep holding and DRAG
                        // to reorder the chip; hold STILL and release to open
                        // the rename/delete menu; a quick TAP never reaches
                        // this detector at all (the chip's own onClick filters
                        // the group). The v0.8.20 hand-rolled awaitEachGesture
                        // mixed the event clock (uptimeMillis) with the wall
                        // clock (System.currentTimeMillis) — its timeout
                        // computed as already-elapsed, so EVERY touch entered
                        // "hold" mode instantly: taps opened the menu instead
                        // of filtering, and its per-event step math never
                        // crossed a chip width, so dragging did nothing.
                        modifier = Modifier
                            .pointerInput(group, groups.size) {
                                var startIndex = -1
                                var totalDragPx = 0f
                                var dragged = false
                                val menuSlop = viewConfiguration.touchSlop
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        // The long press just registered.
                                        startIndex = groups.indexOf(group)
                                        totalDragPx = 0f
                                        dragged = false
                                        dragGroupName = group
                                        dragGroupIndex = startIndex
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        totalDragPx += dragAmount.x
                                        if (kotlin.math.abs(totalDragPx) > menuSlop) {
                                            dragged = true
                                        }
                                        // Total horizontal travel maps to the
                                        // target slot: one chip width per
                                        // crossing, measured from the START
                                        // index (per-event stepping drifts and
                                        // sticks).
                                        val w = size.width.toFloat()
                                        if (dragged && w > 0f && startIndex >= 0 &&
                                            groups.size > 1
                                        ) {
                                            val target = (startIndex +
                                                (totalDragPx / w).toInt())
                                                .coerceIn(0, groups.size - 1)
                                            val cur = groups.indexOf(group)
                                            if (target != cur && cur >= 0) {
                                                // Reorder live: remove + insert.
                                                val moved = groups.toMutableList()
                                                moved.removeAt(cur)
                                                moved.add(target, group)
                                                groups = moved
                                                dragGroupIndex = target
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        dragGroupName = null
                                        dragGroupIndex = -1
                                        if (dragged) {
                                            // Persist the full new order.
                                            LibraryPrefs.writeGroupsOrderPublic(context, groups)
                                            WepWawetLogger.engineDebug(
                                                "LibraryScreen: group order saved",
                                                "groups" to groups.size)
                                        } else {
                                            // Held STILL — the manage menu
                                            // (rename / delete).
                                            manageGroupName = group
                                        }
                                    },
                                    onDragCancel = {
                                        dragGroupName = null
                                        dragGroupIndex = -1
                                    },
                                )
                            },
                        label = { Text("$group ($count)") },
                        leadingIcon = {
                            Icon(
                                if (groupFolders[group] != null) Icons.Filled.FolderOpen
                                else Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
                // v0.8.22: FOLDER CHIPS ARE GONE — folders live as group
                // LINKS now (merge a group with "Link to folder" from its
                // long-press menu; every pre-merge folder chip migrated
                // into a linked group, so nothing was lost).
                // v0.8.13: workshop-tag filter — ONE chip opening the sheet
                // (was: every tag rendered inline here, cluttering the top).
                FilterChip(
                    selected = activeTags.isNotEmpty(),
                    onClick = { showTagSheet = true },
                    label = {
                        Text(if (activeTags.isEmpty()) "Tags"
                        else "Tags (${activeTags.size})")
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Label,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                // v0.8.22: ONE plain "＋" chip with ONE option — "Make
                // group": a NAME-ONLY group, no directory made ("The chip '+'
                // should create a group with just a name, with no directory
                // made"). Device folders are attached afterwards from the
                // group's OWN long-press menu ("Link to folder" — new device
                // folder with the .nomedia toggle, or an existing one).
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { showPlusDropdown = !showPlusDropdown },
                        label = { Text("＋") },
                    )
                    DropdownMenu(
                        expanded = showPlusDropdown,
                        onDismissRequest = { showPlusDropdown = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Make group") },
                            leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            onClick = {
                                showPlusDropdown = false
                                showMakeGroup = true
                            },
                        )
                    }
                }
            }

            when {
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                activeGroup != null && gridItems.isEmpty() -> {
                    // v0.8.22: an empty GROUP view — the group exists but
                    // has no wallpapers and (if linked) its folder has no
                    // images. Offer the two real next steps: link a device
                    // folder (its images then appear here live), or import
                    // images into the link.
                    val groupLink = groupFolders[activeGroup]
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "\"$activeGroup\" is empty",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (groupLink != null)
                                "Supported: JPG, PNG, WebP, GIF. Use the + at the top to " +
                                    "add images to this group's folder."
                            else
                                "Add wallpapers with their own hold menu, or link a device " +
                                    "folder (hold the group chip) — its images appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        if (groupLink == null) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = {
                                // v0.8.18 (review B15): the old empty catch
                                // swallowed picker-launch failures completely —
                                // the button just did nothing on OEMs whose
                                // folder picker is unavailable. Log it and tell
                                // the user, like every other picker launch does.
                                try {
                                    folderPicker.launch(null)
                                } catch (t: Throwable) {
                                    WepWawetLogger.appWarn(
                                        "LibraryScreen: folder picker failed to launch",
                                        "exception" to t.javaClass.name,
                                        "message" to (t.message ?: ""))
                                    scope.launch {
                                        snackbar.showSnackbar("Couldn't open the folder picker")
                                    }
                                }
                            }) {
                                Text("Link a folder")
                            }
                        }
                    }
                }
                gridItems.isEmpty() -> {
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
                                "Browse the Workshop tab, import files with the + button, " +
                                    "or make a group with the ＋ chip and link a device folder to it."
                            else "Try a different search or change the filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    // v0.8.13: fixed columns per tile-size bucket — the
                    // square cards make the column count the tile size, and
                    // a Fixed count guarantees Small/Medium/Large/XLarge
                    // are all visibly different (Adaptive collapsed Medium
                    // and Large to the same 2 columns on phone widths).
                    val isCompact =
                        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(tileSize.columnsFor(isCompact)),
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
                        items(gridItems, key = {
                            when (it) {
                                is LibraryItem.Wall -> "w:" + it.w.filePath
                                is LibraryItem.Img -> "i:" + it.entry.uri
                            }
                        }) { item ->
                            when (item) {
                                is LibraryItem.Wall -> LibraryWallpaperCard(
                                    wallpaper = item.w,
                                    isActive = selectedPath == item.w.filePath,
                                    isPinned = item.w.filePath in pinned,
                                    onClick = { onOpenWallpaper(item.w) },
                                    onLongClick = { contextMenuFor = item.w },
                                )
                                is LibraryItem.Img -> FolderImageTile(
                                    entry = item.entry,
                                    // The folder badge marks folder images
                                    // inside the All view.
                                    showFolderBadge = activeGroup == null,
                                    isPinned = item.entry.uri in pinned,
                                    // v0.8.16: render the crop when saved.
                                    displayUri = cropOverrides[item.entry.uri],
                                    onClick = { selectedImage = item.folder to item.entry },
                                    onLongClick = { imageMenuFor = item.entry },
                                )
                            }
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
                    playlistPickerFor = Triple(
                        target.filePath,
                        target.title.ifEmpty { target.filePath.substringAfterLast('/') },
                        target.type)
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
                        // v0.8.18 (review B12): prefs-only refresh — no full
                        // re-enumeration for a pin toggle.
                        refreshPrefs()
                        contextMenuFor = null
                    }
                }
                // ===== Add to group (the user's manual groups) =====
                val targetGroup = groupOf[target.filePath]
                ContextAction(
                    icon = {
                        Icon(
                            Icons.Filled.Folder, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    label = if (targetGroup != null)
                        "Group: $targetGroup (change)"
                    else "Add to group",
                ) {
                    contextMenuFor = null
                    groupTarget = target.filePath to
                        target.title.ifEmpty { target.filePath.substringAfterLast('/') }
                }
                // Workshop page when the import filename carries the item id
                workshopItemIdOf(target.filePath)?.let { itemId ->
                    ContextAction(
                        icon = {
                            Icon(
                                Icons.Filled.OpenInNew, contentDescription = null,
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
                    icon = {
                        Icon(
                            Icons.Filled.Close, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    label = "Remove from library",
                ) {
                    pendingDelete = target
                    contextMenuFor = null
                }
            }
        }
    }

    // ===== v0.8.15: folder-image context menu (long-press) =====
    // "the rename should be when you hold down on the image. you should
    // be able to add images to groups aswell by holding down and pin."
    imageMenuFor?.let { entry ->
        val imagePinned = entry.uri in pinned
        val imageGroup = groupOf[entry.uri]
        ModalBottomSheet(onDismissRequest = { imageMenuFor = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ContextAction(
                    icon = {
                        Icon(
                            Icons.Filled.PushPin, contentDescription = null,
                            tint = if (imagePinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    label = if (imagePinned) "Unpin from top" else "Pin to top",
                ) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            LibraryPrefs.setPinned(context, entry.uri, !imagePinned)
                        }
                        // v0.8.18 (review B12): prefs-only refresh — no full
                        // re-enumeration for a pin toggle.
                        refreshPrefs()
                        imageMenuFor = null
                    }
                }
                ContextAction(
                    icon = {
                        Icon(
                            Icons.Filled.Folder, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    label = if (imageGroup != null)
                        "Group: $imageGroup (change)"
                    else "Add to group",
                ) {
                    imageMenuFor = null
                    groupTarget = entry.uri to entry.displayName.substringBeforeLast('.')
                }
                ContextAction(
                    icon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                    label = "Add to playlist",
                ) {
                    imageMenuFor = null
                    // v0.8.16: the playlist gets the CROPPED copy when one
                    // exists (what the user sees); re-editing re-points it.
                    val resolved = ImageCropStore.resolve(context, entry.uri)
                    playlistPickerFor = Triple(
                        resolved,
                        entry.displayName.substringBeforeLast('.'),
                        "Image")
                }
                // v0.8.16: the EDITOR (crop + aspect presets) — also on the
                // preview dialog where Set lives.
                if (onEditImage != null) {
                    ContextAction(
                        icon = {
                            Icon(
                                Icons.Filled.Edit, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        label = "Edit image",
                    ) {
                        imageMenuFor = null
                        onEditImage(entry)
                    }
                }
                ContextAction(
                    icon = {
                        Icon(
                            Icons.Filled.Edit, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    label = "Rename",
                ) {
                    imageMenuFor = null
                    renameImageTarget = entry
                }
                // v0.8.17: delete ("there should be a delete option for
                // images when you hold down") — destructive, so it asks.
                ContextAction(
                    icon = {
                        Icon(
                            Icons.Filled.Delete, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    label = "Delete",
                ) {
                    imageMenuFor = null
                    deleteImageTarget = entry
                }
            }
        }
    }

    // v0.8.17: delete confirm — removes the file (SAF deleteDocument or a
    // plain File delete for suite images), its crop override, and any
    // playlist entries pointing at either path.
    deleteImageTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteImageTarget = null },
            title = { Text("Delete image?") },
            text = {
                Text(
                    "\"${entry.displayName}\" is deleted for good. " +
                        "Playlists using it lose the entry.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val victim = entry
                        deleteImageTarget = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                val resolved = ImageCropStore.resolve(context, victim.uri)
                                ImageCropStore.clear(context, victim.uri)
                                com.wepwawet.playlist.SuitePlaylistStore
                                    .removeEntryEverywhere(context, victim.uri)
                                if (resolved != victim.uri) {
                                    com.wepwawet.playlist.SuitePlaylistStore
                                        .removeEntryEverywhere(context, resolved)
                                }
                                ImageFolderAssets.deleteImage(context, victim.uri)
                            }
                            snackbar.showSnackbar(if (ok) "Deleted" else "Delete failed (see logs)")
                            if (ok) WepWawetLogger.appInfo("LibraryScreen: image deleted",
                                "file" to victim.displayName)
                            scheduleReload()
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteImageTarget = null }) { Text("Cancel") }
            },
        )
    }

    // ===== Playlist add (the saved suite playlists) =====
    playlistPickerFor?.let { target ->
        com.wepwawet.playlist.PlaylistPickerDialog(
            wallpaperTitle = target.second,
            onDismiss = { playlistPickerFor = null },
            onManagePlaylists = {
                playlistPickerFor = null
                onOpenPlaylists()
            },
            onPicked = { playlistId ->
                val path = target.first
                val title = target.second
                val type = target.third
                scope.launch {
                    val added = withContext(Dispatchers.IO) {
                        com.wepwawet.playlist.SuitePlaylistStore.addEntry(
                            context, playlistId,
                            com.wepwawet.playlist.SuitePlaylistStore.Entry(path, title, type))
                    }
                    playlistPickerFor = null
                    // v0.8.15: addEntry dedupes by filepath — say so instead
                    // of always claiming success ("Added to playlist" used to
                    // show for an entry that was already there).
                    snackbar.showSnackbar(if (added) "Added to playlist" else "Already in this playlist")
                }
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
                            // a deleted wallpaper must not stay pinned / grouped /
                            // inside a suite playlist
                            withContext(Dispatchers.IO) {
                                LibraryPrefs.setPinned(context, victim.filePath, false)
                                LibraryPrefs.assignToGroup(context, victim.filePath, null)
                                com.wepwawet.playlist.SuitePlaylistStore
                                    .removeEntryEverywhere(context, victim.filePath)
                            }
                            pendingDelete = null
                            scheduleReload()
                        }
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    // ===== Group dialogs =====

    // Assign a wallpaper to a group (context menu → "Add to group") —
    // groups are created HERE (no "＋" chip on the chip row anymore).
    // v0.8.15: (path, title) — folder images are groupable too.
    groupTarget?.let { (targetPath, targetTitle) ->
        val currentGroup = groupOf[targetPath]
        AlertDialog(
            onDismissRequest = { groupTarget = null },
            title = { Text("Add to group") },
            text = {
                Column {
                    Text(
                        targetTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (groups.isEmpty()) {
                        Text("No groups yet — type a name below to make one.")
                    } else {
                        for (group in groups) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        LibraryPrefs.assignToGroup(context, targetPath, group)
                                        groupOf = LibraryPrefs.readGroupOf(context)
                                        groupTarget = null
                                        WepWawetLogger.engine("LibraryScreen: assigned to group",
                                            "file" to targetPath,
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
                                    LibraryPrefs.assignToGroup(context, targetPath, name)
                                    groups = LibraryPrefs.readGroups(context)
                                    groupOf = LibraryPrefs.readGroupOf(context)
                                    groupTarget = null
                                    WepWawetLogger.engine("LibraryScreen: group created",
                                        "group" to name)
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
                                LibraryPrefs.assignToGroup(context, targetPath, null)
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

    // v0.8.20: the "＋" dropdown's Make-a-group flow — a name dialog; the
    // created group appears as a chip (assign wallpapers from each
    // wallpaper's own "Add to group" menu).
    if (showMakeGroup) {
        var newGroupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMakeGroup = false },
            title = { Text("Make a group") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it.take(30) },
                        singleLine = true,
                        label = { Text("Group name") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Assign wallpapers to the group from each wallpaper's menu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newGroupName.trim().take(30)
                        if (name.isNotEmpty() &&
                            LibraryPrefs.createGroup(context, name)
                        ) {
                            groups = LibraryPrefs.readGroups(context)
                            WepWawetLogger.engine("LibraryScreen: group created (+ menu)",
                                "group" to name)
                            showMakeGroup = false
                        }
                    },
                    enabled = newGroupName.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showMakeGroup = false }) { Text("Cancel") }
            },
        )
    }

    // Rename / delete a group + link/unlink its folder (long-press a
    // group chip). v0.8.22: "Long-pressing a group chip should add a
    // 'Link to folder' option that asks whether to make a new device
    // folder (with the .nomedia toggle) or choose an existing one."
    manageGroupName?.let { group ->
        var renameTo by remember(group) { mutableStateOf(group) }
        val linked = groupFolders[group]
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
                        if (linked != null)
                            "Linked folder: ${ImageFolderAssets.folderDisplayName(linked)} — " +
                                "its images are scanned live into this group."
                        else
                            "No folder linked. A linked folder's images are scanned live " +
                                "into this group (files added from other apps appear " +
                                "automatically).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Deleting the group keeps its wallpapers — they just return to \"All\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    TextButton(
                        onClick = {
                            if (LibraryPrefs.renameGroup(context, group, renameTo)) {
                                if (activeGroup == group) activeGroup = renameTo.trim().take(30)
                                groups = LibraryPrefs.readGroups(context)
                                groupFolders = LibraryPrefs.readGroupFolders(context)
                                groupOf = LibraryPrefs.readGroupOf(context)
                            }
                            manageGroupName = null
                        },
                    ) { Text("Rename") }
                    if (linked != null) {
                        TextButton(
                            onClick = {
                                // Unlink: the scan stops, the files stay.
                                LibraryPrefs.setGroupFolder(context, group, null)
                                groups = LibraryPrefs.readGroups(context)
                                groupFolders = LibraryPrefs.readGroupFolders(context)
                                manageGroupName = null
                                scope.launch { snackbar.showSnackbar("Folder unlinked") }
                                scheduleReload()
                            },
                        ) { Text("Unlink folder") }
                    } else {
                        TextButton(
                            onClick = {
                                linkFolderForGroup = group
                                manageGroupName = null
                                showPlusMenu = true
                            },
                        ) { Text("Link to folder") }
                    }
                    TextButton(
                        onClick = {
                            LibraryPrefs.deleteGroup(context, group)
                            if (activeGroup == group) activeGroup = null
                            groups = LibraryPrefs.readGroups(context)
                            groupFolders = LibraryPrefs.readGroupFolders(context)
                            groupOf = LibraryPrefs.readGroupOf(context)
                            manageGroupName = null
                            scheduleReload()
                        },
                    ) { Text("Delete group") }
                }
            },
            dismissButton = {
                TextButton(onClick = { manageGroupName = null }) { Text("Cancel") }
            },
        )
    }

    // The "Link to folder" choice (from the manage menu): make a new
    // device folder (with the .nomedia toggle) or choose an existing one.
    // Rides on the existing showPlusMenu dialog; the picked/created folder
    // links to linkFolderForGroup (or the active group / an auto-named
    // group when launched from the top-bar +).
    if (showPlusMenu) {
        FolderAddMenuDialog(
            title = if (linkFolderForGroup != null) "Link to folder"
            else if (activeGroup != null) "Add images to group"
            else "Add image folder",
            onDismiss = {
                showPlusMenu = false
                linkFolderForGroup = null
            },
            onChooseExisting = {
                showPlusMenu = false
                try {
                    folderPicker.launch(null)
                } catch (t: Throwable) {
                    WepWawetLogger.appWarn("LibraryScreen: folder picker failed",
                        "exception" to t.javaClass.name)
                }
            },
            onCreateNew = {
                showPlusMenu = false
                showCreateFolder = true
            },
            onImportOther = if (plusMenuForImport) {
                {
                    showPlusMenu = false
                    linkFolderForGroup = null
                    try {
                        importLauncher.launch(
                            getContentChooserIntent(
                                mimeTypes = arrayOf(
                                    "video/*", "image/jpg", "image/jpeg", "image/png",
                                    "image/webp", "image/gif", "application/*",
                                ),
                                allowMultiple = true))
                    } catch (t: Throwable) {
                        // v0.8.18 (review B15): surface the failure — the
                        // old empty-style catch left the menu silently
                        // doing nothing on OEM picker failures.
                        WepWawetLogger.appWarn("LibraryScreen: import picker failed",
                            "exception" to t.javaClass.name,
                            "message" to (t.message ?: ""))
                        scope.launch {
                            snackbar.showSnackbar("Couldn't open the file picker")
                        }
                    }
                }
            } else null,
        )
    }

    // ===== v0.8.13: the workshop-tag filter sheet =====
    if (showTagSheet) {
        LibraryTagFilterSheet(
            tagCounts = libraryTagCounts,
            activeTags = activeTags,
            onApply = { picked ->
                activeTags = picked
                if (picked.isNotEmpty()) {
                    activeGroup = null
                    animatedActive = false
                }
                showTagSheet = false
                WepWawetLogger.engineDebug("LibraryScreen: tag filter applied",
                    "tags" to picked.size)
            },
            onDismiss = { showTagSheet = false },
        )
    }

    // v0.8.18/22: the shared create-folder flow (parent picker → name +
    // hidden-from-gallery toggle → subfolder) — the created folder LINKS to
    // the group that asked for it (linkFolderForGroup), the active group,
    // or a fresh auto-named group.
    if (showCreateFolder) {
        CreateFolderFlow(
            visible = true,
            onDismiss = { showCreateFolder = false },
            onCreated = { dirUri ->
                showCreateFolder = false
                folderPicked(android.net.Uri.parse(dirUri),
                    linkFolderForGroup ?: activeGroup)
                linkFolderForGroup = null
                WepWawetLogger.appInfo("LibraryScreen: folder created",
                    "uri" to dirUri.takeLast(48))
            },
        )
    }

    // ===== Folder image fullscreen preview + set =====
    // v0.8.14: COIL renders the preview (same loader as the grid tiles —
    // it already works on this device, the manual decode didn't) and ONE
    // "Set as wallpaper" button sets the plain system wallpaper. The old
    // "System wallpaper" / "Set as wallpaper" (WE renderer) pair is gone —
    // basic images never touch the renderer. Rename sits beside Close.
    selectedImage?.let { (folder, entry) ->
        var fullBitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
        var loadFailed by remember(entry.uri) { mutableStateOf(false) }
        LaunchedEffect(entry.uri) {
            fullBitmap = withContext(Dispatchers.IO) {
                // v0.8.16: show the CROP when one exists (the original
                // file is never modified — see ImageCropStore).
                val resolved = ImageCropStore.resolve(context, entry.uri)
                val data: Any = if (resolved.startsWith("/"))
                    java.io.File(resolved) else android.net.Uri.parse(resolved)
                val request = ImageRequest.Builder(context)
                    .data(data)
                    .allowHardware(false)
                    .size(2048)
                    .build()
                val drawable = context.imageLoader.execute(request).drawable ?: return@withContext null
                (drawable as? BitmapDrawable)?.bitmap
            }
            if (fullBitmap == null) {
                loadFailed = true
                WepWawetLogger.appWarn("LibraryScreen: fullscreen preview decode failed",
                    "file" to entry.displayName)
            }
        }
        Dialog(
            onDismissRequest = { if (!workingImage) selectedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 48.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = fullBitmap
                    when {
                        bmp != null -> androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = entry.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        loadFailed -> Icon(
                            Icons.Filled.BrokenImage,
                            contentDescription = "Could not load image",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> CircularProgressIndicator()
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { setFolderImageAsSystemWallpaper(entry) },
                    enabled = !workingImage && fullBitmap != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (workingImage) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Filled.Wallpaper, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Set as wallpaper")
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // v0.8.16: the apply dialog's action is EDIT now ("replace
                    // the rename on the image menu where you apply and change
                    // it to an edit") — cropping with aspect presets; the
                    // original file is untouched. Rename stays on the
                    // long-press menu.
                    if (onEditImage != null) {
                        OutlinedButton(
                            onClick = {
                                if (!workingImage) {
                                    selectedImage = null
                                    onEditImage(entry)
                                }
                            },
                            enabled = !workingImage,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Edit", maxLines = 1)
                        }
                    }
                    OutlinedButton(
                        onClick = { if (!workingImage) selectedImage = null },
                        enabled = !workingImage,
                        modifier = Modifier.weight(1f),
                    ) { Text("Close", maxLines = 1) }
                }
            }
        }
    }

    // ===== v0.8.14: rename-image dialog =====
    renameImageTarget?.let { target ->
        var nameField by remember(target.uri) {
            mutableStateOf(target.displayName.substringBeforeLast('.'))
        }
        AlertDialog(
            onDismissRequest = { renameImageTarget = null },
            title = { Text("Rename image") },
            text = {
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it.take(80) },
                    singleLine = true,
                    label = { Text("Image name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameFolderImage(target, nameField)
                        renameImageTarget = null
                    },
                    enabled = nameField.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameImageTarget = null }) { Text("Cancel") }
            },
        )
    }
}

// ════════════════════════════════════════════════════════════════════
// v0.8.13: the workshop-tag filter sheet (the Wallpapers page's "Tags"
// chip) — same pattern as the Workshop page's filter sheet: a modal
// bottom sheet with a search field and multi-select chips, applied with
// one tap. Replaces the long inline tag-chip row that cluttered the top.
// ════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryTagFilterSheet(
    tagCounts: List<Pair<String, Int>>,
    activeTags: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Local working copy — nothing commits until "Apply".
    var working by remember { mutableStateOf(activeTags.toSet()) }
    var search by remember { mutableStateOf("") }
    val query = search.trim().lowercase(Locale.ROOT)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Tags", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (working.isEmpty()) "showing all"
                    else "${working.size} selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "The Steam Workshop tags of your downloaded wallpapers. " +
                    "Pick any number — the grid shows wallpapers matching ANY.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search tags…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (tagCounts.isEmpty()) {
                Text(
                    "No workshop tags yet — they fill in as you download " +
                        "wallpapers from the Workshop.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for ((tag, count) in tagCounts) {
                        if (query.isNotEmpty() &&
                            !tag.lowercase(Locale.ROOT).contains(query)
                        ) continue
                        FilterChip(
                            selected = tag in working,
                            onClick = {
                                working = if (tag in working) working - tag else working + tag
                            },
                            label = { Text("$tag ($count)") },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { working = emptySet() },
                    enabled = working.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Clear") }
                Button(
                    onClick = { onApply(working) },
                    modifier = Modifier.weight(1f),
                ) { Text("Apply") }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// Grid item model — wallpapers AND folder images under ONE sort
// ════════════════════════════════════════════════════════════════════

/**
 * v0.8.14: NEWEST/OLDEST sort by the "added to the app" date — when the
 * item ENTERED this library (download completed / folder image first
 * seen), NOT the file's own date ("in the wallpaper menu newest sorting
 * should be the date added to the app not the date the image was
 * created"). Workshop downloads seed from the download history's
 * completion timestamps; folder images from the first-seen registry;
 * everything else falls back to the file date.
 */
private sealed interface LibraryItem {
    val sortDate: Long
    val sortTitle: String

    data class Wall(
        val w: WallpaperInfoSparse,
        val addedAt: Long,
    ) : LibraryItem {
        override val sortDate: Long get() = addedAt
        override val sortTitle: String
            get() = w.title.ifEmpty { w.filePath.substringAfterLast('/') }
    }

    data class Img(
        val folder: ImageFolderAssets.SavedFolder,
        val entry: ImageFolderAssets.ImageEntry,
        val addedAt: Long,
    ) : LibraryItem {
        override val sortDate: Long get() = addedAt
        override val sortTitle: String get() = entry.displayName
    }
}

private fun libraryItemComparator(sortMethod: SortMethod): Comparator<LibraryItem> {
    val byTitle: (LibraryItem) -> String = { it.sortTitle.lowercase(Locale.ROOT) }
    return when (sortMethod) {
        SortMethod.NEWEST -> compareByDescending { it.sortDate }
        SortMethod.OLDEST -> compareBy { it.sortDate }
        SortMethod.ALPHAASC -> compareBy(byTitle)
        SortMethod.ALPHADESC -> compareByDescending(byTitle)
    }
}

/**
 * v0.8.18 (review B14): delegates to the ONE shared parser —
 * WorkshopDownloadJob.workshopItemIdOf (the object that owns the import
 * naming convention). The old private copy of the same regex could drift
 * from the convention without anything noticing.
 */
private fun workshopItemIdOf(filePath: String): String? =
    com.wepwawet.pairing.WorkshopDownloadJob.workshopItemIdOf(filePath)

/** The official SortMethod enum with its display labels. */
private enum class SortMethod(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    ALPHAASC("Alphabetically Ascending"),
    ALPHADESC("Alphabetically Descending"),
}

/**
 * v0.8.12: ONE tile size for the entire wallpaper section — the wallpaper
 * grid, the folder grids, the merged All view. Selected from the sort
 * menu; persisted via [LibraryPrefs].
 *
 * v0.8.13: DETERMINISTIC column counts (GridCells.Fixed), not
 * GridCells.Adaptive — on a ~424dp-wide phone Adaptive(150dp) and
 * Adaptive(200dp) both yielded 2 columns, so Medium and Large rendered
 * IDENTICALLY ("medium and large tiles are currently the same"). The
 * cards are square (fillMaxWidth + aspectRatio(1f)), so the column count
 * IS the tile size — a fixed count per size guarantees every step is
 * visibly different on every device.
 */
enum class LibraryTileSize(val label: String) {
    SMALL("Small tiles"),
    MEDIUM("Medium tiles"),
    LARGE("Large tiles"),
    XLARGE("Extra large tiles"),
    ;

    /**
     * The grid column count for this size bucket.
     *
     * @param isCompact `true` on phone-width screens (< 600dp), `false` on
     *   tablets/desktop-width windows.
     */
    fun columnsFor(isCompact: Boolean): Int = when (this) {
        SMALL -> if (isCompact) 4 else 6
        MEDIUM -> if (isCompact) 3 else 4
        LARGE -> if (isCompact) 2 else 3
        XLARGE -> if (isCompact) 1 else 2
    }
}

/** Pinned-wallpaper + groups + tile-size + filter persistence. */
internal object LibraryPrefs {
    private const val PREFS = "wallpapersuite_library"
    private const val KEY_PINNED = "pinned_wallpapers"

    // ===== wallpaper GROUPS (the user's manual groups).                =====
    // A group is just a name; each wallpaper maps to at most one group
    // (filePath → groupName). Stored with one prefs key per wallpaper —
    // simple, and removing a deleted wallpaper is a one-line remove().
    // v0.8.19: the group ORDER is user-controlled ("hold to drag them to
    // move their position at the top") — an ordered JSON array under
    // KEY_GROUPS_ORDER; the legacy StringSet is migrated on first read
    // (alphabetical, the old display order, so nothing jumps).
    private const val KEY_GROUPS = "wallpaper_groups"
    private const val KEY_GROUPS_ORDER = "wallpaper_groups_order"
    private const val KEY_GROUP_OF_PREFIX = "wallpaper_group_of:"
    private const val KEY_TILE_SIZE = "library_tile_size"

    // ===== v0.8.13: the last-open library filter (group / built-in
    // Animated / folder / tags) — "make it so when you close the app it
    // remembers what group you had open and starts there".
    private const val KEY_FILTER_GROUP = "last_filter_group"   // "" = none
    private const val KEY_FILTER_ANIMATED = "last_filter_animated"
    private const val KEY_FILTER_FOLDER = "last_filter_folder" // "" = none
    private const val KEY_FILTER_TAGS = "last_filter_tags"     // csv

    fun readPinned(context: android.content.Context): Set<String> =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(KEY_PINNED, emptySet()) ?: emptySet()

    fun setPinned(context: android.content.Context, filePath: String, pinned: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()).toMutableSet()
        if (pinned) current.add(filePath) else current.remove(filePath)
        prefs.edit().putStringSet(KEY_PINNED, current).apply()
    }

    /**
     * The group list in the USER'S chosen order (v0.8.19: hold-drag
     * reordering). One-time migration from the legacy StringSet in the old
     * alphabetical display order; groups created afterwards append.
     */
    fun readGroups(context: android.content.Context): List<String> {
        // v0.8.22: the one-time folders→groups merge runs before ANY group
        // listing (library chips, the playlist picker, Discover's save
        // dialog) so no caller ever sees the pre-merge split.
        ensureFoldersMergedIntoGroups(context)
        return readGroupsRaw(context)
    }

    /**
     * The ordered group list WITHOUT the merge pass — private: callers use
     * [readGroups]. The merge itself must read groups through THIS (it
     * runs from inside readGroups — calling readGroups there would
     * recurse forever).
     */
    private fun readGroupsRaw(context: android.content.Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val ordered = prefs.getString(KEY_GROUPS_ORDER, null)
        if (ordered != null) {
            return try {
                org.json.JSONArray(ordered).let { arr ->
                    List(arr.length()) { i -> arr.optString(i) }
                        .filter { it.isNotBlank() }
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
        // One-time migration: the old StringSet, alphabetical (the old
        // display order) — then the ordered form takes over.
        val legacy = (prefs.getStringSet(KEY_GROUPS, emptySet()) ?: emptySet())
            .filter { it.isNotBlank() }
            .sorted()
        writeGroupsOrder(context, legacy)
        return legacy
    }

    private fun writeGroupsOrder(context: android.content.Context, groups: List<String>) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GROUPS_ORDER, org.json.JSONArray(groups).toString())
            .apply()
    }

    /**
     * v0.8.19: persist a COMPLETE new group order (the chip hold-drag
     * writes the whole row at drop). Public for the drag handler; keeps
     * the legacy StringSet in sync.
     */
    fun writeGroupsOrderPublic(context: android.content.Context, groups: List<String>) {
        writeGroupsOrder(context, groups)
        writeGroups(context, groups)
    }

    /**
     * v0.8.19: move a group to a new position in the user's order (the
     * chip hold-drag). `toIndex` is clamped to the list bounds.
     */
    fun moveGroupTo(context: android.content.Context, name: String, toIndex: Int) {
        val groups = readGroups(context).toMutableList()
        val from = groups.indexOf(name)
        if (from < 0) return
        val to = toIndex.coerceIn(0, groups.size - 1)
        if (to == from) return
        groups.removeAt(from)
        groups.add(to, name)
        writeGroupsOrder(context, groups)
        // Keep the legacy set in sync for any pre-migration reader.
        writeGroups(context, groups)
    }

    private fun writeGroups(context: android.content.Context, groups: Collection<String>) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_GROUPS, groups.toSet()).apply()
    }

    fun createGroup(context: android.content.Context, name: String): Boolean {
        val trimmed = name.trim().take(30)
        if (trimmed.isEmpty()) return false
        val groups = readGroups(context).toMutableList()
        if (groups.contains(trimmed)) return false
        groups.add(trimmed) // v0.8.19: new groups append (user's order).
        writeGroupsOrder(context, groups)
        writeGroups(context, groups)
        return true
    }

    fun renameGroup(context: android.content.Context, oldName: String, newName: String): Boolean {
        val trimmed = newName.trim().take(30)
        if (trimmed.isEmpty() || trimmed == oldName) return false
        // v0.8.19: rename IN PLACE — the group keeps its position in the
        // user's order (was a Set round-trip that re-sorted alphabetically).
        val ordered = readGroups(context).toMutableList()
        val idx = ordered.indexOf(oldName)
        if (idx < 0) return false
        if (ordered.contains(trimmed)) return false
        ordered[idx] = trimmed
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val edit = prefs.edit()
            .putString(KEY_GROUPS_ORDER, org.json.JSONArray(ordered).toString())
            .putStringSet(KEY_GROUPS, ordered.toSet())
        // Re-key every member's assignment.
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_GROUP_OF_PREFIX) && value == oldName) {
                edit.putString(key, trimmed)
            }
        }
        // v0.8.22: the linked folder follows the rename.
        prefs.getString(KEY_GROUP_FOLDER_PREFIX + oldName, null)?.let { link ->
            edit.remove(KEY_GROUP_FOLDER_PREFIX + oldName)
                .putString(KEY_GROUP_FOLDER_PREFIX + trimmed, link)
        }
        edit.apply()
        return true
    }

    fun deleteGroup(context: android.content.Context, name: String) {
        // v0.8.19: delete from the ordered list (position-aware) and the
        // legacy set, then unassign every member.
        val ordered = readGroups(context).toMutableList()
        ordered.remove(name)
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val edit = prefs.edit()
            .putString(KEY_GROUPS_ORDER, org.json.JSONArray(ordered).toString())
            .putStringSet(KEY_GROUPS, ordered.toSet())
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_GROUP_OF_PREFIX) && value == name) {
                edit.remove(key)
            }
        }
        // v0.8.22: a linked group takes its folder link with it (the
        // folder's files stay on disk; the scan just stops).
        edit.remove(KEY_GROUP_FOLDER_PREFIX + name)
        edit.apply()
        dropFolderIfUnlinked(context)
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

    fun assignToGroup(context: android.content.Context, filePath: String, group: String?) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (group == null) {
            prefs.edit().remove(KEY_GROUP_OF_PREFIX + filePath).apply()
        } else {
            prefs.edit().putString(KEY_GROUP_OF_PREFIX + filePath, group).apply()
        }
    }

    // ══ v0.8.22: UNIFIED GROUPS — folders merged INTO groups ══════════
    // "Merge the app's separate folders and groups into one thing called
    // a group, and remove the folder chips and the folder-creation flow as
    // separate features." A group is a name + optional members, PLUS an
    // optional LINKED device folder: a linked group's grid is the live
    // scan of that directory (files added from other apps appear
    // automatically on every library reload) combined with anything
    // manually added to the group. Folders no longer exist as standalone
    // chips — only as group links.

    private const val KEY_GROUP_FOLDER_PREFIX = "group_folder_of:"
    private const val KEY_FOLDERS_MERGED = "folders_merged_into_groups"

    /** A group + its optional linked folder ("" = none). */
    data class GroupInfo(val name: String, val folderUri: String?)

    /** The tree URI linked to [group], or null. */
    fun groupFolderOf(context: android.content.Context, group: String): String? =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_GROUP_FOLDER_PREFIX + group, null)
            ?.takeIf { it.isNotBlank() }

    /** Every group WITH its linked-folder state (the unified group list). */
    fun readGroupsInfo(context: android.content.Context): List<GroupInfo> =
        readGroups(context).map { GroupInfo(it, groupFolderOf(context, it)) }

    /** Every group's linked folder (groups without a link are absent). */
    fun readGroupFolders(context: android.content.Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val result = mutableMapOf<String, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_GROUP_FOLDER_PREFIX) && value is String &&
                value.isNotBlank()
            ) {
                result[key.removePrefix(KEY_GROUP_FOLDER_PREFIX)] = value
            }
        }
        return result
    }

    /**
     * Link (or unlink with null) a device folder to [group]. Unlinking
     * drops the folder from the scan registry once NO group links it (its
     * files stay on disk untouched).
     */
    fun setGroupFolder(context: android.content.Context, group: String, treeUri: String?) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (treeUri == null) {
            prefs.edit().remove(KEY_GROUP_FOLDER_PREFIX + group).apply()
            dropFolderIfUnlinked(context)
        } else {
            prefs.edit().putString(KEY_GROUP_FOLDER_PREFIX + group, treeUri).apply()
        }
    }

    /** Remove a folder from the scan registry when no group links it. */
    private fun dropFolderIfUnlinked(context: android.content.Context) {
        val linked = readGroupFolders(context).values.toSet()
        for (folder in ImageFolderAssets.readFolders(context)) {
            if (folder.treeUri !in linked) {
                ImageFolderAssets.removeFolder(context, folder.treeUri)
            }
        }
    }

    /**
     * v0.8.22 one-time migration: every existing FOLDER becomes a GROUP
     * with that folder linked (name de-conflicted against existing groups;
     * position after the old groups), so nothing is lost and folder chips
     * simply become group chips. The folders themselves stay registered in
     * ImageFolderAssets — that object is the linked-scan engine now, not a
     * standalone UI feature.
     */
    private fun ensureFoldersMergedIntoGroups(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FOLDERS_MERGED, false)) return
        // readGroupsRaw, NOT readGroups — this runs from inside readGroups;
        // the public one would recurse forever.
        val existing = readGroupsRaw(context).toMutableList()
        val taken = existing.toMutableSet()
        var migrated = 0
        val edit = prefs.edit()
        for (folder in ImageFolderAssets.readFolders(context)) {
            // A unique group name from the folder's display name.
            var name = folder.name.ifBlank { "Folder" }.trim().take(30)
            if (name in taken) {
                var n = 2
                while ("$name ($n)" in taken) n++
                name = "$name ($n)".take(30)
            }
            taken.add(name)
            existing.add(name)
            edit.putString(KEY_GROUP_FOLDER_PREFIX + name, folder.treeUri)
            migrated++
        }
        if (migrated > 0) {
            edit.putString(KEY_GROUPS_ORDER, org.json.JSONArray(existing).toString())
            edit.putStringSet(KEY_GROUPS, existing.toSet())
        }
        edit.putBoolean(KEY_FOLDERS_MERGED, true).apply()
        if (migrated > 0) {
            WepWawetLogger.appInfo("LibraryPrefs: folders merged into groups",
                "migrated" to migrated)
        }
    }

    fun readTileSize(context: android.content.Context): LibraryTileSize {
        val name = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_TILE_SIZE, null)
        return runCatching { LibraryTileSize.valueOf(name ?: "") }
            .onFailure {
                WepWawetLogger.appWarn("LibraryScreen: swallowed failure at line 2554",
                    "exception" to it.javaClass.simpleName,
                    "message" to (it.message ?: ""))
            }
        .getOrDefault(LibraryTileSize.MEDIUM)
    }

    // ── v0.8.13: last-open filter persistence ──────────────────────

    fun saveLibraryFilter(
        context: android.content.Context,
        group: String?,
        animated: Boolean,
        folder: String?,
        tags: Set<String>,
    ) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FILTER_GROUP, group ?: "")
            .putBoolean(KEY_FILTER_ANIMATED, animated)
            .putString(KEY_FILTER_FOLDER, folder ?: "")
            .putString(KEY_FILTER_TAGS, tags.joinToString(","))
            .apply()
    }

    /** The saved filter, or null when nothing was ever saved. */
    fun readSavedLibraryFilter(context: android.content.Context):
            Tuple4<String?, Boolean, String?, Set<String>>? {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_FILTER_GROUP) &&
            !prefs.contains(KEY_FILTER_FOLDER) &&
            !prefs.contains(KEY_FILTER_ANIMATED)
        ) return null
        val group = prefs.getString(KEY_FILTER_GROUP, "").orEmpty().ifEmpty { null }
        val animated = prefs.getBoolean(KEY_FILTER_ANIMATED, false)
        val folder = prefs.getString(KEY_FILTER_FOLDER, "").orEmpty().ifEmpty { null }
        val tags = prefs.getString(KEY_FILTER_TAGS, "").orEmpty()
            .split(",").filter { it.isNotBlank() }.toSet()
        return Tuple4(group, animated, folder, tags)
    }

    /** Minimal tuple (avoids a new public type just for the filter). */
    data class Tuple4<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )

    fun writeTileSize(context: android.content.Context, size: LibraryTileSize) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_TILE_SIZE, size.name).apply()
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
 * One folder-image cell (v0.8.12): square, same tile size as the
 * wallpaper cards, loaded through COIL — the shared ImageLoader brings
 * memory/disk caching, GIF support, measured downsampling and a proper
 * error state (the old manual BitmapFactory decode failed silently,
 * which is why "the images in the folder are not loading").
 * v0.8.15: long-press opens the image context menu (pin / group /
 * playlist / rename) exactly like the wallpaper cards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderImageTile(
    entry: ImageFolderAssets.ImageEntry,
    showFolderBadge: Boolean,
    isPinned: Boolean = false,
    // v0.8.16: what to actually RENDER — the crop copy when one exists
    // (cropOverrides[uri]), else the original uri.
    displayUri: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onLongClick != null)
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                else Modifier.clickable(onClick = onClick)
            ),
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(displayUri ?: entry.uri)
                .crossfade(true)
                .build(),
            contentDescription = entry.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.BrokenImage,
                        contentDescription = "Could not load image",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        // Folder badge — marks folder images inside the All view.
        if (showFolderBadge) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = "Folder image",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(50))
                    .padding(4.dp)
                    .size(14.dp),
            )
        }

        // v0.8.15: pinned marker — same look as the wallpaper cards, so
        // pins read identically across the whole grid.
        if (isPinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(50))
                    .padding(4.dp),
            )
        }

        // Title overlay at the bottom — same rhythm as the wallpaper cards.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0x99000000)),
        ) {
            Text(
                entry.displayName.substringBeforeLast('.'),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
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
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        val bmp = preview
        if (bmp != null) {
            androidx.compose.foundation.Image(
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

        // Active marker: a subtle check chip.
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
