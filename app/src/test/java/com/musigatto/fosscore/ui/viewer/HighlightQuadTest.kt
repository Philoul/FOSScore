package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.pdf.PdfPageSpace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A rect shown on screen -> the same rect in PDF points, for each page turn.
 *
 * Ruling (2026-11-11): the plan's expected values for 180° and 270° did not match
 * [com.musigatto.fosscore.pdf.PdfAnnotMapper] (the mapper the shipped export already uses, and
 * geometrically consistent for all four turns). These are the mapper's actual results.
 */
class HighlightQuadTest {

    private val space = PdfPageSpace(0f, 0f, 612f, 792f, 0)
    // 20% x 10% of the page, starting at (10%, 20%): RectPx(left, top, width, height)
    private val shown = RectPx(0.1f, 0.2f, 0.2f, 0.1f)

    private fun assertRect(expected: RectPx, actual: RectPx) {
        assertEquals("left", expected.left, actual.left, 0.01f)
        assertEquals("top", expected.top, actual.top, 0.01f)
        assertEquals("width", expected.width, actual.width, 0.01f)
        assertEquals("height", expected.height, actual.height, 0.01f)
    }

    @Test
    fun uprightPage() {
        assertRect(RectPx(61.2f, 554.4f, 122.4f, 79.2f), pdfRect(shown, space))
    }

    @Test
    fun pageTurned90() {
        assertRect(RectPx(122.4f, 79.2f, 61.2f, 158.4f), pdfRect(shown, space.copy(rotation = 90)))
    }

    @Test
    fun pageTurned180() {
        assertRect(RectPx(428.4f, 158.4f, 122.4f, 79.2f), pdfRect(shown, space.copy(rotation = 180)))
    }

    @Test
    fun pageTurned270() {
        assertRect(RectPx(428.4f, 554.4f, 61.2f, 158.4f), pdfRect(shown, space.copy(rotation = 270)))
    }
}