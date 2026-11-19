package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Onset
import com.musigatto.fosscore.musicxml.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class BarPathTest {
    private val top = 100f
    private val bottom = 140f

    private fun head(x: Float, measureEnd: Float, lineEnd: Float = 500f) =
        PageGeometry.Head(x, 120f, top, bottom, 5f, lineEnd, measureEnd)

    /**
     * A half note ending a measure before a repeat back (Savoir aimer, measure 40): the bar goes
     * on to the end of its measure, not to the end of the line over the next measure, not played
     * (2026-11-19).
     */
    @Test
    fun beforeARepeatTheBarGoesToTheEndOfTheMeasure() {
        // measure 1 from 0 to 200 (one note at 50), measure 2 from 200 to 400 (a half note at 250)
        val geometry = PageGeometry.of(600f, 800f, mapOf("a" to head(50f, 200f), "b" to head(250f, 400f)))
        // a, b (two beats), then a again: the repeat
        val path = BarPath(listOf(Onset(0.0, listOf("a")), Onset(2.0, listOf("b")), Onset(4.0, listOf("a"))), listOf(0.0, 2.0, 4.0), geometry)
        assertEquals(400f, path.at(3.999)?.x ?: 0f, 1f)
    }

    /**
     * Within a measure the bar never goes back: notes played later but drawn more to the left
     * (a voice read wrong) left it going back and forth (J'ai encore rêvé d'elle, Lettre à France).
     */
    @Test
    fun withinAMeasureTheBarNeverGoesBack() {
        val geometry = PageGeometry.of(600f, 800f, mapOf("a" to head(50f, 300f), "b" to head(150f, 300f), "c" to head(100f, 300f), "d" to head(200f, 300f)))
        val path = BarPath(
            listOf(Onset(0.0, listOf("a")), Onset(1.0, listOf("b")), Onset(2.0, listOf("c")), Onset(3.0, listOf("d"))),
            listOf(0.0),
            geometry,
        )
        val xs = listOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5).map { path.at(it)?.x ?: 0f }
        xs.zipWithNext().forEach { (a, b) -> assert(b >= a) { "went back: $xs" } }
    }
}
