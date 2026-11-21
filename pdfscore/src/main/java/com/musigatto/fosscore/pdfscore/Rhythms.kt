package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Articulation
import com.musigatto.fosscore.pdfscore.music.BeamMark
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Duration
import com.musigatto.fosscore.pdfscore.music.Event
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.NoteType
import com.musigatto.fosscore.pdfscore.music.Rest
import com.musigatto.fosscore.pdfscore.music.StaffMeasure
import com.musigatto.fosscore.pdfscore.music.Stem
import kotlin.math.abs

/** An event of the neutral model and where the page draws it (its heads, or its rest): what a player lights. */
data class PlacedEvent(val event: Event, val box: Box)

/** The events of one measure of a staff ([index] as [StaffNote.measure]), with where they are drawn. */
data class MeasureReading(val index: Int, val events: List<PlacedEvent>) {
    /** The measure without the page: what the rest of the app works with. */
    val measure: StaffMeasure get() = StaffMeasure(index, events.map { it.event })
}

/**
 * Reads how long each note and rest of a staff lasts, and in which voice, from the symbols' names
 * and boxes and the page's lines and shapes only, so that a scan's blobs are read by the same code:
 * - a note's value from its head (whole, half, black), then the flags or beams at its stem's end;
 * - the augmentation dots after it, the staccato dots over or under it;
 * - a rest's value from its symbol (a whole rest hangs from a line, a half rest sits on one);
 * - small notes are grace notes, unless struck with a normal note or rest (a cue line);
 * - two voices where notes with stems up and down are struck together, else one;
 * - each event's onset in its measure, the durations of its voice before it added up.
 * - tuplets from their number (and its bracket, or the beam it sits on).
 */
object Rhythms {
    /** How near (in spaces) a small note is to a normal note or rest struck with it: a cue line, not grace notes. */
    private const val CUE_ALIGN = 0.6

    fun read(page: PageDrawing, reading: StaffReading): List<MeasureReading> {
        val staff = reading.staff
        val space = staff.space
        val beams = beams(page, space)
        val stems = stems(page.lines, space)
        val normalHead = reading.notes.map { it.head.box.height }.sorted().let { if (it.isEmpty()) space else it[it.size / 2] }

        val chords = chords(reading, stems, space)
        val rests = rests(reading)
        fun small(chord: Draft) = chord.notes.all { it.head.box.height < 0.8 * normalHead }
        // small notes struck with a normal note or rest (at its x) are a cue line, played; the
        // others are grace notes, before the note they lead to
        val normal = chords.filterNot(::small) + rests
        fun cue(chord: Draft) = normal.any { abs(it.box.left - chord.box.left) < CUE_ALIGN * space }
        // a grace note has a stem: a small whole head on its own is a curve misread
        val drafts = chords.filterNot { small(it) && it.type == NoteType.WHOLE && !cue(it) }.map { chord ->
            val onStem = beamsAt(chord, beams, space)
            val grace = small(chord) && !cue(chord)
            val value = chord.type ?: blackValue(chord, onStem, reading.symbols, space)
            chord.copy(
                // a grace note alone is an eighth (an acciaccatura): its small flag is seldom read
                type = if (grace && value == NoteType.QUARTER && onStem.isEmpty()) NoteType.EIGHTH else value,
                grace = grace,
                beams = onStem,
            )
        } + rests
        dots(drafts, reading, space)
        beamMarks(drafts)
        arpeggios(drafts, reading.symbols, space)
        fermatas(drafts, reading.symbols, space)
        val tuplets = tuplets(reading, page.lines, beams, space)
        val ties = ties(page, reading, space)

        return drafts.groupBy { it.measure }.toSortedMap().map { (measure, items) -> MeasureReading(measure, timed(items, staff, tuplets, ties)) }
    }

