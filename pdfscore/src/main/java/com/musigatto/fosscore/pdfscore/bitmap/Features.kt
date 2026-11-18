package com.musigatto.fosscore.pdfscore.bitmap

/**
 * A blob's 67 features (SPEC 9), each 0..244, computed with integers only but for the two sizes:
 * its width and height in spaces on a log scale, its ink density, and the density of each cell of
 * an 8 x 8 grid over it.
 */
object Features {
    const val COUNT = 3 + 8 * 8

    /**
     * The features of [mask] (cropped to its ink) at staff space [space]; [thresholds] are the 244
     * size steps of the model file (exp((j - 0.5) / 80) - 1, as stored: never recomputed).
     */
    fun of(mask: InkMask, space: Double, thresholds: DoubleArray): IntArray {
        val h = mask.height
        val w = mask.width
        // integral image: ii[y][x] = ink in rows < y, columns < x
        val ii = LongArray((h + 1) * (w + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                if (mask[x, y]) row++
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row
            }
        }
        fun sum(y0: Int, x0: Int, y1: Int, x1: Int): Long =
            ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
        val q = IntArray(COUNT)
        q[0] = size(w, space, thresholds)
        q[1] = size(h, space, thresholds)
        val area = w.toLong() * h
        q[2] = ((80 * sum(0, 0, h, w) + area) / (2 * area)).toInt()
        val ey = cells(h)
        val ex = cells(w)
        for (i in 0 until 8) for (j in 0 until 8) {
            val s = sum(ey[2 * i], ex[2 * j], ey[2 * i + 1], ex[2 * j + 1])
            val a = (ey[2 * i + 1] - ey[2 * i]).toLong() * (ex[2 * j + 1] - ex[2 * j])
            q[3 + 8 * i + j] = ((40 * s + a) / (2 * a)).toInt()
        }
        return q
    }

    /** How many of the size steps (times [space]) [n] pixels reach. */
    private fun size(n: Int, space: Double, thresholds: DoubleArray): Int = thresholds.count { n.toDouble() >= it * space }

    /** The 8 cells' [start, end) along a side of [n] pixels, as start0, end0, start1, end1... */
    private fun cells(n: Int): IntArray {
        val out = IntArray(16)
        for (k in 0 until 8) {
            val a = (k * n) / 8
            val b = maxOf(((k + 1) * n) / 8, a + 1)
            out[2 * k] = a
            out[2 * k + 1] = minOf(b, n)
        }
        return out
    }
}
