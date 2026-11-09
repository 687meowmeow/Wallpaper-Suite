package com.wepwawet.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The official Wallpaper Engine client's logging, copied 1:1 from the
 * decompiled `io.wallpaperengine.weutil.Util$Companion.log()` so that our
 * log file and logcat output are indistinguishable from the real app's —
 * exactly what the user asked for ("copy the logging from the real app,
 * copy it exactly, so it's easier to find issues with wallpapers").
 *
 * Ground truth (decompiled official client, v2.8.8):
 * ```kotlin
 * fun log(str: String) {
 *     Log.v("WEERR", str)
 *     if (logToFileEnabled) {
 *         synchronized(this) {
 *             try {
 *                 val file = File(logFilePath)
 *                 if (!file.exists()) { file.parentFile?.mkdirs(); file.createNewFile() }
 *                 if (file.canWrite()) {
 *                     if (file.length() > 1048576) {
 *                         RandomAccessFile(file, "rw").setLength(0L)   // 1 MiB truncate
 *                     }
 *                     val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.getDefault())
 *                     fmt.timeZone = TimeZone.getTimeZone("UTC")
 *                     file.appendText(fmt.format(Date()) + ": " + str + "\n")
 *                 }
 *             } catch (_: Exception) {}
 *         }
 *     }
 * }
 * ```
 *
 * Differences from the official implementation, all deliberate:
 *  - `logFilePath` is derived per-call from a stored application context
 *    (the official stores a bare String set by setLogToFile).
 *  - A test hook ([setFileForTest]) lets unit tests exercise the format
 *    and the 1 MiB truncation without an Android Context.
 */
object WeClientLog {

    /** The official logcat tag — Log.v level, exactly as the real app uses. */
    const val TAG = "WEERR"

    /** The official log file location (relative to filesDir). */
    const val LOG_RELATIVE_PATH = "/logs/applog.txt"

    /** The official truncation threshold: 1 MiB, then the file resets. */
    private const val MAX_LOG_BYTES = 1_048_576L

    /** The official timestamp format — UTC, minute precision, literal Z. */
    private const val TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm'Z'"

    @Volatile
    private var logToFileEnabled: Boolean = false

    @Volatile
    private var logFile: File? = null

    /** Test seam: fixed file + forced enabled state. Pass null to clear. */
    fun setFileForTest(file: File?, enabled: Boolean) {
        logFile = file
        logToFileEnabled = enabled
    }

    /**
     * Mirrors the official `Util.setLogToFile(context, enabled)`: flips the
     * flag and derives `<filesDir>/logs/applog.txt` when enabling.
     */
    fun setLogToFile(context: Context, enabled: Boolean) {
        logToFileEnabled = enabled
        logFile = if (enabled) {
            File(context.filesDir.absolutePath + LOG_RELATIVE_PATH)
        } else {
            null
        }
    }

    /** Mirrors `Util.setLogToFileEnabled` (flag only, path unchanged). */
    fun setLogToFileEnabled(enabled: Boolean) {
        logToFileEnabled = enabled
    }

    fun isLogToFileEnabled(): Boolean = logToFileEnabled

    /**
     * The official log call: logcat `WEERR` (Log.v) always; the file append
     * only while file logging is on (the real app's "Write Errors to Log"
     * setting, `general_log_errors`).
     */
    fun log(str: String) {
        Log.v(TAG, str)
        if (!logToFileEnabled) return
        val file = logFile ?: return
        synchronized(this) {
            try {
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }
                if (file.canWrite()) {
                    if (file.length() > MAX_LOG_BYTES) {
                        RandomAccessFile(file, "rw").use { it.setLength(0L) }
                    }
                    val fmt = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.getDefault())
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                    file.appendText(fmt.format(Date()) + ": " + str + "\n")
                }
            } catch (_: Exception) {
                // exactly like the official client: never crash on logging
            }
        }
    }
}
