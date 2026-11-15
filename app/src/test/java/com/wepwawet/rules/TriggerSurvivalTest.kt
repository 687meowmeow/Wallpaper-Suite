package com.wepwawet.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.27: the regression tests for the field-report failure classes —
 * the 0.8.26 log showed a "Screen turns on" rule whose stored triggers
 * were NOT screenOn/unlocked (an invisible mismatch: the chips looked
 * right, nothing fired). These tests pin the invariants that keep that
 * class of failure either impossible or visible:
 *
 *  1. EVERY trigger type survives the JSON round trip with its type and
 *     params intact (no silent type rewrites or drops in storage);
 *  2. the always-armed broadcast family contains only event types (never
 *     a time type — those ride the wake schedule);
 *  3. group-block markers round-trip their group name and can never be
 *     confused with a real file path.
 */
class TriggerSurvivalTest {

    private val ALL_TRIGGER_TYPES = listOf(
        RuleModel.TRIG_TIME_WINDOW, RuleModel.TRIG_TIME_OF_DAY,
        RuleModel.TRIG_DAY_OF_WEEK, RuleModel.TRIG_DATE, RuleModel.TRIG_INTERVAL,
        RuleModel.TRIG_SCREEN_ON, RuleModel.TRIG_SCREEN_OFF, RuleModel.TRIG_UNLOCKED,
        RuleModel.TRIG_CHARGER_IN, RuleModel.TRIG_CHARGER_OUT,
        RuleModel.TRIG_BATTERY_BELOW, RuleModel.TRIG_BATTERY_ABOVE,
        RuleModel.TRIG_SAVER_ON, RuleModel.TRIG_SAVER_OFF,
        RuleModel.TRIG_WIFI_CONNECT, RuleModel.TRIG_WIFI_DISCONNECT,
        RuleModel.TRIG_MOBILE_DATA, RuleModel.TRIG_HEADPHONES_IN,
        RuleModel.TRIG_HEADPHONES_OUT, RuleModel.TRIG_BT_DEVICE_IN,
        RuleModel.TRIG_BT_DEVICE_OUT, RuleModel.TRIG_ORIENTATION,
        RuleModel.TRIG_WALLPAPER_CHANGED, RuleModel.TRIG_WORKSHOP_DOWNLOADED,
        RuleModel.TRIG_WORKSHOP_UPDATE, RuleModel.TRIG_DISCOVER_QUEUE_LOW,
        RuleModel.TRIG_MANUAL_BUTTON, RuleModel.TRIG_NOTIFICATION_BUTTON,
        RuleModel.TRIG_QS_TILE, RuleModel.TRIG_FROM_RULE,
    )

    @Test
    fun `every trigger type survives a json round trip`() {
        for (type in ALL_TRIGGER_TYPES) {
            val t = RuleModel.Trigger(type = type)
            assertEquals(type, RuleModel.triggerFromJson(RuleModel.triggerToJson(t)).type)
        }
    }

    @Test
    fun `trigger params survive a json round trip`() {
        val t = RuleModel.Trigger(
            type = RuleModel.TRIG_WIFI_CONNECT,
            minuteStart = 480, minuteEnd = 1320, timeMinute = 600,
            dayOfWeek = 5, dateEpochDay = 20000L, intervalMinutes = 45,
            batteryLevel = 33, networkSsid = "Home 5G",
            deviceName = "WH-1000XM5", ruleId = "abc12345",
        )
        val back = RuleModel.triggerFromJson(RuleModel.triggerToJson(t))
        assertEquals(t, back)
    }

    @Test
    fun `a screenOn rule round trips whole — triggers intact, nothing dropped`() {
        // The exact shape the playlist sheet creates for "Screen turns on"
        // plus a second trigger: the rule must come back identical.
        val rule = RuleModel.Rule(
            name = "Screen turns on",
            triggers = arrayListOf(
                RuleModel.Trigger(type = RuleModel.TRIG_SCREEN_ON),
                RuleModel.Trigger(type = RuleModel.TRIG_UNLOCKED),
            ),
            actions = arrayListOf(RuleModel.Block(type = RuleModel.BLOCK_NEXT)),
        )
        val json = RuleModel.ruleToJson(rule)
        val back = RuleModel.ruleFromJson(json)
        assertEquals(listOf(RuleModel.TRIG_SCREEN_ON, RuleModel.TRIG_UNLOCKED),
            back.triggers.map { it.type })
        assertEquals(1, back.actions.size)
        assertEquals(RuleModel.BLOCK_NEXT, back.actions[0].type)
        assertEquals(rule, back)
    }

    @Test
    fun `the always armed family are event types - never wake scheduled`() {
        // If one of these were ever classified a TIME trigger, it would
        // depend on the alarm schedule instead of the live receiver.
        val family = listOf(
            RuleModel.TRIG_SCREEN_ON, RuleModel.TRIG_SCREEN_OFF,
            RuleModel.TRIG_UNLOCKED, RuleModel.TRIG_CHARGER_IN,
            RuleModel.TRIG_CHARGER_OUT, RuleModel.TRIG_SAVER_ON,
            RuleModel.TRIG_SAVER_OFF,
        )
        for (t in family) {
            assertFalse("family member $t must not be a time trigger",
                t in RuleModel.TIME_TRIGGERS)
        }
    }

    @Test
    fun `group block markers round trip their group and never look like paths`() {
        for (group in listOf("wall", "背景", "a group with  spaces")) {
            val marker = com.wepwawet.playlist.GroupBlocks.marker(group)
            assertTrue(com.wepwawet.playlist.GroupBlocks.isGroupBlock(marker))
            assertEquals(group, com.wepwawet.playlist.GroupBlocks.groupName(marker))
            assertFalse(com.wepwawet.playlist.GroupBlocks.isGroupBlock("/sdcard/$group.jpg"))
            assertFalse(com.wepwawet.playlist.GroupBlocks.isGroupBlock("content://x/$group"))
        }
    }

    @Test
    fun `two markers of the same group are distinct entries`() {
        val a = com.wepwawet.playlist.GroupBlocks.marker("wall")
        val b = com.wepwawet.playlist.GroupBlocks.marker("wall")
        assertNotEquals(a, b)
        assertEquals(com.wepwawet.playlist.GroupBlocks.groupName(a),
            com.wepwawet.playlist.GroupBlocks.groupName(b))
    }
}
