package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Onset
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.musicxml.Playback
import kotlin.math.max

/**
 * Where the playback bar is on a page: [x], and the [top] and [bottom] lines of the line of music
 * it crosses, with its staff [space]. SVG units.
 */
class BarSpot(
    val x: Float,
    val top: Float,
    val bottom: Float,
    val space: Float,
    internal val lineEnd: Float = x,
    internal val measureEnd: Float = lineEnd,
)

/**
 * The way the playback bar goes over one page laid out as [geometry], built on the measures
 * (maintainer, 2026-11-20: no going back and forth, anywhere).
 *
 * Each measure played ([bars]: where they start, in quarters, in the order played) is crossed in
 * its own time, from where its music starts to its right barline, or on to the first note of the
 * next measure when that one is drawn right after it on the same line. The time a measure lasts
 * comes from the bars, so its time signature and the tempo are followed. Within the measure the
 * bar goes through the notes of the [onsets], for its speed to follow the music, but only through
 * those of that measure, drawn within it, and in an order that goes right: a note read in the
 * wrong measure, or drawn out of order, is left out. The bar only goes back where the music does:
 * a repeat, a jump, a new line, another page. A measure with no note on the page (rests) is
 * crossed all the same: a measure of a MusicXML score is drawn with its barlines, and one of a PDF
 * read takes the room left between its neighbours.
 */
class BarPath(private val onsets: List<Onset>, bars: List<Double>, private val geometry: PageGeometry) {
    constructor(playback: Playback, geometry: PageGeometry) : this(playback.onsets, playback.bars, geometry)

    /** The page's width in SVG units. */
    val width: Float get() = geometry.width

    // a measure as drawn on the page: its line, its left barline, where its music starts (its
    // first notehead), its right barline
    private class Drawn(
        val top: Float,
        val bottom: Float,
        val space: Float,
        val left: Float,
        val start: Float,
        val end: Float,
        val lineEnd: Float,
    )

    // the bar's way over one measure played: its measure, and the times and places it goes through
    private class Way(val drawn: Drawn, val t: DoubleArray, val x: FloatArray)

    // the measure a note is in: where the page draws it, else what its id says
    private fun rankOf(id: String): Int? = geometry.measureRank(id) ?: measureKey(id)?.toIntOrNull()

    private val drawn: Map<Int, Drawn> = drawnMeasures()

    // the measures in the order played: when each starts, and which one it is (its rank, or -1)
    private val starts: DoubleArray = bars.ifEmpty { keyChanges() }.toDoubleArray()
    private val ends = DoubleArray(starts.size) { k -> starts.getOrNull(k + 1) ?: lastEnd(k) }
    private val ranks: IntArray = playedRanks()
    private val ways: Array<Way?> = Array(starts.size) { k -> way(k) }

    /** Where the bar is with the music at [quarters]; null when the music is not on this page. */
    fun at(quarters: Double): BarSpot? {
        if (starts.isEmpty()) return null
        val k = lastAtOrBefore(starts, quarters).coerceAtLeast(0)
        val way = ways[k] ?: return null
        val j = lastAtOrBefore(way.t, quarters)
        val x = when {
            j < 0 -> way.x.first()
            j >= way.t.size - 1 -> way.x.last()
            else -> {
                val span = way.t[j + 1] - way.t[j]
                val f = if (span > 0) ((quarters - way.t[j]) / span).toFloat().coerceIn(0f, 1f) else 0f
                way.x[j] + (way.x[j + 1] - way.x[j]) * f
            }
        }
        val d = way.drawn
        return BarSpot(x, d.top, d.bottom, d.space, d.lineEnd, d.end)
    }

