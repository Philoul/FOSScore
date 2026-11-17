package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreReaderTest {
    private val space = 5.0

    private fun staffLines(top: Double) = (0..4).map { DrawnLine(50.0, top + it * space, 550.0, top + it * space, 0.5) }
    private fun glyph(name: String, x: Double, y: Double, height: Double, fraction: Double, width: Double) =
        DrawnGlyph(name, Box(x, y - fraction * height * space, x + width * space, y + (1 - fraction) * height * space))
    private fun gClef(top: Double) = glyph("gClef", 55.0, top + 3 * space, 7.0, 0.64, 2.7)
    private fun sharp(x: Double, top: Double) = glyph("accidentalSharp", x, top, 2.7, 0.5, 1.0)
    private fun whole(x: Double, y: Double) = glyph("noteheadWhole", x, y, 1.0, 0.5, 1.8)
    private fun digit(d: Int, x: Double, y: Double) = glyph("timeSig$d", x, y, 2.0, 0.5, 1.5)
    private fun barline(x: Double, top: Double) = DrawnLine(x, top, x, top + 4 * space, 0.8)

    /**
     * Two systems on two pages, the time signature drawn on the first only: the measures follow
     * each other across systems and pages, and the time holds on.
     */
    @Test
    fun measuresFollowEachOtherAndTheTimeHoldsOn() {
        val top = 100.0
        val first = PageDrawing(
            600.0, 800.0,
            listOf(gClef(top), sharp(70.0, top), digit(4, 80.0, top + space), digit(4, 80.0, top + 3 * space), whole(120.0, top + 4 * space), whole(320.0, top)),
            staffLines(top) + barline(300.0, top) + barline(549.0, top),
            emptyList(), emptyList(),
        )
        val second = PageDrawing(
            600.0, 800.0,
            listOf(gClef(top), sharp(70.0, top), whole(120.0, top + 2 * space)),
            staffLines(top) + barline(549.0, top),
            emptyList(), emptyList(),
        )
        val score = ScoreReader.read(listOf(first, second))
        assertEquals(listOf(Part(1)), score.parts)
        assertEquals(listOf(1, 2, 3), score.measures.map { it.rank })
        assertEquals(listOf(0, 0, 1), score.measures.map { it.page })
        assertEquals(listOf(true, false, true), score.measures.map { it.newSystem })
        assertEquals(List(3) { TimeSignature(4, 4) }, score.measures.map { it.staves.single().time })
        assertEquals(List(3) { Key(1) }, score.measures.map { it.staves.single().key })
        assertEquals(List(3) { Clef(Clef.Sign.G, 2) }, score.measures.map { it.staves.single().clef })
        assertEquals(listOf("E4", "F#5", "B4"), score.measures.map { (it.staves.single().events.single().event as Chord).pitches.single().toString() })
        // the first measure runs from the staff's start (clef included) to the barline; its music starts at the note
        val box = score.measures[0].staves.single().box
        assertEquals(listOf(50.0, 300.0, 120.0), listOf(box.left, box.right, box.start))
        assertEquals(300.0, score.measures[1].staves.single().box.left, 1e-9)
        // each note found on its page by the id the MusicXML gives it
        val places = score.notePlaces()
        assertEquals(3, places.size)
        val (page, head) = places.getValue(ScoreReading.noteId(3, 1, 0, 0))
        assertEquals(1, page)
        assertEquals(120.0, head.left, 1e-9)
    }

    /**
     * Over the first system: the title (the largest text), the composer (on the right) and the
     * lyricist (on the left); left of a part's staves, its instrument's name.
     */
    @Test
    fun theHeaderAndThePartNamesAreRead() {
        val top = 100.0
        val page = PageDrawing(
            600.0, 800.0,
            listOf(gClef(top), whole(120.0, top + 2 * space)),
            staffLines(top),
            emptyList(),
            listOf(
                DrawnText("Une  chanson", "font_1", 230.0, 40.0, 20.0),
                DrawnText("Paroles :  Quelqu'un", "font_1", 50.0, 70.0, 10.0),
                DrawnText("Musique :  Quelqu'un d'autre", "font_1", 420.0, 70.0, 10.0),
                DrawnText("Piano", "font_1", 10.0, top + 2.5 * space, 12.0),
            ),
        )
        val score = ScoreReader.read(listOf(page))
        assertEquals(Header("Une chanson", composer = "Musique : Quelqu'un d'autre", lyricist = "Paroles : Quelqu'un"), score.header)
        assertEquals(listOf(Part(1, "Piano")), score.parts)
    }

    /** A title drawn twice (a PDF may draw a text over itself) is one title. */
    @Test
    fun aTitleDrawnTwiceIsOneTitle() {
        val top = 100.0
        val title = DrawnText("Une chanson", "font_1", 230.0, 40.0, 20.0)
        val page = PageDrawing(600.0, 800.0, listOf(gClef(top), whole(120.0, top + 2 * space)), staffLines(top), emptyList(), listOf(title, title.copy(x = 231.0)))
        assertEquals("Une chanson", ScoreReader.read(listOf(page)).header.title)
    }

    /** A brace joins its staves into one part; a staff alone is a part. */
    @Test
    fun aBraceMakesAPartOfItsStaves() {
        val tops = listOf(100.0, 150.0, 200.0)
        val brace = DrawnGlyph("brace", Box(42.0, 149.0, 48.0, 221.0))
        val page = PageDrawing(
            600.0, 800.0,
            tops.map { gClef(it) } + brace + tops.map { whole(120.0, it + 2 * space) },
            tops.flatMap { staffLines(it) } + DrawnLine(50.0, 100.0, 50.0, 220.0, 1.0),
            emptyList(), emptyList(),
        )
        val score = ScoreReader.read(listOf(page))
        assertEquals(listOf(Part(1), Part(2)), score.parts)
        assertEquals(3, score.measures.single().staves.size)
    }
}
