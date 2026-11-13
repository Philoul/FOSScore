package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Symbols of the free fonts named by their look against all of them (needs Verovio's font data, which :musicxml downloads). */
class FontSymbolNamesTest {
    private val dir = File(System.getProperty("pdfscore.fonts") ?: "")

    private fun fonts(): List<MusicFont> {
        assumeTrue(dir.path.isNotEmpty() && dir.isDirectory)
        return TestFonts.all(dir).also { assumeTrue(it.isNotEmpty()) }
    }

    /** Verovio draws a tuplet's number with the music font: a small 3 is a tuplet's, not an octave's 8. */
    @Test
    fun aTupletNumberOfEveryFontIsNamed() {
        val fonts = fonts()
        val classifier = SymbolClassifier(fonts.flatMap { font -> font.symbols.filter { it.name in CommonSymbols.NAMES } })
        for (font in fonts) {
            for (name in listOf("tuplet3", "tuplet5", "tuplet6", "timeSig3")) {
                val symbol = font.symbols.firstOrNull { it.name == name } ?: continue
                assertEquals("${font.name} $name", name, classifier.name(symbol.polygons)?.name)
            }
        }
    }
}
