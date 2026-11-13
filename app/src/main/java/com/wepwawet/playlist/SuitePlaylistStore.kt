package com.wepwawet.playlist

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wepwawet.log.WepWawetLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * WallpaperSuite playlists — the multi-playlist store.
 *
 * The official Wallpaper Engine client keeps exactly ONE playlist
 * (see [io.wallpaperengine.wedata.PlaylistData]); our users asked for
 * many playlists, each:
 *   • holding any mix of animated (Scene/Video) and static wallpapers —
 *     an entry is just an engine-relative file path, the type tag is
 *     informational;
 *   • rotating in RANDOM or ORDERED (preset) order;
 *   • on a change interval (minutes);
 *   • with optional TIME RULES — "from 22:00 to 06:00 show this wallpaper
 *     (or rotate within this group)". A rule whose window matches the
 *     current clock restricts the rotation to that rule's entries; a
 *     single-entry rule simply pins one wallpaper for its window.
 *
 * Persistence: one JSON file (filesDir/suite_playlists.json, Gson) plus
 * the active playlist id in the same file. All writes are synchronous +
 * small — playlists are tiny compared to wallpapers.
 *
 * The live wallpaper service ([io.wallpaperengine.weclient.WEWallpaperService])
 * reads the ACTIVE playlist on every settings sync and drives the
 * rotation from its existing 1-second playlist tick; when no suite
 * playlist is active the official single-playlist machinery keeps
 * working unchanged.
 */
object SuitePlaylistStore {

    /** A wallpaper inside a playlist. [type] is "Scene"/"Video"/"" (informational). */
    // v0.8.16: `var` — renameFilepathEverywhere re-points entries at a
    // folder image's new crop file, and Gson rewrites fields by reflection
    // (same rationale as the Playlist fields above).
    data class Entry(
        var filepath: String,
        var title: String,
        var type: String,
    )

    /**
     * A time window + the subset of the playlist's entries that owns it.
     * `startMinute`/`endMinute` are minutes-of-day; a window may wrap
     * past midnight (e.g. 22:00→06:00). endMinute == startMinute is
     * treated as a full-day window.
     */
    data class TimeRule(
        val startMinute: Int,
        val endMinute: Int,
        val entryFilePaths: MutableList<String>,
    )

    data class Playlist(
        // v0.8.13: `var`, NOT `val` — Gson deserializes by writing fields
        // via reflection, and a FINAL field is the one construct that can
        // fail silently on some runtimes (and always fails for records).
        // A final `id` that could not be re-written would keep the RANDOM
        // UUID from the no-arg constructor Kotlin synthesizes when every
        // parameter has a default — the stored JSON id and the object's id
        // would diverge, and read(created.id) would return null: exactly
        // the reported "this playlist no longer exists" right after
        // creating one. `var` removes that entire failure class.
        var id: String = UUID.randomUUID().toString(),
        var name: String = "Playlist",
        /** "random" | "ordered". */
        var orderMode: String = ORDER_RANDOM,
        /** Rotation interval in minutes (random/ordered modes). */
        var intervalMinutes: Int = 15,
        var entries: MutableList<Entry> = ArrayList(),
        var timeRules: MutableList<TimeRule> = ArrayList(),
        // ── v0.8.19 (random-pull rework): the "Random" pull button no
        // longer freezes ONE fetched image into the list. Instead, a
        // dynamic RANDOM SLOT lives in the playlist: each time the
        // rotation lands on it, a FRESH image is pulled through the SAME
        // booru search Discover uses — with THIS playlist's OWN tag
        // settings (strict/soft tags, style, content tier, sources), so
        // two playlists can pull from completely different feeds.
        // v0.8.20: MULTIPLE slots ("you should be able to add more than
        // one random") — randomSlotCount is how many random slots the
        // rotation has (0 = off). Each slot pulls its own fresh image
        // (a slot in flight is marked used so two slots never repeat the
        // same URL in one rotation). Defaults: null = inherit Discover's
        // current settings.
        var randomSlotCount: Int = 0,
        var randomStrictTags: List<String>? = null,
        var randomSoftTags: List<String>? = null,
        var randomStyleValue: Int? = null,
        var randomR18Mode: Int? = null,
        var randomSources: List<String>? = null,
        /** v0.8.19 legacy field (v0.8.20: migrated into randomSlotCount). */
        var randomPullEnabled: Boolean = false,
    )

