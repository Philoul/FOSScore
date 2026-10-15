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

data class Part(val id: String, val name: String)

/**
 * A MusicXML score kept as a DOM, so parts can be hidden before handing it to Verovio.
 * Files exported from MuseScore often contain the whole arrangement (voice, piano, strings,
 * drums...) while the musician only wants to read one or two parts.
 */
class MusicXml private constructor(private val xml: ByteArray) {
    private val doc: Document = build(xml)

    val parts: List<Part> = doc.documentElement.child("part-list")
        ?.children("score-part").orEmpty()
        .map { Part(it.getAttribute("id"), it.child("part-name")?.textContent?.trim().orEmpty()) }

    /** The score with only [keep] parts (all parts when [keep] is empty), as XML text. */
    fun serialize(keep: Set<String>): String {
        // Android's DOM cannot clone a Document: filter a fresh parse instead.
        val copy = if (keep.isEmpty()) doc else build(xml)
        val root = copy.documentElement
        if (keep.isNotEmpty()) {
            root.child("part-list")?.let { list ->
                list.children("score-part").filter { it.getAttribute("id") !in keep }
                    .forEach { list.removeChild(it) }
                // Brackets and braces would point at removed parts.
                list.children("part-group").forEach { list.removeChild(it) }
            }
            root.children("part").filter { it.getAttribute("id") !in keep }.forEach { root.removeChild(it) }
        }
        val out = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(copy), StreamResult(out))
        return out.toString()
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
            return builder.parse(ByteArrayInputStream(xml))
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

        private fun Element.children(tag: String): List<Element> {
            val out = mutableListOf<Element>()
            var n = firstChild
            while (n != null) {
                if (n is Element && n.tagName == tag) out += n
                n = n.nextSibling
            }
            return out
        }

        private fun Element.child(tag: String): Element? = children(tag).firstOrNull()
    }
}
