package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.BarStyle
import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.Repeat
import com.musigatto.fosscore.pdfscore.music.TimeSignature

/**
 * A whole score read from its pages: its [parts] (one staff each, or several joined by a brace), its
 * [measures] in reading order, each with what every staff holds and where it is drawn, and its
 * [header] (title, composer, lyricist) as the first page writes it.
 */
data class ScoreReading(val parts: List<Part>, val measures: List<ScoreMeasure>, val header: Header = Header()) {
    /** The staves of the score, top to bottom: the parts' staves one after the other. */
    val staffCount: Int get() = parts.sumOf { it.staves }

    /** Where each note is drawn, by the id [MusicXmlWriter] gives it: its page (0 up) and its head's box. */
    fun notePlaces(): Map<String, Pair<Int, Box>> = measures.flatMap { measure ->
        measure.staves.flatMapIndexed { s, staff ->
            staff.events.flatMapIndexed { e, placed ->
                val notes = (placed.event as? Chord)?.pitches?.size ?: 0
                (0 until notes).map { k -> noteId(measure.rank, s + 1, e, k) to (measure.page to placed.box) }
            }
        }
    }.toMap()

    /**
     * Where each note is drawn, by the id [MusicXmlWriter] gives it, with what a playback bar needs
     * to follow the music on the page: the system the note is on ([FollowPlace]).
     */
    fun followPlaces(): Map<String, FollowPlace> {
        val places = LinkedHashMap<String, FollowPlace>()
        var first = 0
        while (first < measures.size) {
            // a system: from a measure that starts one to the next that does
            var end = first + 1
            while (end < measures.size && !measures[end].newSystem) end++
            val system = measures.subList(first, end)
            val boxes = system.flatMap { m -> m.staves.map { it.box } }
            if (boxes.isNotEmpty()) {
                val top = boxes.minOf { it.top }
                val bottom = boxes.maxOf { it.bottom }
                val lineEnd = boxes.maxOf { it.right }
                for (measure in system) measure.staves.forEachIndexed { s, staff ->
                    staff.events.forEachIndexed { e, placed ->
                        val notes = (placed.event as? Chord)?.pitches?.size ?: 0
                        val box = placed.box
                        for (k in 0 until notes) {
                            places[noteId(measure.rank, s + 1, e, k)] = FollowPlace(
                                measure.page, (box.left + box.right) / 2, (box.top + box.bottom) / 2,
                                top, bottom, staff.box.space, lineEnd, measure.staves.maxOf { it.box.right },
                            )
                        }
                    }
                }
            }
            first = end
        }
        return places
    }

    companion object {
        /** The id of a measure (rank 1 up), as `:musicxml` names them so annotations hang on either. */
        fun measureId(rank: Int): String = "fosscore-measure-$rank"

        /** The id of the [k]th note (0 up) of the [event]th event (0 up) of [staff] (1 up) in measure [rank]. */
        fun noteId(rank: Int, staff: Int, event: Int, k: Int): String = "fosscore-note-$rank-$staff-$event-$k"
    }
}

/**
 * Where a note is drawn, in points on its [page] (0 up): the centre of its head ([x], [y]), the
 * [top] line of its system's first staff and the [bottom] line of its last, the staff [space], and
 * where the system's staves end on the right ([lineEnd]) and where the note's measure ends ([measureEnd]). What
 * `:musicxml`'s PageGeometry.Head holds.
 */
data class FollowPlace(
    val page: Int,
    val x: Double,
    val y: Double,
    val top: Double,
    val bottom: Double,
    val space: Double,
    val lineEnd: Double,
    val measureEnd: Double = lineEnd,
)

/** A part: how many [staves] it is written on (2 for a piano), and its instrument's [name] as written left of them. */
data class Part(val staves: Int, val name: String? = null)

/** What the first page writes over the music: the [title], the [composer] (on the right), the [lyricist] (on the left). */
data class Header(val title: String? = null, val composer: String? = null, val lyricist: String? = null)

/**
 * One measure of the score: its [rank] (1 up), the [page] it is drawn on (0 up), whether it starts a
 * system or a page, and one [staves] entry per staff of the score, top to bottom.
 */
data class ScoreMeasure(
    val rank: Int,
    val page: Int,
    val newSystem: Boolean,
    val newPage: Boolean,
    val staves: List<MeasureStaff>,
)

