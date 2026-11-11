package com.wepwawet.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wepwawet.data.model.WorkshopItem
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weutil.WallpaperInfoSparse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Folders — pick a folder from the device (SAF), browse its images in a
 * grid, and set any of them as the live wallpaper.
 *
 * "Set as wallpaper" packs the image into a Scene .mpkg via the SAME
 * packer used for image-type workshop downloads ([MpkgPacker.packSceneWallpaper]
 * — single fullscreen quad + RGBA texture), imports it into the engine
 * library, and applies it through the standard WallpaperSuite flow.
 * A secondary action sets it as the plain SYSTEM wallpaper for people
 * who want the image outside the engine too.
 *
 * v0.8.9 (the "extremely bare bones" rework):
 *  - a bottom-nav tab (no back arrow) with the folder + count in a header;
 *  - memory-efficient thumbnails (two-pass stream decode with
 *    inSampleSize — the old code read WHOLE image byte arrays per cell);
 *  - tap opens a fullscreen preview dialog (what you see is what gets
 *    applied) instead of a bare confirmation alert;
 *  - idempotent imports: the library entry is keyed by the image's
 *    document id + last-modified, so re-setting the same image REUSES
 *    the packed .mpkg instead of stacking duplicates in downloads/;
 *  - sort by Newest / Name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageFolderScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var folderUri by remember { mutableStateOf<String?>(readSavedFolder(context)) }
    var images by remember { mutableStateOf<List<ImageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ImageEntry?>(null) }
    var working by remember { mutableStateOf(false) }
    var sortNewest by remember { mutableStateOf(true) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist the grant so the folder survives reboots.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            folderUri = uri.toString()
            writeSavedFolder(context, uri.toString())
            WepWawetLogger.appInfo("ImageFolderScreen: folder picked",
                "uri" to uri.toString().take(120))
        }
    }

    // Load the folder's images whenever the folder or sort changes.
    LaunchedEffect(folderUri, sortNewest) {
        val uriStr = folderUri ?: return@LaunchedEffect
        loading = true
        images = withContext(Dispatchers.IO) {
            val list = enumerateFolderImages(context, uriStr)
            if (sortNewest) list.sortedByDescending { it.lastModified }
            else list.sortedBy { it.displayName.lowercase() }
        }
        loading = false
        WepWawetLogger.appInfo("ImageFolderScreen: enumerated",
            "count" to images.size)
    }

    /**
     * Pack (or reuse) + import + apply. v0.8.9: the library entry is
     * keyed by the image identity, so setting the same image again
     * applies the EXISTING library file — no duplicate .mpkg churn.
     */
    fun setAsEngineWallpaper(entry: ImageEntry) {
        if (working) return
        working = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    packAndImportImage(context, entry)
                }
                if (result != null) {
                    // Apply through the official flow (hot-reload when the
                    // live service is up, otherwise the system picker).
                    WeEnginePrefs.callApplyWallpaper(context, result, false)
                    snackbar.showSnackbar("Wallpaper set ✓ — \"${result.title}\"")
                    WepWawetLogger.appInfo("ImageFolderScreen: engine wallpaper set",
                        "file" to result.filePath,
                        "title" to result.title)
                } else {
                    snackbar.showSnackbar("Failed to pack the image (see logs)")
                }
            } finally {
                working = false
                selected = null
            }
        }
    }

    fun setAsSystemWallpaper(entry: ImageEntry) {
        if (working) return
        working = true
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    val bmp = decodeBitmapScaled(context, entry.uri, 4096) ?: return@withContext false
                    val crop = com.wepwawet.nyanpasu.NyanpasuApplier.cropForScreen(context, bmp)
                    val result = com.wepwawet.nyanpasu.NyanpasuApplier.apply(
                        context, crop, homeOn = true, lockOn = true)
                    result.ok
                }
                snackbar.showSnackbar(if (ok) "System wallpaper set ✓" else "System wallpaper write failed")
            } finally {
                working = false
                selected = null
            }
        }
    }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Folders",
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (sortNewest) "✓ Newest first" else "Newest first") },
                                onClick = { sortNewest = true; sortMenuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (!sortNewest) "✓ Name (A–Z)" else "Name (A–Z)") },
                                onClick = { sortNewest = false; sortMenuOpen = false },
                            )
                        }
                    }
                    IconButton(onClick = {
                        try {
                            folderPicker.launch(null)
                        } catch (t: Throwable) {
                            WepWawetLogger.appWarn("ImageFolderScreen: picker launch failed",
                                "exception" to t.javaClass.name)
                        }
                    }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Choose folder")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                folderUri == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Turn a folder into wallpapers", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pick any folder on your device — its images show up here " +
                                "and can be set as your wallpaper with one tap.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { folderPicker.launch(null) }) {
                            Text("Choose folder")
                        }
                    }
                }
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                images.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("No images in this folder", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Supported: JPG, PNG, WebP, GIF.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { folderPicker.launch(null) }) {
                            Text("Choose a different folder")
                        }
                    }
                }
                else -> {
                    // Folder header: name + count (compact, single line).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                folderDisplayName(folderUri!!),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${images.size} images",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { folderPicker.launch(null) }) {
                            Text("Change")
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(images, key = { it.uri }) { entry ->
                            ImageGridCell(entry) { selected = entry }
                        }
                    }
                }
            }
        }
    }

    // ===== Fullscreen preview + set-as-wallpaper dialog =====
    selected?.let { entry ->
        var fullBitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(entry.uri) {
            fullBitmap = withContext(Dispatchers.IO) {
                decodeBitmapScaled(context, entry.uri, 2048)
            }
        }
        Dialog(
            onDismissRequest = { if (!working) selected = null },
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
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = entry.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CircularProgressIndicator()
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { setAsSystemWallpaper(entry) },
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                    ) { Text("System wallpaper") }
                    Button(
                        onClick = { setAsEngineWallpaper(entry) },
                        enabled = !working && fullBitmap != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (working) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(Icons.Filled.Wallpaper, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("Set as wallpaper")
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { if (!working) selected = null }) { Text("Close") }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// Data + helpers
// ════════════════════════════════════════════════════════════════════

data class ImageEntry(
    val uri: String,
    val displayName: String,
    val lastModified: Long,
)

private const val PREFS = "wallpapersuite_image_folders"
private const val KEY_FOLDER = "folder_uri"
private const val KEY_IMPORTS = "imported_images"

private fun readSavedFolder(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)

private fun writeSavedFolder(context: Context, uri: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_FOLDER, uri).apply()
}

/** "content://…/tree/primary%3APictures%2FWallpapers" → "Pictures/Wallpapers". */
private fun folderDisplayName(treeUriStr: String): String = try {
    val docId = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(treeUriStr))
    docId.substringAfter(':').ifBlank { docId }
} catch (t: Throwable) {
    "Folder"
}

