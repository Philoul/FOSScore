package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** The dashed boundary between two lines of music of a MusicXML page while annotating. */
class BoundaryDashesTest {
    @Test
    fun theBoundaryIsDrawnDashByDash() {
        // Android 7 drew a line with a dash effect as a full line: the dashes are drawn one by one
        assertEquals(listOf(0f to 8f, 16f to 24f, 32f to 40f), dashes(length = 40f, dash = 8f))
        // the last dash stops at the end of the line
        assertEquals(listOf(0f to 8f, 16f to 20f), dashes(length = 20f, dash = 8f))
        assertEquals(emptyList<Pair<Float, Float>>(), dashes(length = 0f, dash = 8f))
    }
}
