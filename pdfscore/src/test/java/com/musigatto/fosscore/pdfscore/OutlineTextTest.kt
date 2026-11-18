package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.NoteType
import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineTextTest {
    private val space = 5.0
    private val top = 100.0
    private val staff = (0..4).map { DrawnLine(50.0, top + it * space, 550.0, top + it * space, 0.5) }
    private fun rect(l: Double, t: Double, r: Double, b: Double) =
        DrawnShape(listOf(Point(l, t), Point(r, t), Point(r, b), Point(l, b), Point(l, t)), curved = true)

    // "♩ = 70" printed without its font, over the staff: the note named, "= 70" one outline, "Andante" another
    private val note = DrawnGlyph("metNoteQuarterUp", Box(80.0, 78.0, 85.0, 91.0))
    private val number = rect(89.0, 82.0, 112.0, 90.5)
    private val word = rect(300.0, 80.0, 340.0, 90.0)
    private val page = PageDrawing(600.0, 800.0, listOf(note), staff, listOf(number, word), emptyList())

    /** What is written right of a metronome mark's note is its number, to be read from the page's pixels. */
    @Test
    fun theNumberOfAMetronomeMarkIsToBeRead() {
        assertEquals(listOf(number.box), OutlineText.spots(page))
    }

    @Test
    fun withoutAMetronomeNoteThereIsNothingToRead() {
        assertEquals(emptyList<Box>(), OutlineText.spots(PageDrawing(600.0, 800.0, emptyList(), staff, listOf(number, word), emptyList())))
    }

    /** Once read, the number is the page's text: the tempo is read from it as from a PDF with fonts. */
    @Test
    fun theNumberReadMakesTheTempo() {
        val read = OutlineText.withTexts(page, mapOf(number.box to "= 70"))
        val tempo = Directions.read(read, Staves.find(read)).single().filterIsInstance<TempoMark>().single()
        assertEquals(NoteType.QUARTER, tempo.beat)
        assertEquals(70, tempo.perMinute)
    }
}
