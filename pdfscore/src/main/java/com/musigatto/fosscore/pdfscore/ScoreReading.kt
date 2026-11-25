package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.BarStyle
import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Event
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.NoteType
import com.musigatto.fosscore.pdfscore.music.Repeat
import com.musigatto.fosscore.pdfscore.music.Rest
import com.musigatto.fosscore.pdfscore.music.Stem
import com.musigatto.fosscore.pdfscore.music.TimeSignature
import kotlin.math.abs
import kotlin.math.roundToLong

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
     * several into one part. A system with fewer staves (parts left out while they rest) has its
     * staves given to the parts where the music goes on most ([StaffParts]).
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
        val times = mutableMapOf<Int, TimeSignature>() // score staff → the time last drawn on it
        val total = parts.sumOf { it.staves }
        // which staff of the score each staff of a system is
        val looks = read.flatMap { (found, systems, _) -> found.zip(systems) { system, staves -> SystemLook(staves.map { (staff, rhythms) -> look(staff, rhythms) }, system.braces) } }
        val chosen = StaffParts.places(looks, parts).iterator()
        val places = read.map { (found, _, _) -> found.map { chosen.next() } }
        // the clef of each staff of the score: the last one drawn, else (it is not drawn yet) the first
        val clefs = mutableMapOf<Int, Clef>()
        read.zip(places).forEach { (page, pagePlaces) ->
            page.second.zip(pagePlaces).forEach { (system, place) ->
                system.forEachIndexed { s, (staff, _) -> staff.clef?.let { clefs.putIfAbsent(place[s], it) } }
            }
        }
        read.forEachIndexed { page, (_, systems, signs) ->
            systems.forEachIndexed { si, system ->
                val place = places[page][si]
                val count = system.maxOfOrNull { (staff, rhythms) ->
                    maxOf(staff.barlines.size, (staff.notes.maxOfOrNull { it.measure } ?: -1) + 1, (rhythms.maxOfOrNull { it.index } ?: -1) + 1)
                } ?: 0
                for (m in 0 until count) {
                    val drawn = system.mapIndexed { s, (staff, rhythms) ->
                        // what is written over the system goes with its top staff
                        val (directions, endings) = signs.getOrNull(si) ?: (emptyList<Direction>() to emptyList())
                        measureStaff(place[s] + 1, staff, rhythms.firstOrNull { it.index == m }?.events.orEmpty(), m, times, if (s == 0) directions else emptyList(), if (s == 0) endings else emptyList())
                    }
                    drawn.forEach { staff -> (staff.clefChanges.lastOrNull()?.value ?: staff.clef)?.let { clefs[staff.box.n - 1] = it } }
                    // a staff the system leaves out (its part rests) is there, empty
                    val staves = (0 until maxOf(total, drawn.size)).mapNotNull { n ->
                        drawn.getOrNull(place.indexOf(n)) ?: drawn.minByOrNull { abs(it.box.n - 1 - n) }?.let { absent(n, it, clefs[n]) }
                    }
                    measures += ScoreMeasure(measures.size + 1, page, newSystem = m == 0, newPage = m == 0 && si == 0, staves = staves)
                }
            }
        }
        // the first page's first system holds the parts' full names and, over it, the header
        val first = read.firstOrNull()?.first?.firstOrNull()
        val header = first?.let { header(pages[0], it) } ?: Header()
        val named = first?.takeIf { it.staves.size == parts.sumOf { p -> p.staves } }?.let { named(parts, pages[0], it) } ?: parts
        return ScoreReading(named, tiedAlterations(fitVoices(crossStaffStarts(measures, parts))), header)
    }

    /**
     * Which staff of the score (0 up) each of the [count] staves of a system is, the score's staves
     * being those of [parts] and the system's braces [braces]. A system with every staff has them
     * in order. One with fewer leaves out the parts that rest: its brace is the braced part of
     * that size (counted from the bottom, the parts left out being the top ones), and the staves
     * around it are the ones around that part. Without a brace to go by, they are the top ones.
     */
    internal fun staffPlaces(count: Int, braces: List<IntRange>, parts: List<Part>): List<Int> {
        val total = parts.sumOf { it.staves }
        val inOrder = (0 until count).toList()
        if (count >= total) return inOrder
        // the first staff of each braced part, by its number of staves
        val firsts = parts.runningFold(0) { s, p -> s + p.staves }.zip(parts).filter { it.second.staves > 1 }
        val shift = braces.sortedByDescending { it.first }.firstNotNullOfOrNull { brace ->
            val size = brace.last - brace.first + 1
            val ofSize = firsts.filter { it.second.staves == size }
            // the system's braces of that size, and the score's, both from the bottom
            val rank = braces.filter { it.last - it.first + 1 == size }.sortedByDescending { it.first }.indexOf(brace)
            ofSize.reversed().getOrNull(rank)?.let { (first, _) -> first - brace.first }
        } ?: return inOrder
        return inOrder.map { it + shift }.takeIf { places -> places.all { it in 0 until total } } ?: inOrder
    }

    /**
     * The [measures] with the time of a part's staves running left to right on all of them: a voice
     * that does not fill its measure, its first note drawn after notes of another staff of its part,
     * starts when those end, if it then fits its measure.
     */
    internal fun crossStaffStarts(measures: List<ScoreMeasure>, parts: List<Part>): List<ScoreMeasure> {
        val firsts = parts.runningFold(0) { s, p -> s + p.staves }
        val partOf = parts.indices.flatMap { p -> List(parts[p].staves) { p } }
        return measures.map { measure ->
            val length = measure.staves.firstNotNullOfOrNull { it.time }?.measureLength ?: return@map measure
            measure.copy(staves = measure.staves.mapIndexed { s, staff ->
                val part = partOf.getOrNull(s) ?: return@mapIndexed staff
                if (parts[part].staves < 2) return@mapIndexed staff
                val others = (firsts[part] until firsts[part] + parts[part].staves).filter { it != s }.mapNotNull { measure.staves.getOrNull(it) }
                val shifts = staff.events.groupBy { it.event.voice }.mapValues { (_, voice) ->
                    val first = voice.filter { (it.event as? Chord)?.grace != true }.minByOrNull { it.event.onset } ?: return@mapValues null
                    if (first.event.onset != Fraction.ZERO || first.event !is Chord) return@mapValues null
                    val end = voice.filter { (it.event as? Chord)?.grace != true }.maxOf { it.event.onset + it.event.duration.length }
                    if (end >= length) return@mapValues null
                    // only a hand silent at the measure's start goes on from the other one: another
                    // voice of its staff playing then says it starts then too (Lettre à France 24,
                    // two voices' G2s side by side)
                    if (staff.events.any { it.event.voice != first.event.voice && it.event.onset == Fraction.ZERO && it.event is Chord }) {
                        return@mapValues null
                    }
                    // the notes of the other staves drawn before it: it starts when they end
                    val before = others.flatMap { it.events }.filter { it.event is Chord && it.box.right < first.box.left }
                    val start = before.maxOfOrNull { it.event.onset + it.event.duration.length } ?: return@mapValues null
                    start.takeIf { it > Fraction.ZERO && end + it <= length }
                }
                if (shifts.values.all { it == null }) staff
                else staff.copy(events = staff.events.map { placed ->
                    shifts[placed.event.voice]?.let { by -> placed.copy(event = shifted(placed.event, by)) } ?: placed
                })
            })
        }
    }

    /**
     * The [measures] with every voice of a staff filling its measure where moving one event does it
     * (maintainer, 2026-11-21: the time each note is played comes first): a voice longer than its
     * measure by an event's duration gives that event to a voice of its staff shorter by as much,
     * where it is drawn among that voice's events.
     */
    internal fun fitVoices(measures: List<ScoreMeasure>): List<ScoreMeasure> = measures.map { measure ->
        val length = measure.staves.firstNotNullOfOrNull { it.time }?.measureLength ?: return@map measure
        // a measure some voice fills is a whole one (not a pickup): a voice short in it lacks time
        val full = measure.staves.any { staff ->
            staff.events.groupBy { it.event.voice }.values.any { inVoice ->
                inVoice.filter { (it.event as? Chord)?.grace != true }.maxOfOrNull { it.event.onset + it.event.duration.length } == length
            }
        }
        measure.copy(staves = measure.staves.map { staff -> fitVoices(if (full) oneFlagLess(staff, length) else staff, length) })
    }

    /**
     * A voice short of its measure by the value of one of its flagged notes, the only one: that
     * note has a flag less than read, and the notes after it start later. The look of two flags
     * and three are close, and no height tells them apart in every font (Né en 17 à Leidenstadt,
     * 35: a sixteenth after a dotted eighth rest read as a 32nd, the notes after it early).
     */
    internal fun oneFlagLess(staff: MeasureStaff, length: Fraction): MeasureStaff {
        var events = staff.events
        for ((_, inVoice) in staff.events.groupBy { it.event.voice }) {
            val timed = inVoice.filter { (it.event as? Chord)?.grace != true }
            val lack = length - (timed.maxOfOrNull { it.event.onset + it.event.duration.length } ?: continue)
            if (lack <= Fraction.ZERO) continue
            val flagged = timed.filter { e ->
                val chord = e.event as? Chord
                chord != null && chord.beams.isEmpty() && chord.stem != Stem.NONE && chord.duration.type.beams >= 2 && chord.duration.length == lack
            }
            val e = flagged.singleOrNull() ?: continue
            val chord = e.event as Chord
            val longer = NoteType.withBeams(chord.duration.type.beams - 1) ?: continue
            val lengthened = e.copy(event = chord.copy(duration = chord.duration.copy(type = longer)))
            events = events.map { other ->
                when {
                    other === e -> lengthened
                    other.event.voice == chord.voice && other.event.onset > chord.onset -> other.copy(event = shifted(other.event, lack))
                    else -> other
                }
            }
        }
        return staff.copy(events = events)
    }

    private fun fitVoices(staff: MeasureStaff, length: Fraction): MeasureStaff {
        fun timed(e: PlacedEvent) = (e.event as? Chord)?.grace != true
        fun end(events: List<PlacedEvent>) = events.filter(::timed).maxOfOrNull { it.event.onset + it.event.duration.length } ?: Fraction.ZERO
        val voices = staff.events.groupBy { it.event.voice }
        for ((a, inA) in voices) {
            val excess = end(inA) - length
            if (excess <= Fraction.ZERO) continue
            for ((b, inB) in voices) {
                if (b == a || length - end(inB) != excess) continue
                // a rest first: what a reading gives the wrong voice most
                val candidates = inA.filter { timed(it) && it.event.duration.length == excess }.sortedBy { if (it.event is Rest) 0 else 1 }
                for (e in candidates) {
                    val d = e.event.duration.length
                    val newA = inA.filter { it !== e }.map { if (it.event.onset > e.event.onset) it.copy(event = shifted(it.event, Fraction.ZERO - d)) else it }
                    val at = inB.filter { timed(it) && it.box.left < e.box.left }.maxOfOrNull { it.event.onset + it.event.duration.length } ?: Fraction.ZERO
                    val newB = inB.map { if (it.event.onset >= at) it.copy(event = shifted(it.event, d)) else it } +
                        e.copy(event = revoiced(e.event, b, at))
                    if (end(newA) == length && end(newB) == length) {
                        val others = staff.events.filter { it.event.voice != a && it.event.voice != b }
                        return staff.copy(events = (others + newA + newB).sortedWith(compareBy<PlacedEvent>({ it.event.onset }, { it.event.voice })))
                    }
                }
            }
        }
        // no voice of the staff has room for it: the note a voice is too long by goes to a voice
        // of its own, at the beat its place in the measure says (Né en 17 à Leidenstadt, 21: a
        // quarter on the second beat beside a half, both stems up, played after the half)
        val span = staff.box.right - staff.box.start
        for ((a, inA) in voices) {
            val excess = end(inA) - length
            if (excess <= Fraction.ZERO || span <= 0.0) continue
            val e = inA.filter { timed(it) && it.event is Chord && it.event.duration.length == excess }.maxByOrNull { it.event.onset } ?: continue
            val newA = inA.filter { it !== e }.map { if (it.event.onset > e.event.onset) it.copy(event = shifted(it.event, Fraction.ZERO - excess)) else it }
            if (end(newA) != length) continue
            // the beats of its value it may start on, the nearest to where it is drawn
            val steps = (length.numerator * excess.denominator) / (length.denominator * excess.numerator)
            if (steps < 2) continue
            val drawn = ((e.box.left - staff.box.start) / span * steps).roundToLong().coerceIn(1, steps - 1)
            val voice = voices.keys.max() + 1
            val moved = e.copy(event = revoiced(e.event, voice, excess * Fraction.of(drawn)))
            val others = staff.events.filter { it.event.voice != a }
            return staff.copy(events = (others + newA + moved).sortedWith(compareBy<PlacedEvent>({ it.event.onset }, { it.event.voice })))
        }
        return staff
    }

    private fun revoiced(event: Event, voice: Int, onset: Fraction): Event = when (event) {
        is Chord -> event.copy(voice = voice, onset = onset)
        is Rest -> event.copy(voice = voice, onset = onset)
    }

    private fun shifted(event: Event, by: Fraction): Event = when (event) {
        is Chord -> event.copy(onset = event.onset + by)
        is Rest -> event.copy(onset = event.onset + by)
    }

    /**
     * The [measures] with each note tied over a barline given the alteration of the note it is tied
     * from (written on that one only), unless one is written on it. The notes after it in its
     * measure keep the key's, as read.
     */
    internal fun tiedAlterations(measures: List<ScoreMeasure>): List<ScoreMeasure> {
        // per staff of the score: the alterations of the notes tied on over the last barline, by white key
        val carried = mutableMapOf<Int, Map<Int, Int>>()
        return measures.map { measure ->
            measure.copy(staves = measure.staves.mapIndexed { s, staff ->
                val open = carried[s].orEmpty().toMutableMap()
                val next = mutableMapOf<Int, Int>()
                val order = staff.events.indices.sortedBy { staff.events[it].event.onset }
                val events = staff.events.toMutableList()
                for (i in order) {
                    val chord = events[i].event as? Chord ?: continue
                    if (chord.grace) continue
                    val pitches = chord.pitches.mapIndexed { k, pitch ->
                        // the note a tie over the barline ends on
                        val alter = pitch?.let { open.remove(it.diatonic) }
                        if (pitch != null && alter != null && k !in chord.accidentals) pitch.copy(alter = alter) else pitch
                    }
                    pitches.forEachIndexed { k, pitch -> if (pitch != null && k in chord.ties) next[pitch.diatonic] = pitch.alter }
                    // a tie inside the measure ends on a note of the measure: not carried over
                    pitches.forEach { pitch -> if (pitch != null && chord.ties.none { pitches.getOrNull(it) == pitch }) next.remove(pitch.diatonic) }
                    if (pitches != chord.pitches) events[i] = events[i].copy(event = chord.copy(pitches = pitches))
                }
                carried[s] = next
                staff.copy(events = events)
            })
        }
    }

    /** What the staff [staff] of a system shows, to tell which part it is ([StaffParts]). */
    private fun look(staff: StaffReading, rhythms: List<MeasureReading>): StaffLook {
        val ordered = rhythms.sortedBy { it.index }
        val chords = ordered.flatMap { m -> m.events.map { it.event }.sortedBy { it.onset } }.mapNotNull { it as? Chord }.filter { !it.grace }
        val pitches = chords.flatMap { c -> c.pitches.mapNotNull { it?.diatonic } }.sorted()
        fun top(chord: Chord?) = chord?.pitches?.mapNotNull { it?.diatonic }?.maxOrNull()?.toDouble()
        // a staff sounds at its start when a note is struck on its first beat (not after a rest),
        // at its end when its last measure ends on a note
        fun startsOnANote(m: MeasureReading?) = m?.events?.any { (it.event as? Chord)?.grace == false && it.event.onset == Fraction.ZERO } == true
        fun endsOnANote(m: MeasureReading?) = m?.events?.filter { (it.event as? Chord)?.grace != true }?.maxByOrNull { it.event.onset }?.event is Chord
        return StaffLook(
            clefStart = staff.clef?.sign,
            clefEnd = (staff.clefs.lastOrNull()?.value ?: staff.clef)?.sign,
            pitch = pitches.getOrNull(pitches.size / 2)?.toDouble(),
            lyrics = chords.any { it.lyric != null },
            startsSounding = startsOnANote(ordered.firstOrNull()),
            endsSounding = endsOnANote(ordered.lastOrNull()),
            first = top(chords.firstOrNull()),
            last = top(chords.lastOrNull()),
        )
    }

    /** The empty staff [n] (0 up) of a measure whose system leaves it out: drawn where [near] is, with its [clef]. */
    private fun absent(n: Int, near: MeasureStaff, clef: Clef?): MeasureStaff = MeasureStaff(
        box = near.box.copy(n = n + 1),
        clef = clef,
        key = near.key,
        time = near.time,
        clefChanges = emptyList(),
        events = emptyList(),
        barStyle = near.barStyle,
        repeatStart = near.repeatStart,
        repeatEnd = near.repeatEnd,
        endingStart = near.endingStart,
        endingStop = near.endingStop,
    )

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
            events = ImpliedTuplets.apply(events, times[n]?.measureLength, times[n]?.let { it.beatType >= 8 && it.beats > 3 && it.beats % 3 == 0 } ?: false),
            barStyle = staff.barStyles.getOrNull(m),
            // a repeat starts after the barline before (or with the system), ends at the one closing it
            repeatStart = staff.barRepeats.getOrNull(m - 1).let { it == Repeat.FORWARD || it == Repeat.BOTH } || (m == 0 && staff.opensRepeat),
            repeatEnd = staff.barRepeats.getOrNull(m).let { it == Repeat.BACKWARD || it == Repeat.BOTH },
            // a direction over the clef and key of a system's first measure is its
            // a segno after the measure's last note is where to go back to, not where one is: a
            // segno marks the start of its measure (Pas de boogie woogie, 72: back to 19)
            directions = directions.filter { (m == 0 || it.x >= left) && it.x < right }.map { direction ->
                val end = events.maxOfOrNull { it.box.right }
                if (direction is Mark && direction.kind == Mark.Kind.SEGNO && end != null && direction.x > end) Words(direction.x, "D.S.") else direction
            },
            // an ending starts in the measure its bracket starts over, stops in the one it reaches the end of
            endingStart = endings.firstOrNull { (m == 0 || it.x >= left - lines.space) && it.x < right - lines.space }?.numbers,
            endingStop = endings.firstOrNull { it.right > left + lines.space && it.right <= right + lines.space }?.let { it.numbers to it.closed },
        )
    }
}