    // the measures of the page: from Verovio's measures for a MusicXML score, from the notes for a
    // PDF read (whose geometry has no measures)
    private fun drawnMeasures(): Map<Int, Drawn> {
        val fromSvg = geometry.measures.mapNotNull { m ->
            val rank = m.rank ?: return@mapNotNull null
            val first = m.staves.firstOrNull() ?: return@mapNotNull null
            rank to Drawn(
                top = m.staves.minOf { it.top },
                bottom = m.staves.maxOf { it.bottom },
                space = first.space,
                left = m.staves.minOf { it.left },
                start = m.staves.minOf { it.start },
                end = m.staves.maxOf { it.right },
                lineEnd = m.staves.maxOf { it.right },
            )
        }.toMap()
        if (fromSvg.isNotEmpty()) return fromSvg

        val heads = geometry.noteIds.mapNotNull { id ->
            val rank = rankOf(id) ?: return@mapNotNull null
            geometry.head(id)?.let { rank to it }
        }.groupBy({ it.first }, { it.second })
        val known = heads.mapValues { (_, hs) ->
            val h = hs.first()
            Drawn(h.top, h.bottom, h.space, hs.minOf { it.x }, hs.minOf { it.x }, hs.maxOf { it.measureEnd }, h.lineEnd)
        }
        // the left barline of a measure: the right one of the measure before it on its line
        val measures = known.mapValues { (_, d) ->
            val before = known.values.filter { it.top == d.top && it.end <= d.start + d.space / 2 }.maxOfOrNull { it.end }
            before?.let { Drawn(d.top, d.bottom, d.space, it, d.start, d.end, d.lineEnd) } ?: d
        }.toMutableMap()
        // measures with no note between two with notes on one line share the room left between them
        val ranks = measures.keys.sorted()
        ranks.zipWithNext().forEach { (a, b) ->
            val gap = b - a - 1
            val da = measures[a] ?: return@forEach
            val db = measures[b] ?: return@forEach
            if (gap <= 0) return@forEach
            val (from, to) = when {
                da.top == db.top -> da.end to db.start - EMPTY_LEAD_SPACES * db.space
                da.lineEnd > da.end + gap * da.space -> da.end to da.lineEnd
                else -> return@forEach
            }
            if (to <= from) return@forEach
            val w = (to - from) / gap
            for (i in 0 until gap) {
                val left = from + i * w
                measures[a + 1 + i] = Drawn(da.top, da.bottom, da.space, left, left, left + w, da.lineEnd)
            }
            if (da.top == db.top) measures[b] = Drawn(db.top, db.bottom, db.space, to, db.start, db.end, db.lineEnd)
        }
        return measures
    }

    // where the measures start when the bars are not known: where the measure of the notes changes
    private fun keyChanges(): List<Double> {
        var last: Int? = null
        return onsets.mapNotNull { o ->
            val key = o.notes.firstNotNullOfOrNull { rankOf(it) } ?: return@mapNotNull null
            o.quarters.takeIf { key != last }.also { last = key }
        }
    }

    // the end of the last measure played: as long as the one before it, and past its last note
    private fun lastEnd(k: Int): Double {
        val start = starts[k]
        val length = if (k > 0) start - starts[k - 1] else DEFAULT_MEASURE
        val lastNote = onsets.lastOrNull()?.quarters?.takeIf { it >= start }?.let { it - start + 1.0 } ?: 0.0
        return start + max(length, lastNote)
    }

    // the measure each measure played is: the one most of its notes name; a measure with no note
    // takes its place between its neighbours, when they are in order
    private fun playedRanks(): IntArray {
        val ranks = IntArray(starts.size) { k ->
            onsetsIn(k).flatMap { onsets[it].notes }.mapNotNull { rankOf(it) }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: -1
        }
        var k = 0
        while (k < ranks.size) {
            if (ranks[k] >= 0) {
                k++
                continue
            }
            var e = k
            while (e < ranks.size && ranks[e] < 0) e++
            val before = ranks.getOrNull(k - 1)
            val after = ranks.getOrNull(e)
            for (i in k until e) {
                ranks[i] = when {
                    before != null && (after == null || after - before == e - k + 1) -> before + (i - k + 1)
                    before == null && after != null -> after - (e - i)
                    else -> -1
                }
            }
            k = e
        }
        return ranks
    }

