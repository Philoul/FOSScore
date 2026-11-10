package com.musigatto.fosscore.ui.library

import com.musigatto.fosscore.library.Sheet
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetFilterTest {
    private fun sheet(id: Long, title: String = "", composer: String = "", musicalKey: String = "") =
        Sheet(id = id, fileName = "", path = "", hash = "", title = title, composer = composer, genre = "", musicalKey = musicalKey, tags = "")

    private val sheets = listOf(
        sheet(1, title = "Fantasía en re menor", composer = "Ludwig van Beethoven"),
        sheet(2, title = "Clave de sol", composer = "Astor Piazzolla")
    )

    // the tags come from their table, by score id
    private val tags = mapOf(2L to listOf("Tángo", "duo"))

    @Test
    fun matchesIgnoringAccentsAndCase() {
        assertEquals(1, filterSheets(sheets, "beethoven", tags).size)
        assertEquals(1, filterSheets(sheets, "RE MENOR", tags).size)
    }

    @Test
    fun matchesTokensAcrossFields() {
        assertEquals(1, filterSheets(sheets, "beethoven menor", tags).size)
        assertEquals(1, filterSheets(sheets, "tango piazzolla", tags).size)
    }

    @Test
    fun matchesAScoresTags() {
        assertEquals(listOf(sheets[1]), filterSheets(sheets, "duo", tags))
        assertEquals(emptyList<Sheet>(), filterSheets(sheets, "duo"))
    }

    @Test
    fun emptyQueryReturnsAll() {
        assertEquals(sheets, filterSheets(sheets, "", tags))
        assertEquals(sheets, filterSheets(sheets, "   ", tags))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList<Sheet>(), filterSheets(sheets, "chopin", tags))
    }
}
