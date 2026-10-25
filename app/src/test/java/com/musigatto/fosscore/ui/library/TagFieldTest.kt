package com.musigatto.fosscore.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TagFieldTest {

    @Test
    fun spaceCommaAndSemicolonSeparateTags() {
        assertEquals(listOf("tango", "vals", "rock"), parseTags(" tango  vals,rock; "))
    }

    @Test
    fun mergingAddsWithoutRepeatsIgnoringCaseAndAccents() {
        assertEquals("Tángo vals", mergeTags("Tángo", "tango vals"))
        assertEquals("vals", mergeTags("", "vals"))
        assertEquals("rock", mergeTags("rock", ""))
    }

    @Test
    fun legacyTextWithCommasBecomesSpacedTags() {
        assertEquals("tango vals", mergeTags("tango, vals", "vals"))
    }
}
