package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import org.junit.Assert.assertEquals
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
        val origin = stamp("cresc.")
        val d = draft("cresc.", origin = origin)
        assertEquals(TypingOutcome.Nothing, typingOutcome(d, draftStamp(d, 1, 60f, 30f, fit)))
    }

    @Test
    fun aNewTextHasTheMiddleOfItsFirstLineOnTheTap() {
        val corner = newTextCorner(Offset(0.3f, 0.5f), lineHeight = 40f, fit = fit)
        assertEquals(0.3f, corner.x, 0f)
        assertEquals(0.5f - 20f / 2000f, corner.y, 1e-6f)
    }
}
