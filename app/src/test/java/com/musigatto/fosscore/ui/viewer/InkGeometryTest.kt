package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test
class InkGeometryTest {
    @Test
    fun encodeDecodeRoundtripPreservesPoints() {
        val pts = listOf(Offset(0.1f, 0.2f), Offset(0.3f, 1f), Offset(1f, 0f))
        assertEquals(pts, decodePoints(encodePoints(pts)))
    }

    @Test
    fun samplePointsDropsConsecutiveClosePoints() {
        val pts = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1.5f, 0f), Offset(10f, 0f))
        val out = samplePoints(pts, 2f)
        // measured against the LAST kept point: (1,0) and (1.5,0) are <2 from (0,0), so only
        // (10,0) gets in. The original expectation (keeping 1.5) contradicted the implementation
        // and always failed (definition in InkGeometry: minDist from the previous one).
        assertEquals(listOf(Offset(0f, 0f), Offset(10f, 0f)), out)
    }

    @Test
    fun distanceToSegmentTakesProjectionOnMiddle() {
        assertEquals(1f, distanceToSegment(Offset(5f, 1f), Offset(0f, 0f), Offset(10f, 0f)), 1e-6f)
        assertEquals(5f, distanceToSegment(Offset(-5f, 0f), Offset(0f, 0f), Offset(10f, 0f)), 1e-6f)
    }

    @Test
    fun eraseMiddleOfStrokeSplitsIntoTwoRuns() {
        val pts = (0..10).map { Offset(it.toFloat(), 0f) }
        val runs = eraseStrokePoints(pts, listOf(Offset(4.5f, 0f), Offset(5.5f, 0f)), 1.2f)
        assertEquals(2, runs.size)
        assertEquals(listOf(4, 4), runs.map { it.size })
    }

    @Test
    fun eraseWholeStrokeReturnsEmpty() {
        val pts = (0..4).map { Offset(it.toFloat(), 0f) }
        assertEquals(emptyList<List<Offset>>(), eraseStrokePoints(pts, listOf(Offset(2f, 0f)), 10f))
    }

    @Test
    fun scalePointsGrowsAroundCenter() {
        val pts = listOf(Offset(0f, 0f), Offset(10f, 0f))
        assertEquals(
            listOf(Offset(-5f, 0f), Offset(15f, 0f)),
            scalePoints(pts, Offset(5f, 0f), 2f)
        )
    }

    @Test
    fun briefPenLiftContinuesTheSameWord() {
        val fit = RectPx(0f, 0f, 1000f, 1000f)
        // the stylus lifts for 80 ms and continues at the same spot: same stroke
        assertEquals(
            true,
            shouldMergeStroke(Offset(500f, 500f), Offset(505f, 502f), fit, 80L, sameStyle = true)
        )
    }

    @Test
    fun longPauseOrFarJumpStartsANewStroke() {
        val fit = RectPx(0f, 0f, 1000f, 1000f)
        // long pause: new stroke
        assertEquals(
            false,
            shouldMergeStroke(Offset(500f, 500f), Offset(505f, 502f), fit, 900L, sameStyle = true)
        )
        // short pause but somewhere else: new stroke
        assertEquals(
            false,
            shouldMergeStroke(Offset(500f, 500f), Offset(700f, 500f), fit, 80L, sameStyle = true)
        )
        // different width or colour: new stroke even if the finger doesn't move
        assertEquals(
            false,
            shouldMergeStroke(Offset(500f, 500f), Offset(500f, 500f), fit, 10L, sameStyle = false)
        )
    }
}