/**
 * One staff of a measure: where it is drawn ([box]), the [clef], [key] and [time] in force when its
 * music starts (time null while none was drawn yet), the clefs drawn after its first note
 * ([clefChanges], held from their x), and its events left to right.
 */
data class MeasureStaff(
    val box: StaffBox,
    val clef: Clef?,
    val key: Key,
    val time: TimeSignature?,
    val clefChanges: List<Change<Clef>>,
    val events: List<PlacedEvent>,
    /** How the barline closing it is drawn; null when none does (the staff's end). */
    val barStyle: BarStyle? = null,
    /** What is written over it (a tempo, "rall."), left to right: on a system's top staff only. */
    val directions: List<Direction> = emptyList(),
    /** Whether a repeat starts with it, or ends with it (its measures are played twice). */
    val repeatStart: Boolean = false,
    val repeatEnd: Boolean = false,
    /** The ending (its numbers) that starts with it; the one that stops with it, and whether its bracket closes there. */
    val endingStart: String? = null,
    val endingStop: Pair<String, Boolean>? = null,
)

/**
 * Where one staff of a measure is drawn, in points on its page: from the [left] to the [right] end
 * of its lines, its [top] and [bottom] lines and the [space] between two lines. Its music starts at
 * [start], the left of its first note or rest (after the clef, key and time at a system's start).
 * The same shape as `:musicxml`'s PageGeometry.StaffBox, so a player can follow either.
 */
data class StaffBox(
    val n: Int,
    val left: Double,
    val right: Double,
    val top: Double,
    val bottom: Double,
    val space: Double,
    val start: Double,
)

/** Reads whole scores: each page's systems, then measures one after the other across systems and pages. */
object ScoreReader {
    /**
     * The score drawn on [pages]. The parts are the staves of the fullest system, a brace joining
     * several into one part. A system with fewer staves (a staff left out while it rests) has its
     * staves taken from the top: which one is missing is not read yet.
     */
    fun read(pages: List<PageDrawing>): ScoreReading {
        val read = pages.map { page ->
            val systems = Staves.find(page)
            val directions = Directions.read(page, systems).zip(Directions.endings(page, systems))
            // the lyrics go to their notes, system by system
            Triple(systems, StaffNotes.read(page, systems).map { system -> Lyrics.attach(page, system.map { it to Rhythms.read(page, it) }) }, directions)
        }
        val fullest = read.flatMap { it.first }.maxByOrNull { it.staves.size }
        val parts = fullest?.let { system ->
            val braced = system.braces.sortedBy { it.first }
            var s = 0
            buildList {
                while (s < system.staves.size) {
                    val brace = braced.firstOrNull { it.first == s }
                    val staves = brace?.let { it.last - it.first + 1 } ?: 1
                    add(Part(staves))
                    s += staves
                }
            }
        }.orEmpty()

        val measures = mutableListOf<ScoreMeasure>()
        val times = mutableMapOf<Int, TimeSignature>() // staff → the time last drawn on it
        read.forEachIndexed { page, (_, systems, signs) ->
            systems.forEachIndexed { si, system ->
                val count = system.maxOfOrNull { (staff, rhythms) ->
                    maxOf(staff.barlines.size, (staff.notes.maxOfOrNull { it.measure } ?: -1) + 1, (rhythms.maxOfOrNull { it.index } ?: -1) + 1)
                } ?: 0
                for (m in 0 until count) {
                    val staves = system.mapIndexed { s, (staff, rhythms) ->
                        // what is written over the system goes with its top staff
                        val (directions, endings) = signs.getOrNull(si) ?: (emptyList<Direction>() to emptyList())
                        measureStaff(s + 1, staff, rhythms.firstOrNull { it.index == m }?.events.orEmpty(), m, times, if (s == 0) directions else emptyList(), if (s == 0) endings else emptyList())
                    }
                    measures += ScoreMeasure(measures.size + 1, page, newSystem = m == 0, newPage = m == 0 && si == 0, staves = staves)
                }
            }
        }
        // the first page's first system holds the parts' full names and, over it, the header
        val first = read.firstOrNull()?.first?.firstOrNull()
        val header = first?.let { header(pages[0], it) } ?: Header()
        val named = first?.takeIf { it.staves.size == parts.sumOf { p -> p.staves } }?.let { named(parts, pages[0], it) } ?: parts
        return ScoreReading(named, measures, header)
    }

