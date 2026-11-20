package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Onset
import com.musigatto.fosscore.musicxml.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarPathTest {
    private val top = 100f
    private val bottom = 140f

    private fun head(x: Float, measureEnd: Float, lineEnd: Float = 600f, top: Float = this.top) =
        PageGeometry.Head(x, top + 20f, top, top + 40f, 5f, lineEnd, measureEnd)

    // the id of a note of a PDF read: its measure is the first number
    private fun id(measure: Int, k: Int = 0) = "fosscore-note-$measure-1-$k-0"

    private fun xs(path: BarPath, from: Double, to: Double): List<Float> =
        generateSequence(from) { it + 0.05 }.takeWhile { it < to }.map { path.at(it)?.x ?: Float.NaN }.toList()

    private fun assertForward(xs: List<Float>) =
        xs.zipWithNext().forEach { (a, b) -> assertTrue("went back: $xs", b >= a - 0.01f) }

    /**
     * A half note ending a measure before a repeat back (Savoir aimer, measure 40): the bar goes
     * on to the end of its measure, not to the end of the line over the next measure, not played
     * (2026-11-19).
     */
    @Test
    fun beforeARepeatTheBarGoesToTheEndOfTheMeasure() {
        // measure 1 from 0 to 200 (one note at 50), measure 2 from 200 to 400 (a half note at 250)
        val geometry = PageGeometry.of(600f, 800f, mapOf(id(1) to head(50f, 200f), id(2) to head(250f, 400f)))
        // measure 1, measure 2 (two beats each), then measure 1 again: the repeat
        val path = BarPath(listOf(Onset(0.0, listOf(id(1))), Onset(2.0, listOf(id(2))), Onset(4.0, listOf(id(1)))), listOf(0.0, 2.0, 4.0), geometry)
        assertEquals(400f, path.at(3.999)?.x ?: 0f, 1f)
    }

    /**
     * Within a measure the bar never goes back: notes played later but drawn more to the left
     * (a voice read wrong) left it going back and forth (J'ai encore rêvé d'elle, Lettre à France).
     */
    @Test
    fun withinAMeasureTheBarNeverGoesBack() {
        val geometry = PageGeometry.of(
            600f, 800f,
            mapOf(id(1, 0) to head(50f, 300f), id(1, 1) to head(150f, 300f), id(1, 2) to head(100f, 300f), id(1, 3) to head(200f, 300f)),
        )
        val path = BarPath((0..3).map { Onset(it.toDouble(), listOf(id(1, it))) }, listOf(0.0), geometry)
        assertForward(xs(path, 0.0, 4.0))
    }

    /**
     * A note read in a measure but drawn in the next one (a PDF read wrong) took the bar past the
     * barline, then back to the next measure's first note: the bar keeps to the measure played.
     */
    @Test
    fun aNoteDrawnInTheNextMeasureDoesNotTakeTheBarBack() {
        // measure 1 from 0 to 200, measure 2 from 200 to 400; a note of measure 1 drawn at 300
        val geometry = PageGeometry.of(
            600f, 800f,
            mapOf(id(1, 0) to head(50f, 200f), id(1, 1) to head(300f, 400f), id(2, 0) to head(250f, 400f), id(2, 1) to head(330f, 400f)),
        )
        val path = BarPath(
            listOf(Onset(0.0, listOf(id(1, 0))), Onset(2.0, listOf(id(1, 1))), Onset(4.0, listOf(id(2, 0))), Onset(6.0, listOf(id(2, 1)))),
            listOf(0.0, 4.0),
            geometry,
        )
        val xs = xs(path, 0.0, 8.0)
        assertForward(xs)
        assertTrue("left its measure: $xs", (path.at(3.9)?.x ?: 0f) <= 250f)
    }

    /**
     * A note drawn too far right early in its measure pulled the bar ahead, then held it there
     * while the next notes were played: the bar follows the notes in their order, the stray one
     * left out.
     */
    @Test
    fun aStrayNoteDoesNotPullTheBarAhead() {
        val geometry = PageGeometry.of(
            600f, 800f,
            mapOf(id(1, 0) to head(50f, 200f), id(1, 1) to head(190f, 200f), id(1, 2) to head(100f, 200f), id(1, 3) to head(150f, 200f)),
        )
        val path = BarPath((0..3).map { Onset(it.toDouble(), listOf(id(1, it))) }, listOf(0.0, 4.0), geometry)
        assertEquals(75f, path.at(1.0)?.x ?: 0f, 1f)
        assertForward(xs(path, 0.0, 3.9))
    }

    /**
     * A measure of rests has no note to follow: the bar crossed it and the measure before at one
     * speed, from the last note before to the first note after. It crosses each measure in the
     * measure's own time: at the barline when the measure starts.
     */
    @Test
    fun aMeasureOfRestsIsCrossedInItsOwnTime() {
        // measure 1 from 0 to 200, measure 2 (rests), measure 3 from 400 to 600
        val geometry = PageGeometry.of(600f, 800f, mapOf(id(1) to head(50f, 200f), id(3) to head(450f, 600f)))
        val path = BarPath(listOf(Onset(0.0, listOf(id(1))), Onset(8.0, listOf(id(3)))), listOf(0.0, 4.0, 8.0), geometry)
        val x = path.at(4.0)?.x ?: 0f
        assertTrue("measure 2 starts at $x", x in 195f..230f)
        val middle = path.at(6.0)?.x ?: 0f
        assertTrue("the middle of measure 2 at $middle", middle in 280f..340f)
        assertForward(xs(path, 0.0, 12.0))
    }

    /** From the end of a line to the next line: the bar goes to the end of the line first. */
    @Test
    fun aNewLineIsReachedAtItsFirstNote() {
        val geometry = PageGeometry.of(
            600f, 800f,
            mapOf(id(1) to head(50f, 600f), id(2) to head(70f, 600f, top = 300f)),
        )
        val path = BarPath(listOf(Onset(0.0, listOf(id(1))), Onset(4.0, listOf(id(2)))), listOf(0.0, 4.0), geometry)
        assertEquals(600f, path.at(3.999)?.x ?: 0f, 1f)
        assertEquals(70f, path.at(4.0)?.x ?: 0f, 1f)
        assertEquals(300f, path.at(4.0)?.top ?: 0f, 0.01f)
    }
}
