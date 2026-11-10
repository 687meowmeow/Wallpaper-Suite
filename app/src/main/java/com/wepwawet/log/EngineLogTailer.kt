package com.wepwawet.log

import android.content.Context
import com.wepwawet.log.WepWawetLogger.Domain
import com.wepwawet.log.WepWawetLogger.Level
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tails the native engine's own log file (`filesDir/logs/applog.txt`) and
 * feeds new lines into [WepWawetLogger] as ENGINE-domain entries.
 *
 * v0.8.6: the user's explicit request was "no save to log because it should
 * automatically be in the main log" — the engine's shader compile errors,
 * texture load failures and scene-init diagnostics must appear in the in-app
 * Logs screen automatically, with no manual export step. The native engine
 * (libscenejni.so) writes to applog.txt when `SceneLib.setLogToFileEnabled(true)`
 * is called; this tailer watches that file and forwards new lines into the
 * main ring buffer.
 *
 * Threading: the tailer runs on a dedicated [Dispatchers.IO] coroutine. It
 * polls the file every 500ms (cheap — one stat + one read of new bytes
 * only). FileObserver was considered but rejected: it doesn't fire reliably
 * on internal storage on all Android versions, and polling at 500ms is
 * cheap enough that the complexity isn't justified.
 *
 * Recursion safety: [WepWawetLogger] does NOT write to applog.txt (only the
 * native engine does), so feeding engine lines back into WepWawetLogger
 * can't loop. The tailer tags each forwarded line with a "ENGINE-FILE/"
 * prefix in the message so it's distinguishable from the Java-side
 * WepWawetLogger.engine() entries (which use the bare "ENGINE" domain tag).
 *
 * Backlog: on first start, the tailer reads the LAST 200 lines of the
 * existing file (if any) so a fresh app open shows recent engine history.
 * Subsequent runs only forward NEW lines (tracked via a byte-offset
 * watermark persisted across app sessions).
 *
 * Pruning: applog.txt can grow unbounded (the engine never truncates it).
 * The tailer truncates the file to the last 256 KB whenever it exceeds 1 MB
 * — this keeps disk usage sane without losing recent diagnostic lines.
 * The byte-offset watermark is reset to 0 after a truncation.
 */
object EngineLogTailer {

    private const val TAG = "EngineLogTailer"
    private const val ENGINE_LOG_REL_PATH = "logs/applog.txt"
    private const val POLL_INTERVAL_MS = 500L
    private const val BACKLOG_LINES_ON_START = 200
    private const val TRUNCATE_THRESHOLD_BYTES = 1L * 1024 * 1024
    private const val TRUNCATE_KEEP_BYTES = 256L * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var job: Job? = null
    @Volatile
    private var appContext: Context? = null

    /**
     * Start tailing. Safe to call multiple times — only the first call
     * starts the coroutine; subsequent calls are no-ops.
     */
    fun start(context: Context) {
        if (job?.isActive == true) return
        appContext = context.applicationContext
        job = scope.launch { tailLoop() }
    }

    /** Stop tailing. Called from tests / shutdown. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun tailLoop() {
        val ctx = appContext ?: return
        val logFile = File(ctx.filesDir, ENGINE_LOG_REL_PATH)
        // Watermark: the byte offset we've already forwarded. Persisted
        // across app sessions so we don't re-forward the entire file on
        // every app start. On first-ever start, prefs returns 0L, but we
        // cap the initial read to the last BACKLOG_LINES_ON_START lines
        // via the "firstRun" flag.
        val prefs = ctx.getSharedPreferences("wepwawet_engine_log_tailer",
            Context.MODE_PRIVATE)
        var firstRun = prefs.getBoolean("first_run", true)
        var watermark = prefs.getLong("watermark", 0L)

        // If the file shrank (truncated by the system, user cleared cache,
        // or app reinstall), reset the watermark to 0.
        if (logFile.exists() && logFile.length() < watermark) {
            watermark = 0L
            prefs.edit().putLong("watermark", 0L).apply()
        }

        while (scope.isActive) {
            try {
                if (!logFile.exists()) {
                    // Engine hasn't written anything yet — wait.
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                val len = logFile.length()
                if (len <= watermark) {
                    // No new bytes.
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                // Prune if the file has grown large.
                if (len > TRUNCATE_THRESHOLD_BYTES) {
                    pruneLogFile(logFile)
                    // After prune, the file is now TRUNCATE_KEEP_BYTES long.
                    // Reset watermark so we re-read the kept tail on the
                    // next iteration (the kept tail hasn't been forwarded
                    // yet because it was at the END of the pre-truncate
                    // file, beyond the old watermark if the watermark was
                    // < len - TRUNCATE_KEEP_BYTES).
                    watermark = 0L
                    prefs.edit().putLong("watermark", 0L).apply()
                    firstRun = true
                    prefs.edit().putBoolean("first_run", true).apply()
                    continue
                }
                // Read new bytes [watermark .. len).
                // v0.8.7 (bug check): only consume up to the LAST COMPLETE
                // NEWLINE. The native engine may be mid-write when we poll —
                // forwarding a partial line split it into two bogus log
                // entries (the remainder arriving on the next poll). The
                // watermark now only advances past the last '\n'; the
                // trailing partial bytes are re-read next poll once the
                // engine finishes the line.
                val readLen = (len - watermark).toInt()
                val buf = RandomAccessFile(logFile, "r").use { raf ->
                    raf.seek(watermark)
                    val b = ByteArray(readLen)
                    raf.readFully(b)
                    b
                }
                val lastNewline = buf.lastIndexOf('\n'.code.toByte())
                if (lastNewline < 0) {
                    // No complete line yet — wait for the next poll.
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                val consumed = lastNewline + 1
                val text = String(buf, 0, consumed, Charsets.UTF_8)
                // On first run, only forward the last BACKLOG_LINES_ON_START
                // lines of the entire file (so we don't dump thousands of
                // historical lines into the ring buffer on a fresh open).
                val linesToForward = if (firstRun) {
                    val allLines = text.split('\n').filter { it.isNotBlank() }
                    firstRun = false
                    prefs.edit().putBoolean("first_run", false).apply()
                    if (allLines.size > BACKLOG_LINES_ON_START) {
                        allLines.takeLast(BACKLOG_LINES_ON_START)
                    } else {
                        allLines
                    }
                } else {
                    text.split('\n').filter { it.isNotBlank() }
                }
                // Forward each line. Classify the level heuristically:
                // lines containing "error"/"fail"/"fail" → WARN; the rest
                // → DEBUG. The native engine's log format isn't structured
                // (it's plain text), so this is best-effort.
                for (line in linesToForward) {
                    val trimmed = line.take(2000)  // cap absurdly long lines
                    val lower = trimmed.lowercase()
                    val level = when {
                        lower.contains("fatal") || lower.contains("panic") -> Level.ERROR
                        lower.contains("error") || lower.contains("failed") ||
                            lower.contains("exception") -> Level.WARN
                        else -> Level.DEBUG
                    }
                    WepWawetLogger.log(
                        domain = Domain.ENGINE,
                        level = level,
                        message = "ENGINE-FILE: $trimmed",
                        context = emptyMap(),
                        exception = null,
                    )
                }
                // Advance the watermark — only past the complete lines.
                watermark += consumed
                prefs.edit().putLong("watermark", watermark).apply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Don't let a transient IO error kill the tailer — log and
                // back off for a second.
                WepWawetLogger.appWarn("$TAG: tail error — backing off",
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
                delay(1000)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Truncate the log file to its last [TRUNCATE_KEEP_BYTES] bytes. The
     * engine keeps the file open with its own writer, so we read the tail,
     * rewrite the file, and accept that the engine's next write will
     * append after our truncation point (the engine opens the file in
     * append mode, so its file offset isn't affected by our truncation
     * on Android's POSIX layer).
     */
    private fun pruneLogFile(logFile: File) {
        runCatching {
            val len = logFile.length()
            if (len <= TRUNCATE_KEEP_BYTES) return@runCatching
            val keepFrom = len - TRUNCATE_KEEP_BYTES
            val tailBytes = RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(keepFrom)
                val buf = ByteArray(TRUNCATE_KEEP_BYTES.toInt())
                raf.readFully(buf)
                buf
            }
            // Truncate by rewriting — the engine's open file handle will
            // keep appending past our new EOF, which is fine.
            java.io.FileOutputStream(logFile).use { fos ->
                fos.write(tailBytes)
                fos.flush()
            }
            WepWawetLogger.appInfo("$TAG: pruned engine log",
                "old_size" to len,
                "new_size" to TRUNCATE_KEEP_BYTES)
        }.onFailure { e ->
            WepWawetLogger.appWarn("$TAG: prune failed",
                "exception" to e.javaClass.name)
        }
    }
}
