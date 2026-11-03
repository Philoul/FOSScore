package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerIconBarTest {

    @Test
    fun theBarHasTheEntriesThatWorkInTheMenusOrderWithAnnotateInItsPlace() {
        assertEquals(
            listOf(
                ViewerMenuItem.LIBRARY, ViewerMenuItem.DISPLAY, ViewerMenuItem.LAYOUT,
                ViewerMenuItem.METRONOME, ViewerMenuItem.PLAYBACK, ViewerMenuItem.ANNOTATE, ViewerMenuItem.SETTINGS,
            ),
            barItems(isMusicXml = true, annotatable = true),
        )
        // a PDF has no layout nor playback of its own (they are MusicXML's)
        assertEquals(
            listOf(ViewerMenuItem.LIBRARY, ViewerMenuItem.DISPLAY, ViewerMenuItem.METRONOME, ViewerMenuItem.ANNOTATE, ViewerMenuItem.SETTINGS),
            barItems(isMusicXml = false, annotatable = true),
        )
    }

    @Test
    fun noPencilOnAScoreThatCannotBeAnnotated() {
        assertTrue(ViewerMenuItem.ANNOTATE !in barItems(isMusicXml = false, annotatable = false))
    }

    @Test
    fun onANarrowScreenTheFirstIconsShowThePencilAlwaysAmongThem() {
        val items = barItems(isMusicXml = true, annotatable = true)
        assertEquals(items, itemsThatFit(items, fits = 7))
        assertEquals(
            listOf(ViewerMenuItem.LIBRARY, ViewerMenuItem.DISPLAY, ViewerMenuItem.LAYOUT, ViewerMenuItem.ANNOTATE),
            itemsThatFit(items, fits = 4),
        )
        assertEquals(listOf(ViewerMenuItem.ANNOTATE), itemsThatFit(items, fits = 1))
        assertEquals(listOf(ViewerMenuItem.ANNOTATE), itemsThatFit(items, fits = 0))
        // without the pencil, every place goes to the first entries
        val pdf = barItems(isMusicXml = false, annotatable = false)
        assertEquals(listOf(ViewerMenuItem.LIBRARY, ViewerMenuItem.DISPLAY), itemsThatFit(pdf, fits = 2))
    }

    @Test
    fun theMenuIsTheDefaultLook() {
        assertEquals(ChromeStyle.MENU, ChromeStyle.of(null))
        assertEquals(ChromeStyle.MENU, ChromeStyle.of("SOMETHING_ELSE"))
        assertEquals(ChromeStyle.ICONS, ChromeStyle.of("ICONS"))
    }
}
