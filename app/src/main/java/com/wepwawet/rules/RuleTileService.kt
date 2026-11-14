package com.wepwawet.rules

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.launch

/**
 * v0.9.0: the Quick-Settings tile — "let me pin a rule to a Quick Settings
 * tile". The tile runs THE PINNED RULE (set from the rule editor's pin
 * action; SuiteRulesStore.pinRuleForTile). Active mode: the state is pushed
 * from our own process (onClick + onTileAdded), never polled.
 */
class RuleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val pinned = SuiteRulesStore.pinnedRuleForTile(applicationContext)
        if (pinned == null) {
            WepWawetLogger.appInfo("RuleTileService: no pinned rule — opening nothing")
            updateTile()
            return
        }
        val pr = SuiteRulesStore.readAll(applicationContext)
            .firstOrNull { it.playlistId == pinned.first }
        val rule = pr?.rules?.firstOrNull { it.id == pinned.second }
        if (rule == null) {
            WepWawetLogger.appWarn("RuleTileService: pinned rule no longer exists",
                "playlist" to pinned.first)
            updateTile()
            return
        }
        WepWawetLogger.appInfo("RuleTileService: tile tapped",
            "rule" to rule.name)
        // The engine runs in the app scope; the tile process IS the app
        // process, so no binding dance is needed.
        com.wepwawet.WepWawetApplication.appScope.launch {
            RuleEngine.fireRuleManual(applicationContext, pinned.first, rule)
        }
        updateTile()
    }

    private fun updateTile() {
        val pinned = SuiteRulesStore.pinnedRuleForTile(applicationContext)
        val tile = qsTile ?: return
        if (pinned != null) {
            val pr = SuiteRulesStore.readAll(applicationContext)
                .firstOrNull { it.playlistId == pinned.first }
            val rule = pr?.rules?.firstOrNull { it.id == pinned.second }
            if (rule != null) {
                tile.label = rule.name.take(14)
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = pr?.rules?.let { "run" } ?: ""
                tile.updateTile()
                return
            }
        }
        tile.label = "Rule"
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
