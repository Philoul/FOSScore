package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Pitch
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The written pitches of a MusicXML score, per part, staff and measure: what the recognition is
 * checked against (a reference .mxl file), and what it writes (read back the same way).
 */
class MxlNotes(document: org.w3c.dom.Document) {
    /** A compressed MusicXML file (.mxl). */
    constructor(file: File) : this(
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().first {
                !it.name.startsWith("META-INF") && (it.name.endsWith(".xml") || it.name.endsWith(".musicxml"))
            }
            zip.getInputStream(entry).use { builder().parse(it) }
        },
    )

    /** An uncompressed MusicXML score. */
    constructor(xml: String) : this(builder().parse(xml.byteInputStream()))

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

    /** The same with each chord's beams, on its first note: "E4 1:begin 2:begin", grace notes left out. */
    val beamed: Map<String, Map<Int, List<List<String>>>>

    /** The same with only the notes a tie starts from: their pitches. */
    val tied: Map<String, Map<Int, List<List<String>>>>

    /** The same with only the notes that show an accidental: "F#4 sharp" (cautionary ones too). */
    val shown: Map<String, Map<Int, List<List<String>>>>

    /** Measure (0 up) → the syllables sung in it, all parts together, sorted. */
    val sung: List<List<String>>

    init {
        val shownParts = mutableMapOf<String, Map<Int, List<List<String>>>>()
        val tiedParts = mutableMapOf<String, Map<Int, List<List<String>>>>()
        val timedParts = mutableMapOf<String, Map<Int, List<List<String>>>>()
        val beamedParts = mutableMapOf<String, Map<Int, List<List<String>>>>()
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
            val beamedStaves = mutableMapOf<Int, MutableList<MutableList<String>>>()
            val tiedStaves = mutableMapOf<Int, MutableList<MutableList<String>>>()
            val shownStaves = mutableMapOf<Int, MutableList<MutableList<String>>>()
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
                        if (note.child("chord") == null) {
                            val beams = note.getElementsByTagName("beam").elements().joinToString("") { " ${it.getAttribute("number")}:${it.textContent.trim()}" }
                            val beamedList = beamedStaves.getOrPut(staff) { mutableListOf() }
                            while (beamedList.size <= index) beamedList += mutableListOf<String>()
                            beamedList[index] += "$pitch$beams"
                        }
                        val tiedList = tiedStaves.getOrPut(staff) { mutableListOf() }
                        while (tiedList.size <= index) tiedList += mutableListOf<String>()
                        if (note.getElementsByTagName("tie").elements().any { it.getAttribute("type") == "start" }) tiedList[index] += pitch.toString()
                        val shownList = shownStaves.getOrPut(staff) { mutableListOf() }
                        while (shownList.size <= index) shownList += mutableListOf<String>()
                        note.child("accidental")?.let { shownList[index] += "$pitch ${it.textContent.trim()}" }
                    }
                }
            }
            staves.values.forEach { list -> while (list.size < measures.size) list += mutableListOf<String>() }
            timedStaves.values.forEach { list -> while (list.size < measures.size) list += mutableListOf<String>() }
            beamedStaves.values.forEach { list -> while (list.size < measures.size) list += mutableListOf<String>() }
            shownStaves.values.forEach { list -> while (list.size < measures.size) list += mutableListOf<String>() }
            shownParts[part.getAttribute("id")] = shownStaves.mapValues { (_, list) -> list.map { it.sorted() } }
            tiedStaves.values.forEach { list -> while (list.size < measures.size) list += mutableListOf<String>() }
            tiedParts[part.getAttribute("id")] = tiedStaves.mapValues { (_, list) -> list.map { it.sorted() } }
            beamedParts[part.getAttribute("id")] = beamedStaves.mapValues { (_, list) -> list.map { it.sorted() } }
            timedParts[part.getAttribute("id")] = timedStaves.mapValues { (_, list) -> list.map { it.sorted() } }
            signatureParts[part.getAttribute("id")] = staves.keys.associateWith { signatures }
            part.getAttribute("id") to staves.mapValues { (_, list) -> list.map { it.sorted() } }
        }
        timed = timedParts
        signatures = signatureParts
        beamed = beamedParts
        tied = tiedParts
        shown = shownParts
        // the first verse's syllables of every part, measure by measure (the PDF may sing them over
        // another staff than the MusicXML's voice part)
        val measureCount = document.getElementsByTagName("part").elements().maxOfOrNull { it.getElementsByTagName("measure").length } ?: 0
        val syllables = List(measureCount) { mutableListOf<String>() }
        document.getElementsByTagName("part").elements().forEach { part ->
            part.getElementsByTagName("measure").elements().forEachIndexed { index, measure ->
                measure.getElementsByTagName("lyric").elements()
                    .filter { it.getAttribute("number").ifEmpty { "1" } == "1" }
                    .forEach { lyric -> lyric.child("text")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { syllables[index] += it } }
            }
        }
        sung = syllables.map { it.sorted() }
    }


    /**
     * Every part's staves one after the other (a piano's two, then the next part's), numbered from
     * 1 again: a score written from a PDF, whose parts are its staves, compared as one.
     */
    fun allStaves(of: Map<String, Map<Int, List<List<String>>>>): Map<Int, List<List<String>>> =
        of.values.flatMap { staves -> staves.toSortedMap().values }.withIndex().associate { (i, measures) -> i + 1 to measures }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private fun Element.child(name: String): Element? =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == name }

    private companion object {
        fun builder() = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder()
    }
}
