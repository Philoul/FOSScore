package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShapesTest {
    @Test
    fun aThinRectangleIsAHorizontalLine() {
        val shape = DrawnShape(listOf(Point(10.0, 100.0), Point(110.0, 100.0), Point(110.0, 100.6), Point(10.0, 100.6)), false)
        assertEquals(DrawnLine(10.0, 100.3, 110.0, 100.3, 0.6), shape.asLine()?.let { it.copy(y1 = round(it.y1), y2 = round(it.y2), width = round(it.width)) })
    }

    /** "Print To PDF" closes its rectangles back on their first point, and closing adds it once more. */
    @Test
    fun aRectangleClosedOnItsFirstPointIsALine() {
        val corners = listOf(Point(10.0, 100.0), Point(110.0, 100.0), Point(110.0, 100.6), Point(10.0, 100.6))
        val shape = DrawnShape(corners + corners[0] + corners[0], false)
        assertEquals(100.0, shape.asLine()?.length ?: 0.0, 1e-9)
    }

    @Test
    fun aSquareOrACurveIsNoLine() {
        val square = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 5.0), Point(0.0, 5.0))
        assertNull(DrawnShape(square, false).asLine())
        assertNull(DrawnShape(listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 0.5), Point(0.0, 0.5)), true).asLine())
    }

    private fun round(v: Double) = Math.round(v * 1000) / 1000.0
}
