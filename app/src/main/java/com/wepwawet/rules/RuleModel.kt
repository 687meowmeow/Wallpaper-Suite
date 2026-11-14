package com.wepwawet.rules

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.9.0: THE RULES MODEL — playlists that REACT to the phone.
 *
 * A rule has three parts:
 *  • TRIGGERS — the events that start it (several; any one starts the rule);
 *  • an optional GATE — a condition tree that must be true for the rule to
 *    run at all;
 *  • a STACK of ACTION BLOCKS — run top to bottom, with first-class
 *    conditional logic (If/Then/Otherwise, Choose, Repeat, Wait, Stop,
 *    Run-rule) and blocks that produce VALUES later blocks can use.
 *
 * Everything is built by taps — dialogs, dropdowns, pickers — never by
 * typing logic. The model is plain data (org.json serialization — the
 * field set is versioned by hand, no reflection surprises); the engine
 * (RuleEngine) executes it and the editor (RuleEditorScreen) edits it.
 *
 * Block VALUE FLOW: a block that picks a wallpaper produces a value — the
 * picked path — under the keys "last" and "block:<id>". A later block's
 * field can reference an earlier value with the marker "@last"/"@block:<id>"
 * (the editor offers them as chips). Named values (a boolean/number/string
 * with a name) are shared across rules via SuiteRulesStore.
 */
object RuleModel {

    // ── Trigger types ──────────────────────────────────────────────────

    const val TRIG_TIME_WINDOW = "timeWindow"       // inside a window every day
    const val TRIG_TIME_OF_DAY = "timeOfDay"         // at a specific time
    const val TRIG_DAY_OF_WEEK = "dayOfWeek"         // on a weekday
    const val TRIG_DATE = "date"                     // on a calendar date
    const val TRIG_INTERVAL = "interval"             // every N minutes
    const val TRIG_SCREEN_ON = "screenOn"
    const val TRIG_SCREEN_OFF = "screenOff"
    const val TRIG_UNLOCKED = "unlocked"
    const val TRIG_CHARGER_IN = "chargerIn"
    const val TRIG_CHARGER_OUT = "chargerOut"
    const val TRIG_BATTERY_BELOW = "batteryBelow"
    const val TRIG_BATTERY_ABOVE = "batteryAbove"
    const val TRIG_SAVER_ON = "saverOn"
    const val TRIG_SAVER_OFF = "saverOff"
    const val TRIG_WIFI_CONNECT = "wifiConnect"
    const val TRIG_WIFI_DISCONNECT = "wifiDisconnect"
    const val TRIG_MOBILE_DATA = "mobileDataActive"
    const val TRIG_HEADPHONES_IN = "headphonesIn"
    const val TRIG_HEADPHONES_OUT = "headphonesOut"
    const val TRIG_BT_DEVICE_IN = "btDeviceIn"
    const val TRIG_BT_DEVICE_OUT = "btDeviceOut"
    const val TRIG_ORIENTATION = "orientationChange"
    const val TRIG_WALLPAPER_CHANGED = "wallpaperChanged"
    const val TRIG_WORKSHOP_DOWNLOADED = "workshopDownloaded"
    const val TRIG_WORKSHOP_UPDATE = "workshopUpdate"
    const val TRIG_DISCOVER_QUEUE_LOW = "discoverQueueLow"
    const val TRIG_MANUAL_BUTTON = "manualButton"
    const val TRIG_NOTIFICATION_BUTTON = "notificationButton"
    const val TRIG_QS_TILE = "qsTile"
    const val TRIG_FROM_RULE = "fromRule"

    /** Time-based trigger types (the hub schedules wakes for these). */
    val TIME_TRIGGERS = setOf(TRIG_TIME_WINDOW, TRIG_TIME_OF_DAY, TRIG_DAY_OF_WEEK, TRIG_DATE, TRIG_INTERVAL)

    // ── Condition types ────────────────────────────────────────────────

