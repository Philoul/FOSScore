package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationBarTest {
    @Test
    fun aPhoneUprightTakesTwoRowsATabletOne() {
        assertFalse(annotationBarFitsOneRow(360f))
        assertTrue(annotationBarFitsOneRow(800f))
    }

    @Test
    fun theBarGoesDownToTheBottomOfTheScreen() {
        // a score shorter than the screen: the free room under it is where the bar is out of the way
        assertEquals(1000f, annotationBarLowest(pageHeightPx = 1000, bottomInsetPx = 0), 0f)
    }

    @Test
    fun theBarStaysAboveTheNavigationBar() {
        assertEquals(952f, annotationBarLowest(pageHeightPx = 1000, bottomInsetPx = 48), 0f)
    }

    @Test
    fun noRoomKeepsTheBarAtTheTop() {
        assertEquals(0f, annotationBarLowest(pageHeightPx = 0, bottomInsetPx = 48), 0f)
    }

    // a phone upright: 2340 px high, the status bar 81 px, the navigation bar 144 px
    private fun place(barTop: Float) =
        toolPanelPlace(barTopPx = barTop, barHeightPx = 300, screenHeightPx = 2340, topInsetPx = 81, bottomInsetPx = 144)

    @Test
    fun aBarAtTheTopOpensItsPanelsBelowItDownToTheNavigationBar() {
        assertEquals(PanelPlace(above = false, maxHeightPx = 2340 - 144 - 300), place(0f))
    }

    @Test
    fun aBarDraggedLowOpensItsPanelsAboveItUpToTheStatusBar() {
        assertEquals(PanelPlace(above = true, maxHeightPx = 1896 - 81), place(1896f))
    }

    @Test
    fun aBarInTheMiddleOpensItsPanelsOnTheRoomierSideNoTallerThanThatSide() {
        // 700 px above it (under the status bar), 1196 px below it: below, and never over a system bar
        assertEquals(PanelPlace(above = false, maxHeightPx = 1196), place(700f))
        assertEquals(PanelPlace(above = true, maxHeightPx = 1219), place(1300f))
    }

    @Test
    fun aBarAtTheTopIsDrawnAtTheTopOverTheStatusBar() {
        assertEquals(0, annotationBarOffsetPx(barDragY = 0f, statusBarPx = 81))
    }

    @Test
    fun aDraggedBarLeavesTheStatusBarStripBehindWithoutJumping() {
        // its buttons were 81 px down (under the status bar strip); dragged by 500 they are 581 px down
        assertEquals(581, annotationBarOffsetPx(barDragY = 500f, statusBarPx = 81))
    }

    @Test
    fun theKeyboardPushesTheBarUpNotOverIt() {
        // the bar parked at the bottom (dragged 1800 px, 81 px status bar, 120 px high): the
        // keyboard comes up to 1300 px, the bar goes just above it
        assertEquals(1099f, annotationBarDragOverKeyboard(1800f, 120, 81, keyboardTopPx = 1300f), 0f)
        // no keyboard, or a bar already above it: it stays where it was put
        assertEquals(1800f, annotationBarDragOverKeyboard(1800f, 120, 81, keyboardTopPx = null), 0f)
        assertEquals(300f, annotationBarDragOverKeyboard(300f, 120, 81, keyboardTopPx = 1300f), 0f)
        // at the top it stays at the top
        assertEquals(0f, annotationBarDragOverKeyboard(0f, 120, 81, keyboardTopPx = 1300f), 0f)
    }
}