    /** Each part with the name written left of its staves in [system] (its lines joined), if any. */
    private fun named(parts: List<Part>, page: PageDrawing, system: StaffSystem): List<Part> {
        var s = 0
        return parts.map { part ->
            val staves = system.staves.subList(s, s + part.staves)
            s += part.staves
            val space = staves.first().space
            val name = page.texts
                .filter { it.x < staves.first().left - 0.5 * space && it.y >= staves.first().top - space && it.y <= staves.last().bottom + 2 * space }
                .sortedBy { it.y }
                .joinToString(" ") { words(it.text) }
                .takeIf { it.isNotEmpty() }
            part.copy(name = name)
        }
    }

    /**
     * The header: over the first system (higher than a tempo or the lyrics), the largest text is the
     * title, the first text on the right the composer, the first on the left the lyricist.
     */
    private fun header(page: PageDrawing, system: StaffSystem): Header {
        val space = system.staves.first().space
        val over = page.texts.filter { it.y < system.top - HEADER * space && words(it.text).isNotEmpty() }
        val largest = over.maxOfOrNull { it.size } ?: return Header()
        val titles = over.filter { it.size >= largest - 0.5 }
        // a text drawn twice is one text
        val title = titles.sortedWith(compareBy({ it.y }, { it.x })).map { words(it.text) }.distinct().joinToString(" ")
        val rest = (over - titles.toSet()).sortedBy { it.y }
        return Header(
            title = title,
            composer = rest.firstOrNull { it.x > page.width / 2 }?.let { words(it.text) },
            lyricist = rest.firstOrNull { it.x <= page.width / 2 }?.let { words(it.text) },
        )
    }

    /** A text's words, one space between them. */
    private fun words(text: String): String = text.trim().split(Regex("""\s+""")).joinToString(" ")

    /** How far over the first system (in spaces) the header ends: below it come tempos and lyrics. */
    private const val HEADER = 5.0

    /** Staff [n] (1 up) of measure [m] of its system, the time carried over from [times]. */
    private fun measureStaff(
        n: Int,
        staff: StaffReading,
        events: List<PlacedEvent>,
        m: Int,
        times: MutableMap<Int, TimeSignature>,
        directions: List<Direction>,
        endings: List<Ending>,
    ): MeasureStaff {
        val lines = staff.staff
        val left = staff.barlines.getOrNull(m - 1) ?: lines.left
        val right = staff.barlines.getOrNull(m) ?: lines.right
        // what is drawn before the first note is in force for the whole measure; a clef after it
        // changes the notes from there on; a key or time after the last note announces the next line's
        val music = events.minOfOrNull { it.box.left } ?: right
        val before = { x: Double -> x < music && (m == 0 || x > left) }
        staff.times.lastOrNull { before(it.x) }?.let { times[n] = it.value }
        val clef = staff.clefs.lastOrNull { it.x < music }?.value ?: staff.clef
        return MeasureStaff(
            box = StaffBox(n, left, right, lines.top, lines.bottom, lines.space, events.minOfOrNull { it.box.left } ?: left),
            clef = clef,
            key = staff.keys.lastOrNull { it.x < music }?.value ?: staff.key,
            time = times[n],
            clefChanges = staff.clefs.filter { it.x >= music && it.x < right },
            // with the triplets the time signature implies where their number was not read or printed
            events = ImpliedTuplets.apply(events, times[n]?.measureLength),
            barStyle = staff.barStyles.getOrNull(m),
            // a repeat starts after the barline before (or with the system), ends at the one closing it
            repeatStart = staff.barRepeats.getOrNull(m - 1).let { it == Repeat.FORWARD || it == Repeat.BOTH } || (m == 0 && staff.opensRepeat),
            repeatEnd = staff.barRepeats.getOrNull(m).let { it == Repeat.BACKWARD || it == Repeat.BOTH },
            // a direction over the clef and key of a system's first measure is its
            directions = directions.filter { (m == 0 || it.x >= left) && it.x < right },
            // an ending starts in the measure its bracket starts over, stops in the one it reaches the end of
            endingStart = endings.firstOrNull { (m == 0 || it.x >= left - lines.space) && it.x < right - lines.space }?.numbers,
            endingStop = endings.firstOrNull { it.right > left + lines.space && it.right <= right + lines.space }?.let { it.numbers to it.closed },
        )
    }
}
