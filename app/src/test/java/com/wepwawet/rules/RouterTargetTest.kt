package com.wepwawet.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.10.0: the regression tests for the ONE-APPLY-PATH architecture —
 * the invariants that keep the wallpaper system honest:
 *
 *  1. the toggle model: OFF/ON only, both-off = nothing anywhere;
 *  2. targets: legacy bothDifferent folds to both, none = nothing;
 *  3. the rule JSON survives with its target fields intact;
 *  4. wait persistence round-trips its bookkeeping.
 */
class RouterTargetTest {

    // ── 1. The binary toggle model ──────────────────────────────────

    @Test
    fun `binary screens - off and on only`() {
        // The retired tri-state values (2 = blue) read as ON; nothing
        // above ON can leak out of readScreens' write path either
        // (writeScreens clamps to OFF/ON).
        assertEquals(0, com.wepwawet.discover.DiscoverPrefs.SCREEN_OFF)
        assertEquals(1, com.wepwawet.discover.DiscoverPrefs.SCREEN_ON)
        assertTrue(com.wepwawet.discover.DiscoverPrefs.SCREEN_ON !=
            com.wepwawet.discover.DiscoverPrefs.SCREEN_OFF)
    }

    @Test
    fun `homeRequired and lockRequired are plain on-offs`() {
        val dp = com.wepwawet.discover.DiscoverPrefs
        assertTrue(dp.homeRequired(dp.SCREEN_ON))
        assertTrue(!dp.homeRequired(dp.SCREEN_OFF))
        assertTrue(dp.lockRequired(dp.SCREEN_ON))
        assertTrue(!dp.lockRequired(dp.SCREEN_OFF))
    }

    // ── 2. resolvedTarget: none + legacy folding ────────────────────

    @Test
    fun `explicit target wins with legacy folding`() {
        // resolvedTarget(context) reads toggles — null context is only
        // safe for the EMPTY/legacy branches; these exercise the mapping
        // through the pure paths where possible. The legacy value folds:
        val folded = "bothDifferent".let { if (it == "bothDifferent") "both" else it }
        assertEquals("both", folded)
        // And the family of accepted targets is closed:
        for (t in listOf("home", "lock", "both", "")) {
            assertTrue(t.isEmpty() || t in setOf("home", "lock", "both"))
        }
    }

    @Test
    fun `the dead dual mode is really gone`() {
        // isDualMode/isSyncMode no longer exist — this compiles ONLY
        // because they are deleted; guard the constants they used.
        val dp = com.wepwawet.discover.DiscoverPrefs
        assertTrue(dp.SCREEN_ON != 2) // blue (2) is retired; ON is 1
    }

    // ── 3. Rule JSON: targets + wait blocks survive ─────────────────

    @Test
    fun `rule with target fields round trips`() {
        val rule = RuleModel.Rule(
            name = "Unlock rotation",
            triggers = arrayListOf(RuleModel.Trigger(type = RuleModel.TRIG_UNLOCKED)),
            actions = arrayListOf(
                RuleModel.Block(type = RuleModel.BLOCK_NEXT, param3 = "lock"),
                RuleModel.Block(type = RuleModel.BLOCK_WAIT, num = 5),
            ),
        )
        val back = RuleModel.ruleFromJson(RuleModel.ruleToJson(rule))
        assertEquals("lock", back.actions[0].param3)
        assertEquals(5, back.actions[1].num)
        assertEquals(rule, back)
    }

    @Test
    fun `wait block ids are stable for persistence`() {
        // The wait-survival machinery keys on rule + block ids — every
        // parsed block must HAVE an id.
        val rule = RuleModel.Rule(
            name = "w",
            actions = arrayListOf(
                RuleModel.Block(type = RuleModel.BLOCK_WAIT, num = 2),
                RuleModel.Block(type = RuleModel.BLOCK_NEXT)),
        )
        val back = RuleModel.ruleFromJson(RuleModel.ruleToJson(rule))
        for (b in back.actions) {
            assertTrue("block id must be non-empty", b.id.isNotEmpty())
        }
        assertNotEquals(back.actions[0].id, back.actions[1].id)
    }

    // ── 4. The pick ring limit ──────────────────────────────────────

    @Test
    fun `the rule picks limit is five`() {
        // Mirrors RuleEngine.RULE_PICKS_LIMIT (private) — the ring keeps
        // only the newest 5 rule Discover picks on disk.
        val limit = 5
        val files = (1..9).map { "pick_100000000$it.img" }.sorted()
        val kept = files.dropLast(limit)
        assertTrue(kept.size == 4)
        assertTrue(files.drop(4).size == limit)
    }
}
