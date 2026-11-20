package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Onset
import com.musigatto.fosscore.musicxml.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A long press on a measure plays from it (maintainer, 2026-11-20): the measure under the finger,
 * found by where its line is and where it ends, and the first time it is played.
 */
class PlayFromMeasureTest {
    // a line of two measures (0-200, 200-400) over a line of one (0-400), on a page of a PDF read
    private fun head(x: Float, top: Float, measureEnd: Float) = PageGeometry.Head(x, top + 10f, top, top + 40f, 5f, 400f, measureEnd)
    private val geometry = PageGeometry.of(
        600f, 800f,
        mapOf(
            "fosscore-note-1-1-0-0" to head(50f, 100f, 200f),
            "fosscore-note-2-1-0-0" to head(250f, 100f, 400f),
            "fosscore-note-3-1-0-0" to head(50f, 300f, 400f),
        ),
    )
    // measure 1, 2, then 1 and 2 again (a repeat), then 3
    private val onsets = listOf(
        Onset(0.0, listOf("fosscore-note-1-1-0-0")), Onset(4.0, listOf("fosscore-note-2-1-0-0")),
        Onset(8.0, listOf("fosscore-note-1-1-0-0")), Onset(12.0, listOf("fosscore-note-2-1-0-0")),
        Onset(16.0, listOf("fosscore-note-3-1-0-0")),
    )

    @Test
    fun theMeasureUnderTheFingerPlaysTheFirstTimeItComes() {
        // between the barline and the first note of measure 2
        assertEquals(4.0, measureStartAt(geometry, 210f, 120f, onsets) ?: -1.0, 1e-9)
        // the clef of the first line: its first measure
        assertEquals(0.0, measureStartAt(geometry, 10f, 120f, onsets) ?: -1.0, 1e-9)
        assertEquals(16.0, measureStartAt(geometry, 300f, 330f, onsets) ?: -1.0, 1e-9)
    }

    @Test
    fun outsideAnyLineIsNoMeasure() {
        assertNull(measureStartAt(geometry, 300f, 700f, onsets))
    }

    @Test
    fun theMeasureOfANoteOfAMusicXmlScore() {
        assertEquals("12", measureKey("fosscore-n2-12-3"))
        assertEquals("7", measureKey("fosscore-note-7-2-0-1"))
        assertNull(measureKey("m-123abc"))
    }
}
