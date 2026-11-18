package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.PageGeometry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfPartsTest {
    // a page 1000 high, a turned half page cut at its middle
    private val height = 1000f
    private val cut = 0.5f

    // a note of a system from [top] to [bottom], its head at [y]
    private fun head(y: Float, top: Float, bottom: Float) = PageGeometry.Head(100f, y, top, bottom, 10f, 900f)

    /**
     * A system the cut goes through shows half on each part: none of its notes is on screen, on
     * either part. Counted by its heads, the notes of its upper staff were above the cut, so the
     * page turned only after it (Je veux être un homme heureux, measures 20-21: the turn came at
     * the end of measure 22 instead of 19).
     */
    @Test
    fun aSystemCutInTwoIsInNeitherPart() {
        val upperStaffNote = head(450f, 420f, 560f)
        assertFalse(HalfParts.wholeAbove(upperStaffNote, height, cut))
        assertFalse(HalfParts.wholeBelow(upperStaffNote, height, cut))
    }

    @Test
    fun aSystemOnOneSideIsInThatPart() {
        assertTrue(HalfParts.wholeAbove(head(300f, 250f, 400f), height, cut))
        assertFalse(HalfParts.wholeBelow(head(300f, 250f, 400f), height, cut))
        assertTrue(HalfParts.wholeBelow(head(700f, 600f, 750f), height, cut))
        assertFalse(HalfParts.wholeAbove(head(700f, 600f, 750f), height, cut))
    }

    /** A hidden note (no head drawn) counts as at the top of its page, as before. */
    @Test
    fun aHiddenNoteIsAtTheTop() {
        assertTrue(HalfParts.wholeAbove(null, height, cut))
        assertFalse(HalfParts.wholeBelow(null, height, cut))
    }
}