    const val ORDER_RANDOM = "random"
    const val ORDER_ORDERED = "ordered"

    private data class StoreFile(
        var playlists: MutableList<Playlist> = ArrayList(),
        var activePlaylistId: String = "",
    )

    /** Gson parse target with nullable fields — Gson bypasses constructors,
     * so a partial JSON leaves nulls; normalized into [StoreFile]. */
    private data class StoreFileRaw(
        var playlists: MutableList<Playlist>? = null,
        var activePlaylistId: String? = null,
    )

    private val gson = Gson()
    private val storeRawType = object : TypeToken<StoreFileRaw>() {}.type

    private fun file(context: Context): File =
        File(context.filesDir, "suite_playlists.json")

    @Synchronized
    private fun readStore(context: Context): StoreFile {
        return try {
            val f = file(context)
            if (!f.isFile) return StoreFile()
            val text = f.readText()
            val raw = gson.fromJson<StoreFileRaw>(text, storeRawType)
            // Local val: `playlists` is a var on StoreFileRaw, so a null
            // check on the property itself can't smart-cast.
            val rawLists = raw?.playlists
            if (rawLists != null) {
                // Named-field JSON (every build with the v0.8.14 keep rule).
                // Sanitize entry-by-entry: a hand-edited or half-corrupt file
                // can leave Gson nulls inside a non-null Kotlin type, and a
                // null name/id would crash the UI further up.
                val clean = rawLists.mapNotNull { it.sanitized() }.toMutableList()
                StoreFile(playlists = clean, activePlaylistId = raw?.activePlaylistId ?: "")
            } else {
                // v0.8.11–v0.8.13 release builds: R8 renamed every field of
                // every Gson-serialized class here (no keep rule covered this
                // package), so Gson sees NO fields and the parse comes back
                // empty even though the file holds the user's playlists.
                // Those builds wrote obfuscated keys in declaration order
                // (Playlist a=id b=name c=orderMode d=intervalMinutes
                // e=entries f=timeRules; StoreFile a=playlists
                // b=activePlaylistId) — recover them instead of silently
                // resetting the store.
                migrateR8Obfuscated(text) ?: StoreFile()
            }
        } catch (e: Exception) {
            WepWawetLogger.appWarn("SuitePlaylistStore: read failed — resetting",
                "exception" to e.javaClass.name)
            StoreFile()
        }
    }

    /** Drops playlists with a null/blank id or name; fills safe defaults. */
    private fun Playlist.sanitized(): Playlist? {
        if (id.isNullOrBlank() || name == null) return null
        // v0.8.20 migration: the v0.8.19 boolean randomPullEnabled folds
        // into randomSlotCount (0/1); an explicit count wins when present.
        val slotCount = when {
            randomSlotCount > 0 -> randomSlotCount
            randomPullEnabled -> 1
            else -> 0
        }
        return Playlist(
            id = id,
            name = name,
            orderMode = orderMode ?: ORDER_RANDOM,
            intervalMinutes = intervalMinutes,
            entries = (entries ?: emptyList()).toMutableList(),
            timeRules = (timeRules ?: emptyList()).toMutableList(),
            randomSlotCount = slotCount,
            randomStrictTags = randomStrictTags,
            randomSoftTags = randomSoftTags,
            randomStyleValue = randomStyleValue,
            randomR18Mode = randomR18Mode,
            randomSources = randomSources,
            randomPullEnabled = randomPullEnabled ?: false,
        )
    }

