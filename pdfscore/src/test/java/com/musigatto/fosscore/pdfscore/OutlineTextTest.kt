package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTextTest {
    private val space = 5.0
    private val top = 100.0
    private val staff = (0..4).map { DrawnLine(50.0, top + it * space, 550.0, top + it * space, 0.5) }
    private fun rect(l: Double, t: Double, r: Double, b: Double, fill: Int = -1) =
        DrawnShape(listOf(Point(l, t), Point(r, t), Point(r, b), Point(l, b), Point(l, t)), curved = true, fill = fill)

    // a piece of text drawn as one fill of several outlines (its letters), as such PDFs draw it
    private fun text(l: Double, t: Double, r: Double, b: Double, fill: Int) =
        listOf(rect(l, t, (l + r) / 2 - 0.5, b, fill), rect((l + r) / 2 + 0.5, t, r, b, fill))
    private fun boxOf(shapes: List<DrawnShape>) = Box(shapes.minOf { it.left }, shapes.minOf { it.top }, shapes.maxOf { it.right }, shapes.maxOf { it.bottom })

    // "♩ = 70" printed without its font, over the staff: the note named, "= 70" one outline, "Andante" another
    private val note = DrawnGlyph("metNoteQuarterUp", Box(80.0, 78.0, 85.0, 91.0))
    private val number = text(89.0, 82.0, 112.0, 90.5, fill = 1)
    private val word = text(300.0, 80.0, 340.0, 90.0, fill = 2)
    private val page = PageDrawing(600.0, 800.0, listOf(note), staff, number + word, emptyList())

    /** What is written right of a metronome mark's note is its number, to be read from the page's pixels. */
    @Test
    fun theNumberOfAMetronomeMarkIsToBeRead() {
        val spots = OutlineText.spots(page)
        assertEquals(listOf(boxOf(number)), spots.filter { it.kind == OutlineText.Kind.METRONOME }.map { it.box })
        assertEquals(listOf(boxOf(word)), spots.filter { it.kind == OutlineText.Kind.WORDS }.map { it.box })
    }

    /**
     * "♩ = 100" with "=" as two lines and the number three spaces after the note (J'ai encore rêvé
     * d'elle): the lines are part of the mark, its number read with them (played at 120 without).
     */
    @Test
    fun anEqualSignOfTwoLinesBindsTheNumberToItsNote() {
        val equal = listOf(DrawnLine(88.0, 83.5, 94.0, 83.5, 0.4), DrawnLine(88.0, 85.5, 94.0, 85.5, 0.4))
        val far = text(96.0, 81.0, 110.0, 90.0, fill = 1)
        val spots = OutlineText.spots(PageDrawing(600.0, 800.0, listOf(note), staff + equal, far, emptyList()))
        val mark = spots.filter { it.kind == OutlineText.Kind.METRONOME }.map { it.box }.single()
        assertEquals(88.0, mark.left, 0.01)
        assertEquals(110.0, mark.right, 0.01)
    }

    @Test
    fun withoutAMetronomeNoteItsNumberIsWords() {
        val spots = OutlineText.spots(PageDrawing(600.0, 800.0, emptyList(), staff, number + word, emptyList()))
        assertTrue(spots.none { it.kind == OutlineText.Kind.METRONOME })
        assertEquals(2, spots.count { it.kind == OutlineText.Kind.WORDS })
    }

    /** Once read, the number is the page's text: the tempo is read from it as from a PDF with fonts. */
    @Test
    fun theNumberReadMakesTheTempo() {
        val read = OutlineText.withTexts(page, mapOf(boxOf(number) to "= 70"))
        val tempo = Directions.read(read, Staves.find(read)).single().filterIsInstance<TempoMark>().single()
        assertEquals(NoteType.QUARTER, tempo.beat)
        assertEquals(70, tempo.perMinute)
    }

    /**
     * An ending's number printed without its font (under its bracket, by its hook) is read from
     * the pixels too: Lettre à France's "1. 2.", "3.", "4." and Savoir aimer's "2." were missing,
     * so their repeats were played wrong.
     */
    @Test
    fun anEndingsNumberIsReadAndMakesTheEnding() {
        val bracket = DrawnLine(300.0, top - 3 * space, 400.0, top - 3 * space, 0.5)
        val hook = DrawnLine(300.0, top - 3 * space, 300.0, top - space, 0.5)
        val digits = rect(302.0, top - 2.6 * space, 312.0, top - 1.2 * space)
        val ending = PageDrawing(600.0, 800.0, emptyList(), staff + bracket + hook, listOf(digits), emptyList())
        val spot = OutlineText.spots(ending).single()
        assertEquals(OutlineText.Kind.ENDING, spot.kind)
        val read = OutlineText.withTexts(ending, mapOf(spot.box to "1, 2."))
        val endings = Directions.endings(read, Staves.find(read)).single()
        assertEquals(listOf("1, 2"), endings.map { it.numbers })
    }

    /**
     * The same ending as LilyPond prints it without fonts: its bracket and hook are thin filled
     * rectangles, not lines (Lettre à France, page 3): they are strokes all the same.
     */
    @Test
    fun anEndingDrawnWithThinFillsIsFound() {
        val bracket = rect(300.0, top - 3 * space - 0.25, 400.0, top - 3 * space + 0.25, fill = 5)
        val hook = rect(299.7, top - 3 * space, 300.3, top - space, fill = 6)
        val digits = text(302.0, top - 2.6 * space, 312.0, top - 1.2 * space, fill = 7)
        val ending = PageDrawing(600.0, 800.0, emptyList(), staff, listOf(bracket, hook) + digits, emptyList())
        val spot = OutlineText.spots(ending).single { it.kind == OutlineText.Kind.ENDING }
        val read = OutlineText.withTexts(ending, mapOf(spot.box to "3."))
        assertEquals(listOf("3"), Directions.endings(read, Staves.find(read)).single().map { it.numbers })
    }

    /**
     * An ending's bracket over a line of lyrics stands higher (Lettre à France's "3." and "4.",
     * 6.5 spaces over the staff): it is found up to 9 spaces over it.
     */
    @Test
    fun anEndingOverTheLyricsIsFound() {
        val y = top - 7.5 * space
        val bracket = rect(300.0, y - 0.25, 400.0, y + 0.25, fill = 5)
        val hook = rect(299.7, y, 300.3, y + 2 * space, fill = 6)
        val digits = text(302.0, y + 0.4 * space, 312.0, y + 1.8 * space, fill = 7)
        val ending = PageDrawing(600.0, 800.0, emptyList(), staff, listOf(bracket, hook) + digits, emptyList())
        val spot = OutlineText.spots(ending).single { it.kind == OutlineText.Kind.ENDING }
        val read = OutlineText.withTexts(ending, mapOf(spot.box to "4."))
        assertEquals(listOf("4"), Directions.endings(read, Staves.find(read)).single().map { it.numbers })
    }

    /** A row of lyrics over the system (several runs on one line) is not read: only the words that may be directions. */
    @Test
    fun aRowOfLyricsIsNotRead() {
        val lyrics = (0 until 5).flatMap { text(100.0 + 60 * it, top - 2 * space, 130.0 + 60 * it, top - space, fill = 20 + it) }
        val spots = OutlineText.spots(PageDrawing(600.0, 800.0, emptyList(), staff, lyrics, emptyList()))
        assertEquals(emptyList<OutlineText.Spot>(), spots)
    }

    /** Words over the music ("To Coda") read from the pixels are directions, not lyrics. */
    @Test
    fun wordsReadAreDirections() {
        val read = OutlineText.withTexts(page, mapOf(boxOf(word) to "To Coda"))
        val words = Directions.read(read, Staves.find(read)).single().filterIsInstance<Words>()
        assertEquals(listOf("To Coda"), words.map { it.text })
    }
}
