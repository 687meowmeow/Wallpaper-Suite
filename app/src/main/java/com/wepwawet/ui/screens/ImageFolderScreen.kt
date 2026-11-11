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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * v0.9.0: Image folders — pick a folder from the device (SAF), browse its
 * images in a grid, and set any of them as the live wallpaper.
 *
 * "Set as wallpaper" packs the image into a Scene .mpkg via the SAME
 * packer used for image-type workshop downloads ([MpkgPacker.packSceneWallpaper]
 * — single fullscreen quad + RGBA texture), imports it into the engine
 * library, and applies it through the standard WallpaperSuite flow.
 * A secondary action sets it as the plain SYSTEM wallpaper for people
 * who want the image outside the engine too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageFolderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var folderUri by remember { mutableStateOf<String?>(readSavedFolder(context)) }
    var images by remember { mutableStateOf<List<ImageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ImageEntry?>(null) }
    var working by remember { mutableStateOf(false) }

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

    // Load the folder's images whenever the folder changes.
    LaunchedEffect(folderUri) {
        val uriStr = folderUri ?: return@LaunchedEffect
        loading = true
        images = withContext(Dispatchers.IO) { enumerateFolderImages(context, uriStr) }
        loading = false
        WepWawetLogger.appInfo("ImageFolderScreen: enumerated",
            "count" to images.size)
    }

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
                    val bytes = context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use { it.readBytes() }
                    val bmp = bytes?.let {
                        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                    }
                    if (bmp == null) return@withContext false
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
                title = "Image folders",
                onBack = onBack,
                actions = {
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
                        Text("Pick a folder of images", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Choose any folder on your device — its images show up here " +
                                "and can be set as your wallpaper with one tap.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    // The set-as-wallpaper choice dialog.
    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!working) selected = null },
            title = { Text("Set as wallpaper") },
            text = {
                Column {
                    Text(
                        entry.displayName,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Engine wallpaper packs the image as a Scene and plays it through " +
                            "WallpaperSuite (with all engine features). System wallpaper " +
                            "sets it as a plain Android wallpaper.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { setAsSystemWallpaper(entry) },
                        enabled = !working,
                    ) { Text("System") }
                    Button(
                        onClick = { setAsEngineWallpaper(entry) },
                        enabled = !working,
                    ) {
                        if (working) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Icon(Icons.Filled.Wallpaper, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Set as wallpaper")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!working) selected = null }) { Text("Cancel") }
            },
        )
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

private fun readSavedFolder(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)

private fun writeSavedFolder(context: Context, uri: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_FOLDER, uri).apply()
}

/** Query a SAF tree folder for image/* files, newest first, capped at 500. */
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
        result.sortedByDescending { it.lastModified }
    } catch (t: Throwable) {
        WepWawetLogger.appWarn("ImageFolderScreen: enumerate failed",
            "exception" to t.javaClass.name)
        emptyList()
    }
}

/**
 * Pack a SAF image into the engine library: copy to cache → packSceneWallpaper
 * → import into filesDir/downloads → return the WallpaperInfoSparse for the
 * caller to apply. Mirrors the image-type workshop download path exactly.
 */
private fun packAndImportImage(context: Context, entry: ImageEntry): WallpaperInfoSparse? {
    return try {
        val cacheFile = File(context.cacheDir, "image_folder_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        val itemId = "img_${System.currentTimeMillis()}"
        val title = entry.displayName.substringBeforeLast('.').take(40).ifEmpty { "image" }
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

@Composable
private fun ImageGridCell(entry: ImageEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(entry.uri) { mutableStateOf(false) }

    // Subsampled thumbnail decode (inSampleSize targets ~512 px).
    LaunchedEffect(entry.uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                contextBytes(context, entry.uri)?.let { bytes ->
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    while (maxOf(opts.outWidth, opts.outHeight) / sample > 512) sample *= 2
                    bitmap = android.graphics.BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
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

private fun contextBytes(context: android.content.Context, uriStr: String): ByteArray? =
    try {
        context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }
