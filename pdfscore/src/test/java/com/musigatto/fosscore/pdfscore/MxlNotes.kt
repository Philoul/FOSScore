package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Pitch
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The written pitches of a MusicXML file (.mxl), per part, staff and measure: what the recognition
 * is checked against.
 */
class MxlNotes(file: File) {
    /** part id → staff (1 up) → measure (0 up) → the measure's pitches, sorted. */
    val parts: Map<String, Map<Int, List<List<String>>>>

    /**
     * The same with each note's written value and staccato: "E4 quarter. staccato", grace notes left out (what
     * [com.musigatto.fosscore.pdfscore.Rhythms] is checked against).
     */
    val timed: Map<String, Map<Int, List<List<String>>>>

    /**
     * The same with what [Signatures] reads of a measure: the key in force and the time signature
     * the measure prints, if any ("key -3 time 3/4"), alike on every staff of the part.
     */
    val signatures: Map<String, Map<Int, List<List<String>>>>

    init {
        val document = ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().first {
                !it.name.startsWith("META-INF") && (it.name.endsWith(".xml") || it.name.endsWith(".musicxml"))
            }
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            zip.getInputStream(entry).use { factory.newDocumentBuilder().parse(it) }
        }
        val timedParts = mutableMapOf<String, Map<Int, List<List<String>>>>()
        val signatureParts = mutableMapOf<String, Map<Int, List<List<String>>>>()
        parts = document.getElementsByTagName("part").elements().associate { part ->
            val measures = part.getElementsByTagName("measure").elements()
            var fifths = 0
            val signatures = measures.map { measure ->
                measure.getElementsByTagName("fifths").elements().firstOrNull()?.let { fifths = it.textContent.trim().toInt() }
                val time = measure.getElementsByTagName("time").elements().firstOrNull { it.getAttribute("print-object") != "no" }?.let {
                    if (it.child("beats") == null) null else "${it.child("beats")?.textContent?.trim()}/${it.child("beat-type")?.textContent?.trim()}"
                }
                listOf(Signatures.of(fifths, time))
            }
            val staves = mutableMapOf<Int, MutableList<MutableList<String>>>()
            val timedStaves = mutableMapOf<Int, MutableList<MutableList<String>>>()
            measures.forEachIndexed { index, measure ->
                measure.getElementsByTagName("note").elements().forEach { note ->
                    // a note the score does not print (no head, or hidden) is not on the page
                    if (note.getAttribute("print-object") == "no" || note.child("notehead")?.textContent?.trim() == "none") return@forEach
                    val staff = note.child("staff")?.textContent?.trim()?.toInt() ?: 1
                    val pitch = note.child("pitch")?.let { p ->
                        val alter = p.child("alter")?.textContent?.trim()?.toDouble()?.toInt() ?: 0
                        Pitch(Pitch.STEPS.indexOf(p.child("step")?.textContent?.trim() ?: "C"), p.child("octave")?.textContent?.trim()?.toInt() ?: 4, alter)
                    } ?: note.child("unpitched")?.let { u ->
                        Pitch(Pitch.STEPS.indexOf(u.child("display-step")?.textContent?.trim() ?: "C"), u.child("display-octave")?.textContent?.trim()?.toInt() ?: 4)
                    } ?: return@forEach // a rest
                    val list = staves.getOrPut(staff) { mutableListOf() }
                    while (list.size <= index) list += mutableListOf<String>()
                    list[index] += pitch.toString()
                    if (note.child("grace") == null) {
                        val dots = (0 until note.childNodes.length).count { (note.childNodes.item(it) as? Element)?.tagName == "dot" }
                        val timedList = timedStaves.getOrPut(staff) { mutableListOf() }
                        while (timedList.size <= index) timedList += mutableListOf<String>()
                        val staccato = note.getElementsByTagName("staccato").length > 0
                        timedList[index] += "$pitch ${note.child("type")?.textContent?.trim()}${".".repeat(dots)}${if (staccato) " staccato" else ""}"
                    }
                }
            }
            staves.values.forEach { list -> while (list.size < measures.size) list += mutableListOf<String>() }
            timedStaves.values.forEach { list -> while (list.size < measures.size) list += mutableListOf<String>() }
            timedParts[part.getAttribute("id")] = timedStaves.mapValues { (_, list) -> list.map { it.sorted() } }
            signatureParts[part.getAttribute("id")] = staves.keys.associateWith { signatures }
            part.getAttribute("id") to staves.mapValues { (_, list) -> list.map { it.sorted() } }
        }
        timed = timedParts
        signatures = signatureParts
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private fun Element.child(name: String): Element? =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == name }
}
