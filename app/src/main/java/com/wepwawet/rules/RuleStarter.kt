package com.wepwawet.rules

/**
 * v0.9.0: ready-made STARTER RULES — "a few ready-made starting rules to
 * copy from, such as a night wallpaper rule, a charging rule, and a Wi-Fi
 * rule". Each factory returns a fresh rule (fresh ids) the editor saves as
 * a copy the user then adapts with taps.
 */
object RuleStarter {

    /** 22:00–06:00: night mode on + the lock screen gets a night wallpaper. */
    fun nightRule(): RuleModel.Rule {
        val nightGate = RuleModel.Cond(
            type = RuleModel.COND_TIME_RANGE,
            minuteStart = 22 * 60,
            minuteEnd = 6 * 60,
        )
        val otherwise = RuleModel.Block(
            type = RuleModel.BLOCK_IF,
            param = "Is it night?",
            cond = RuleModel.Cond(type = RuleModel.COND_TIME_RANGE,
                minuteStart = 22 * 60, minuteEnd = 6 * 60),
            thenBlocks = ArrayList(listOf(
                RuleModel.Block(type = RuleModel.BLOCK_SET_VALUE,
                    param = "Night mode", param2 = "on", param3 = "bool"),
                RuleModel.Block(type = RuleModel.BLOCK_PICK_GROUP,
                    param = "", param2 = "random",
                    param3 = "lock",
                    num = 1),
            )),
            elseBlocks = ArrayList(listOf(
                RuleModel.Block(type = RuleModel.BLOCK_SET_VALUE,
                    param = "Night mode", param2 = "off", param3 = "bool"),
            )),
        )
        return RuleModel.Rule(
            name = "Night wallpaper",
            triggers = ArrayList(listOf(
                RuleModel.Trigger(type = RuleModel.TRIG_TIME_WINDOW,
                    minuteStart = 22 * 60, minuteEnd = 6 * 60),
                RuleModel.Trigger(type = RuleModel.TRIG_TIME_WINDOW,
                    minuteStart = 6 * 60, minuteEnd = 6 * 60),
            )),
            gate = null,
            actions = ArrayList(listOf(otherwise)),
            cooldownMinutes = 30,
        )
    }

    /** Plugged in: pause the rotation + hold the wallpaper for an hour. */
    fun chargingRule(): RuleModel.Rule =
        RuleModel.Rule(
            name = "Charging",
            triggers = ArrayList(listOf(
                RuleModel.Trigger(type = RuleModel.TRIG_CHARGER_IN),
            )),
            actions = ArrayList(listOf(
                RuleModel.Block(type = RuleModel.BLOCK_NOTIFY,
                    param = "Charging",
                    param2 = "Wallpaper held for 60 minutes while charging."),
                RuleModel.Block(type = RuleModel.BLOCK_HOLD_CURRENT, num = 60),
            )),
            cooldownMinutes = 10,
        )

    /**
     * On home Wi-Fi: set a Night-mode-style flag other rules can read
     * ("whether a named value is a certain thing" condition example).
     */
    fun wifiRule(): RuleModel.Rule =
        RuleModel.Rule(
            name = "Home Wi-Fi",
            triggers = ArrayList(listOf(
                RuleModel.Trigger(type = RuleModel.TRIG_WIFI_CONNECT),
            )),
            gate = RuleModel.Cond(type = RuleModel.COND_WIFI_CONNECTED),
            actions = ArrayList(listOf(
                RuleModel.Block(type = RuleModel.BLOCK_SET_VALUE,
                    param = "Home Wi-Fi", param2 = "on", param3 = "bool"),
                RuleModel.Block(type = RuleModel.BLOCK_NOTIFY,
                    param = "Home Wi-Fi",
                    param2 = "The Home Wi-Fi value is on — rules can read it."),
            )),
            cooldownMinutes = 5,
        )

    /** The starter catalogue (label → factory). */
    fun starters(): List<Pair<String, () -> RuleModel.Rule>> = listOf(
        "Night wallpaper" to ::nightRule,
        "Charging" to ::chargingRule,
        "Home Wi-Fi" to ::wifiRule,
    )
}
