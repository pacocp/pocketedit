package dev.pocketedit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class LineEnding(val chars: String, val label: String) {
    LF("\n", "LF"), CRLF("\r\n", "CRLF"), CR("\r", "CR")
}

/** Everything needed to write a file back exactly the way it was read. */
data class DocInfo(
    val uri: Uri?,
    val name: String,
    val lineEnding: LineEnding = LineEnding.LF,
    val bom: Boolean = false,
    val charset: String = UTF8,
    val lastModified: Long? = null,
) {
    val lang: Lang get() = Lang.forName(name)

    companion object {
        const val UTF8 = "UTF-8"
        const val LATIN1 = "ISO-8859-1"
        fun untitled() = DocInfo(uri = null, name = "Untitled.txt")
    }
}

data class FileMeta(val name: String?, val size: Long?, val lastModified: Long?)

class FileTooLargeException : IOException("File is larger than ${FileRepository.MAX_BYTES / (1024 * 1024)} MB")
class BinaryFileException : IOException("This looks like a binary file, not text")

/**
 * Reads and writes documents through the Storage Access Framework.
 * The same code handles local storage, SD cards, Downloads and Google Drive,
 * because each of them is just a DocumentsProvider behind a content:// Uri.
 */
class FileRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun takePermission(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, rw)
        } catch (_: SecurityException) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Not persistable (e.g. a one-off "Open with" grant). Fine for this session.
            }
        }
    }

    fun releasePermission(uri: Uri) {
        try {
            val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.releasePersistableUriPermission(uri, rw)
        } catch (_: Exception) {
        }
    }

    fun queryMeta(uri: Uri): FileMeta {
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun col(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }
                    return FileMeta(
                        name = col(OpenableColumns.DISPLAY_NAME)?.let { c.getString(it) },
                        size = col(OpenableColumns.SIZE)?.let { c.getLong(it) },
                        lastModified = col(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                            ?.let { c.getLong(it) }?.takeIf { it > 0 },
                    )
                }
            }
        } catch (_: Exception) {
            // Some providers don't support queries; fall through.
        }
        return FileMeta(null, null, null)
    }

    fun displayName(uri: Uri, meta: FileMeta = queryMeta(uri)): String =
        meta.name ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "file.txt"

    /** Returns the normalized text (always \n) plus the info needed to write it back. */
    fun read(uri: Uri): Pair<String, DocInfo> {
        val meta = queryMeta(uri)
        val bytes = resolver.openInputStream(uri)?.use { readLimited(it) }
            ?: throw IOException("Could not open the file")
        val decoded = decode(bytes)
        val info = DocInfo(
            uri = uri,
            name = displayName(uri, meta),
            lineEnding = decoded.lineEnding,
            bom = decoded.bom,
            charset = decoded.charset,
            lastModified = meta.lastModified,
        )
        return decoded.text to info
    }

    /** Reads the current content only (for conflict checks). */
    fun readTextOnly(uri: Uri): String {
        val bytes = resolver.openInputStream(uri)?.use { readLimited(it) }
            ?: throw IOException("Could not open the file")
        return decode(bytes).text
    }

    /**
     * Writes [text] back to [info].uri preserving line endings, BOM and charset.
     * Returns the updated info (charset may be upgraded to UTF-8, lastModified refreshed).
     */
    fun write(text: String, info: DocInfo): DocInfo {
        val uri = info.uri ?: throw IOException("No file location")
        var charsetName = info.charset
        if (charsetName != DocInfo.UTF8 && !Charset.forName(charsetName).newEncoder().canEncode(text)) {
            // Typed characters the old encoding can't hold: upgrade to UTF-8 rather than lose them.
            charsetName = DocInfo.UTF8
        }
        val withEndings = if (info.lineEnding == LineEnding.LF) text else text.replace("\n", info.lineEnding.chars)
        var bytes = withEndings.toByteArray(Charset.forName(charsetName))
        if (info.bom && charsetName == DocInfo.UTF8) bytes = UTF8_BOM + bytes

        writeBytes(uri, bytes)
        val meta = queryMeta(uri)
        return info.copy(
            charset = charsetName,
            lastModified = meta.lastModified,
            name = meta.name ?: info.name,
        )
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        // 1) "wt" = write + truncate. Supported by local storage and Google Drive.
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("null stream")
            out.use { it.write(bytes); it.flush() }
            return
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            // Provider doesn't support "wt"; try the next strategy.
        }

        // 2) "rw" + explicit truncate.
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("null descriptor")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                out.channel.truncate(0)
                out.write(bytes)
                out.flush()
            }
            return
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
        }

        // 3) Plain "w". Some providers don't truncate with it, so verify the size afterwards.
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the file for writing")
        out.use { it.write(bytes); it.flush() }
        val size = queryMeta(uri).size
        if (size != null && size > bytes.size) {
            throw IOException("The storage provider didn't shrink the file; old text may remain at the end. Use Save as.")
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw FileTooLargeException()
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    private data class Decoded(val text: String, val lineEnding: LineEnding, val bom: Boolean, val charset: String)

    private fun decode(bytes: ByteArray): Decoded {
        val bom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val offset = if (bom) 3 else 0

        val probeEnd = minOf(bytes.size, offset + 8000)
        for (i in offset until probeEnd) if (bytes[i] == 0.toByte()) throw BinaryFileException()

        var charset = DocInfo.UTF8
        val raw = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        } catch (_: CharacterCodingException) {
            // Not valid UTF-8: Latin-1 maps every byte, so the file round-trips unchanged.
            charset = DocInfo.LATIN1
            String(bytes, offset, bytes.size - offset, Charsets.ISO_8859_1)
        }

        val lineEnding = when {
            raw.contains("\r\n") -> LineEnding.CRLF
            raw.contains('\r') -> LineEnding.CR
            else -> LineEnding.LF
        }
        val text = if (lineEnding == LineEnding.LF) raw else raw.replace("\r\n", "\n").replace('\r', '\n')
        return Decoded(text, lineEnding, bom, charset)
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        fun locationLabel(uri: Uri?): String = when (uri?.authority) {
            null -> "Not saved yet"
            "com.google.android.apps.docs.storage",
            "com.google.android.apps.docs.storage.legacy" -> "Google Drive"
            "com.android.externalstorage.documents" -> "Phone storage"
            "com.android.providers.downloads.documents" -> "Downloads"
            "com.android.providers.media.documents" -> "Media"
            else -> uri.authority ?: "Unknown"
        }

        fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "txt", "" -> "text/plain"
                "md", "markdown" -> "text/markdown"
                "json" -> "application/json"
                "xml" -> "text/xml"
                "html", "htm" -> "text/html"
                "csv" -> "text/csv"
                else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: "application/octet-stream"
            }
        }
    }
}
