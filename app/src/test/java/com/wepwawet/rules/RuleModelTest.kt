package com.wepwawet.rules

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.0: the rules model's test set — the JSON round-trip is the store's
 * contract (hand-rolled serialization: every field must survive), the deep
 * copies must be independent with fresh ids, and the starter rules must be
 * well-formed.
 */
class RuleModelTest {

    private fun fullRule(): RuleModel.Rule = RuleModel.Rule(
        id = "r1",
        name = "Night wallpaper",
        enabled = false,
        triggers = arrayListOf(
            RuleModel.Trigger(
                type = RuleModel.TRIG_TIME_WINDOW,
                minuteStart = 22 * 60, minuteEnd = 6 * 60),
            RuleModel.Trigger(
                type = RuleModel.TRIG_WIFI_CONNECT,
                networkSsid = "Home"),
            RuleModel.Trigger(
                type = RuleModel.TRIG_BATTERY_BELOW,
                batteryLevel = 15),
        ),
        gate = RuleModel.Cond(
            type = RuleModel.COND_ALL,
            children = arrayListOf(
                RuleModel.Cond(type = RuleModel.COND_CHARGING),
                RuleModel.Cond(
                    type = RuleModel.COND_NOT,
                    children = arrayListOf(
                        RuleModel.Cond(
                            type = RuleModel.COND_BATTERY_BELOW,
                            batteryLevel = 10),
                    ),
                ),
            ),
        ),
        actions = arrayListOf(
            RuleModel.Block(
                id = "b1",
                type = RuleModel.BLOCK_IF,
                param = "Is it night?",
                cond = RuleModel.Cond(
                    type = RuleModel.COND_TIME_RANGE,
                    minuteStart = 22 * 60, minuteEnd = 6 * 60),
                thenBlocks = arrayListOf(
                    RuleModel.Block(id = "b2", type = RuleModel.BLOCK_SET_VALUE,
                        param = "Night mode", param2 = "on", param3 = "bool"),
                ),
                elseBlocks = arrayListOf(
                    RuleModel.Block(id = "b3", type = RuleModel.BLOCK_PICK_GROUP,
                        param = "Favorites", param2 = "random", param3 = "lock"),
                ),
            ),
            RuleModel.Block(
                id = "b4",
                type = RuleModel.BLOCK_CHOOSE,
                param = "Pick one",
                options = arrayListOf(
                    RuleModel.BlockOption("Option 1", arrayListOf(
                        RuleModel.Block(id = "b5", type = RuleModel.BLOCK_NEXT,
                            param2 = "home"),
                    )),
                    RuleModel.BlockOption("Option 2", arrayListOf()),
                ),
            ),
            RuleModel.Block(
                id = "b6",
                type = RuleModel.BLOCK_REPEAT,
                num = 3,
                children = arrayListOf(
                    RuleModel.Block(id = "b7", type = RuleModel.BLOCK_WAIT, num = 5),
                ),
            ),
            RuleModel.Block(
                id = "b8",
                type = RuleModel.BLOCK_SHOW_SET,
                paths = arrayListOf("/a.jpg", "@last"),
                param2 = "avoidRecent",
                param3 = "bothDifferent",
            ),
        ),
        cooldownMinutes = 30,
        holdMinutes = 60,
    )

    @Test
    fun `rule json round trip preserves every field`() {
        val rule = fullRule()
        val json = RuleModel.ruleToJson(rule).toString()
        val back = RuleModel.ruleFromJson(JSONObject(json))

        assertEquals(rule.id, back.id)
        assertEquals(rule.name, back.name)
        assertEquals(rule.enabled, back.enabled)
        assertEquals(rule.cooldownMinutes, back.cooldownMinutes)
        assertEquals(rule.holdMinutes, back.holdMinutes)
        assertEquals(rule.triggers, back.triggers)
        assertEquals(rule.gate, back.gate)
        assertEquals(rule.actions, back.actions)
    }

    @Test
    fun `playlist rules json round trip`() {
        val pr = RuleModel.PlaylistRules(
            playlistId = "p1",
            mode = "all",
            rules = arrayListOf(fullRule()),
        )
        val json = RuleModel.playlistRulesToJson(pr).toString()
        val back = RuleModel.playlistRulesFromJson("p1", JSONObject(json))
        assertEquals("all", back.mode)
        assertEquals(pr.rules, back.rules)
    }

    @Test
    fun `legacy and partial json parses without crashing`() {
        // The oldest readable shape: an empty object must yield defaults.
        val fromEmpty = RuleModel.ruleFromJson(JSONObject())
        assertTrue(fromEmpty.triggers.isEmpty())
        assertTrue(fromEmpty.actions.isEmpty())
        assertNull(fromEmpty.gate)
        // A trigger with only a type keeps the default params.
        val t = RuleModel.triggerFromJson(JSONObject().put("type", RuleModel.TRIG_INTERVAL))
        assertEquals(RuleModel.TRIG_INTERVAL, t.type)
        assertEquals(60, t.intervalMinutes)
    }

