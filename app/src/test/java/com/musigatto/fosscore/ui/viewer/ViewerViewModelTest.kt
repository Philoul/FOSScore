package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerViewModelTest {

    private class MemoryStore(var saved: DisplaySettings = DisplaySettings()) : DisplayStore {
        override fun load() = saved
        override fun save(settings: DisplaySettings) {
            saved = settings
        }
    }

    @Test
    fun theDisplaySavedIsTheOneShown() {
        val saved = DisplaySettings(twoUp = false, halfPage = true, dimPct = 30f, invert = true)
        assertEquals(saved, ViewerViewModel(MemoryStore(saved)).display.value)
    }

    @Test
    fun everyChoiceIsSavedAtOnce() {
        val store = MemoryStore()
        val vm = ViewerViewModel(store)
        vm.setTwoUp(false)
        vm.setHalfPage(true)
        vm.setInvert(true)
        vm.setKeepScreenOn(true)
        assertEquals(
            DisplaySettings(twoUp = false, halfPage = true, invert = true, keepScreenOn = true),
            store.saved,
        )
    }

    @Test
    fun theDimSliderIsSavedWhenItIsLetGo() {
        val store = MemoryStore()
        val vm = ViewerViewModel(store)
        vm.setDimPct(25f)
        assertEquals(25f, vm.display.value.dimPct)
        // still dragging: nothing written
        assertEquals(0f, store.saved.dimPct)
        vm.commitDimPct()
        assertEquals(25f, store.saved.dimPct)
    }

    @Test
    fun annotatingGoesBackToASinglePageWithoutForgettingTheReadingMode() {
        val store = MemoryStore(DisplaySettings(twoUp = true, halfPage = true))
        val vm = ViewerViewModel(store)
        vm.singlePage()
        assertFalse(vm.display.value.twoUp)
        assertFalse(vm.display.value.halfPage)
        // entering edit mode is not a choice of the user: it is not saved
        assertTrue(store.saved.twoUp)
        assertTrue(store.saved.halfPage)
        vm.restoreReadingMode()
        assertTrue(vm.display.value.twoUp)
        assertTrue(vm.display.value.halfPage)
    }

    @Test
    fun leavingEditModeKeepsWhatWasChosenInTheMeantime() {
        val store = MemoryStore(DisplaySettings(twoUp = true))
        val vm = ViewerViewModel(store)
        vm.singlePage()
        vm.setTwoUp(false)
        vm.restoreReadingMode()
        assertFalse(vm.display.value.twoUp)
    }
}
