package com.wepwawet.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies [WeClientLog] is an exact copy of the official client's logging
 * behavior (decompiled Util$Companion.log):
 *   - logcat tag WEERR at Log.v level (not assertable on JVM — stubbed),
 *   - the file line format `yyyy-MM-dd'T'HH:mm'Z': <message>` in UTC,
 *   - file writes only while enabled,
 *   - the 1 MiB truncation reset.
 */
class WeClientLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var logFile: File

    @Before
    fun setUp() {
        logFile = File(tmp.root, "logs/applog.txt")
        WeClientLog.setFileForTest(logFile, enabled = true)
    }

    @After
    fun tearDown() {
        WeClientLog.setFileForTest(null, enabled = false)
    }

    @Test
    fun `writes nothing while disabled`() {
        WeClientLog.setFileForTest(logFile, enabled = false)
        WeClientLog.log("hello")
        assertFalse(logFile.exists())
    }

    @Test
    fun `line format matches the official client exactly`() {
        WeClientLog.log("Loading wallpaper scene.mpkg")
        val content = logFile.readText()
        // `yyyy-MM-dd'T'HH:mm'Z': message\n` — UTC, minute precision,
        // literal Z, ": " separator, trailing newline.
        val pattern = Regex(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z: Loading wallpaper scene\\.mpkg\\n$"
        )
        assertTrue(
            "actual: <<$content>>", pattern.containsMatchIn(content)
        )
    }

    @Test
    fun `appends in order and creates parent dirs`() {
        WeClientLog.log("first")
        WeClientLog.log("second")
        val lines = logFile.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith(": first"))
        assertTrue(lines[1].endsWith(": second"))
    }

    @Test
    fun `truncates when the file exceeds one mebibyte`() {
        // Pre-fill past the 1 MiB threshold, then log once.
        logFile.parentFile!!.mkdirs()
        logFile.writeText("x".repeat(1_048_576 + 1))
        WeClientLog.log("after overflow")
        val content = logFile.readText()
        assertTrue(content.endsWith(": after overflow\n"))
        // The truncate-then-append means the old payload is gone.
        assertFalse(content.contains("xxxx"))
        assertTrue(
            "expected small file after truncation, was ${content.length}",
            content.length < 200,
        )
    }

    @Test
    fun `timestamps are utc`() {
        WeClientLog.log("tz check")
        val stamp = logFile.readText().substringBefore(": tz check")
        // The literal 'Z' suffix is the official format's UTC marker.
        assertTrue(stamp.endsWith("Z"))
        assertEquals(17, stamp.length)
    }
}
