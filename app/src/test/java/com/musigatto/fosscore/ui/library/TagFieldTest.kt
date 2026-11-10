package com.musigatto.fosscore.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class TagFieldTest {

    @Test
    fun spaceCommaAndSemicolonSeparateTags() {
        assertEquals(listOf("tango", "vals", "rock"), withTags(emptyList(), " tango  vals,rock; "))
    }

    @Test
    fun addingKeepsEachTagOnceIgnoringCaseAndAccents() {
        assertEquals(listOf("Tángo", "vals"), withTags(listOf("Tángo"), "tango vals"))
        assertEquals(listOf("vals"), withTags(emptyList(), "vals"))
        assertEquals(listOf("rock"), withTags(listOf("rock"), ""))
    }

    @Test
    fun typingASpaceFinishesTheTag() {
        assertEquals(listOf("duo") to "", typedTags(emptyList(), "duo "))
        assertEquals(listOf("duo") to "pra", typedTags(emptyList(), "duo pra"))
        assertEquals(emptyList<String>() to "duo", typedTags(emptyList(), "duo"))
    }

    @Test
    fun theLibrarysOtherTagsAreOffered() {
        val known = listOf("duo", "Practice", "rock")
        assertEquals(listOf("Practice", "rock"), suggestTags(known, listOf("Duo"), ""))
        assertEquals(listOf("Practice"), suggestTags(known, emptyList(), "prá"))
        assertEquals(emptyList<String>(), suggestTags(known, listOf("rock"), "ro"))
    }
}
