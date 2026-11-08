package io.wallpaperengine.weclient

/**
 * SharedPreferences keys + property-name constants for the engine layer,
 * ported 1:1 from the official client's `WallpaperProperties` companion
 * (the PreferenceFragment UI itself is replaced by our Compose screens;
 * only the constants and their exact string values are needed).
 */
object WallpaperProperties {

    // ===== General settings (SharedPreferences keys) =====
    const val GENERAL_PROP_FPS = "general_fps"
    const val GENERAL_PROP_TOUCH_INPUT = "general_touch_input"
    const val GENERAL_PROP_POWER_SAVE = "general_power_save"
    const val GENERAL_PROP_LOG_ERRORS = "general_log_errors"
    const val GENERAL_PROP_COLORS_API = "general_colors_api"
    const val GENERAL_PROP_AUTO_CONNECT = "general_auto_connect"
    const val GENERAL_PROP_MATCH_PREVIEW = "general_match_preview"
    const val GENERAL_PROP_HAS_SHOWN_SCROLL_WARNING = "general_has_shown_scroll_warning"
    const val GENERAL_DEFAULT_MAX_FPS = 30

    // ===== Playlist =====
    const val PLAYLIST_MODE = "playlist_mode"
    const val PLAYLIST_DURATION = "playlist_duration"
    const val PLAYLIST_DEFAULT_DURATION = 30
    const val PLAYLIST_MODE_RANDOM = "random"
    const val PLAYLIST_MODE_SORTED = "sorted"
    const val PLAYLIST_MODE_TIME_OF_DAY = "timeOfDay"
    const val PLAYLIST_MODE_DAY_OF_WEEK = "dayOfWeek"

    // ===== Per-wallpaper property names (engine JSON keys) =====
    const val PROP_ALIGNMENT = "alignment"
    const val PROP_ALIGNMENT_FLIP_H = "alignmentfliph"
    const val PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX = "alignmentfreexform"
    const val PROP_ALIGNMENT_FREE_TRANSFORM_MATRIX_LANDSCAPE = "alignmentfreexformlandscape"
    const val PROP_ALIGNMENT_ORIENTATION = "alignmentorientation"
    const val PROP_ALIGNMENT_POSITION = "alignmentposition"
    const val PROP_ALIGNMENT_POSITION_LANDSCAPE = "alignmentpositionlandscape"
    const val PROP_AUDIO_PROCESSING = "audioprocessing"
    const val PROP_MOTION_RESPONSE = "mobilemotionresponse"
    const val PROP_PARALLAX = "mobileparallax"
    const val PROP_PARALLAX_STRENGTH = "mobileparallaxstrength"
    const val PROP_PIXEL_ART_OPTIMIZATION = "pixelartoptimization"
    const val PROP_RATE = "rate"
    const val PROP_SCHEME_COLOR = "schemecolor"
    const val PROP_WEC_BRIGHTNESS = "wec_brs"
    const val PROP_WEC_CONTRAST = "wec_con"
    const val PROP_WEC_ENABLED = "wec_e"
    const val PROP_WEC_HUE_SHIFT = "wec_hue"
    const val PROP_WEC_SATURATION = "wec_sa"

    // ===== Property values =====
    const val VALUE_ALIGNMENT_ORIENTATION_LANDSCAPE = "landscape"
    const val VALUE_ALIGNMENT_ORIENTATION_PORTRAIT = "portrait"
    const val VALUE_COVER_INDEX = 0
    const val VALUE_FILL_INDEX = 1
    const val VALUE_FREE_INDEX = 2
    const val VALUE_FREE_INDEX_SCENE = 4
    const val VALUE_PARALLAX_DISABLED_INDEX = 0
    const val VALUE_PARALLAX_GYRO_INDEX = 1
    const val VALUE_PARALLAX_SCROLL_INDEX = 2
    const val VALUE_PARALLAX_GYRO_FREE_INDEX = 3
}
