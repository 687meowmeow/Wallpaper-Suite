@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.wepwawet.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wepwawet.log.WepWawetLogger
import io.wallpaperengine.weclient.WeEnginePrefs
import io.wallpaperengine.weutil.WallpaperInfoSparse
import io.wallpaperengine.wrapper.SceneLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-level library operations over the integrated Wallpaper Engine
 * (thread-safe wrappers around [SceneLib] for the Compose UI).
 *
 * All engine calls are marshalled to [Dispatchers.IO] — the native library
 * is not thread-safe against concurrent init and enumeration.
 */
object EngineLibrary {

    private const val TAG = "EngineLibrary"

    /** Single shared SceneLib instance (initLibrary is idempotent per process). */
    @Volatile
    private var lib: SceneLib? = null

    fun sceneLib(context: Context): SceneLib {
        val existing = lib
        if (existing != null) return existing
        synchronized(this) {
            val current = lib
            if (current != null) return current
            val created = SceneLib()
            created.initLibrary(context.applicationContext)
            lib = created
            return created
        }
    }

    /**
     * v0.8.8 (engine-log fix): make sure `filesDir/logs/` exists BEFORE the
     * native engine's file logging is enabled.
     *
     * The engine (libscenejni.so) writes its own diagnostics — shader
     * compile errors, texture load failures, scene-init messages — to
     * `<internalStoragePath>/logs/applog.txt` when
     * `SceneLib.setLogToFileEnabled(true)` is called (the path root is the
     * `filesDir` we hand the engine in `initLibrary`). The engine does NOT
     * create the parent directory, and NOTHING ELSE in this app creates
     * `filesDir/logs/` (LogPersistenceWorker uses
     * `filesDir/wepwawet/logs/`, CrashHandler uses
     * `filesDir/wepwawet/crashes/`). The official client's Java-side
     * `Util.log` creates the directory with mkdirs() when it opens the
     * file — the v0.8.5 removal of our WeClientLog port (which did the
     * same) deleted the only thing that ever created it.
     *
     * Result: ever since v0.8.6 turned the native engine's file logging
     * on, every engine write to applog.txt silently failed (fopen can't
     * create the missing directory), so EngineLogTailer had nothing to
     * tail — zero engine-side diagnostics in the logs and crash reports
     * (exactly what the v0.8.7/v0.8.8 xray-freeze reports show).
     *
     * Called from every setLogToFileEnabled(true) site. Idempotent.
     */
    fun ensureEngineLogDir(context: Context) {
        runCatching {
            val dir = java.io.File(context.applicationContext.filesDir, "logs")
            if (!dir.isDirectory) {
                val created = dir.mkdirs()
                com.wepwawet.log.WepWawetLogger.appInfo(
                    "EngineLibrary: engine log dir created",
                    "path" to dir.absolutePath,
                    "created" to created,
                )
            }
        }
    }

    // =====================================================================
    // Enumeration
    // =====================================================================

