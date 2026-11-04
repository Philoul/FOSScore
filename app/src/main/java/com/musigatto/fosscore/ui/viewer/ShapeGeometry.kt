package com.musigatto.fosscore.ui.viewer

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

/**
 * The Shapes tool stores each shape as plain polyline points, exactly like free ink: the whole
 * annotation machinery (draw, select, move, erase, undo, backup) works on them with no new code.
 * [shapePoints] is the whole geometry, called on release with the drag's two ends.
 */
enum class ShapeKind(@param:StringRes val labelRes: Int) {
    LINE(R.string.shape_line),
    RECT(R.string.shape_rect),
    ELLIPSE(R.string.shape_ellipse),
    ARROW(R.string.shape_arrow),
}

/** Fill is only for what has an inside: a line or an arrow has nothing to paint. */
val ShapeKind.canFill: Boolean get() = this == ShapeKind.RECT || this == ShapeKind.ELLIPSE

/** A closed ellipse polygon of so many segments: small enough to be smooth, big enough to stay. */
const val ELLIPSE_POINTS = 32

/** The arrow head's spread, and how the head's length follows the shape's width (wings ~3× it). */
private const val ARROW_WING_ANGLE = 0.5f
private const val ARROW_HEAD_PER_WIDTH = 3f

/** The head of an arrow drawn [widthPx] wide: the length the geometry wants, not one the UI picks. */
fun arrowHeadPx(widthPx: Float): Float = widthPx * ARROW_HEAD_PER_WIDTH

/** The thickness a new shape starts with (the panel saves its own default). */
const val DEFAULT_SHAPE_PX = 4f

/**
 * The polyline of one shape from the drag's [from] to its [to], in fit coordinates. Everything is
 * axis/direction aware: the arrow's head sits at [to] (the user's last touch) opening opposite to
 * the drag. [headPx] only feeds an arrow (pass 0 elsewhere); a head longer than the shaft clamps
 * to 3/4 of it so the wings never cross the start.
 */
fun shapePoints(kind: ShapeKind, from: Offset, to: Offset, headPx: Float): List<Offset> {
    val dir = to - from
    val len = dir.getDistance()
    if (len <= 0f) {
        // a tap, not a drag: nothing degenerate to draw (the gesture guards this, but the maths
        // must not NaN if a caller ever slips one through)
        return listOf(from)
    }
    if (kind == ShapeKind.LINE) return listOf(from, to)
    if (kind == ShapeKind.RECT) {
        val b1 = Offset(from.x, to.y)
        val b2 = Offset(to.x, from.y)
        return listOf(from, b1, to, b2, from)
    }
    if (kind == ShapeKind.ELLIPSE) {
        val cx = (from.x + to.x) / 2f
        val cy = (from.y + to.y) / 2f
        val rx = (to.x - from.x) / 2f
        val ry = (to.y - from.y) / 2f
        val step = 2.0 * PI / ELLIPSE_POINTS
        // start at the top and run clockwise, closing on the first point
        return (0..ELLIPSE_POINTS).map { i ->
            val t = -Math.PI / 2 + step * i
            Offset(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat())
        }
    }
    // ARROW: shaft start → tip, then the two wings walk from and back to the tip, so one
    // polyline draws the whole thing (shaft, wing, shaft-tip, wing) in a single stroke
    val head = min(headPx, len * 0.75f)
    val axis = dir / len
    val side = Offset(-axis.y, axis.x)
    val back = -axis * head * cos(ARROW_WING_ANGLE)
    val wingLen = head * sin(ARROW_WING_ANGLE)
    val w1 = to + back - side * wingLen
    val w2 = to + back + side * wingLen
    return listOf(from, to, w1, to, w2)
}
