package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale

/**
 * The golden blobs (SPEC 14, free synthetic data): each blob's 67 features exactly, and the class
 * and score the shipped model gives it.
 */
class GoldenBlobsTest {
    private class Record(val n: Int, val space: Double, val mask: InkMask, val features: IntArray, val name: String, val score: String)

    private fun records(): List<Record> {
        val lines = Golden.lines("blobs.txt").filter { !it.startsWith("#") }
        val out = mutableListOf<Record>()
        var i = 0
        while (i < lines.size) {
            val n = lines[i].split(" ")[1].toInt()
            val d = lines[i + 1].split(" ")
            val space = d[1].toDouble()
            assertEquals("hex and decimal D of blob $n", java.lang.Double.parseDouble(d[2]), space, 0.0)
            val (w, h) = lines[i + 2].split(" ").drop(1).map { it.toInt() }
            val mask = InkMask(w, h)
            for (y in 0 until h) lines[i + 3 + y].forEachIndexed { x, c -> mask[x, y] = c == '1' }
            val features = lines[i + 3 + h].split(" ").drop(1).map { it.toInt() }.toIntArray()
            val cls = lines[i + 4 + h].split(" ")
            assertEquals("end", lines[i + 5 + h])
            out += Record(n, space, mask, features, cls[1], cls[2])
            i += 6 + h
        }
        return out
    }

    @Test
    fun theModelFileReadsAndChecksItsCrc() {
        val model = Golden.model
        assertNotNull(model)
        model?.let {
            assertEquals(244, it.thresholds.size)
            assertEquals(53, it.symbols.classes.size)
            assertEquals(10, it.digits.classes.size)
            // a byte changed: the CRC refuses the file
            val bytes = OmrModel::class.java.getResourceAsStream(OmrModel.RESOURCE)?.use { s -> s.readBytes() } ?: ByteArray(0)
            bytes[5000] = (bytes[5000] + 1).toByte()
            assertEquals(null, OmrModel.read(bytes))
        }
    }

    @Test
    fun theThresholdsAreTheGoldenOnes() {
        // THR[j] = exp((j - 0.5) / 80) - 1, as stored in the model: "j decimal hex" per line
        val listed = Golden.lines("thresholds.txt").filter { !it.startsWith("#") }.map { it.split(" ") }
        assertEquals(244, listed.size)
        val model = Golden.model
        assertNotNull(model)
        model?.let {
            listed.forEachIndexed { i, (j, decimal, hex) ->
                assertEquals("$j", i + 1, j.toInt())
                assertEquals("THR[$j]", java.lang.Double.parseDouble(hex), it.thresholds[i], 0.0)
                assertEquals("THR[$j]", decimal.toDouble(), it.thresholds[i], 0.0)
            }
        }
    }

    @Test
    fun goldenBlobsHaveTheirFeaturesAndClasses() {
        val model = Golden.model
        assertNotNull(model)
        val records = records()
        assertEquals(31, records.size)
        model?.let {
            for (r in records) {
                val q = Features.of(r.mask, r.space, it.thresholds)
                assertArrayEquals("features of blob ${r.n}", r.features, q)
                val c = it.symbols.classify(q)
                assertEquals("class of blob ${r.n}", r.name, c.name)
                assertEquals("score of blob ${r.n}", r.score, String.format(Locale.ROOT, "%.4f", c.score))
            }
        }
    }
}
