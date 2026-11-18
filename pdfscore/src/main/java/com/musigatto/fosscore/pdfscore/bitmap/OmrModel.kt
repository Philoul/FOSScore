package com.musigatto.fosscore.pdfscore.bitmap

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.sqrt

/** A class and how sure of it the classifier is (its share of the neighbours' votes, 0 to 1). */
data class Classified(val name: String, val score: Double)

/**
 * A k-nearest-neighbours classifier over the 67 features (SPEC 10): the [k] exemplars nearest a
 * blob (squared distance, ties to the lower index) vote for their class with weight
 * 1 / (sqrt(d) / 20 + 0.3). [features] holds the exemplars row after row, [classOf] their classes.
 */
class Knn(val classes: List<String>, private val features: ByteArray, private val classOf: ByteArray, val k: Int) {
    private val count = classOf.size

    /** The exemplars ordered by their first feature (the width), lower index first on ties. */
    private val byWidth: IntArray = (0 until count).sortedBy { features[it * Features.COUNT].toInt() and 0xFF }.toIntArray()
    private val widths = IntArray(count) { features[byWidth[it] * Features.COUNT].toInt() and 0xFF }

    /** The score of every class for features [q], in class order. */
    fun scores(q: IntArray): DoubleArray {
        val bestD = IntArray(k) { Int.MAX_VALUE }
        val bestI = IntArray(k) { Int.MAX_VALUE }
        fun offer(e: Int) {
            val limit = bestD[k - 1]
            var d = 0
            val base = e * Features.COUNT
            for (f in 0 until Features.COUNT) {
                val diff = q[f] - (features[base + f].toInt() and 0xFF)
                d += diff * diff
                if (d > limit) return
            }
            if (d == limit && e > bestI[k - 1]) return
            var i = k - 1
            while (i > 0 && (bestD[i - 1] > d || (bestD[i - 1] == d && bestI[i - 1] > e))) {
                bestD[i] = bestD[i - 1]
                bestI[i] = bestI[i - 1]
                i--
            }
            bestD[i] = d
            bestI[i] = e
        }
        // scan outward from the query's width: a side stops once its width alone is too far
        var hi = lowerBound(q[0])
        var lo = hi - 1
        while (lo >= 0 || hi < count) {
            var moved = false
            if (hi < count) {
                val dw = widths[hi] - q[0]
                if (dw.toLong() * dw <= bestD[k - 1].toLong()) {
                    offer(byWidth[hi++])
                    moved = true
                } else {
                    hi = count
                }
            }
            if (lo >= 0) {
                val dw = q[0] - widths[lo]
                if (dw.toLong() * dw <= bestD[k - 1].toLong()) {
                    offer(byWidth[lo--])
                    moved = true
                } else {
                    lo = -1
                }
            }
            if (!moved && lo < 0 && hi >= count) break
        }
        val out = DoubleArray(classes.size)
        for (i in 0 until minOf(k, count)) {
            if (bestI[i] == Int.MAX_VALUE) continue
            out[classOf[bestI[i]].toInt() and 0xFF] += 1.0 / (sqrt(bestD[i].toDouble()) / 20.0 + 0.3)
        }
        var total = 0.0
        for (v in out) total += v
        if (total > 0) for (c in out.indices) out[c] /= total
        return out
    }

    /** The best class (first maximum in class order) and its score. */
    fun classify(q: IntArray): Classified {
        val p = scores(q)
        var best = 0
        for (c in 1 until p.size) if (p[c] > p[best]) best = c
        return Classified(classes[best], p[best])
    }

    /** The first position in [widths] whose width is at least [w]. */
    private fun lowerBound(w: Int): Int {
        var a = 0
        var b = count
        while (a < b) {
            val m = (a + b) ushr 1
            if (widths[m] < w) a = m + 1 else b = m
        }
        return a
    }
}

/**
 * The classifier file `fosscore-omr-model.bin` (SPEC 13): the size steps of the features, the main
 * kNN over the symbol classes and the digit kNN used to read time signatures.
 */
class OmrModel(val thresholds: DoubleArray, val symbols: Knn, val digits: Knn) {
    companion object {
        /** Where the model ships, among the module's resources. */
        const val RESOURCE = "/com/musigatto/fosscore/pdfscore/bitmap/fosscore-omr-model.bin"

        /** The model shipped with the module; null when it is missing or damaged. */
        fun load(): OmrModel? = OmrModel::class.java.getResourceAsStream(RESOURCE)?.use { read(it.readBytes()) }

        /**
         * The model in [bytes]; null when they are not one (wrong header, wrong length, or a CRC-32
         * that does not match: a damaged file is never half read).
         */
        fun read(bytes: ByteArray): OmrModel? {
            if (bytes.size < 44 || String(bytes, 0, 8, Charsets.US_ASCII) != "FOSSOMR1") return null
            val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (crc != (buffer.getInt(bytes.size - 4).toLong() and 0xFFFFFFFFL)) return null
            buffer.position(8)
            val header = IntArray(8) { buffer.int }
            val (version, nFeatures, k, nClasses, n) = header
            val nDigitClasses = header[5]
            val nDigits = header[6]
            val nThresholds = header[7]
            if (version != 1 || nFeatures != Features.COUNT || k < 1) return null
            val expected = 40L + 8L * nThresholds + (n.toLong() + nDigits) * (nFeatures + 1) + 4
            if (expected > bytes.size) return null
            val thresholds = DoubleArray(nThresholds) { buffer.double }
            fun names(count: Int): List<String> = List(count) {
                val length = buffer.get().toInt() and 0xFF
                val name = ByteArray(length)
                buffer.get(name)
                String(name, Charsets.US_ASCII)
            }
            val classes = names(nClasses)
            val digitClasses = names(nDigitClasses)
            fun bytes(count: Int): ByteArray = ByteArray(count).also { buffer.get(it) }
            val x = bytes(n * nFeatures)
            val y = bytes(n)
            val dx = bytes(nDigits * nFeatures)
            val dy = bytes(nDigits)
            if (buffer.position() != bytes.size - 4) return null
            return OmrModel(thresholds, Knn(classes, x, y, k), Knn(digitClasses, dx, dy, k))
        }
    }
}
