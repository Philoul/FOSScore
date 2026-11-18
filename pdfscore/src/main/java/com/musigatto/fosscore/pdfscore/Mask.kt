package com.musigatto.fosscore.pdfscore

/**
 * A small black-and-white image of a symbol: the common ground on which a symbol is recognised by
 * its look, whether it came from a PDF's outline (rasterised with [rasterize]) or from a scan's pixels.
 */
class Mask(val width: Int, val height: Int, private val bits: BooleanArray = BooleanArray(width * height)) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && bits[y * width + x]

    operator fun set(x: Int, y: Int, on: Boolean) {
        if (x in 0 until width && y in 0 until height) {
            bits[y * width + x] = on
            packed = null
        }
    }

    val count: Int get() = words().sumOf { java.lang.Long.bitCount(it) }

    /** The bits 64 to a word, for [iou]: a symbol is compared with thousands of references. */
    private var packed: LongArray? = null

    private fun words(): LongArray = packed ?: LongArray((bits.size + 63) / 64).also { words ->
        for (i in bits.indices) if (bits[i]) words[i / 64] = words[i / 64] or (1L shl (i % 64))
        packed = words
    }

    /** Intersection over union with a mask of the same size: 1 when they are the same. */
    fun iou(other: Mask): Double {
        require(other.width == width && other.height == height) { "masks of different sizes" }
        val a = words()
        val b = other.words()
        var both = 0
        var either = 0
        for (i in a.indices) {
            both += java.lang.Long.bitCount(a[i] and b[i])
            either += java.lang.Long.bitCount(a[i] or b[i])
        }
        return if (either == 0) 1.0 else both.toDouble() / either
    }

    /** The mask grown by one pixel all around (the eight neighbours of each pixel on). */
    fun dilated(): Mask {
        val result = Mask(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            if (!this[x, y]) continue
            for (dy in -1..1) for (dx in -1..1) result[x + dx, y + dy] = true
        }
        return result
    }

    /** How many on pixels of this mask are on in [other] too. */
    private fun overlap(other: Mask): Int {
        val a = words()
        val b = other.words()
        var count = 0
        for (i in a.indices) count += java.lang.Long.bitCount(a[i] and b[i])
        return count
    }

    /** The bits row by row, eight to a byte (the first in the high bit). */
    fun toBytes(): ByteArray {
        val bytes = ByteArray((width * height + 7) / 8)
        for (i in 0 until width * height) if (bits[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        return bytes
    }

    companion object {
        /** A mask from [toBytes]'s bytes. */
        fun fromBytes(width: Int, height: Int, bytes: ByteArray): Mask =
            Mask(width, height, BooleanArray(width * height) { i -> (bytes[i / 8].toInt() and (0x80 ushr (i % 8))) != 0 })

        /**
         * How alike two masks of the same size are, within a pixel: the share of each one's pixels
         * that fall on the other or next to it ([aNear], [bNear]: the masks [dilated]). 1 when
         * they are the same or a pixel apart.
         */
        fun closeness(a: Mask, aNear: Mask, b: Mask, bNear: Mask): Double {
            val total = a.count + b.count
            return if (total == 0) 1.0 else (a.overlap(bNear) + b.overlap(aNear)).toDouble() / total
        }

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
