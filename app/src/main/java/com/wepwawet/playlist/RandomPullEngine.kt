package com.wepwawet.playlist

import android.content.Context
import android.graphics.Bitmap
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.discover.DiscoverPrefs
import com.wepwawet.discover.DiscoverSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.8.19 (random-pull rework): the DYNAMIC random slot for playlists.
 *
 * "i want the current random system in the playlists to be replaced by
 * instead of getting a random wallpaper once and adding it. it adds a
 * random wallpaper that is randomy every time it is ran. it should also
 * have its own individual tag settings and stuff just like the discover
 * page."
 *
 * The old "Random" button pulled ONE image through Discover's settings and
 * froze it into the entry list. The new model:
 *
 *  • [SuitePlaylistStore.Playlist.randomPullEnabled] turns the playlist's
 *    RANDOM SLOT on. A playlist with the slot enabled pulls a FRESH image
 *    every time the rotation lands on the slot — never the same file twice
 *    in a row (recent-URL dedup), no storage of stale picks.
 *  • The playlist's OWN tag settings ([Playlist.randomStrictTags],
 *    [randomSoftTags], [randomStyleValue], [randomR18Mode],
 *    [randomSources]) drive the search — the same knobs the Discover page
 *    exposes. A null setting inherits Discover's current value, so a bare
 *    slot behaves exactly like the old pull.
 *
 * The engine downloads into filesDir/playlist_random (the same directory
 * the old one-shot pull used) but keeps only the CURRENT file per
 * playlist — each pull deletes the previous one, so nothing accumulates.
 */
object RandomPullEngine {

    private const val TAG = "RandomPullEngine"
    private const val PREFS = "suite_random_pull"
    private const val KEY_CURRENT_PREFIX = "current_"
    private const val KEY_RECENT_PREFIX = "recent_"

    /**
     * The synthetic candidate the service's rotation uses for the random
     * slot (a real file path can never contain this — paths are absolute).
     */
    const val SLOT_MARKER = "<<random-pull-slot>>"

    private fun dir(context: Context): File =
        File(context.filesDir, "playlist_random").apply { mkdirs() }

    /**
     * Pull ONE fresh image for [playlist]'s random slot and return its
     * absolute file path. The previous slot file is deleted once the new
     * one is complete. Returns null on any failure (the caller keeps the
     * previous wallpaper — a failed pull never blanks anything).
     */
    suspend fun pull(
        context: Context,
        playlist: SuitePlaylistStore.Playlist,
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Resolve the effective settings: the playlist's own values,
            // falling back to Discover's current ones (1.0's parity knobs).
            val strict = playlist.randomStrictTags
                ?: DiscoverPrefs.readStrictTags(context)
            val soft = playlist.randomSoftTags
                ?: DiscoverPrefs.readSoftTags(context)
            val style = playlist.randomStyleValue
                ?: DiscoverPrefs.readStyle(context)
            val r18 = playlist.randomR18Mode
                ?: DiscoverPrefs.readR18(context)
            val sources = playlist.randomSources?.toSet()
                ?: DiscoverPrefs.readSources(context)
            val recent = readRecent(context, playlist.id)

            val url = DiscoverSearch.searchOne(
                context = context,
                strictTags = strict,
                softTags = soft,
                styleValue = style,
                r18Mode = r18,
                recentUrls = recent,
                sources = sources,
            )
            val bytes = DiscoverSearch.downloadImage(url)
            DiscoverPrefs.pushRecentUrl(context, url)

            // Write the new file FIRST, then delete the old (a failure
            // mid-way keeps the previous slot working).
            val img = File(dir(context), "slot_${playlist.id}.img")
            img.writeBytes(bytes)
            rememberCurrent(context, playlist.id, img.absolutePath, url)
            WepWawetLogger.appInfo("$TAG: pulled a fresh random-slot image",
                "playlist" to playlist.name,
                "bytes" to bytes.size,
                "url" to url.take(100))
            img.absolutePath
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WepWawetLogger.appWarn("$TAG: random-slot pull failed",
                "playlist" to playlist.name,
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            null
        }
    }

    /**
     * The CURRENT slot image for a playlist (already on disk), if any —
     * used when the rotation wants the slot but a fresh pull is already
     * in flight (single-flight guard in the service).
     */
    fun currentFile(context: Context, playlistId: String): File? {
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_PREFIX + playlistId, null) ?: return null
        val f = File(path)
        return if (f.isFile) f else null
    }

    /** Decode a file into a bitmap (system-wallpaper set path). */
    fun decode(file: File, maxDim: Int): Bitmap? =
        com.wepwawet.discover.DiscoverApplier.decode(file.readBytes(), maxDim)

    /** Is [path] inside the random-slot directory? (history pinning etc.) */
    fun isSlotFile(path: String): Boolean = path.contains("/playlist_random/")

    private fun readRecent(context: Context, playlistId: String): Set<String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The playlist's own last URL + Discover's global recents (a
        // different playlist just set the same image — don't repeat it).
        val own = p.getString(KEY_RECENT_PREFIX + playlistId, null)
        val global = DiscoverPrefs.readRecentUrls(context)
        return if (own != null) global + own else global
    }

    private fun rememberCurrent(context: Context, playlistId: String, path: String, url: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = p.getString(KEY_CURRENT_PREFIX + playlistId, null)
        p.edit()
            .putString(KEY_CURRENT_PREFIX + playlistId, path)
            .putString(KEY_RECENT_PREFIX + playlistId, url)
            .apply()
        // Delete the PREVIOUS slot file (keep only the current one).
        if (previous != null && previous != path) {
            runCatching { File(previous).delete() }
        }
    }
}
