package com.musigatto.fosscore.musicxml

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class StaccatoPlayedTest {

    // divisions 4: a staccato chord (dot on its first note only), a staccato note tied on, then
    // the measure ends on a staccato eighth (La groupie du pianiste, ends of measures 14, 15, 19)
    private val score = """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="4.0">
          <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
          <part id="P1">
            <measure number="1">
              <attributes><divisions>4</divisions><time><beats>3</beats><beat-type>4</beat-type></time></attributes>
              <note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>quarter</type><staff>1</staff>
                <notations><articulations><staccato/></articulations></notations></note>
              <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
              <note><pitch><step>D</step><octave>4</octave></pitch><duration>4</duration><tie type="start"/><voice>1</voice><type>quarter</type>
                <notations><articulations><staccato/></articulations></notations></note>
              <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><tie type="stop"/><voice>1</voice><type>eighth</type></note>
              <note><pitch><step>F</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>eighth</type>
                <notations><articulations><staccato/></articulations></notations></note>
            </measure>
          </part>
        </score-partwise>
    """.trimIndent()

    private fun parse(xml: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val measure = doc.getElementsByTagName("measure").item(0) as Element
        return (0 until measure.childNodes.length).mapNotNull { measure.childNodes.item(it) as? Element }
            .filter { it.tagName == "note" || it.tagName == "forward" }
    }

    private fun Element.text(tag: String): String? = getElementsByTagName(tag).item(0)?.textContent?.trim()

    private fun Element.kind() = when {
        tagName == "forward" -> "forward"
        getElementsByTagName("rest").length > 0 -> "rest"
        else -> "note"
    }

    @Test
    fun aStaccatoChordSoundsAQuarterOfItsValueThenItsVoiceIsSilent() {
        val doc = MusicXmlScore.parse(score.toByteArray())
        val played = parse(doc.prepare(played = true))
        assertEquals(listOf("note", "note", "rest", "rest", "note", "note", "note", "rest"), played.map { it.kind() })
        // both notes of the chord a sixteenth, then an eighth and a sixteenth of silence in voice 1 of staff 1
        assertEquals(listOf("1", "1", "2", "1"), played.take(4).map { it.text("duration") })
        assertEquals(listOf("16th", "16th", "eighth", "16th"), played.take(4).map { it.text("type") })
        assertEquals(listOf("1", "1"), played.subList(2, 4).map { it.text("voice") })
        assertEquals(listOf("1", "1"), played.subList(2, 4).map { it.text("staff") })
        assertEquals(listOf("no", "no"), played.subList(2, 4).map { it.getAttribute("print-object") })
        // a staccato note whose tie goes on is left as written
        assertEquals("4", played[4].text("duration"))
    }

    /** Verovio drops a `<forward>` that ends a measure: the silence is a rest, the measure keeps its length. */
    @Test
    fun aStaccatoEndingTheMeasureLeavesItsLength() {
        val doc = MusicXmlScore.parse(score.toByteArray())
        val played = parse(doc.prepare(played = true))
        assertEquals("rest", played.last().kind())
        assertEquals(12, played.filter { it.text("chord") == null && it.getElementsByTagName("chord").length == 0 }
            .sumOf { it.text("duration")?.toInt() ?: 0 })
    }

    @Test
    fun theScoreDrawnKeepsItsValues() {
        val doc = MusicXmlScore.parse(score.toByteArray())
        doc.prepare(played = true)
        val drawn = parse(doc.prepare())
        assertEquals(listOf("note", "note", "note", "note", "note"), drawn.map { it.kind() })
        assertEquals(listOf("4", "4", "4", "2", "2"), drawn.map { it.text("duration") })
        assertEquals("quarter", drawn[0].text("type"))
    }
}
