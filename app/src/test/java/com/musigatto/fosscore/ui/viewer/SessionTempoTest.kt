package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tempo a score starts at (maintainer's rule, 2026-11-19): changed by hand, it holds for the
 * session; set in the score's settings, it is the default; else the tempo printed (factor 1).
 */
class SessionTempoTest {
    @Test
    fun theTempoPrintedFirst() {
        val tempos = SessionTempos()
        assertEquals(1f, tempos.start("a", scoreDefault = null))
    }

    @Test
    fun theScoresSettingBeforeThePrintedOne() {
        val tempos = SessionTempos()
        assertEquals(0.8f, tempos.start("a", scoreDefault = 0.8f))
    }

    @Test
    fun aChangeByHandHoldsForTheSessionOnly() {
        val tempos = SessionTempos()
        tempos.set("a", 1.2f)
        assertEquals(1.2f, tempos.start("a", scoreDefault = 0.8f))
        // another score keeps its own
        assertEquals(1f, tempos.start("b", scoreDefault = null))
        // a new session (the app started again) starts from the score again
        assertEquals(0.8f, SessionTempos().start("a", scoreDefault = 0.8f))
    }
}
