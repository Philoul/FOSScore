package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ledger lines taken out of a scan, and what is no ledger line. */
class LedgerLinesTest {
    // a staff with lines every 20 rows from row 100, columns 0..299
    private val staff = BitmapStaff(doubleArrayOf(100.0, 120.0, 140.0, 160.0, 180.0), 0, 299, 300, intArrayOf(0))

    private fun page(): InkMask {
        val m = InkMask(300, 300)
        for (k in 0 until 5) for (x in 0..299) for (y in 100 + 20 * k..101 + 20 * k) m[x, y] = true
        return m
    }

    /**
     * A tie over the staff whose flat top lies where the first ledger line would: it was erased as
     * one, the tie cut in pieces nothing read (Mistral gagnant, measure 3).
     */
    @Test
    fun aTieAtALedgerLinesHeightIsKept() {
        val m = page()
        for (x in 40..160) {
            val y = (80 + 0.0012 * (x - 100) * (x - 100)).toInt() // flat top at row 80, a space over the staff
            for (t in 0..2) m[x, y + t] = true
        }
        val free = LineRemoval.remove(m, listOf(staff), 2.0)
        assertTrue((40..160).all { x -> (60..99).any { free[x, it] } })
    }

    /** A head on the first ledger line over the staff: the line is erased, the head kept. */
    @Test
    fun aLedgerLineHoldingAHeadIsErased() {
        val m = page()
        for (x in 190..225) for (y in 80..81) m[x, y] = true // the ledger line
        for (x in 196..219) for (y in 71..89) m[x, y] = true // its head
        val found = mutableListOf<LineRemoval.Ledger>()
        val free = LineRemoval.remove(m, listOf(staff), 2.0, found)
        assertEquals(1, found.size)
        assertFalse(free[192, 80])
        assertTrue(free[205, 80])
    }

    /** A note two ledger lines over the staff: the inner line, with no head, leads to it. */
    @Test
    fun anInnerLedgerLineLeadingToAHeadIsErased() {
        val m = page()
        for (x in 190..225) for (y in 80..81) m[x, y] = true
        for (x in 190..225) for (y in 60..61) m[x, y] = true
        for (x in 196..219) for (y in 51..69) m[x, y] = true // the head on the second line
        val found = mutableListOf<LineRemoval.Ledger>()
        LineRemoval.remove(m, listOf(staff), 2.0, found)
        assertEquals(2, found.size)
    }
}
