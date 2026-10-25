package com.musigatto.fosscore.score

import com.musigatto.fosscore.musicxml.MusicXmlFiles
import java.io.File

/**
 * The kind of file behind a sheet. It is told by the extension of the copy kept in the app's
 * files: the importer names that copy after what the file really holds.
 *
 * ponytail: a `format` column in the sheets table would say the same thing; it comes with the
 * first schema change that needs a migration anyway (per-score MusicXML settings).
 */
enum class SheetFormat(val extension: String) {
    PDF("pdf"),
    MUSICXML("musicxml"),
    MXL("mxl");

    val isMusicXml: Boolean get() = this != PDF

    companion object {
        /** MIME types offered by the file picker. Many apps hand MusicXML files over as plain XML or octet-stream. */
        val PICKER_TYPES = arrayOf(
            "application/pdf",
            "application/vnd.recordare.musicxml",
            "application/vnd.recordare.musicxml+xml",
            "application/xml",
            "text/xml",
            "application/octet-stream",
        )

        fun of(path: String): SheetFormat = when (path.substringAfterLast('.', "").lowercase()) {
            MUSICXML.extension, "xml" -> MUSICXML
            MXL.extension -> MXL
            else -> PDF
        }

        /**
         * What [file] holds, from its content: a PDF, plain or compressed MusicXML, or null for
         * anything else.
         */
        fun detect(file: File): SheetFormat? {
            val head = ByteArray(1024)
            val n = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
            // a PDF may start with a few bytes of junk before its header; readers accept it
            if (String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")) return PDF
            val bytes = file.readBytes()
            if (!MusicXmlFiles.looksLikeMusicXml(bytes)) return null
            return if (MusicXmlFiles.isCompressed(bytes)) MXL else MUSICXML
        }
    }
}
