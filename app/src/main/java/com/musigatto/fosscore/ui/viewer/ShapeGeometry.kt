package com.musigatto.fosscore.ui.viewer

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

/**
 * The Shapes tool stores each shape as plain polyline points, exactly like free ink: the whole
 * annotation machinery (draw, select, move, erase, undo, backup) works on them with no new code.
 * [shapePoints] is the whole geometry, called on release with the drag's two ends.
 *
 * The musical ones come first (a musician's shapes), the geometric ones after: the panel lays them
 * out in rows of five, so the split falls between the two groups.
 */
enum class ShapeKind(@param:StringRes val labelRes: Int) {
    SLUR(R.string.shape_slur),
    TIE(R.string.shape_tie),
    CRESCENDO(R.string.shape_crescendo),
    TUPLET(R.string.shape_tuplet),
    STAFF(R.string.shape_staff),
    LINE(R.string.shape_line),
    RECT(R.string.shape_rect),
    ELLIPSE(R.string.shape_ellipse),
    ARROW(R.string.shape_arrow),
}

/** Fill is only for what has an inside: a line or an arrow has nothing to paint. */
val ShapeKind.canFill: Boolean get() = this == ShapeKind.RECT || this == ShapeKind.ELLIPSE

/** The kind the row is stored as: a staff carries several lines, a filled closure is a solid. */
fun strokeKindOf(kind: ShapeKind, filled: Boolean): Int = when {
    kind == ShapeKind.STAFF -> Stroke.SHAPE_STAFF
    filled && kind.canFill -> Stroke.SHAPE_FILLED
    else -> Stroke.SHAPE
}

/** A closed ellipse polygon of so many segments: small enough to be smooth, big enough to stay. */
const val ELLIPSE_POINTS = 32

/** A staff has five lines: the score's own, so a drawn one can sit on top of them. */
const val STAFF_LINES = 5

/** An arc sampled this many times: smooth at any size the page allows. */
private const val ARC_SEGMENTS = 24

/** A slur spans notes and bows deep; a tie joins two and barely lifts. */
private const val SLUR_SAG = 0.20f
private const val TIE_SAG = 0.07f

/** The tick at each end of a tuplet bracket, as a share of its length. */
private const val BRACKET_TICK = 0.14f
private const val TICK_MAX_PX = 28f

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
 * the drag, and so do a hairpin's mouth and a slur's bow (above a left-to-right drag).
 * [headPx] only feeds an arrow (pass 0 elsewhere); a head longer than the shaft clamps to 3/4 of it
 * so the wings never cross the start.
 */
fun shapePoints(kind: ShapeKind, from: Offset, to: Offset, headPx: Float): List<Offset> {
    val dir = to - from
    val len = dir.getDistance()
    if (len <= 0f) {
        // a tap, not a drag: nothing degenerate to draw (the gesture guards this, but the maths
        // must not NaN if a caller ever slips one through)
        return listOf(from)
    }
    val axis = dir / len
    // the drag's right hand: with a left-to-right drag it points down the page, so -side is up
    val side = Offset(-axis.y, axis.x)
    when (kind) {
        ShapeKind.LINE -> return listOf(from, to)
        ShapeKind.RECT -> {
            val b1 = Offset(from.x, to.y)
            val b2 = Offset(to.x, from.y)
            return listOf(from, b1, to, b2, from)
        }
        ShapeKind.ELLIPSE -> {
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
        ShapeKind.ARROW -> {
            // shaft start → tip, then the two wings walk from and back to the tip, so one
            // polyline draws the whole thing (shaft, wing, shaft-tip, wing) in a single stroke
            val head = min(headPx, len * 0.75f)
            val back = -axis * head * cos(ARROW_WING_ANGLE)
            val wingLen = head * sin(ARROW_WING_ANGLE)
            val w1 = to + back - side * wingLen
            val w2 = to + back + side * wingLen
            return listOf(from, to, w1, to, w2)
        }
        ShapeKind.SLUR, ShapeKind.TIE -> {
            val sag = len * if (kind == ShapeKind.SLUR) SLUR_SAG else TIE_SAG
            return arcPoints(from, to, (from + to) / 2f - side * sag)
        }
        ShapeKind.CRESCENDO -> {
            // hairpin: three points, open V. drag from shut end toward open end.
            // left→right = crescendo < (shut at from), right→left = decrescendo > (shut at from).
            val midY = (from.y + to.y) / 2f
            val h = (abs(from.y - to.y) / 2f).coerceAtLeast(1f)
            val shut = from
            val openTop = Offset(to.x, midY - h)
            val openBottom = Offset(to.x, midY + h)
            return listOf(shut, openTop, openBottom)
        }
        ShapeKind.TUPLET -> {
            // the bracket over the notes: a line with a tick down at each end
            val tick = (len * BRACKET_TICK).coerceAtMost(TICK_MAX_PX)
            return listOf(from - side * tick, from, to, to - side * tick)
        }
        ShapeKind.STAFF -> {
            // five lines in one stroke, as point pairs: the page builder reads each pair as its
            // own line, so the whole staff keeps one width (the panel's) and scales as a block
            val left = min(from.x, to.x)
            val right = max(from.x, to.x)
            val top = min(from.y, to.y)
            val bottom = max(from.y, to.y)
            return (0 until STAFF_LINES).flatMap { i ->
                val y = top + (bottom - top) * i / (STAFF_LINES - 1)
                listOf(Offset(left, y), Offset(right, y))
            }
        }
    }
}

/** A quadratic arc from [from] to [to] bent toward [control], sampled [ARC_SEGMENTS] times. */
private fun arcPoints(from: Offset, to: Offset, control: Offset): List<Offset> =
    (0..ARC_SEGMENTS).map { i ->
        val t = i / ARC_SEGMENTS.toFloat()
        val u = 1f - t
        from * (u * u) + control * (2f * u * t) + to * (t * t)
    }
