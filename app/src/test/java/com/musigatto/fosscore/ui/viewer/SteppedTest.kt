package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class SteppedTest {

    @Test
    fun aStepGoesToTheNextMultiple() {
        assertEquals(90, stepped(87, 5, 1))
        assertEquals(85, stepped(87, 5, -1))
        assertEquals(90, stepped(85, 5, 1))
        assertEquals(80, stepped(85, 5, -1))
        assertEquals(101, stepped(100, 1, 1))
        assertEquals(99, stepped(100, 1, -1))
    }
}
