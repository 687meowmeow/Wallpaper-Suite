package com.wepwawet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.rules.RuleEngine
import com.wepwawet.rules.RuleModel
import com.wepwawet.rules.SuiteRulesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.9.0: the rule editor's shared machinery — trigger/block type sheets,
 * the BLOCK CARD (icon + one-line summary + settings editable by tapping
 * fields, move / long-press-duplicate / swipe-delete, nested stacks with a
 * colored rail, collapse), the GATE's condition-tree card, and the small
 * settings-dialog framework (choice / number / text / time / wallpaper
 * picker — everything with taps, value chips for earlier blocks' values).
 */

// ══ The settings framework ═══════════════════════════════════════════

/** A tappable settings field on a card. */
@Composable
internal fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A small choice dialog (dropdown-equivalent, taps only). */
@Composable
internal fun ChoiceDialog(
    title: String,
    options: List<String>,
    current: String,
    onPick: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((i, opt) in options.withIndex()) {
                    val selected = opt == current
                    Text(
                        opt,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(i); onDismiss() }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A number dialog: − value + (taps only). */
/**
 * v0.9.0 redesign: FREE-TYPED number dialog — type any value; [min] is
 * enforced (the interval trigger's minimum is 1 minute), there is NO
 * maximum (the old 5..24*60 slider cap on "Every N minutes" is gone).
 */
@Composable
internal fun NumberDialog(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var v by remember { mutableStateOf(value.coerceAtLeast(min).toString()) }
    val parsed = v.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = v,
                    onValueChange = { raw ->
                        v = raw.filter { it.isDigit() || it == '-' }.take(9)
                    },
                    singleLine = true,
                    label = { Text("Number (at least $min)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (parsed != null && parsed < min) {
                    Text(
                        "The minimum is $min.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val n = parsed ?: return@TextButton
                    if (n >= min) {
                        onChange(n)
                        onDismiss()
                    }
                },
                enabled = parsed != null && parsed >= min,
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A text dialog — with VALUE CHIPS from earlier blocks ("@last" etc.). */
@Composable
internal fun TextDialog(
    title: String,
    hint: String,
    initial: String,
    chips: List<Pair<String, String>> = emptyList(),
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = v,
                    onValueChange = { v = it.take(120) },
                    singleLine = true,
                    label = { Text(hint) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Insert a value from an earlier block:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        for ((label, ref) in chips) {
                            FilterChip(
                                selected = false,
                                onClick = { v = ref },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChange(v); onDismiss() }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A minute-of-day dialog (taps only: −15/−1 +1/+15). */
@Composable
internal fun MinuteDialog(
    title: String,
    minute: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var v by remember { mutableStateOf(minute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(fmtMin(v), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { v = ((v - 15) % 1440 + 1440) % 1440 }) { Text("−15") }
                    TextButton(onClick = { v = ((v - 1) % 1440 + 1440) % 1440 }) { Text("−1") }
                    TextButton(onClick = { v = (v + 1) % 1440 }) { Text("+1") }
                    TextButton(onClick = { v = (v + 15) % 1440 }) { Text("+15") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChange(v); onDismiss() }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The cooldown/hold chip dialog (from the rule header). */
@Composable
internal fun editNumberDialog(
    @Suppress("UNUSED_PARAMETER") context: android.content.Context,
    current: Int,
    title: String,
    onChange: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(true) }
    if (open) {
        NumberDialog(
            title = title,
            value = current,
            min = 0,
            max = 24 * 60,
            onChange = onChange,
            onDismiss = { open = false },
        )
    }
}

// ══ Trigger UI ═════════════════════════════════════════════════════

private val TRIGGER_CATEGORIES = listOf(
    "Time" to listOf(
        RuleModel.TRIG_TIME_WINDOW, RuleModel.TRIG_TIME_OF_DAY, RuleModel.TRIG_DAY_OF_WEEK,
        RuleModel.TRIG_DATE, RuleModel.TRIG_INTERVAL,
    ),
    "Phone" to listOf(
        RuleModel.TRIG_SCREEN_ON, RuleModel.TRIG_SCREEN_OFF, RuleModel.TRIG_UNLOCKED,
        RuleModel.TRIG_CHARGER_IN, RuleModel.TRIG_CHARGER_OUT,
        RuleModel.TRIG_BATTERY_BELOW, RuleModel.TRIG_BATTERY_ABOVE,
        RuleModel.TRIG_SAVER_ON, RuleModel.TRIG_SAVER_OFF,
        RuleModel.TRIG_WIFI_CONNECT, RuleModel.TRIG_WIFI_DISCONNECT,
        RuleModel.TRIG_MOBILE_DATA,
        RuleModel.TRIG_HEADPHONES_IN, RuleModel.TRIG_HEADPHONES_OUT,
        RuleModel.TRIG_BT_DEVICE_IN, RuleModel.TRIG_BT_DEVICE_OUT,
        RuleModel.TRIG_ORIENTATION,
    ),
    "Wallpaper" to listOf(
        RuleModel.TRIG_WALLPAPER_CHANGED, RuleModel.TRIG_WORKSHOP_DOWNLOADED,
        RuleModel.TRIG_WORKSHOP_UPDATE, RuleModel.TRIG_DISCOVER_QUEUE_LOW,
    ),
    "Manual" to listOf(
        RuleModel.TRIG_MANUAL_BUTTON, RuleModel.TRIG_NOTIFICATION_BUTTON,
        RuleModel.TRIG_QS_TILE, RuleModel.TRIG_FROM_RULE,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TriggerTypeSheet(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                label = { Text("Search triggers") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            val q = search.trim().lowercase()
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                for ((cat, types) in TRIGGER_CATEGORIES) {
                    val matching = types.filter {
                        q.isEmpty() || triggerLabel(it).lowercase().contains(q)
                    }
                    if (matching.isEmpty()) continue
                    Text(
                        cat,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    for (t in matching) {
                        Text(
                            triggerLabel(t),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(t) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * v0.9.0 redesign: the interval trigger's editor — a free-typed number
 * plus a unit (minutes / hours / days). No presets, no maximum, minimum
 * 1 minute.
 */
@Composable
internal fun IntervalDialog(
    minutes: Int,
    onDismiss: () -> Unit,
    onChange: (Int) -> Unit,
) {
    var unit by remember { mutableStateOf("min") }
    var v by remember { mutableStateOf("1") }
    val parsed = v.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Every…") },
        text = {
            Column {
                OutlinedTextField(
                    value = v,
                    onValueChange = { raw -> v = raw.filter { it.isDigit() }.take(9) },
                    singleLine = true,
                    label = { Text("Number (at least 1)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = unit == "min",
                        onClick = { unit = "min" },
                        shape = SegmentedButtonDefaults.itemShape(0, 3),
                    ) { Text("Minutes") }
                    SegmentedButton(
                        selected = unit == "hours",
                        onClick = { unit = "hours" },
                        shape = SegmentedButtonDefaults.itemShape(1, 3),
                    ) { Text("Hours") }
                    SegmentedButton(
                        selected = unit == "days",
                        onClick = { unit = "days" },
                        shape = SegmentedButtonDefaults.itemShape(2, 3),
                    ) { Text("Days") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val n = parsed ?: return@TextButton
                    if (n < 1) return@TextButton
                    onChange(when (unit) {
                        "hours" -> n * 60
                        "days" -> n * 60 * 24
                        else -> n
                    })
                    onDismiss()
                },
                enabled = parsed != null && parsed >= 1,
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A trigger's own editor — the params its type needs, with taps only. */
@Composable
internal fun TriggerEditorDialog(
    trigger: RuleModel.Trigger,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (RuleModel.Trigger) -> Unit,
) {
    var t by remember { mutableStateOf(trigger.copy()) }
    // Which field's dialog is open (one at a time).
    var field by remember { mutableStateOf<String?>(null) }

    val close = { field = null }
    when (field) {
        "start" -> MinuteDialog("Window start", t.minuteStart, { t = t.copy(minuteStart = it); field = null }, close)
        "end" -> MinuteDialog("Window end", t.minuteEnd, { t = t.copy(minuteEnd = it); field = null }, close)
        "time" -> MinuteDialog("Time of day", t.timeMinute, { t = t.copy(timeMinute = it); field = null }, close)
        "day" -> ChoiceDialog("Day of week",
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "Any day"),
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "Any day")
                .getOrElse(t.dayOfWeek) { "Any day" },
            { idx -> t = t.copy(dayOfWeek = if (idx == 7) -1 else idx) }, close)
        // v0.9.0 redesign: free-typed number plus a unit — no presets, no
        // maximum, minimum 1 minute.
        "interval" -> IntervalDialog(t.intervalMinutes, close) { minutes ->
            t = t.copy(intervalMinutes = minutes)
        }
        "battery" -> NumberDialog("Battery level (%)", t.batteryLevel, 1, 100, { t = t.copy(batteryLevel = it) }, close)
        "ssid" -> TextDialog("Wi-Fi network", "Network name (blank = any network)", t.networkSsid, onDismiss = close, onChange = { t = t.copy(networkSsid = it) })
        "device" -> TextDialog("Bluetooth device", "Device name (blank = any device)", t.deviceName, onDismiss = close, onChange = { t = t.copy(deviceName = it) })
        "date" -> TextDialog("Date", "YYYY-MM-DD (or blank for today)", t.dateEpochDay.let { d -> if (d > 0) java.time.LocalDate.ofEpochDay(d).toString() else "" }, onDismiss = close, onChange = { s -> t = t.copy(dateEpochDay = runCatching { java.time.LocalDate.parse(s).toEpochDay() }
                .onFailure {
                    WepWawetLogger.appWarn("RuleEditorBlocks: parseDate — the typed date did not parse",
                        "input" to s.take(20),
                        "exception" to it.javaClass.simpleName)
                }
                .getOrDefault(0L)) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(triggerLabel(t.type)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (t.type) {
                    RuleModel.TRIG_TIME_WINDOW -> {
                        SettingRow("Start", fmtMin(t.minuteStart)) { field = "start" }
                        SettingRow("End", fmtMin(t.minuteEnd)) { field = "end" }
                    }
                    RuleModel.TRIG_TIME_OF_DAY -> SettingRow("Time", fmtMin(t.timeMinute)) { field = "time" }
                    RuleModel.TRIG_DAY_OF_WEEK -> SettingRow("Day",
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "Any day")
                            .getOrElse(t.dayOfWeek) { "Any day" }) { field = "day" }
                    RuleModel.TRIG_DATE -> SettingRow("Date",
                        if (t.dateEpochDay > 0) java.time.LocalDate.ofEpochDay(t.dateEpochDay).toString() else "not set") { field = "date" }
                    RuleModel.TRIG_INTERVAL -> SettingRow("Every", "${t.intervalMinutes} minutes") { field = "interval" }
                    RuleModel.TRIG_BATTERY_BELOW, RuleModel.TRIG_BATTERY_ABOVE ->
                        SettingRow("Level", "${t.batteryLevel}%") { field = "battery" }
                    RuleModel.TRIG_WIFI_CONNECT, RuleModel.TRIG_WIFI_DISCONNECT ->
                        SettingRow("Network", t.networkSsid.ifEmpty { "Any network" }) { field = "ssid" }
                    RuleModel.TRIG_BT_DEVICE_IN, RuleModel.TRIG_BT_DEVICE_OUT ->
                        SettingRow("Device", t.deviceName.ifEmpty { "Any device" }) { field = "device" }
                    else -> Text(
                        "No settings — this trigger is always the same.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = { onSave(t) }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


// ══ The block type sheet (search + categories) ══════════════════════

internal val BLOCK_CATEGORIES: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Wallpaper" to listOf(
        RuleModel.BLOCK_NEXT to "Next wallpaper",
        RuleModel.BLOCK_SHOW to "Show a wallpaper",
        RuleModel.BLOCK_SHOW_SET to "Show one from a set",
        RuleModel.BLOCK_RANDOM to "Random wallpaper",
        RuleModel.BLOCK_PICK_GROUP to "Random from a group",
        RuleModel.BLOCK_LIMIT to "Only use these wallpapers while…",
        RuleModel.BLOCK_PICK_PLAYLIST to "Pick from a playlist",
        RuleModel.BLOCK_PULL_DISCOVER to "Pull a fresh Discover image",
        RuleModel.BLOCK_PREV to "Go to previous",
        RuleModel.BLOCK_SHUFFLE to "Shuffle",
    ),
    "Playlist" to listOf(
        RuleModel.BLOCK_PAUSE_ROTATION to "Pause rotation",
        RuleModel.BLOCK_RESUME_ROTATION to "Resume rotation",
        RuleModel.BLOCK_RESTART_PLAYLIST to "Restart the playlist",
        RuleModel.BLOCK_HOLD_CURRENT to "Hold current wallpaper",
        RuleModel.BLOCK_SWITCH_PLAYLIST to "Switch playlist",
    ),
    "Engine" to listOf(
        RuleModel.BLOCK_SET_FPS to "Set the FPS limit",
        RuleModel.BLOCK_AUDIO_MUTE to "Mute or unmute audio effects",
        RuleModel.BLOCK_RENDERER_PAUSE to "Pause or resume the renderer",
        RuleModel.BLOCK_APPLY_PRESET to "Apply a saved property preset",
    ),
    "Logic" to listOf(
        RuleModel.BLOCK_IF to "If",
        RuleModel.BLOCK_CHOOSE to "Choose (asks when run by hand)",
        RuleModel.BLOCK_REPEAT to "Repeat",
        RuleModel.BLOCK_WAIT to "Wait",
        RuleModel.BLOCK_STOP to "Stop",
        RuleModel.BLOCK_RUN_RULE to "Run another rule",
    ),
    "Values" to listOf(
        RuleModel.BLOCK_SET_VALUE to "Set a named value",
        RuleModel.BLOCK_ADD_VALUE to "Add to a named value",
        RuleModel.BLOCK_CLEAR_VALUE to "Clear a named value",
    ),
    "Utility" to listOf(
        RuleModel.BLOCK_NOTIFY to "Show a notification",
        RuleModel.BLOCK_DISCOVER_REFRESH to "Trigger a Discover refresh",
        RuleModel.BLOCK_WORKSHOP_DOWNLOAD to "Start a Workshop download",
        RuleModel.BLOCK_SAVE_TO_FOLDER to "Save the last pick to a group folder",
        RuleModel.BLOCK_ADD_TO_PLAYLIST to "Add the last pick to a playlist",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlockTypeSheet(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                label = { Text("Search blocks") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            val q = search.trim().lowercase()
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                for ((cat, blocks) in BLOCK_CATEGORIES) {
                    val matching = blocks.filter {
                        q.isEmpty() || it.second.lowercase().contains(q)
                    }
                    if (matching.isEmpty()) continue
                    Text(
                        cat,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    for ((type, label) in matching) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(type) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Icon(
                                blockIcon(type),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    blockName(type),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** The SHORT name of a block type (the sheet rows show icon + name + the
 *  category label as the description). */
internal fun blockName(type: String): String = when (type) {
    RuleModel.BLOCK_SHOW -> "Show wallpaper"
    RuleModel.BLOCK_SHOW_SET -> "Show from set"
    RuleModel.BLOCK_PICK_GROUP -> "Random from group"
    RuleModel.BLOCK_PICK_PLAYLIST -> "From playlist"
    RuleModel.BLOCK_RANDOM -> "Random wallpaper"
    RuleModel.BLOCK_PULL_DISCOVER -> "Discover pull"
    RuleModel.BLOCK_NEXT -> "Next wallpaper"
    RuleModel.BLOCK_PREV -> "Previous"
    RuleModel.BLOCK_SHUFFLE -> "Shuffle"
    RuleModel.BLOCK_LIMIT -> "Limit"
    RuleModel.BLOCK_PAUSE_ROTATION -> "Pause rotation"
    RuleModel.BLOCK_RESUME_ROTATION -> "Resume rotation"
    RuleModel.BLOCK_RESTART_PLAYLIST -> "Restart playlist"
    RuleModel.BLOCK_HOLD_CURRENT -> "Hold current"
    RuleModel.BLOCK_SWITCH_PLAYLIST -> "Switch playlist"
    RuleModel.BLOCK_SET_FPS -> "Set FPS"
    RuleModel.BLOCK_AUDIO_MUTE -> "Audio mute"
    RuleModel.BLOCK_RENDERER_PAUSE -> "Renderer pause"
    RuleModel.BLOCK_APPLY_PRESET -> "Apply preset"
    RuleModel.BLOCK_IF -> "If"
    RuleModel.BLOCK_CHOOSE -> "Choose"
    RuleModel.BLOCK_REPEAT -> "Repeat"
    RuleModel.BLOCK_WAIT -> "Wait"
    RuleModel.BLOCK_STOP -> "Stop"
    RuleModel.BLOCK_RUN_RULE -> "Run rule"
    RuleModel.BLOCK_NOTIFY -> "Notify"
    RuleModel.BLOCK_DISCOVER_REFRESH -> "Discover refresh"
    RuleModel.BLOCK_WORKSHOP_DOWNLOAD -> "Workshop download"
    RuleModel.BLOCK_SET_VALUE -> "Set value"
    RuleModel.BLOCK_ADD_VALUE -> "Add to value"
    RuleModel.BLOCK_CLEAR_VALUE -> "Clear value"
    RuleModel.BLOCK_SAVE_TO_FOLDER -> "Save to folder"
    RuleModel.BLOCK_ADD_TO_PLAYLIST -> "Add to playlist"
    else -> type
}

/** Fresh sensible defaults for a new block of [type]. */
internal fun newBlock(type: String): RuleModel.Block = when (type) {
    RuleModel.BLOCK_IF -> RuleModel.Block(type = type, param = "Condition",
        cond = RuleModel.Cond(type = RuleModel.COND_ALL),
        thenBlocks = ArrayList(), elseBlocks = ArrayList())
    RuleModel.BLOCK_CHOOSE -> RuleModel.Block(type = type, param = "Pick one",
        options = ArrayList(listOf(
            RuleModel.BlockOption(label = "Option 1"),
            RuleModel.BlockOption(label = "Option 2"),
        )))
    RuleModel.BLOCK_REPEAT -> RuleModel.Block(type = type, num = 2, children = ArrayList())
    RuleModel.BLOCK_WAIT -> RuleModel.Block(type = type, num = 5)
    RuleModel.BLOCK_HOLD_CURRENT -> RuleModel.Block(type = type, num = 60)
    RuleModel.BLOCK_SET_FPS -> RuleModel.Block(type = type, num = 30)
    RuleModel.BLOCK_RANDOM -> RuleModel.Block(type = type, param = "library",
        param2 = "random", param3 = "")
    RuleModel.BLOCK_PICK_GROUP, RuleModel.BLOCK_SHOW_SET ->
        RuleModel.Block(type = type, param2 = "random", param3 = "")
    RuleModel.BLOCK_SHOW -> RuleModel.Block(type = type, param = "", param3 = "")
    // v0.9.0 redesign: the Limit scope — starts with no wallpapers; the
    // editor's picker (requireNonEmpty) refuses to save an empty scope.
    RuleModel.BLOCK_LIMIT -> RuleModel.Block(type = type, paths = ArrayList())
    else -> RuleModel.Block(type = type)
}

@Composable
private fun blockIcon(type: String): ImageVector = when (type) {
    RuleModel.BLOCK_LIMIT -> Icons.Filled.FilterAlt
    in listOf(RuleModel.BLOCK_SHOW, RuleModel.BLOCK_SHOW_SET, RuleModel.BLOCK_PICK_GROUP,
        RuleModel.BLOCK_PICK_PLAYLIST, RuleModel.BLOCK_RANDOM, RuleModel.BLOCK_PULL_DISCOVER,
        RuleModel.BLOCK_NEXT, RuleModel.BLOCK_PREV, RuleModel.BLOCK_SHUFFLE) ->
        Icons.Filled.SwapVert
    in listOf(RuleModel.BLOCK_PAUSE_ROTATION, RuleModel.BLOCK_RESUME_ROTATION,
        RuleModel.BLOCK_RESTART_PLAYLIST, RuleModel.BLOCK_HOLD_CURRENT,
        RuleModel.BLOCK_SWITCH_PLAYLIST, RuleModel.BLOCK_IF, RuleModel.BLOCK_CHOOSE,
        RuleModel.BLOCK_REPEAT, RuleModel.BLOCK_WAIT, RuleModel.BLOCK_STOP,
        RuleModel.BLOCK_RUN_RULE) -> Icons.Filled.QueueMusic
    in listOf(RuleModel.BLOCK_SET_FPS, RuleModel.BLOCK_AUDIO_MUTE,
        RuleModel.BLOCK_RENDERER_PAUSE, RuleModel.BLOCK_APPLY_PRESET) -> Icons.Filled.Settings
    in listOf(RuleModel.BLOCK_SET_VALUE, RuleModel.BLOCK_ADD_VALUE,
        RuleModel.BLOCK_CLEAR_VALUE, RuleModel.BLOCK_SAVE_TO_FOLDER,
        RuleModel.BLOCK_ADD_TO_PLAYLIST) -> Icons.Filled.PlaylistAdd
    else -> Icons.Filled.Notifications
}

// ── Label helpers (the card's fields) ─────────────────────────────────

private fun targetLabel(block: RuleModel.Block): String = when (block.param3) {
    "" -> "Follow the app toggles"
    "home" -> "Home screen"
    "lock" -> "Lock screen"
    "both" -> "Both (same image)"
    "bothDifferent" -> "Both (different images)"
    else -> block.param3
}

private fun pickModeLabel(block: RuleModel.Block): String = when (block.param2) {
    "inOrder" -> "In order"
    "newest" -> "Newest"
    "avoidRecent" -> "Avoid the most recently shown"
    else -> "Random"
}

private fun sourceLabel(v: String): String = when (v) {
    "group" -> "A group"
    "playlist" -> "A playlist"
    "workshop" -> "The Workshop"
    "discover" -> "Discover"
    else -> "The library"
}

private fun valueKindLabel(v: String): String = when (v) {
    "bool" -> "On/off"
    "number" -> "Number"
    else -> "Text"
}

private fun valueSetLabel(block: RuleModel.Block): String = when (block.param3) {
    "bool" -> if (block.param2 == "on" || block.param2 == "true") "on" else "off"
    "number" -> block.num.toString()
    else -> block.param2
}

private fun displayPath(path: String): String = when {
    path.isEmpty() -> "(pick a wallpaper)"
    path == "@last" -> "the last pick"
    path.startsWith("@block:") -> "an earlier block's pick"
    else -> path.substringAfterLast('/')
}

internal fun playlistNameOf(context: android.content.Context, id: String): String {
    if (id.isEmpty()) return "This playlist"
    return com.wepwawet.playlist.SuitePlaylistStore.readAll(context)
        .firstOrNull { it.id == id }?.name ?: "(deleted playlist)"
}

/** The value chips an earlier block offers ("@last" + earlier pick blocks). */
private fun valueChips(rule: RuleModel.Rule): List<Pair<String, String>> {
    val chips = ArrayList<Pair<String, String>>()
    chips.add("Last pick" to "@last")
    for (b in rule.actions) {
        if (b.type in listOf(
                RuleModel.BLOCK_SHOW, RuleModel.BLOCK_SHOW_SET, RuleModel.BLOCK_PICK_GROUP,
                RuleModel.BLOCK_PICK_PLAYLIST, RuleModel.BLOCK_RANDOM,
                RuleModel.BLOCK_PULL_DISCOVER, RuleModel.BLOCK_NEXT,
                RuleModel.BLOCK_PREV, RuleModel.BLOCK_SHUFFLE)) {
            chips.add("Pick by \"${blockSummaryPlain(b)}\"" to "@block:${b.id}")
        }
    }
    return chips
}

/** A short plain name for a value-producing block (the chip label). */
private fun blockSummaryPlain(b: RuleModel.Block): String = when (b.type) {
    RuleModel.BLOCK_LIMIT -> "only ${b.paths.size} wallpapers"
    RuleModel.BLOCK_SHOW -> displayPath(b.param)
    RuleModel.BLOCK_SHOW_SET -> "from a set of ${b.paths.size}"
    RuleModel.BLOCK_PICK_GROUP -> "from ${b.param.ifEmpty { "a group" }}"
    RuleModel.BLOCK_PICK_PLAYLIST -> "from a playlist"
    RuleModel.BLOCK_RANDOM -> "random from ${sourceLabel(b.param)}"
    RuleModel.BLOCK_PULL_DISCOVER -> "a fresh Discover image"
    RuleModel.BLOCK_NEXT -> "next"
    RuleModel.BLOCK_PREV -> "previous"
    RuleModel.BLOCK_SHUFFLE -> "shuffle"
    else -> b.type
}

// ══ The block card + nested stacks ══════════════════════════════════

/**
 * One recursive inner stack (Then / Otherwise / Repeat body / an option's
 * blocks): the colored RAIL on the left, then the blocks with a "＋" at the
 * end. Every inner block is a full [BlockCard] — nested Ifs inside Ifs work.
 */
@Composable
private fun InnerStack(
    label: String,
    blocks: List<RuleModel.Block>,
    rule: RuleModel.Rule,
    depth: Int,
    highlights: Map<String, Long>,
    setBlocks: (List<RuleModel.Block>) -> Unit,
    railColor: Color = MaterialTheme.colorScheme.primary,
) {
    var showSheet by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            // The colored rail.
            Box(
                Modifier
                    .width(3.dp)
                    .height((24 * (blocks.size + 1)).coerceAtLeast(24).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(railColor.copy(alpha = 0.45f)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((idx, b) in blocks.withIndex()) {
                    BlockCard(
                        block = b,
                        rule = rule,
                        depth = depth,
                        highlights = highlights,
                        onEdit = { mutate ->
                            val list = blocks.toMutableList()
                            mutate(list[idx])
                            setBlocks(list)
                        },
                        onMove = { delta ->
                            val list = blocks.toMutableList()
                            val j = (idx + delta).coerceIn(0, list.size - 1)
                            val moved = list.removeAt(idx)
                            list.add(j, moved)
                            setBlocks(list)
                        },
                        onDelete = { setBlocks(blocks.filter { it.id != b.id }) },
                        onDuplicate = {
                            val copy = RuleModel.copyBlock(b)
                            val list = blocks.toMutableList()
                            list.add(idx + 1, copy)
                            setBlocks(list)
                        },
                    )
                }
                TextButton(onClick = { showSheet = true }) {
                    Text("＋ add a block", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    if (showSheet) {
        BlockTypeSheet(
            onDismiss = { showSheet = false },
            onPick = { type ->
                showSheet = false
                setBlocks(blocks + newBlock(type))
            },
        )
    }
}

/**
 * The BLOCK CARD: icon + a one-line plain-language summary + settings
 * editable by tapping fields right on the card; move up/down,
 * long-press-drag duplicates, swipe right deletes; container blocks (If /
 * Repeat / Choose) show inner stacks with a rail and collapse. The engine
 * highlights it in the run flow.
 */
@Composable
internal fun BlockCard(
    block: RuleModel.Block,
    rule: RuleModel.Rule,
    depth: Int,
    highlights: Map<String, Long>,
    onEdit: ((RuleModel.Block) -> Unit) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    val context = LocalContext.current
    var field by remember(block.id) { mutableStateOf<String?>(null) }
    var collapsed by remember(block.id) { mutableStateOf(block.collapsed) }
    // The swipe-right delete gesture state (LOCAL per card).
    var swipeX by remember(block.id) { mutableStateOf(0f) }
    val highlight = (highlights[block.id] ?: 0) > System.currentTimeMillis()
    val close = { field = null }

    // ── The field dialogs ──
    when (field) {
        // v0.9.0 redesign: the target lives in param3 (the old dialog
        // wrote param2, clobbering the pick mode), and the DEFAULT is
        // "follow the app toggles" (the universal Home/Lock system).
        "target" -> ChoiceDialog("Apply to",
            listOf(
                "Follow the app toggles (default)",
                "Home screen",
                "Lock screen",
                "Both (same image)",
                "Both (different images)",
            ),
            targetLabel(block),
            { idx ->
                onEdit {
                    it.param3 = listOf(
                        "", "home", "lock", "both", "bothDifferent")[idx]
                }
            },
            close)
        "pickmode" -> ChoiceDialog("How to pick",
            listOf("Random", "In order", "Newest", "Avoid the most recently shown"),
            pickModeLabel(block),
            { idx -> onEdit { it.param2 = listOf("random", "inOrder", "newest", "avoidRecent")[idx] } },
            close)
        "group" -> {
            val groups = LibraryPrefs.readGroups(context)
            ChoiceDialog("Group",
                groups.ifEmpty { listOf("(no groups yet — make one in the library)") },
                block.param.ifEmpty { "(pick a group)" },
                { idx2 -> onEdit { b -> b.param = groups.getOrNull(idx2) ?: b.param } }, close)
        }
        "group3" -> {
            val groups = LibraryPrefs.readGroups(context)
            ChoiceDialog("Group",
                groups.ifEmpty { listOf("(no groups yet — make one in the library)") },
                block.param3.ifEmpty { "(pick a group)" },
                { idx2 -> onEdit { b -> b.param3 = groups.getOrNull(idx2) ?: b.param3 } }, close)
        }
        "playlist" -> {
            val all = com.wepwawet.playlist.SuitePlaylistStore.readAll(context)
            ChoiceDialog("Playlist",
                all.map { it.name }.ifEmpty { listOf("(no playlists)") },
                playlistNameOf(context, block.param),
                { idx2 -> onEdit { b -> b.param = all.getOrNull(idx2)?.id ?: b.param } }, close)
        }
        "playlist3" -> {
            val all = com.wepwawet.playlist.SuitePlaylistStore.readAll(context)
            ChoiceDialog("Playlist",
                all.map { it.name }.ifEmpty { listOf("(no playlists)") },
                playlistNameOf(context, block.param3),
                { idx2 -> onEdit { b -> b.param3 = all.getOrNull(idx2)?.id ?: b.param3 } }, close)
        }
        "source" -> ChoiceDialog("Random from",
            listOf("The library", "A group", "A playlist", "The Workshop", "Discover"),
            sourceLabel(block.param),
            { idx2 -> onEdit { b -> b.param = listOf("library", "group", "playlist", "workshop", "discover")[idx2] } },
            close)
        "preset" -> PresetPickerDialog(block.param, close) { name -> onEdit { it.param = name } }
        "minutes" -> NumberDialog("Minutes", block.num, 1, 24 * 60,
            { v -> onEdit { b -> b.num = v } }, close)
        "seconds" -> NumberDialog("Seconds", block.num, 1, 600,
            { v -> onEdit { b -> b.num = v } }, close)
        "count" -> NumberDialog("Repeat count", block.num, 1, 50,
            { v -> onEdit { b -> b.num = v } }, close)
        "fps" -> NumberDialog("FPS limit", block.num, 1, 120,
            { v -> onEdit { b -> b.num = v } }, close)
        "amount" -> NumberDialog("Amount", block.num, -1000, 1000,
            { v -> onEdit { b -> b.num = v } }, close)
        "audio" -> ChoiceDialog("Audio-responsive effects",
            listOf("Mute", "Unmute"),
            if (block.param == "unmute") "Unmute" else "Mute",
            { idx2 -> onEdit { b -> b.param = if (idx2 == 1) "unmute" else "mute" } }, close)
        "renderer" -> ChoiceDialog("Renderer",
            listOf("Pause", "Resume"),
            if (block.param == "resume") "Resume" else "Pause",
            { idx2 -> onEdit { b -> b.param = if (idx2 == 1) "resume" else "pause" } }, close)
        "rule" -> {
            val others = SuiteRulesStore.readPlaylistRules(context, rulePlaylistId(rule, context))
                .rules.filter { it.id != block.param }
            ChoiceDialog("Run which rule",
                (others.map { it.name }).ifEmpty { listOf("(no other rules)") },
                ruleNameOf(context, rulePlaylistId(rule, context), block.param),
                { idx -> onEdit { it.param = others.getOrNull(idx)?.id ?: it.param } }, close)
        }
        "wallpaper" -> WallpaperPickerDialog(
            multi = false, initial = setOf(block.param),
            chips = valueChips(rule),
            onDismiss = close,
            playlistId = rulePlaylistId(rule, context),
            onPicked = { paths -> onEdit { it.param = paths.firstOrNull() ?: it.param } })
        "set" -> WallpaperPickerDialog(
            multi = true, initial = block.paths.toSet(),
            chips = valueChips(rule),
            onDismiss = close,
            playlistId = rulePlaylistId(rule, context),
            onPicked = { paths -> onEdit { it.paths = paths.toMutableList() } })
        // v0.9.0 redesign: the LIMIT scope's wallpaper set — a dense grid
        // with the playlist's own wallpapers first; a scope can never be
        // saved with zero wallpapers.
        "limit" -> WallpaperPickerDialog(
            multi = true, initial = block.paths.toSet(),
            chips = emptyList(),
            onDismiss = close,
            playlistId = rulePlaylistId(rule, context),
            requireNonEmpty = true,
            onPicked = { paths -> onEdit { it.paths = paths.toMutableList() } })
        "title" -> TextDialog("Notification title", "Title", block.param,
            onChange = { v -> onEdit { b -> b.param = v } }, onDismiss = close)
        "text" -> TextDialog("Notification text", "Text", block.param2,
            onChange = { v -> onEdit { b -> b.param2 = v } }, onDismiss = close)
        "workshopid" -> TextDialog("Workshop item ID", "The number from the item's link",
            block.param, onChange = { v -> onEdit { b -> b.param = v } }, onDismiss = close)
        "valuename" -> {
            val names = SuiteRulesStore.readValues(context).keys.toList()
            if (names.isEmpty()) {
                TextDialog("Named value", "The value's name (e.g. Night mode)", block.param,
                    onChange = { v -> onEdit { b -> b.param = v } }, onDismiss = close)
            } else {
                ChoiceDialog("Named value",
                    names + "＋ a new name…",
                    block.param,
                    { idx2 ->
                        if (idx2 == names.size) field = "valuename-new"
                        else onEdit { b -> b.param = names[idx2] }
                    }, close)
            }
        }
        "valuename-new" -> TextDialog("New named value", "The value's name (e.g. Night mode)",
            "", onChange = { v -> onEdit { b -> b.param = v } }, onDismiss = close)
        "valuekind" -> ChoiceDialog("Value kind",
            listOf("On/off", "Number", "Text"),
            valueKindLabel(block.param3),
            { idx2 -> onEdit { b -> b.param3 = listOf("bool", "number", "string")[idx2] } }, close)
        "valuevalue" -> when (block.param3) {
            "bool" -> ChoiceDialog("Set the value",
                listOf("on", "off"), block.param2.ifEmpty { "on" },
                { idx2 -> onEdit { b -> b.param2 = if (idx2 == 0) "on" else "off" } }, close)
            "number" -> NumberDialog("Number", block.num, -1000, 1000,
                { v -> onEdit { b -> b.num = v; b.param2 = v.toString() } }, close)
            else -> TextDialog("Value", "The value to set", block.param2,
                onChange = { v -> onEdit { b -> b.param2 = v } }, onDismiss = close)
        }
    }

    // v0.9.0 redesign: the ⋮ menu (Duplicate / Disable / Delete) replaced
    // the swipe-right delete and long-press duplicate; the drag handle
    // (long-press-drag) reorders — one row height per crossing.
    var menuOpen by remember(block.id) { mutableStateOf(false) }
    var dragCrossings by remember(block.id) { mutableStateOf(0) }
    val liveMove by androidx.compose.runtime.rememberUpdatedState(onMove)
    val density = androidx.compose.ui.platform.LocalDensity.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(block.id) {
                val rowH = with(density) { 64.dp.toPx() }
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragCrossings = 0 },
                    onDrag = { change, drag ->
                        change.consume()
                        val target = (drag.position.y / rowH).toInt()
                        // One onMove(±1) per crossing keeps the parent's
                        // clamp in charge; the live move updates the list
                        // and the card's position follows.
                        while (target > dragCrossings) {
                            liveMove(+1); dragCrossings++
                        }
                        while (target < dragCrossings) {
                            liveMove(-1); dragCrossings--
                        }
                    },
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = if (highlight)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(18.dp),
                )
                Icon(
                    blockIcon(block.type), null,
                    Modifier
                        .padding(start = 8.dp)
                        .size(18.dp),
                    tint = if (block.disabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
                Text(
                    blockSummary(context, rule, block),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
                if (RuleModel.hasChildren(block.type)) {
                    IconButton(onClick = {
                        collapsed = !collapsed
                        onEdit { it.collapsed = collapsed }
                    }) {
                        Icon(
                            if (collapsed) Icons.Filled.KeyboardArrowDown
                            else Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Collapse",
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menuOpen = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (block.disabled) "Enable" else "Disable") },
                            leadingIcon = {
                                Icon(
                                    if (block.disabled) Icons.Filled.PlayArrow
                                    else Icons.Filled.Block,
                                    null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onEdit { it.disabled = !it.disabled }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }

            if (!collapsed) {
                val targetSetting: @Composable () -> Unit = {
                    SettingRow("Apply to", targetLabel(block)) { field = "target" }
                }
                Column(Modifier.padding(bottom = 4.dp)) {
                    when (block.type) {
                        RuleModel.BLOCK_LIMIT -> {
                            SettingRow("Wallpapers", "${block.paths.size} chosen") {
                                field = "limit"
                            }
                        }
                        RuleModel.BLOCK_SHOW -> {
                            SettingRow("Wallpaper", displayPath(block.param)) { field = "wallpaper" }
                            targetSetting()
                        }
                        RuleModel.BLOCK_SHOW_SET -> {
                            SettingRow("Set", "${block.paths.size} wallpapers") { field = "set" }
                            SettingRow("How to pick", pickModeLabel(block)) { field = "pickmode" }
                            targetSetting()
                        }
                        RuleModel.BLOCK_PICK_GROUP -> {
                            SettingRow("Group", block.param.ifEmpty { "(pick a group)" }) { field = "group" }
                            SettingRow("How to pick", pickModeLabel(block)) { field = "pickmode" }
                            targetSetting()
                        }
                        RuleModel.BLOCK_PICK_PLAYLIST -> {
                            SettingRow("Playlist", playlistNameOf(context, block.param)) { field = "playlist" }
                            SettingRow("How to pick", pickModeLabel(block)) { field = "pickmode" }
                            targetSetting()
                        }
                        RuleModel.BLOCK_RANDOM -> {
                            SettingRow("From", sourceLabel(block.param)) { field = "source" }
                            if (block.param == "group") {
                                SettingRow("Group", block.param3.ifEmpty { "(pick a group)" }) { field = "group3" }
                            }
                            if (block.param == "playlist") {
                                SettingRow("Playlist", playlistNameOf(context, block.param3)) { field = "playlist3" }
                            }
                            SettingRow("How to pick", pickModeLabel(block)) { field = "pickmode" }
                            targetSetting()
                        }
                        RuleModel.BLOCK_PULL_DISCOVER, RuleModel.BLOCK_NEXT,
                        RuleModel.BLOCK_PREV, RuleModel.BLOCK_SHUFFLE,
                        -> targetSetting()
                        RuleModel.BLOCK_HOLD_CURRENT -> SettingRow("Hold for", "${block.num} minutes") { field = "minutes" }
                        RuleModel.BLOCK_SWITCH_PLAYLIST -> SettingRow("Playlist", playlistNameOf(context, block.param)) { field = "playlist" }
                        RuleModel.BLOCK_SET_FPS -> SettingRow("Limit", "${block.num} FPS") { field = "fps" }
                        RuleModel.BLOCK_AUDIO_MUTE -> SettingRow("Effects", if (block.param == "unmute") "Unmuted" else "Muted") { field = "audio" }
                        RuleModel.BLOCK_RENDERER_PAUSE -> SettingRow("Renderer", if (block.param == "resume") "Resumes" else "Pauses") { field = "renderer" }
                        RuleModel.BLOCK_APPLY_PRESET -> SettingRow("Preset", block.param.ifEmpty { "(pick a preset)" }) { field = "preset" }
                        RuleModel.BLOCK_REPEAT -> SettingRow("Repeat", "${block.num} ×") { field = "count" }
                        RuleModel.BLOCK_WAIT -> SettingRow("Wait", "${block.num} seconds") { field = "seconds" }
                        RuleModel.BLOCK_RUN_RULE -> SettingRow("Rule", ruleNameOf(context, rulePlaylistId(rule, context), block.param)) { field = "rule" }
                        RuleModel.BLOCK_NOTIFY -> {
                            SettingRow("Title", block.param) { field = "title" }
                            SettingRow("Text", block.param2) { field = "text" }
                        }
                        RuleModel.BLOCK_WORKSHOP_DOWNLOAD -> SettingRow("Item ID", block.param) { field = "workshopid" }
                        RuleModel.BLOCK_SET_VALUE -> {
                            SettingRow("Name", block.param.ifEmpty { "(pick a name)" }) { field = "valuename" }
                            SettingRow("Kind", valueKindLabel(block.param3)) { field = "valuekind" }
                            SettingRow("Value", valueSetLabel(block)) { field = "valuevalue" }
                        }
                        RuleModel.BLOCK_ADD_VALUE -> {
                            SettingRow("Name", block.param.ifEmpty { "(pick a name)" }) { field = "valuename" }
                            SettingRow("Amount", "${block.num}") { field = "amount" }
                        }
                        RuleModel.BLOCK_CLEAR_VALUE -> SettingRow("Name", block.param.ifEmpty { "(pick a name)" }) { field = "valuename" }
                        RuleModel.BLOCK_SAVE_TO_FOLDER -> SettingRow("Group", block.param.ifEmpty { "(pick a group)" }) { field = "group" }
                        RuleModel.BLOCK_ADD_TO_PLAYLIST -> SettingRow("Playlist", playlistNameOf(context, block.param)) { field = "playlist" }
                        else -> {}
                    }
                }

                // ── The nested stacks ──
                when (block.type) {
                    RuleModel.BLOCK_IF -> {
                        CondCard(cond = block.cond ?: RuleModel.Cond()) { newCond ->
                            onEdit { it.cond = newCond }
                        }
                        InnerStack(
                            label = "Then",
                            blocks = block.thenBlocks,
                            rule = rule, depth = depth + 1,
                            highlights = highlights,
                            setBlocks = { list -> onEdit { it.thenBlocks = list.toMutableList() } },
                            railColor = MaterialTheme.colorScheme.primary,
                        )
                        InnerStack(
                            label = "Otherwise",
                            blocks = block.elseBlocks,
                            rule = rule, depth = depth + 1,
                            highlights = highlights,
                            setBlocks = { list -> onEdit { it.elseBlocks = list.toMutableList() } },
                            railColor = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    RuleModel.BLOCK_REPEAT -> InnerStack(
                        label = "Repeat ${block.num} ×",
                        blocks = block.children,
                        rule = rule, depth = depth + 1,
                        highlights = highlights,
                        setBlocks = { list -> onEdit { it.children = list.toMutableList() } },
                        railColor = MaterialTheme.colorScheme.secondary,
                    )
                    RuleModel.BLOCK_CHOOSE -> {
                        for ((oi, opt) in block.options.withIndex()) {
                            InnerStack(
                                label = "Option: ${opt.label}",
                                blocks = opt.blocks,
                                rule = rule, depth = depth + 1,
                                highlights = highlights,
                                setBlocks = { list -> onEdit { it.options[oi].blocks = list.toMutableList() } },
                                railColor = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Row {
                            TextButton(onClick = {
                                onEdit {
                                    it.options.add(RuleModel.BlockOption(
                                        label = "Option ${it.options.size + 1}"))
                                }
                            }) { Text("＋ Add an option") }
                            if (block.options.size > 1) {
                                TextButton(onClick = {
                                    onEdit {
                                        if (it.options.size > 1) it.options.removeAt(it.options.size - 1)
                                    }
                                }) { Text("Remove the last option") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The rule's own playlist id lookup (rules are per playlist). */
internal fun rulePlaylistId(rule: RuleModel.Rule, context: android.content.Context): String {
    // The rule object itself doesn't carry the playlist id; the editor
    // knows it — for lookups here, search by rule id across playlists.
    for (pr in SuiteRulesStore.readAll(context)) {
        if (pr.rules.any { it.id == rule.id }) return pr.playlistId
    }
    return ""
}

internal fun ruleNameOf(
    context: android.content.Context,
    playlistId: String,
    ruleId: String,
): String {
    if (ruleId.isEmpty()) return "(pick a rule)"
    return SuiteRulesStore.readPlaylistRules(context, playlistId).rules
        .firstOrNull { it.id == ruleId }?.name ?: "(deleted rule)"
}

// ══ The block summary (plain language, one line) ════════════════════

internal fun blockSummary(
    context: android.content.Context,
    rule: RuleModel.Rule,
    b: RuleModel.Block,
): String = when (b.type) {
    RuleModel.BLOCK_LIMIT -> "Only use ${b.paths.size} wallpapers while this is active"
    RuleModel.BLOCK_SHOW -> "Show ${displayPath(b.param)} on the ${targetLabel(b).lowercase()}"
    RuleModel.BLOCK_SHOW_SET -> "Show one of ${b.paths.size} wallpapers (${pickModeLabel(b).lowercase()})"
    RuleModel.BLOCK_PICK_GROUP -> "Pick from the group \"${b.param.ifEmpty { "…" }}\""
    RuleModel.BLOCK_PICK_PLAYLIST -> "Pick from ${playlistNameOf(context, b.param)}"
    RuleModel.BLOCK_RANDOM -> "Random from ${sourceLabel(b.param).lowercase()}"
    RuleModel.BLOCK_PULL_DISCOVER -> "Pull a fresh Discover image"
    RuleModel.BLOCK_NEXT -> "Go to the next wallpaper"
    RuleModel.BLOCK_PREV -> "Go to the previous wallpaper"
    RuleModel.BLOCK_SHUFFLE -> "Shuffle the playlist"
    RuleModel.BLOCK_PAUSE_ROTATION -> "Pause the rotation"
    RuleModel.BLOCK_RESUME_ROTATION -> "Resume the rotation"
    RuleModel.BLOCK_RESTART_PLAYLIST -> "Restart the playlist"
    RuleModel.BLOCK_HOLD_CURRENT -> "Hold the current wallpaper for ${b.num} minutes"
    RuleModel.BLOCK_SWITCH_PLAYLIST -> "Switch to ${playlistNameOf(context, b.param)}"
    RuleModel.BLOCK_SET_FPS -> "Set the FPS limit to ${b.num}"
    RuleModel.BLOCK_AUDIO_MUTE -> if (b.param == "unmute") "Unmute audio-responsive effects"
        else "Mute audio-responsive effects"
    RuleModel.BLOCK_RENDERER_PAUSE -> if (b.param == "resume") "Resume the renderer" else "Pause the renderer"
    RuleModel.BLOCK_APPLY_PRESET -> "Apply the preset \"${b.param}\""
    RuleModel.BLOCK_IF -> "If the condition holds, run Then; otherwise Otherwise"
    RuleModel.BLOCK_CHOOSE -> "Ask me to choose between ${b.options.size} options"
    RuleModel.BLOCK_REPEAT -> "Repeat ${b.num} times"
    RuleModel.BLOCK_WAIT -> "Wait ${b.num} seconds"
    RuleModel.BLOCK_STOP -> "Stop the rule here"
    RuleModel.BLOCK_RUN_RULE -> "Run \"${ruleNameOf(context, rulePlaylistId(rule, context), b.param)}\""
    RuleModel.BLOCK_NOTIFY -> "Notify: ${b.param.ifEmpty { "(no title)" }}"
    RuleModel.BLOCK_DISCOVER_REFRESH -> "Refresh Discover now"
    RuleModel.BLOCK_WORKSHOP_DOWNLOAD -> "Download Workshop item ${b.param.ifEmpty { "…" }}"
    RuleModel.BLOCK_SET_VALUE -> "Set \"${b.param}\" to ${valueSetLabel(b)}"
    RuleModel.BLOCK_ADD_VALUE -> "Add ${b.num} to \"${b.param}\""
    RuleModel.BLOCK_CLEAR_VALUE -> "Clear \"${b.param}\""
    RuleModel.BLOCK_SAVE_TO_FOLDER -> "Save the last pick into \"${b.param}\"'s folder"
    RuleModel.BLOCK_ADD_TO_PLAYLIST -> "Add the last pick to ${playlistNameOf(context, b.param)}"
    else -> b.type
}

// ══ The condition tree (gate + If blocks) ═════════════════════════════

/** A condition's plain label. */
internal fun condLabel(c: RuleModel.Cond): String = when (c.type) {
    RuleModel.COND_ALL -> "All of"
    RuleModel.COND_ANY -> "Any of"
    RuleModel.COND_NOT -> "Not"
    RuleModel.COND_TIME_RANGE -> "Time is ${fmtMin(c.minuteStart)}–${fmtMin(c.minuteEnd)}"
    RuleModel.COND_DAY_OF_WEEK ->
        "Day is " + (listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            .getOrElse(c.dayOfWeek) { "any" })
    RuleModel.COND_CHARGING -> "Charging"
    RuleModel.COND_BATTERY_BELOW -> "Battery below ${c.batteryLevel}%"
    RuleModel.COND_BATTERY_ABOVE -> "Battery above ${c.batteryLevel}%"
    RuleModel.COND_SAVER_ON -> "Battery saver on"
    RuleModel.COND_WIFI_CONNECTED -> "On Wi-Fi"
    RuleModel.COND_WIFI_IS -> "On \"${c.networkSsid.ifEmpty { "any Wi-Fi" }}\""
    RuleModel.COND_MOBILE_DATA -> "On mobile data"
    RuleModel.COND_HEADPHONES -> "Headphones connected"
    RuleModel.COND_SCREEN_ON -> "Screen on"
    RuleModel.COND_ORIENTATION_IS -> "Orientation is ${c.orientation}"
    RuleModel.COND_PLAYLIST_ACTIVE -> "Playlist \"${c.playlistId.ifEmpty { "this one" }}\" active"
    RuleModel.COND_WALLPAPER_IS -> "Wallpaper is ${displayPath(c.wallpaperPath)}"
    RuleModel.COND_SHOWN_MINUTES -> "Shown for ${c.minutes}+ min"
    RuleModel.COND_VALUE_IS -> "\"${c.valueName}\" ${c.cmp} ${c.compareToNum}${if (c.compareTo.isNotEmpty()) c.compareTo else ""}"
    RuleModel.COND_LAST_PICK_OK -> "The last pick succeeded"
    else -> c.type
}

/** All condition types by category (the picker). */
private val COND_CATEGORIES = listOf(
    "Combine" to listOf(
        RuleModel.COND_ALL, RuleModel.COND_ANY, RuleModel.COND_NOT,
    ),
    "Time" to listOf(
        RuleModel.COND_TIME_RANGE, RuleModel.COND_DAY_OF_WEEK,
    ),
    "Power" to listOf(
        RuleModel.COND_CHARGING, RuleModel.COND_BATTERY_BELOW,
        RuleModel.COND_BATTERY_ABOVE, RuleModel.COND_SAVER_ON,
    ),
    "Network + audio" to listOf(
        RuleModel.COND_WIFI_CONNECTED, RuleModel.COND_WIFI_IS,
        RuleModel.COND_MOBILE_DATA, RuleModel.COND_HEADPHONES,
    ),
    "Screen + device" to listOf(
        RuleModel.COND_SCREEN_ON, RuleModel.COND_ORIENTATION_IS,
    ),
    "Wallpaper" to listOf(
        RuleModel.COND_PLAYLIST_ACTIVE, RuleModel.COND_WALLPAPER_IS,
        RuleModel.COND_SHOWN_MINUTES, RuleModel.COND_LAST_PICK_OK,
    ),
    "Values" to listOf(
        RuleModel.COND_VALUE_IS,
    ),
)

/**
 * The condition tree card — a node: its type + params (tappable fields)
 * and, for all/any/not, its children (nested condition cards; an If can sit
 * inside an If via nested all/any/not gates).
 */
@Composable
internal fun CondCard(
    cond: RuleModel.Cond,
    onChange: (RuleModel.Cond) -> Unit,
) {
    val context = LocalContext.current
    var field by remember { mutableStateOf<String?>(null) }
    val close = { field = null }

    when (field) {
        "type" -> ConditionTypePicker(cond.type, close) { type ->
            onChange(cond.copy(type = type,
                children = if (type in listOf(RuleModel.COND_ALL, RuleModel.COND_ANY, RuleModel.COND_NOT)) {
                    cond.children.ifEmpty { ArrayList(listOf(RuleModel.Cond(type = RuleModel.COND_CHARGING))) }.toMutableList()
                } else cond.children))
        }
        "rangeStart" -> MinuteDialog("From", cond.minuteStart, { onChange(cond.copy(minuteStart = it)) }, close)
        "rangeEnd" -> MinuteDialog("Until", cond.minuteEnd, { onChange(cond.copy(minuteEnd = it)) }, close)
        "day" -> ChoiceDialog("Day of week",
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "Any day"),
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "Any day")
                .getOrElse(cond.dayOfWeek) { "Any day" },
            { idx -> onChange(cond.copy(dayOfWeek = if (idx == 7) -1 else idx)) }, close)
        "battery" -> NumberDialog("Battery level (%)", cond.batteryLevel, 1, 100,
            { onChange(cond.copy(batteryLevel = it)) }, close)
        "ssid" -> TextDialog("Wi-Fi network", "Network name (blank = any)", cond.networkSsid,
            onChange = { onChange(cond.copy(networkSsid = it)) }, onDismiss = close)
        "orientation" -> ChoiceDialog("Orientation",
            listOf("portrait", "landscape"), cond.orientation,
            { idx -> onChange(cond.copy(orientation = if (idx == 0) "portrait" else "landscape")) }, close)
        "playlist" -> {
            val all = com.wepwawet.playlist.SuitePlaylistStore.readAll(context)
            ChoiceDialog("Playlist", all.map { it.name },
                playlistNameOf(context, cond.playlistId),
                { idx -> onChange(cond.copy(playlistId = all.getOrNull(idx)?.id ?: cond.playlistId)) }, close)
        }
        "wallpaper" -> WallpaperPickerDialog(
            multi = false, initial = setOf(cond.wallpaperPath),
            chips = listOf("Last pick" to "@last"),
            onDismiss = close,
            onPicked = { paths -> onChange(cond.copy(wallpaperPath = paths.firstOrNull() ?: cond.wallpaperPath)) })
        "minutes" -> NumberDialog("Shown for N+ minutes", cond.minutes.toInt(), 1, 24 * 60,
            { onChange(cond.copy(minutes = it.toLong())) }, close)
        "valuename" -> {
            val names = SuiteRulesStore.readValues(context).keys.toList()
            ChoiceDialog("Named value", names.ifEmpty { listOf("(no values yet)") },
                cond.valueName,
                { idx -> onChange(cond.copy(valueName = names.getOrNull(idx) ?: cond.valueName)) }, close)
        }
        "cmp" -> ChoiceDialog("Comparison",
            listOf("=", "≠", "<", ">", "≤", "≥"), cond.cmp,
            { idx -> onChange(cond.copy(cmp = listOf("=", "≠", "<", ">", "≤", "≥")[idx])) }, close)
        "cmpnum" -> NumberDialog("Compare to", cond.compareToNum.toInt(), -100000, 100000,
            { onChange(cond.copy(compareToNum = it.toLong())) }, close)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                condLabel(cond),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { field = "type" }
                    .padding(vertical = 6.dp, horizontal = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { onChange(RuleModel.Cond(type = RuleModel.COND_ALL)) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove condition")
            }
        }
        // The node's params.
        when (cond.type) {
            RuleModel.COND_TIME_RANGE -> {
                SettingRow("From", fmtMin(cond.minuteStart)) { field = "rangeStart" }
                SettingRow("Until", fmtMin(cond.minuteEnd)) { field = "rangeEnd" }
            }
            RuleModel.COND_DAY_OF_WEEK -> SettingRow("Day",
                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "Any day")
                    .getOrElse(cond.dayOfWeek) { "Any day" }) { field = "day" }
            RuleModel.COND_BATTERY_BELOW, RuleModel.COND_BATTERY_ABOVE ->
                SettingRow("Level", "${cond.batteryLevel}%") { field = "battery" }
            RuleModel.COND_WIFI_IS -> SettingRow("Network", cond.networkSsid.ifEmpty { "any" }) { field = "ssid" }
            RuleModel.COND_ORIENTATION_IS -> SettingRow("Orientation", cond.orientation) { field = "orientation" }
            RuleModel.COND_PLAYLIST_ACTIVE -> SettingRow("Playlist", playlistNameOf(context, cond.playlistId)) { field = "playlist" }
            RuleModel.COND_WALLPAPER_IS -> SettingRow("Wallpaper", displayPath(cond.wallpaperPath)) { field = "wallpaper" }
            RuleModel.COND_SHOWN_MINUTES -> SettingRow("Minutes", "${cond.minutes}+") { field = "minutes" }
            RuleModel.COND_VALUE_IS -> {
                SettingRow("Name", cond.valueName) { field = "valuename" }
                SettingRow("Comparison", cond.cmp) { field = "cmp" }
                SettingRow("Compare to", cond.compareToNum.toString()) { field = "cmpnum" }
            }
            else -> {}
        }
        // The children (combine nodes) + add/remove.
        if (cond.type in listOf(RuleModel.COND_ALL, RuleModel.COND_ANY, RuleModel.COND_NOT)) {
            for ((ci, child) in cond.children.withIndex()) {
                CondCard(child) { newChild ->
                    val list = cond.children.toMutableList()
                    list[ci] = newChild
                    onChange(cond.copy(children = list))
                }
            }
            Row {
                TextButton(onClick = {
                    onChange(cond.copy(children = (cond.children + RuleModel.Cond(type = RuleModel.COND_CHARGING)).toMutableList()))
                }) { Text("＋ add a condition") }
                if (cond.children.size > 1) {
                    TextButton(onClick = {
                        onChange(cond.copy(children = cond.children.dropLast(1).toMutableList()))
                    }) { Text("remove the last") }
                }
            }
        }
    }
}

/** The condition-type picker (categories, taps only). */
@Composable
private fun ConditionTypePicker(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Condition") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                val q = search.trim().lowercase()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for ((cat, types) in COND_CATEGORIES) {
                        val matching = types.filter {
                            q.isEmpty() || condLabel(
                                RuleModel.Cond(type = it)).lowercase().contains(q)
                        }
                        if (matching.isEmpty()) continue
                        Text(cat, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        for (t in matching) {
                            val label = condLabel(RuleModel.Cond(type = t))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (t == current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPick(t) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ══ The preset picker (+ capture-the-current) ════════════════════════

@Composable
internal fun PresetPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf(SuiteRulesStore.readPresets(context)) }
    var newName by remember { mutableStateOf("") }
    var captureMode by remember { mutableStateOf(false) }

    if (captureMode) {
        AlertDialog(
            onDismissRequest = { captureMode = false },
            title = { Text("Capture the current wallpaper's properties") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(30) },
                    singleLine = true,
                    label = { Text("Preset name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val svc = io.wallpaperengine.weclient.WEWallpaperService.instance
                        val wallpaper = svc?.currentWallpaperFile() ?: ""
                        // The properties JSON the engine applies is Gson of
                        // the controls' current property map (same shape the
                        // property editor saves).
                        val propsMap = svc?.rulesWallpaperControls()
                            ?.getWallpaperProperties()
                        val props = if (propsMap != null) {
                            com.google.gson.Gson().toJson(propsMap)
                        } else "{}"
                        SuiteRulesStore.savePreset(context,
                            SuiteRulesStore.Preset(newName.trim(), wallpaper, props))
                        WepWawetLogger.appInfo("RuleEditor: preset captured",
                            "name" to newName.trim())
                        presets = SuiteRulesStore.readPresets(context)
                        captureMode = false
                    },
                ) { Text("Save preset") }
            },
            dismissButton = { TextButton(onClick = { captureMode = false }) { Text("Cancel") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Property preset") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (presets.isEmpty()) {
                    Text(
                        "No presets yet — capture the current wallpaper's " +
                            "properties as the first one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (p in presets) {
                    Text(
                        p.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (p.name == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(p.name) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { captureMode = true }) { Text("＋ Capture current") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ══ The wallpaper picker (dense grid + group chips + value chips) ══════

/**
 * v0.9.0 redesign: the wallpaper picker is a DENSE THUMBNAIL GRID (reusing
 * [PlaylistPickThumb]) with group chips, like the playlist Add picker — the
 * playlist's OWN wallpapers come first, then the library + folder images.
 * The VALUE CHIPS ("@last" / earlier blocks) stay insertable. With
 * [requireNonEmpty] (the Limit scope), the confirm button stays disabled
 * until at least one wallpaper is chosen — a scope cannot be saved with
 * zero wallpapers.
 */
@Composable
internal fun WallpaperPickerDialog(
    multi: Boolean,
    initial: Set<String>,
    chips: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPicked: (List<String>) -> Unit,
    playlistId: String = "",
    requireNonEmpty: Boolean = false,
) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(initial.filter { it.isNotEmpty() }.toMutableSet()) }

    // The playlist's own entries (resolved, in order), then everything
    // addable: engine wallpapers + folder images (through their crops).
    var ownItems by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var libraryItems by remember {
        mutableStateOf<List<Triple<String, String, Boolean>>>(emptyList())
    }
    val groups = remember { LibraryPrefs.readGroups(context) }
    val groupOf = remember { LibraryPrefs.readGroupOf(context) }
    val groupFolders = remember { LibraryPrefs.readGroupFolders(context) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // The playlist's own wallpapers (title from its entries).
            val own = ArrayList<Pair<String, String>>()
            if (playlistId.isNotEmpty()) {
                val playlist = com.wepwawet.playlist.SuitePlaylistStore.read(context, playlistId)
                if (playlist != null) {
                    for (e in playlist.entries) {
                        if (com.wepwawet.playlist.GroupBlocks.isGroupBlock(e.filepath)) {
                            for (p in com.wepwawet.playlist.GroupBlocks
                                .resolve(context, e).paths) {
                                own.add(p to p.substringAfterLast('/'))
                            }
                        } else if (!com.wepwawet.playlist.RandomPullEngine
                                .isSlot(e.filepath)) {
                            own.add(e.filepath to
                                e.title.ifEmpty { e.filepath.substringAfterLast('/') })
                        }
                    }
                }
            }
            ownItems = own.distinctBy { it.first }

            val cropMap = com.wepwawet.ui.screens.ImageCropStore.read(context)
            val walls = com.wepwawet.engine.EngineLibrary.enumerateWallpapers(context).map {
                Triple(it.filePath,
                    it.title.ifEmpty { it.filePath.substringAfterLast('/') },
                    it.isLocal || it.filePath.startsWith("content://"))
            }
            val folderImages = com.wepwawet.ui.screens.ImageFolderAssets
                .readFolders(context).flatMap { folder ->
                    com.wepwawet.ui.screens.ImageFolderAssets
                        .enumerateFolderImages(context, folder.treeUri)
                        .map { folder.treeUri to it }
                }
                .map { (treeUri, e) ->
                    val resolved = cropMap[e.uri]?.cropFilePath ?: e.uri
                    Triple(resolved,
                        e.displayName.substringBeforeLast('.'),
                        !resolved.startsWith("/"))
                }
            libraryItems = (walls + folderImages).distinctBy { it.first }
        }
        loaded = true
    }

    val ownSet = remember(ownItems) { ownItems.map { it.first }.toHashSet() }
    val visibleLibrary = remember(libraryItems, search, group, groupFolders) {
        val q = search.trim().lowercase()
        val link = group?.let { groupFolders[it] }
        libraryItems.filter { (path, title, _) ->
            (group == null || groupOf[path] == group) &&
                (q.isEmpty() || title.lowercase().contains(q))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (multi) "Pick wallpapers" else "Pick a wallpaper",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    label = { Text("Search") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Or a value from an earlier block:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        for ((label, ref) in chips) {
                            FilterChip(
                                selected = ref in selected,
                                onClick = {
                                    selected = mutableSetOf(ref)
                                    onPicked(listOf(ref))
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                if (groups.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = group == null,
                            onClick = { group = null },
                            label = { Text("All") },
                        )
                        for (g in groups) {
                            FilterChip(
                                selected = group == g,
                                onClick = { group = if (group == g) null else g },
                                label = { Text(g) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!loaded) {
                    androidx.compose.material3.LinearProgressIndicator(
                        Modifier.fillMaxWidth())
                } else {
                    LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // The playlist's own wallpapers come FIRST.
                        if (ownItems.isNotEmpty()) {
                            item(key = "own-header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                                Text(
                                    "This playlist",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            gridItems(ownItems, key = { "own-" + it.first }) { (path, title) ->
                                WallpaperGridCell(path, title, path in selected, multi) {
                                    if (multi) {
                                        selected = if (path in selected)
                                            (selected - path).toMutableSet()
                                        else (selected + path).toMutableSet()
                                    } else {
                                        onPicked(listOf(path))
                                    }
                                }
                            }
                            item(key = "lib-header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                                Text(
                                    "Library",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        gridItems(visibleLibrary, key = { it.first }) { (path, title, isUri) ->
                            WallpaperGridCell(path, title, path in selected, multi) {
                                if (multi) {
                                    selected = if (path in selected)
                                        (selected - path).toMutableSet()
                                    else (selected + path).toMutableSet()
                                } else {
                                    onPicked(listOf(path))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    if (multi) {
                        TextButton(
                            onClick = { onPicked(selected.toList()) },
                            enabled = !requireNonEmpty || selected.isNotEmpty(),
                        ) { Text("Set ${selected.size}") }
                    }
                }
            }
        }
    }
}

/** One dense cell of the wallpaper picker grid. */
@Composable
private fun WallpaperGridCell(
    path: String,
    title: String,
    checked: Boolean,
    multi: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (checked)
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(10.dp),
                    )
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box {
            PlaylistPickThumb(
                filepath = path,
                isContentUri = !path.startsWith("/"),
                size = 0.dp,
            )
            if (checked && multi) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
