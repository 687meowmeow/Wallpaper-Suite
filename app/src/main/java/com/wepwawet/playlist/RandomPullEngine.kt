package com.wepwawet.playlist

import android.content.Context
import android.graphics.Bitmap
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.discover.DiscoverPrefs
import com.wepwawet.discover.DiscoverSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * v0.8.19 (random-pull rework) → v0.8.21 (slots as entries): the DYNAMIC
 * random slot for playlists.
 *
 * "i want the current random system in the playlists to be replaced by
 * instead of getting a random wallpaper once and adding it. it adds a
 * random wallpaper that is randomy every time it is ran. it should also
 * have its own individual tag settings and stuff just like the discover
 * page." … "you should be able to add more than one random" … "you should
 * be able to move the random slots position as if it was a wallpaper …
 * it should open a separate menu when editing the random."
 *
 * The v0.8.21 model: a random slot IS an playlist [SuitePlaylistStore.Entry]
 * whose [filepath] is a unique marker ([SLOT_PREFIX] + UUID). Each time the
 * rotation lands on it, a FRESH image is pulled through that slot's OWN
 * settings (null fields inherit Discover's current value). Slots move
 * up/down, get removed, open their own editor, and can be time-ruled
 * exactly like image entries.
 *
 * The engine downloads into filesDir/playlist_random (per-slot files) and
 * keeps only each slot's CURRENT file — each pull deletes that slot's
 * previous one, so nothing accumulates.
 */
object RandomPullEngine {

    private const val TAG = "RandomPullEngine"
    private const val PREFS = "suite_random_pull"
    private const val KEY_CURRENT_PREFIX = "current_"
    private const val KEY_RECENT_PREFIX = "recent_"

    /** Random-slot entry markers: [SLOT_PREFIX] + a unique id. */
    const val SLOT_PREFIX = "random://slot/"

    /** The v0.8.19/20 synthetic single-slot marker (migrated on sight). */
    const val LEGACY_SLOT_MARKER = "<<random-pull-slot>>"

    /** Is this entry filepath a random-slot marker? */
    fun isSlot(path: String): Boolean =
        path.startsWith(SLOT_PREFIX) || path == LEGACY_SLOT_MARKER

    /** A fresh unique slot marker (a real file path can never look like this). */
    fun newSlotMarker(): String = SLOT_PREFIX + UUID.randomUUID().toString()

    /** A brand-new random-slot entry (null settings inherit Discover's). */
    fun newSlotEntry(
        strict: List<String>? = null,
        soft: List<String>? = null,
        style: Int? = null,
        r18: Int? = null,
        sources: List<String>? = null,
    ): SuitePlaylistStore.Entry = SuitePlaylistStore.Entry(
        filepath = newSlotMarker(),
        title = "Random",
        type = "Random",
        randomStrictTags = strict,
        randomSoftTags = soft,
        randomStyleValue = style,
        randomR18Mode = r18,
        randomSources = sources,
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "playlist_random").apply { mkdirs() }

    // A stable per-slot file name: markers contain "://", so hash them.
    private fun slotFile(context: Context, playlistId: String, slotMarker: String): File =
        // toHexString of an Int is always 1–8 hex chars (no separators).
        File(dir(context), "slot_${playlistId}_${Integer.toHexString(slotMarker.hashCode())}.img")

    /**
     * Pull ONE fresh image for [slot] (an entry of [playlist]) and return
     * its absolute file path. The slot's previous file is deleted once the
     * new one is complete. Returns null on any failure (the caller keeps
     * the previous wallpaper — a failed pull never blanks anything).
     */
    suspend fun pull(
        context: Context,
        playlist: SuitePlaylistStore.Playlist,
        slot: SuitePlaylistStore.Entry,
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Resolve the effective settings: the SLOT's own values,
            // falling back to Discover's current ones (1.0's parity knobs).
            val strict = slot.randomStrictTags
                ?: DiscoverPrefs.readStrictTags(context)
            val soft = slot.randomSoftTags
                ?: DiscoverPrefs.readSoftTags(context)
            val style = slot.randomStyleValue
                ?: DiscoverPrefs.readStyle(context)
            val r18 = slot.randomR18Mode
                ?: DiscoverPrefs.readR18(context)
            val sources = slot.randomSources?.toSet()
                ?: DiscoverPrefs.readSources(context)
            val recent = readRecent(context, slot.filepath)

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
            // mid-way keeps the previous slot file working).
            val img = slotFile(context, playlist.id, slot.filepath)
            img.writeBytes(bytes)
            rememberCurrent(context, playlist.id, slot.filepath, img.absolutePath, url)
            WepWawetLogger.appInfo("$TAG: pulled a fresh random-slot image",
                "playlist" to playlist.name,
                "slot" to slot.filepath.takeLast(18),
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

    private fun readRecent(context: Context, slotMarker: String): Set<String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The slot's own last URL + Discover's global recents (another slot
        // or a Discover apply just used the same image — don't repeat it).
        val own = p.getString(KEY_RECENT_PREFIX + slotMarker, null)
        val global = DiscoverPrefs.readRecentUrls(context)
        return if (own != null) global + own else global
    }

    private fun rememberCurrent(
        context: Context,
        playlistId: String,
        slotMarker: String,
        path: String,
        url: String,
    ) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = KEY_CURRENT_PREFIX + playlistId
        val previous = p.getString(key, null)
        p.edit()
            .putString(key, path)
            .putString(KEY_RECENT_PREFIX + slotMarker, url)
            .apply()
        // Delete the PREVIOUS slot file (keep only the current one).
        if (previous != null && previous != path) {
            runCatching { File(previous).delete() }.onFailure { WepWawetLogger.appWarn("RandomPullEngine: File(previous) — best-effort call failed", "exception" to it.javaClass.simpleName) }
        }
    }
}
