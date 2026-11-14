package com.wepwawet.playlist

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.screens.LibraryPrefs

/**
 * v0.9.0: GROUP BLOCKS — a playlist entry that is an entire GROUP, not one
 * wallpaper.
 *
 * "I want to add an entire group to a playlist as a single block. The block
 * stays linked to the group, so wallpapers added to or removed from the
 * group later appear or disappear in the playlist automatically. Inside the
 * block I can choose how it is ordered: manual order that I can drag, newest
 * first, oldest first, A to Z, or random."
 *
 * The model: the entry's [filepath] is a unique MARKER
 * ([MARKER_PREFIX] + group name — real file paths can never look like this),
 * with the group name + order mode + manual member order stored on the
 * [SuitePlaylistStore.Entry] itself, so a block moves, is removed, and is
 * time-ruled exactly like any other entry. At rotation time the marker
 * RESOLVES to the group's CURRENT members (engine wallpapers assigned to
 * the group + the linked folder's live scan + manually added folder images
 * — the same contents the library grid shows for that group), ordered per
 * the block's mode. A rotation that lands on the block picks from inside it
 * using that order.
 *
 * Order modes:
 *  • manual — the stored member order (dragged in the block editor); new
 *    members append alphabetically, removed members drop out.
 *  • newest / oldest — by "added to the app" (download time, first-seen).
 *  • az — by title.
 *  • random — shuffled at resolve time.
 */
object GroupBlocks {

    const val MARKER_PREFIX = "group://block/"

    /** Block order modes (stored on the entry). */
    const val ORDER_MANUAL = "manual"
    const val ORDER_NEWEST = "newest"
    const val ORDER_OLDEST = "oldest"
    const val ORDER_AZ = "az"
    const val ORDER_RANDOM = "random"

    val ORDER_MODES = listOf(ORDER_MANUAL, ORDER_NEWEST, ORDER_OLDEST, ORDER_AZ, ORDER_RANDOM)

    fun isGroupBlock(path: String): Boolean = path.startsWith(MARKER_PREFIX)

    /** The group a block marker references (before the unique suffix). */
    fun groupName(path: String): String = path.removePrefix(MARKER_PREFIX).substringBefore('#')

    /**
     * A fresh UNIQUE marker for a group-block entry of [group] — the same
     * group can appear as several blocks, and entries are keyed by path.
     */
    fun marker(group: String): String =
        MARKER_PREFIX + group + "#" + java.util.UUID.randomUUID().toString().take(8)

    fun orderLabel(mode: String): String = when (mode) {
        ORDER_MANUAL -> "Manual (drag)"
        ORDER_NEWEST -> "Newest first"
        ORDER_OLDEST -> "Oldest first"
        ORDER_AZ -> "A to Z"
        ORDER_RANDOM -> "Random"
        else -> mode
    }

    /**
     * A brand-new group-block entry for [group]. [manual] is the initial
     * manual member order (may be empty — resolve reconciles it).
     */
    fun newBlockEntry(
        group: String,
        order: String = ORDER_MANUAL,
        manual: List<String> = emptyList(),
    ): SuitePlaylistStore.Entry = SuitePlaylistStore.Entry(
        filepath = marker(group),
        title = "Group: $group",
        type = "Group",
        groupRef = group,
        blockOrder = order,
        blockManualMembers = manual,
    )

    // ── Member resolution ──────────────────────────────────────────────

    /** A resolve result: the ordered member paths + their display titles. */
    data class ResolvedBlock(val group: String, val members: List<Pair<String, String>>) {
        val paths: List<String> get() = members.map { it.first }
    }

