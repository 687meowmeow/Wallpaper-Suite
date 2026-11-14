package com.wepwawet.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wepwawet.rules.RuleEngine
import com.wepwawet.rules.RuleModel
import com.wepwawet.rules.SuiteRulesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * v0.9.0: THE RULE EDITOR — one scrollable screen per rule ("the new
 * features live inside the existing playlist editor so it feels like one
 * screen"; this overlay takes over from it).
 *
 * Top to bottom: the rule's name + on/off switch + a PLAY button that runs
 * it NOW (each block briefly HIGHLIGHTS as it executes; Choose blocks pop a
 * menu when started by hand), cooldown + hold + pin chips, TRIGGERS as
 * chips with a "＋" chip, the optional GATE (a condition tree), and the
 * ACTION STACK as cards — "＋" between blocks and at the bottom opens the
 * bottom sheet (search + blocks grouped into Wallpaper / Playlist / Engine
 * / Logic / Utility).
 *
 * Every change commits to SuiteRulesStore immediately (the store
 * re-aggregates the trigger hub); state mirrors a fresh read — the same
 * live-update discipline as the playlist editor (save() on a fresh copy).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(
    playlistId: String,
    ruleId: String,
    onExit: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    var rule by remember(ruleId) {
        mutableStateOf(SuiteRulesStore.readPlaylistRules(context, playlistId)
            .rules.firstOrNull { it.id == ruleId })
    }
    if (rule == null) {
        onExit()
        return
    }
    var running by remember { mutableStateOf(false) }
    var runResult by remember { mutableStateOf<String?>(null) }
    // blockId → highlight-until (the play run flashes cards as they run).
    var highlights by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var chooseMenu by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var chooseResolve by remember { mutableStateOf<((Int?) -> Unit)?>(null) }
    var showTriggerSheet by remember { mutableStateOf(false) }
    var showBlockSheet by remember { mutableStateOf(false) }
    var editingTriggerIdx by remember { mutableStateOf<Int?>(null) }
    var headerField by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun save(mutate: RuleModel.Rule.() -> Unit) {
        val fresh = SuiteRulesStore.readPlaylistRules(context, playlistId)
            .rules.firstOrNull { it.id == ruleId } ?: return
        fresh.mutate()
        val pr = SuiteRulesStore.readPlaylistRules(context, playlistId)
        val idx = pr.rules.indexOfFirst { it.id == ruleId }
        if (idx >= 0) pr.rules[idx] = fresh else pr.rules.add(fresh)
        SuiteRulesStore.writePlaylistRules(context, pr)
        rule = SuiteRulesStore.readPlaylistRules(context, playlistId)
            .rules.firstOrNull { it.id == ruleId }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this rule?") },
            text = { Text("The rule is removed. Its blocks and triggers go with it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val pr = SuiteRulesStore.readPlaylistRules(context, playlistId)
                    pr.rules.removeAll { it.id == ruleId }
                    SuiteRulesStore.writePlaylistRules(context, pr)
                    onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    when (headerField) {
        "cooldown" -> NumberDialog("Cooldown (minutes)", r0(rule)?.cooldownMinutes ?: 0, 0, 24 * 60,
            { v -> save { cooldownMinutes = v } }, { headerField = null })
        "hold" -> NumberDialog("Hold (minutes)", r0(rule)?.holdMinutes ?: 0, 0, 24 * 60,
            { v -> save { holdMinutes = v } }, { headerField = null })
    }

    Scaffold(
        topBar = {
            com.wepwawet.ui.components.SlimTopBar(
                title = "Rule — ${rule?.name ?: ""}",
                onBack = onExit,
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete rule")
                    }
                },
            )
        },
    ) { padding ->
        val r = rule ?: return@Scaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Name + enable + play ──
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = r.name,
                        onValueChange = { save { name = it.take(40) } },
                        singleLine = true,
                        label = { Text("Rule name") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = r.enabled, onCheckedChange = { save { enabled = it } })
                    IconButton(
                        onClick = {
                            if (running) return@IconButton
                            running = true
                            runResult = null
                            scope.launch {
                                val result = RuleEngine.fireRuleManual(
                                    context, playlistId, r,
                                    chooser = { title, options ->
                                        val picked = CompletableDeferred<Int?>()
                                        chooseMenu = title to options
                                        chooseResolve = { picked.complete(it) }
                                        val choice = picked.await()
                                        chooseMenu = null
                                        chooseResolve = null
                                        choice
                                    },
                                    onHighlight = { blockId ->
                                        highlights = highlights +
                                            (blockId to (System.currentTimeMillis() + 1400))
                                    },
                                )
                                runResult = result
                                running = false
                            }
                        },
                    ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Run now") }
                }
            }

            // Cooldown + hold + pins.
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { headerField = "cooldown" },
                        label = {
                            Text("Cooldown: ${if (r.cooldownMinutes > 0) "${r.cooldownMinutes} min" else "off"}")
                        },
                    )
                    FilterChip(
                        selected = false,
                        onClick = { headerField = "hold" },
                        label = {
                            Text("Hold: ${if (r.holdMinutes > 0) "${r.holdMinutes} min" else "off"}")
                        },
                    )
                    val pinnedTile = SuiteRulesStore.pinnedRuleForTile(context)
                    FilterChip(
                        selected = pinnedTile == (playlistId to ruleId),
                        onClick = {
                            if (pinnedTile == (playlistId to ruleId)) {
                                SuiteRulesStore.pinRuleForTile(context, null, null)
                            } else {
                                SuiteRulesStore.pinRuleForTile(context, playlistId, ruleId)
                            }
                            rule = SuiteRulesStore.readPlaylistRules(context, playlistId)
                                .rules.firstOrNull { it.id == ruleId }
                        },
                        label = { Text("Pin to tile") },
                    )
                    val pinnedNotif = SuiteRulesStore.notificationRule(context)
                    FilterChip(
                        selected = pinnedNotif == (playlistId to ruleId),
                        onClick = {
                            if (pinnedNotif == (playlistId to ruleId)) {
                                SuiteRulesStore.setNotificationRule(context, null, null)
                            } else {
                                SuiteRulesStore.setNotificationRule(context, playlistId, ruleId)
                                RuleEngine.buildRuleButtonNotification(context)
                            }
                            rule = SuiteRulesStore.readPlaylistRules(context, playlistId)
                                .rules.firstOrNull { it.id == ruleId }
                        },
                        label = { Text("Notification button") },
                    )
                }
            }

            if (r.lastFiredAt > 0 || runResult != null) {
                item {
                    Text(
                        listOfNotNull(
                            if (r.lastFiredAt > 0)
                                "Last fired ${relativeTime(r.lastFiredAt)} — ${r.lastResult}"
                            else null,
                            runResult?.let { "Just ran: $it" },
                        ).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ── Triggers ──
            item {
                Text("When…", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Any of these starts the automation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((ti, t) in r.triggers.withIndex()) {
                        FilterChip(
                            selected = false,
                            onClick = { editingTriggerIdx = ti },
                            label = { Text(triggerChipLabel(t)) },
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = { showTriggerSheet = true },
                        label = { Text("＋") },
                    )
                }
            }

            // ── Gate ──
            item {
                Text("Only if… (optional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Every condition here must be true for the automation to run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                val gateCond = r.gate
                if (gateCond == null) {
                    TextButton(onClick = {
                        save { gate = RuleModel.Cond(type = RuleModel.COND_ALL) }
                    }) { Text("＋ Add a condition") }
                } else {
                    CondCard(cond = gateCond) { newGate -> save { gate = newGate } }
                }
            }

            // ── Action stack ──
            item {
                Text("Do…", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Runs top to bottom. Drag a card by its handle to " +
                        "reorder; the ⋮ menu duplicates, disables or deletes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // v0.9.0: a "＋" affordance BETWEEN every pair of blocks ("the
            // plus at the bottom of the stack, or between blocks") + the
            // cards themselves.
            for ((idx, block) in r.actions.withIndex()) {
                if (idx > 0) {
                    item(key = "plus-${block.id}") { PlusBetweenButton { showBlockSheet = true } }
                }
                item(key = block.id) {
                    BlockCard(
                        block = block,
                        rule = r,
                        depth = 0,
                        highlights = highlights,
                        onEdit = { mutate -> save { actions[idx].let { mutate(it) } } },
                        onMove = { delta ->
                            save {
                                val i = actions.indexOfFirst { it.id == block.id }
                                val j = (i + delta).coerceIn(0, actions.size - 1)
                                if (i >= 0) {
                                    val moved = actions.removeAt(i)
                                    actions.add(j, moved)
                                }
                            }
                        },
                        onDelete = { save { actions.removeAll { it.id == block.id } } },
                        onDuplicate = {
                            val copy = RuleModel.copyBlock(block)
                            save {
                                val i = actions.indexOfFirst { it.id == block.id }
                                if (i >= 0) actions.add(i + 1, copy)
                            }
                        },
                    )
                }
            }
            item(key = "add-block-bottom") {
                Button(
                    onClick = { showBlockSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add a block")
                }
                Spacer(Modifier.size(48.dp))
            }
        }
    }

    // ── Sheets + dialogs ──
    if (showTriggerSheet) {
        TriggerTypeSheet(
            onDismiss = { showTriggerSheet = false },
            onPick = { type ->
                showTriggerSheet = false
                save { triggers.add(RuleModel.Trigger(type = type)) }
                editingTriggerIdx = rule?.triggers?.size?.minus(1) ?: -1
            },
        )
    }
    if (showBlockSheet) {
        val r = rule
        if (r != null) {
            BlockTypeSheet(
                onDismiss = { showBlockSheet = false },
                onPick = { type ->
                    showBlockSheet = false
                    save { actions.add(newBlock(type)) }
                },
            )
        }
    }
    editingTriggerIdx?.let { idx ->
        val current = rule?.triggers?.getOrNull(idx)
        if (current == null) {
            editingTriggerIdx = null
        } else {
            TriggerEditorDialog(
                trigger = current,
                onDismiss = { editingTriggerIdx = null },
                onDelete = {
                    save { triggers.removeAt(idx) }
                    editingTriggerIdx = null
                },
                onSave = { t ->
                    save { triggers[idx] = t }
                    editingTriggerIdx = null
                },
            )
        }
    }
    chooseMenu?.let { (title, options) ->
        AlertDialog(
            onDismissRequest = { chooseResolve?.invoke(null) },
            title = { Text(title) },
            text = {
                Column {
                    for ((i, opt) in options.withIndex()) {
                        Text(
                            opt,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { chooseResolve?.invoke(i) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { chooseResolve?.invoke(null) }) { Text("Cancel") }
            },
        )
    }
}

/** The tiny "＋" affordance between blocks. */
@Composable
internal fun PlusBetweenButton(onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "＋ add a block here",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** The trigger chip's label. */
internal fun triggerChipLabel(t: RuleModel.Trigger): String = when (t.type) {
    RuleModel.TRIG_TIME_WINDOW -> "Window ${fmtMin(t.minuteStart)}–${fmtMin(t.minuteEnd)}"
    RuleModel.TRIG_TIME_OF_DAY -> "At ${fmtMin(t.timeMinute)}"
    RuleModel.TRIG_DAY_OF_WEEK ->
        "On " + (listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            .getOrElse(t.dayOfWeek) { "any day" })
    RuleModel.TRIG_DATE -> "On a date"
    RuleModel.TRIG_INTERVAL -> "Every ${t.intervalMinutes} min"
    RuleModel.TRIG_BATTERY_BELOW -> "Battery < ${t.batteryLevel}%"
    RuleModel.TRIG_BATTERY_ABOVE -> "Battery > ${t.batteryLevel}%"
    RuleModel.TRIG_WIFI_CONNECT ->
        if (t.networkSsid.isEmpty()) "Wi-Fi connects" else "Joins ${t.networkSsid}"
    RuleModel.TRIG_WIFI_DISCONNECT ->
        if (t.networkSsid.isEmpty()) "Wi-Fi disconnects" else "Leaves ${t.networkSsid}"
    RuleModel.TRIG_BT_DEVICE_IN ->
        if (t.deviceName.isEmpty()) "Device connects" else "${t.deviceName} connects"
    RuleModel.TRIG_BT_DEVICE_OUT ->
        if (t.deviceName.isEmpty()) "Device disconnects" else "${t.deviceName} disconnects"
    RuleModel.TRIG_FROM_RULE -> "Started by a rule"
    else -> triggerLabel(t.type)
}

/** Safe view of the (nullable) rule state for header dialogs. */
private fun r0(rule: RuleModel.Rule?): RuleModel.Rule =
    rule ?: RuleModel.Rule()

internal fun fmtMin(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
