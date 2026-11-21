package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.pdfscore.FollowPlace
import com.musigatto.fosscore.score.MusicXmlScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MusicXML view of a PDF is laid out like the PDF (maintainer, 2026-11-20): the same lines and
 * pages, the music the size it is on the PDF, unless a page of it would not fit the screen.
 */
class PdfLookTest {

    // an A4 page (595 × 842 pt), staff spaces of 7 pt: two systems of two staves, 60 pt apart
    private fun note(page: Int, top: Double, bottom: Double) = FollowPlace(page, 100.0, top + 7.0, top, bottom, 7.0, 540.0)

    private val places = CompanionPlaces.read(
        CompanionPlaces.write(
            mapOf(0 to PageSize(595.0, 842.0), 1 to PageSize(595.0, 842.0)),
            mapOf(
                "a" to note(0, 100.0, 188.0), "b" to note(0, 400.0, 488.0),
                "c" to note(1, 80.0, 168.0), "d" to note(1, 300.0, 388.0), "e" to note(1, 640.0, 728.0),
            ),
        )
    )

    @Test
    fun aPdfsLookIsMeasuredInStaffSpaces() {
        val look = places.look() ?: error("no look")
        assertEquals(85.0, look.width, 0.01)
        assertEquals(842 / 7.0, look.height, 0.01)
        // the fullest page: from the first system's top line (80) to the last one's bottom line (728)
        assertEquals(648 / 7.0, look.music, 0.01)
    }

    @Test
    fun aPdfWithoutMusicHasNoLook() {
        assertNull(CompanionPlaces.read(CompanionPlaces.write(mapOf(0 to PageSize(595.0, 842.0)), emptyMap())).look())
    }

    private val tablet = MusicXmlScreen(800, 1280, 800, 44, 88, 112, twoUp = false)
    private val parts = listOf(Part("P1", "Piano"))

    @Test
    fun theMusicXmlViewOfAPdfKeepsItsLinesAndPagesAndItsSize() {
        val look = PdfLook(width = 85.0, height = 120.3, music = 70.0)
        val spec = tablet.spec(ScoreLayout(), parts, look = look)
        assertTrue(spec.fileBreaks)
        // the PDF shown on the whole screen: 800 px for 85 spaces, a space of 9.4 px; Verovio's is
        // 18 px at a scale of 100
        assertEquals(52, spec.scale)
        // a score of its own is broken to fit the screen, at the screen's own size
        val own = tablet.spec(ScoreLayout(), parts)
        assertEquals(false, own.fileBreaks)
        assertEquals(44, own.scale)
    }

    /**
     * The notes as large as the PDF's when the PDF's page fits the screen (J'te l'dis quand même,
     * measured on the Tab 3, 2026-11-21): a space of 5.5 px on the PDF, 4.9 px in its MusicXML view,
     * shrunk for room Verovio did not take (its lines of music took 3 % less height than the PDF's,
     * not 20 % more).
     */
    @Test
    fun theNotesAreAsLargeAsThePdfsWhenItsPageFits() {
        // 842 × 1190 units, spaces of 5.85, the music from 144 to 1090
        val look = PdfLook(width = 842 / 5.85, height = 1190 / 5.85, music = 946 / 5.85)
        val spec = tablet.spec(ScoreLayout(), parts, look = look)
        // the PDF shown 800 px wide: a space of 5.56 px, 18 px at a scale of 100
        assertEquals(30, spec.scale)
    }

    @Test
    fun aPageOfThePdfAlwaysFitsOnTheScreen() {
        // a page full of music, the screen shorter than the PDF's page shape
        val look = PdfLook(width = 85.0, height = 120.3, music = 110.0)
        val wide = MusicXmlScreen(1280, 800, 1280, 44, 88, 112, twoUp = false)
        val spec = wide.spec(ScoreLayout(), parts, look = look)
        assertTrue(spec.fileBreaks)
        val needed = (110.0 * PDF_MUSIC_ROOM + PDF_MUSIC_AIR) * 18 * spec.scale / 100 + 0.8 * spec.scale
        assertTrue("$needed px for ${800 - 88 - 112}", needed <= 800 - 88 - 112)
    }

    @Test
    fun aLargerSizeOrAnnotatingBreaksTheMusicAgain() {
        val look = PdfLook(width = 85.0, height = 120.3, music = 70.0)
        // larger than the PDF: its lines no longer fit, the music is broken to fit the screen
        val larger = tablet.spec(ScoreLayout(musicSize = 120), parts, look = look)
        assertEquals(false, larger.fileBreaks)
        assertEquals(52 * 120 / 100, larger.scale)
        // smaller: the same lines and pages, the music smaller
        val smaller = tablet.spec(ScoreLayout(musicSize = 80), parts, look = look)
        assertEquals(true, smaller.fileBreaks)
        assertEquals(52 * 80 / 100, smaller.scale)
        // annotating spaces the lines out: they would not fit the PDF's pages
        assertEquals(false, tablet.spec(ScoreLayout(), parts, annotating = true, look = look).fileBreaks)
    }
}
