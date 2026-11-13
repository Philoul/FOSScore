package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FontNamingTest {
    private fun ellipse(rx: Double, ry: Double) = (0 until 32).map { i -> Point(rx * cos(2 * PI * i / 32), ry * sin(2 * PI * i / 32)) }
    private fun ring(rx: Double, ry: Double) = listOf(ellipse(rx, ry), ellipse(0.55 * rx, 0.55 * ry).reversed())
    private fun square(side: Double) = listOf(Point(0.0, 0.0), Point(side, 0.0), Point(side, side), Point(0.0, side))

    private val classifier = SymbolClassifier(
        listOf(
            FontSymbol("A", "noteheadBlack", listOf(ellipse(0.6, 0.5))),
            FontSymbol("A", "noteheadHalf", ring(0.6, 0.5)),
            FontSymbol("A", "augmentationDot", listOf(ellipse(0.2, 0.2))),
        ),
    )

    private val smufl = mapOf(0xE0A4 to "noteheadBlack", 0xE0A3 to "noteheadHalf", 0xE1E7 to "augmentationDot", 0xE4E5 to "restQuarter", 0xE680 to "harpPedalRaised")

    private val naming = FontNaming(classifier, smufl)

    /**
     * A font whose code points agree with the look of most of its glyphs follows SMuFL: its code
     * names the glyph the look does not tell (an unusual quarter rest, here a square), or tells wrong.
     */
    @Test
    fun aSmuflFontsCodesNameItsGlyphs() {
        val glyphs = listOf(
            FontNaming.Glyph(0xE0A4, listOf(ellipse(0.62, 0.48))),
            FontNaming.Glyph(0xE0A3, ring(0.61, 0.5)),
            FontNaming.Glyph(0xE1E7, listOf(ellipse(0.21, 0.2))),
            FontNaming.Glyph(0xE4E5, listOf(square(1.0))),
            FontNaming.Glyph(0xE0A3, listOf(ellipse(0.6, 0.5))), // drawn filled: the code says half
        )
        assertEquals(listOf("noteheadBlack", "noteheadHalf", "augmentationDot", "restQuarter", "noteheadHalf"), naming.names(glyphs))
    }

    /** A symbol recognition does not look for stays unnamed, though it looks like one it does. */
    @Test
    fun aSmuflSymbolNotLookedForStaysUnnamed() {
        val glyphs = listOf(
            FontNaming.Glyph(0xE0A4, listOf(ellipse(0.6, 0.5))),
            FontNaming.Glyph(0xE680, listOf(ellipse(0.6, 0.5))),
        )
        assertEquals(listOf("noteheadBlack", null), naming.names(glyphs))
    }

    /** A font whose codes mostly disagree with its glyphs' look uses them for something else: the look names them. */
    @Test
    fun aFontOfItsOwnCodesIsNamedByLook() {
        val glyphs = listOf(
            FontNaming.Glyph(0xE0A3, listOf(ellipse(0.6, 0.5))),
            FontNaming.Glyph(0xE1E7, ring(0.6, 0.5)),
            FontNaming.Glyph(0xE0A4, listOf(ellipse(0.2, 0.2))),
            FontNaming.Glyph(null, listOf(ellipse(0.6, 0.5))),
        )
        assertEquals(listOf("noteheadBlack", "noteheadHalf", "augmentationDot", "noteheadBlack"), naming.names(glyphs))
    }
}
