package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A staff's lines are not quite evenly spaced on a scan: Mistral gagnant's pages, made of strips
 * at 141 dpi, put them 19 to 21.5 pixels apart once drawn at 300 dpi, and the fifth line,
 * expected where the first two lines' spacing put it, was too far: three staves of the score were
 * lost (and their measures with them).
 */
class StaffFinderTest {
    private fun page(vararg rows: Int): InkMask {
        val ink = InkMask(800, 300)
        for (row in rows) for (x in 50 until 750) {
            ink[x, row] = true
            ink[x, row + 1] = true
        }
        return ink
    }

    @Test
    fun evenlySpacedLinesAreAStaff() {
        val staves = StaffFinder.find(page(100, 120, 140, 160, 180), 2.0, 20.0).staves
        assertEquals(1, staves.size)
    }

    @Test
    fun linesSpacedUnevenlyByAPixelOrTwoAreAStaff() {
        // spaced 19, 21, 21, 21: the fifth line is 6 pixels from 100 + 4 x 19
        val staves = StaffFinder.find(page(100, 119, 140, 161, 182), 2.0, 20.0).staves
        assertEquals(1, staves.size)
        assertEquals(100.5, staves.single().top, 0.6)
        assertEquals(182.5, staves.single().bottom, 0.6)
    }
}