/** Query a SAF tree folder for image-kind files, capped at 500. */
private fun enumerateFolderImages(context: Context, folderUriStr: String): List<ImageEntry> {
    return try {
        val folder = Uri.parse(folderUriStr)
        val docId = android.provider.DocumentsContract.getTreeDocumentId(folder)
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            folder, docId)
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<ImageEntry>()
        context.contentResolver.query(
            children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && result.size < 500) {
                val mime = cursor.getString(2) ?: continue
                if (!mime.startsWith("image/")) continue
                val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    folder, cursor.getString(0))
                result.add(ImageEntry(
                    uri = docUri.toString(),
                    displayName = cursor.getString(1) ?: "image",
                    lastModified = cursor.getLong(3),
                ))
            }
        }
        result
    } catch (t: Throwable) {
        WepWawetLogger.appWarn("ImageFolderScreen: enumerate failed",
            "exception" to t.javaClass.name)
        emptyList()
    }
}

/**
 * v0.8.9: memory-efficient decode — two passes over a STREAM (bounds,
 * then subsampled pixels) with inSampleSize targeting [maxDim]. The old
 * code read the entire image into a ByteArray per grid cell, which cost
 * tens of MB of heap per thumbnail on modern phone cameras.
 */
private fun decodeBitmapScaled(context: Context, uriStr: String, maxDim: Int): Bitmap? {
    return try {
        val uri = Uri.parse(uriStr)
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (t: Throwable) {
        null
    }
}

/**
 * Pack a SAF image into the engine library: copy to cache → packSceneWallpaper
 * → import into filesDir/downloads → return the WallpaperInfoSparse for the
 * caller to apply. Mirrors the image-type workshop download path exactly.
 *
 * v0.8.9: IDEMPOTENT — the itemId is a stable hash of the image's document
 * uri + last-modified, and the last import is remembered in prefs. Setting
 * the same image again reuses (or overwrites) the SAME library entry
 * instead of stacking duplicate .mpkg files in downloads/.
 */
private fun packAndImportImage(context: Context, entry: ImageEntry): WallpaperInfoSparse? {
    return try {
        val title = entry.displayName.substringBeforeLast('.').take(40).ifEmpty { "image" }
        val stableKey = (entry.uri + "|" + entry.lastModified).hashCode().toLong() and 0xFFFFFFFFL
        val itemId = "img_$stableKey"

        // Reuse path: the same image was imported before and its library
        // file is still on disk — just hand back that entry.
        val remembered = rememberedImport(context, entry)
        if (remembered != null) {
            val existing = File(context.filesDir, remembered)
            if (existing.exists() && existing.length() > 0L) {
                WepWawetLogger.appInfo("ImageFolderScreen: reusing packed import",
                    "file" to remembered)
                return WallpaperInfoSparse().apply {
                    this.title = title
                    this.filePath = remembered
                    this.type = "Scene"
                    this.lastModifiedDate = existing.lastModified()
                }
            }
        }

        val cacheFile = File(context.cacheDir, "image_folder_${itemId}")
        context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        val mpkgFile = File(context.cacheDir, "${itemId}_pack.mpkg")
        val size = com.wepwawet.pairing.MpkgPacker.packSceneWallpaper(
            item = com.wepwawet.data.model.WorkshopItemTyped(
                item = WorkshopItem(publishedfileid = itemId, title = title),
                type = WallpaperType.SCENE,
            ),
            imageFile = cacheFile,
            outputFile = mpkgFile,
        )
        runCatching { cacheFile.delete() }
        if (size <= 0L || !mpkgFile.exists()) {
            WepWawetLogger.appWarn("ImageFolderScreen: pack failed",
                "entry" to entry.displayName)
            return null
        }

        // Import into the library (same layout as workshop downloads).
        // The stable itemId makes a re-import overwrite the same file.
        val sanitized = title.replace(Regex("[^\\w \\-()]", RegexOption.IGNORE_CASE), "")
            .trim().take(40).ifEmpty { "image" }
        val target = File(context.filesDir, "downloads/$itemId - $sanitized.mpkg")
        target.parentFile?.mkdirs()
        mpkgFile.copyTo(target, overwrite = true)
        runCatching { mpkgFile.delete() }
        if (!EngineLibrary.sceneLib(context).isWallpaperVersionValid("downloads/${target.name}")) {
            WepWawetLogger.appWarn("ImageFolderScreen: engine rejected the packed image",
                "file" to target.name)
            target.delete()
            return null
        }
        rememberImport(context, entry, "downloads/${target.name}")
        WepWawetLogger.appInfo("ImageFolderScreen: image packed + imported",
            "file" to target.name,
            "size" to target.length())
        WallpaperInfoSparse().apply {
            this.title = title
            this.filePath = "downloads/${target.name}"
            this.type = "Scene"
            this.lastModifiedDate = System.currentTimeMillis()
        }
    } catch (t: Throwable) {
        WepWawetLogger.appError("ImageFolderScreen: packAndImport failed",
            "exception" to t.javaClass.name,
            "message" to (t.message ?: ""))
        null
    }
}

/** The prefs map key for an image's remembered import. */
private fun importKey(entry: ImageEntry): String =
    (entry.uri + "|" + entry.lastModified).hashCode().toString(16)

private fun rememberedImport(context: Context, entry: ImageEntry): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_IMPORTS + ":" + importKey(entry), null)

private fun rememberImport(context: Context, entry: ImageEntry, libraryPath: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_IMPORTS + ":" + importKey(entry), libraryPath).apply()
}

@Composable
private fun ImageGridCell(entry: ImageEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(entry.uri) { mutableStateOf(false) }

    // v0.8.9: subsampled stream decode (max ~512 px) — see decodeBitmapScaled.
    LaunchedEffect(entry.uri) {
        withContext(Dispatchers.IO) {
            bitmap = decodeBitmapScaled(context, entry.uri, 512)
            loaded = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = entry.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!loaded) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp))
        }
    }
}
