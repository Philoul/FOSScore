package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Articulation
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.BarStyle
import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Duration
import com.musigatto.fosscore.pdfscore.music.Event
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.NoteType
import com.musigatto.fosscore.pdfscore.music.Pitch
import com.musigatto.fosscore.pdfscore.music.Rest
import com.musigatto.fosscore.pdfscore.music.Stem
import com.musigatto.fosscore.pdfscore.music.TimeSignature

/**
 * Writes a [ScoreReading] as a MusicXML 4.0 partwise score. Each measure and note gets an id
 * ([ScoreReading.measureId], [ScoreReading.noteId]) that [ScoreReading.notePlaces] finds on the page.
 * What is not read yet is not written: the accidentals written are the ones drawn (a renderer
 * draws only those), tuplets get their time-modification but no bracket, and there are no slurs,
 * dynamics or lyrics. The pitches are the ones heard: under an octave line they are an octave off
 * what is drawn, and an octave-shift says so.
 */
object MusicXmlWriter {
    fun write(score: ScoreReading, title: String? = null): String {
        val divisions = divisions(score)
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" \"http://www.musicxml.org/dtds/partwise.dtd\">\n")
        out.append("<score-partwise version=\"4.0\">\n")
        // the title the page writes, else the one given (the file's name)
        (score.header.title ?: title)?.let { out.append("  <work><work-title>${escape(it)}</work-title></work>\n") }
        val creators = listOfNotNull(score.header.composer?.let { "composer" to it }, score.header.lyricist?.let { "lyricist" to it })
        if (creators.isNotEmpty()) {
            out.append("  <identification>")
            creators.forEach { (type, name) -> out.append("<creator type=\"$type\">${escape(name)}</creator>") }
            out.append("</identification>\n")
        }
        out.append("  <part-list>\n")
        score.parts.forEachIndexed { p, part ->
            out.append("    <score-part id=\"P${p + 1}\"><part-name>${escape(part.name ?: "Part ${p + 1}")}</part-name></score-part>\n")
        }
        out.append("  </part-list>\n")
        var firstStaff = 0
        score.parts.forEachIndexed { p, part ->
            out.append("  <part id=\"P${p + 1}\">\n")
            var previous: List<MeasureStaff>? = null
            // what runs on from measure to measure on each staff: its octave line, its ties
            val running = Array(part.staves) { Running() }
            score.measures.forEachIndexed { index, measure ->
                val staves = (0 until part.staves).mapNotNull { measure.staves.getOrNull(firstStaff + it) }
                writeMeasure(out, measure, staves, previous, firstStaff, part.staves, divisions, running, last = index == score.measures.lastIndex)
                previous = staves
            }
            out.append("  </part>\n")
            firstStaff += part.staves
        }
        out.append("</score-partwise>\n")
        return out.toString()
    }

    private fun writeMeasure(
        out: StringBuilder,
        measure: ScoreMeasure,
        staves: List<MeasureStaff>,
        previous: List<MeasureStaff>?,
        firstStaff: Int,
        staffCount: Int,
        divisions: Long,
        running: Array<Running>,
        last: Boolean,
    ) {
        out.append("    <measure number=\"${measure.rank}\" id=\"${ScoreReading.measureId(measure.rank)}\">\n")
        if (measure.rank > 1 && (measure.newPage || measure.newSystem)) {
            out.append(if (measure.newPage) "      <print new-page=\"yes\"/>\n" else "      <print new-system=\"yes\"/>\n")
        }
        // what changed since the measure before (everything in the first one)
        val first = staves.firstOrNull()
        // an ending that starts here, a repeat that starts here: one left barline
        val left = StringBuilder()
        if (first?.repeatStart == true) left.append("<bar-style>heavy-light</bar-style>")
        first?.endingStart?.let { left.append("<ending number=\"$it\" type=\"start\"/>") }
        if (first?.repeatStart == true) left.append("<repeat direction=\"forward\"/>")
        if (left.isNotEmpty()) out.append("      <barline location=\"left\">").append(left).append("</barline>\n")
        val attributes = StringBuilder()
        if (previous == null) {
            attributes.append("        <divisions>$divisions</divisions>\n")
        }
        first?.let { staff ->
            if (previous == null || previous.firstOrNull()?.key != staff.key) attributes.append(key(staff.key))
            staff.time?.takeIf { previous == null || previous.firstOrNull()?.time != it }?.let { attributes.append(time(it)) }
        }
        if (previous == null && staffCount > 1) attributes.append("        <staves>$staffCount</staves>\n")
        staves.forEachIndexed { s, staff ->
            val before = previous?.getOrNull(s)?.let { it.clefChanges.lastOrNull()?.value ?: it.clef }
            staff.clef?.takeIf { previous == null || before != it }?.let { attributes.append(clef(it, s + 1, staffCount)) }
        }
        if (attributes.isNotEmpty()) out.append("      <attributes>\n").append(attributes).append("      </attributes>\n")

        val length = measureLength(staves)
        // with a time signature, a voice read longer than the measure keeps what fits in it: the
        // rest would make the measure last longer than its signature (an error of the reading
        // shifting the whole music after it, for the player)
        val fixed = staves.any { it.time != null }
        var cursor = Fraction.ZERO
        staves.forEachIndexed { s, staff ->
            val voices = staff.events.withIndex()
                .mapNotNull { (index, placed) ->
                    (if (fixed) cut(placed.event, length) else placed.event)?.let { IndexedValue(index, placed.copy(event = it)) }
                }
                .groupBy { it.value.event.voice }.toSortedMap()
            for ((voice, events) in voices) {
                if (cursor > Fraction.ZERO) out.append("      <backup><duration>${units(cursor, divisions)}</duration></backup>\n")
                cursor = Fraction.ZERO
                val changes = if (voice == voices.firstKey()) staff.clefChanges.toMutableList() else mutableListOf()
                val directions = if (voice == voices.firstKey()) staff.directions.toMutableList() else mutableListOf()
                val ordered = events.sortedWith(compareBy({ it.value.event.onset }, { if ((it.value.event as? Chord)?.grace == true) 0 else 1 }))
                val tuplets = tupletMarks(ordered.map { it.value.event })
                for ((position, indexed) in ordered.withIndex()) {
                    val (index, placed) = indexed
                    val event = placed.event
                    while (changes.isNotEmpty() && changes.first().x < placed.box.left) {
                        out.append("      <attributes>\n").append(clef(changes.removeAt(0).value, s + 1, staffCount)).append("      </attributes>\n")
                    }
                    if (event.onset > cursor) {
                        out.append("      <forward><duration>${units(event.onset - cursor, divisions)}</duration></forward>\n")
                        cursor = event.onset
                    }
                    // what is written over the music, before the note it stands over
                    while (directions.isNotEmpty() && directions.first().x <= placed.box.left + DIRECTION_REACH) {
                        out.append(direction(directions.removeAt(0), s + 1, staffCount))
                    }
                    // the octave line follows the staff's first voice
                    if (event is Chord && voice == voices.firstKey() && event.ottava != running[s].ottava) {
                        if (running[s].ottava != 0) out.append(octaveShift("stop", running[s].ottava, s + 1, staffCount))
                        if (event.ottava != 0) out.append(octaveShift(if (event.ottava > 0) "down" else "up", event.ottava, s + 1, staffCount))
                        running[s].ottava = event.ottava
                    }
                    val ids = { k: Int -> ScoreReading.noteId(measure.rank, firstStaff + s + 1, index, k) }
                    val musicXmlVoice = s * 4 + voice
                    writeEvent(out, event, length, divisions, musicXmlVoice, if (staffCount > 1) s + 1 else null, staff.clef, ids, running[s].tied, tuplets[position].orEmpty())
                    if (!(event is Chord && event.grace)) cursor = event.onset + duration(event, length)
                }
                changes.forEach { out.append("      <attributes>\n").append(clef(it.value, s + 1, staffCount)).append("      </attributes>\n") }
                directions.forEach { out.append(direction(it, s + 1, staffCount)) }
            }
            // a staff with nothing read fills its measure with a rest, so the parts keep in step
            if (voices.isEmpty()) {
                if (cursor > Fraction.ZERO) out.append("      <backup><duration>${units(cursor, divisions)}</duration></backup>\n")
                out.append("      <note><rest measure=\"yes\"/><duration>${units(length, divisions)}</duration><voice>${s * 4 + 1}</voice>")
                if (staffCount > 1) out.append("<staff>${s + 1}</staff>")
                out.append("</note>\n")
                cursor = length
            }
            // a line still running at the score's end stops there
            if (last && running[s].ottava != 0) {
                out.append(octaveShift("stop", running[s].ottava, s + 1, staffCount))
                running[s].ottava = 0
            }
        }
        // a repeat's end (else a double or final barline: a plain one goes without saying) and an
        // ending that stops here: one right barline
        val right = StringBuilder()
        val style = if (first?.repeatEnd == true) BarStyle.LIGHT_HEAVY else first?.barStyle?.takeIf { it != BarStyle.REGULAR }
        style?.let { right.append("<bar-style>${it.musicXml}</bar-style>") }
        first?.endingStop?.let { (numbers, closed) -> right.append("<ending number=\"$numbers\" type=\"${if (closed) "stop" else "discontinue"}\"/>") }
        if (first?.repeatEnd == true) right.append("<repeat direction=\"backward\"/>")
        if (right.isNotEmpty()) out.append("      <barline location=\"right\">").append(right).append("</barline>\n")
        out.append("    </measure>\n")
    }

    private fun writeEvent(
        out: StringBuilder,
        event: Event,
        length: Fraction,
        divisions: Long,
        voice: Int,
        staff: Int?,
        clef: Clef?,
        id: (Int) -> String,
        tied: MutableSet<Pitch>,
        tuplet: String = "",
    ) {
        val tail = StringBuilder()
        val duration = event.duration
        if (event is Rest && event.wholeMeasure) {
            out.append("      <note><rest measure=\"yes\"/><duration>${units(length, divisions)}</duration><voice>$voice</voice>")
            staff?.let { out.append("<staff>$it</staff>") }
            out.append("</note>\n")
            return
        }
        tail.append("<voice>$voice</voice><type>${duration.type.musicXml}</type>")
        repeat(duration.dots) { tail.append("<dot/>") }
        // after the accidental, in MusicXML's order
        val after = StringBuilder()
        if (duration.tuplet != Fraction.of(1)) {
            after.append("<time-modification><actual-notes>${duration.tuplet.denominator}</actual-notes>")
            after.append("<normal-notes>${duration.tuplet.numerator}</normal-notes></time-modification>")
        }
        when (event) {
            is Rest -> {
                out.append("      <note><rest/><duration>${units(duration.length, divisions)}</duration>").append(tail).append(after)
                staff?.let { out.append("<staff>$it</staff>") }
                if (event.fermata || tuplet.isNotEmpty()) out.append("<notations>").append(tuplet).append(if (event.fermata) FERMATA else "").append("</notations>")
                out.append("</note>\n")
            }
            is Chord -> {
                when (event.stem) {
                    Stem.UP -> after.append("<stem>up</stem>")
                    Stem.DOWN -> after.append("<stem>down</stem>")
                    Stem.NONE -> Unit
                }
                staff?.let { after.append("<staff>$it</staff>") }
                // the beams once, on the chord's first note (as MuseScore writes them)
                val beams = event.beams.withIndex().joinToString("") { (level, mark) -> "<beam number=\"${level + 1}\">${mark.musicXml}</beam>" }
                val articulations = if (Articulation.STACCATO in event.articulations) "<articulations><staccato/></articulations>" else ""
                event.pitches.forEachIndexed { k, pitch ->
                    // a tie stops on the next note of its pitch, and may start again there
                    val stop = pitch != null && !event.grace && tied.remove(pitch)
                    val start = pitch != null && !event.grace && k in event.ties
                    if (start && pitch != null) tied += pitch
                    val ties = listOf("stop" to stop, "start" to start).filter { it.second }.map { it.first }
                    out.append("      <note id=\"${id(k)}\">")
                    // a grace eighth alone is an acciaccatura, drawn slashed
                    if (event.grace) out.append(if (event.duration.type == NoteType.EIGHTH && event.beams.isEmpty()) "<grace slash=\"yes\"/>" else "<grace/>")
                    if (k > 0) out.append("<chord/>")
                    out.append(pitchElement(pitch, clef))
                    if (!event.grace) out.append("<duration>${units(duration.length, divisions)}</duration>")
                    ties.forEach { out.append("<tie type=\"$it\"/>") }
                    out.append(tail)
                    event.accidentals[k]?.let { alter -> ACCIDENTALS[alter]?.let { out.append("<accidental>$it</accidental>") } }
                    out.append(after)
                    if (k == 0) out.append(beams)
                    val notations = ties.joinToString("") { "<tied type=\"$it\"/>" } + (if (k == 0) tuplet else "") + articulations +
                        (if (event.arpeggiate) "<arpeggiate/>" else "") + (if (event.fermata && k == 0) FERMATA else "")
                    if (notations.isNotEmpty()) out.append("<notations>").append(notations).append("</notations>")
                    // the syllable once, on the chord's first note
                    event.lyric?.takeIf { k == 0 }?.let { out.append("<lyric number=\"1\"><syllabic>${it.syllabic.musicXml}</syllabic><text>${escape(it.text)}</text></lyric>") }
                    out.append("</note>\n")
                }
            }
        }
    }

    /**
     * Where the tuplets of a voice's [events] (in order) start and stop, by position: MusicXML's
     * `<tuplet>` notations, which Verovio needs to play time-modified notes at their time (else it
     * plays them at their written value). A tuplet is a run of notes and rests of one ratio; it
     * closes when what is written in it makes its actual notes of its smallest value (three eighths
     * for a triplet of eighths, a quarter and an eighth too), or when the run ends.
     */
    internal fun tupletMarks(events: List<Event>): Map<Int, String> {
        val marks = HashMap<Int, String>()
        var start = -1
        var written = Fraction.ZERO
        var smallest = Fraction.ZERO
        fun close(last: Int) {
            marks[start] = if (start == last) START + STOP else START
            if (last != start) marks[last] = STOP
            start = -1
        }
        events.forEachIndexed { i, event ->
            if ((event as? Chord)?.grace == true) return@forEachIndexed
            val ratio = event.duration.tuplet
            if (start >= 0 && (ratio == Fraction.of(1) || ratio != events[start].duration.tuplet)) close(i - 1)
            if (ratio == Fraction.of(1)) return@forEachIndexed
            val value = Duration(event.duration.type, event.duration.dots).length
            if (start < 0) {
                start = i
                written = Fraction.ZERO
                smallest = value
            }
            written += value
            if (value < smallest) smallest = value
            if (written >= smallest * Fraction.of(ratio.denominator)) close(i)
        }
        if (start >= 0) close(events.lastIndex)
        return marks
    }

    private const val START = "<tuplet type=\"start\" bracket=\"no\"/>"
    private const val STOP = "<tuplet type=\"stop\"/>"

    /** MusicXML's accidentals by the alter they show. */
    private val ACCIDENTALS = mapOf(-2 to "flat-flat", -1 to "flat", 0 to "natural", 1 to "sharp", 2 to "double-sharp")

    /** A note's pitch, or where it sits on a percussion staff (or unknown: no clef read). */
    private fun pitchElement(pitch: Pitch?, clef: Clef?): String = when {
        pitch == null -> "<unpitched/>"
        clef?.sign == Clef.Sign.PERCUSSION ->
            "<unpitched><display-step>${Pitch.STEPS[pitch.step]}</display-step><display-octave>${pitch.octave}</display-octave></unpitched>"
        else -> "<pitch><step>${Pitch.STEPS[pitch.step]}</step>" +
            (if (pitch.alter != 0) "<alter>${pitch.alter}</alter>" else "") + "<octave>${pitch.octave}</octave></pitch>"
    }

    private const val FERMATA = "<fermata type=\"upright\"/>"

    /** How far right of a note (in points) a direction may start and still stand over it. */
    private const val DIRECTION_REACH = 5.0

    /**
     * A direction over staff [staff]: words, or a metronome mark with the tempo it sets (MusicXML's
     * sound tempo is in quarters a minute: a dotted quarter at 40 is 60).
     */
    /** The jump words say, as MusicXML's sound attributes: "D.C." back to the start, "D.S." to the segno, "To Coda", "Fine". */
    private fun jump(text: String): String? {
        val words = text.trim().lowercase()
        return when {
            Regex("""^(d\.\s?c\.|da capo)""").containsMatchIn(words) -> "dacapo=\"yes\""
            Regex("""^(d\.\s?s\.|dal segno)""").containsMatchIn(words) -> "dalsegno=\"segno\""
            words.startsWith("to coda") -> "tocoda=\"coda\""
            words.startsWith("fine") -> "fine=\"yes\""
            else -> null
        }
    }

    private fun direction(direction: Direction, staff: Int, staffCount: Int): String {
        val on = if (staffCount > 1) "<staff>$staff</staff>" else ""
        return when (direction) {
            // a jump's target, and the sound a player jumps by
            is Mark -> when (direction.kind) {
                Mark.Kind.SEGNO -> "      <direction placement=\"above\"><direction-type><segno/></direction-type>$on<sound segno=\"segno\"/></direction>\n"
                Mark.Kind.CODA -> "      <direction placement=\"above\"><direction-type><coda/></direction-type>$on<sound coda=\"coda\"/></direction>\n"
            }
            is Words -> "      <direction placement=\"above\"><direction-type><words>${escape(direction.text)}</words></direction-type>$on" +
                (jump(direction.text)?.let { "<sound $it/>" } ?: "") + "</direction>\n"
            is TempoMark -> {
                val quarters = Duration(direction.beat, direction.dots).length * Fraction.of(4L * direction.perMinute)
                val tempo = quarters.numerator.toDouble() / quarters.denominator
                val sound = if (tempo == Math.floor(tempo)) tempo.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", tempo)
                "      <direction placement=\"above\"><direction-type><metronome><beat-unit>${direction.beat.musicXml}</beat-unit>" +
                    "<beat-unit-dot/>".repeat(direction.dots) + "<per-minute>${direction.perMinute}</per-minute></metronome></direction-type>" +
                    "$on<sound tempo=\"$sound\"/></direction>\n"
            }
        }
    }

    /** What runs on over barlines on a staff: the octave line in force, the pitches tied on to their next note. */
    private class Running {
        var ottava = 0
        val tied = mutableSetOf<Pitch>()
    }

    /**
     * An octave line starting or stopping on staff [staff]: [octaves] the notes sound off what is
     * drawn; MusicXML calls an "8va" a shift down (the notes are drawn lower than heard).
     */
    private fun octaveShift(type: String, octaves: Int, staff: Int, staffCount: Int): String {
        val on = if (staffCount > 1) "<staff>$staff</staff>" else ""
        val size = 7 * kotlin.math.abs(octaves) + 1
        return "      <direction><direction-type><octave-shift type=\"$type\" size=\"$size\"/></direction-type>$on</direction>\n"
    }

    private fun key(key: Key) = "        <key><fifths>${key.fifths}</fifths></key>\n"

    private fun time(time: TimeSignature) = "        <time><beats>${time.beats}</beats><beat-type>${time.beatType}</beat-type></time>\n"

    private fun clef(clef: Clef, staff: Int, staffCount: Int): String {
        val number = if (staffCount > 1) " number=\"$staff\"" else ""
        val sign = when (clef.sign) {
            Clef.Sign.G -> "G"
            Clef.Sign.F -> "F"
            Clef.Sign.C -> "C"
            Clef.Sign.PERCUSSION -> "percussion"
        }
        val line = if (clef.sign == Clef.Sign.PERCUSSION) "" else "<line>${clef.line}</line>"
        val octave = if (clef.octaveShift != 0) "<clef-octave-change>${clef.octaveShift}</clef-octave-change>" else ""
        return "        <clef$number><sign>$sign</sign>$line$octave</clef>\n"
    }

    /**
     * How long a measure is: its time signature's length, else (none drawn yet) as long as its
     * longest voice, else a whole.
     */
    private fun measureLength(staves: List<MeasureStaff>): Fraction =
        staves.firstNotNullOfOrNull { it.time }?.measureLength
            ?: staves.flatMap { it.events }.filter { (it.event as? Rest)?.wholeMeasure != true && (it.event as? Chord)?.grace != true }
                .maxOfOrNull { it.event.onset + it.event.duration.length }
            ?: Fraction.of(1)

    /**
     * [event] within a measure [length] long: as it is when it ends in time, null when it starts
     * at the end or after, shortened to the longest value that fits when it crosses the end (the
     * MusicXML plays; the PDF shows the notes as drawn).
     */
    private fun cut(event: Event, length: Fraction): Event? {
        if (event is Rest && event.wholeMeasure) return event
        if (event is Chord && event.grace) return event.takeIf { it.onset < length }
        val left = length - event.onset
        if (left <= Fraction.ZERO) return null
        if (event.duration.length <= left) return event
        val shorter = NoteType.entries.flatMap { type -> (0..2).map { Duration(type, it) } }
            .filter { it.length <= left }.maxByOrNull { it.length } ?: return null
        return when (event) {
            is Chord -> event.copy(duration = shorter)
            is Rest -> event.copy(duration = shorter)
        }
    }

    private fun duration(event: Event, length: Fraction): Fraction =
        if (event is Rest && event.wholeMeasure) length else event.duration.length

    /** Divisions per quarter note: enough for every length and onset of the score to be a whole number of them. */
    private fun divisions(score: ScoreReading): Long {
        var divisions = 1L
        fun need(length: Fraction) {
            val quarters = length * Fraction.of(4)
            divisions = lcm(divisions, quarters.denominator)
        }
        score.measures.forEach { measure ->
            measure.staves.forEach { staff ->
                staff.time?.let { need(it.measureLength) }
                staff.events.forEach { need(it.event.duration.length); need(it.event.onset) }
            }
        }
        return divisions
    }

    private fun units(length: Fraction, divisions: Long): Long {
        val quarters = length * Fraction.of(4 * divisions)
        return quarters.numerator / quarters.denominator
    }

    private fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
