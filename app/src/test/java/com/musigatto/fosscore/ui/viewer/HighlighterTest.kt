package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.library.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The highlighter is ink with its own kind: selected/erased like ink, never a shape or a word. */
class HighlighterTest {
    private val fit = RectPx(0f, 0f, 1000f, 1000f)

    private fun highlight(group: Long, y: Float) = Stroke(
        sheetId = 1,
        page = 0,
        points = "0.1;${y / 1000f}|0.9;${y / 1000f}",
        width = 0.02f,
        kind = Stroke.HIGHLIGHTER,
        groupId = group,
    )

    @Test
    fun aHighlighterIsInkAndNeverAShape() {
        assertFalse(Stroke.isShape(Stroke.HIGHLIGHTER))
        assertTrue(Stroke.isHighlight(Stroke.HIGHLIGHTER))
        assertFalse(Stroke.isHighlight(Stroke.INK))
    }

    @Test
    fun twoHighlightsDrawnNextToEachOtherAreTwoObjects() {
        // each pass gets its own group, so the geometric word rule cannot join them
        val groups = groupInk(listOf(highlight(1, 100f), highlight(2, 110f)), fit)
        assertEquals(2, groups.size)
    }
}
