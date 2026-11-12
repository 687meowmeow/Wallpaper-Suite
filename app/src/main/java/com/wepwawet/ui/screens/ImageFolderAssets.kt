package com.wepwawet.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.wepwawet.data.model.WorkshopItem
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.engine.EngineLibrary
import com.wepwawet.log.WepWawetLogger
import io.wallpaperengine.weutil.WallpaperInfoSparse
import java.io.File

/**
 * Shared folder-image machinery for the Wallpapers tab (v0.8.11 — the
 * Folders tab was folded INTO the main wallpaper page).
 *
 * The user picks any device folder (SAF); its images appear in the
 * library grid under their own filter chip. Tapping one packs it into a
 * Scene .mpkg through the SAME packer used for image-type workshop
 * downloads ([com.wepwawet.pairing.MpkgPacker.packSceneWallpaper]) and
 * applies it through the standard flow.
 *
 * Multiple folders are supported — each is persisted as a tree URI with
 * a persistable read grant.
 */
object ImageFolderAssets {

    data class ImageEntry(
        val uri: String,
        val displayName: String,
        val lastModified: Long,
    )

    data class SavedFolder(
        val treeUri: String,
        val name: String,
    )

    private const val PREFS = "wallpapersuite_image_folders"
    private const val KEY_FOLDERS = "folder_uris"
    private const val KEY_IMPORTS = "imported_images"
    // Legacy single-folder key (v0.8.9/v0.8.10) — migrated on first read.
    private const val KEY_LEGACY_FOLDER = "folder_uri"

    // ── Folder registry ──────────────────────────────────────────────

