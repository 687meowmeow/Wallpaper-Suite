package io.wallpaperengine.wedata

/**
 * The official client's built-in property sheets (port of
 * `io.wallpaperengine.wedata.PropertyList`). The JSON payloads are copied
 * verbatim from the decompiled client so per-wallpaper property behavior and
 * defaults stay identical.
 */
object PropertyList {

    val defaultSceneProperties: String = """
    {
    "alignmentposition" : {
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "condition" : "false",
        "order" : -100
    },
    "alignmentpositionlandscape" : {
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "condition" : "false",
        "order" : -100
    },
    "alignmentfreexform" : {
        "type" : "textinput",
        "value" : "",
        "condition" : "false"
    },
    "alignmentfreexformlandscape" : {
        "type" : "textinput",
        "value" : "",
        "condition" : "false"
    },
    "alignmentorientation" : {
        "type" : "combo",
        "value" : "portrait",
        "condition" : "false",
        "order" : -100
    },
    "alignmentfliph" : {
        "text" : "ui_browse_properties_alignment_flip_horizontally",
        "type" : "bool",
        "value" : false,
        "order" : 1
    },
    "mobileparallax" : {
        "text" : "ui_browse_properties_parallax",
        "type" : "combo",
        "value" : 0,
        "order" : 4,
        "options" : [{
            "label" : "ui_browse_properties_parallax_disabled",
            "value" : 0
        }, {
            "label" : "ui_browse_properties_parallax_gyro",
            "value" : 1
        }, {
            "label" : "ui_browse_properties_parallax_scroll",
            "value" : 2
        }]
    },
    "mobileparallaxstrength" : {
        "text" : "ui_browse_properties_parallax_strength",
        "type" : "slider",
        "value" : 100.0,
        "min" : 1.0,
        "max" : 200.0,
        "order" : 5,
        "condition" : "mobileparallax.value"
    },
    "rate" : {
        "text" : "ui_browse_properties_playback_rate",
        "type" : "slider",
        "value" : 100.0,
        "min" : 1.0,
        "max" : 200.0,
        "order" : 6
    },
    "wec_e" : {
        "text" : "ui_browse_properties_show_color_options",
        "type" : "bool",
        "value" : false,
        "order" : 10
    },
    "wec_brs" : {
        "text" : "ui_browse_properties_brightness",
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "order" : 11,
        "condition" : "wec_e.value"
    },
    "wec_con" : {
        "text" : "ui_browse_properties_contrast",
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "order" : 12,
        "condition" : "wec_e.value"
    },
    "wec_sa" : {
        "text" : "ui_browse_properties_saturation",
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "order" : 13,
        "condition" : "wec_e.value"
    },
    "wec_hue" : {
        "text" : "ui_browse_properties_hue_shift",
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "order" : 14,
        "condition" : "wec_e.value"
    }
    }
    """.trimIndent()

    val alignmentModes: String = """
    {
    "alignment" : {
        "text" : "ui_browse_properties_alignment",
        "type" : "combo",
        "value" : 0,
        "order" : 2147483647,
        "options" : [{
            "label" : "ui_browse_properties_alignment_cover",
            "value" : 0
        }, {
            "label" : "ui_browse_properties_alignment_fill",
            "value" : 1
        }, {
            "label" : "ui_browse_properties_alignment_free",
            "value" : 4
        }]
    }
    }
    """.trimIndent()

    val audioRecordingProperties: String = """
    {
    "audioprocessing" : {
        "text" : "ui_browse_properties_audio_recording",
        "type" : "bool",
        "value" : true,
        "order" : 2
    }
    }
    """.trimIndent()

    val environmentPhysicsProperties: String = """
    {
    "mobilemotionresponse" : {
        "type" : "bool",
        "value" : true,
        "condition" : "false",
        "order" : 2
    }
    }
    """.trimIndent()

    val defaultVideoProperties: String = """
    {
    "alignmentposition" : {
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "condition" : "false",
        "order" : -100
    },
    "alignmentpositionlandscape" : {
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "condition" : "false",
        "order" : -100
    },
    "alignmentfreexform" : {
        "type" : "textinput",
        "value" : "",
        "condition" : "false"
    },
    "alignmentfreexformlandscape" : {
        "type" : "textinput",
        "value" : "",
        "condition" : "false"
    },
    "alignmentfliph" : {
        "text" : "ui_browse_properties_alignment_flip_horizontally",
        "type" : "bool",
        "value" : false,
        "order" : 1
    },
    "alignment" : {
        "text" : "ui_browse_properties_alignment",
        "type" : "combo",
        "value" : 0,
        "order" : 2147483647,
        "options" : [{
            "label" : "ui_browse_properties_alignment_cover",
            "value" : 0
        }, {
            "label" : "ui_browse_properties_alignment_fill",
            "value" : 1
        }, {
            "label" : "ui_browse_properties_alignment_free",
            "value" : 2
        }]
    },
    "mobileparallax" : {
        "text" : "ui_browse_properties_parallax",
        "type" : "combo",
        "value" : 0,
        "order" : 4,
        "options" : [{
            "label" : "ui_browse_properties_parallax_disabled",
            "value" : 0
        }, {
            "label" : "ui_browse_properties_parallax_gyro",
            "value" : 1
        }, {
            "label" : "ui_browse_properties_parallax_scroll",
            "value" : 2
        }]
    },
    "mobileparallaxstrength" : {
        "text" : "ui_browse_properties_parallax_strength",
        "type" : "slider",
        "value" : 100.0,
        "min" : 1.0,
        "max" : 200.0,
        "order" : 5,
        "condition" : "mobileparallax.value"
    },
    "rate" : {
        "text" : "ui_browse_properties_playback_rate",
        "type" : "slider",
        "value" : 100.0,
        "min" : 1.0,
        "max" : 200.0,
        "order" : 6
    },
    "schemecolor" : {
        "text" : "ui_browse_properties_background_color",
        "type" : "color",
        "value" : "0 0 0",
        "order" : 7
    },
    "wec_e" : {
        "text" : "ui_browse_properties_show_color_options",
        "type" : "bool",
        "value" : false,
        "order" : 10
    },
    "wec_brs" : {
        "text" : "ui_browse_properties_brightness",
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "order" : 11,
        "condition" : "wec_e.value"
    },
    "wec_con" : {
        "text" : "ui_browse_properties_contrast",
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "order" : 12,
        "condition" : "wec_e.value"
    },
    "wec_sa" : {
        "text" : "ui_browse_properties_saturation",
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "order" : 13,
        "condition" : "wec_e.value"
    },
    "wec_hue" : {
        "text" : "ui_browse_properties_hue_shift",
        "type" : "slider",
        "value" : 50.0,
        "min" : 0.0,
        "max" : 100.0,
        "order" : 14,
        "condition" : "wec_e.value"
    }
    }
    """.trimIndent()

    val defaultGIFProperties: String = """
    {
    "pixelartoptimization" : {
        "text" : "ui_browse_properties_pixel_art_optimization",
        "type" : "bool",
        "value" : false,
        "order" : 2
    }
    }
    """.trimIndent()
}
