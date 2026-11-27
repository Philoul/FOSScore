package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Onset
import com.musigatto.fosscore.musicxml.PageGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bar's way over a page is ready before a turn shows the page (maintainer, 2026-11-26: in
 * half page the bar stopped for half a second at each turn, while the ways of both pages shown
 * were worked out on the main thread).
 */
class BarPathsTest {
    private fun page(n: Int) = PageGeometry.of(
        600f, 800f,
        mapOf("fosscore-note-$n-1-0-0" to PageGeometry.Head(50f, 120f, 100f, 140f, 5f, 600f, 200f)),
    )

    private val pages = (0 until 5).map { page(it + 1) }
    private val built = mutableListOf<PageGeometry>()
    private val paths = BarPaths { geo ->
        built.add(geo)
        BarPath(listOf(Onset(0.0, geo.noteIds.toList())), listOf(0.0), geo)
    }

    @Test
    fun theNextPagesWaysAreReadyBeforeATurnShowsThem() = runBlocking {
        paths.prepare(1, pages.size, pages::getOrNull, Dispatchers.Unconfined)
        // this page, the next one (a turned half page shows it) and the one after (the turn)
        for (p in 1..3) assertNotNull("page $p", paths.of(pages[p]))
        // the nearest first
        assertEquals(listOf(pages[1], pages[2], pages[3], pages[0]), built.take(4))
    }

    @Test
    fun aWayIsWorkedOutOnceAndALayoutNoLongerShownIsDropped() = runBlocking {
        paths.prepare(0, pages.size, pages::getOrNull, Dispatchers.Unconfined)
        paths.prepare(1, pages.size, pages::getOrNull, Dispatchers.Unconfined)
        assertEquals(pages.size, built.size)
        // laid out again: new geometries, the old ways go
        val again = (0 until 5).map { page(it + 1) }
        paths.prepare(0, again.size, again::getOrNull, Dispatchers.Unconfined)
        assertNull(paths.of(pages[0]))
        assertNotNull(paths.of(again[0]))
    }

    @Test
    fun aPageNotLaidOutYetHasNoWay() = runBlocking {
        paths.prepare(0, pages.size, { p -> pages[p].takeIf { p != 1 } }, Dispatchers.Unconfined)
        assertNull(paths.of(null))
        assertNotNull(paths.of(pages[2]))
    }
}
