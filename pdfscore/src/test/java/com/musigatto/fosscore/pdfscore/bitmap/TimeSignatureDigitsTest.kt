package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The digit classifier reads one digit: a time signature's "12" (Mistral gagnant's 12/8, its two
 * digits touching) is cut in two at its thinnest column before it is read; one digit is not.
 */
class TimeSignatureDigitsTest {
    private val space = 20.0

    private fun rules(db: InkMask): SymbolRules? = Golden.model?.let { SymbolRules(emptyList(), emptyList(), space, 2.0, db, it) }

    @Test
    fun twoTouchingDigitsAreCutInTwo() {
        val db = InkMask(200, 100)
        // a "1" (8 columns) and a "2" (22 columns) joined by a 2-pixel bridge at the bottom: 52 wide
        for (y in 10..50) for (x in 20..27) db[x, y] = true
        for (y in 48..50) for (x in 28..49) db[x, y] = true
        for (y in 10..50) for (x in 50..71) if (y < 20 || y > 44 || x > 64) db[x, y] = true
        val rules = rules(db)
        assumeTrue(rules != null)
        val (crop, box) = Blobs.crop(db, 0, 0, 199, 99) ?: error("no ink")
        val parts = rules!!.split(crop, box)
        assertEquals(2, parts.size)
        assertEquals(20, parts[0].second[0])
        assertEquals(71, parts[1].second[2])
    }

    @Test
    fun oneDigitStaysWhole() {
        val db = InkMask(200, 100)
        for (y in 10..50) for (x in 20..49) if (y < 20 || y > 40 || x < 26 || x > 43) db[x, y] = true
        val rules = rules(db)
        assumeTrue(rules != null)
        val (crop, box) = Blobs.crop(db, 0, 0, 199, 99) ?: error("no ink")
        assertEquals(1, rules!!.split(crop, box).size)
    }
}
