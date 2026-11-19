package com.musigatto.fosscore.score

import com.musigatto.fosscore.musicxml.LayoutSpec
import com.musigatto.fosscore.musicxml.MUSIC_CACHE_VERSION
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCacheNameTest {
    /**
     * The pages kept of a layout name their notes as the music played names them: when the music
     * is made differently (its cache version goes up), the pages are drawn again. Pages kept from
     * before the notes were named left the bar without the notes played (Je suis malade, MusicXML,
     * 2026-11-19: no bar on page 1, then on page 2 only, drawn again).
     */
    @Test
    fun thePagesKeptFollowTheMusicsVersion() {
        val name = MusicXmlPages.pageCacheName(LayoutSpec(1200, 1700))
        assertTrue(name, name.endsWith(".$MUSIC_CACHE_VERSION"))
    }
}
