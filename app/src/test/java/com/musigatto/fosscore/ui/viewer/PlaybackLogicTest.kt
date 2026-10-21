package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Part
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackLogicTest {

    @Test
    fun theLoopButtonMarksAThenBThenStops() {
        val a = LoopState.Off.tap(8.0)
        assertEquals(LoopState.FromA(8.0), a)
        val looping = a.tap(24.0)
        assertEquals(LoopState.Looping(8.0, 24.0), looping)
        assertEquals(LoopState.Off, looping.tap(30.0))
    }

    @Test
    fun aBMarkedBeforeASwapsThem() {
        // the music was taken back between the two taps
        assertEquals(LoopState.Looping(4.0, 16.0), LoopState.FromA(16.0).tap(4.0))
    }

    @Test
    fun aSecondTapOnTheSameSpotWaitsForTheMusic() {
        assertEquals(LoopState.FromA(8.0), LoopState.FromA(8.0).tap(8.0))
    }

    @Test
    fun backGoesToAWhenThereIsOne() {
        assertEquals(0.0, LoopState.Off.restartAt(), 0.0)
        assertEquals(8.0, LoopState.FromA(8.0).restartAt(), 0.0)
        assertEquals(8.0, LoopState.Looping(8.0, 24.0).restartAt(), 0.0)
    }

    @Test
    fun tempoIsShownInBeatsPerMinute() {
        assertEquals(100, bpmOf(1f, 100.0))
        assertEquals(80, bpmOf(0.8f, 100.0))
        assertEquals(0.8f, factorOf(80, 100.0), 1e-6f)
    }

    @Test
    fun tempoStaysInTheRangeTheModuleAccepts() {
        assertEquals(MIN_TEMPO_FACTOR, factorOf(1, 100.0), 0f)
        assertEquals(MAX_TEMPO_FACTOR, factorOf(1000, 100.0), 0f)
    }

    @Test
    fun thePartsHeardAreThePartsShownUntilPicked() {
        val parts = listOf(Part("P1", "Voice"), Part("P2", "Piano"))
        assertEquals(setOf("P1"), heardParts(null, setOf("P1"), parts))
        assertEquals(setOf("P2"), heardParts(setOf("P2"), setOf("P1"), parts))
    }

    @Test
    fun theMusicNeverGoesSilentByAccident() {
        val parts = listOf(Part("P1", "Voice"), Part("P2", "Piano"))
        // a part that is no longer in the file is dropped; with nothing left, every part plays
        assertEquals(setOf("P1", "P2"), heardParts(setOf("gone"), setOf("P1"), parts))
    }
}
