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
}
