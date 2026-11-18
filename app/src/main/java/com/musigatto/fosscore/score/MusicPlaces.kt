package com.musigatto.fosscore.score

import com.musigatto.fosscore.musicxml.PageGeometry

/**
 * Where the music of a score is drawn, page by page: what the playback bar and the page turns
 * follow. A MusicXML score's are those of its layout; a PDF's, those its music was read from.
 */
interface MusicPlaces {
    /** Where the notes of page [index] are, or null (not known, or not drawn yet). */
    fun geometry(index: Int): PageGeometry?

    /** The page (0 up) the note [id] is on, or -1. */
    suspend fun pageOf(id: String): Int
}
