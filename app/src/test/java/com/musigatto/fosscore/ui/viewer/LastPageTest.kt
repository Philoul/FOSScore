package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastPageTest {

    @Test
    fun aScoreLeftAShortWhileAgoOpensWhereItWasLeft() {
        val left = 1_000_000L
        assertTrue(Settings.keepsLastPage(left, left + 60_000L))
        assertTrue(Settings.keepsLastPage(left, left + Settings.LAST_PAGE_KEPT_MS - 1))
    }

    @Test
    fun aScoreLeftLongAgoOrNeverOpensAtItsFirstPage() {
        val left = 1_000_000L
        assertFalse(Settings.keepsLastPage(left, left + Settings.LAST_PAGE_KEPT_MS))
        // saved before the time was kept
        assertFalse(Settings.keepsLastPage(0L, left + Settings.LAST_PAGE_KEPT_MS))
        // a clock put back
        assertFalse(Settings.keepsLastPage(left, left - 1))
    }
}
