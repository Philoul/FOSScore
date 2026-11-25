package com.musigatto.fosscore.musicxml

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class StaccatoPlayedTest {

    // divisions 2: a staccato chord (dot on its first note only), a staccato note tied on, a plain quarter
    private val score = """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="4.0">
          <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
          <part id="P1">
            <measure number="1">
              <attributes><divisions>2</divisions><time><beats>3</beats><beat-type>4</beat-type></time></attributes>
              <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>quarter</type><staff>1</staff>
                <notations><articulations><staccato/></articulations></notations></note>
              <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>
              <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><tie type="start"/><voice>1</voice><type>quarter</type>
                <notations><articulations><staccato/></articulations></notations></note>
              <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><tie type="stop"/><voice>1</voice><type>quarter</type></note>
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

    @Test
    fun aStaccatoChordSoundsHalfItsValueThenItsVoiceIsSilent() {
        val doc = MusicXmlScore.parse(score.toByteArray())
        val played = parse(doc.prepare(played = true))
        assertEquals(listOf("note", "note", "forward", "note", "note"), played.map { it.tagName })
        // both notes of the chord an eighth, then an eighth of silence in voice 1 of staff 1
        assertEquals(listOf("1", "1", "1"), played.take(3).map { it.text("duration") })
        assertEquals(listOf("eighth", "eighth"), played.take(2).map { it.text("type") })
        assertEquals("1", played[2].text("voice"))
        assertEquals("1", played[2].text("staff"))
        // a staccato note whose tie goes on is left as written
        assertEquals("2", played[3].text("duration"))
    }

    @Test
    fun theScoreDrawnKeepsItsValues() {
        val doc = MusicXmlScore.parse(score.toByteArray())
        doc.prepare(played = true)
        val drawn = parse(doc.prepare())
        assertEquals(listOf("note", "note", "note", "note"), drawn.map { it.tagName })
        assertEquals(listOf("2", "2", "2", "2"), drawn.map { it.text("duration") })
        assertEquals("quarter", drawn[0].text("type"))
    }
}