    /** Reads the v0.8.11–13 R8-obfuscated layout via [org.json]. */
    private fun migrateR8Obfuscated(text: String): StoreFile? {
        return try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("playlists") ?: root.optJSONArray("a") ?: return null
            val migrated = ArrayList<Playlist>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optStringOrNull("id") ?: o.optStringOrNull("a") ?: continue
                if (id.isBlank()) continue
                migrated.add(Playlist(
                    id = id,
                    name = o.optStringOrNull("name") ?: o.optStringOrNull("b") ?: "Playlist",
                    orderMode = o.optStringOrNull("orderMode") ?: o.optStringOrNull("c") ?: ORDER_RANDOM,
                    intervalMinutes = o.optInt("intervalMinutes", o.optInt("d", 15)),
                    entries = readEntries(o.optJSONArray("entries") ?: o.optJSONArray("e")),
                    timeRules = readRules(o.optJSONArray("timeRules") ?: o.optJSONArray("f")),
                ))
            }
            val active = root.optStringOrNull("activePlaylistId")
                ?: root.optStringOrNull("b") ?: ""
            if (migrated.isEmpty() && active.isBlank()) return null
            WepWawetLogger.appInfo("SuitePlaylistStore: migrated legacy obfuscated store",
                "playlists" to migrated.size)
            StoreFile(playlists = migrated, activePlaylistId = active)
        } catch (e: Exception) {
            WepWawetLogger.appWarn("SuitePlaylistStore: legacy migration failed",
                "exception" to e.javaClass.name)
            null
        }
    }

    private fun readEntries(arr: JSONArray?): MutableList<Entry> {
        if (arr == null) return ArrayList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(
                filepath = o.optStringOrNull("filepath") ?: o.optStringOrNull("a") ?: continue,
                title = o.optStringOrNull("title") ?: o.optStringOrNull("b") ?: "",
                type = o.optStringOrNull("type") ?: o.optStringOrNull("c") ?: "",
            ))
        }
        return out
    }

    private fun readRules(arr: JSONArray?): MutableList<TimeRule> {
        if (arr == null) return ArrayList()
        val out = ArrayList<TimeRule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val paths = o.optJSONArray("entryFilePaths") ?: o.optJSONArray("c")
            out.add(TimeRule(
                startMinute = o.optInt("startMinute", o.optInt("a", 0)),
                endMinute = o.optInt("endMinute", o.optInt("b", 0)),
                entryFilePaths = ArrayList<String>().also { list ->
                    paths?.let { for (j in 0 until it.length()) list.add(it.optString(j)) }
                },
            ))
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    @Synchronized
    private fun writeStore(context: Context, store: StoreFile) {
        try {
            file(context).writeText(gson.toJson(store))
        } catch (e: Exception) {
            WepWawetLogger.appError("SuitePlaylistStore: write failed",
                "exception" to e.javaClass.name)
        }
    }

    // ── Playlist CRUD ────────────────────────────────────────────────

    fun readAll(context: Context): List<Playlist> = readStore(context).playlists.toList()

    fun read(context: Context, id: String): Playlist? =
        readStore(context).playlists.firstOrNull { it.id == id }

    fun upsert(context: Context, playlist: Playlist) {
        // v0.8.15: time rules may only reference entries that still exist —
        // the editor's entry removal used to leave dangling paths that made
        // a rule show "N wallpapers pinned" for wallpapers no longer in
        // the playlist (rotation itself already ignored orphans).
        val entryPaths = playlist.entries.map { it.filepath }.toHashSet()
        for (rule in playlist.timeRules) {
            rule.entryFilePaths.removeAll { it !in entryPaths }
        }
        val store = readStore(context)
        val idx = store.playlists.indexOfFirst { it.id == playlist.id }
        if (idx >= 0) store.playlists[idx] = playlist else store.playlists.add(playlist)
        writeStore(context, store)
        WepWawetLogger.appInfo("SuitePlaylistStore: saved",
            "id" to playlist.id,
            "name" to playlist.name,
            "entries" to playlist.entries.size,
            "time_rules" to playlist.timeRules.size,
            "order" to playlist.orderMode)
        notifyService(context)
    }

    fun delete(context: Context, id: String) {
        val store = readStore(context)
        store.playlists.removeAll { it.id == id }
        if (store.activePlaylistId == id) store.activePlaylistId = ""
        writeStore(context, store)
        WepWawetLogger.appInfo("SuitePlaylistStore: deleted", "id" to id)
        notifyService(context)
    }

    fun rename(context: Context, id: String, name: String) {
        val store = readStore(context)
        val target = store.playlists.firstOrNull { it.id == id } ?: return
        target.name = name.trim().take(40).ifEmpty { target.name }
        writeStore(context, store)
        notifyService(context)
    }

    fun create(context: Context, name: String): Playlist {
        val playlist = Playlist(name = name.trim().take(40).ifEmpty { "Playlist" })
        val store = readStore(context)
        store.playlists.add(playlist)
        writeStore(context, store)
        WepWawetLogger.appInfo("SuitePlaylistStore: created",
            "id" to playlist.id, "name" to playlist.name)
        return playlist
    }

    /**
     * v0.8.17: RETIRE the built-in "Discover" playlist auto-refresh concept
     * entirely ("remove auto refresh entirely from the section. dont have
     * it be a normal play list"). Any playlist left over from a v0.8.16
     * install is dissolved — its entries are dropped (the saved discover
     * files under filesDir/discover are not the user's library images, so
     * nothing else references them) and the files are deleted.
     */
    fun removeDiscoverPlaylistIfPresent(context: Context) {
        val store = readStore(context)
        val leftover = store.playlists.firstOrNull { it.id == "discover" } ?: return
        for (entry in leftover.entries) {
            if (entry.filepath.contains("/discover/")) {
                runCatching { java.io.File(entry.filepath).delete() }
            }
        }
        store.playlists.removeAll { it.id == "discover" }
        if (store.activePlaylistId == "discover") store.activePlaylistId = ""
        writeStore(context, store)
        notifyService(context)
        WepWawetLogger.appInfo("SuitePlaylistStore: legacy Discover playlist removed",
            "entries" to leftover.entries.size)
    }

    // ── Active playlist ──────────────────────────────────────────────

    fun activePlaylist(context: Context): Playlist? {
        val store = readStore(context)
        return store.playlists.firstOrNull { it.id == store.activePlaylistId }
    }

    /** v0.8.15: the stored active playlist id — ONE read instead of a
     * per-item isActive() file parse in the manager list. */
    fun activePlaylistId(context: Context): String =
        readStore(context).activePlaylistId

    fun isActive(context: Context, id: String): Boolean =
        readStore(context).activePlaylistId == id

    fun setActive(context: Context, id: String?) {
        val store = readStore(context)
        store.activePlaylistId = id ?: ""
        writeStore(context, store)
        WepWawetLogger.appInfo("SuitePlaylistStore: active changed",
            "id" to (id ?: "none"))
        notifyService(context)
        // v0.8.16: ACTIVATION MEANS THE LIVE WALLPAPER TAKES OVER. When
        // the WE engine is not running (a static/system wallpaper is set),
        // activating a playlist used to do NOTHING visible — the notify
        // above is a no-op without a running service, and nothing changed
        // when the user went home either ("the buttons in the playlist
        // menu dont update live"). Programmatic live-wallpaper switching
        // (WallpaperManager.setWallpaperComponent) is NOT public API, so
        // the supported path is the system's live-wallpaper confirm flow:
        // ACTION_CHANGE_LIVE_WALLPAPER preselects OUR engine and the user
        // confirms with one tap. The engine then boots, syncs the active
        // playlist and rotates into it.
        if (id != null &&
            io.wallpaperengine.weclient.WEWallpaperService.instance == null
        ) {
            runCatching {
                val intent = android.content.Intent(
                    android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(
                        android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        android.content.ComponentName(
                            context,
                            io.wallpaperengine.weclient.WEWallpaperService::class.java))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                WepWawetLogger.appInfo(
                    "SuitePlaylistStore: activation opened the live-wallpaper " +
                        "confirm flow for the engine")
            }.onFailure {
                WepWawetLogger.appWarn(
                    "SuitePlaylistStore: could not open the live-wallpaper flow",
                    "exception" to it.javaClass.simpleName,
                    "message" to (it.message ?: ""))
            }
        }
    }

    // ── Entry helpers ────────────────────────────────────────────────

    /** Adds an entry to a playlist (no duplicates by filepath). */
    fun addEntry(context: Context, playlistId: String, entry: Entry): Boolean {
        val store = readStore(context)
        val playlist = store.playlists.firstOrNull { it.id == playlistId } ?: return false
        if (playlist.entries.any { it.filepath == entry.filepath }) return false
        playlist.entries.add(entry)
        writeStore(context, store)
        WepWawetLogger.appInfo("SuitePlaylistStore: entry added",
            "playlist" to playlist.name,
            "file" to entry.filepath)
        notifyService(context)
        return true
    }

    /** Removes every entry whose filepath matches (also from time rules). */
    fun removeEntryEverywhere(context: Context, filepath: String) {
        val store = readStore(context)
        var changed = false
        for (playlist in store.playlists) {
            if (playlist.entries.removeAll { it.filepath == filepath }) changed = true
            for (rule in playlist.timeRules) {
                if (rule.entryFilePaths.removeAll { it == filepath }) changed = true
            }
        }
        if (changed) {
            writeStore(context, store)
            WepWawetLogger.appInfo("SuitePlaylistStore: entry removed everywhere",
                "file" to filepath)
            notifyService(context)
        }
    }

    /**
     * v0.8.16: swap one entry path for another across every playlist
     * (entries + time rules). Used when a folder image's crop is saved or
     * removed — the playlist keeps pointing at the image the user SEES
     * (the cropped file), and re-editing keeps it current.
     */
    fun renameFilepathEverywhere(context: Context, fromPath: String, toPath: String) {
        if (fromPath == toPath) return
        val store = readStore(context)
        var changed = false
        for (playlist in store.playlists) {
            for (entry in playlist.entries) {
                if (entry.filepath == fromPath) {
                    entry.filepath = toPath
                    changed = true
                }
            }
            for (rule in playlist.timeRules) {
                for (i in rule.entryFilePaths.indices) {
                    if (rule.entryFilePaths[i] == fromPath) {
                        rule.entryFilePaths[i] = toPath
                        changed = true
                    }
                }
            }
        }
        if (changed) {
            writeStore(context, store)
            WepWawetLogger.appInfo("SuitePlaylistStore: entry path renamed",
                "from" to fromPath.takeLast(48),
                "to" to toPath.takeLast(48))
            notifyService(context)
        }
    }

    // ── Rule evaluation (used by the live service) ───────────────────

    /**
     * The entries that own the CURRENT minute of day: the first time rule
     * whose window contains `minuteOfDay`, or null when no rule matches
     * (the whole playlist rotates). Windows may wrap midnight.
     */
    fun entriesForMinute(playlist: Playlist, minuteOfDay: Int): List<String>? {
        for (rule in playlist.timeRules) {
            if (rule.entryFilePaths.isEmpty()) continue
            val inWindow = if (rule.startMinute == rule.endMinute) {
                true // full-day window
            } else if (rule.startMinute < rule.endMinute) {
                minuteOfDay >= rule.startMinute && minuteOfDay < rule.endMinute
            } else {
                // wraps midnight (e.g. 22:00→06:00)
                minuteOfDay >= rule.startMinute || minuteOfDay < rule.endMinute
            }
            if (inWindow) {
                return rule.entryFilePaths
                    .filter { path -> playlist.entries.any { it.filepath == path } }
            }
        }
        return null
    }

    /** minute-of-day for "now" (local timezone). */
    fun currentMinuteOfDay(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    private fun notifyService(context: Context) {
        // The live service re-syncs its playlist state machine on demand.
        io.wallpaperengine.weclient.WEWallpaperService.instance
            ?.requestPlaylistSettingsChanged()
    }
}
