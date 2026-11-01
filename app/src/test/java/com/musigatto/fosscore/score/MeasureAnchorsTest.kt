package com.musigatto.fosscore.score

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.ui.viewer.decodePoints
import com.musigatto.fosscore.ui.viewer.encodePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureAnchorsTest {

    // measure 12: two staves of a piano part, side by side with measure 13
    private val page = listOf(
        StaffSlot(12, 1, left = 0.1f, right = 0.5f, top = 0.2f, bottom = 0.24f, spaceX = 0.0125f, spaceY = 0.01f),
        StaffSlot(12, 2, left = 0.1f, right = 0.5f, top = 0.3f, bottom = 0.34f, spaceX = 0.0125f, spaceY = 0.01f),
        StaffSlot(13, 1, left = 0.5f, right = 0.9f, top = 0.2f, bottom = 0.24f, spaceX = 0.0125f, spaceY = 0.01f),
    )

    // the music twice as big, measure 12 now at the top of the next page
    private val bigger = listOf(
        StaffSlot(12, 1, left = 0.05f, right = 0.85f, top = 0.1f, bottom = 0.18f, spaceX = 0.025f, spaceY = 0.02f),
    )

    private fun assertClose(expected: List<Offset>, actual: List<Offset>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.x, a.x, 1e-4f)
            assertEquals(e.y, a.y, 1e-4f)
        }
    }

    @Test
    fun aMarkBelongsToTheStaffItIsOver() {
        assertEquals(12 to 1, MeasureAnchors.slotAt(Offset(0.3f, 0.19f), page)?.let { it.measure to it.staff })
        assertEquals(12 to 2, MeasureAnchors.slotAt(Offset(0.3f, 0.29f), page)?.let { it.measure to it.staff })
        assertEquals(13 to 1, MeasureAnchors.slotAt(Offset(0.7f, 0.25f), page)?.let { it.measure to it.staff })
        // past the right end of the last measure: the nearest one across
        assertEquals(13, MeasureAnchors.slotAt(Offset(0.95f, 0.2f), page)?.measure)
        assertNull(MeasureAnchors.slotAt(Offset(0.5f, 0.5f), emptyList()))
    }

    // two lines of music: measure 1 indented (the instrument names), measure 2 below
    private val twoLines = listOf(
        StaffSlot(1, 1, left = 0.2f, right = 0.9f, top = 0.1f, bottom = 0.14f, spaceX = 0.0125f, spaceY = 0.01f),
        StaffSlot(2, 1, left = 0.05f, right = 0.9f, top = 0.3f, bottom = 0.34f, spaceX = 0.0125f, spaceY = 0.01f),
    )

    @Test
    fun aMarkBelongsToTheLineOfMusicOnItsSideOfTheBoundary() {
        assertEquals(0.22f, MeasureAnchors.boundaries(twoLines).single(), 1e-6f)
        // just above the boundary: under measure 1, not over measure 2
        assertEquals(1, MeasureAnchors.slotAt(Offset(0.5f, 0.21f), twoLines)?.measure)
        assertEquals(2, MeasureAnchors.slotAt(Offset(0.5f, 0.23f), twoLines)?.measure)
        // right of the indent of measure 1, below its staff: still the line above
        assertEquals(1, MeasureAnchors.slotAt(Offset(0.3f, 0.16f), twoLines)?.measure)
        // the staves of one measure make one line, and so do measures side by side
        assertEquals(emptyList<Float>(), MeasureAnchors.boundaries(page))
    }

    @Test
    fun noMarkGoesInTheMarginOfALine() {
        val margins = MeasureAnchors.margins(twoLines)
        assertEquals(listOf(LineMargin(0f, 0.22f, 0.2f), LineMargin(0.22f, 1f, 0.05f)), margins)
        // the instrument names of the first line, left of its indented staves
        assertTrue(MeasureAnchors.inMargin(Offset(0.1f, 0.12f), margins))
        assertNull(MeasureAnchors.slotAt(Offset(0.1f, 0.12f), twoLines))
        // the same distance from the left on the second line is over its music
        assertFalse(MeasureAnchors.inMargin(Offset(0.1f, 0.32f), margins))
        assertEquals(2, MeasureAnchors.slotAt(Offset(0.1f, 0.32f), twoLines)?.measure)
    }

    @Test
    fun aStrokeComesBackWhereItWasDrawn() {
        val points = listOf(Offset(0.2f, 0.18f), Offset(0.25f, 0.22f), Offset(0.3f, 0.19f))
        val stroke = Stroke(id = 7, sheetId = 1, page = 2, points = encodePoints(points), width = 0.002f, color = 0xFF0000FF.toInt())
        val anchored = MeasureAnchors.anchor(stroke, page)!!
        assertEquals(12, anchored.measure)
        assertEquals(1, anchored.staff)
        assertEquals(ANCHORED_PAGE, anchored.page)
        // the corner: a quarter of the measure's music, two staff spaces above its top line
        val corner = decodePoints(anchored.points).first()
        assertEquals(0.25f, corner.x, 1e-4f)
        assertEquals(-2f, corner.y, 1e-4f)
        assertEquals(0.2f, anchored.width, 1e-4f)
        val back = MeasureAnchors.project(anchored, page, 2)!!
        assertEquals(7L, back.id)
        assertEquals(2, back.page)
        assertEquals(0.002f, back.width, 1e-5f)
        assertEquals(stroke.color, back.color)
        assertClose(points, decodePoints(back.points))
    }

    @Test
    fun aStrokeFollowsItsMeasureToAnotherLayout() {
        val points = listOf(Offset(0.2f, 0.21f), Offset(0.3f, 0.21f))
        val anchored = MeasureAnchors.anchor(Stroke(id = 1, sheetId = 1, page = 0, points = encodePoints(points), width = 0.002f), page)!!
        val moved = MeasureAnchors.project(anchored, bigger, 1)!!
        assertEquals(1, moved.page)
        assertEquals(0.004f, moved.width, 1e-5f)
        // the same place in the measure, the shape twice as big
        assertClose(listOf(Offset(0.25f, 0.12f), Offset(0.45f, 0.12f)), decodePoints(moved.points))
        // a page without measure 12 does not show it
        assertNull(MeasureAnchors.project(anchored, page.filter { it.measure == 13 }, 0))
    }

    @Test
    fun aStampFollowsItsMeasureAndKeepsItsSizeInStaffSpaces() {
        val stamp = Stamp(id = 3, sheetId = 1, page = 0, symbol = "FERMATA", x = 0.3f, y = 0.18f, size = 0.03f)
        val anchored = MeasureAnchors.anchor(stamp, page)!!
        assertEquals(12, anchored.measure)
        assertEquals(0.5f, anchored.x, 1e-4f)
        assertEquals(3f, anchored.size, 1e-4f)
        val back = MeasureAnchors.project(anchored, page, 0)!!
        assertEquals(0.3f, back.x, 1e-4f)
        assertEquals(0.18f, back.y, 1e-4f)
        assertEquals("FERMATA", back.symbol)
        val moved = MeasureAnchors.project(anchored, bigger, 1)!!
        assertEquals(0.45f, moved.x, 1e-4f)
        assertEquals(0.06f, moved.size, 1e-4f)
    }

    @Test
    fun aTextFollowsItsMeasureWithItsWords() {
        val text = Stamp(id = 4, sheetId = 1, page = 0, symbol = Stamp.TEXT_SYMBOL, x = 0.3f, y = 0.18f, size = 0.012f, text = "dolce")
        val moved = MeasureAnchors.project(MeasureAnchors.anchor(text, page)!!, bigger, 1)!!
        assertEquals("dolce", moved.text)
        assertEquals(0.024f, moved.size, 1e-5f)
    }

    @Test
    fun aMarkStaysOverItsNoteWhenTheMeasureBeginsALine() {
        // measure 12 in the middle of a line: its first note just after the bar line
        val inLine = listOf(StaffSlot(12, 1, left = 0.1f, right = 0.5f, top = 0.2f, bottom = 0.24f, spaceX = 0.0125f, spaceY = 0.01f, start = 0.12f))
        // then at the start of a line: the clef and the key come before its first note
        val lineStart = listOf(StaffSlot(12, 1, left = 0.05f, right = 0.85f, top = 0.1f, bottom = 0.14f, spaceX = 0.0125f, spaceY = 0.01f, start = 0.2f))
        val onFirstNote = MeasureAnchors.anchor(Stamp(id = 1, sheetId = 1, page = 0, symbol = "ONE", x = 0.12f, y = 0.19f, size = 0.02f), inLine)!!
        assertEquals(0.2f, MeasureAnchors.project(onFirstNote, lineStart, 0)!!.x, 1e-4f)
        val halfway = MeasureAnchors.anchor(Stamp(id = 2, sheetId = 1, page = 0, symbol = "THREE", x = 0.31f, y = 0.19f, size = 0.02f), inLine)!!
        assertEquals(0.525f, MeasureAnchors.project(halfway, lineStart, 0)!!.x, 1e-4f)
    }

    @Test
    fun aMeasureWithoutAWholeNumberHasNoMark() {
        val geometry = com.musigatto.fosscore.musicxml.PageGeometry.parse(
            """<svg viewBox="0 0 2100 2970"><svg class="definition-scale" color="black" viewBox="0 0 21000 29700">""" +
                """<g class="page-margin" transform="translate(400, 400)"><g class="system">""" +
                """<g id="m1" class="measure" data-n="12a"><g id="s1" class="staff" data-n="1"><path d="M1000 500 L3000 500" stroke-width="13"/><path d="M1000 680 L3000 680" stroke-width="13"/></g></g>""" +
                """<g id="m2" class="measure" data-n="13"><g id="s2" class="staff" data-n="1"><path d="M3000 500 L5000 500" stroke-width="13"/><path d="M3000 680 L5000 680" stroke-width="13"/></g></g>""" +
                """</g></g></svg></svg>"""
        )
        assertEquals(listOf(13), MeasureAnchors.slotsOf(geometry).map { it.measure })
    }
}
