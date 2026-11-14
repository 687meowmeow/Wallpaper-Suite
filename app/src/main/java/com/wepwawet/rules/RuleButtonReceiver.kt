package com.wepwawet.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wepwawet.WepWawetApplication
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.launch

/**
 * v0.9.0: the NOTIFICATION BUTTON receiver — the persistent "rule button"
 * notification's Run action lands here and fires the chosen rule.
 * (Explicit broadcast — registered at runtime by RuleTriggerHub.start, so
 * no manifest receiver is needed while the app process lives; the pairing
 * service keeps it alive.)
 */
class RuleButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val playlistId = intent.getStringExtra("playlistId") ?: return
        val ruleId = intent.getStringExtra("ruleId") ?: return
        val appCtx = context.applicationContext
        WepWawetLogger.appInfo("RuleButtonReceiver: rule button tapped",
            "playlist" to playlistId, "rule" to ruleId)
        val pending = goAsync()
        WepWawetApplication.appScope.launch {
            try {
                val pr = SuiteRulesStore.readPlaylistRules(appCtx, playlistId)
                val rule = pr.rules.firstOrNull { it.id == ruleId } ?: return@launch
                RuleEngine.fireRuleManual(appCtx, playlistId, rule)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * v0.9.0: internal app events the engine reacts to (Workshop downloads,
 * Discover queue-low, our own wallpaper applies) — every internal emitter
 * posts through here.
 */
object RuleEvents {
    fun post(context: Context, type: String, extra: Map<String, String> = emptyMap()) {
        RuleTriggerHub.dispatch(context, type, extra)
    }
}
