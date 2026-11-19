package com.musigatto.fosscore.musicxml

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * The order a score's measures are played in, with its repeats, endings and jumps, and the score
 * written in that order without any of them: what is played, for the MIDI and its timing map.
 * Verovio's own expansion missed the forms this reads (endings of several passes, a To Coda in an
 * ending, a repeat back after the coda), even from MuseScore's own MusicXML.
 *
 * - A section starts at a forward repeat (or the start); going back to it starts the next pass.
 * - An ending is played on the passes it names, read from its text ("1. 2.") before its number
 *   (MuseScore writes number="2" for "1. 2.").
 * - A backward repeat in an ending goes back while a later ending of the section has a later
 *   pass; out of an ending, it goes back once (or as its `times` says).
 * - To Coda is taken after a D.S. or D.C., or within an ending (it belongs to that pass).
 * - A final barline (light-heavy, no repeat) ends the piece; Fine ends it after a D.S. or D.C.
 * - "To" written before a sign is a jump to it: "To 𝄋" a D.S., "3. To ⊕" a To Coda.
 * - After a D.S. or D.C., repeats are not taken again, but those of the coda.
 * - A jump takes the tempo in force where it lands.
 */
object Unfold {
    /** The measures of [xml] (0 up, as the first part has them) in the order they are played. */
    fun order(xml: String): List<Int> = order(parse(xml))

    /**
     * [xml] with its measures in the order they are played and no repeat, ending or jump left;
     * a measure played again, and everything with an id in it, gets "-rend2", "-rend3"... after
     * its id, as Verovio names its own copies ([Playback] maps them back).
     */
    fun unroll(xml: String): String {
        val doc = parse(xml)
        val order = order(doc)
        doc.documentElement.childElements("part").forEachIndexed { p, part ->
            val measures = part.childElements("measure")
            if (measures.isEmpty()) return@forEachIndexed
            measures.forEach { part.removeChild(it) }
            // the tempo in force where each measure starts, and where it ends, in the score's order
            val tempoAtStart = arrayOfNulls<String>(measures.size)
            val tempoAtEnd = arrayOfNulls<String>(measures.size)
            var tempo: String? = null
            measures.forEachIndexed { i, measure ->
                tempoAtStart[i] = tempo
                measure.descendants("sound").lastOrNull { it.hasAttribute("tempo") }?.let { tempo = it.getAttribute("tempo") }
                tempoAtEnd[i] = tempo
            }
            val times = IntArray(measures.size)
            var previous = -1
            order.forEach { i ->
                val original = measures.getOrNull(i) ?: return@forEach
                times[i]++
                val copy = original.cloneNode(true) as Element
                if (times[i] > 1) rename(copy, "-rend${times[i]}")
                stripJumps(copy)
                // a jump takes the tempo of the place it goes to (the first part carries it for all)
                val start = tempoAtStart[i]
                if (p == 0 && previous >= 0 && previous != i - 1 && start != null && start != tempoAtEnd[previous]) {
                    val direction = doc.createElement("direction")
                    direction.appendChild(doc.createElement("sound").apply { setAttribute("tempo", start) })
                    copy.insertBefore(direction, copy.childElements().firstOrNull { it.nodeName != "barline" && it.nodeName != "attributes" && it.nodeName != "print" })
                }
                previous = i
                part.appendChild(copy)
            }
            pairOctaveShifts(part)
        }
        return serialize(doc)
    }

    private class Info(
        val forward: Boolean,
        val backward: Boolean,
        val times: Int,
        val endingStart: List<Int>?,
        val endingStop: Boolean,
        val finalBar: Boolean,
        val segno: Boolean,
        val coda: Boolean,
        val toCoda: Boolean,
        val dalSegno: Boolean,
        val daCapo: Boolean,
        val fine: Boolean,
    )

    private class Block(val start: Int, val end: Int, val numbers: List<Int>)

