package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.NoteType
import kotlin.math.abs

/** Something written over a system, held from [x]: a tempo, words. */
sealed interface Direction {
    val x: Double
}

/** A metronome mark: [perMinute] [beat]s a minute, the beat [dots] times dotted ("♩. = 40"). */
data class TempoMark(override val x: Double, val beat: NoteType, val dots: Int, val perMinute: Int) : Direction

/** Words over the music that say how to play it ("Rall.", "a tempo", "cresc.", "D.S. al Coda"). */
data class Words(override val x: Double, val text: String) : Direction

/** A sign a jump goes to: a segno ("D.S." jumps back to it) or a coda ("To Coda" jumps on to it). */
data class Mark(override val x: Double, val kind: Kind) : Direction {
    enum class Kind { SEGNO, CODA }
}

/**
 * An ending (a first or second time): the bracket over the measures from [x] to [right], played
 * the times its [numbers] say ("1", "1, 2"); [closed] when the bracket has a hook at its end too
 * (an open one is the last ending).
 */
data class Ending(val x: Double, val right: Double, val numbers: String, val closed: Boolean)

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
        // a metronome mark may stand over the chord symbols, higher than words
        val tempos = page.glyphs.filter { it.box.bottom < staff.top && it.box.bottom >= staff.top - ENDING_REACH * space }.mapNotNull { note ->
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
        val marks = page.glyphs.filter { over(it.box.bottom) }.mapNotNull { glyph ->
            when (glyph.name) {
                "segno" -> Mark(glyph.box.left, Mark.Kind.SEGNO)
                "coda", "codaSquare" -> Mark(glyph.box.left, Mark.Kind.CODA)
                else -> null
            }
        }
        // a coda or segno in the margin left of the system, by any of its staves: its start
        val bottom = system.staves.last().lineYs.last()
        val margin = page.glyphs.filter { glyph ->
            glyph.box.right <= staff.left + 0.5 * space && glyph.box.right >= staff.left - MARGIN_REACH * space &&
                glyph.box.bottom >= staff.top - space && glyph.box.top <= bottom + space
        }.mapNotNull { glyph ->
            when (glyph.name) {
                "segno" -> Mark(staff.left, Mark.Kind.SEGNO)
                "coda", "codaSquare" -> Mark(staff.left, Mark.Kind.CODA)
                else -> null
            }
        }
        (tempos + words + marks + margin).sortedBy { it.x }
    }

    /**
     * Each system's endings, left to right: over the top staff, a level line at least two spaces
     * long with a hook down at its start, and its number ("1.", "1, 2.") just under the line by the
     * hook. A hook down at its end too closes it; the last ending stays open.
     */
    fun endings(page: PageDrawing, systems: List<StaffSystem>): List<List<Ending>> = systems.map { system ->
        val staff = system.staves.first()
        val space = staff.space
        // the strokes: lines, and thin filled rectangles (a PDF printed without fonts)
        val strokes = Strokes.of(page)
        val hooks = strokes.filter { it.isVertical && it.length in 0.5 * space..3.0 * space }
        strokes.filter { line ->
            line.isHorizontal && line.length >= 2 * space && line.y1 < staff.top - 0.5 * space && line.y1 >= staff.top - ENDING_REACH * space
        }.mapNotNull { line ->
            val left = minOf(line.x1, line.x2)
            val right = maxOf(line.x1, line.x2)
            val hookAt = { x: Double -> hooks.any { abs(it.x1 - x) < 0.3 * space && minOf(it.y1, it.y2) >= line.y1 - 0.3 * space && minOf(it.y1, it.y2) <= line.y1 + 0.3 * space } }
            if (!hookAt(left)) return@mapNotNull null
            page.texts.filter { it.x >= left - 0.2 * space && it.x <= left + 3 * space && it.y > line.y1 && it.y <= line.y1 + 3 * space }
                .minByOrNull { it.x }
                ?.let { ENDING.find(it.text)?.groupValues?.get(1) }
                ?.let { numbers -> Ending(left, right, numbers.replace(Regex("""\s+"""), " "), closed = hookAt(right)) }
        }.sortedBy { it.x }
    }

    /** An ending's numbers, with their dot: "1.", "1, 2." (a tuplet's bracket has a number with none). */
    private val ENDING = Regex("""^\s*(\d+(?:\s*,\s*\d+)*)\.\s*$""")

    /** Whether [text] is a tempo or expression word ("Rall.", "a tempo"), not a lyric. */
    fun isWords(text: String): Boolean = WORDS.containsMatchIn(text)

    /** How far left of a system (in spaces) a coda or segno in its margin may be. */
    private const val MARGIN_REACH = 8.0

    /** How far over the top line (in spaces) a direction is written. */
    private const val REACH = 6.0

    /** How far over the top line (in spaces) an ending's bracket may be: over a line of lyrics too. */
    const val ENDING_REACH = 9.0

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
            """calando|allarg|string|sostenuto|dolce|espress|fine|d\.\s?c\.|d\.\s?s\.|da capo|dal segno|al coda|to coda|""" +
            """lento|largo|adagio|andante|moderato|allegr|presto|vivace|""" +
            // "To" and a sign: "To 𝄋", "3. To ⊕" (the OCR reads the sign too: "To $", "3. To @")
            """(\d+\.\s*)*to\b(?!\s+[a-zA-Z]{3}))""",
        RegexOption.IGNORE_CASE,
    )
}
