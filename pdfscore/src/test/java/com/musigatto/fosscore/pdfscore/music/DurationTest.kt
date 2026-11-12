package com.musigatto.fosscore.pdfscore.music

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationTest {
    @Test
    fun fractionsStayReduced() {
        assertEquals(Fraction.of(1, 2), Fraction.of(2, 4))
        assertEquals(Fraction.of(3, 8), Fraction.of(1, 4) + Fraction.of(1, 8))
        assertEquals(Fraction.ZERO, Fraction.of(1, 4) - Fraction.of(2, 8))
        assertEquals("-1/2", Fraction.of(1, -2).toString())
    }

    @Test
    fun dotsAddHalfOfTheLastValue() {
        assertEquals(Fraction.of(1, 4), Duration(NoteType.QUARTER).length)
        assertEquals(Fraction.of(3, 8), Duration(NoteType.QUARTER, dots = 1).length)
        assertEquals(Fraction.of(7, 8), Duration(NoteType.HALF, dots = 2).length)
    }

    @Test
    fun aTripletEighthIsATwelfth() {
        assertEquals(Fraction.of(1, 12), Duration(NoteType.EIGHTH, tuplet = Fraction.of(2, 3)).length)
    }

    @Test
    fun flagsAndBeamsGiveTheValueOfABlackNote() {
        assertEquals(NoteType.QUARTER, NoteType.withBeams(0))
        assertEquals(NoteType.EIGHTH, NoteType.withBeams(1))
        assertEquals(NoteType.THIRTY_SECOND, NoteType.withBeams(3))
        assertEquals(null, NoteType.withBeams(9))
    }
}
