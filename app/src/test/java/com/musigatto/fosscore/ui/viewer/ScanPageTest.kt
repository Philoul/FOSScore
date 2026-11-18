package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.pdfscore.DrawnLine
import com.musigatto.fosscore.pdfscore.DrawnShape
import com.musigatto.fosscore.pdfscore.PageDrawing
import com.musigatto.fosscore.pdfscore.Point
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPageTest {
    private val withImage = "<svg><image width=\"595\" height=\"842\" xlink:href=\"data:image/png;base64,AAAA\"/></svg>".toByteArray()
    private val withoutImage = "<svg><path d=\"M0 0L1 1\"/></svg>".toByteArray()

    private fun letters(count: Int) = List(count) { i ->
        val x = 50.0 + i * 6
        DrawnShape(listOf(Point(x, 40.0), Point(x + 4, 40.0), Point(x + 4, 48.0), Point(x, 48.0)), curved = true)
    }

    /** Five staff lines, a staff space of 2 points apart. */
    private fun staff(top: Double) = List(5) { DrawnLine(40.0, top + it * 2.0, 550.0, top + it * 2.0, 0.2) }

    /**
     * A scan whose PDF also draws a few vector things (its title as outlines, a stamp): it was read
     * as a PDF printed without fonts, and no note was found.
     */
    @Test
    fun aPictureWithAFewThingsDrawnOverItIsAScan() {
        val page = PageDrawing(595.0, 842.0, emptyList(), emptyList(), letters(30), emptyList())
        assertTrue(isScan(withImage, page))
    }

    /**
     * A scan stored as strips of picture, a band of paper 7 points high under each: the bands,
     * evenly spaced, were read as staves, and the page as printed without fonts.
     */
    @Test
    fun aScanInStripsOverBandsOfPaperIsAScan() {
        val bands = List(48) { DrawnLine(1.0, 3.5 + it * 7.0, 840.0, 3.5 + it * 7.0, 7.0) }
        val page = PageDrawing(842.0, 1191.0, emptyList(), bands, emptyList(), emptyList())
        assertTrue(isScan(withImage, page))
    }

    /** A PDF drawn without fonts, a picture in a corner (a logo): its staves are drawn, it is no scan. */
    @Test
    fun drawnStavesBesideAPictureAreNoScan() {
        val page = PageDrawing(595.0, 842.0, emptyList(), staff(100.0) + staff(130.0), letters(30), emptyList())
        assertFalse(isScan(withImage, page))
    }

    /** An A4 page is drawn at 300 dpi; an A3 one as wide as the A4 one, not 3509 pixels wide. */
    @Test
    fun aPageLargerThanA4IsDrawnAsWideAsA4() {
        org.junit.Assert.assertEquals(300.0, scanDpi(595.0), 0.0)
        org.junit.Assert.assertEquals(2480.0, scanDpi(842.0) * 842.0 / 72, 1e-6)
    }

    @Test
    fun aPageWithoutAnyPictureIsNoScan() {
        val page = PageDrawing(595.0, 842.0, emptyList(), emptyList(), emptyList(), emptyList())
        assertFalse(isScan(withoutImage, page))
    }
}
