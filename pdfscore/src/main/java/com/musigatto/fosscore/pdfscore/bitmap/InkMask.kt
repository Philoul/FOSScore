package com.musigatto.fosscore.pdfscore.bitmap

import kotlin.math.floor

/**
 * A black and white image: [width] columns, [height] rows, `true` where there is ink. Pixel (x, y)
 * covers [x, x + 1) x [y, y + 1), y downward. The pixels are kept row after row in [ink].
 */
class InkMask(val width: Int, val height: Int, val ink: BooleanArray = BooleanArray(width * height)) {
    operator fun get(x: Int, y: Int): Boolean = ink[y * width + x]

    operator fun set(x: Int, y: Int, value: Boolean) {
        ink[y * width + x] = value
    }

    /** How many ink pixels the image holds. */
    fun count(): Int = ink.count { it }

    fun copy(): InkMask = InkMask(width, height, ink.copyOf())

    /** Every [step]th row, from the first: the rows 0, step, 2 step... as an image of their own. */
    fun rows(step: Int): InkMask {
        val rows = (height + step - 1) / step
        val out = InkMask(width, rows)
        for (r in 0 until rows) System.arraycopy(ink, r * step * width, out.ink, r * width, width)
        return out
    }

    /**
     * The pixels belonging to a horizontal run (in their row, cut at the image's borders) at least
     * [minLength] long (SPEC 0, `hruns`).
     */
    fun longRuns(minLength: Int): InkMask {
        val out = InkMask(width, height)
        for (y in 0 until height) {
            val row = y * width
            var x = 0
            while (x < width) {
                if (!ink[row + x]) {
                    x++
                    continue
                }
                var end = x
                while (end + 1 < width && ink[row + end + 1]) end++
                if (end - x + 1 >= minLength) java.util.Arrays.fill(out.ink, row + x, row + end + 1, true)
                x = end + 1
            }
        }
        return out
    }

    /**
     * Gaps closed along the rows (SPEC 2, `close`): a white run of at most [gap] pixels with ink
     * just left and right of it (not at the image's border) becomes ink. Used only to find lines.
     */
    fun closeGaps(gap: Int): InkMask {
        val out = copy()
        for (y in 0 until height) {
            val row = y * width
            var x = 0
            while (x < width) {
                if (ink[row + x]) {
                    x++
                    continue
                }
                var end = x
                while (end + 1 < width && !ink[row + end + 1]) end++
                if (x > 0 && end < width - 1 && end - x + 1 <= gap) java.util.Arrays.fill(out.ink, row + x, row + end + 1, true)
                x = end + 1
            }
        }
        return out
    }
}

/** Rounding half up, also for negative values: floor(v + 0.5) (SPEC 0). */
internal fun rnd(v: Double): Int = floor(v + 0.5).toInt()

/** floor(v) as an Int. */
internal fun floorInt(v: Double): Int = floor(v).toInt()

/**
 * The median of [values] (sorted here): the middle one, or the mean of the two middle ones when
 * their count is even.
 */
internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
}
