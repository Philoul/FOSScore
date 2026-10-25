package com.musigatto.fosscore.metronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetronomeTest {

    @Test
    fun compoundTimeIsCountedInDottedQuarters() {
        assertEquals(2, Meter(6, 8).pulses)
        assertEquals(4, Meter(12, 8).pulses)
        assertEquals(3, Meter(3, 8).pulses)
        assertEquals(4, Meter(4, 4).pulses)
        assertTrue(Meter(9, 8).compound)
        assertFalse(Meter(3, 4).compound)
    }

    @Test
    fun meterReadsTheWrittenTimeSignature() {
        assertEquals(Meter(6, 8), Meter.parse("6/8"))
        assertEquals(Meter(4, 4), Meter.parse("C"))
        assertEquals(Meter(2, 2), Meter.parse("¢"))
        assertNull(Meter.parse("3+2/8"))
        assertNull(Meter.parse(null))
    }

    @Test
    fun tempoNames() {
        assertEquals("Grave", tempoName(30))
        assertEquals("Andante", tempoName(92))
        assertEquals("Allegro", tempoName(120))
        assertEquals("Prestissimo", tempoName(220))
    }

    @Test
    fun tapTempoAveragesTheLastTaps() {
        val tap = TapTempo()
        assertNull(tap.tap(0))
        assertEquals(120, tap.tap(500))
        // one uneven tap moves the tempo a little, not to 100
        assertEquals(109, tap.tap(1_100))
        assertEquals(112, tap.tap(1_600))
        // a long pause starts again
        assertNull(tap.tap(10_000))
        assertEquals(60, tap.tap(11_000))
    }

    @Test
    fun barPagesFindThePageOfEachBar() {
        // 6 bars on page 1 (the title takes room), then 8 on every page
        val pages = BarPages(listOf(6, 8), pageCount = 4)
        assertEquals(8, pages.barsOf(3))
        assertEquals(14, pages.firstBar(2))
        assertEquals(0, pages.pageOf(5))
        assertEquals(1, pages.pageOf(6))
        assertEquals(2, pages.pageOf(14))
        // past the last bar counted: the last page
        assertEquals(3, pages.pageOf(100))
        assertFalse(pages.inLowerHalf(2))
        assertTrue(pages.inLowerHalf(3))
        assertTrue(pages.inLowerHalf(10))
        assertEquals(10, pages.startBar(1, lowerHalf = true))
    }

    @Test
    fun clicksFallOnTheAudioFramesOfTheBeats() {
        val schedule = ClickSchedule(countInBars = 1)
        // ♩ = 120: a beat every half second, 22050 frames; one bar of count-in first
        val clicks = List(5) { schedule.next(120, Meter(4, 4), 1) }
        assertEquals(listOf(0L, 22_050L, 44_100L, 66_150L, 88_200L), clicks.map { it.first })
        assertEquals(Tick(-1, 0), clicks[0].second)
        assertEquals(Tick(0, 0), clicks[4].second)
        // in 6/8, two beats a bar, each split in three
        val compound = ClickSchedule(countInBars = 0)
        val ticks = List(7) { compound.next(60, Meter(6, 8), 3).second }
        assertEquals(Tick(0, 0, 1), ticks[1])
        assertEquals(Tick(0, 1, 0), ticks[3])
        assertEquals(Tick(1, 0, 0), ticks[6])
    }

    @Test
    fun barPagesWithoutCountsTurnNothing() {
        val pages = BarPages(emptyList(), pageCount = 3)
        assertFalse(pages.known)
        assertEquals(0, pages.pageOf(12))
        assertEquals(listOf(6, 8, 8), BarPages.parse("6, 8,8"))
        assertEquals("6,8,8", BarPages.format(listOf(6, 8, 8)))
    }
}
