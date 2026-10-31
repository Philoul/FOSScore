package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap of a tool in the annotation bar (issue #56): a tool is chosen and stays in hand, tapping the
 * one in hand opens or closes its options, and it is never turned off by a tap. It is the whole
 * state machine behind the arrow, the pen, the eraser and the stamp.
 */
class ToolTapTest {

    @Test
    fun `choosing a tool opens no options`() {
        assertFalse(nextToolTap(active = false, panelOpen = false))
    }

    @Test
    fun `tapping the tool in hand opens its options`() {
        assertTrue(nextToolTap(active = true, panelOpen = false))
    }

    @Test
    fun `tapping it again closes them and keeps the tool in hand`() {
        assertFalse(nextToolTap(active = true, panelOpen = true))
    }

    @Test
    fun `an unknown or missing saved name starts with the arrow`() {
        assertEquals(AnnotationTool.SELECT, annotationToolOf(null))
        assertEquals(AnnotationTool.SELECT, annotationToolOf("LASSO"))
    }

    @Test
    fun `a saved tool comes back as the same tool`() {
        AnnotationTool.entries.forEach { assertEquals(it, annotationToolOf(it.name)) }
    }
}
