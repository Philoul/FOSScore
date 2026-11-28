package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The scan classifier has no arpeggio and names its wavy line a quarter rest (Mistral gagnant:
 * every rolled chord had a rest before it). A "rest" with a vertical spine just before a chord at
 * its height is the wave; a rest is drawn with slanted strokes.
 */
class ScanArpeggiosTest {
    private val space = 20.0

    @Test
    fun aRestWithASpineJustBeforeAChordIsAnArpeggio() {
        val model = Golden.model
        assumeTrue(model != null)
        val wave = Blob(1, 100, 200, 115, 272, InkMask(16, 73), stems = listOf(Segment(107, 107, 200, 272, true)))
        val rest = Blob(1, 400, 200, 415, 262, InkMask(16, 63))
        val rules = SymbolRules(listOf(wave, rest), emptyList(), space, 2.0, InkMask(800, 400), model!!)
        val symbols = listOf(
            BitmapSymbol("restQuarter", 100, 200, 115, 272, 0.6, 0),
            BitmapSymbol("noteheadBlack", 125, 220, 150, 240, 1.0, 2),
            BitmapSymbol("restQuarter", 400, 200, 415, 262, 0.9, 1),
            BitmapSymbol("noteheadBlack", 425, 220, 450, 240, 1.0, 3),
        )
        assertEquals(listOf("wiggleArpeggiatoUp", "noteheadBlack", "restQuarter", "noteheadBlack"), rules.arpeggios(symbols).map { it.name })
    }
}
