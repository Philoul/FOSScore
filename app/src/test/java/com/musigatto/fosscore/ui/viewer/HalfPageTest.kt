package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A half-page cut only goes through rows of paper: blankBands depends on these rows. */
class HalfPageTest {

    @Test
    fun aRowOfPaperIsBlank() {
        val rows = blankRows(
            intArrayOf(0x00000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt()),
            width = 3,
            height = 1,
        )
        assertTrue(rows[0])
    }

    @Test
    fun onePixelBelowThePaperThresholdMakesTheRowUsed() {
        val rows = blankRows(
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt(), 0xFFDFDFDF.toInt()),
            width = 3,
            height = 1,
        )
        assertFalse(rows[0])
    }
}
