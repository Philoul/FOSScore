package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StampSymbolTest {
    @Test
    fun completeSetWithUniqueLabels() {
        // 41 con nombre traducido + 10 digitaciones que se anuncian con su propio número
        assertEquals(51, StampSymbol.entries.size)
        val named = StampSymbol.entries.filter { it.descRes != null }
        assertEquals(41, named.size)
        assertEquals(named.size, named.map { it.descRes }.toSet().size)

        // one glyph per stamp, all in SMuFL's private use area (a duplicated code point, or a
        // decimal that forgot its 0x, draws the wrong symbol in the palette)
        assertEquals(
            StampSymbol.entries.size,
            StampSymbol.entries.map { it.codepoint }.toSet().size
        )
        StampSymbol.entries.forEach {
            assertTrue("U+%04X".format(it.codepoint), it.codepoint in 0xE000..0xF8FF)
        }
    }

    @Test
    fun newSymbolsCarryTheirSmuflCodepoints() {
        // verified against the official SMuFL tables (holds-and-pauses, common-ornaments,
        // clefs, repeats, plucked-techniques, keyboard-techniques)
        assertEquals(0xE4CE, StampSymbol.BREATH_COMMA.codepoint)
        assertEquals(0xE567, StampSymbol.TURN.codepoint)
        assertEquals(0xE634, StampSymbol.ARPEGGIO_UP.codepoint)
        assertEquals(0xE635, StampSymbol.ARPEGGIO_DOWN.codepoint)
        assertEquals(0xE650, StampSymbol.PEDAL.codepoint)
        assertEquals(0xE655, StampSymbol.PEDAL_RELEASE.codepoint)
        assertEquals(0xE050, StampSymbol.TREBLE_CLEF.codepoint)
        assertEquals(0xE062, StampSymbol.BASS_CLEF.codepoint)
        assertEquals(0xE05C, StampSymbol.ALTO_CLEF.codepoint)
        assertEquals(0xE053, StampSymbol.TREBLE_CLEF_8VA.codepoint)
        assertEquals(0xE052, StampSymbol.TREBLE_CLEF_8VB.codepoint)
        assertEquals(0xE040, StampSymbol.REPEAT_START.codepoint)
        assertEquals(0xE041, StampSymbol.REPEAT_END.codepoint)
        assertEquals(0xE042, StampSymbol.REPEAT_START_END.codepoint)
    }

    @Test
    fun stampColorFallsBackToThemeColorWhenNull() {
        val fallback = Color.Red
        assertEquals(fallback, stampColor(null, fallback))
        assertEquals(Color(0xFF336699.toInt()), stampColor(0xFF336699.toInt(), fallback))
    }

    @Test
    fun invertedMatchesNegativeModeAndKeepsAlpha() {
        assertEquals(Color.White, Color.Black.inverted())
        assertEquals(Color.Black, Color.White.inverted())
        val half = Color(0x80FF0000.toInt()).inverted()
        assertEquals(Color(0x8000FFFF.toInt()).toArgb(), half.toArgb())
    }
}