    const val COND_ALL = "all"
    const val COND_ANY = "any"
    const val COND_NOT = "not"
    const val COND_TIME_RANGE = "timeRange"          // now inside a window
    const val COND_DAY_OF_WEEK = "dayOfWeekIs"
    const val COND_CHARGING = "charging"
    const val COND_BATTERY_BELOW = "batteryBelow"
    const val COND_BATTERY_ABOVE = "batteryAbove"
    const val COND_SAVER_ON = "saverOn"
    const val COND_WIFI_CONNECTED = "wifiConnected"
    const val COND_WIFI_IS = "wifiIs"                 // a specific ssid ("" = any)
    const val COND_MOBILE_DATA = "mobileDataOn"
    const val COND_HEADPHONES = "headphonesOn"
    const val COND_SCREEN_ON = "screenOn"
    const val COND_ORIENTATION_IS = "orientationIs"   // portrait / landscape
    const val COND_PLAYLIST_ACTIVE = "playlistActive" // a playlist id
    const val COND_WALLPAPER_IS = "wallpaperIs"      // a path / @value
    const val COND_SHOWN_MINUTES = "shownMinutes"     // current shown for X
    const val COND_VALUE_IS = "valueIs"               // named value comparison
    const val COND_LAST_PICK_OK = "lastPickOk"

    /** Leaf condition kinds (everything except all/any/not). */
    val COND_LEAVES = setOf(
        COND_TIME_RANGE, COND_DAY_OF_WEEK, COND_CHARGING, COND_BATTERY_BELOW,
        COND_BATTERY_ABOVE, COND_SAVER_ON, COND_WIFI_CONNECTED, COND_WIFI_IS,
        COND_MOBILE_DATA, COND_HEADPHONES, COND_SCREEN_ON, COND_ORIENTATION_IS,
        COND_PLAYLIST_ACTIVE, COND_WALLPAPER_IS, COND_SHOWN_MINUTES,
        COND_VALUE_IS, COND_LAST_PICK_OK,
    )

    // ── Action block types ─────────────────────────────────────────────

    // Wallpaper
    const val BLOCK_SHOW = "showWallpaper"           // a specific one
    const val BLOCK_SHOW_SET = "showFromSet"         // picked from a chosen set
    const val BLOCK_PICK_GROUP = "pickFromGroup"     // the group's live contents
    const val BLOCK_PICK_PLAYLIST = "pickFromPlaylist"
    const val BLOCK_RANDOM = "randomFrom"            // library/group/playlist/workshop/discover
    const val BLOCK_PULL_DISCOVER = "pullDiscover"   // a fresh Discover image
    const val BLOCK_NEXT = "nextWallpaper"
    const val BLOCK_PREV = "prevWallpaper"
    const val BLOCK_SHUFFLE = "shuffleWallpaper"
    // Scope ("Only use these wallpapers while…") — a rule containing one is
    // a SCOPE: while its condition holds, the rotation may only pick from
    // the block's paths. Evaluated continuously by the service, never
    // "run" like a normal action.
    const val BLOCK_LIMIT = "limitScope"
    // Playlist
    const val BLOCK_PAUSE_ROTATION = "pauseRotation"
    const val BLOCK_RESUME_ROTATION = "resumeRotation"
    const val BLOCK_RESTART_PLAYLIST = "restartPlaylist"
    const val BLOCK_HOLD_CURRENT = "holdCurrent"
    const val BLOCK_SWITCH_PLAYLIST = "switchPlaylist"
    // Engine
    const val BLOCK_SET_FPS = "setFps"
    const val BLOCK_AUDIO_MUTE = "audioMute"          // mute/unmute (param)
    const val BLOCK_RENDERER_PAUSE = "rendererPause"  // pause/resume (param)
    const val BLOCK_APPLY_PRESET = "applyPreset"
    // Logic
    const val BLOCK_IF = "ifBlock"
    const val BLOCK_CHOOSE = "choose"
    const val BLOCK_REPEAT = "repeat"
    const val BLOCK_WAIT = "wait"
    const val BLOCK_STOP = "stop"
    const val BLOCK_RUN_RULE = "runRule"
    // Utility
    const val BLOCK_NOTIFY = "notify"
    const val BLOCK_DISCOVER_REFRESH = "discoverRefresh"
    const val BLOCK_WORKSHOP_DOWNLOAD = "workshopDownload"
    // Named values
    const val BLOCK_SET_VALUE = "setValue"
    const val BLOCK_ADD_VALUE = "addValue"
    const val BLOCK_CLEAR_VALUE = "clearValue"
    // Value flow
    const val BLOCK_SAVE_TO_FOLDER = "saveToFolder"
    const val BLOCK_ADD_TO_PLAYLIST = "addToPlaylist"

