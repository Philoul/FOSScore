package org.fosscore.spike.musicxml

import java.io.ByteArrayInputStream
import java.io.StringReader
import java.io.StringWriter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** [visible] is false when every staff of the part is marked invisible (MuseScore "hide staff"). */
data class Part(val id: String, val name: String, val visible: Boolean = true)

/**
 * A MusicXML score kept as a DOM, so parts can be hidden before handing it to Verovio.
 * Files exported from MuseScore often contain the whole arrangement (voice, piano, strings,
 * drums...) while the musician only wants to read one or two parts.
 */
class MusicXml private constructor(private val xml: ByteArray) {
    private val doc: Document = build(xml)

    val parts: List<Part> = doc.documentElement.let { root ->
        val hidden = root.children("part").filter { it.allStavesHidden() }.map { it.getAttribute("id") }.toSet()
        root.child("part-list")?.children("score-part").orEmpty().map {
            val id = it.getAttribute("id")
            Part(id, it.child("part-name")?.textContent?.trim().orEmpty(), id !in hidden)
        }
    }

    /** The score with only [keep] parts (all parts when [keep] is empty), as XML text. */
    fun serialize(keep: Set<String>): String {
        // Android's DOM cannot clone a Document, and parsing it again costs more than a second on
        // slow tablets: detach the other parts, serialize, then put them back where they were.
        val root = doc.documentElement
        val detached = mutableListOf<Triple<Element, org.w3c.dom.Node?, Element>>()
        // Detach in document order so that, restored in reverse, each node's next sibling is back.
        fun detach(parent: Element, drop: (Element) -> Boolean) {
            parent.children().filter(drop).forEach { child ->
                detached += Triple(parent, child.nextSibling, child)
                parent.removeChild(child)
            }
        }
        if (keep.isNotEmpty()) {
            root.child("part-list")?.let { list ->
                detach(list) {
                    // Brackets and braces would point at removed parts.
                    it.tagName == "part-group" || (it.tagName == "score-part" && it.getAttribute("id") !in keep)
                }
            }
            detach(root) { it.tagName == "part" && it.getAttribute("id") !in keep }
        }
        try {
            val out = StringWriter()
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }.transform(DOMSource(doc), StreamResult(out))
            return out.toString()
        } finally {
            detached.asReversed().forEach { (parent, next, child) -> parent.insertBefore(child, next) }
        }
    }

    companion object {
        /** Parses plain MusicXML or compressed .mxl. Never touches the network (no DTD loading). */
        fun parse(bytes: ByteArray): MusicXml = MusicXml(if (isZip(bytes)) unzipRootFile(bytes) else bytes)

        private fun build(xml: ByteArray): Document {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isValidating = false
                isNamespaceAware = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            return builder.parse(ByteArrayInputStream(xml)).also { hideInvisibleNotes(it) }
        }

        /**
         * MuseScore hides a note either with print-object="no" or, for a vocal line kept only for
         * its lyrics, with no notehead and no stem. Verovio still draws the beams, ledger lines,
         * ties, slurs and accidentals of such notes: mark them invisible and drop what hangs on them.
         * The lyrics stay.
         */
        private fun hideInvisibleNotes(doc: Document) {
            val notes = doc.getElementsByTagName("note")
            for (i in 0 until notes.length) {
                val note = notes.item(i) as Element
                val headless = note.child("notehead")?.textContent?.trim() == "none" &&
                    note.child("stem")?.textContent?.trim() == "none"
                if (!headless && note.getAttribute("print-object") != "no") continue
                note.setAttribute("print-object", "no")
                note.children().filter { it.tagName in HIDDEN_NOTE_MARKS }.forEach { note.removeChild(it) }
                note.children("notations").forEach { notations ->
                    notations.children().filter { it.tagName in HIDDEN_NOTE_MARKS }.forEach { notations.removeChild(it) }
                }
            }
        }

        private val HIDDEN_NOTE_MARKS = setOf("beam", "tie", "tied", "slur", "accidental")

        /** True when the first measure marks every staff of this part invisible. */
        private fun Element.allStavesHidden(): Boolean {
            val attributes = child("measure")?.children("attributes").orEmpty()
            val staves = attributes.firstNotNullOfOrNull { it.child("staves")?.textContent?.trim()?.toIntOrNull() } ?: 1
            val hidden = attributes.flatMap { it.children("staff-details") }
                .filter { it.getAttribute("print-object") == "no" }
                .map { it.getAttribute("number").toIntOrNull() }
            return null in hidden || (1..staves).all { it in hidden }
        }

        private fun isZip(b: ByteArray) = b.size > 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

        // .mxl: META-INF/container.xml names the score file inside the archive.
        private fun unzipRootFile(bytes: ByteArray): ByteArray {
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                }
            }
            val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            val rootPath = container?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            return rootPath?.let { entries[it] }
                ?: entries.entries.first { !it.key.startsWith("META-INF") && it.key.endsWith("xml") }.value
        }

        private fun Element.children(tag: String? = null): List<Element> {
            val out = mutableListOf<Element>()
            var n = firstChild
            while (n != null) {
                if (n is Element && (tag == null || n.tagName == tag)) out += n
                n = n.nextSibling
            }
            return out
        }

        private fun Element.child(tag: String): Element? = children(tag).firstOrNull()
    }
}