    private fun order(doc: Document): List<Int> {
        val part = doc.documentElement.childElements("part").firstOrNull() ?: return emptyList()
        val info = part.childElements("measure").map(::infoOf)
        val n = info.size
        // the endings: from their start to the measure that stops them
        val blocks = mutableListOf<Block>()
        var i = 0
        while (i < n) {
            val numbers = info[i].endingStart
            if (numbers != null) {
                var end = i
                while (end < n - 1 && !info[end].endingStop && info[end + 1].endingStart == null) end++
                // an ending stopped before the backward repeat that follows it, with no other ending
                // in between and another one right after it, goes on to it (MuseScore stops "1. 2."
                // after its first measure); a last ending ("2." of Savoir aimer) does not
                if (!info[end].backward) {
                    val back = (end + 1 until n).firstOrNull { info[it].backward || info[it].endingStart != null }
                    if (back != null && info[back].backward && info[back].endingStart == null && info.getOrNull(back + 1)?.endingStart != null) end = back
                }
                blocks += Block(i, end, numbers)
                i = end + 1
            } else {
                i++
            }
        }
        fun blockAt(m: Int) = blocks.firstOrNull { m in it.start..it.end }

        val order = mutableListOf<Int>()
        val backs = IntArray(n)
        var sectionStart = 0
        var pass = 1
        var jumped = false
        // where the coda reached after a jump starts (-1: none yet)
        var coda = -1
        var cameBack = false
        i = 0
        while (i < n && order.size < n * MAX_PASSES) {
            val block = blocks.firstOrNull { it.start == i }
            if (block != null && pass !in block.numbers && block.numbers.isNotEmpty()) {
                i = block.end + 1
                continue
            }
            if (info[i].forward && !cameBack) {
                sectionStart = i
                pass = 1
            }
            cameBack = false
            order += i
            val m = info[i]
            if (m.fine && jumped) break
            if (m.toCoda && (jumped || blockAt(i) != null)) {
                val target = (i + 1 until n).firstOrNull { info[it].coda }
                if (target != null) {
                    if (jumped) coda = target
                    i = target
                    continue
                }
            }
            // after a jump, only the repeats of the coda are played
            if (m.backward && (!jumped || (coda >= 0 && sectionStart >= coda))) {
                val inBlock = blockAt(i)
                val again = if (inBlock != null) {
                    blocks.any { it.start >= sectionStart && it.numbers.any { k -> k > pass } }
                } else {
                    backs[i] < m.times - 1
                }
                if (again) {
                    backs[i]++
                    pass++
                    i = sectionStart
                    cameBack = true
                    continue
                }
            }
            if (m.dalSegno && !jumped) {
                val segno = (0 until n).firstOrNull { info[it].segno }
                if (segno != null) {
                    jumped = true
                    i = segno
                    continue
                }
            }
            if (m.daCapo && !jumped) {
                jumped = true
                i = 0
                continue
            }
            if (m.finalBar && i < n - 1) break
            i++
        }
        return order
    }

    private fun infoOf(measure: Element): Info {
        val barlines = measure.childElements("barline")
        fun bar(location: String) = barlines.filter { (it.getAttribute("location").ifEmpty { "right" }) == location }
        val left = bar("left")
        val right = bar("right")
        val repeatBack = right.flatMap { it.childElements("repeat") }.firstOrNull { it.getAttribute("direction") == "backward" }
        val startEnding = (left + right).flatMap { it.childElements("ending") }.firstOrNull { it.getAttribute("type") == "start" }
        val sounds = measure.descendants("sound")
        val words = measure.descendants("words").joinToString(" ") { it.textContent.trim().lowercase() }
        fun sound(attribute: String) = sounds.any { it.hasAttribute(attribute) }
        val segnoSign = sound("segno") || measure.descendants("segno").isNotEmpty()
        val codaSign = sound("coda") || measure.descendants("coda").isNotEmpty()
        // "To" and a sign ("3. To ⊕", "To 𝄋"): the sign is where to go, not where one is
        val toWords = measure.descendants("words").map { it.textContent.trim().lowercase() }.filter { TO_SIGN.containsMatchIn(it) }
        val toSign = toWords.isNotEmpty()
        // the sign not named, only read by the OCR after "To": "$" or "§" a segno, "@" or "®" a coda
        val readSign = toWords.firstNotNullOfOrNull { w -> w.substringAfterLast("to").trim().firstOrNull() }
        val toSegnoRead = !segnoSign && !codaSign && readSign != null && readSign in SEGNO_READ
        val toCodaRead = !segnoSign && !codaSign && readSign != null && readSign in CODA_READ
        return Info(
            forward = left.flatMap { it.childElements("repeat") }.any { it.getAttribute("direction") == "forward" },
            backward = repeatBack != null,
            times = repeatBack?.getAttribute("times")?.toIntOrNull() ?: 2,
            endingStart = startEnding?.let { numbersOf(it) },
            endingStop = right.flatMap { it.childElements("ending") }.any { it.getAttribute("type") == "stop" || it.getAttribute("type") == "discontinue" },
            finalBar = repeatBack == null && right.flatMap { it.childElements("bar-style") }.any { it.textContent.trim() == "light-heavy" },
            segno = segnoSign && !(toSign && !codaSign),
            coda = codaSign && !sound("tocoda") && "to coda" !in words && !toSign,
            toCoda = sound("tocoda") || "to coda" in words || (toSign && codaSign) || toCodaRead,
            dalSegno = sound("dalsegno") || Regex("""\bd\.\s*s\.""").containsMatchIn(words) || (toSign && segnoSign && !codaSign) || toSegnoRead,
            daCapo = sound("dacapo") || Regex("""\bd\.\s*c\.""").containsMatchIn(words),
            fine = sound("fine") || Regex("""\bfine\b""").containsMatchIn(words),
        )
    }

