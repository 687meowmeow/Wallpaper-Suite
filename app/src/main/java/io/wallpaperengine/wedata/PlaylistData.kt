package io.wallpaperengine.wedata

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Playlist persistence model, ported 1:1 from the official client
 * (`playlistConfig` SharedPreferences entry, Gson-serialized).
 */
class PlaylistDataWallpaper {
    var filepath: String = ""
    var duration: Int = 0
}

class PlaylistData {
    var wallpapers: ArrayList<PlaylistDataWallpaper> = ArrayList()

    companion object {
        private val playlistDataType = object : TypeToken<PlaylistData>() {}.type

        fun fromJson(json: String?): PlaylistData {
            if (json.isNullOrEmpty()) return PlaylistData()
            return try {
                Gson().fromJson(json, playlistDataType) ?: PlaylistData()
            } catch (e: Exception) {
                PlaylistData()
            }
        }

        fun toJson(data: PlaylistData): String = Gson().toJson(data)
    }
}
