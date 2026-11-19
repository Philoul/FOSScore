package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A scan's brace is no symbol the model names: the two staves of a piano were two parts
 * (Halleluya, roadmap 10). Ink all along the left of two staves of a system, from the top of the
 * first to the bottom of the second, is a brace.
 */
class BitmapBracesTest {
    private fun staff(top: Int) = BitmapStaff(DoubleArray(5) { top + 10.0 * it }, 60, 380, 400, intArrayOf(0))

    @Test
    fun inkAllAlongTheLeftOfTwoStavesIsABrace() {
        val ink = InkMask(400, 400)
        val staves = listOf(staff(60), staff(140), staff(220))
        // a brace by the second and third staves (a voice over a piano), a wavy stroke 4 px wide
        for (y in 140..260) for (x in 0 until 4) ink[46 + x + (y / 20) % 2, y] = true
        val braces = BitmapPage.braces(ink, staves, listOf(listOf(0, 1, 2)))
        assertEquals(1, braces.size)
        assertEquals(140, braces.single().y0)
        assertEquals(260, braces.single().y1)
    }

    @Test
    fun withoutInkThereIsNoBrace() {
        val ink = InkMask(400, 400)
        // the system's opening line at the staves' left edge only
        for (y in 60..260) ink[60, y] = true
        assertEquals(0, BitmapPage.braces(ink, listOf(staff(60), staff(140), staff(220)), listOf(listOf(0, 1, 2))).size)
    }
}
