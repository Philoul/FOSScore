package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sharing writes the marks into a copy of the PDF: a MusicXML score has no PDF to copy. */
class ShareMenuTest {

    @Test
    fun shareIsReadyOnAPdfAndNotOnAMusicXmlScore() {
        assertEquals(MenuItemState.READY, menuItemState(ViewerMenuItem.SHARE, isMusicXml = false))
        assertEquals(MenuItemState.NOT_ON_MUSICXML, menuItemState(ViewerMenuItem.SHARE, isMusicXml = true))
    }

    @Test
    fun theCopyIsNamedAfterTheScoreAndTheLayer() {
        assertEquals("Canción (Capa 2).pdf", shareFileName("Canción", "Capa 2"))
    }
}
