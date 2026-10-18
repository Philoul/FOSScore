package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import kotlin.math.max
import kotlin.math.min

// snapshot of a page for undo: stamps + strokes
data class PageEdit(val stamps: List<Stamp>, val strokes: List<Stroke>)

// minimum distance in px between points of a stroke (downsample)
const val MIN_SAMPLE_DIST_PX = 2f

// stroke width as a fraction of the page height (0.002..0.02)
const val MIN_STROKE_WIDTH = 0.002f
const val MAX_STROKE_WIDTH = 0.02f

// the user picks the width in screen px at zoom 1 (fit height); the fraction is derived when the
// stroke is created. ponytail: on small pages the real cap is MAX_STROKE_WIDTH (2% of the height),
// so the last px of the slider do not grow; raise that constant if it bothers.
const val DEFAULT_PEN_PX = 8f
const val MIN_PEN_PX = 1f
const val MAX_PEN_PX = 20f

fun penWidthPxToFrac(px: Float, fit: RectPx): Float =
    (px / max(1f, fit.height)).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)

// eraser radius as a fraction of the page height (px = frac * fit.height)
const val ERASE_RADIUS_FRAC = 0.02f

// Writing without fully lifting: if the next stroke starts within STROKE_MERGE_MS and near the
// end of the previous one, it is joined to it (one word = one stroke, even if the stylus lifts).
// ponytail: the points are joined with a straight segment (the gap is a few ms). 400ms separates
// words but joins letters; lower to ~150ms to require continuous writing. There is a Log.d
// ("ink: merge=...") to see the real gap/dist on the device and tune by hand.
const val STROKE_MERGE_MS = 400L
const val STROKE_MERGE_DIST_FRAC = 0.04f

// last stroke just written, so the next one can be joined to it
class InkTail {
    var last: Stroke? = null
    var at: Long = 0L
}

// does the new stroke continue the previous one? same style, short pause and near its end
fun shouldMergeStroke(
    prevEndPx: Offset,
    newStartPx: Offset,
    fit: RectPx,
    gapMs: Long,
    sameStyle: Boolean
): Boolean = sameStyle && gapMs <= STROKE_MERGE_MS &&
    (newStartPx - prevEndPx).getDistance() <= STROKE_MERGE_DIST_FRAC * fit.height

// drops consecutive points closer than minDist to the previous one
fun samplePoints(pts: List<Offset>, minDist: Float): List<Offset> {
    if (pts.size < 2) return pts
    val out = ArrayList<Offset>(pts.size)
    var last = pts.first()
    out.add(last)
    for (p in pts.drop(1)) {
        if ((p - last).getDistance() >= minDist) {
            out.add(p)
            last = p
        }
    }
    return out
}

fun encodePoints(points: List<Offset>): String =
    points.joinToString("|") { "${it.x};${it.y}" }

fun decodePoints(s: String): List<Offset> {
    val out = ArrayList<Offset>()
    for (raw in s.split('|')) {
        val parts = raw.split(';')
        if (parts.size == 2) {
            val x = parts[0].toFloatOrNull()
            val y = parts[1].toFloatOrNull()
            if (x != null && y != null) out.add(Offset(x, y))
        }
    }
    return out
}

fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val len2 = abx * abx + aby * aby
    if (len2 == 0f) return (p - a).getDistance()
    var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2
    t = t.coerceIn(0f, 1f)
    return (p - Offset(a.x + abx * t, a.y + aby * t)).getDistance()
}

fun minDistanceToPolyline(p: Offset, poly: List<Offset>): Float {
    if (poly.isEmpty()) return Float.MAX_VALUE
    if (poly.size == 1) return (p - poly[0]).getDistance()
    var best = Float.MAX_VALUE
    for (i in 0 until poly.size - 1) {
        best = min(best, distanceToSegment(p, poly[i], poly[i + 1]))
    }
    return best
}

// removes from a stroke the points within < radius of the eraser path and returns the surviving
// segments (separated ends become distinct strokes); empty = erased entirely.
// ponytail: the eraser is always partial (you brush over what you want removed). To remove a
// whole word: tap it (selects it) then 🗑. Upgrade if ever needed: an "erase whole stroke" mode
// as an eraser setting; it wasn't requested and 🗑 already covers it.
fun eraseStrokePoints(pts: List<Offset>, eraser: List<Offset>, radius: Float): List<List<Offset>> {
    if (eraser.isEmpty() || pts.isEmpty()) return listOf(pts)
    val keep = pts.map { minDistanceToPolyline(it, eraser) >= radius }
    if (keep.none { it }) return emptyList()
    val runs = mutableListOf<MutableList<Offset>>()
    var cur = mutableListOf<Offset>()
    // ponytail: a gap > 2*radius between survivors splits segments; fails if a stroke has
    // naturally widely spaced samples. Upgrade: decide by proximity to the eraser path.
    val splitGap = radius * 2f
    for (i in pts.indices) {
        if (!keep[i]) {
            if (cur.isNotEmpty()) { runs.add(cur); cur = mutableListOf() }
            continue
        }
        if (cur.isNotEmpty() && (pts[i] - cur.last()).getDistance() > splitGap) {
            runs.add(cur); cur = mutableListOf()
        }
        cur.add(pts[i])
    }
    if (cur.isNotEmpty()) runs.add(cur)
    return runs
}

// box enclosing the points (px) — a stroke's selection rectangle
fun strokeBounds(pts: List<Offset>): RectPx {
    if (pts.isEmpty()) return RectPx(0f, 0f, 0f, 0f)
    var l = Float.MAX_VALUE
    var t = Float.MAX_VALUE
    var r = -Float.MAX_VALUE
    var b = -Float.MAX_VALUE
    for (p in pts) {
        if (p.x < l) l = p.x
        if (p.y < t) t = p.y
        if (p.x > r) r = p.x
        if (p.y > b) b = p.y
    }
    return RectPx(l, t, r - l, b - t)
}

fun scalePoints(pts: List<Offset>, center: Offset, factor: Float): List<Offset> =
    pts.map { Offset(center.x + (it.x - center.x) * factor, center.y + (it.y - center.y) * factor) }

// stroke under the finger (tolerance = the handle or the width, whichever is larger)
fun hitStroke(pos: Offset, strokes: List<Stroke>, fit: RectPx): Stroke? {
    if (fit.width <= 0f) return null
    return strokes.lastOrNull { st ->
        val tol = max(handleRadiusPx(fit), st.width * fit.height)
        minDistanceToPolyline(pos, decodePoints(st.points).map { normalizedToPx(it, fit) }) <= tol
    }
}