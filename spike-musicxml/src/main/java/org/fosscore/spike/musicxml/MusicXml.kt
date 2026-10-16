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

/**
 * [visible] is false when every staff of the part is marked invisible (MuseScore "hide staff").
 * [program] is the General MIDI program (1-128) from the file, [drums] is true on MIDI channel 10.
 */
data class Part(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val program: Int? = null,
    val drums: Boolean = false
)

/** Where lyrics are drawn. */
enum class LyricsPlacement(val label: String) {
    FILE("as in file"),
    ABOVE("above staff"),
    BELOW("below staff"),
    /** Below the lowest staff of the part: for a piano part, under the left hand. */
    BOTTOM("under system")
}

/** What to change in the score before it goes to Verovio. */
data class ScoreFilter(
    /** Parts to keep; empty keeps all. */
    val parts: Set<String> = emptySet(),
    val lyrics: LyricsPlacement = LyricsPlacement.FILE,
    /** General MIDI program (1-128) per part id, overriding the file. */
    val programs: Map<String, Int> = emptyMap()
)

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
            val instrument = it.child("midi-instrument")
            Part(
                id,
                it.child("part-name")?.textContent?.trim().orEmpty(),
                id !in hidden,
                instrument?.child("midi-program")?.textContent?.trim()?.toIntOrNull(),
                instrument?.child("midi-channel")?.textContent?.trim() == "10"
            )
        }
    }

    /** The first tempo of the score in quarter notes per minute, if the file gives one. */
    val tempo: Double? = doc.getElementsByTagName("sound").let { sounds ->
        (0 until sounds.length).map { sounds.item(it) as Element }
            .firstNotNullOfOrNull { it.getAttribute("tempo").toDoubleOrNull() }
    }

    /** True when most lyrics of the file ask to be drawn above the staff. */
    private val lyricsAboveInFile: Boolean = doc.getElementsByTagName("lyric").let { lyrics ->
        val places = (0 until lyrics.length).map { (lyrics.item(it) as Element).getAttribute("placement") }
        places.count { it == "above" } > places.size / 2
    }

    /** Whether lyrics must be drawn above the staff for [placement]. */
    fun lyricsAbove(placement: LyricsPlacement): Boolean = when (placement) {
        LyricsPlacement.FILE -> lyricsAboveInFile
        LyricsPlacement.ABOVE -> true
        LyricsPlacement.BELOW, LyricsPlacement.BOTTOM -> false
    }

    /**
     * Size of each first-page credit (title, composer...), in percent of the file's default word
     * font, in the order Verovio turns them into page-header lines. Null when the file gives none.
     */
    val creditSizes: List<Int?> = doc.documentElement.let { root ->
        val wordSize = root.child("defaults")?.child("word-font")?.getAttribute("font-size")?.toFloatOrNull()
            ?.takeIf { it > 0 } ?: 10f
        root.children("credit").filter { it.getAttribute("page") == "1" }.flatMap { it.children("credit-words") }
            .map { words -> words.getAttribute("font-size").toFloatOrNull()?.let { Math.round(it / wordSize * 100) } }
    }

    /** The score changed as [filter] asks, as XML text. */
    fun serialize(filter: ScoreFilter): String {
        // Android's DOM cannot clone a Document, and parsing it again costs more than a second on
        // slow tablets: change the DOM in place, serialize, then undo every change in reverse.
        val undo = mutableListOf<() -> Unit>()
        try {
            val root = doc.documentElement
            val keep = filter.parts
            if (keep.isNotEmpty()) {
                val kept = root.children("part").filter { it.getAttribute("id") in keep }
                val removed = root.children("part").filter { it.getAttribute("id") !in keep }
                // MuseScore writes tempo marks in the first part only, often the hidden voice.
                kept.firstOrNull()?.let { if (kept.none(::hasTempo)) copyTempo(removed, it, undo) }
                root.child("part-list")?.let { list ->
                    detach(list, undo) {
                        // Brackets and braces would point at removed parts.
                        it.tagName == "part-group" || (it.tagName == "score-part" && it.getAttribute("id") !in keep)
                    }
                }
                detach(root, undo) { it.tagName == "part" && it.getAttribute("id") !in keep }
            }
            placeLyrics(filter.lyrics, undo)
            setPrograms(filter.programs, undo)
            val out = StringWriter()
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }.transform(DOMSource(doc), StreamResult(out))
            return out.toString()
        } finally {
            undo.asReversed().forEach { it() }
        }
    }

    // Detach in document order so that, restored in reverse, each node's next sibling is back.
    private fun detach(parent: Element, undo: MutableList<() -> Unit>, drop: (Element) -> Boolean) {
        parent.children().filter(drop).forEach { child ->
            val next = child.nextSibling
            parent.removeChild(child)
            undo += { parent.insertBefore(child, next) }
        }
    }

    private fun setText(element: Element, text: String, undo: MutableList<() -> Unit>) {
        val old = element.textContent
        element.textContent = text
        undo += { element.textContent = old }
    }

    // Above or below the staff is set later in the MEI (MeiFixes): Verovio's MusicXML import
    // ignores <lyric placement>. Only "under the system" needs a change in the MusicXML.
    private fun placeLyrics(placement: LyricsPlacement, undo: MutableList<() -> Unit>) {
        if (placement != LyricsPlacement.BOTTOM) return
        // Move the hidden voices that carry lyrics to the lowest staff of their part. Their notes
        // are invisible, so only the lyrics move; the visible notes stay where they are.
        for (part in doc.documentElement.children("part")) {
            val staves = part.child("measure")?.children("attributes").orEmpty()
                .firstNotNullOfOrNull { it.child("staves")?.textContent?.trim()?.toIntOrNull() } ?: 1
            if (staves < 2) continue
            val hiddenNotes = part.children("measure").flatMap { it.children("note") }
                .filter { it.getAttribute("print-object") == "no" }
            val voices = hiddenNotes.filter { it.child("lyric") != null }.mapNotNull { it.child("voice")?.textContent }.toSet()
            hiddenNotes.filter { it.child("voice")?.textContent in voices }.forEach { note ->
                note.child("staff")?.let { setText(it, staves.toString(), undo) }
            }
        }
    }

    private fun setPrograms(programs: Map<String, Int>, undo: MutableList<() -> Unit>) {
        if (programs.isEmpty()) return
        doc.documentElement.child("part-list")?.children("score-part").orEmpty().forEach { scorePart ->
            val program = programs[scorePart.getAttribute("id")] ?: return@forEach
            scorePart.children("midi-instrument").forEach { instrument ->
                instrument.child("midi-program")?.let { setText(it, program.toString(), undo) }
            }
        }
    }

    private fun isTempo(e: Element): Boolean = when (e.tagName) {
        "sound" -> e.hasAttribute("tempo")
        "direction" -> e.child("sound")?.hasAttribute("tempo") == true || e.getElementsByTagName("metronome").length > 0
        else -> false
    }

    private fun hasTempo(part: Element) = part.children("measure").any { m -> m.children().any(::isTempo) }

    /**
     * Copies the tempo marks of [from] into [to], at the same measure and beat, so the tempo and
     * its mark survive hiding the part that holds them.
     */
    private fun copyTempo(from: List<Element>, to: Element, undo: MutableList<() -> Unit>) {
        val marks = from.flatMap(::tempoMarks).distinctBy { it.measure to it.quarters }
        if (marks.isEmpty()) return
        val measures = to.children("measure")
        var divisions = 1
        measures.forEachIndexed { index, measure ->
            measure.children("attributes").forEach { a ->
                a.child("divisions")?.textContent?.trim()?.toIntOrNull()?.let { divisions = it }
            }
            for (mark in marks.filter { it.measure == index }) {
                val copy = mark.element.cloneNode(true) as Element
                if (copy.tagName == "direction") {
                    copy.children("staff").forEach { copy.removeChild(it) }
                    copy.children("voice").forEach { copy.removeChild(it) }
                    copy.children("offset").forEach { copy.removeChild(it) }
                    val offset = Math.round(mark.quarters * divisions).toInt()
                    if (offset > 0) {
                        val element = doc.createElement("offset").apply { textContent = offset.toString() }
                        // Schema order: direction-type+, offset?, ..., voice?, staff?, sound?
                        val before = copy.children().firstOrNull { it.tagName !in setOf("direction-type") }
                        copy.insertBefore(element, before)
                    }
                }
                // At the start of the measure, after its attributes and print settings.
                val first = measure.children().firstOrNull { it.tagName !in setOf("attributes", "print") }
                measure.insertBefore(copy, first)
                undo += { measure.removeChild(copy) }
            }
        }
    }

    private class TempoMark(val measure: Int, val quarters: Double, val element: Element)

    private fun tempoMarks(part: Element): List<TempoMark> {
        val out = mutableListOf<TempoMark>()
        var divisions = 1
        part.children("measure").forEachIndexed { index, measure ->
            var time = 0
            for (e in measure.children()) {
                val duration = e.child("duration")?.textContent?.trim()?.toIntOrNull() ?: 0
                when (e.tagName) {
                    "attributes" -> e.child("divisions")?.textContent?.trim()?.toIntOrNull()?.let { divisions = it }
                    "note" -> if (e.child("chord") == null) time += duration
                    "backup" -> time -= duration
                    "forward" -> time += duration
                    else -> if (isTempo(e)) out += TempoMark(index, time.toDouble() / divisions, e)
                }
            }
        }
        return out
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
