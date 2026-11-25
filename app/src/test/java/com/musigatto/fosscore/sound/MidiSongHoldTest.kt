package com.musigatto.fosscore.sound

import com.musigatto.fosscore.musicxml.Hold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MidiSongHoldTest {

    // format 0, 480 ticks per quarter: ♩ = 60 then ♩ = 120 from the third quarter, three quarters of C4
    private val midi: ByteArray = run {
        val track = byteArrayOf(
            0x00, 0xFF.toByte(), 0x51, 0x03, 0x0F, 0x42, 0x40,
            0x00, 0x90.toByte(), 60, 100,
            0x83.toByte(), 0x60, 0x80.toByte(), 60, 0,
            0x00, 0x90.toByte(), 60, 100,
            0x83.toByte(), 0x60, 0x80.toByte(), 60, 0,
            0x00, 0xFF.toByte(), 0x51, 0x03, 0x07, 0xA1.toByte(), 0x20,
            0x00, 0x90.toByte(), 60, 100,
            0x83.toByte(), 0x60, 0x80.toByte(), 60, 0,
            0x00, 0xFF.toByte(), 0x2F, 0x00,
        )
        byteArrayOf(
            'M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(), 0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0.toByte(),
            'M'.code.toByte(), 'T'.code.toByte(), 'r'.code.toByte(), 'k'.code.toByte(), 0, 0, 0, track.size.toByte(),
        ) + track
    }

    @Test
    fun aFermataSlowsTheMusicDownOverItAndOnlyThere() {
        // the second quarter held: twice as long, the first and the third at their written tempo
        val song = MidiSong.read(midi).holding(listOf(Hold(1.0, 2.0)), 2.0)
        assertEquals(1_000_000, song.microsPerQuarter(0.5))
        assertEquals(2_000_000, song.microsPerQuarter(1.0))
        assertEquals(2_000_000, song.microsPerQuarter(1.99))
        assertEquals(500_000, song.microsPerQuarter(2.0))
        assertEquals(1.0, song.nextTempoChange(0.0)!!, 1e-9)
    }

    @Test
    fun aFermataOverATempoChangeHoldsBothTempos() {
        val song = MidiSong.read(midi).holding(listOf(Hold(1.5, 2.5)), 2.0)
        assertEquals(2_000_000, song.microsPerQuarter(1.5))
        assertEquals(1_000_000, song.microsPerQuarter(2.0))
        assertEquals(500_000, song.microsPerQuarter(2.5))
    }

    @Test
    fun noFermataLeavesTheSongAsItIs() {
        val song = MidiSong.read(midi)
        assertSame(song, song.holding(emptyList(), 2.0))
    }
}
