package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayActionTest {

    /**
     * ▶ on a PDF whose music was read, pressed before its companion is open (it takes seconds on
     * the Tab 3): it waits for the companion and plays. It read the whole PDF again instead.
     */
    @Test
    fun aPdfReadAlreadyWaitsForItsCompanion() {
        assertEquals(PlayAction.WAIT_FOR_COMPANION, playAction(music = false, isMusicXml = false, isPdf = true, companionKept = true, noMusic = false))
    }

    @Test
    fun theOtherCases() {
        assertEquals(PlayAction.PLAY, playAction(music = true, isMusicXml = false, isPdf = true, companionKept = true, noMusic = false))
        assertEquals(PlayAction.PLAY, playAction(music = true, isMusicXml = true, isPdf = false, companionKept = false, noMusic = false))
        assertEquals(PlayAction.NOTHING, playAction(music = false, isMusicXml = true, isPdf = false, companionKept = false, noMusic = false))
        assertEquals(PlayAction.READ, playAction(music = false, isMusicXml = false, isPdf = true, companionKept = false, noMusic = false))
        assertEquals(PlayAction.METRONOME, playAction(music = false, isMusicXml = false, isPdf = true, companionKept = false, noMusic = true))
    }
}