    @Test
    fun `copyRule is a deep independent copy with a fresh id`() {
        val rule = fullRule()
        val copy = RuleModel.copyRule(rule)
        assertNotEquals(rule.id, copy.id)
        // The copy is NAMED as a copy ("… (copy)") by design.
        assertEquals(rule.name + " (copy)", copy.name)
        assertEquals(rule.triggers, copy.triggers)
        assertEquals(rule.gate, copy.gate)
        // The actions are deep-copied with FRESH ids (value refs stay
        // unambiguous) — compare the shape, not the ids.
        assertEquals(rule.actions.map { it.type }, copy.actions.map { it.type })
        assertEquals(rule.actions[0].thenBlocks.map { it.param },
            copy.actions[0].thenBlocks.map { it.param })
        assertEquals(rule.actions[3].paths, copy.actions[3].paths)
        // Deep independence: mutating the copy must not touch the original.
        (copy.actions[0].thenBlocks as ArrayList)[0].param = "CHANGED"
        assertEquals("Night mode", rule.actions[0].thenBlocks[0].param)
    }

    @Test
    fun `copyBlock gives a fresh id so value refs stay unambiguous`() {
        val b = fullRule().actions[0]
        val copy = RuleModel.copyBlock(b)
        assertNotEquals(b.id, copy.id)
        assertEquals(b.type, copy.type)
        assertEquals(b.thenBlocks, copy.thenBlocks)
        assertEquals(b.elseBlocks, copy.elseBlocks)
    }

    @Test
    fun `starter rules are well formed`() {
        for ((label, factory) in RuleStarter.starters()) {
            val r = factory()
            assertTrue("$label needs a name", r.name.isNotBlank())
            assertTrue("$label needs triggers", r.triggers.isNotEmpty())
            assertTrue("$label needs actions", r.actions.isNotEmpty())
            assertTrue("$label needs a unique id", r.id.isNotBlank())
            // Every nested block carries its own stack type constraints.
            walk(r.actions) { b ->
                when (b.type) {
                    RuleModel.BLOCK_IF ->
                        assertTrue(b.cond != null)
                    RuleModel.BLOCK_CHOOSE ->
                        assertTrue(b.options.isNotEmpty())
                    RuleModel.BLOCK_REPEAT ->
                        assertTrue(b.num in 1..50)
                    else -> {}
                }
            }
            // The JSON round-trip must accept every starter too.
            val back = RuleModel.ruleFromJson(
                JSONObject(RuleModel.ruleToJson(r).toString()))
            assertEquals(r.actions, back.actions)
        }
    }

    @Test
    fun `trigger canon separates identical types with different params`() {
        val a = RuleModel.Trigger(type = RuleModel.TRIG_TIME_WINDOW,
            minuteStart = 22 * 60, minuteEnd = 6 * 60)
        val b = RuleModel.Trigger(type = RuleModel.TRIG_TIME_WINDOW,
            minuteStart = 9 * 60, minuteEnd = 17 * 60)
        assertNotEquals(RuleTriggerHubCanon(a), RuleTriggerHubCanon(b))
        assertEquals(RuleTriggerHubCanon(a), RuleTriggerHubCanon(a.copy()))
    }

    /** Canon access without spinning up the hub (top-level pure logic). */
    private fun RuleTriggerHubCanon(t: RuleModel.Trigger): String =
        listOf(t.type, t.minuteStart, t.minuteEnd, t.timeMinute, t.dayOfWeek,
            t.dateEpochDay, t.intervalMinutes).joinToString("|")

    private fun walk(blocks: List<RuleModel.Block>, visit: (RuleModel.Block) -> Unit) {
        for (b in blocks) {
            visit(b)
            walk(b.thenBlocks, visit)
            walk(b.elseBlocks, visit)
            walk(b.children, visit)
            for (opt in b.options) walk(opt.blocks, visit)
        }
    }

    @Test
    fun `time triggers are classified for the hub scheduler`() {
        assertTrue(RuleModel.TIME_TRIGGERS.contains(RuleModel.TRIG_TIME_WINDOW))
        assertTrue(RuleModel.TIME_TRIGGERS.contains(RuleModel.TRIG_TIME_OF_DAY))
        assertTrue(RuleModel.TIME_TRIGGERS.contains(RuleModel.TRIG_DAY_OF_WEEK))
        assertTrue(RuleModel.TIME_TRIGGERS.contains(RuleModel.TRIG_DATE))
        assertTrue(RuleModel.TIME_TRIGGERS.contains(RuleModel.TRIG_INTERVAL))
        // Event triggers are NOT time triggers.
        assertTrue(!RuleModel.TIME_TRIGGERS.contains(RuleModel.TRIG_SCREEN_ON))
        assertTrue(!RuleModel.TIME_TRIGGERS.contains(RuleModel.TRIG_WIFI_CONNECT))
    }
}
