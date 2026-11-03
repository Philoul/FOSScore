package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** The half-page cut line, drawn dash by dash: Android 7 drew it as a full line. */
class HalfCutDashesTest {
    @Test
    fun onABlankBandTheCutIsDashedAcrossThePage() {
        assertEquals(
            listOf(100f to 104f, 108f to 112f, 116f to 120f),
            halfCutDashes(left = 100f, right = 120f, onBlank = true, tick = 10f, dash = 4f),
        )
    }

    @Test
    fun throughTheMusicOnlyTheMarginsAreDashed() {
        assertEquals(
            listOf(100f to 104f, 108f to 110f, 190f to 194f, 198f to 200f),
            halfCutDashes(left = 100f, right = 200f, onBlank = false, tick = 10f, dash = 4f),
        )
    }
}
