package com.musigatto.fosscore.musicxml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnfoldTest {

    /** A measure of the test score: what its barlines and directions say. */
    private data class M(
        val forward: Boolean = false,
        val backward: Boolean = false,
        val ending: String? = null,
        val endingNumber: String? = null,
        val endingStop: Boolean = false,
        val finalBar: Boolean = false,
        val toCoda: Boolean = false,
        val coda: Boolean = false,
        val segno: Boolean = false,
        val dalSegno: Boolean = false,
        val fine: Boolean = false,
    )

    /** A one-part score of [measures], numbered 1 up, each a whole note with an id. */
    private fun score(measures: List<M>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><score-partwise version="3.1"><part-list><score-part id="P1"><part-name>P</part-name></score-part></part-list><part id="P1">""")
        measures.forEachIndexed { i, m ->
            append("""<measure number="${i + 1}" id="fosscore-measure-${i + 1}">""")
            if (m.forward || m.ending != null) {
                append("""<barline location="left">""")
                if (m.forward) append("""<bar-style>heavy-light</bar-style><repeat direction="forward"/>""")
                if (m.ending != null) append("""<ending number="${m.endingNumber ?: m.ending.filter { it.isDigit() }}" type="start">${m.ending}</ending>""")
                append("</barline>")
            }
            if (i == 0) append("<attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>")
            if (m.segno) append("""<direction><direction-type><segno/></direction-type><sound segno="s"/></direction>""")
            if (m.coda) append("""<direction><direction-type><coda/></direction-type><sound coda="c"/></direction>""")
            if (m.toCoda) append("""<direction><direction-type><words>To Coda</words></direction-type><sound tocoda="1"/></direction>""")
            append("""<note id="n${i + 1}"><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration><type>whole</type></note>""")
            if (m.dalSegno) append("""<direction><direction-type><words>D.S. al Coda</words></direction-type><sound dalsegno="s"/></direction>""")
            if (m.fine) append("""<direction><direction-type><words>Fine</words></direction-type><sound fine="yes"/></direction>""")
            if (m.backward || m.endingStop || m.finalBar) {
                append("""<barline location="right">""")
                if (m.backward || m.finalBar) append("<bar-style>light-heavy</bar-style>")
                if (m.endingStop) append("""<ending number="${m.endingNumber ?: m.ending.orEmpty().filter { it.isDigit() }}" type="stop"/>""")
                if (m.backward) append("""<repeat direction="backward"/>""")
                append("</barline>")
            }
            append("</measure>")
        }
        append("</part></score-partwise>")
    }

    private fun played(measures: List<M>) = Unfold.order(score(measures)).map { it + 1 }

    @Test
    fun aRepeatIsPlayedTwice() {
        val m = List(4) { M() }.toMutableList()
        m[1] = M(forward = true)
        m[2] = M(backward = true)
        assertEquals(listOf(1, 2, 3, 2, 3, 4), played(m))
    }

    @Test
    fun firstAndSecondEndings() {
        val m = List(5) { M() }.toMutableList()
        m[2] = M(ending = "1.", endingStop = true, backward = true)
        m[3] = M(ending = "2.", endingStop = true)
        assertEquals(listOf(1, 2, 3, 1, 2, 4, 5), played(m))
    }

    /**
     * Lettre à France, as the maintainer plays it: |: at 17; endings "1. 2." over 24-28 with :|
     * at 28; "3." at 29 with To Coda; "4." at 30, the end; the coda at 31; :| at 46. MuseScore
     * exports the endings' numbers wrong ("2" for "1. 2.", "3" for "4."); their text is right.
     * Verovio played it 1-28, 17-28, 29, 31-46, 17-28, 29, 30, 31-46.
     */
    @Test
    fun lettreAFrance() {
        val m = List(46) { M() }.toMutableList()
        m[16] = M(forward = true)
        m[23] = M(ending = "1.2.", endingNumber = "2")
        m[27] = M(ending = "1.2.", endingNumber = "2", endingStop = true, backward = true)
        m[28] = M(ending = "3.", endingNumber = "3", endingStop = true, toCoda = true)
        m[29] = M(ending = "4.", endingNumber = "3", endingStop = true, finalBar = true)
        m[30] = M(coda = true)
        m[45] = M(backward = true)
        val expected = (1..28) + (17..28) + (17..23) + listOf(29) + (31..46) + (17..23) + listOf(30)
        assertEquals(expected, played(m))
    }

    /**
     * The same as MuseScore exports it: the "1. 2." ending stops (discontinue) at the end of its
     * first measure, 24, though its bracket goes on to the repeat at 28. An ending goes on to the
     * backward repeat after it when no other ending starts before it.
     */
    @Test
    fun lettreAFranceAsMuseScoreExportsIt() {
        val m = List(46) { M() }.toMutableList()
        m[16] = M(forward = true)
        m[23] = M(ending = "1.2.", endingNumber = "2", endingStop = true)
        m[27] = M(backward = true)
        m[28] = M(ending = "3.", endingNumber = "3", endingStop = true, toCoda = true)
        m[29] = M(ending = "4.", endingNumber = "3", endingStop = true, finalBar = true)
        m[30] = M(coda = true)
        m[45] = M(backward = true)
        val expected = (1..28) + (17..28) + (17..23) + listOf(29) + (31..46) + (17..23) + listOf(30)
        assertEquals(expected, played(m))
    }

    @Test
    fun dalSegnoAlCodaSkipsToTheCodaTheSecondTime() {
        val m = List(8) { M() }.toMutableList()
        m[1] = M(segno = true)
        m[2] = M(toCoda = true)
        m[4] = M(dalSegno = true, finalBar = false)
        m[5] = M(coda = true)
        assertEquals(listOf(1, 2, 3, 4, 5, 2, 3, 6, 7, 8), played(m))
    }

    /** The unrolled score has no repeat left, its copies named after the measures and notes they repeat. */
    @Test
    fun theUnrolledScoreHasCopiesNamedAfterTheirOriginals() {
        val m = List(3) { M() }.toMutableList()
        m[0] = M(forward = true)
        m[1] = M(backward = true)
        val xml = Unfold.unroll(score(m))
        assertFalse(xml.contains("repeat"))
        assertEquals(5, Regex("<measure ").findAll(xml).count())
        assertTrue(xml.contains("id=\"n1-rend2\""))
        assertTrue(xml.contains("id=\"fosscore-measure-2-rend2\""))
        assertTrue(xml.contains("id=\"n3\""))
    }
}
