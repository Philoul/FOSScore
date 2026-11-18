package com.musigatto.fosscore.pdfscore.bitmap

/**
 * A vertical stroke of the line-free image (a stem, a barline): columns [x0]..[x1], rows [y0]..[y1]
 * (inclusive); [thin] when no wider than a stem.
 */
data class Segment(val x0: Int, val x1: Int, val y0: Int, val y1: Int, val thin: Boolean)

/** The vertical strokes of the line-free image (SPEC 7). */
object Segments {
    /**
     * Column runs at least rnd(2 D) long, joined with the run of the previous column they overlap
     * enough (5 x overlap >= 3 x the shorter); kept when at most max(2, rnd(0.75 D)) wide, thin when
     * at most max(2, rnd(0.35 D)); sorted by (x0, y0, x1, y1).
     */
    fun find(img: InkMask, space: Double): List<Segment> {
        val minLength = rnd(2.0 * space)
        val maxWidth = maxOf(2, rnd(0.75 * space))
        val thinWidth = maxOf(2, rnd(0.35 * space))
        // x0, x1, y0, y1
        var open = mutableListOf<IntArray>()
        val closed = mutableListOf<IntArray>()
        for (x in 0 until img.width) {
            var s = 0
            while (s < img.height) {
                if (!img[x, s]) {
                    s++
                    continue
                }
                var e = s
                while (e + 1 < img.height && img[x, e + 1]) e++
                val length = e - s + 1
                if (length >= minLength) {
                    val g = open.firstOrNull { g ->
                        g[1] == x - 1 && s <= g[3] && e >= g[2] && 5 * (minOf(e, g[3]) - maxOf(s, g[2])) >= 3 * minOf(length, g[3] - g[2] + 1)
                    }
                    g?.let {
                        it[1] = x
                        it[2] = minOf(it[2], s)
                        it[3] = maxOf(it[3], e)
                    } ?: open.add(intArrayOf(x, x, s, e))
                    val still = mutableListOf<IntArray>()
                    for (o in open) if (o[1] < x - 1) closed += o else still += o
                    open = still
                }
                s = e + 1
            }
        }
        closed += open
        return closed.filter { it[1] - it[0] + 1 <= maxWidth }
            .map { Segment(it[0], it[1], it[2], it[3], it[1] - it[0] + 1 <= thinWidth) }
            .sortedWith(compareBy({ it.x0 }, { it.y0 }, { it.x1 }, { it.y1 }))
    }
}
