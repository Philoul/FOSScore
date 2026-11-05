package com.musigatto.fosscore.metronome

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountInTest {

    @Test
    fun twoBarsOfFourBeatsClickBeforeTheStartOfThePiece() {
        val countIn = CountIn(2, Meter(4, 4), subdivision = 1, accent = true, from = 0.0)
        assertEquals(8.0, countIn.length, 1e-9)
        assertArrayEquals(doubleArrayOf(-8.0, -7.0, -6.0, -5.0, -4.0, -3.0, -2.0, -1.0), countIn.at, 1e-9)
        assertEquals(ClickKind.ACCENT, countIn.kinds[0])
        assertEquals(ClickKind.ACCENT, countIn.kinds[4])
        assertEquals(Tick(-2, 0), countIn.tickAt(-8.0))
        assertEquals(Tick(-1, 3), countIn.tickAt(-0.5))
    }

    @Test
    fun theCountInEndsWhereTheMusicStarts() {
        // playing from bar 3 of a 3/4 piece: one bar counted before it
        val countIn = CountIn(1, Meter(3, 4), subdivision = 1, accent = true, from = 6.0)
        assertArrayEquals(doubleArrayOf(3.0, 4.0, 5.0), countIn.at, 1e-9)
        assertEquals(Tick(-1, 2), countIn.tickAt(5.9))
        assertNull(countIn.tickAt(6.0))
        assertNull(countIn.tickAt(2.5))
    }

    @Test
    fun compoundTimeCountsDottedQuarters() {
        val countIn = CountIn(1, Meter(6, 8), subdivision = 1, accent = false, from = 0.0)
        assertEquals(3.0, countIn.length, 1e-9)
        assertArrayEquals(doubleArrayOf(-3.0, -1.5), countIn.at, 1e-9)
    }

    @Test
    fun noCountInIsNoClick() {
        val countIn = CountIn(0, Meter(4, 4), subdivision = 1, accent = true, from = 4.0)
        assertEquals(0.0, countIn.length, 1e-9)
        assertEquals(0, countIn.at.size)
        assertNull(countIn.tickAt(3.5))
    }
}
