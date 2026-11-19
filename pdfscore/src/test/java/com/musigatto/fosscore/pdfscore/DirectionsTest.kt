package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.NoteType
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionsTest {
    private val space = 5.0
    private val top = 100.0

    private val staffLines = (0..4).map { DrawnLine(50.0, top + it * space, 550.0, top + it * space, 0.5) }
    private val clef = DrawnGlyph("gClef", Box(55.0, top - 1.5 * space, 55.0 + 2.7 * space, top + 5.5 * space))
    private val head = DrawnGlyph("noteheadBlack", Box(100.0, top + 1.5 * space, 106.5, top + 2.5 * space))

    private fun read(glyphs: List<DrawnGlyph>, texts: List<DrawnText>): List<Direction> {
        val page = PageDrawing(600.0, 800.0, listOf(clef, head) + glyphs, staffLines, emptyList(), texts)
        return Directions.read(page, Staves.find(page)).single()
    }

    /** A note symbol over the staff and "= 62" after it: the tempo, in quarters a minute. */
    @Test
    fun aMetronomeMarkIsATempo() {
        val note = DrawnGlyph("metNoteQuarterUp", Box(108.0, top - 4 * space, 111.0, top - 2.5 * space))
        val text = DrawnText("  =  62", "font_4", 112.0, top - 2.5 * space, 12.0)
        assertEquals(listOf(TempoMark(108.0, NoteType.QUARTER, 0, 62)), read(listOf(note), listOf(text)))
    }

    /** A dotted quarter = 40 (a 6/8 tempo): the dot after the note symbol counts. */
    @Test
    fun aDottedNoteMarkKeepsItsDot() {
        val note = DrawnGlyph("metNoteQuarterUp", Box(108.0, top - 4 * space, 111.0, top - 2.5 * space))
        val dot = DrawnGlyph("metAugmentationDot", Box(112.0, top - 2.9 * space, 113.0, top - 2.6 * space))
        val text = DrawnText("= 40", "font_4", 115.0, top - 2.5 * space, 12.0)
        assertEquals(listOf(TempoMark(108.0, NoteType.QUARTER, 1, 40)), read(listOf(note, dot), listOf(text)))
    }

    /** "Rall." over the staff is a direction; a lyric, a measure number or a title is not. */
    @Test
    fun tempoWordsAreDirectionsLyricsAreNot() {
        val texts = listOf(
            DrawnText("Rall.", "font_4", 200.0, top - 2 * space, 12.0),
            DrawnText("a tempo", "font_4", 300.0, top - 2 * space, 12.0),
            DrawnText("Lu-", "font_1", 120.0, top - 2 * space, 10.0),
            DrawnText("23", "font_1", 52.0, top - 2 * space, 8.0),
        )
        assertEquals(listOf(Words(200.0, "Rall."), Words(300.0, "a tempo")), read(emptyList(), texts))
    }

    /**
     * "To" and a sign ("To 𝄋", "3. To ⊕"), as the OCR reads them with the sign ("To $", "3. To @"),
     * are directions: taken for lyrics, Savoir aimer's jumps were lost (2026-11-20).
     */
    @Test
    fun toAndASignIsADirection() {
        val texts = listOf(
            DrawnText("3. To @", "ocr", 200.0, top - 2 * space, 9.0),
            DrawnText("To $", "ocr", 300.0, top - 2 * space, 9.0),
            DrawnText("Tout le monde", "font_1", 400.0, top - 2 * space, 10.0),
        )
        assertEquals(listOf(Words(200.0, "3. To @"), Words(300.0, "To $")), read(emptyList(), texts))
    }

    /** A segno and a coda over the staff are where a jump goes; "D.S. al Coda", "Fine" say when to jump. */
    @Test
    fun segnoCodaAndJumpWords() {
        val segno = DrawnGlyph("segno", Box(150.0, top - 4 * space, 155.0, top - 1.5 * space))
        val coda = DrawnGlyph("coda", Box(400.0, top - 4 * space, 406.0, top - 1.5 * space))
        val texts = listOf(DrawnText("D.S. al Coda", "font_4", 300.0, top - 2 * space, 12.0), DrawnText("Fine", "font_4", 350.0, top - 2 * space, 12.0))
        assertEquals(
            listOf(Mark(150.0, Mark.Kind.SEGNO), Words(300.0, "D.S. al Coda"), Words(350.0, "Fine"), Mark(400.0, Mark.Kind.CODA)),
            read(listOf(segno, coda), texts),
        )
    }

    /**
     * An ending is a line over the staff with a hook down at its start and a number ("1.", "2."):
     * closed when it has a hook at its end too, open (the last ending) when not.
     */
    @Test
    fun endingsAreBracketsWithANumber() {
        val y = top - 3 * space
        val lines = listOf(
            DrawnLine(200.0, y, 300.0, y, 0.6), DrawnLine(200.0, y, 200.0, y + 1.5 * space, 0.6), DrawnLine(300.0, y, 300.0, y + 1.5 * space, 0.6),
            DrawnLine(302.0, y, 420.0, y, 0.6), DrawnLine(302.0, y, 302.0, y + 1.5 * space, 0.6),
        )
        val texts = listOf(DrawnText("1.", "font_1", 203.0, y + space, 10.0), DrawnText("2.", "font_1", 305.0, y + space, 10.0))
        val page = PageDrawing(600.0, 800.0, listOf(clef, head), staffLines + lines, emptyList(), texts)
        assertEquals(
            listOf(Ending(200.0, 300.0, "1", closed = true), Ending(302.0, 420.0, "2", closed = false)),
            Directions.endings(page, Staves.find(page)).single(),
        )
    }
}
