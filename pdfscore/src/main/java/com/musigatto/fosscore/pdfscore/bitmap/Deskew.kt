package com.musigatto.fosscore.pdfscore.bitmap

/**
 * A page's tilt, found and undone (SPEC 4). The tilt is an integer slope k: dy/dx = k / 10000. The
 * page is sheared, not rotated: each column moves up or down by [shifts] of it, so the deskewed
 * pixel (x, y) is the page's (x, y + shift[x]).
 */
class Deskew(val slope: Int, val shifts: IntArray) {
    /** The deskewed image of [ink]: DB[y][x] = B[y + shift[x]][x], white outside the page. */
    fun apply(ink: InkMask): InkMask {
        val out = InkMask(ink.width, ink.height)
        for (x in 0 until ink.width) {
            val shift = shifts[x]
            for (y in 0 until ink.height) {
                val from = y + shift
                if (from >= 0 && from < ink.height && ink[x, from]) out[x, y] = true
            }
        }
        return out
    }

    companion object {
        const val UNIT = 10000

        /** The deskew of [ink] with the first staff space [space] (D0). */
        fun of(ink: InkMask, space: Double): Deskew {
            val k = slope(ink, space)
            return Deskew(k, shifts(ink.width, k))
        }

        /** How far each column moves at slope [k]. */
        fun shifts(width: Int, k: Int): IntArray =
            IntArray(width) { x -> Math.floorDiv((x - width / 2).toLong() * k + UNIT / 2, UNIT.toLong()).toInt() }

        /**
         * The slope that lines up the most pixels of long horizontal runs on the same rows: the
         * points are the even columns of the runs at least floor(2 D0) long found on the even rows
         * (gaps closed); a slope scores the sum of its rows' counts squared. Coarse slopes 17 j, then
         * every slope within 17 of the best; on an equal score the smaller |k| (a straight page stays
         * straight), then the negative one.
         */
        fun slope(ink: InkMask, space: Double): Int {
            val long = ink.rows(2).closeGaps(maxOf(1, rnd(space / 4))).longRuns(floorInt(2 * space))
            var count = 0
            for (y in 0 until long.height) for (x in 0 until long.width step 2) if (long[x, y]) count++
            if (count == 0) return 0
            val ys = LongArray(count)
            val xs = LongArray(count)
            var n = 0
            for (y in 0 until long.height) for (x in 0 until long.width step 2) if (long[x, y]) {
                ys[n] = 2L * y
                xs[n] = (x - ink.width / 2).toLong()
                n++
            }
            val rows = LongArray(count)
            fun score(k: Int): Long {
                var min = Long.MAX_VALUE
                var max = Long.MIN_VALUE
                for (i in 0 until count) {
                    val row = Math.floorDiv(ys[i] * UNIT - xs[i] * k + UNIT / 2, UNIT.toLong())
                    rows[i] = row
                    if (row < min) min = row
                    if (row > max) max = row
                }
                val hist = LongArray((max - min + 1).toInt())
                for (i in 0 until count) hist[(rows[i] - min).toInt()]++
                var s = 0L
                for (c in hist) s += c * c
                return s
            }
            fun byAbs(ks: List<Int>): List<Int> = ks.sortedWith(compareBy({ kotlin.math.abs(it) }, { it }))

            var best = -1L
            var coarse = 0
            for (k in byAbs((-30..30).map { 17 * it })) {
                val s = score(k)
                if (s > best) {
                    best = s
                    coarse = k
                }
            }
            best = -1L
            var fine = 0
            for (k in byAbs((coarse - 17..coarse + 17).toList())) {
                val s = score(k)
                if (s > best || (s == best && kotlin.math.abs(k) < kotlin.math.abs(fine))) {
                    best = s
                    fine = k
                }
            }
            return fine
        }
    }
}
