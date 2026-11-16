package io.wallpaperengine.weutil

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.8.28 (THE PLAIN-PATH FIX): the header-byte classifier that decides
 * image vs GIF vs video vs package — the old `contentResolver.getType`
 * answered null for every plain file path, so router_applied jpgs and
 * rule_picks imgs fell into the VIDEO branch and MediaPlayer.prepare
 * failed (0x1). These tests pin the classifier for every content shape
 * the app can apply, including WRONG EXTENSIONS (the bytes decide, not
 * the name) and corrupt/short files.
 */
class WallpaperFileSniffTest {

    private fun head(vararg bytes: Int): ByteArray =
        ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `png jpg bmp and webp are images`() {
        assertEquals(WallpaperFile.Kind.IMAGE,
            WallpaperFile.sniffBytes(head(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(WallpaperFile.Kind.IMAGE,
            WallpaperFile.sniffBytes(head(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(WallpaperFile.Kind.IMAGE,
            WallpaperFile.sniffBytes(head(0x42, 0x4D, 0x00, 0x00)))
        assertEquals(WallpaperFile.Kind.IMAGE,
            WallpaperFile.sniffBytes(head(
                'R'.code, 'I'.code, 'F'.code, 'F'.code,
                0x10, 0, 0, 0,
                'W'.code, 'E'.code, 'B'.code, 'P'.code)))
    }

    @Test
    fun `gif87a and gif89a are gifs`() {
        assertEquals(WallpaperFile.Kind.GIF,
            WallpaperFile.sniffBytes(head(
                'G'.code, 'I'.code, 'F'.code, '8'.code, '7'.code, 'a'.code)))
        assertEquals(WallpaperFile.Kind.GIF,
            WallpaperFile.sniffBytes(head(
                'G'.code, 'I'.code, 'F'.code, '8'.code, '9'.code, 'a'.code)))
    }

    @Test
    fun `mp4 matroska avi and mpeg streams are videos`() {
        // ISO-BMFF: "ftyp" at offset 4.
        assertEquals(WallpaperFile.Kind.VIDEO,
            WallpaperFile.sniffBytes(head(0, 0, 0, 0x20,
                'f'.code, 't'.code, 'y'.code, 'p'.code, 'i'.code, 's'.code)))
        // Matroska/WebM.
        assertEquals(WallpaperFile.Kind.VIDEO,
            WallpaperFile.sniffBytes(head(0x1A, 0x45, 0xDF, 0xA3, 0, 0)))
        // RIFF....AVI
        assertEquals(WallpaperFile.Kind.VIDEO,
            WallpaperFile.sniffBytes(head(
                'R'.code, 'I'.code, 'F'.code, 'F'.code,
                0x10, 0, 0, 0,
                'A'.code, 'V'.code, 'I'.code, ' '.code)))
        // MPEG-PS.
        assertEquals(WallpaperFile.Kind.VIDEO,
            WallpaperFile.sniffBytes(head(0x00, 0x00, 0x01, 0xBA)))
    }

    @Test
    fun `zip packages are packages (mpkg routing)`() {
        assertEquals(WallpaperFile.Kind.ZIP_PACKAGE,
            WallpaperFile.sniffBytes(head('P'.code, 'K'.code, 0x03, 0x04)))
    }

    @Test
    fun `wrong extensions do not matter — the bytes decide`() {
        // A JPEG named .img (the rule_picks naming) sniffs as an image.
        assertEquals(WallpaperFile.Kind.IMAGE,
            WallpaperFile.sniffBytes(head(0xFF, 0xD8, 0xFF, 0xE1)))
        // A PNG named .jpg still sniffs as an image.
        assertEquals(WallpaperFile.Kind.IMAGE,
            WallpaperFile.sniffBytes(head(0x89, 0x50, 0x4E, 0x47)))
    }

    @Test
    fun `corrupt and too-short files are other (honest failure)`() {
        assertEquals(WallpaperFile.Kind.OTHER, WallpaperFile.sniffBytes(head(1)))
        assertEquals(WallpaperFile.Kind.OTHER, WallpaperFile.sniffBytes(head(1, 2)))
        assertEquals(WallpaperFile.Kind.OTHER,
            WallpaperFile.sniffBytes(head(0xDE, 0xAD, 0xBE, 0xEF)))
    }
}