    /** Blocks that hold an inner stack (the UI indents them with a rail). */
    fun hasChildren(type: String): Boolean =
        type == BLOCK_IF || type == BLOCK_REPEAT || type == BLOCK_CHOOSE

    // ── The model classes (plain data, org.json serialization) ──────────

    data class Trigger(
        var type: String = TRIG_TIME_WINDOW,
        var minuteStart: Int = 0,
        var minuteEnd: Int = 0,
        var timeMinute: Int = 0,
        var dayOfWeek: Int = -1,
        var dateEpochDay: Long = 0,
        var intervalMinutes: Int = 60,
        var batteryLevel: Int = 20,
        var networkSsid: String = "",
        var deviceName: String = "",
        var ruleId: String = "",
    )

    data class Cond(
        var type: String = COND_ALL,
        var children: MutableList<Cond> = ArrayList(),
        // leaf params (used per type)
        var minuteStart: Int = 0,
        var minuteEnd: Int = 0,
        var dayOfWeek: Int = -1,
        var batteryLevel: Int = 20,
        var networkSsid: String = "",
        var deviceName: String = "",
        var orientation: String = "portrait",
        var playlistId: String = "",
        var valueName: String = "",
        var cmp: String = "=",
        var compareTo: String = "",
        var compareToNum: Long = 0,
        var minutes: Long = 30,
        var wallpaperPath: String = "",
    )

    data class Block(
        var id: String = newId(),
        var type: String = BLOCK_SHOW,
        /** Primary param — a path / group / playlist / source / preset…; may be a "@value" reference. */
        var param: String = "",
        /** Secondary param — target screen, pick mode, preset name, … */
        var param2: String = "",
        /** Tertiary param — audio mute / renderer pause direction etc. */
        var param3: String = "",
        /** Generic number — repeat count, wait seconds, fps, hold minutes… */
        var num: Int = 1,
        var cond: Cond? = null,
        var paths: MutableList<String> = ArrayList(),
        var thenBlocks: MutableList<Block> = ArrayList(),
        var elseBlocks: MutableList<Block> = ArrayList(),
        var children: MutableList<Block> = ArrayList(),
        var options: MutableList<BlockOption> = ArrayList(),
        var collapsed: Boolean = false,
        /** v0.9.0 redesign: the ⋮ menu's Disable — a disabled block is
         *  kept but skipped by the engine. */
        var disabled: Boolean = false,
    )

    data class BlockOption(
        var label: String = "Option",
        var blocks: MutableList<Block> = ArrayList(),
    )

    data class Rule(
        var id: String = newId(),
        var name: String = "Rule",
        var enabled: Boolean = true,
        var triggers: MutableList<Trigger> = ArrayList(),
        var gate: Cond? = null,
        var actions: MutableList<Block> = ArrayList(),
        var cooldownMinutes: Int = 0,
        var holdMinutes: Int = 0,
        var lastFiredAt: Long = 0,
        var lastResult: String = "",
    )

    data class PlaylistRules(
        var playlistId: String = "",
        /** "first" = the first matching rule wins; "all" = every match runs. */
        var mode: String = "first",
        var rules: MutableList<Rule> = ArrayList(),
    )

    fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

    // ── JSON (de)serialization ─────────────────────────────────────────

    fun triggerToJson(t: Trigger): JSONObject = JSONObject()
        .put("type", t.type)
        .put("minuteStart", t.minuteStart)
        .put("minuteEnd", t.minuteEnd)
        .put("timeMinute", t.timeMinute)
        .put("dayOfWeek", t.dayOfWeek)
        .put("dateEpochDay", t.dateEpochDay)
        .put("intervalMinutes", t.intervalMinutes)
        .put("batteryLevel", t.batteryLevel)
        .put("networkSsid", t.networkSsid)
        .put("deviceName", t.deviceName)
        .put("ruleId", t.ruleId)

    fun triggerFromJson(o: JSONObject): Trigger = Trigger(
        type = o.optString("type", TRIG_TIME_WINDOW),
        minuteStart = o.optInt("minuteStart", 0),
        minuteEnd = o.optInt("minuteEnd", 0),
        timeMinute = o.optInt("timeMinute", 0),
        dayOfWeek = o.optInt("dayOfWeek", -1),
        dateEpochDay = o.optLong("dateEpochDay", 0),
        intervalMinutes = o.optInt("intervalMinutes", 60),
        batteryLevel = o.optInt("batteryLevel", 20),
        networkSsid = o.optString("networkSsid", ""),
        deviceName = o.optString("deviceName", ""),
        ruleId = o.optString("ruleId", ""),
    )