    // the indices of the onsets of measure played [k]
    private fun onsetsIn(k: Int): IntRange {
        val from = firstAtOrAfter(starts[k])
        val to = firstAtOrAfter(ends[k]) - 1
        return from..to
    }

    private fun firstAtOrAfter(quarters: Double): Int {
        var lo = 0
        var hi = onsets.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (onsets[mid].quarters < quarters - EPSILON) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // the left of the notes of onset [i] that are of measure [rank] and drawn on this page
    private fun notesX(i: Int, rank: Int): Float? =
        onsets[i].notes.filter { rankOf(it) == rank }.mapNotNull { geometry.head(it)?.x }.minOrNull()

    // where the bar is when measure played [k] starts: on its first note when one starts with it,
    // else on its left barline
    private fun entry(k: Int, d: Drawn): Float {
        val downbeat = onsetsIn(k).firstOrNull()?.takeIf { onsets[it].quarters <= starts[k] + EPSILON }
            ?.let { notesX(it, ranks[k]) }
        return downbeat?.let { max(it, d.left) } ?: if (d.left < d.start) d.left else d.start
    }

    private fun way(k: Int): Way? {
        val rank = ranks[k]
        val d = drawn[rank] ?: return null
        val from = entry(k, d)
        // on to the first note of the next measure played when it is drawn right after, else to
        // the barline (a new line, a repeat back, a jump, the next measure not played)
        val next = k + 1
        val nextDrawn = drawn[ranks.getOrElse(next) { -1 }]
        val to = nextDrawn?.takeIf { ranks[next] == rank + 1 && it.top == d.top && starts[next] <= ends[k] + EPSILON }
            ?.let { entry(next, it) }?.takeIf { it >= from } ?: max(from, d.end)
        // the notes of the measure within it, in an order that goes right
        val inner = onsetsIn(k).filter { onsets[it].quarters > starts[k] + EPSILON }
            .mapNotNull { i -> notesX(i, rank)?.takeIf { it > from && it < to }?.let { onsets[i].quarters to it } }
        val kept = increasing(inner)
        val t = DoubleArray(kept.size + 2)
        val x = FloatArray(kept.size + 2)
        t[0] = starts[k]
        x[0] = from
        kept.forEachIndexed { i, (q, nx) ->
            t[i + 1] = q
            x[i + 1] = nx
        }
        t[t.size - 1] = ends[k]
        x[x.size - 1] = to
        return Way(d, t, x)
    }

    private companion object {
        const val EPSILON = 1e-6

        // a measure whose length is not known: four quarters
        const val DEFAULT_MEASURE = 4.0

        // how far left of the first note of a measure (in spaces) its barline is taken to be, after
        // measures of rests on a PDF read
        const val EMPTY_LEAD_SPACES = 1.5f

        // the index of the last value at or before [q] in the sorted [values], or -1
        fun lastAtOrBefore(values: DoubleArray, q: Double): Int {
            var lo = 0
            var hi = values.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (values[mid] <= q + EPSILON) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found
        }

        // the longest run of [points] (in time order) whose places go right
        fun increasing(points: List<Pair<Double, Float>>): List<Pair<Double, Float>> {
            if (points.size < 2) return points
            val length = IntArray(points.size) { 1 }
            val previous = IntArray(points.size) { -1 }
            for (i in points.indices) {
                for (j in 0 until i) {
                    if (points[j].second < points[i].second && length[j] + 1 > length[i]) {
                        length[i] = length[j] + 1
                        previous[i] = j
                    }
                }
            }
            var i = length.indices.maxByOrNull { length[it] } ?: return emptyList()
            val run = ArrayList<Pair<Double, Float>>()
            while (i >= 0) {
                run.add(points[i])
                i = previous[i]
            }
            return run.asReversed()
        }
    }
}
