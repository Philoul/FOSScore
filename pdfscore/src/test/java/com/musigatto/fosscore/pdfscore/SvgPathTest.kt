package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SvgPathTest {
    @Test
    fun absoluteAndRelativeLines() {
        val polygons = SvgPath.parse("M0 0L10 0l0 10h-10z M20 20 h5 v5 H20Z").orEmpty()
        assertEquals(2, polygons.size)
        assertEquals(listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0)), polygons[0])
        assertEquals(listOf(Point(20.0, 20.0), Point(25.0, 20.0), Point(25.0, 25.0), Point(20.0, 25.0)), polygons[1])
    }

    /** Fonts write numbers packed: "-97 -125c-54 0-97 31", ".5", no space before a minus. */
    @Test
    fun packedNumbersAndImplicitRepeats() {
        val polygons = SvgPath.parse("M1-1l2 0 0 2-2 0z").orEmpty()
        assertEquals(listOf(Point(1.0, -1.0), Point(3.0, -1.0), Point(3.0, 1.0), Point(1.0, 1.0)), polygons.single())
        assertEquals(Point(0.5, 0.25), SvgPath.parse("M.5 .25l1 0 0 1z").orEmpty().single().first())
    }

    @Test
    fun curvesEndOnTheirLastPoint() {
        val polygon = SvgPath.parse("M0 0c0 10 10 10 10 0s10 -10 10 0z").orEmpty().single()
        assertEquals(Point(10.0, 0.0), polygon[8])
        assertEquals(Point(20.0, 0.0), polygon.last())
        // the smooth curve mirrors the first one's last control point: it dips under the line
        assertEquals(true, polygon.subList(9, polygon.size).any { it.y < 0 })
    }

    @Test
    fun arcsAreNotRead() {
        assertNull(SvgPath.parse("M0 0 a5 5 0 0 1 10 0z"))
    }
}
