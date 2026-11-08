package io.wallpaperengine.wedata

/**
 * General (app-wide) engine settings, ported 1:1 from the official client.
 * Persisted via [io.wallpaperengine.weclient.WeEnginePrefs.readGeneralSettings]
 * using the same SharedPreferences keys the official client uses.
 */
class GeneralSettingsInfo {
    var maxFps: Int = 30
    var touchInputEnabled: Boolean = true
    var powerSavingEnabled: Boolean = true
    var logToFileEnabled: Boolean = false

    /**
     * Wallpaper Colors integration mode:
     * `enabled` / `disabled` / `hintdark` / `hintdarktheme` / `hintbright`.
     */
    var colorsApi: String = "enabled"
}
