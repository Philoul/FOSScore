package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A PDF whose music is read can be shown as that music, laid out as a MusicXML score
 * (maintainer, 2026-11-20): the viewer then opens its companion.
 */
class ScoreViewTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theMusicXmlViewOfAPdfOpensItsMusicRead() {
        val companion = folder.newFile("abc.musicxml")
        assertEquals(companion, viewedFile("/scores/song.pdf", musicView = true, companion = companion))
        assertEquals(File("/scores/song.pdf"), viewedFile("/scores/song.pdf", musicView = false, companion = companion))
    }

    @Test
    fun withoutItsMusicReadAPdfIsShownAsItself() {
        val missing = File(folder.root, "none.musicxml")
        assertEquals(File("/scores/song.pdf"), viewedFile("/scores/song.pdf", musicView = true, companion = missing))
    }

    @Test
    fun aMusicXmlScoreIsAlwaysItself() {
        val companion = folder.newFile("abc.musicxml")
        assertEquals(File("/scores/song.musicxml"), viewedFile("/scores/song.musicxml", musicView = true, companion = companion))
    }
}
