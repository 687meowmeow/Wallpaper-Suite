package com.wepwawet.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.wepwawet.log.WepWawetLogger
import java.io.File

/**
 * Shared folder-image machinery for the Wallpapers tab (v0.8.11 — the
 * Folders tab was folded INTO the main wallpaper page).
 *
 * The user picks any device folder (SAF); its images appear in the
 * library grid under their own filter chip. v0.8.14: tapping one opens a
 * fullscreen preview and "Set as wallpaper" writes it as the plain SYSTEM
 * wallpaper (crop + WallpaperManager.setBitmap) — basic images never go
 * through the Wallpaper Engine renderer ("videos should though", and
 * video imports still do). Images can also be renamed in place via SAF.
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
    // v0.8.14: first-seen registry — "date ADDED TO THE APP" per image.
    private const val KEY_ADDED_PREFIX = "added|"
    // Legacy single-folder key (v0.8.9/v0.8.10) — migrated on first read.
    private const val KEY_LEGACY_FOLDER = "folder_uri"

    /**
     * v0.8.17: the folder's OWN document id, for both storage shapes:
     *  • a tree uri picked via OpenDocumentTree ("/tree/<docId>") — its
     *    tree id IS the root folder;
     *  • a subfolder document uri created by the "+ folder → Create new"
     *    flow ("/tree/<root>/document/<subDocId>") — getDocumentId gives
     *    the SUBFOLDER, so enumeration/save target the subfolder itself.
     */
    private fun folderDocId(folder: Uri): String = try {
        android.provider.DocumentsContract.getDocumentId(folder)
    } catch (t: Throwable) {
        android.provider.DocumentsContract.getTreeDocumentId(folder)
    }

    /** A "document" uri carries its own segment after the tree part. */
    private fun isDocumentUri(folder: Uri): Boolean =
        folder.pathSegments.count() >= 4 && folder.pathSegments[2] == "document"

    // ── Folder registry ──────────────────────────────────────────────

    fun readFolders(context: Context): List<SavedFolder> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = prefs.getString(KEY_LEGACY_FOLDER, null)
        val set = prefs.getStringSet(KEY_FOLDERS, null) ?: mutableSetOf()
        // One-time migration of the v0.8.9 single folder.
        val uris = if (legacy != null && legacy !in set) {
            set.toMutableSet().apply { add(legacy) }.let {
                prefs.edit().putStringSet(KEY_FOLDERS, it).apply()
            }
            prefs.edit().remove(KEY_LEGACY_FOLDER).apply()
            WepWawetLogger.appInfo("ImageFolderAssets: legacy folder migrated")
            set.plus(legacy)
        } else {
            set
        }
        // v0.8.18: the built-in Suite folder is RETIRED ("remove the suite
        // images") — saving now goes through the choose-or-create folder
        // flow. One-time cleanup of the v0.8.17 suite_images directory so
        // its files don't linger as invisible orphans.
        val prefs2 = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs2.getBoolean("suite_cleanup_done", false)) {
            prefs2.edit().putBoolean("suite_cleanup_done", true).apply()
            runCatching {
                File(context.filesDir, "suite_images").deleteRecursively()
            }.onFailure { WepWawetLogger.appWarn("ImageFolderAssets: readFolders — the runCatching block failed", "exception" to it.javaClass.simpleName, "message" to (it.message ?: "")) }
            WepWawetLogger.appInfo("ImageFolderAssets: v0.8.17 suite folder removed")
        }
        return uris.map { SavedFolder(it, folderDisplayName(it)) }
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

    /**
     * "content://…/tree/primary%3APictures%2FWallpapers" → "Pictures/Wallpapers";
     * a created subfolder document uri → just the subfolder's own name;
     * the suite folder → "Suite images".
     */
    fun folderDisplayName(treeUriStr: String): String = try {
            val uri = Uri.parse(treeUriStr)
            val docId = if (isDocumentUri(uri))
                android.provider.DocumentsContract.getDocumentId(uri)
            else
                android.provider.DocumentsContract.getTreeDocumentId(uri)
        docId.substringAfterLast('/').ifBlank {
            docId.substringAfter(':').ifBlank { docId }
        }
    } catch (t: Throwable) {
        "Folder"
    }

    // ── Enumeration + decode ─────────────────────────────────────────

    /** Query a folder for image-kind files, capped at 500. SAF trees and
     *  created subfolders both work (folderDocId handles either shape). */
    fun enumerateFolderImages(context: Context, folderUriStr: String): List<ImageEntry> {
        return try {
            val folder = Uri.parse(folderUriStr)
            // v0.8.17: subfolder document uris enumerate their OWN children
            // (folderDocId falls back to the tree id for picked roots).
            val docId = folderDocId(folder)
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
            val parentDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, folderDocId(tree))
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
                val parentDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    tree, folderDocId(tree))
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
                    }.onFailure { WepWawetLogger.appWarn("ImageFolderAssets: importImagesToFolder — the runCatching block failed", "exception" to it.javaClass.simpleName, "message" to (it.message ?: "")) }
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
        }.onFailure { WepWawetLogger.appWarn("ImageFolderAssets: takePersistableGrants — the runCatching block failed", "exception" to it.javaClass.simpleName, "message" to (it.message ?: "")) }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { WepWawetLogger.appWarn("ImageFolderAssets: takePersistableGrants — the runCatching block failed", "exception" to it.javaClass.simpleName, "message" to (it.message ?: "")) }
    }

    /**
     * v0.8.17: delete a folder image ("there should be a delete option for
     * images when you hold down"). Suite images are plain files; SAF
     * documents go through DocumentsContract.deleteDocument with a
     * resolver.delete fallback (some providers only implement the latter).
     */
    fun deleteImage(context: Context, uriStr: String): Boolean {
        // Plain internal file path (e.g. playlist-random images).
        if (uriStr.startsWith("/")) {
            return try {
                val ok = File(uriStr).delete()
                WepWawetLogger.appInfo("ImageFolderAssets: file image deleted",
                    "file" to uriStr.substringAfterLast('/'), "ok" to ok)
                ok
            } catch (t: Throwable) {
                false
            }
        }
        return try {
            val uri = Uri.parse(uriStr)
            try {
                android.provider.DocumentsContract.deleteDocument(
                    context.contentResolver, uri)
            } catch (t: Throwable) {
                // Fallback for providers without deleteDocument.
                context.contentResolver.delete(uri, null, null) > 0
            }.also { ok ->
                WepWawetLogger.appInfo("ImageFolderAssets: image deleted",
                    "ok" to ok, "uri" to uriStr.takeLast(48))
            }
        } catch (t: Throwable) {
            WepWawetLogger.appWarn("ImageFolderAssets: delete failed",
                "exception" to t.javaClass.name,
                "message" to (t.message ?: ""))
            false
        }
    }

    /**
     * v0.8.17: create a subfolder inside a granted parent tree (the "+ folder
     * → Create new folder" flow) with an optional ".nomedia" marker — the
     * standard mechanism gallery apps and MediaStore use to skip a folder.
     * Returns the new folder's DOCUMENT uri (usable directly as a library
     * folder), or null.
     */
    fun createSubfolder(
        context: Context,
        parentTreeUriStr: String,
        name: String,
        hiddenFromGallery: Boolean,
    ): String? {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return null
        return try {
            val tree = Uri.parse(parentTreeUriStr)
            val parentDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val dirUri = android.provider.DocumentsContract.createDocument(
                context.contentResolver,
                parentDoc,
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                clean) ?: run {
                WepWawetLogger.appWarn("ImageFolderAssets: createSubfolder returned null",
                    "name" to clean)
                return null
            }
            if (hiddenFromGallery) {
                val nomedia = runCatching {
                    android.provider.DocumentsContract.createDocument(
                        context.contentResolver, dirUri, "application/octet-stream", ".nomedia")
                }
                    .onFailure {
                        WepWawetLogger.appWarn("ImageFolderAssets: createNomediaMarker — creating the .nomedia document failed",
                            "exception" to it.javaClass.simpleName,
                            "message" to (it.message ?: ""))
                    }
                .getOrNull()
                if (nomedia == null) {
                    WepWawetLogger.appWarn("ImageFolderAssets: .nomedia not created " +
                        "(folder still created)")
                }
            }
            WepWawetLogger.appInfo("ImageFolderAssets: subfolder created",
                "name" to clean, "hidden" to hiddenFromGallery)
            dirUri.toString()
        } catch (t: Throwable) {
            WepWawetLogger.appWarn("ImageFolderAssets: createSubfolder failed",
                "exception" to t.javaClass.name,
                "message" to (t.message ?: ""))
            null
        }
    }

    // ── First-seen registry ("date added to the app") ─────────────────

    /**
     * v0.8.14: "Newest" on the Wallpapers page sorts by when an item was
     * ADDED TO THE APP, not by the image file's own date ("right now all
     * the folder images are at the top of All"). For a folder image that
     * added-date is the first time our scanner saw its document uri:
     * every entry of [entries] missing from the registry is registered
     * with NOW, and the full uri→addedAt map is returned. Images that
     * were already known keep their original first-seen time, so the
     * one-time registration on update does not reshuffle them later.
     *
     * v0.8.18 (review B19): entries whose uri is NOT in [entries] are
     * pruned in the same pass. The registry used to grow monotonically —
     * deleted/renamed images and removed folders left dead `added|<uri>`
     * entries in the prefs file forever.
     */
    fun registerAddedDates(context: Context, entries: List<ImageEntry>): Map<String, Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val map = HashMap<String, Long>(entries.size)
        var registered = 0
        val editor by lazy { prefs.edit() }
        val liveKeys = entries.mapTo(HashSet(entries.size)) { KEY_ADDED_PREFIX + it.uri }
        for (entry in entries) {
            val key = KEY_ADDED_PREFIX + entry.uri
            val existing = prefs.getLong(key, -1L)
            if (existing > 0L) {
                map[entry.uri] = existing
            } else {
                map[entry.uri] = now
                editor.putLong(key, now)
                registered++
            }
        }
        // v0.8.18 (review B19): prune dead entries — registry keys for
        // images that are no longer in any enumerated folder.
        var pruned = 0
        for (key in prefs.all.keys) {
            if (key.startsWith(KEY_ADDED_PREFIX) && key !in liveKeys) {
                editor.remove(key)
                pruned++
            }
        }
        if (registered > 0 || pruned > 0) {
            editor.apply()
            WepWawetLogger.appInfo("ImageFolderAssets: added-dates registry updated",
                "new" to registered,
                "pruned" to pruned)
        }
        return map
    }

    // ── Pack + import ────────────────────────────────────────────────
    // v0.8.18 (review B26): packAndImportImage + its importKey /
    // rememberedImport / rememberImport helpers were DELETED — dead code
    // since v0.8.14 (folder images take the plain system-wallpaper path;
    // "basic images shouldn't use the wallpaper engine renderer"). Zero
    // callers existed at deletion time (verified by grep). The 90-line
    // pipeline encoded stale assumptions about the .tex packer that no
    // longer hold. KEY_IMPORTS stays declared above: any legacy
    // "imported_images" prefs entries from old installs are simply never
    // read again (harmless residue, like the legacy single-folder key).
}
