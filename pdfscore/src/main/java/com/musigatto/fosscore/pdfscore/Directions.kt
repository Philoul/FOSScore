package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.NoteType
import kotlin.math.abs

/** Something written over a system, held from [x]: a tempo, words. */
sealed interface Direction {
    val x: Double
}

/** A metronome mark: [perMinute] [beat]s a minute, the beat [dots] times dotted ("♩. = 40"). */
data class TempoMark(override val x: Double, val beat: NoteType, val dots: Int, val perMinute: Int) : Direction

/** Words over the music that say how to play it ("Rall.", "a tempo", "cresc."). */
data class Words(override val x: Double, val text: String) : Direction

/**
 * Reads what is written over each system: metronome marks (a note symbol, maybe dotted, then
 * "= 62") and tempo or expression words. Words are told from lyrics by what they say (the fonts'
 * names say nothing in every PDF): only the usual tempo and expression words are directions.
 */
object Directions {
    /** Each system's directions, left to right, as [Staves.find] gives the systems. */
    fun read(page: PageDrawing, systems: List<StaffSystem>): List<List<Direction>> = systems.map { system ->
        val staff = system.staves.first()
        val space = staff.space
        val over = { y: Double -> y < staff.top && y >= staff.top - REACH * space }
        val tempos = page.glyphs.filter { over(it.box.bottom) }.mapNotNull { note ->
            val beat = BEATS[note.name] ?: return@mapNotNull null
            val dots = page.glyphs.count { dot ->
                dot.name == "metAugmentationDot" && dot.box.left >= note.box.right - 0.1 * space &&
                    dot.box.left <= note.box.right + 1.5 * space && abs(dot.box.centerY - note.box.bottom) <= space
            }
            page.texts.filter { it.x >= note.box.right - 0.5 * space && it.x <= note.box.right + 4 * space && abs(it.y - note.box.bottom) <= space }
                .minByOrNull { it.x }
                ?.let { PER_MINUTE.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
                ?.let { TempoMark(note.box.left, beat, dots, it) }
        }
        val words = page.texts.filter { over(it.y) && isWords(it.text) }.map { Words(it.x, it.text.trim()) }
        (tempos + words).sortedBy { it.x }
    }

    /** Whether [text] is a tempo or expression word ("Rall.", "a tempo"), not a lyric. */
    fun isWords(text: String): Boolean = WORDS.containsMatchIn(text)

    /** How far over the top line (in spaces) a direction is written. */
    private const val REACH = 6.0

    private val BEATS = mapOf(
        "metNoteWhole" to NoteType.WHOLE,
        "metNoteHalfUp" to NoteType.HALF,
        "metNoteQuarterUp" to NoteType.QUARTER,
        "metNote8thUp" to NoteType.EIGHTH,
        "metNote16thUp" to NoteType.SIXTEENTH,
    )

    private val PER_MINUTE = Regex("""=\s*(\d+)""")

    /** The usual tempo and expression words, at the start of the text. */
    private val WORDS = Regex(
        """^\s*(rall|rit|ritard|riten|accel|a tempo|tempo|rubato|poco|molto|meno|più|piu|cresc|decresc|dim|smorz|morendo|""" +
            """calando|allarg|string|sostenuto|dolce|espress|fine|d\.\s?c\.|d\.\s?s\.|da capo|dal segno|al coda|""" +
            """lento|largo|adagio|andante|moderato|allegr|presto|vivace)""",
        RegexOption.IGNORE_CASE,
    )
}
