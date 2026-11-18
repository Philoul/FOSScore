package com.musigatto.fosscore.pdfscore.bitmap

/**
 * An 8-bit gray image: [width] columns, [height] rows, row after row in [pixels] (0 black, 255
 * white, read as unsigned bytes). On Android, a decoded ARGB pixel's gray is best taken as
 * `(77 R + 150 G + 29 B + 128) shr 8` (SPEC 1).
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/**
 * Ink and paper apart (SPEC 2): Otsu's threshold, moved a quarter of the way toward the paper's
 * gray so that thin, blurred staff lines stay whole.
 */
object Binarize {
    /** How many pixels have each gray, 0 to 255. */
    fun histogram(gray: GrayImage): LongArray {
        val hist = LongArray(256)
        for (p in gray.pixels) hist[p.toInt() and 0xFF]++
        return hist
    }

    /**
     * Otsu's threshold of [hist]; when more than a quarter of the pixels would be ink (a sparse page
     * whose paper is uneven: Otsu has split the paper), Otsu again on the histogram cut above the
     * last threshold, at most 4 times.
     */
    fun otsu(hist: LongArray): Int {
        val total = hist.sum()
        var t = otsuOf(hist)
        repeat(4) {
            var under = 0L
            for (i in 0..t) under += hist[i]
            if (4 * under <= total || t == 0) return t
            val cut = hist.copyOf()
            for (i in t + 1 until 256) cut[i] = 0
            t = otsuOf(cut)
        }
        return t
    }

    /** Otsu on one histogram: the gray that best splits it in two classes (first maximum). */
    private fun otsuOf(hist: LongArray): Int {
        val n = hist.sum()
        var mt = 0L
        for (i in 0 until 256) mt += i * hist[i]
        var best = -1.0
        var threshold = 0
        var wB = 0L
        var muB = 0L
        for (t in 0 until 256) {
            wB += hist[t]
            muB += t * hist[t]
            if (wB == 0L || wB == n) continue
            val a = mt.toDouble() * wB.toDouble() - muB.toDouble() * n.toDouble()
            val s = a * a / (wB.toDouble() * (n - wB).toDouble())
            if (s > best) {
                best = s
                threshold = t
            }
        }
        return threshold
    }

    /**
     * The gray at or under which a pixel is ink: Otsu's [otsu] plus a quarter of the way to the
     * paper's gray (the most common gray over it).
     */
    fun threshold(hist: LongArray, otsu: Int = otsu(hist)): Int {
        var paper = 255
        if (otsu < 255) {
            paper = otsu + 1
            for (i in otsu + 2 until 256) if (hist[i] > hist[paper]) paper = i
        }
        return otsu + Math.floorDiv(paper - otsu, 4)
    }

    /** The ink mask of [gray] at [threshold]: ink where the gray is at most it. */
    fun ink(gray: GrayImage, threshold: Int): InkMask {
        val out = InkMask(gray.width, gray.height)
        for (i in gray.pixels.indices) out.ink[i] = (gray.pixels[i].toInt() and 0xFF) <= threshold
        return out
    }
}
