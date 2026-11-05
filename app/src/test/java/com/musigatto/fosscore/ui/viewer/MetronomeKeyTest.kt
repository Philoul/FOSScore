package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Metronome key does what the band's own button does (one band for the music and the metronome). */
class MetronomeKeyTest {
    @Test
    fun overTheMusicTheKeySwitchesTheClicksAsTheBandButton() {
        // not the metronome alone: that stopped the music
        assertEquals(MetronomeKey.TOGGLE_CLICKS, metronomeKey(isMusicXml = true))
    }

    @Test
    fun onAPdfTheKeyStartsOrStopsTheMetronomeAlone() {
        assertEquals(MetronomeKey.START_STOP, metronomeKey(isMusicXml = false))
    }
}
