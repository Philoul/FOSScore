package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.musigatto.fosscore.library.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typing a text in place (#35): where the typed text lands, and what the end of the typing writes. */
class TextTypingTest {
    private val fit = RectPx(0f, 0f, 1000f, 2000f)

    private fun draft(text: String, origin: Stamp? = null, left: Float = 0.2f, top: Float = 0.1f) =
        TextDraft(page = 3, origin = origin, text = text, size = 0.012f, color = null, left = left, top = top)

    private fun stamp(text: String) = Stamp(
        id = 7, sheetId = 1, page = 3, symbol = Stamp.TEXT_SYMBOL, x = 0.5f, y = 0.5f, size = 0.012f, text = text,
    )

    @Test
    fun theTextStartsWhereItWasTappedAndGrowsToTheRight() {
        // the editor of 813d8ce opened at the tap but the text landed centred on it: here the box's
        // top left corner is the place of the draft, whatever the length of the text
        val short = draftStamp(draft("ab"), sheetId = 1, width = 40f, height = 30f, fit = fit)
        val long = draftStamp(draft("abcdef"), sheetId = 1, width = 120f, height = 30f, fit = fit)
        assertEquals(200f, short.x * fit.width - 20f, 0.01f)
        assertEquals(200f, long.x * fit.width - 60f, 0.01f)
        assertEquals(200f, short.y * fit.height - 15f, 0.01f)
    }

    @Test
    fun aTextNearTheEdgeStaysOnThePage() {
        val near = draftStamp(draft("abc", left = 0.95f), sheetId = 1, width = 200f, height = 30f, fit = fit)
        assertEquals(1000f, near.x * fit.width + 100f, 0.01f)
    }

    @Test
    fun aNewTextIsAStampOfTheSheetAndThePage() {
        val typed = draftStamp(draft("la"), sheetId = 9, width = 40f, height = 30f, fit = fit)
        assertEquals(9L, typed.sheetId)
        assertEquals(3, typed.page)
        assertEquals(Stamp.TEXT_SYMBOL, typed.symbol)
        assertEquals("la", typed.text)
    }

    @Test
    fun aNewTextLeftEmptyWritesNothing() {
        val d = draft("  \n")
        assertEquals(TypingOutcome.Nothing, typingOutcome(d, draftStamp(d, 1, 10f, 30f, fit)))
    }

    @Test
    fun aNewTextIsInsertedWithoutTheBlanksAtItsEnd() {
        val d = draft("Rit. \n")
        val outcome = typingOutcome(d, draftStamp(d, 1, 60f, 60f, fit))
        assertEquals("Rit.", (outcome as TypingOutcome.Insert).stamp.text)
    }

    @Test
    fun aTextEmptiedGoes() {
        val origin = stamp("cresc.")
        val d = draft("", origin = origin)
        assertEquals(TypingOutcome.Delete(origin), typingOutcome(d, draftStamp(d, 1, 10f, 30f, fit)))
    }

    @Test
    fun aTextChangedIsTheSameRow() {
        val origin = stamp("cresc.")
        val d = draft("cresc. poco", origin = origin)
        val outcome = typingOutcome(d, draftStamp(d, 1, 120f, 30f, fit)) as TypingOutcome.Update
        assertEquals(7L, outcome.stamp.id)
        assertEquals("cresc. poco", outcome.stamp.text)
    }

    @Test
    fun aTextOpenedAndLeftAsItWasWritesNothing() {
        // the origin sits where the draft's box is: opening a text and pressing Done changes nothing
        val origin = stamp("cresc.").copy(x = 230f / 1000f, y = 215f / 2000f)
        val d = draft("cresc.", origin = origin)
        assertEquals(TypingOutcome.Nothing, typingOutcome(d, draftStamp(d, 1, 60f, 30f, fit)))
    }

    @Test
    fun aTextMovedByItsHandleWritesTheNewPlace() {
        val origin = stamp("cresc.")
        // the draft starts where the text is, then the left handle moves it
        val d = draft("cresc.", origin = origin).copy(left = 0.5f)
        assertTrue(typingOutcome(d, draftStamp(d, 1, 60f, 30f, fit)) is TypingOutcome.Update)
    }

    @Test
    fun aTextKeepsItsWidthTypefaceAndWeight() {
        val d = draft("a longer line").copy(width = 0.6f, font = TextFont.SERIF, bold = true, italic = true)
        val typed = draftStamp(d, 1, textWrapWidthPx(0.6f, fit), 30f, fit)
        assertEquals(0.6f, typed.textWidth)
        assertEquals("SERIF", typed.font)
        assertTrue(typed.bold)
        assertTrue(typed.italic)
    }

    @Test
    fun anOldTextKeepsWrappingAtThePageWidth() {
        // texts from before widths existed: the same wrap the editor always used
        assertEquals(1000f * TEXT_MAX_WRAP - TEXT_WRAP_PAD, textWrapWidthPx(null, fit), 0f)
        // a text with its own width wraps there, and its box is that width
        assertEquals(450f, textWrapWidthPx(0.45f, fit), 0f)
        assertEquals(450f, textBoxWidthPx(0.45f, measuredW = 200f, wrapW = 450f), 0f)
        assertEquals(200f, textBoxWidthPx(null, measuredW = 200f, wrapW = 450f), 0f)
    }

    @Test
    fun theRightHandleChangesTheWidthWithTheLeftEdgeFixed() {
        val d = draft("x").copy(width = 0.4f)
        // 400 px wide, dragged 100 px to the right: 500 px, left still at 0.2 of the page
        assertEquals(0.5f, resizedTextWidth(d, boxW = 400f, dx = 100f, fit = fit), 1e-6f)
        // it stops at the page's right edge
        assertEquals(0.8f, resizedTextWidth(d, boxW = 400f, dx = 4000f, fit = fit), 1e-6f)
        // and never collapses under the minimum
        assertEquals(MIN_TEXT_WIDTH, resizedTextWidth(d, boxW = 400f, dx = -4000f, fit = fit), 1e-6f)
    }

    @Test
    fun movingTheLeftHandleMovesTheWholeBoxInsideThePage() {
        val d = draft("x").copy(left = 0.2f, top = 0.1f)
        val moved = movedTextCorner(d, dx = 100f, dy = 50f, boxW = 100f, boxH = 30f, fit = fit)
        assertEquals(0.3f, moved.x, 1e-6f)
        assertEquals(0.125f, moved.y, 1e-6f)
        // it stops at the top left instead of leaving the sheet
        val stuck = movedTextCorner(d, dx = -500f, dy = -500f, boxW = 100f, boxH = 30f, fit = fit)
        assertEquals(0f, stuck.x, 0f)
        assertEquals(0f, stuck.y, 0f)
    }

    @Test
    fun aNewTextHasTheMiddleOfItsFirstLineOnTheTap() {
        val corner = newTextCorner(Offset(0.3f, 0.5f), lineHeight = 40f, fit = fit)
        assertEquals(0.3f, corner.x, 0f)
        assertEquals(0.5f - 20f / 2000f, corner.y, 1e-6f)
    }

    @Test
    fun theLinesOfATextAreAsFarApartAsItsFontIsBig() {
        // the theme's style: 16 sp letters on 24 sp lines, whatever their size
        val theme = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
        val density = Density(2f)
        val big = textStampStyle(theme, fontPx = 192f, density = density)
        val small = textStampStyle(theme, fontPx = 24f, density = density)
        assertEquals(96f, big.fontSize.value, 0f)
        // a line of fingering 96 sp high was 24 sp apart from the next one
        assertEquals(TEXT_LINE_EM.em, big.lineHeight)
        assertEquals(TEXT_LINE_EM.em, small.lineHeight)
    }

    @Test
    fun theTextPanelShowsTheTextBeingEdited() {
        val edited = draft("pp", origin = stamp("pp")).copy(size = 0.03f, color = 0xFFFF0000.toInt())
        // 0.03 of a page 2000 px high: 60 px, red, not the 24 px black of the next new text
        assertEquals(
            TextPanelValues(60f, 0xFFFF0000.toInt(), TextFont.SANS, false, false),
            textPanelValues(edited, 24f, null, TextFont.SERIF, true, true, fit),
        )
        // a new text being typed shows its own too (the panel changed it)
        assertEquals(
            TextPanelValues(24f, null, TextFont.SANS, false, false),
            textPanelValues(draft("").copy(size = 0.012f), 40f, 7, TextFont.SERIF, true, true, fit),
        )
        // nothing typed: the next new text's
        assertEquals(
            TextPanelValues(40f, 7, TextFont.SERIF, true, true),
            textPanelValues(null, 40f, 7, TextFont.SERIF, true, true, fit),
        )
    }

    @Test
    fun theTypefaceReachesTheTextStyle() {
        val serif = textStampStyle(
            TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            fontPx = 24f,
            density = Density(2f),
            font = TextFont.MONO,
            bold = true,
            italic = true,
        )
        assertEquals(TextFont.MONO.family, serif.fontFamily)
        assertEquals(FontWeight.Bold, serif.fontWeight)
        assertEquals(FontStyle.Italic, serif.fontStyle)
    }
}
