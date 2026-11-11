package com.musigatto.fosscore.pdfscore

/**
 * A small black-and-white image of a symbol: the common ground on which a symbol is recognised by
 * its look, whether it came from a PDF's outline (rasterised with [rasterize]) or from a scan's pixels.
 */
class Mask(val width: Int, val height: Int, private val bits: BooleanArray = BooleanArray(width * height)) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && bits[y * width + x]

    operator fun set(x: Int, y: Int, on: Boolean) {
        if (x in 0 until width && y in 0 until height) bits[y * width + x] = on
    }

    val count: Int get() = bits.count { it }

    /** Intersection over union with a mask of the same size: 1 when they are the same. */
    fun iou(other: Mask): Double {
        require(other.width == width && other.height == height) { "masks of different sizes" }
        var both = 0
        var either = 0
        for (i in bits.indices) {
            if (bits[i] && other.bits[i]) both++
            if (bits[i] || other.bits[i]) either++
        }
        return if (either == 0) 1.0 else both.toDouble() / either
    }

    companion object {
        /**
         * Fills [polygons] (closed outlines, nonzero winding) into a mask covering [frame], one pixel
         * per 1 / [scale] units. A pixel is on when its centre is inside.
         */
        fun rasterize(polygons: List<List<Point>>, frame: Box, scale: Double): Mask {
            val width = maxOf(1, kotlin.math.ceil(frame.width * scale).toInt())
            val height = maxOf(1, kotlin.math.ceil(frame.height * scale).toInt())
            val mask = Mask(width, height)
            val crossings = ArrayList<Pair<Double, Int>>()
            for (row in 0 until height) {
                val y = frame.top + (row + 0.5) / scale
                crossings.clear()
                for (polygon in polygons) {
                    for (i in polygon.indices) {
                        val a = polygon[i]
                        val b = polygon[(i + 1) % polygon.size]
                        if (a.y == b.y) continue
                        val upward = a.y < b.y
                        val low = if (upward) a else b
                        val high = if (upward) b else a
                        if (y < low.y || y >= high.y) continue
                        val x = low.x + (y - low.y) * (high.x - low.x) / (high.y - low.y)
                        crossings += x to (if (upward) 1 else -1)
                    }
                }
                crossings.sortBy { it.first }
                var winding = 0
                for (i in crossings.indices) {
                    val start = crossings[i].first
                    winding += crossings[i].second
                    val end = crossings.getOrNull(i + 1)?.first ?: break
                    if (winding == 0) continue
                    val from = kotlin.math.ceil((start - frame.left) * scale - 0.5).toInt()
                    val to = kotlin.math.floor((end - frame.left) * scale - 0.5).toInt()
                    for (col in maxOf(0, from)..minOf(width - 1, to)) mask[col, row] = true
                }
            }
            return mask
        }
    }
}
