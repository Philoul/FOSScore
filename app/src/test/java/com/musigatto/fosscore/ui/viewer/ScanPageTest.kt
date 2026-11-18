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

    /** A PDF drawn without fonts, a picture in a corner (a logo): its staves are drawn, it is no scan. */
    @Test
    fun drawnStavesBesideAPictureAreNoScan() {
        val page = PageDrawing(595.0, 842.0, emptyList(), staff(100.0) + staff(130.0), letters(30), emptyList())
        assertFalse(isScan(withImage, page))
    }

    @Test
    fun aPageWithoutAnyPictureIsNoScan() {
        val page = PageDrawing(595.0, 842.0, emptyList(), emptyList(), emptyList(), emptyList())
        assertFalse(isScan(withoutImage, page))
    }
}
