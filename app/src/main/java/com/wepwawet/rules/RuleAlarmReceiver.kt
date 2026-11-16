package com.wepwawet.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.launch

/**
 * v0.9.2 (THE WAKE-CAN'T-BE-TRUSTED FIX, from the 2026-11-14 review):
 * the time-trigger wake now uses AlarmManager.setAndAllowWhileIdle —
 * the old Handler.postDelayed schedule ran on the UPTIME clock and
 * STOPPED in deep sleep, so no interval / time-of-day / day trigger
 * could fire while the device dozed.
 *
 * This receiver is the alarm's target: a MANIFEST-declared receiver
 * (android:exported="false", no intent-filter — it is only ever fired by
 * our own explicit, package-scoped PendingIntent from
 * [RuleTriggerHub.scheduleNextWake], so nothing else can reach it).
 *
 * On fire: run every due time trigger (the hub's interval anchors still
 * guarantee at-most-once-per-interval), then re-arm the next wake. The
 * persisted "Every N" anchor (suite_rules_runtime's interval|… keys) is
 * untouched — an alarm that fires late never double-fires a rule.
 */
class RuleAlarmReceiver : BroadcastReceiver() {

    /** v0.8.28: a persisted Wait block re-armed for its remaining time. */
    companion object {
        const val ACTION_RESUME_WAIT = "com.wepwawet.rules.RESUME_WAIT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        WepWawetLogger.appInfo("RuleAlarmReceiver: wake fired",
            "at" to System.currentTimeMillis(),
            "action" to (intent.action ?: ""))
        val pending = goAsync()
        com.wepwawet.WepWawetApplication.appScope.launch {
            try {
                if (intent.action == ACTION_RESUME_WAIT) {
                    RuleEngine.onWaitAlarm(
                        appCtx,
                        intent.getStringExtra("playlist") ?: "",
                        intent.getStringExtra("rule") ?: "",
                        intent.getStringExtra("block") ?: "")
                } else {
                    RuleTriggerHub.onAlarmWake(appCtx)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
