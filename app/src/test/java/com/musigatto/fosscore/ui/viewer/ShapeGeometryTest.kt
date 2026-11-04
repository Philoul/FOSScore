package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stroke
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
        ShapeKind.entries.forEach { kind ->
            val pts = shapePoints(kind, a, a, headPx = 10f)
            assertTrue(pts.all { p -> !p.x.isNaN() && !p.y.isNaN() })
        }
    }

    @Test
    fun theStaffIsFiveLinesOfItsBox() {
        val pts = shapePoints(ShapeKind.STAFF, Offset(20f, 100f), Offset(220f, 180f), headPx = 0f)
        assertEquals(STAFF_LINES * 2, pts.size)
        // one line per pair: same height, edge to edge
        (0 until STAFF_LINES).forEach { i ->
            val left = pts[i * 2]
            val right = pts[i * 2 + 1]
            assertEquals(20f, left.x, 1e-3f)
            assertEquals(220f, right.x, 1e-3f)
            assertEquals(left.y, right.y, 1e-3f)
        }
        // evenly spread from the top of the drag to the bottom: that is how it sits on the printed one
        val ys = (0 until STAFF_LINES).map { pts[it * 2].y }
        assertEquals(listOf(100f, 120f, 140f, 160f, 180f), ys)
    }

    @Test
    fun theStaffReadsTheSameFromEitherCorner() {
        // the drag that started at the bottom-right draws the same staff
        val flip = shapePoints(ShapeKind.STAFF, Offset(220f, 180f), Offset(20f, 100f), headPx = 0f)
        val straight = shapePoints(ShapeKind.STAFF, Offset(20f, 100f), Offset(220f, 180f), headPx = 0f)
        assertEquals(straight, flip)
    }

    @Test
    fun aHairpinFillsItsDragBoxShutOnOneSideOpenOnTheOther() {
        // left-to-right = crescendo: shut on left, open on right
        val from = Offset(100f, 100f)
        val to = Offset(300f, 150f)
        val pts = shapePoints(ShapeKind.CRESCENDO, from, to, headPx = 0f)
        assertEquals(4, pts.size) // closed box: shut corner, open top, open bottom, shut corner (closed)
        assertEquals(Offset(100f, 100f), pts[0]) // shut at from
        assertEquals(Offset(300f, 100f), pts[1]) // open top
        assertEquals(Offset(300f, 150f), pts[2]) // open bottom
        assertEquals(Offset(100f, 150f), pts[3]) // shut bottom
        // right-to-left = decrescendo: shut on right, open on left
        val rev = shapePoints(ShapeKind.CRESCENDO, to, from, headPx = 0f)
        assertEquals(Offset(300f, 100f), rev[0]) // shut at from (now top-right)
        assertEquals(Offset(100f, 100f), rev[1]) // open top
        assertEquals(Offset(100f, 150f), rev[2]) // open bottom
        assertEquals(Offset(300f, 150f), rev[3]) // shut bottom
    }

    @Test
    fun theTupletBracketHasATickAtEachEnd() {
        val from = Offset(100f, 100f)
        val to = Offset(300f, 100f)
        val pts = shapePoints(ShapeKind.TUPLET, from, to, headPx = 0f)
        assertEquals(4, pts.size)
        assertEquals(to, pts[2])
        // both ticks point the same way, away from the line over the notes
        assertTrue(pts[0].y < from.y)
        assertTrue(pts[3].y < to.y)
    }

    @Test
    fun aSlurBowsOffTheLineAndATieLessSo() {
        val from = Offset(100f, 100f)
        val to = Offset(300f, 100f)
        val slur = shapePoints(ShapeKind.SLUR, from, to, headPx = 0f)
        val tie = shapePoints(ShapeKind.TIE, from, to, headPx = 0f)
        // starts and ends where the finger did
        assertEquals(from, slur.first())
        assertEquals(to, slur.last())
        assertEquals(from, tie.first())
        assertEquals(to, tie.last())
        // bows up on a left-to-right drag, a slur deeper than a tie, and never below the line
        val slurSag = from.y - slur.minOf { it.y }
        val tieSag = from.y - tie.minOf { it.y }
        assertTrue("slur $slurSag, tie $tieSag", slurSag > tieSag)
        assertTrue(tieSag > 0f)
        assertTrue(slur.all { it.y <= from.y + 1e-3f })
        // smooth: sampled, not a hand of straight pieces
        slur.zipWithNext().forEach { (a0, b0) -> assertTrue((b0 - a0).getDistance() < 20f) }
    }

    @Test
    fun theRowKindTellsAStaffAndASolidApart() {
        assertEquals(Stroke.SHAPE_STAFF, strokeKindOf(ShapeKind.STAFF, filled = true))
        assertEquals(Stroke.SHAPE_FILLED, strokeKindOf(ShapeKind.RECT, filled = true))
        assertEquals(Stroke.SHAPE, strokeKindOf(ShapeKind.RECT, filled = false))
        // a line has no inside: "filled" cannot turn it into a solid
        assertEquals(Stroke.SHAPE, strokeKindOf(ShapeKind.LINE, filled = true))
    }
}