    /**
     * Enumerates every wallpaper the engine knows about:
     *  - the bundled official wallpapers (`wallpapers/` under filesDir);
     *  - imported mpkgs (`downloads/` under filesDir);
     *  - SAF-registered local image/video/GIF wallpapers.
     *
     * Order: newest first (same default as the official client's grid).
     */
    suspend fun enumerateWallpapers(context: Context): List<WallpaperInfoSparse> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val result = ArrayList<WallpaperInfoSparse>()
            var officials = 0
            var downloads = 0
            try {
                val engine = sceneLib(context)
                val blacklisted = WeEnginePrefs.readOfficialBlacklist(context)
                for (wallpaper in engine.enumerateWallpapers()) {
                    if (blacklisted.contains(wallpaper.filePath)) continue
                    if (wallpaper.isError) {
                        if (wallpaper.isOfficial) {
                            // official wallpapers that failed to extract are hidden entirely
                            continue
                        }
                        WepWawetLogger.engineWarn("Library: corrupt wallpaper entry",
                            "file" to wallpaper.filePath)
                        wallpaper.title = "(corrupt)"
                    }
                    // v0.8.2: the native enumerateWallpapers() never populates
                    // the `type` field (verified against libscenejni.so — it
                    // only sets title/filePath/flags; the official client also
                    // calls getWallpaperType() separately). Fill it in here so
                    // the library grid and preview show the real type
                    // (Scene/Video) instead of a blank subtitle.
                    if (wallpaper.type.isEmpty() && !wallpaper.isLocal) {
                        wallpaper.type = try {
                            engine.getWallpaperType(wallpaper.filePath) ?: ""
                        } catch (t: Throwable) {
                            ""
                        }
                    }
                    if (wallpaper.isOfficial) officials++ else downloads++
                    result.add(wallpaper)
                }
            } catch (t: Throwable) {
                WepWawetLogger.engineError("Library: enumerateWallpapers failed",
                    exception = t)
            }
            // local SAF wallpapers
            for (local in WeEnginePrefs.readLocalWallpapers(context)) {
                try {
                    val displayName = queryDisplayName(context, local.uri) ?: continue
                    val info = WallpaperInfoSparse()
                    info.isLocal = true
                    info.title = displayName.substringBeforeLast('.')
                    info.filePath = local.uri
                    info.lastModifiedDate = local.lastModifiedDate
                    info.type = "Video"
                    result.add(info)
                } catch (t: Throwable) {
                    WepWawetLogger.engineWarn("Library: local wallpaper unavailable",
                        "exception" to (t.message ?: t.javaClass.name))
                }
            }
            WepWawetLogger.engine("Library: enumerated wallpapers",
                "officials" to officials,
                "downloads" to downloads,
                "locals" to (result.size - officials - downloads),
                "total" to result.size,
                "ms" to (System.currentTimeMillis() - startedAt))
            result.sortedByDescending { it.lastModifiedDate }
        }

    private fun queryDisplayName(context: Context, uriString: String): String? {
        return try {
            context.contentResolver.query(Uri.parse(uriString), null, null, null, null)?.use { cursor ->
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

    // =====================================================================
    // Import (mpkg from streams/intents + local image/video URIs)
    // =====================================================================

    sealed class ImportResult {
        data class Success(val wallpaper: WallpaperInfoSparse) : ImportResult()
        data class Failed(val name: String, val reason: String) : ImportResult()
    }

    /**
     * Imports .mpkg / .bin content (the application-MIME branch of the official
     * client's import): copies the stream into `filesDir/downloads/<name>`,
     * validates the engine version and parses its metadata.
     */
    suspend fun importMpkgStream(context: Context, uri: Uri, displayName: String): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val engine = sceneLib(context)
                val relativePath = WeEnginePrefs.DOWNLOAD_FOLDER + displayName
                val file = File(context.filesDir, relativePath)
                WeEnginePrefs.ensureDirectoriesExist(file)
                WepWawetLogger.engine("Library: importing package",
                    "name" to displayName,
                    "target" to relativePath)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext ImportResult.Failed(displayName, "Could not open $displayName")
                if (!engine.isWallpaperVersionValid(relativePath)) {
                    WepWawetLogger.engineWarn("Library: import rejected — engine version mismatch",
                        "name" to displayName)
                    file.delete()
                    return@withContext ImportResult.Failed(displayName, "unsupported version")
                }
                val info = engine.getWallpaperInfoSparse(relativePath)
                    ?: return@withContext ImportResult.Failed(displayName, "not a wallpaper package")
                info.filePath = relativePath
                WepWawetLogger.engine("Library: import OK",
                    "title" to info.title,
                    "type" to info.type,
                    "size" to file.length())
                ImportResult.Success(info)
            } catch (t: Throwable) {
                WepWawetLogger.engineError("Library: importMpkgStream failed", exception = t)
                ImportResult.Failed(displayName, t.message ?: "import error")
            }
        }

    /**
     * Imports a local image/video/GIF the way the official client does: keep a
     * persistent read grant and remember the URI (no copying).
     */
    suspend fun importLocalMedia(context: Context, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                if (context.contentResolver.getType(uri)?.startsWith("image/") == true ||
                    context.contentResolver.getType(uri)?.startsWith("video/") == true
                ) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (e: SecurityException) {
                    }
                }
                val displayName = queryDisplayName(context, uri.toString())
                    ?: uri.lastPathSegment ?: "wallpaper"
                val locals = WeEnginePrefs.readLocalWallpapers(context)
                val entry = WeEnginePrefs.LocalWallpaperEntry()
                entry.uri = uri.toString()
                entry.lastModifiedDate = System.currentTimeMillis()
                locals.removeAll { it.uri == entry.uri }
                locals.add(entry)
                WeEnginePrefs.storeLocalWallpapers(context, locals)

                val info = WallpaperInfoSparse()
                info.isLocal = true
                info.title = displayName.substringBeforeLast('.')
                info.filePath = uri.toString()
                info.lastModifiedDate = entry.lastModifiedDate
                info.type = "Video"
                ImportResult.Success(info)
            } catch (t: Throwable) {
                WepWawetLogger.engineError("Library: importLocalMedia failed", exception = t)
                ImportResult.Failed(name = "", reason = t.message ?: "import error")
            }
        }

    // =====================================================================
    // Delete
    // =====================================================================

    /**
     * Deletes an imported wallpaper (file on disk or SAF registration).
     *
     * v0.9.0: a removal now takes EVERY trace of the item with it —
     * previously only the filesDir .mpkg was deleted, while
     *   • the cached scene .pkg under cache/pkg_cache/<id>.pkg (up to
     *     80 MB each, kept when "Cache .pkg files" is enabled), and
     *     • stale cacheDir temp files (<id>_<sendId>_debug.pkg/.mpkg,
     *     <id>_trimmed.mp4) from interrupted pack/trim runs
     * survived on disk. The user saw storage fill up (and re-downloading
     * the same item instantly "resurrected" from the .pkg cache) even
     * though the wallpaper was gone from the library.
     */
    suspend fun deleteWallpaper(context: Context, info: WallpaperInfoSparse): Boolean =
        withContext(Dispatchers.IO) {
            try {
                var deletedMain = false
                if (info.isLocal) {
                    val locals = WeEnginePrefs.readLocalWallpapers(context)
                    locals.removeAll { it.uri == info.filePath }
                    WeEnginePrefs.storeLocalWallpapers(context, locals)
                    deletedMain = true
                } else {
                    val file = File(context.filesDir, info.filePath)
                    if (file.exists()) deletedMain = file.delete()
                    if (WeEnginePrefs.getStoredString(context, WeEnginePrefs.PREF_KEY_SELECTED_WALLPAPER) ==
                        info.filePath
                    ) {
                        // fall back like the official client: clear the selection so the
                        // playlist/default logic re-picks on next reload
                        WeEnginePrefs.storeString(
                            context, WeEnginePrefs.PREF_KEY_SELECTED_WALLPAPER, ""
                        )
                    }
                }

                // === v0.9.0: sweep every on-disk trace of the same item. ===
                // Workshop imports are named "<itemId> - title.mpkg" — parse
                // the leading id the same way the "Open workshop page"
                // context action does.
                val itemId = Regex("downloads/(\\d{6,}) - ").find(info.filePath)
                    ?.groupValues?.get(1)
                if (itemId != null) {
                    // 1. The cached scene .pkg (set by "Cache .pkg files").
                    val cachedPkg = File(File(context.cacheDir, "pkg_cache"), "$itemId.pkg")
                    if (cachedPkg.exists()) {
                        val ok = runCatching { cachedPkg.delete() }.getOrDefault(false)
                        WepWawetLogger.engine("Library: deleted cached .pkg",
                            "file" to cachedPkg.name,
                            "ok" to ok)
                    }
                    // 2. Leftover cache temp files from pack/trim runs:
                    //    <id>_<sendId>_debug.pkg / _debug.mpkg / _send.mpkg /
                    //    <id>_trimmed.mp4, plus any interrupted install dir.
                    runCatching {
                        context.cacheDir.listFiles()?.forEach { f ->
                            if (f.isFile && f.name.startsWith("$itemId")) {
                                f.delete()
                            }
                        }
                    }
                    val installDir = File(context.cacheDir, "workshop_downloads/$itemId")
                    if (installDir.exists()) {
                        runCatching { installDir.deleteRecursively() }
                    }
                }

                // remove from playlist
                val playlist = WeEnginePrefs.readPlaylist(context)
                val changed = playlist.wallpapers.removeAll { it.filepath == info.filePath }
                if (changed) {
                    WeEnginePrefs.storePlaylist(context, playlist)
                }
                WepWawetLogger.engine("Library: deleteWallpaper complete",
                    "file" to info.filePath,
                    "main_deleted" to deletedMain,
                    "item_id" to (itemId ?: "n/a"))
                true
            } catch (t: Throwable) {
                WepWawetLogger.engineError("Library: deleteWallpaper failed", exception = t)
                false
            }
        }

    /** Clears the extracted official wallpapers so the engine re-extracts them. */
    suspend fun restoreOfficialWallpapers(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val wallpapersDir = File(context.filesDir, "wallpapers")
                if (wallpapersDir.exists()) {
                    wallpapersDir.listFiles()?.forEach { it.delete() }
                }
                // The engine re-extracts the assets on the next init when the
                // directory is missing (assetTime-based invalidation).
                true
            } catch (t: Throwable) {
                false
            }
        }

    // =====================================================================
    // Selection
    // =====================================================================

    fun getSelectedWallpaper(context: Context): String? =
        WeEnginePrefs.getStoredString(context, WeEnginePrefs.PREF_KEY_SELECTED_WALLPAPER)

    fun isWallpaperActive(context: Context, info: WallpaperInfoSparse): Boolean =
        getSelectedWallpaper(context) == info.filePath
}
