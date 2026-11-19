package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ▶ reuses what the opening of the score warmed (the MIDI read, the samples) when the channels'
 * sounds are the same: it took 150 to 340 ms again (2026-11-20, the maintainer wants under 500).
 */
class WarmReuseTest {
    private val same = { a: String, b: String -> a == b }

    @Test
    fun theSameSoundsChannelByChannelAreTheSame() {
        assertTrue(sameSounds(listOf(null, "piano", null), listOf(null, "piano", null), same))
    }

    @Test
    fun anotherSoundOnAChannelIsNot() {
        assertFalse(sameSounds(listOf(null, "piano"), listOf(null, "organ"), same))
        assertFalse(sameSounds(listOf(null, "piano"), listOf("piano", null), same))
        assertFalse(sameSounds(listOf<String?>(null), listOf(null, null), same))
    }
}