    /**
     * The notes a tie starts from. A tie is a curve from beside a head (past its middle, at its
     * height) to beside the next head on its line or space; or on to the staff's end, when the tie
     * goes on over the line break (its other half starts the next system, before the note it ends
     * on). A curve to a note on another line or space is a slur.
     */
    private fun ties(page: PageDrawing, reading: StaffReading, space: Double): Set<StaffNote> {
        val staff = reading.staff
        val notes = reading.notes
        val starts = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<StaffNote, Boolean>())
        val besideHead = { p: Point, head: Box -> abs(p.y - head.centerY) <= 1.5 * space }
        for (curve in page.shapes) {
            val box = curve.box
            if (!curve.curved || box.width < 0.8 * space || box.height > 1.5 * space) continue
            val left = curve.points.minBy { it.x }
            val right = curve.points.maxBy { it.x }
            val start = notes.filter { note ->
                val head = note.head.box
                left.x >= head.left + 0.2 * head.width && left.x <= head.right + 1.0 * space && besideHead(left, head)
            }.minByOrNull { abs(left.x - it.head.box.right) + abs(left.y - it.head.box.centerY) } ?: continue
            val next = notes.filter { it.step == start.step && it.head.box.left > start.head.box.right }.minByOrNull { it.head.box.left }
            val ends = next?.let { note ->
                val head = note.head.box
                right.x >= head.left - 1.0 * space && right.x <= head.right - 0.2 * head.width && besideHead(right, head)
            } ?: (right.x >= staff.right - 1.5 * space)
            if (ends) starts += start
        }
        return starts
    }

    /** A note or rest being read: what is known of it so far. */
    private data class Draft(
        val measure: Int,
        val box: Box,
        val notes: List<StaffNote> = emptyList(),
        val stem: Stem = Stem.NONE,
        val stemLine: DrawnLine? = null,
        val type: NoteType? = null,
        val rest: Boolean = false,
        val grace: Boolean = false,
        var dots: Int = 0,
        var staccato: Boolean = false,
        /** The beams on its stem, from the outer one (at the stem's end) in. */
        val beams: List<Beam> = emptyList(),
        var beamMarks: List<BeamMark> = emptyList(),
        var arpeggiate: Boolean = false,
        var fermata: Boolean = false,
    ) {
        val x: Double get() = box.left
    }

    /**
     * The notes on one stem make one chord: a stem runs from a head's side (its right side when up,
     * its left side when down) past it. A head with no stem (a whole note) joins the heads it touches.
     */
    private fun chords(reading: StaffReading, stems: List<DrawnLine>, space: Double): List<Draft> {
        val byStem = LinkedHashMap<DrawnLine, MutableList<StaffNote>>()
        val stemless = mutableListOf<StaffNote>()
        for (note in reading.notes) {
            val head = note.head.box
            val touching = if (headValue(note.head.name) == NoteType.WHOLE || headValue(note.head.name) == NoteType.BREVE) emptyList()
            else stems.filter { line ->
                val top = minOf(line.y1, line.y2)
                val bottom = maxOf(line.y1, line.y2)
                minOf(abs(line.x1 - head.right), abs(line.x1 - head.left)) < 0.35 * space &&
                    head.centerY >= top - 0.6 * space && head.centerY <= bottom + 0.6 * space
            }
            // a head with a stem up on its right and one down on its left is struck by two voices
            // (a melody and its accompaniment on one staff): a note of both chords
            val up = touching.filter { abs(it.x1 - head.right) < 0.35 * space && minOf(it.y1, it.y2) < head.centerY - space }
                .minByOrNull { abs(it.x1 - head.right) }
            val down = touching.filter { abs(it.x1 - head.left) < 0.35 * space && maxOf(it.y1, it.y2) > head.centerY + space }
                .minByOrNull { abs(it.x1 - head.left) }
            val chosen = if (up != null && down != null && up !== down) listOf(up, down)
            else listOfNotNull(touching.minByOrNull { minOf(abs(it.x1 - head.right), abs(it.x1 - head.left)) })
            if (chosen.isEmpty()) stemless.add(note) else chosen.forEach { byStem.getOrPut(it) { mutableListOf() } += note }
        }
        // A stem holds notes of one value: black and half heads on one line are two voices' stems
        // drawn end to end (chords whose stem runs up to the melody's half note).
        val stemmed = byStem.flatMap { (line, onLine) ->
            onLine.groupBy { headValue(it.head.name) }.map { (value, notes) ->
                val top = minOf(line.y1, line.y2)
                val bottom = maxOf(line.y1, line.y2)
                val headsY = notes.map { it.head.box.centerY }.average()
                Draft(
                    measure = notes.first().measure,
                    box = union(notes.map { it.head.box }),
                    notes = notes,
                    stem = if (bottom - headsY < headsY - top) Stem.UP else Stem.DOWN,
                    stemLine = line,
                    type = value,
                )
            }
        }
        // Stemless heads that touch (a whole-note chord, its seconds side by side) are one chord.
        val groups = mutableListOf<MutableList<StaffNote>>()
        for (note in stemless.sortedBy { it.head.box.left }) {
            groups.lastOrNull { group ->
                group.first().measure == note.measure && group.any { note.head.box.left <= it.head.box.right + 0.2 * space }
            }?.add(note) ?: groups.add(mutableListOf(note))
        }
        val whole = groups.map { notes ->
            Draft(notes.first().measure, union(notes.map { it.head.box }), notes, type = notes.mapNotNull { headValue(it.head.name) }.maxByOrNull { it.length } ?: NoteType.QUARTER)
        }
        return stemmed + whole
    }

    /** The beams at the end of a chord's stem, from the outer one (at the stem's end) in. */
    private fun beamsAt(chord: Draft, beams: List<Beam>, space: Double): List<Beam> {
        val stem = chord.stemLine ?: return emptyList()
        val x = stem.x1
        val top = minOf(stem.y1, stem.y2)
        val bottom = maxOf(stem.y1, stem.y2)
        val up = chord.stem == Stem.UP
        val end = if (up) top else bottom
        // how far along the stem from its end a beam may lie: never past the heads
        val reach = minOf(4.0 * space, (bottom - top) - 1.0 * space)
        val inward = { beam: Beam -> if (up) beam.yAt(x) - end else end - beam.yAt(x) }
        // a beam ends on its stems: a sixteenth's short beam stops short of the next one
        return beams.filter { beam -> x >= beam.left - 0.2 * space && x <= beam.right + 0.2 * space && inward(beam) in -0.6 * space..reach }
            .sortedBy(inward)
    }

    /**
     * What each beam does at each stem it is on: it begins at its leftmost stem, ends at its
     * rightmost one, goes on between; a beam on one stem only is a hook, pointing to the side of
     * the stem it lies on.
     */
    private fun beamMarks(drafts: List<Draft>) {
        val stemsOf = java.util.IdentityHashMap<Beam, MutableList<Draft>>()
        drafts.forEach { draft -> draft.beams.forEach { stemsOf.getOrPut(it) { mutableListOf() } += draft } }
        for (draft in drafts) {
            val x = draft.stemLine?.x1 ?: continue
            draft.beamMarks = draft.beams.map { beam ->
                val xs = stemsOf[beam].orEmpty().mapNotNull { it.stemLine?.x1 }
                when {
                    xs.size <= 1 -> if (beam.right - x < x - beam.left) BeamMark.BACKWARD_HOOK else BeamMark.FORWARD_HOOK
                    xs.none { it < x } -> BeamMark.BEGIN
                    xs.none { it > x } -> BeamMark.END
                    else -> BeamMark.CONTINUE
                }
            }
        }
    }

    /**
     * The rolled chords: a wavy line drawn upright (in pieces, each taller than wide; a trill's line
     * lies flat) just before the chord, at its height.
     */
    private fun arpeggios(drafts: List<Draft>, symbols: List<DrawnGlyph>, space: Double) {
        val wiggles = symbols.filter { it.name.orEmpty().startsWith("wiggle") && it.box.height > it.box.width }
        for (chord in drafts.filter { !it.rest }) {
            chord.arpeggiate = wiggles.any { wiggle ->
                wiggle.box.right >= chord.box.left - 2.0 * space && wiggle.box.right <= chord.box.left + 0.2 * space &&
                    wiggle.box.bottom >= chord.box.top - space && wiggle.box.top <= chord.box.bottom + space
            }
        }
    }

    /** The notes and rests held by a fermata: the nearest one under it (or over it, a fermata below), in its width. */
    private fun fermatas(drafts: List<Draft>, symbols: List<DrawnGlyph>, space: Double) {
        symbols.filter { it.name.orEmpty().startsWith("fermata") }.forEach { fermata ->
            val below = fermata.name.orEmpty().contains("Below")
            drafts.filter {
                fermata.box.centerX >= it.box.left - 0.5 * space && fermata.box.centerX <= it.box.right + 0.5 * space &&
                    if (below) fermata.box.top >= it.box.centerY else fermata.box.bottom <= it.box.centerY
            }.minByOrNull { abs(it.box.centerY - fermata.box.centerY) }?.fermata = true
        }
    }

    /** A black note's value: a quarter, less by each flag or beam ([beams]) at the end of its stem. */
    private fun blackValue(chord: Draft, beams: List<Beam>, symbols: List<DrawnGlyph>, space: Double): NoteType {
        val stem = chord.stemLine ?: return NoteType.QUARTER
        val x = stem.x1
        val end = if (chord.stem == Stem.UP) minOf(stem.y1, stem.y2) else maxOf(stem.y1, stem.y2)
        val beamCount = beams.size
        val flagCount = symbols.filter { it.name.orEmpty().startsWith("flag") }
            .filter { flag ->
                abs(flag.box.left - x) < 0.5 * space &&
                    flag.box.bottom >= end - 1.0 * space && flag.box.top <= end + 1.0 * space
            }
            .maxOfOrNull { FLAG.find(it.name.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let(::flagsOf) ?: 0 } ?: 0
        return NoteType.withBeams(maxOf(beamCount, flagCount)) ?: NoteType.HUNDRED_TWENTY_EIGHTH
    }

    /** 8 → 1 flag, 16 → 2, 32 → 3... */
    private fun flagsOf(denominator: Int): Int = Integer.numberOfTrailingZeros(denominator) - 2

    private val FLAG = Regex("^flag(\\d+)")

    /** An x head (a drum's, with no pitch): SMuFL's noteheadX… and noteheadCircleX… */
    private fun crossHead(name: String?): Boolean = name?.let { it.startsWith("noteheadX") || it.startsWith("noteheadCircleX") } == true

    /** The value a head shows by itself (whole, half), null for a black one (its stem says the rest). */
    private fun headValue(name: String?): NoteType? = when {
        name == null -> null
        name.contains("DoubleWhole") -> NoteType.BREVE
        name.contains("Whole") -> NoteType.WHOLE
        name.contains("Half") -> NoteType.HALF
        else -> null
    }

    private fun rests(reading: StaffReading): List<Draft> {
        val staff = reading.staff
        return reading.symbols.filter { glyph ->
            // a rest drawn under a head is hidden by it: nothing a reader sees
            reading.notes.none { covered(glyph.box, it.head.box) >= 0.5 }
        }.mapNotNull { glyph ->
            val type = when (glyph.name) {
                "restDoubleWhole" -> NoteType.BREVE
                // drawn alike: a whole rest hangs from a line, a half rest sits on one
                "restWhole", "restHalf" -> if (!onLine(staff, glyph.box.top) && onLine(staff, glyph.box.bottom)) NoteType.HALF else NoteType.WHOLE
                "restQuarter" -> NoteType.QUARTER
                "rest8th" -> NoteType.EIGHTH
                "rest16th" -> NoteType.SIXTEENTH
                "rest32nd" -> NoteType.THIRTY_SECOND
                "rest64th" -> NoteType.SIXTY_FOURTH
                "rest128th" -> NoteType.HUNDRED_TWENTY_EIGHTH
                else -> return@mapNotNull null
            }
            Draft(reading.barlines.count { it < glyph.box.centerX }, glyph.box, type = type, rest = true)
        }
    }

    /** Whether [y] is on a line of the staff, or where a ledger line would be. */
    private fun onLine(staff: Staff, y: Double): Boolean {
        val halfSpaces = (staff.bottom - y) / (staff.space / 2)
        val step = Math.round(halfSpaces)
        return step % 2 == 0L && abs(halfSpaces - step) < 0.35
    }

    /**
     * The dots of each note or rest. A dot over or under a chord's heads (in their width, above or
     * below them) is its staccato: drawn like an augmentation dot, told apart by where it sits. The
     * other dots are augmentation dots: one belongs to the nearest note or rest on its left whose
     * head (or rest) it sits beside; repeat dots by a barline are not. Several in a row are a double
     * dot.
     */
    private fun dots(drafts: List<Draft>, reading: StaffReading, space: Double) {
        val owned = java.util.IdentityHashMap<Draft, MutableList<DrawnGlyph>>()
        val chords = drafts.filter { !it.rest }
        val allDots = reading.symbols.filter { it.name == "augmentationDot" }
        // repeat dots: by a barline, two one over the other, a space apart
        fun isRepeatDot(dot: DrawnGlyph) = reading.barlines.any { abs(it - dot.box.centerX) < 1.2 * space } &&
            allDots.any { other -> other !== dot && abs(other.box.centerX - dot.box.centerX) < 0.3 * space && abs(abs(other.box.centerY - dot.box.centerY) - space) < 0.3 * space }
        allDots
            .filterNot { isRepeatDot(it) }
            .filterNot { dot -> staccatoOf(dot, chords, space)?.also { it.staccato = true } != null }
            // another dot drawn on a head is some other mark
            .filter { dot -> reading.notes.none { overlap(it.head.box, dot.box) } }
            .sortedBy { it.box.left }
            .forEach { dot ->
                drafts.filter { draft ->
                    val gap = dot.box.left - draft.box.right
                    gap >= -0.2 * space && gap <= 2.5 * space + 1.2 * space * (owned[draft]?.size ?: 0) &&
                        if (draft.rest) dot.box.centerY >= draft.box.top - 0.5 * space && dot.box.centerY <= draft.box.bottom + 0.5 * space
                        else draft.notes.any { abs(it.head.box.centerY - dot.box.centerY) <= 0.75 * space }
                }.maxByOrNull { it.box.right }?.let { owned.getOrPut(it) { mutableListOf() } += dot }
            }
        for ((draft, list) in owned) {
            // one row of dots per head: the longest row is the chord's
            draft.dots = list.groupBy { Math.round(it.box.centerY / (0.5 * space)) }.values.maxOf { it.size }
        }
    }

    /**
     * The chord a dot is the staccato of: the nearest one it is over or under, in its heads' width
     * (pressed against the outer head, it can overlap its edge), and close: in the space next to
     * the outer head, or just past the stem's end.
     */
    private fun staccatoOf(dot: DrawnGlyph, chords: List<Draft>, space: Double): Draft? {
        val x = dot.box.centerX
        val y = dot.box.centerY
        return chords.filter {
            x >= it.box.left - 0.1 * space && x <= it.box.right + 0.1 * space &&
                (y < it.box.top + 0.25 * space || y > it.box.bottom - 0.25 * space) &&
                (minOf(abs(y - it.box.top), abs(y - it.box.bottom)) <= 1.0 * space ||
                    it.stemLine?.let { stem -> minOf(abs(y - stem.y1), abs(y - stem.y2)) <= 1.25 * space } == true)
        }
            .minByOrNull { minOf(abs(y - it.box.top), abs(y - it.box.bottom)) }
    }

    /**
     * Voices and onsets of one measure. Two voices when notes on two stems are struck together:
     * - a stem up over a stem down: the stems up are voice 1, down voice 2, a rest goes by the side
     *   of the middle line it lies on;
     * - two stems the same way (a melody over chords): the notes above the gap between them are
     *   voice 1, below it voice 2.
     * Else a rest drawn beside a chord, at its time, is another voice's (voice 2 under the chords,
     * voice 1 over them); else everything is one voice. A voice is silent until it shows: an event
     * drawn at the time of another voice's starts then (the rests before it are hidden).
     */
    private fun timed(items: List<Draft>, staff: Staff, tuplets: List<Tuplet>, ties: Set<StaffNote>): List<PlacedEvent> {
        val space = staff.space
        val chords = items.filter { !it.rest && !it.grace }
        // chords on two stems struck together: a stem up beside a stem down (heads side by side
        // for a second), or two stems on one side (a melody over chords, all stems up)
        val together = chords.flatMap { a ->
            chords.filter { b ->
                a !== b && (a.box.centerY < b.box.centerY || (a.box == b.box && a.stem == Stem.UP && b.stem == Stem.DOWN)) &&
                    abs(a.x - b.x) < (if (a.stem != b.stem && a.stem != Stem.NONE && b.stem != Stem.NONE) 1.3 else 0.3) * space
            }.map { b -> a to b }
        }
        val byStem = together.all { (upper, lower) -> upper.stem == Stem.UP && lower.stem == Stem.DOWN }
        // a whole note (no stem) struck with notes on stems: it is a voice, the notes on stems the
        // other, however low they go (no line between the voices then)
        val wholeVoice = when {
            byStem || together.isEmpty() -> null
            together.all { (upper, lower) -> lower.stem == Stem.NONE && upper.stem != Stem.NONE } -> 2
            together.all { (upper, lower) -> upper.stem == Stem.NONE && lower.stem != Stem.NONE } -> 1
            else -> null
        }
        // where the upper voice ends and the lower one begins, when the stems do not say it
        val split = together.map { (upper, lower) -> (upper.box.bottom + lower.box.top) / 2 }.average()
        val middle = staff.top + 2 * space
        // rest → whether it lies under the chord it is drawn beside
        val besideRests = java.util.IdentityHashMap<Draft, Boolean>()
        // the stems of the chords a rest is drawn over (they are the lower voice), and under (the upper one)
        val lowerStems = mutableSetOf<Stem>()
        val upperStems = mutableSetOf<Stem>()
        if (together.isEmpty()) {
            items.filter { it.rest }.forEach { rest ->
                chords.firstOrNull { sharedWidth(rest.box, it.box) >= 0.3 * space }?.let { chord ->
                    val under = rest.box.centerY > chord.box.centerY
                    besideRests[rest] = under
                    (if (under) upperStems else lowerStems) += chord.stem
                }
            }
        }
        // the stems say the voices when the chords beside rests have one stem (or none: a whole
        // note) and other chords another: a half or whole note under a rest, the notes after the
        // rest stem up. The chords like those beside rests are their voice, the others the other one.
        val besideStem = (lowerStems + upperStems).singleOrNull()
        val stemVoices = besideStem != null && chords.any { it.stem != besideStem }
        fun voiceOf(draft: Draft): Int = when {
            together.isEmpty() && besideRests.isEmpty() -> 1
            together.isEmpty() && stemVoices && !draft.rest -> {
                val besideVoice = if (lowerStems.isNotEmpty()) 2 else 1
                if (draft.stem == besideStem) besideVoice else 3 - besideVoice
            }
            // a rest beside no chord, when rests lie under chords: the voice of the rest at its height
            // (a voice's rests are drawn at one height: a whole note over chords, their rests under it)
            together.isEmpty() -> besideRests[draft]?.let { under -> if (under) 2 else 1 }
                ?: when {
                    !besideRests.containsValue(true) -> 2
                    draft.rest -> besideRests.entries.minBy { abs(it.key.box.centerY - draft.box.centerY) }.let { if (it.value) 2 else 1 }
                    else -> 1
                }
            wholeVoice != null && !draft.rest -> if (draft.stem == Stem.NONE) wholeVoice else 3 - wholeVoice
            !byStem -> if (draft.box.centerY > split) 2 else 1
            draft.stem == Stem.UP -> 1
            draft.stem == Stem.DOWN -> 2
            else -> if (draft.box.centerY > middle + 0.5 * space) 2 else 1
        }
        val result = mutableListOf<PlacedEvent>()
        // the voice with the most events first: the others start where they line up with it
        val timedBefore = mutableListOf<Pair<Draft, Fraction>>()
        for ((voice, inVoice) in items.groupBy(::voiceOf).entries.sortedByDescending { it.value.size }) {
            var time = Fraction.ZERO
            val sorted = inVoice.sortedBy { it.x }
            val ratios = java.util.IdentityHashMap<Draft, Fraction>()
            for (tuplet in tuplets) {
                val under = sorted.filter { !it.grace && tuplet.holds(it, space) }
                if (tuplet.fits(under)) under.forEach { ratios[it] = tuplet.ratio }
            }
            for (draft in sorted) {
                if (!draft.grace) {
                    timedBefore.filter { (other, _) -> !other.grace && sharedWidth(draft.box, other.box) >= 0.3 * space }
                        .maxOfOrNull { it.second }?.let { if (it > time) time = it }
                }
                val duration = Duration(draft.type ?: NoteType.QUARTER, draft.dots, ratios[draft] ?: Fraction.of(1))
                val event = if (draft.rest) {
                    Rest(duration, time, voice, wholeMeasure = duration.type == NoteType.WHOLE && sorted.size == 1, fermata = draft.fermata)
                } else {
                    val notes = draft.notes.sortedBy { it.step }
                    Chord(
                        notes.map { it.pitch }, duration, draft.stem, time, voice, draft.grace,
                        if (draft.staccato) setOf(Articulation.STACCATO) else emptySet(),
                        draft.beamMarks,
                        draft.notes.firstOrNull()?.octaves ?: 0,
                        notes.indices.filter { notes[it] in ties }.toSet(),
                        notes.withIndex().mapNotNull { (i, note) -> note.accidental?.let { i to it } }.toMap(),
                        draft.arpeggiate,
                        draft.fermata,
                        crossHeads = notes.indices.filter { crossHead(notes[it].head.name) }.toSet(),
                    )
                }
                result += PlacedEvent(event, draft.box)
                timedBefore += draft to time
                if (!draft.grace) time += duration.length
            }
        }
        return result.sortedWith(compareBy<PlacedEvent>({ it.event.onset }, { it.event.voice }))
    }

    /**
     * A tuplet's number and the stretch of the staff it covers, from [left] to [right]: its notes
     * last [ratio] of what they show (2/3 for a triplet).
     */
    private class Tuplet(val count: Int, val left: Double, val right: Double) {
        val ratio: Fraction = Fraction.of(normal(count).toLong(), count.toLong())

        /**
         * Whether the note or rest is under it: its stem (or its middle) between the ends; a rest
         * at least partly (a bracket's hook can stand right of the middle of the rest it starts on).
         */
        fun holds(draft: Draft, space: Double): Boolean {
            if (draft.rest) return minOf(draft.box.right, right) - maxOf(draft.box.left, left) >= 0.25 * space
            val x = draft.stemLine?.x1 ?: draft.box.centerX
            return (x >= left - 0.1 * space && x <= right + 0.1 * space) || draft.box.centerX in left..right
        }

        /**
         * Whether the notes under it make a tuplet: together they last [count] times one note
         * value (a quarter and an eighth under "3" are three eighths), which a fingering's "3" over
         * one note does not.
         */
        fun fits(under: List<Draft>): Boolean {
            if (under.isEmpty()) return false
            val written = under.fold(Fraction.ZERO) { sum, it -> sum + Duration(it.type ?: NoteType.QUARTER, it.dots).length }
            val unit = written * Fraction.of(1, count.toLong())
            return unit.numerator == 1L && unit.denominator.countOneBits() == 1
        }

        private companion object {
            /** How many of the written notes the tuplet's take the time of: 3 in the time of 2... */
            fun normal(count: Int): Int = when (count) {
                2 -> 3
                4 -> 3
                else -> Integer.highestOneBit(count - 1)
            }
        }
    }

    /**
     * The tuplets of a staff: a number ("tuplet3"), and the notes it covers. Those are between the
     * hooks of its bracket when it has one; else under the beam it sits on.
     */
    private fun tuplets(reading: StaffReading, lines: List<DrawnLine>, beams: List<Beam>, space: Double): List<Tuplet> =
        reading.symbols.mapNotNull { glyph ->
            val count = TUPLET.find(glyph.name.orEmpty())?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
            val number = glyph.box
            val hooks = lines.filter { it.isVertical && it.length <= 1.2 * space && abs((it.y1 + it.y2) / 2 - number.centerY) <= 1.5 * space }
            val left = hooks.filter { it.x1 < number.left && number.left - it.x1 <= 8 * space }.maxOfOrNull { it.x1 }
            val right = hooks.filter { it.x1 > number.right && it.x1 - number.right <= 8 * space }.minOfOrNull { it.x1 }
            left?.let { l -> right?.let { r -> Tuplet(count, l, r) } }
                ?: beams.filter { number.centerX in it.left..it.right && abs(it.yAt(number.centerX) - number.centerY) <= 2.5 * space }
                    .minByOrNull { abs(it.yAt(number.centerX) - number.centerY) }
                    ?.let { Tuplet(count, it.left, it.right) }
        }

    private val TUPLET = Regex("^tuplet([0-9])$")

    /**
     * The thin upright lines that may be stems, a stem drawn in pieces (one from the heads, one on
     * to the beam) joined into one.
     */
    private fun stems(lines: List<DrawnLine>, space: Double): List<DrawnLine> {
        val pieces = lines.filter { it.isVertical && it.width < 0.3 * space && it.length >= 0.5 * space }
            .map { DrawnLine(it.x1, minOf(it.y1, it.y2), it.x1, maxOf(it.y1, it.y2), it.width) }
            .sortedBy { it.x1 }
        // the pieces on one x, then each run of them that touch, top to bottom
        val columns = mutableListOf<MutableList<DrawnLine>>()
        for (piece in pieces) {
            columns.lastOrNull()?.takeIf { abs(it.first().x1 - piece.x1) < 0.1 * space }?.add(piece) ?: columns.add(mutableListOf(piece))
        }
        val joined = columns.flatMap { column ->
            column.sortedBy { it.y1 }.fold(mutableListOf<DrawnLine>()) { runs, piece ->
                runs.lastOrNull()
                    ?.takeIf { piece.y1 <= it.y2 + 0.2 * space }
                    ?.let { runs[runs.lastIndex] = DrawnLine(it.x1, it.y1, it.x1, maxOf(it.y2, piece.y2), it.width) }
                    ?: runs.add(piece)
                runs
            }
        }
        return joined.filter { it.length >= 1.5 * space }
    }

    /** A beam: a thick bar, flat or slanting, its middle at [yLeft] on its left end and [yRight] on its right one. */
    internal class Beam(val left: Double, val right: Double, val yLeft: Double, val yRight: Double) {
        fun yAt(x: Double): Double = yLeft + (yRight - yLeft) * ((x - left) / (right - left)).coerceIn(0.0, 1.0)
    }

    /**
     * The page's beams: a flat one may come as a thick line (a filled rectangle read as a line), a
     * slanting one as a four-cornered shape with upright ends; half a space thick or so.
     */
    internal fun beams(page: PageDrawing, space: Double): List<Beam> {
        val thick = 0.3 * space..0.9 * space
        val flat = page.lines.filter { it.isHorizontal && it.width in thick && it.length >= 0.8 * space }
            .map { Beam(minOf(it.x1, it.x2), maxOf(it.x1, it.x2), it.y1, it.y2) }
        val slanted = page.shapes.filter { !it.curved }.mapNotNull { shape ->
            val corners = shape.corners()
            if (corners.size != 4) return@mapNotNull null
            val sorted = corners.sortedBy { it.x }
            val leftEnd = sorted.take(2)
            val rightEnd = sorted.takeLast(2)
            if (abs(leftEnd[0].x - leftEnd[1].x) > 0.1 * space || abs(rightEnd[0].x - rightEnd[1].x) > 0.1 * space) return@mapNotNull null
            if (rightEnd[0].x - leftEnd[0].x < 0.8 * space) return@mapNotNull null
            if (abs(leftEnd[0].y - leftEnd[1].y) !in thick || abs(rightEnd[0].y - rightEnd[1].y) !in thick) return@mapNotNull null
            Beam(leftEnd[0].x, rightEnd[0].x, (leftEnd[0].y + leftEnd[1].y) / 2, (rightEnd[0].y + rightEnd[1].y) / 2)
        }
        // MuseScore 3 draws some beams twice, one over the other
        val grid = 0.05 * space
        val distinct = (flat + slanted).distinctBy { beam -> listOf(beam.left, beam.right, beam.yLeft, beam.yRight).map { Math.round(it / grid) } }
        return joined(distinct, space)
    }

    /**
     * Beams drawn in pieces from stem to stem (Verovio does) made whole: a piece that starts where
     * another ends, at its height and its slant, goes on with it. Otherwise the stem where they meet
     * would count each piece as a beam of its own.
     */
    private fun joined(beams: List<Beam>, space: Double): List<Beam> {
        val result = mutableListOf<Beam>()
        for (beam in beams.sortedBy { it.left }) {
            val index = result.indexOfFirst { before ->
                abs(beam.left - before.right) < 0.1 * space &&
                    abs(beam.yLeft - before.yRight) < 0.1 * space &&
                    abs(slope(beam) - slope(before)) < 0.05
            }
            if (index < 0) {
                result += beam
            } else {
                val before = result[index]
                result[index] = Beam(before.left, beam.right, before.yLeft, beam.yRight)
            }
        }
        return result
    }

    private fun slope(beam: Beam): Double = (beam.yRight - beam.yLeft) / maxOf(beam.right - beam.left, 1e-6)

    private fun overlap(a: Box, b: Box): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /** How wide [a] and [b] lie over each other (negative when apart). */
    private fun sharedWidth(a: Box, b: Box): Double = minOf(a.right, b.right) - maxOf(a.left, b.left)

    /** How much of [a]'s area [b] covers, from 0 to 1. */
    private fun covered(a: Box, b: Box): Double {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val area = a.width * a.height
        return if (width <= 0 || height <= 0 || area <= 0) 0.0 else width * height / area
    }

    private fun union(boxes: List<Box>): Box =
        Box(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
}
