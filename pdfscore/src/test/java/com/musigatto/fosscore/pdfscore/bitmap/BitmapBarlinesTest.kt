package com.musigatto.fosscore.pdfscore.bitmap

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An arpeggio's wavy line is no barline (Je suis malade's scan, Lucie's): its spine, a column inked
 * all along, made a vertical stroke from the staff's top line to its bottom one, and the empty
 * measure it added shifted every measure after it. A barline is as wide as its ink; the wave's ink
 * is far wider than its spine.
 */
class BitmapBarlinesTest {
    @Test
    fun aBarlineIsAsWideAsItsInk() {
        val ink = InkMask(100, 200)
        for (y in 20..140) for (x in 50..52) ink[x, y] = true
        assertTrue(BitmapPage.spread(Segment(50, 52, 20, 140, thin = true), ink) <= 1.0)
    }

    @Test
    fun anArpeggiosWaveIsNoBarline() {
        val ink = InkMask(100, 200)
        // a zigzag 3 px thick swinging 4 px either way of column 50, crossing it on every row
        for (y in 20..140) {
            val swing = (y / 4) % 4 - 2
            for (x in 47 + swing..53 + swing) ink[x, y] = true
        }
        // the stroke finder keeps only the spine, one column wide
        val spine = Segment(50, 50, 20, 140, thin = true)
        assertTrue(BitmapPage.spread(spine, ink) > 1.75)
    }
}
