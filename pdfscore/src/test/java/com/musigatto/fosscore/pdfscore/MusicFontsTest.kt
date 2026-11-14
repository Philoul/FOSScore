package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFontsTest {
    /** A font of no known name is music when its glyphs carry SMuFL code points (MuseScore's Gootville, MuseJazz…). */
    @Test
    fun aGlyphWithASmuflCodePointIsMusicWhateverItsFont() {
        assertTrue(MusicFonts.isMusic("ABCDEF+Gootville", "", 7))
        assertTrue(MusicFonts.isMusic("MuseJazz", null, 0xE0A4))
    }

    @Test
    fun aKnownMusicFontIsMusicWithoutCodePoints() {
        assertTrue(MusicFonts.isMusic("ABCDEF+Leland", null, 38))
    }

    @Test
    fun textIsNotMusic() {
        assertFalse(MusicFonts.isMusic("FreeSerif", "a", 97))
        assertFalse(MusicFonts.isMusic("FreeSerif", null, 3))
    }
}
