package com.musigatto.fosscore.library

import kotlinx.coroutines.flow.Flow

/**
 * Where the viewer reads and writes the stamps of a page, in the page's coordinates. A PDF keeps
 * them on its pages ([StampRepository]); a MusicXML score hangs them on its measures, so they
 * follow the music when it is laid out again (score.MusicXmlMarks). The editing code (stamps,
 * texts, selection, undo) is the same for both.
 */
interface StampStore {
    /**
     * The stamps of [pages], by page. The viewer asks for the page it shows plus its neighbours:
     * by the time the page is turned, its stamps are already in hand.
     */
    fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stamp>>>

    /** The new row's id; -1 when the stamp has nowhere to go (off the music of a MusicXML page). */
    suspend fun insert(stamp: Stamp): Long
    suspend fun update(stamp: Stamp)
    suspend fun delete(stamp: Stamp)
    suspend fun deleteAll(stamps: List<Stamp>)

    /** Puts a page back from an undo snapshot. */
    suspend fun restorePage(sheetId: Long, page: Int, stamps: List<Stamp>)
}

/** The same as [StampStore], for the ink. */
interface StrokeStore {
    fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stroke>>>

    /** The new row's id; -1 when the stroke has nowhere to go (off the music of a MusicXML page). */
    suspend fun insert(stroke: Stroke): Long
    suspend fun insertAll(strokes: List<Stroke>)
    suspend fun update(stroke: Stroke)
    suspend fun updateAll(strokes: List<Stroke>)
    suspend fun delete(stroke: Stroke)
    suspend fun deleteAll(strokes: List<Stroke>)

    /** Puts a page back from an undo snapshot. */
    suspend fun restorePage(sheetId: Long, page: Int, strokes: List<Stroke>)
}
