package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskTest {
    private fun square(left: Double, top: Double, side: Double) = listOf(
        Point(left, top), Point(left + side, top), Point(left + side, top + side), Point(left, top + side),
    )

    @Test
    fun fillsASquare() {
        val mask = Mask.rasterize(listOf(square(0.0, 0.0, 4.0)), Box(0.0, 0.0, 8.0, 8.0), 1.0)
        assertEquals(16, mask.count)
        assertEquals(true, mask[3, 3])
        assertEquals(false, mask[4, 4])
    }

    @Test
    fun aHoleTurningTheOtherWayStaysEmpty() {
        val outer = square(0.0, 0.0, 6.0)
        val hole = square(2.0, 2.0, 2.0).reversed()
        val mask = Mask.rasterize(listOf(outer, hole), Box(0.0, 0.0, 6.0, 6.0), 1.0)
        assertEquals(32, mask.count)
        assertEquals(false, mask[2, 2])
    }

    @Test
    fun iouOfHalfOverlappingSquares() {
        val frame = Box(0.0, 0.0, 8.0, 4.0)
        val a = Mask.rasterize(listOf(square(0.0, 0.0, 4.0)), frame, 1.0)
        val b = Mask.rasterize(listOf(square(2.0, 0.0, 4.0)), frame, 1.0)
        assertEquals(8.0 / 24.0, a.iou(b), 1e-9)
        assertEquals(1.0, a.iou(a), 1e-9)
    }
}
