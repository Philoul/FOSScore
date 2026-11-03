package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A shape is born at release as plain polyline points ("x,y|x,y", like any other stroke), so the
 * whole ink machinery draws, selects, moves, erases and exports it with no new code. These are the
 * generation rules the Shapes tool lives on.
 */
class ShapeGeometryTest {
    private val a = Offset(10f, 50f)
    private val b = Offset(110f, 50f)

    @Test
    fun lineIsTheTwoEndPoints() {
        val pts = shapePoints(ShapeKind.LINE, a, b, headPx = 12f)
        assertEquals(listOf(a, b), pts)
    }

    @Test
    fun rectWalksTheCornersAndCloses() {
        val pts = shapePoints(ShapeKind.RECT, Offset(20f, 30f), Offset(80f, 60f), headPx = 0f)
        assertEquals(5, pts.size)
        // closed: the last point walks back to the first
        assertEquals(pts.first(), pts.last())
        val xs = pts.map { it.x }.distinct().sorted()
        val ys = pts.map { it.y }.distinct().sorted()
        // the corners only sit on the two edges, whatever way the drag was read
        assertEquals(listOf(20f, 80f), xs)
        assertEquals(listOf(30f, 60f), ys)
    }

    @Test
    fun rectWorksFromAnyCornerOfTheDrag() {
        // the drag that started at the bottom-right corner draws the same box
        val flip = shapePoints(ShapeKind.RECT, Offset(80f, 60f), Offset(20f, 30f), headPx = 0f)
        val straight = shapePoints(ShapeKind.RECT, Offset(20f, 30f), Offset(80f, 60f), headPx = 0f)
        assertEquals(straight.map { it.x }.distinct().sorted(), flip.map { it.x }.distinct().sorted())
        assertEquals(straight.map { it.y }.distinct().sorted(), flip.map { it.y }.distinct().sorted())
    }

    @Test
    fun ellipseIsClosedAndStaysInItsBox() {
        val from = Offset(20f, 30f)
        val to = Offset(120f, 90f)
        val pts = shapePoints(ShapeKind.ELLIPSE, from, to, headPx = 0f)
        // closed, fixed sampling so the stored size stays known
        assertEquals(ELLIPSE_POINTS + 1, pts.size)
        assertEquals(pts.first(), pts.last())
        val left = minOf(from.x, to.x)
        val right = maxOf(from.x, to.x)
        val top = minOf(from.y, to.y)
        val bottom = maxOf(from.y, to.y)
        pts.forEach {
            assertTrue("x $it", it.x >= left - 1e-3f && it.x <= right + 1e-3f)
            assertTrue("y $it", it.y >= top - 1e-3f && it.y <= bottom + 1e-3f)
        }
        // and it reaches its extremes: it is an ellipse, not a small circle inside
        assertTrue(pts.any { it.y <= top + 1e-3f })
        assertTrue(pts.any { it.y >= bottom - 1e-3f })
        assertTrue(pts.any { it.x <= left + 1e-3f })
        assertTrue(pts.any { it.x >= right - 1e-3f })
    }

    @Test
    fun arrowRetracesTheTipWithBothWings() {
        // pointing right: the shaft, then tip → upper wing → tip → lower wing, the direction the
        // drag ran decides (the user's last touch is where the head sits)
        val pts = shapePoints(ShapeKind.ARROW, a, b, headPx = 12f)
        assertEquals(a, pts.first())
        assertEquals(b, pts[1])
        assertEquals("the tip is walked back to twice", b, pts[3])
        val wing1 = pts[2]
        val wing2 = pts[4]
        assertEquals("wings open symmetrically across the shaft", wing1.y, -wing2.y + 2 * b.y, 1e-3f)
        // the wings trail behind the tip, not in front of it
        assertTrue(wing1.x < b.x && wing2.x < b.x)
        assertTrue("wing length", (wing1 - b).getDistance() > 0f)
    }

    @Test
    fun arrowPointsWhereTheDragRan() {
        // straight down: the wings now sit left and right of the tip
        val top = Offset(50f, 10f)
        val bottom = Offset(50f, 110f)
        val pts = shapePoints(ShapeKind.ARROW, top, bottom, headPx = 12f)
        assertEquals(bottom, pts[1])
        val wing1 = pts[2]
        val wing2 = pts[4]
        assertEquals(wing1.x, -wing2.x + 2 * bottom.x, 1e-3f)
        assertTrue("wings trail behind the tip", wing1.y < bottom.y && wing2.y < bottom.y)
    }

    @Test
    fun arrowHeadNeverSwallowsTheShaft() {
        // a stubby shaft with a big head: the head clamps so the wings never cross the start
        val from = Offset(100f, 100f)
        val to = Offset(104f, 100f)
        val pts = shapePoints(ShapeKind.ARROW, from, to, headPx = 50f)
        assertTrue(pts[2].x > from.x - 1e-3f)
        assertTrue(pts[4].x > from.x - 1e-3f)
        // both wings open the sameDistance to either side of the shaft
        assertEquals((pts[2].y - from.y), -(pts[4].y - from.y), 1e-3f)
    }

    @Test
    fun degenerateDragMakesNoNonsense() {
        // the gesture guards a tap from reaching here, but the maths must not NaN either way
        listOf(ShapeKind.LINE, ShapeKind.RECT, ShapeKind.ELLIPSE, ShapeKind.ARROW).forEach { kind ->
            val pts = shapePoints(kind, a, a, headPx = 10f)
            assertTrue(pts.all { p -> !p.x.isNaN() && !p.y.isNaN() })
        }
    }
}
