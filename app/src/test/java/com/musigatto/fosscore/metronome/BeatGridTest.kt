package com.musigatto.fosscore.metronome

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeatGridTest {

    @Test
    fun aBarOfFourBeatsClicksOnEachQuarterTheFirstAccented() {
        val grid = BeatGrid(listOf(0.0, 4.0), end = 8.0, meter = Meter(4, 4), subdivision = 1, accent = true)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0), grid.at, 1e-9)
        assertEquals(ClickKind.ACCENT, grid.kinds[0])
        assertEquals(ClickKind.BEAT, grid.kinds[1])
        assertEquals(ClickKind.ACCENT, grid.kinds[4])
    }

    @Test
    fun aPickupBarHasOnlyItsOwnBeats() {
        // one quarter of pickup, then a full bar of 3/4
        val grid = BeatGrid(listOf(0.0, 1.0), end = 4.0, meter = Meter(3, 4), subdivision = 1, accent = true)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 2.0, 3.0), grid.at, 1e-9)
        assertEquals(Tick(1, 0), grid.tickAt(1.0))
        assertEquals(Tick(1, 2), grid.tickAt(3.5))
    }

    @Test
    fun compoundTimeClicksDottedQuartersAndTheirSubdivisions() {
        // 6/8: two pulses of a dotted quarter (1.5 quarters), each in three eighths
        val grid = BeatGrid(listOf(0.0), end = 3.0, meter = Meter(6, 8), subdivision = 3, accent = false)
        assertArrayEquals(doubleArrayOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5), grid.at, 1e-9)
        assertEquals(listOf(ClickKind.BEAT, ClickKind.SUBDIVISION, ClickKind.SUBDIVISION), grid.kinds.take(3))
        assertEquals(Tick(0, 1), grid.tickAt(2.2))
    }

    @Test
    fun beforeTheFirstBeatThereIsNoTick() {
        val grid = BeatGrid(listOf(1.0), end = 5.0, meter = Meter(4, 4), subdivision = 1, accent = true)
        assertNull(grid.tickAt(0.5))
    }
}
