package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SymbolClassifierTest {
    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double) =
        (0 until 32).map { i -> Point(cx + rx * cos(2 * PI * i / 32), cy + ry * sin(2 * PI * i / 32)) }

    private fun rectangle(left: Double, top: Double, right: Double, bottom: Double) =
        listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))

    /** A ring: the outer outline one way, the inner one the other way (a hole, nonzero winding). */
    private fun ring(rx: Double, ry: Double, inner: Double) = listOf(ellipse(0.0, 0.0, rx, ry), ellipse(0.0, 0.0, rx * inner, ry * inner).reversed())

    private val classifier = SymbolClassifier(
        listOf(
            FontSymbol("A", "noteheadBlack", listOf(ellipse(0.0, 0.0, 0.6, 0.5))),
            FontSymbol("A", "noteheadHalf", ring(0.6, 0.5, 0.55)),
            FontSymbol("A", "noteheadWhole", ring(0.85, 0.5, 0.5)),
            FontSymbol("A", "augmentationDot", listOf(ellipse(0.0, 0.0, 0.2, 0.2))),
            FontSymbol("A", "stem", listOf(rectangle(0.0, 0.0, 0.12, 3.5))),
        ),
    )

    /** Another font's head, a bit wider and flatter: still a black head, not a dot. */
    @Test
    fun aHeadOfAnotherFontIsNamed() {
        assertEquals("noteheadBlack", classifier.name(listOf(ellipse(5.0, 5.0, 0.68, 0.45)))?.name)
        assertEquals("noteheadHalf", classifier.name(ring(0.66, 0.46, 0.6))?.name)
    }

    /** A dot and a black head have the same shape: their size tells them apart. */
    @Test
    fun theSizeTellsADotFromAHead() {
        assertEquals("augmentationDot", classifier.name(listOf(ellipse(0.0, 0.0, 0.22, 0.21)))?.name)
    }

    /** A half and a whole note's heads are both rings: their width tells them apart. */
    @Test
    fun theWidthTellsAWholeFromAHalf() {
        assertEquals("noteheadWhole", classifier.name(ring(0.9, 0.52, 0.5))?.name)
    }

    @Test
    fun nothingAlikeIsLeftUnnamed() {
        assertNull(classifier.name(listOf(rectangle(0.0, 0.0, 3.0, 3.0))))
    }
}
