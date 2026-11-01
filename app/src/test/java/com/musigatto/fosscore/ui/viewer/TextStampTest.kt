package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The geometry of a text stamp (issue #35): the font is a share of the page's height and the
 * selection box is the MEASURED text, clamped inside the sheet — not the stamp's square. */
class TextStampTest {

    private fun textStamp() = Stamp(
        sheetId = 1, page = 0, symbol = Stamp.TEXT_SYMBOL,
        x = 0.5f, y = 0.5f, size = 0.024f, text = "Aa",
    )

    @Test
    fun fontUsesShareOfPageHeightAndClamps() {
        val fit = RectPx(0f, 0f, 1000f, 2000f)
        assertEquals(48f, textFontPx(0.024f, fit), 0.001f)
        // past the cap the font stops growing: a 0.99 share of a page would swallow the music
        assertEquals(MAX_TEXT_FRAC * fit.height, textFontPx(0.99f, fit), 0.001f)
        assertEquals(MIN_TEXT_FRAC * fit.height, textFontPx(1e-5f, fit), 0.001f)
    }

    @Test
    fun boxIsTheMeasuredTextNotASquare() {
        val fit = RectPx(0f, 0f, 1000f, 2000f)
        val r = textBoxRect(textStamp(), 300f, 40f, fit)
        assertEquals(500f - 150f, r.left, 0.001f) // centre x = 0.5 * 1000
        assertEquals(300f, r.width, 0.001f)
        assertEquals(1000f - 20f, r.top, 0.001f) // centre y = 0.5 * 2000
        assertEquals(40f, r.height, 0.001f)
    }

    @Test
    fun boxStaysInsideThePage() {
        val fit = RectPx(0f, 0f, 1000f, 2000f)
        val st = textStamp().copy(x = 0.999f)
        val r = textBoxRect(st, 900f, 40f, fit)
        assertTrue("right ${r.right} over the page", r.right <= fit.width && r.left >= 0f)
        assertTrue("top ${r.top} over the page", r.top >= 0f && r.bottom <= fit.height)
    }

    @Test
    fun textStampHitUsesMeasuredTextRect() {
        val fit = RectPx(0f, 0f, 1000f, 2000f)
        val stamp = textStamp()
        val rect = RectPx(100f, 300f, 300f, 40f)

        assertEquals(stamp.id, hitStamp(Offset(110f, 320f), listOf(stamp), fit, mapOf(stamp.id to rect))?.id)
        assertNull(hitStamp(Offset(490f, 1000f), listOf(stamp), fit, mapOf(stamp.id to rect)))
    }

    @Test
    fun sizeIsANormalizedShareOfThePage() {
        val fit = RectPx(0f, 0f, 1000f, 2000f)
        assertEquals(0.024f, textSizePxToFrac(48f, fit), 0.001f)
        assertEquals(MAX_TEXT_FRAC, textSizePxToFrac(999f, fit), 0.001f)
    }
}
