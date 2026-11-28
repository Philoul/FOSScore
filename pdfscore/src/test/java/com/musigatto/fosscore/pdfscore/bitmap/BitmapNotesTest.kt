package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pieces of a scan's notes the readers need (Mistral gagnant's scan). */
class BitmapNotesTest {
    private val space = 20.0

    /**
     * A stem-down eighth's flag curls up to its head and touches it: with the stem erased, head
     * and flag were one piece named a flag, dropped with its note. The head is cut off at the neck.
     */
    @Test
    fun aHeadItsFlagTouchesIsCutOffIt() {
        val m = InkMask(26, 70)
        for (y in 0 until 22) for (x in 0 until 26) m[x, y] = true // the head
        for (y in 22 until 26) for (x in 18 until 21) m[x, y] = true // the neck, 3 px
        for (y in 26 until 70) for (x in 0 until 26) if (x in (y - 26) / 2..(y - 26) / 2 + 5) m[x, y] = true // the flag
        val parts = Blobs.splitHead(m, intArrayOf(100, 200, 125, 269), space)
        assertEquals(2, parts.size)
        assertEquals(listOf(100, 200, 125, 221), parts[0].second.toList())
    }

    @Test
    fun aFlagAloneStaysWhole() {
        val m = InkMask(12, 60)
        for (y in 0 until 60) for (x in 0 until 12) if (x in y / 6..y / 6 + 3) m[x, y] = true
        assertEquals(1, Blobs.splitHead(m, intArrayOf(0, 0, 11, 59), space).size)
    }

    /** A tie of a scan is drawn as a curve from its first column to its last, as a PDF draws it. */
    @Test
    fun aTieIsACurveFromEndToEnd() {
        val mask = InkMask(60, 10)
        for (x in 0 until 60) {
            val y = (8 - 6 * kotlin.math.sin(Math.PI * x / 59)).toInt()
            mask[x, y] = true
            mask[x, minOf(9, y + 1)] = true
        }
        val curve = BitmapPage.curve(Blob(1, 300, 400, 359, 409, mask))
        assertTrue(curve.curved)
        assertEquals(300.5, curve.points.first().x, 0.01)
        assertEquals(359.5, curve.points.last().x, 0.01)
        assertTrue(curve.points.first().y > curve.points[curve.points.size / 2].y)
    }
}
