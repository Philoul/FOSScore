package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.LyricsPlacement
import com.musigatto.fosscore.musicxml.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerMenuViewModelTest {

    private class MemoryStore(var saved: ScoreLayout = ScoreLayout()) : ScoreLayoutStore {
        override fun load() = saved
        override fun save(layout: ScoreLayout) {
            saved = layout
        }
    }

    private val voice = Part("P1", "Voice")
    private val piano = Part("P2", "Piano")
    private val hiddenGuitar = Part("P3", "Guitar", visibleInFile = false)

    @Test
    fun theLayoutSavedForTheScoreIsTheOneShown() {
        val saved = ScoreLayout(musicSize = 80, semitones = -2, lyrics = LyricsPlacement.ABOVE)
        assertEquals(saved, ViewerMenuViewModel(MemoryStore(saved)).layout.value)
    }

    @Test
    fun everyChangeIsSavedAtOnce() {
        val store = MemoryStore()
        val vm = ViewerMenuViewModel(store)
        vm.setMusicSize(120)
        vm.transpose(3)
        vm.setLyrics(LyricsPlacement.BOTTOM)
        assertEquals(ScoreLayout(musicSize = 120, semitones = 3, lyrics = LyricsPlacement.BOTTOM), store.saved)
    }

    @Test
    fun musicSizeAndTranspositionStayInRange() {
        val vm = ViewerMenuViewModel(MemoryStore())
        vm.setMusicSize(10)
        assertEquals(MIN_MUSIC_SIZE, vm.layout.value.musicSize)
        vm.setMusicSize(500)
        assertEquals(MAX_MUSIC_SIZE, vm.layout.value.musicSize)
        vm.transpose(-20)
        assertEquals(-MAX_TRANSPOSE, vm.layout.value.semitones)
        vm.transpose(20)
        assertEquals(MAX_TRANSPOSE, vm.layout.value.semitones)
    }

    @Test
    fun aNewScoreShowsThePartsItsAuthorLeftVisible() {
        val parts = listOf(voice, piano, hiddenGuitar)
        val layout = ScoreLayout()
        assertEquals(setOf("P1", "P2"), layout.partsShown(parts))
        assertEquals(setOf("P1", "P2"), layout.layoutParts(parts))
    }

    @Test
    fun whenEveryPartIsShownTheModuleIsAskedForAllOfThem() {
        // an empty set is "all parts" for the musicxml module
        assertEquals(emptySet<String>(), ScoreLayout().layoutParts(listOf(voice, piano)))
    }

    @Test
    fun aScoreWithEveryPartHiddenInTheFileStillShowsThem() {
        val parts = listOf(hiddenGuitar, Part("P4", "Bass", visibleInFile = false))
        assertEquals(setOf("P3", "P4"), ScoreLayout().partsShown(parts))
    }

    @Test
    fun hidingAPartKeepsTheOthers() {
        val vm = ViewerMenuViewModel(MemoryStore())
        vm.onScoreRead(listOf(voice, piano, hiddenGuitar))
        vm.setPartShown("P2", false)
        assertEquals(setOf("P1"), vm.layout.value.shownParts)
        vm.setPartShown("P3", true)
        assertEquals(setOf("P1", "P3"), vm.layout.value.shownParts)
    }

    @Test
    fun theLastPartShownCannotBeHidden() {
        val vm = ViewerMenuViewModel(MemoryStore())
        vm.onScoreRead(listOf(voice, piano))
        vm.setPartShown("P1", false)
        vm.setPartShown("P2", false)
        assertEquals(setOf("P2"), vm.layout.value.partsShown(vm.parts.value))
    }

    @Test
    fun partsThatAreNoLongerInTheFileAreIgnored() {
        val layout = ScoreLayout(shownParts = setOf("P1", "gone"))
        assertEquals(setOf("P1"), layout.partsShown(listOf(voice, piano)))
    }

    @Test
    fun theMenuOpensOnePanelAtATime() {
        val vm = ViewerMenuViewModel(MemoryStore())
        assertEquals(ViewerSurface.NONE, vm.surface.value)
        vm.show(ViewerSurface.MENU)
        vm.show(ViewerSurface.LAYOUT)
        assertEquals(ViewerSurface.LAYOUT, vm.surface.value)
        vm.dismiss()
        assertEquals(ViewerSurface.NONE, vm.surface.value)
    }

    @Test
    fun onlyWhatWorksTodayCanBeChosen() {
        val ready = ViewerMenuItem.entries.filter {
            menuItemState(it, isMusicXml = true) == MenuItemState.READY
        }
        assertEquals(
            listOf(ViewerMenuItem.LIBRARY, ViewerMenuItem.DISPLAY, ViewerMenuItem.LAYOUT, ViewerMenuItem.METRONOME, ViewerMenuItem.PLAYBACK),
            ready,
        )
        assertEquals(MenuItemState.READY, menuItemState(ViewerMenuItem.METRONOME, isMusicXml = false))
        assertEquals(MenuItemState.SOON, menuItemState(ViewerMenuItem.TUNER, isMusicXml = true))
        assertEquals(MenuItemState.SOON, menuItemState(ViewerMenuItem.SETLIST, isMusicXml = false))
    }

    @Test
    fun theLayoutPanelIsForMusicXmlAndAnnotationsForPdf() {
        assertEquals(MenuItemState.MUSICXML_ONLY, menuItemState(ViewerMenuItem.LAYOUT, isMusicXml = false))
        // a PDF plays once it has a MIDI file attached (roadmap 3.2)
        assertEquals(MenuItemState.MUSICXML_ONLY, menuItemState(ViewerMenuItem.PLAYBACK, isMusicXml = false))
        assertEquals(MenuItemState.NOT_ON_MUSICXML, menuItemState(ViewerMenuItem.ANNOTATE, isMusicXml = true))
        assertEquals(MenuItemState.READY, menuItemState(ViewerMenuItem.ANNOTATE, isMusicXml = false))
    }

    @Test
    fun aPdfHasNoParts() {
        val vm = ViewerMenuViewModel(MemoryStore())
        assertEquals(emptyList<Part>(), vm.parts.value)
        assertNull(vm.layout.value.shownParts)
    }
}
