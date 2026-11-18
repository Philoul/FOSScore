package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.pdfscore.FollowPlace
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionPlacesTest {

    private val pages = mapOf(0 to PageSize(595.0, 842.0), 2 to PageSize(595.0, 842.0))
    private val places = mapOf(
        "fosscore-note-1-1-0-0" to FollowPlace(0, 120.5, 100.0, 90.0, 150.0, 6.5, 560.0),
        "fosscore-note-9-1-0-0" to FollowPlace(2, 80.0, 300.0, 280.0, 340.0, 6.5, 555.0),
    )

    /** The bar is drawn on the PDF where the music was read: each note on its page, with its system. */
    @Test
    fun theNotesAreFoundOnTheirPageAfterBeingKept() = runTest {
        val read = CompanionPlaces.read(CompanionPlaces.write(pages, places))
        val geo = read.geometry(2) ?: error("no page 3")
        assertEquals(595f, geo.width)
        assertEquals(842f, geo.height)
        val head = geo.head("fosscore-note-9-1-0-0") ?: error("no head")
        assertEquals(80f, head.x)
        assertEquals(300f, head.y)
        assertEquals(280f, head.top)
        assertEquals(340f, head.bottom)
        assertEquals(6.5f, head.space)
        assertEquals(555f, head.lineEnd)
        assertNull(geo.head("fosscore-note-1-1-0-0"))
        assertEquals(0, read.pageOf("fosscore-note-1-1-0-0"))
        assertEquals(2, read.pageOf("fosscore-note-9-1-0-0"))
        assertEquals(-1, read.pageOf("elsewhere"))
    }

    /** A page where no music was read (a title page) has nothing to follow. */
    @Test
    fun aPageWithoutMusicHasNoPlaces() {
        val read = CompanionPlaces.read(CompanionPlaces.write(pages, places))
        assertNull(read.geometry(1))
    }

    /** The places say which reader wrote them: a companion of an older reader is read again. */
    @Test
    fun thePlacesSayTheirReader() {
        val text = CompanionPlaces.write(pages, places, reader = 3)
        assertEquals(3, CompanionPlaces.readerOf(text))
        // the places themselves read the same
        assertEquals(120.5f, CompanionPlaces.read(text).geometry(0)?.head("fosscore-note-1-1-0-0")?.x)
        assertNull(CompanionPlaces.readerOf("fosscore-places 1\npage 0 595.00 842.00\n"))
        assertNull(CompanionPlaces.readerOf("something else"))
    }

    @Test
    fun unreadablePlacesAreNone() {
        assertNull(CompanionPlaces.read("something else").geometry(0))
    }
}
