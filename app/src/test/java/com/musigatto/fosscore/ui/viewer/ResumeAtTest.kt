package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Onset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where ▶ goes on after a pause (maintainer, 2026-11-19: "it always starts again from the start or
 * the pause, even on another page"): the page shown when the music paused there is no longer on it.
 */
class ResumeAtTest {
    private val onsets = listOf(Onset(0.0, listOf("a")), Onset(4.0, listOf("b")), Onset(8.0, listOf("c")))

    @Test
    fun thePauseOnScreenGoesOnFromThePause() {
        assertNull(resumeElsewhere(listOf("a"), setOf("a", "b"), onsets))
    }

    @Test
    fun anotherPageShownStartsAtItsFirstNotes() {
        assertEquals(8.0, resumeElsewhere(listOf("a"), setOf("c"), onsets) ?: -1.0, 1e-9)
    }

    @Test
    fun nothingKnownOnScreenGoesOnFromThePause() {
        assertNull(resumeElsewhere(listOf("a"), emptySet(), onsets))
    }
}
