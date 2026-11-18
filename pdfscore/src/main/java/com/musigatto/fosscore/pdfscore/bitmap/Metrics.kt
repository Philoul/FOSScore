package com.musigatto.fosscore.pdfscore.bitmap

/**
 * The page's two units (SPEC 3): the staff lines' thickness [thickness] (T) and a first staff space
 * [space] (D0), both in pixels, from the vertical ink runs of every 4th column.
 */
data class Metrics(val thickness: Double, val space: Double) {
    companion object {
        /**
         * T: the most common ink run's length; D0: the most common distance from a run's start to
         * the next one's in the same column (a line and the white below it); each refined by the
         * mean over the mode and its two neighbours.
         */
        fun of(ink: InkMask): Metrics {
            val blk = LongArray(201)
            val ph = LongArray(401)
            for (x in 0 until ink.width step 4) {
                var previous = -1
                var y = 0
                while (y < ink.height) {
                    if (!ink[x, y]) {
                        y++
                        continue
                    }
                    var end = y
                    while (end + 1 < ink.height && ink[x, end + 1]) end++
                    blk[minOf(end - y + 1, 200)]++
                    if (previous >= 0) ph[minOf(y - previous, 400)]++
                    previous = y
                    y = end + 1
                }
            }
            ph[0] = 0
            ph[1] = 0
            ph[2] = 0
            val t0 = firstMax(blk, 1, 59)
            val d0 = firstMax(ph, 0, 299)
            return Metrics(refine(blk, maxOf(t0 - 1, 1), t0 + 1, t0), refine(ph, maxOf(d0 - 1, 0), d0 + 1, d0))
        }

        /** The first index of the largest count in [from]..[to]. */
        private fun firstMax(counts: LongArray, from: Int, to: Int): Int {
            var best = from
            for (i in from + 1..to) if (counts[i] > counts[best]) best = i
            return best
        }

        /** The mean of the indices [from]..[to] weighted by their counts; [mode] when they are all 0. */
        private fun refine(counts: LongArray, from: Int, to: Int, mode: Int): Double {
            var num = 0L
            var den = 0L
            for (i in from..to) {
                num += i * counts[i]
                den += counts[i]
            }
            return if (den == 0L) mode.toDouble() else num.toDouble() / den.toDouble()
        }
    }
}
