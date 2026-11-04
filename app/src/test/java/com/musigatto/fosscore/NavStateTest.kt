package com.musigatto.fosscore

import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.ui.viewer.StartPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The views of the side menu replace one another: back never lands on a hidden one. */
class NavStateTest {
    private val score = Sheet(id = 1L, fileName = "a.pdf", path = "/a.pdf", hash = "h", title = "A",
        composer = "", genre = "", musicalKey = "", tags = "",
    )

    @Test
    fun setlistsFromTheLibraryOverAScoreReplaceIt() {
        // the library opened over a score, then Setlists in its menu: back must go to the score,
        // not close a library hidden under the setlists
        val nav = NavState(sheet = score, libraryOpen = true).toSetlists()

        assertTrue(nav.setlistsOpen)
        assertFalse(nav.libraryOpen)
        assertEquals(score, nav.sheet)
    }

    @Test
    fun libraryFromTheSetlistsReplacesThem() {
        val nav = NavState(sheet = score, setlistsOpen = true, setlistShown = 3).toLibrary()

        assertFalse(nav.setlistsOpen)
        assertTrue(nav.libraryOpen)
        assertEquals(score, nav.sheet)
    }

    @Test
    fun turningToTheNextScoreOfASetlistKeepsTheTopBarAsItWas() {
        val next = score.copy(id = 2L, hash = "h2")
        val playing = NavState(sheet = score, playSetlist = 3, playItem = 9)

        assertTrue(playing.toPlace(next, itemId = 10, start = StartPage.FIRST, chromeShown = true).chromeShown)
        assertFalse(playing.toPlace(next, itemId = 10, start = StartPage.FIRST, chromeShown = false).chromeShown)
    }

    @Test
    fun backFromAScoreOfASetlistGoesBackToTheSetlist() {
        val nav = NavState(sheet = score, playSetlist = 3, playItem = 9).leaveScore()

        assertEquals(null, nav.sheet)
        assertTrue(nav.setlistsOpen)
        assertEquals(3L, nav.setlistShown)
        // the place just left stays marked in the setlist
        assertEquals(9L, nav.playItem)
    }

    @Test
    fun backFromAScoreOfTheLibraryGoesBackToTheLibrary() {
        val nav = NavState(sheet = score, libraryOpen = true).leaveScore()

        assertEquals(null, nav.sheet)
        assertFalse(nav.setlistsOpen)
        assertFalse(nav.libraryOpen)
    }

    @Test
    fun setlistsFromAScoreShowItsSetlist() {
        val nav = NavState(sheet = score, playSetlist = 3, playItem = 9).toSetlists(3)

        assertTrue(nav.setlistsOpen)
        assertEquals(3L, nav.setlistShown)
        assertEquals(9L, nav.playItem)
    }
}
