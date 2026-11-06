package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.library.Stroke
import org.junit.Assert.assertEquals
import org.junit.Test

/** Preparing ink for drawing skips the strokes that did not change: pages with lots of ink. */
class InkDrawCacheTest {

    private val a = Stroke(id = 1, sheetId = 1, page = 0, points = "0.1,0.1|0.2,0.2", width = 0.01f)
    private val b = Stroke(id = 2, sheetId = 1, page = 0, points = "0.3,0.3|0.4,0.4", width = 0.01f)

    @Test
    fun onlyTheChangedStrokeIsBuiltAgain() {
        val built = mutableListOf<Long>()
        val cache = HashMap<Long, Pair<Stroke, String>>()
        fun draws(strokes: List<Stroke>) = reuse(strokes, cache) { built += it.id; it.points }

        assertEquals(listOf(a.points, b.points), draws(listOf(a, b)))
        assertEquals(listOf(a.points, b.copy(points = "0.5,0.5").points), draws(listOf(a, b.copy(points = "0.5,0.5"))))

        assertEquals(listOf(1L, 2L, 2L), built)
    }

    @Test
    fun aStrokesDrawingDoesNotAccumulateAfterItIsGone() {
        val cache = HashMap<Long, Pair<Stroke, String>>()
        reuse(listOf(a, b), cache) { it.points }
        reuse(listOf(a), cache) { it.points }
        assertEquals(setOf(1L), cache.keys)
    }
}