    // the passes an ending is for: its text ("1. 2.") first, its number ("1, 2") else
    private fun numbersOf(ending: Element): List<Int> {
        val fromText = DIGITS.findAll(ending.textContent).map { it.value.toInt() }.toList()
        return fromText.ifEmpty { DIGITS.findAll(ending.getAttribute("number")).map { it.value.toInt() }.toList() }
    }

    // what the played score must not repeat or jump again
    private fun stripJumps(measure: Element) {
        for (barline in measure.childElements("barline")) {
            (barline.childElements("repeat") + barline.childElements("ending")).forEach { barline.removeChild(it) }
        }
        for (sound in measure.descendants("sound")) {
            listOf("segno", "coda", "tocoda", "dalsegno", "dacapo", "fine").forEach { sound.removeAttribute(it) }
        }
    }

    /**
     * Leaves out the octave lines of [part] that start and never stop, and the stops of none
     * (a reading may miss one; the measures unrolled may part them): Verovio 6.3 crashes making
     * the MIDI of a line that does not stop.
     */
    private fun pairOctaveShifts(part: Element) {
        val open = mutableMapOf<String, Element>()
        val unmatched = mutableListOf<Element>()
        for (shift in part.descendants("octave-shift")) {
            val number = shift.getAttribute("number").ifEmpty { "1" }
            if (shift.getAttribute("type") == "stop") {
                if (open.remove(number) == null) unmatched += shift
            } else {
                open.put(number, shift)?.let { unmatched += it }
            }
        }
        unmatched += open.values
        for (shift in unmatched) {
            val type = shift.parentNode as? Element ?: continue
            type.removeChild(shift)
            if (type.childElements().isNotEmpty()) continue
            val direction = type.parentNode as? Element ?: continue
            direction.removeChild(type)
            if (direction.childElements("direction-type").isEmpty()) direction.parentNode?.removeChild(direction)
        }
    }

    private fun rename(element: Element, suffix: String) {
        if (element.hasAttribute("id")) element.setAttribute("id", element.getAttribute("id") + suffix)
        element.childElements().forEach { rename(it, suffix) }
    }

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        return factory.newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun serialize(doc: Document): String {
        val out = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(doc), StreamResult(out))
        return out.toString()
    }

    private fun Element.childElements(name: String? = null): List<Element> {
        val out = mutableListOf<Element>()
        var node = firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && (name == null || node.nodeName == name)) out += node as Element
            node = node.nextSibling
        }
        return out
    }

    private fun Element.descendants(name: String): List<Element> =
        getElementsByTagName(name).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private val DIGITS = Regex("""\d+""")

    // words ending in "to", a sign drawn after them: "To" (segno), "3. To" (coda); the OCR may
    // read the sign too ("To $", "3. To @")
    private val TO_SIGN = Regex("""(^|[\s.])to[^a-z0-9]*$""")

    // what the OCR reads a segno and a coda sign as (Savoir aimer on the Tab 3: "$" and "@")
    private val SEGNO_READ = setOf('$', '§', '%', 's')
    private val CODA_READ = setOf('@', '®', '©', '⊕', 'ø', '0', 'o')

    // a score whose jumps would loop is cut after this many times its length
    private const val MAX_PASSES = 8
}
