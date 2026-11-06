package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class SetlistTurnTest {

    private fun flow(page: Int, halfTurned: Boolean = false, half: Boolean = false, twoUp: Boolean = false) =
        PageFlow(page, halfTurned, halfEnabled = half, twoUp = twoUp, isLandscape = true, pageCount = 3)

    @Test
    fun pastTheLastPageASetlistOpensTheNextScore() {
        assertEquals(Turn.ToOtherScore, turnNext(flow(2), hasNextScore = true))
        // alone, or at the end of the setlist, the last page stays
        assertEquals(Turn.Stay, turnNext(flow(2), hasNextScore = false))
        // before the end, the page turns as always
        assertEquals(Turn.ToPage(flow(1)), turnNext(flow(0), hasNextScore = true))
    }

    @Test
    fun withHalfPagesTheNextScoreComesAfterTheLastHalf() {
        // the top of the last page shows under the end of the page before: one more turn first
        assertEquals(Turn.ToPage(flow(2, half = true)), turnNext(flow(1, halfTurned = true, half = true), hasNextScore = true))
        assertEquals(Turn.ToOtherScore, turnNext(flow(2, half = true), hasNextScore = true))
    }

    @Test
    fun beforeTheFirstPageASetlistOpensTheScoreBefore() {
        assertEquals(Turn.ToOtherScore, turnPrev(flow(0), hasPreviousScore = true))
        assertEquals(Turn.Stay, turnPrev(flow(0), hasPreviousScore = false))
        assertEquals(Turn.ToPage(flow(0)), turnPrev(flow(1), hasPreviousScore = true))
    }
}
