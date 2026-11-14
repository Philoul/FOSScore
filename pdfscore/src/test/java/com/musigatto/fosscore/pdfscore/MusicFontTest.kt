package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicFontTest {
    /** A font in Verovio's data format: an index, then one file per glyph. */
    private val files = mapOf(
        "F.xml" to """<font units-per-em="1000"><g c="E0A4" n="noteheadBlack"/><g c="E4E3" n="restWhole"/></font>""",
        "F/E0A4.xml" to """<symbol><path d="M0 0L250 0L250 250Z"/></symbol>""",
        "F/E4E3.xml" to """<symbol><path d="M0 0L500 0L500 125L0 125Z"/></symbol>""",
    )

    @Test
    fun aFontIsReadInSpaces() {
        val font = MusicFont.readVerovio("F") { files[it] }
        assertEquals(listOf("noteheadBlack", "restWhole"), font?.symbols?.map { it.name })
        assertEquals(1.0, font?.symbols?.first()?.box?.width ?: 0.0, 1e-9)
    }

    /** Only the symbols asked for are read: an app's assets hold hundreds of glyph files. */
    @Test
    fun onlyTheSymbolsAskedForAreRead() {
        val read = mutableListOf<String>()
        val font = MusicFont.readVerovio("F", setOf("restWhole")) { path -> read += path; files[path] }
        assertEquals(listOf("restWhole"), font?.symbols?.map { it.name })
        assertEquals(listOf("F.xml", "F/E4E3.xml"), read)
    }
}