    /**
     * Resolve the block to its CURRENT members in the block's order.
     *
     * Members are the group's live contents — exactly what the library
     * grid shows for the group: engine wallpapers assigned to it, folder
     * images manually added to it, and (when the group links a device
     * folder) the folder's live scan. Folder-image members resolve through
     * their CROP when one exists (the playlist rotates what the user sees).
     *
     * This runs on the service's rotation path; a 60s in-memory cache keeps
     * repeated calls (shouldUpdate + pick) to one enumeration per wake.
     */
    fun resolve(context: Context, entry: SuitePlaylistStore.Entry): ResolvedBlock {
        val group = entry.groupRef
            ?: return ResolvedBlock("", emptyList())
        val cacheKey = "${group}|${entry.filepath}|${entry.blockOrder}"
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            cache[cacheKey]?.let { (at, block) ->
                if (now - at < CACHE_MS) return block
            }
        }
        // The member enumeration is a suspend (IO) call; resolve() itself
        // is synchronous (service-side callers). runBlocking on the DEDICATED
        // IO dispatcher is safe here — callers are background threads or
        // brief, minutes-apart service wakes, and the 60s cache makes
        // repeats free.
        val block = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            resolveUncached(context, entry, group)
        }
        synchronized(cacheLock) {
            cache[cacheKey] = now to block
        }
        return block
    }

    private val cacheLock = Any()
    private val cache = HashMap<String, Pair<Long, ResolvedBlock>>()
    private const val CACHE_MS = 60_000L

    private suspend fun resolveUncached(
        context: Context,
        entry: SuitePlaylistStore.Entry,
        group: String,
    ): ResolvedBlock {
        val groupOf = LibraryPrefs.readGroupOf(context)
        val cropMap = com.wepwawet.ui.screens.ImageCropStore.read(context)

        // 1. Engine wallpapers assigned to the group.
        val wallpapers = com.wepwawet.engine.EngineLibrary
            .enumerateWallpapers(context)
            .filter { groupOf[it.filePath] == group }

        // 2. Folder images manually added to the group.
        val manualUris = groupOf.entries.filter { it.value == group }
            .map { it.key }
            .filter { it.startsWith("content://") }

        // 3. The linked folder's live scan.
        val linked = LibraryPrefs.groupFolderOf(context, group)
        val scanned = if (linked != null) {
            com.wepwawet.ui.screens.ImageFolderAssets
                .enumerateFolderImages(context, linked)
        } else {
            emptyList()
        }

        // Fold into member (path, title) pairs, de-duplicated by path.
        // Folder images resolve through their CROP; the scan's manual
        // duplicates (a scanned image that is also manually added) collapse.
        val members = LinkedHashMap<String, String>()
        for (w in wallpapers) members[w.filePath] = w.title
        val seenUris = HashSet<String>()
        for (image in scanned) {
            if (image.uri in seenUris) continue
            seenUris.add(image.uri)
            members[cropMap[image.uri]?.cropFilePath ?: image.uri] =
                image.displayName
        }
        for (uri in manualUris) {
            if (uri in seenUris) continue
            seenUris.add(uri)
            members[cropMap[uri]?.cropFilePath ?: uri] =
                uri.substringAfterLast('/').substringBeforeLast('.')
        }

        var ordered = orderMembers(context, entry, members)
        WepWawetLogger.engineDebug("GroupBlocks: block resolved",
            "group" to group,
            "order" to (entry.blockOrder ?: ORDER_MANUAL),
            "members" to ordered.size)
        return ResolvedBlock(group, ordered)
    }

    /** Apply the block's order mode to the resolved member set. */
    private fun orderMembers(
        context: Context,
        entry: SuitePlaylistStore.Entry,
        members: LinkedHashMap<String, String>,
    ): List<Pair<String, String>> {
        val list = members.entries.map { it.key to it.value }
        return when (entry.blockOrder ?: ORDER_MANUAL) {
            ORDER_MANUAL -> {
                // The stored manual order first (unknowns dropped), then
                // any members added since, appended A→Z so a live-synced
                // addition is deterministic.
                val manual = entry.blockManualMembers ?: emptyList()
                val known = HashSet(list.map { it.first })
                val byPath = list.associateBy { it.first }
                val head = manual.filter { it in known }.mapNotNull { byPath[it] }
                val rest = list.filter { it.first !in manual }
                    .sortedBy { it.second.lowercase() }
                head + rest
            }
            ORDER_AZ -> list.sortedBy { it.second.lowercase() }
            ORDER_RANDOM -> list.shuffled()
            else -> {
                // newest / oldest — by "added to the app": the download
                // time for workshop wallpapers, the first-seen time for
                // folder images, the file date as the tiebreak.
                val downloads = com.wepwawet.data.DownloadManager
                    .completedDownloads.value.associate { it.itemId to it.completedAt }
                val folders = com.wepwawet.ui.screens.ImageFolderAssets.readFolders(context)
                val added = HashMap<String, Long>()
                for (folder in folders) {
                    val imgs = com.wepwawet.ui.screens.ImageFolderAssets
                        .enumerateFolderImages(context, folder.treeUri)
                    for (img in imgs) added[img.uri] = img.lastModified
                }
                fun addedAt(path: String, title: String): Long =
                    downloads[com.wepwawet.pairing.WorkshopDownloadJob.workshopItemIdOf(path)]
                        ?: added[path]
                        ?: 0L
                val dated = list.map { it to addedAt(it.first, it.second) }
                if (entry.blockOrder == ORDER_OLDEST) {
                    dated.sortedBy { it.second }.map { it.first }
                } else {
                    dated.sortedByDescending { it.second }.map { it.first }
                }
            }
        }
    }

    /**
     * The editor reconciles a block's stored manual order with its current
     * members (persisted when the user reorders; a resolve never writes).
     */
    fun reconcileManual(
        context: Context,
        entry: SuitePlaylistStore.Entry,
    ): List<String> {
        val members = resolve(context, entry).paths
        val manual = entry.blockManualMembers ?: emptyList()
        val known = HashSet(members)
        val head = manual.filter { it in known }
        val rest = members.filter { it !in manual }
        return head + rest
    }

    /** Invalidate the resolve cache (group contents or links changed). */
    fun invalidate() {
        synchronized(cacheLock) { cache.clear() }
    }
}
