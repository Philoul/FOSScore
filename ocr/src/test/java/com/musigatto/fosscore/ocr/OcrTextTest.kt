package com.musigatto.fosscore.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrTextTest {

    /** A measure number, or the beats of a tempo mark: the letters a digit is often read as are taken back. */
    @Test
    fun digitsReadAsLettersAreDigits() {
        assertEquals("70", OcrText.clean("7O", TextKind.DIGITS))
        assertEquals("112", OcrText.clean("l|2", TextKind.DIGITS))
        assertEquals("58", OcrText.clean(" S8. ", TextKind.DIGITS))
        assertNull(OcrText.clean("abc", TextKind.DIGITS))
    }

    @Test
    fun aLineIsTrimmedAndItsSpacesCollapsed() {
        assertEquals("Allegro ma non troppo", OcrText.clean("  Allegro   ma non\ttroppo \n", TextKind.LINE))
        assertNull(OcrText.clean(" \n ", TextKind.LINE))
    }

    /** An ending's number as the score reader wants it: "1, 2." from "1. 2.", "1.2.", "1,2"; "4." from "4". */
    @Test
    fun anEndingsNumbersAreWrittenTheUsualWay() {
        assertEquals("1, 2.", OcrText.endingNumbers("1. 2."))
        assertEquals("1, 2.", OcrText.endingNumbers("1.2."))
        assertEquals("3.", OcrText.endingNumbers("3"))
        assertEquals("4.", OcrText.endingNumbers(" 4."))
        assertNull(OcrText.endingNumbers("To Coda"))
    }

    /** "= 70" after a note symbol: the number read whatever the spaces, and its digits taken back. */
    @Test
    fun theBeatsOfATempoMarkAreRead() {
        assertEquals(70, OcrText.perMinute("= 70"))
        assertEquals(70, OcrText.perMinute("=7O"))
        assertEquals(126, OcrText.perMinute("J = c. 126"))
        assertNull(OcrText.perMinute("Allegro"))
        assertNull(OcrText.perMinute("= 7"))
    }
}
