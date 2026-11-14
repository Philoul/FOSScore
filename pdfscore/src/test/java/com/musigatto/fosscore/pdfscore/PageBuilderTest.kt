package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageBuilderTest {
    /** A cubic curve is cut in pieces that stay on it: a quarter circle's points stay at its radius. */
    @Test
    fun aCurveIsFlattenedOnTheCurve() {
        val k = 0.5523 // the control distance of a cubic quarter circle
        val path = PathFlattener()
        path.moveTo(10.0, 0.0)
        path.curveTo(10.0, 10.0 * k, 10.0 * k, 10.0, 0.0, 10.0)
        val points = path.subpaths().single()
        assertTrue(path.curved)
        assertTrue(points.size > 4)
        assertEquals(Point(0.0, 10.0), points.last())
        points.forEach { assertTrue(abs(kotlin.math.hypot(it.x, it.y) - 10.0) < 0.05) }
    }

    /** A closed subpath ends where it started; a new move starts another subpath. */
    @Test
    fun subpathsCloseAndSplitAtMoves() {
        val path = PathFlattener()
        path.moveTo(0.0, 0.0)
        path.lineTo(10.0, 0.0)
        path.lineTo(10.0, 1.0)
        path.closePath()
        path.moveTo(20.0, 0.0)
        path.lineTo(20.0, 30.0)
        assertEquals(
            listOf(
                listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 1.0), Point(0.0, 0.0)),
                listOf(Point(20.0, 0.0), Point(20.0, 30.0)),
            ),
            path.subpaths(),
        )
    }

    /** Characters following each other on a line make one run of text, a space where the gap is wide. */
    @Test
    fun charactersOnALineMakeOneText() {
        val page = PageBuilder(600.0, 800.0)
        page.text("L", "Serif", 100.0, 50.0, 10.0, 6.0)
        page.text("u", "Serif", 106.0, 50.0, 10.0, 5.0)
        page.text("c", "Serif", 113.0, 50.0, 10.0, 4.0)
        page.text("x", "Serif", 300.0, 50.0, 10.0, 5.0)
        assertEquals(listOf("Lu c", "x"), page.build().texts.map { it.text })
    }

    /** Named by its SMuFL code point alone: a symbol recognition looks for, else nothing. */
    @Test
    fun aSmuflCodePointNamesACommonSymbol() {
        val smufl = mapOf(0xE0A4 to "noteheadBlack", 0xE680 to "harpPedalRaised")
        assertEquals("noteheadBlack", SmuflCodes.name(smufl, 0xE0A4))
        assertNull(SmuflCodes.name(smufl, 0xE680))
        assertNull(SmuflCodes.name(smufl, null))
    }
}
