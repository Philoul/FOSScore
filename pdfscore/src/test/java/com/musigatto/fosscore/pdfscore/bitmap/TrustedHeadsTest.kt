package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A head the model names with little confidence is no head (Halleluya, measure 9: a half note at
 * 0.21, where the heads score 1.0, made the measure two beats too long). Other symbols are kept:
 * their confidence is read by the rules that place them.
 */
class TrustedHeadsTest {
    @Test
    fun aHeadNamedUnsurelyIsLeftOut() {
        val symbols = listOf(
            BitmapSymbol("noteheadHalf", 0, 0, 10, 8, 0.21, 0),
            BitmapSymbol("noteheadBlack", 20, 0, 30, 8, 1.0, 1),
            BitmapSymbol("restQuarter", 40, 0, 48, 20, 0.21, 2),
        )
        assertEquals(listOf(1, 2), BitmapPage.trusted(symbols).map { it.blob })
    }

    /**
     * A head read over most of a surer head (a second reading of one blob's piece) is the same
     * head: kept, it was a second note (scan benchmark 701 -> 708 measures right without it).
     */
    @Test
    fun aHeadOverASurerOneIsTheSameHead() {
        val symbols = listOf(
            BitmapSymbol("noteheadBlack", 20, 0, 30, 8, 1.0, 1),
            BitmapSymbol("noteheadBlack", 23, 1, 33, 9, 0.6, 2),
            BitmapSymbol("noteheadBlack", 40, 0, 50, 8, 0.6, 3),
        )
        assertEquals(listOf(1, 3), BitmapPage.trusted(symbols).map { it.blob })
    }
}
