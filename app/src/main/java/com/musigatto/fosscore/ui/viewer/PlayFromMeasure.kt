package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Onset
import com.musigatto.fosscore.musicxml.PageGeometry

// the measure in a note's id: "fosscore-note-<measure>-..." (a PDF read), "fosscore-n<part>-<measure>-..." (a MusicXML)
private val MEASURE_OF_NOTE = Regex("""^fosscore-note-(\d+)-|^fosscore-n\d+-(\d+)-""")

/** The measure a note's id names, or null for an id that names none. */
internal fun measureKey(noteId: String): String? =
    MEASURE_OF_NOTE.find(noteId)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }

/**
 * Where the music starts for a long press at ([x], [y]) on a page laid out as [geometry] (its own
 * units): the measure under the finger is the one of its line (a little over and under the line's
 * staves) whose end is the first right of the finger; the music starts the first time that
 * measure is played ([onsets], in the order played). Null when the finger is on no line of music.
 */
internal fun measureStartAt(geometry: PageGeometry, x: Float, y: Float, onsets: List<Onset>): Double? {
    val measure = geometry.noteIds.mapNotNull { id -> geometry.head(id)?.let { id to it } }
        .filter { (_, h) -> y >= h.top - LINE_MARGIN * h.space && y <= h.bottom + LINE_MARGIN * h.space && h.measureEnd >= x }
        .minByOrNull { (_, h) -> h.measureEnd }
        ?.let { (id, _) -> measureKey(id) } ?: return null
    return onsets.firstOrNull { o -> o.notes.any { measureKey(it) == measure } }?.quarters
}

// how far over and under its staves (in spaces) a press is still on a line of music
private const val LINE_MARGIN = 2f