    fun readFolders(context: Context): List<SavedFolder> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = prefs.getString(KEY_LEGACY_FOLDER, null)
        val set = prefs.getStringSet(KEY_FOLDERS, null) ?: mutableSetOf()
        // One-time migration of the v0.8.9 single folder.
        if (legacy != null && legacy !in set) {
            set.toMutableSet().apply { add(legacy) }.let {
                prefs.edit().putStringSet(KEY_FOLDERS, it).apply()
            }
            prefs.edit().remove(KEY_LEGACY_FOLDER).apply()
            WepWawetLogger.appInfo("ImageFolderAssets: legacy folder migrated")
            return set.plus(legacy).map { SavedFolder(it, folderDisplayName(it)) }
        }
        return set.map { SavedFolder(it, folderDisplayName(it)) }
    }

    fun addFolder(context: Context, treeUri: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
        if (set.add(treeUri)) {
            prefs.edit().putStringSet(KEY_FOLDERS, set).apply()
            WepWawetLogger.appInfo("ImageFolderAssets: folder added",
                "uri" to treeUri.take(120))
        }
    }

    fun removeFolder(context: Context, treeUri: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()).toMutableSet()
        if (set.remove(treeUri)) {
            prefs.edit().putStringSet(KEY_FOLDERS, set).apply()
            WepWawetLogger.appInfo("ImageFolderAssets: folder removed",
                "uri" to treeUri.take(120))
        }
    }

    /** "content://…/tree/primary%3APictures%2FWallpapers" → "Pictures/Wallpapers". */
    fun folderDisplayName(treeUriStr: String): String = try {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(treeUriStr))
        docId.substringAfter(':').ifBlank { docId }
    } catch (t: Throwable) {
        "Folder"
    }

    // ── Enumeration + decode ─────────────────────────────────────────

    /** Query a SAF tree folder for image-kind files, capped at 500. */
    fun enumerateFolderImages(context: Context, folderUriStr: String): List<ImageEntry> {
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
            WepWawetLogger.appWarn("ImageFolderAssets: enumerate failed",
                "exception" to t.javaClass.name)
            emptyList()
        }
    }

    /**
     * Memory-efficient decode — two passes over a STREAM (bounds, then
     * subsampled pixels) with inSampleSize targeting [maxDim].
     *
     * v0.8.12 robustness pass ("images in the folder are not loading"):
     *  • falls back to an AssetFileDescriptor + decodeFileDescriptor pass
     *    when the plain stream decode yields nothing (some providers —
     *    cloud/OTG/scoped-storage bridges — serve seekable fds but flaky
     *    streams);
     *  • logs a WARN with the reason on failure so the next log dump tells
     *    us exactly which images and why (the old code failed silently).
     */
    fun decodeBitmapScaled(context: Context, uriStr: String, maxDim: Int): Bitmap? {
        val uri = Uri.parse(uriStr)
        val streamResult = try {
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
            WepWawetLogger.appWarn("ImageFolderAssets: stream decode failed",
                "uri" to uriStr.takeLast(60),
                "exception" to t.javaClass.simpleName)
            null
        }
        if (streamResult != null) return streamResult

        // Fallback: fd-based decode (providers that serve seekable fds).
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val bounds = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFileDescriptor(
                    afd.fileDescriptor, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                // Re-open: the bounds pass consumed the fd position.
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd2 ->
                    android.graphics.BitmapFactory.decodeFileDescriptor(afd2.fileDescriptor, null, opts)
                }
            } ?: run {
                WepWawetLogger.appWarn("ImageFolderAssets: no fd for image",
                    "uri" to uriStr.takeLast(60))
                null
            }
        } catch (t: Throwable) {
            WepWawetLogger.appWarn("ImageFolderAssets: fd decode failed",
                "uri" to uriStr.takeLast(60),
                "exception" to t.javaClass.simpleName)
            null
        }
    }

    // ── Saving INTO a folder (Discover → "Save to folder") ───────────

    /**
     * v0.8.12: write [bitmap] as a JPEG into a picked SAF folder. Used by
     * Discover's "Save to folder" so a booru image lands in one of the
     * user's library folders (and then shows in the Wallpapers section).
     *
     * Requires the persistable WRITE grant — [takePersistableGrants].
     */
    fun saveImageToFolder(context: Context, treeUriStr: String, bitmap: Bitmap): Boolean {
        return try {
            val tree = Uri.parse(treeUriStr)
            val docId = android.provider.DocumentsContract.getTreeDocumentId(tree)
            val parentDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val name = "wallpaper_${System.currentTimeMillis()}.jpg"
            val docUri = android.provider.DocumentsContract.createDocument(
                context.contentResolver, parentDoc, "image/jpeg", name) ?: run {
                WepWawetLogger.appWarn("ImageFolderAssets: createDocument returned null",
                    "folder" to folderDisplayName(treeUriStr))
                return false
            }
            context.contentResolver.openOutputStream(docUri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            } ?: run {
                WepWawetLogger.appWarn("ImageFolderAssets: openOutputStream failed",
                    "folder" to folderDisplayName(treeUriStr))
                return false
            }
            WepWawetLogger.appInfo("ImageFolderAssets: image saved to folder",
                "folder" to folderDisplayName(treeUriStr))
            true
        } catch (t: Throwable) {
            WepWawetLogger.appWarn("ImageFolderAssets: save to folder failed",
                "exception" to t.javaClass.name,
                "message" to (t.message ?: ""))
            false
        }
    }

    /**
     * v0.8.13: copy picked images (photo picker / SAF URIs) INTO one of the
     * user's library folders — the Wallpapers page's "+" button while a
     * folder chip is active ("i should be able to add an image to the
     * folder using the plus at the top").
     *
     * Each image becomes a new document in the tree (the provider
     * auto-resolves name collisions). Requires the persistable WRITE grant
     * taken by [takePersistableGrants]. Returns (copied, failed) counts.
     */
    fun importImagesToFolder(
        context: Context,
        treeUriStr: String,
        sources: List<Uri>,
    ): Pair<Int, Int> {
        var copied = 0
        var failed = 0
        val tree = Uri.parse(treeUriStr)
        for (source in sources) {
            try {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                val parentDoc =
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                // Prefer the provider's display name; fall back to a timestamp.
                val displayName = queryDisplayName(context, source)
                    ?: "image_${System.currentTimeMillis()}.jpg"
                val mime = context.contentResolver.getType(source) ?: "image/jpeg"
                val target = android.provider.DocumentsContract.createDocument(
                    context.contentResolver, parentDoc, mime, displayName)
                if (target == null) {
                    failed++
                    WepWawetLogger.appWarn("ImageFolderAssets: createDocument returned null",
                        "name" to displayName)
                    continue
                }
                val ok = try {
                    context.contentResolver.openInputStream(source)?.use { input ->
                        context.contentResolver.openOutputStream(target)?.use { output ->
                            input.copyTo(output)
                            true
                        } ?: false
                    } ?: false
                } catch (t: Throwable) {
                    WepWawetLogger.appWarn("ImageFolderAssets: copy stream failed",
                        "name" to displayName,
                        "exception" to t.javaClass.simpleName)
                    false
                }
                if (ok) copied++ else {
                    failed++
                    runCatching {
                        context.contentResolver.delete(target, null)
                    }
                }
            } catch (t: Throwable) {
                failed++
                WepWawetLogger.appWarn("ImageFolderAssets: import image failed",
                    "exception" to t.javaClass.name,
                    "message" to (t.message ?: ""))
            }
        }
        WepWawetLogger.appInfo("ImageFolderAssets: images imported to folder",
            "folder" to folderDisplayName(treeUriStr),
            "copied" to copied,
            "failed" to failed)
        return copied to failed
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                var idx = cursor.getColumnIndex(
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx < 0) idx = 0
                cursor.getString(idx)
            } else null
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * v0.8.12: take BOTH the read and the write persistable grants for a
     * picked tree — read for enumeration, write so images can be saved
     * into the folder later (Discover's "Save to folder").
     */
    fun takePersistableGrants(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    // ── Pack + import ────────────────────────────────────────────────

    /**
     * Pack a SAF image into the engine library: copy to cache →
     * packSceneWallpaper → import into filesDir/downloads → return the
     * WallpaperInfoSparse for the caller to apply.
     *
     * IDEMPOTENT — the itemId is a stable hash of the image's document
     * uri + last-modified, and the last import is remembered in prefs.
     * Setting the same image again reuses (or overwrites) the SAME
     * library entry instead of stacking duplicate .mpkg files.
     */
    fun packAndImportImage(context: Context, entry: ImageEntry): WallpaperInfoSparse? {
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
                    WepWawetLogger.appInfo("ImageFolderAssets: reusing packed import",
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
                WepWawetLogger.appWarn("ImageFolderAssets: pack failed",
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
                WepWawetLogger.appWarn("ImageFolderAssets: engine rejected the packed image",
                    "file" to target.name)
                target.delete()
                return null
            }
            rememberImport(context, entry, "downloads/${target.name}")
            WepWawetLogger.appInfo("ImageFolderAssets: image packed + imported",
                "file" to target.name,
                "size" to target.length())
            WallpaperInfoSparse().apply {
                this.title = title
                this.filePath = "downloads/${target.name}"
                this.type = "Scene"
                this.lastModifiedDate = System.currentTimeMillis()
            }
        } catch (t: Throwable) {
            WepWawetLogger.appError("ImageFolderAssets: packAndImport failed",
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
}