    fun condToJson(c: Cond): JSONObject {
        val o = JSONObject().put("type", c.type)
        if (c.children.isNotEmpty()) {
            val arr = JSONArray()
            for (ch in c.children) arr.put(condToJson(ch))
            o.put("children", arr)
        }
        if (c.minuteStart != 0) o.put("minuteStart", c.minuteStart)
        if (c.minuteEnd != 0) o.put("minuteEnd", c.minuteEnd)
        if (c.dayOfWeek != -1) o.put("dayOfWeek", c.dayOfWeek)
        if (c.batteryLevel != 20) o.put("batteryLevel", c.batteryLevel)
        if (c.networkSsid.isNotEmpty()) o.put("networkSsid", c.networkSsid)
        if (c.deviceName.isNotEmpty()) o.put("deviceName", c.deviceName)
        if (c.orientation != "portrait") o.put("orientation", c.orientation)
        if (c.playlistId.isNotEmpty()) o.put("playlistId", c.playlistId)
        if (c.valueName.isNotEmpty()) o.put("valueName", c.valueName)
        if (c.cmp != "=") o.put("cmp", c.cmp)
        if (c.compareTo.isNotEmpty()) o.put("compareTo", c.compareTo)
        if (c.compareToNum != 0L) o.put("compareToNum", c.compareToNum)
        if (c.minutes != 30L) o.put("minutes", c.minutes)
        if (c.wallpaperPath.isNotEmpty()) o.put("wallpaperPath", c.wallpaperPath)
        return o
    }

    fun condFromJson(o: JSONObject): Cond = Cond(
        type = o.optString("type", COND_ALL),
        children = ArrayList<Cond>().also { list ->
            val arr = o.optJSONArray("children") ?: return@also
            for (i in 0 until arr.length()) {
                val co = arr.optJSONObject(i) ?: continue
                list.add(condFromJson(co))
            }
        },
        minuteStart = o.optInt("minuteStart", 0),
        minuteEnd = o.optInt("minuteEnd", 0),
        dayOfWeek = o.optInt("dayOfWeek", -1),
        batteryLevel = o.optInt("batteryLevel", 20),
        networkSsid = o.optString("networkSsid", ""),
        deviceName = o.optString("deviceName", ""),
        orientation = o.optString("orientation", "portrait"),
        playlistId = o.optString("playlistId", ""),
        valueName = o.optString("valueName", ""),
        cmp = o.optString("cmp", "="),
        compareTo = o.optString("compareTo", ""),
        compareToNum = o.optLong("compareToNum", 0),
        minutes = o.optLong("minutes", 30),
        wallpaperPath = o.optString("wallpaperPath", ""),
    )

    fun blockToJson(b: Block): JSONObject {
        val o = JSONObject()
            .put("id", b.id)
            .put("type", b.type)
            .put("param", b.param)
            .put("param2", b.param2)
            .put("param3", b.param3)
            .put("num", b.num)
        if (b.paths.isNotEmpty()) o.put("paths", JSONArray(b.paths))
        b.cond?.let { o.put("cond", condToJson(it)) }
        if (b.thenBlocks.isNotEmpty()) o.put("then", blocksToJson(b.thenBlocks))
        if (b.elseBlocks.isNotEmpty()) o.put("else", blocksToJson(b.elseBlocks))
        if (b.children.isNotEmpty()) o.put("children", blocksToJson(b.children))
        if (b.options.isNotEmpty()) {
            val arr = JSONArray()
            for (opt in b.options) {
                arr.put(JSONObject()
                    .put("label", opt.label)
                    .put("blocks", blocksToJson(opt.blocks)))
            }
            o.put("options", arr)
        }
        if (b.collapsed) o.put("collapsed", true)
        if (b.disabled) o.put("disabled", true)
        return o
    }

