package com.musigatto.fosscore.pdfscore

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

    init {
        val document = ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().first {
                !it.name.startsWith("META-INF") && (it.name.endsWith(".xml") || it.name.endsWith(".musicxml"))
            }
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            zip.getInputStream(entry).use { factory.newDocumentBuilder().parse(it) }
        }
        parts = document.getElementsByTagName("part").elements().associate { part ->
            val measures = part.getElementsByTagName("measure").elements()
            val staves = mutableMapOf<Int, MutableList<MutableList<String>>>()
            measures.forEachIndexed { index, measure ->
                measure.getElementsByTagName("note").elements().forEach { note ->
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
                }
            }
            staves.values.forEach { list -> while (list.size < measures.size) list += mutableListOf<String>() }
            part.getAttribute("id") to staves.mapValues { (_, list) -> list.map { it.sorted() } }
        }
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private fun Element.child(name: String): Element? =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == name }
}
