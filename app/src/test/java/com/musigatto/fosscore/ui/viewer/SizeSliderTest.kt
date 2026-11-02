package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class SizeSliderTest {
    @Test
    fun aSizeSliderGivesWholePixels() {
        assertEquals(4f, wholePx(4.4f), 0f)
        assertEquals(31f, wholePx(30.6f), 0f)
    }
}
