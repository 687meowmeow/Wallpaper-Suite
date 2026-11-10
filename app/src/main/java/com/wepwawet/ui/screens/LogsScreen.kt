package com.wepwawet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wepwawet.log.LogEntry
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/**
 * In-app log viewer.
 *
 * v0.8.5: the "Download logs" button was removed — logs are auto-persisted
 * to `filesDir/wepwawet/logs/wepwawet-YYYYMMDD.log` by
 * [com.wepwawet.log.LogPersistenceWorker] (a 15-minute periodic WorkManager
 * job). The user no longer needs to manually export logs to a file; the
 * latest 14 days of logs are always on disk. The Copy button (clipboard)
 * and Clear button (wipes the in-memory ring buffer) remain for ad-hoc
 * sharing and cleanup.
 *
 * The CrashHandler still writes crash reports (with a tail of the ring
 * buffer + the engine log) to `Downloads/WallpaperSuite/crash-*.txt` on
 * crash — that's a separate crash artifact, not a general log export.
 */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val events = remember { mutableStateListOf<LogEntry>() }

    LaunchedEffect(Unit) {
        // Initial load from the ring buffer snapshot
        events.clear()
        events.addAll(WepWawetLogger.snapshot())
        // Incremental updates — only new entries trigger recomposition.
        // Replaces the previous 500ms full-snapshot polling which caused
        // full-LazyColumn recomposition twice per second.
        WepWawetLogger.events.collect { entry ->
            events.add(entry)
            // v0.7.21: Increased from 2000 to 5000 to match RING_BUFFER_CAPACITY.
            while (events.size > 5000) events.removeAt(0)
        }
    }

    fun copyLogs() {
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
        val text = events.reversed().joinToString("\n") { entry ->
            buildString {
                append("[${formatter.format(entry.timestamp)}] ")
                append("[${entry.domain.tag}] ${entry.level.name}: ${entry.message}")
                if (entry.context.isNotEmpty()) {
                    append(" ")
                    append(entry.context.entries.joinToString(" ") { "${it.key}=${it.value ?: "null"}" })
                }
                if (entry.exceptionStackTrace != null) {
                    append("\n")
                    append(entry.exceptionStackTrace.take(500))
                }
            }
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WallpaperSuite Logs", text))
        Toast.makeText(context, "Copied ${events.size} log entries", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Logs (${events.size})",
                onBack = onBack,
                actions = {
                    // v0.8.5: Download button removed — LogPersistenceWorker
                    // auto-saves the ring buffer to
                    // filesDir/wepwawet/logs/wepwawet-YYYYMMDD.log every 15
                    // minutes (and prunes files older than 14 days). The user
                    // doesn't need to manually export logs anymore.
                    IconButton(onClick = { copyLogs() }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = {
                        WepWawetLogger.clear()
                        events.clear()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (events.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text(
                        "No logs yet. Logs are auto-saved to the app's internal storage.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // v0.7.24 (M-U5): events.reversed() allocated a fresh
                    // 5000-element list on EVERY recomposition (i.e. per log
                    // entry). Index-based reverse iteration allocates nothing.
                    items(events.size, key = { events[events.size - 1 - it].seq }) { i ->
                        LogEntryRow(events[events.size - 1 - i])
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        WepWawetLogger.Level.TRACE, WepWawetLogger.Level.DEBUG -> Color(0xFF6B7280)
        WepWawetLogger.Level.INFO -> Color(0xFFE6E6E9)
        WepWawetLogger.Level.WARN -> Color(0xFFFFB72E)
        WepWawetLogger.Level.ERROR -> Color(0xFFFF6B5C)
        WepWawetLogger.Level.FATAL -> Color(0xFFFFB72E)
    }
    val timeStr = remember(entry.seq) {
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
        formatter.format(entry.timestamp)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (entry.level) {
                WepWawetLogger.Level.ERROR, WepWawetLogger.Level.FATAL -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = timeStr,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = entry.domain.tag,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = entry.level.name,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = entry.message,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            if (entry.context.isNotEmpty()) {
                Text(
                    text = entry.context.entries.joinToString(" ") { "${it.key}=${it.value ?: "null"}" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (entry.exceptionStackTrace != null) {
                Text(
                    text = entry.exceptionStackTrace.take(2000),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
