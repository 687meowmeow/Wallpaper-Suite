@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.wallpaperengine.wedata

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * A single user-adjustable wallpaper property (port of the official
 * client's `io.wallpaperengine.wedata.Property`).
 *
 * Deserialized from JSON maps like:
 * ```json
 * "rate": {
 *   "text": "ui_browse_properties_playback_rate",
 *   "type": "slider",
 *   "value": 100.0, "min": 1.0, "max": 200.0,
 *   "order": 6, "condition": "wec_e.value"
 * }
 * ```
 * `value`/`min`/`max` arrive as Double/Boolean/String/… — hence `Any?`.
 */
class Property {

    class Option {
        var label: String = ""
        var value: Any? = null
    }

    var text: String = ""
    var type: String = ""

    /** Current value — Double, Boolean, Int, String, … depending on [type]. */
    var value: Any? = null

    /** Localized label of the current value (combo entries), if any. */
    var valueLabel: String = ""

    /** Visibility condition, e.g. `"wec_e.value"` — true when that prop is on. */
    var condition: String = ""

    var min: Any? = null
    var max: Any? = null
    var step: Float? = null
    var precision: Int? = null
    var order: Int? = null

    var options: Array<Option> = emptyArray()

    constructor()

    /** Copy constructor (used when deriving per-wallpaper state from defaults). */
    constructor(other: Property) {
        text = other.text
        type = other.type
        value = other.value
        valueLabel = other.valueLabel
        condition = other.condition
        min = other.min
        max = other.max
        step = other.step
        precision = other.precision
        order = other.order
        options = other.options
    }

    companion object {
        private val tPropertyHashMap: Type =
            object : TypeToken<HashMap<String, Property>>() {}.type

        /** Parses a property JSON map; empty map on any parse failure. */
        @JvmStatic
        fun fromJson(properties: String?): HashMap<String, Property> {
            if (properties.isNullOrEmpty()) return HashMap()
            return try {
                Gson().fromJson(properties, tPropertyHashMap) ?: HashMap()
            } catch (e: Exception) {
                HashMap()
            }
        }
    }
}
