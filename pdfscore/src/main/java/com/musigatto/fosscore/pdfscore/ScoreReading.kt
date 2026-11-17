package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.BarStyle
import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.TimeSignature

/**
 * A whole score read from its pages: its [parts] (one staff each, or several joined by a brace), and
 * its [measures] in reading order, each with what every staff holds and where it is drawn.
 */
data class ScoreReading(val parts: List<Part>, val measures: List<ScoreMeasure>) {
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

    companion object {
        /** The id of a measure (rank 1 up), as `:musicxml` names them so annotations hang on either. */
        fun measureId(rank: Int): String = "fosscore-measure-$rank"

        /** The id of the [k]th note (0 up) of the [event]th event (0 up) of [staff] (1 up) in measure [rank]. */
        fun noteId(rank: Int, staff: Int, event: Int, k: Int): String = "fosscore-note-$rank-$staff-$event-$k"
    }
}

/** A part: how many [staves] it is written on (2 for a piano). */
data class Part(val staves: Int)

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
            val directions = Directions.read(page, systems)
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
        read.forEachIndexed { page, (_, systems, directions) ->
            systems.forEachIndexed { si, system ->
                val count = system.maxOfOrNull { (staff, rhythms) ->
                    maxOf(staff.barlines.size, (staff.notes.maxOfOrNull { it.measure } ?: -1) + 1, (rhythms.maxOfOrNull { it.index } ?: -1) + 1)
                } ?: 0
                for (m in 0 until count) {
                    val staves = system.mapIndexed { s, (staff, rhythms) ->
                        // what is written over the system goes with its top staff
                        measureStaff(s + 1, staff, rhythms.firstOrNull { it.index == m }?.events.orEmpty(), m, times, if (s == 0) directions.getOrNull(si).orEmpty() else emptyList())
                    }
                    measures += ScoreMeasure(measures.size + 1, page, newSystem = m == 0, newPage = m == 0 && si == 0, staves = staves)
                }
            }
        }
        return ScoreReading(parts, measures)
    }

    /** Staff [n] (1 up) of measure [m] of its system, the time carried over from [times]. */
    private fun measureStaff(
        n: Int,
        staff: StaffReading,
        events: List<PlacedEvent>,
        m: Int,
        times: MutableMap<Int, TimeSignature>,
        directions: List<Direction>,
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
            events = events,
            barStyle = staff.barStyles.getOrNull(m),
            // a direction over the clef and key of a system's first measure is its
            directions = directions.filter { (m == 0 || it.x >= left) && it.x < right },
        )
    }
}
