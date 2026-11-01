package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationBarDragTest {
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
}
