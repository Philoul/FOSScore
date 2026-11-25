package com.musigatto.fosscore.musicxml

import org.junit.Assert.assertEquals
import org.junit.Test

class FermataTest {

    // 4/4, two measures; a repeat around both. Measure 2: a quarter, a held half chord, a held
    // quarter rest in the second voice under it (backup), and a grace note with a fermata
    private val score = """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="4.0">
          <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
          <part id="P1">
            <measure number="1">
              <attributes><divisions>2</divisions><time><beats>4</beats><beat-type>4</beat-type></time>
                <clef><sign>G</sign><line>2</line></clef></attributes>
              <barline location="left"><bar-style>heavy-light</bar-style><repeat direction="forward"/></barline>
              <note><pitch><step>C</step><octave>4</octave></pitch><duration>8</duration><voice>1</voice><type>whole</type></note>
            </measure>
            <measure number="2">
              <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>quarter</type></note>
              <note><grace/><pitch><step>F</step><octave>4</octave></pitch><voice>1</voice><type>eighth</type>
                <notations><fermata/></notations></note>
              <note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>half</type>
                <notations><fermata type="upright"/></notations></note>
              <note><chord/><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice><type>half</type>
                <notations><fermata type="upright"/></notations></note>
              <note><pitch><step>F</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice><type>quarter</type></note>
              <backup><duration>8</duration></backup>
              <forward><duration>2</duration></forward>
              <note><rest/><duration>2</duration><voice>2</voice><type>quarter</type>
                <notations><fermata/></notations></note>
              <forward><duration>4</duration></forward>
              <barline location="right"><bar-style>light-heavy</bar-style><repeat direction="backward"/></barline>
            </measure>
          </part>
        </score-partwise>
    """.trimIndent()

    @Test
    fun theHeldNotesAndRestsAreReadWhereTheyAreInTheirMeasure() {
        val doc = MusicXmlScore.parse(score.toByteArray())
        // the half chord from beat 2 to 4, the rest under it from beat 2 to 3; no grace note
        assertEquals(mapOf(1 to listOf(Hold(1.0, 3.0), Hold(1.0, 3.0), Hold(1.0, 2.0))), doc.fermatas)
    }

    /**
     * A whole note held over a hand running in quarters: the run goes on in time, the hold is on
     * its last note (Pas de boogie woogie: the whole notes held over the arpeggios slowed down
     * the whole measure).
     */
    @Test
    fun aNoteHeldOverMovingNotesIsHeldFromTheLastOfThem() {
        val run = (0 until 4).joinToString("") {
            """<note><pitch><step>C</step><octave>3</octave></pitch><duration>1</duration><voice>2</voice><type>quarter</type><staff>2</staff></note>"""
        }
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>1</divisions><staves>2</staves></attributes>
                  <note><pitch><step>B</step><octave>5</octave></pitch><duration>4</duration><voice>1</voice><type>whole</type><staff>1</staff>
                    <notations><fermata/></notations></note>
                  <backup><duration>4</duration></backup>
                  $run
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()
        assertEquals(mapOf(0 to listOf(Hold(3.0, 4.0))), MusicXmlScore.parse(xml.toByteArray()).fermatas)
    }

    @Test
    fun theHoldsArePlacedEachTimeTheMeasureIsPlayed() {
        val doc = MusicXmlScore.parse(score.toByteArray())
        val order = Unfold.order(score)
        assertEquals(listOf(0, 1, 0, 1), order)
        val bars = listOf(0.0, 4.0, 8.0, 12.0)
        // the chord and the rest held together make one hold, played twice
        assertEquals(listOf(Hold(5.0, 7.0), Hold(13.0, 15.0)), Playback.holdsOf(order, bars, doc.fermatas))
    }

    @Test
    fun noHoldWhenTheBarsDoNotMatchTheOrder() {
        val doc = MusicXmlScore.parse(score.toByteArray())
        assertEquals(emptyList<Hold>(), Playback.holdsOf(listOf(0, 1, 0, 1), listOf(0.0, 4.0), doc.fermatas))
    }

    @Test
    fun theHoldsGoThroughTheCache() {
        val holds = listOf(Hold(5.0, 7.0), Hold(13.25, 15.5))
        assertEquals(holds, Playback.holdsFromJson(Playback.holdsJson(holds)))
    }
}
