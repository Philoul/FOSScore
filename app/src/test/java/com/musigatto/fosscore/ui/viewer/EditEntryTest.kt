package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditEntryTest {
    @Test
    fun aScoreThatCanBeAnnotatedOffersThePen() {
        assertTrue(editEntryShown(editing = false, annotatable = true))
    }

    @Test
    fun whileAnnotatingTheTickEndsIt() {
        assertFalse(editEntryShown(editing = true, annotatable = true))
    }

    @Test
    fun aScoreThatCannotBeAnnotatedDoesNotOfferAPenThatOpensNothing() {
        assertFalse(editEntryShown(editing = false, annotatable = false))
    }
}
