package io.wallpaperengine.weutil

import android.content.Context
import android.net.Uri
import com.wepwawet.log.WepWawetLogger
import java.io.File
import java.io.InputStream

/**
 * v0.8.28 (THE PLAIN-PATH FIX): one way to read and classify wallpaper
 * files — content URIs, file URIs, and bare absolute paths alike.
 *
 * The bug this replaces: `contentResolver.getType(Uri.parse(path))`
 * answers null for every plain file path (it only knows content://
 * URIs), so every router_applied .jpg and rule_picks .img file
 * fell into the VIDEO branch and MediaPlayer.prepare failed (0x1) —
 * and `contentResolver.openInputStream` on a scheme-less path throws
 * FileNotFoundException, so even the image branch could never decode.
 *
 * The type is sniffed from the file's HEADER BYTES, never from the
 * extension (wrong extensions are content too).
 */
object WallpaperFile {

    private const val TAG = "WallpaperFile"

    /** The sniffed kind of a wallpaper file. */
    enum class Kind { IMAGE, GIF, VIDEO, ZIP_PACKAGE, OTHER }

    /**
     * Open a read stream for any path shape:
     * content:// through the resolver; file:// and bare absolute paths
     * through the filesystem. Null when the file does not exist or
     * cannot be opened (the caller reports it).
     */
    fun open(context: Context, path: String): InputStream? {
        return try {
            when {
                path.startsWith("content://") ->
                    context.contentResolver.openInputStream(Uri.parse(path))
                path.startsWith("file://") -> {
                    val f = File(Uri.parse(path).path ?: return null)
                    if (f.isFile) f.inputStream() else null
                }
                else -> {
                    val f = File(path)
                    if (f.isFile) f.inputStream() else null
                }
            }
        } catch (t: Throwable) {
            WepWawetLogger.appWarn("$TAG: open failed",
                "path" to path.takeLast(56),
                "exception" to (t.message ?: t.javaClass.name))
            null
        }
    }

    /** Cheap existence/reachability check for any path shape. */
    fun exists(context: Context, path: String): Boolean {
        return try {
            when {
                path.startsWith("content://") ->
                    context.contentResolver.openInputStream(Uri.parse(path))?.use { true } ?: false
                path.startsWith("file://") ->
                    File(Uri.parse(path).path ?: return false).isFile
                else -> File(path).isFile
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Sniff the content kind from the first header bytes. Reads at most
     * 32 bytes; falls back to the extension ONLY when the bytes cannot
     * be read (a file we cannot read at all is reported OTHER — callers
     * treat that as unreadable).
     */
    fun sniff(context: Context, path: String): Kind {
        val head = try {
            val out = ByteArray(32)
            open(context, path)?.use { ins ->
                var read = 0
                while (read < out.size) {
                    val n = ins.read(out, read, out.size - read)
                    if (n < 0) break
                    read += n
                }
                out.copyOf(read)
            } ?: return Kind.OTHER
        } catch (t: Throwable) {
            return Kind.OTHER
        }
        return sniffBytes(head).also {
            WepWawetLogger.appInfo("$TAG: sniffed",
                "path" to path.takeLast(56),
                "kind" to it.name,
                "head_len" to head.size)
        }
    }

    /** The pure byte-level classifier (unit-testable, no Android). */
    fun sniffBytes(head: ByteArray): Kind {
        if (head.size < 4) return Kind.OTHER
        // PNG
        if (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        ) return Kind.IMAGE
        // JPEG
        if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
            head[2] == 0xFF.toByte()
        ) return Kind.IMAGE
        // GIF87a / GIF89a
        if (head.size >= 6 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == '8'.code.toByte()
        ) return Kind.GIF
        // WEBP / AVI: RIFF containers
        if (head.size >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte()
        ) {
            val form = String(head, 8, 4, Charsets.US_ASCII)
            return when {
                form == "WEBP" -> Kind.IMAGE
                form == "AVI " -> Kind.VIDEO
                else -> Kind.OTHER
            }
        }
        // BMP
        if (head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()) return Kind.IMAGE
        // ISO-BMFF (mp4/m4v/mov/3gp): "ftyp" at offset 4
        if (head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") return Kind.VIDEO
        // Matroska / WebM
        if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        ) return Kind.VIDEO
        // MPEG-PS / MPEG-TS streams
        if (head[0] == 0x00.toByte() && head[1] == 0x00.toByte() &&
            head[2] == 0x01.toByte() &&
            (head[3] == 0xBA.toByte() || head[3] == 0xB3.toByte())
        ) return Kind.VIDEO
        // ZIP (an .mpkg/.pkg package — the service routes those to the
        // scene/package paths before the view ever sees them).
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return Kind.ZIP_PACKAGE
        return Kind.OTHER
    }
}