    fun blockFromJson(o: JSONObject): Block = Block(
        id = o.optString("id", newId()).ifEmpty { newId() },
        type = o.optString("type", BLOCK_SHOW),
        param = o.optString("param", ""),
        param2 = o.optString("param2", ""),
        param3 = o.optString("param3", ""),
        num = o.optInt("num", 1),
        paths = ArrayList<String>().also { list ->
            val arr = o.optJSONArray("paths") ?: return@also
            for (i in 0 until arr.length()) list.add(arr.optString(i))
        },
        cond = o.optJSONObject("cond")?.let { condFromJson(it) },
        thenBlocks = blocksFromJson(o.optJSONArray("then")),
        elseBlocks = blocksFromJson(o.optJSONArray("else")),
        children = blocksFromJson(o.optJSONArray("children")),
        options = ArrayList<BlockOption>().also { list ->
            val arr = o.optJSONArray("options") ?: return@also
            for (i in 0 until arr.length()) {
                val oo = arr.optJSONObject(i) ?: continue
                list.add(BlockOption(
                    label = oo.optString("label", "Option"),
                    blocks = blocksFromJson(oo.optJSONArray("blocks")),
                ))
            }
        },
        collapsed = o.optBoolean("collapsed", false),
        disabled = o.optBoolean("disabled", false),
    )

    fun blocksToJson(blocks: List<Block>): JSONArray {
        val arr = JSONArray()
        for (b in blocks) arr.put(blockToJson(b))
        return arr
    }

    private fun blocksFromJson(arr: JSONArray?): MutableList<Block> =
        ArrayList<Block>().also { list ->
            if (arr == null) return@also
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(blockFromJson(o))
            }
        }

    fun ruleToJson(r: Rule): JSONObject = JSONObject()
        .put("id", r.id)
        .put("name", r.name)
        .put("enabled", r.enabled)
        .put("triggers", JSONArray(r.triggers.map { triggerToJson(it) }))
        .put("gate", r.gate?.let { condToJson(it) } ?: JSONObject.NULL)
        .put("actions", blocksToJson(r.actions))
        .put("cooldownMinutes", r.cooldownMinutes)
        .put("holdMinutes", r.holdMinutes)
        .put("lastFiredAt", r.lastFiredAt)
        .put("lastResult", r.lastResult)

    fun ruleFromJson(o: JSONObject): Rule = Rule(
        id = o.optString("id", newId()).ifEmpty { newId() },
        name = o.optString("name", "Rule"),
        enabled = o.optBoolean("enabled", true),
        triggers = ArrayList<Trigger>().also { list ->
            val arr = o.optJSONArray("triggers") ?: return@also
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                list.add(triggerFromJson(t))
            }
        },
        gate = o.optJSONObject("gate")?.let { condFromJson(it) },
        actions = blocksFromJson(o.optJSONArray("actions")),
        cooldownMinutes = o.optInt("cooldownMinutes", 0),
        holdMinutes = o.optInt("holdMinutes", 0),
        lastFiredAt = o.optLong("lastFiredAt", 0),
        lastResult = o.optString("lastResult", ""),
    )

    fun playlistRulesToJson(pr: PlaylistRules): JSONObject = JSONObject()
        .put("mode", pr.mode)
        .put("rules", JSONArray(pr.rules.map { ruleToJson(it) }))

    fun playlistRulesFromJson(playlistId: String, o: JSONObject): PlaylistRules =
        PlaylistRules(
            playlistId = playlistId,
            mode = o.optString("mode", "first"),
            rules = ArrayList<Rule>().also { list ->
                val arr = o.optJSONArray("rules") ?: return@also
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    list.add(ruleFromJson(r))
                }
            },
        )

    /** Deep-copy helpers (duplicate block / duplicate rule). */
    fun copyBlock(b: Block): Block {
        val json = blockToJson(b)
        val copy = blockFromJson(json)
        copy.id = newId()  // fresh ids so value refs stay unambiguous
        return copy
    }

    fun deepCopyBlocks(blocks: List<Block>): MutableList<Block> =
        blocks.map { copyBlock(it) }.toMutableList()

    fun copyRule(r: Rule): Rule = Rule(
        id = newId(),
        name = if (r.name.endsWith(" (copy)")) r.name else "${r.name} (copy)",
        enabled = r.enabled,
        triggers = r.triggers.map { it.copy() }.toMutableList(),
        gate = r.gate?.let { condFromJson(condToJson(it)) },
        actions = deepCopyBlocks(r.actions),
        cooldownMinutes = r.cooldownMinutes,
        holdMinutes = r.holdMinutes,
    )
}
