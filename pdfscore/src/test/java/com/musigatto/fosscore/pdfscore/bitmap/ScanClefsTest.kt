package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Clefs a scan's classifier names that are none (Mistral gagnant's scan). */
class ScanClefsTest {
    private val space = 20.0
    private val staff = BitmapStaff(DoubleArray(5) { 200.0 + space * it }, 100, 1900, 40, IntArray(46))

    private fun rules(): SymbolRules? = Golden.model?.let { SymbolRules(emptyList(), listOf(staff), space, 2.0, InkMask(2000, 600), it) }

    private fun symbol(name: String, x: Int, y0: Int, y1: Int) = BitmapSymbol(name, x, y0, x + 50, y1, 1.0, 0)

    /** A treble clef reaches over and under its staff; an octave line's "8" and hook read as one, over it only, is none. */
    @Test
    fun aTrebleClefReachesOverAndUnderItsStaff() {
        val rules = rules()
        assumeTrue(rules != null)
        assertTrue(rules!!.clefFits(symbol("gClef", 110, 170, 310)))
        // a clef change, drawn smaller
        assertTrue(rules.clefFits(symbol("gClef", 900, 185, 300)))
        // the "8" of an octave line over the staff
        assertFalse(rules.clefFits(symbol("gClef", 900, 180, 260)))
    }

    /** A bass clef past the staff's start may be a time signature's digit; at the start it is the clef. */
    @Test
    fun aBassClefInTheStaffMayBeATimeSignature() {
        val rules = rules()
        assumeTrue(rules != null)
        assertTrue(rules!!.mayBeDigits(symbol("fClef", 900, 200, 262)))
        assertFalse(rules.mayBeDigits(symbol("fClef", 110, 200, 262)))
    }
}
