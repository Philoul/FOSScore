package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Articulation
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Event
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.Pitch
import com.musigatto.fosscore.pdfscore.music.Rest
import com.musigatto.fosscore.pdfscore.music.Stem
import com.musigatto.fosscore.pdfscore.music.TimeSignature

/**
 * Writes a [ScoreReading] as a MusicXML 4.0 partwise score. Each measure and note gets an id
 * ([ScoreReading.measureId], [ScoreReading.noteId]) that [ScoreReading.notePlaces] finds on the page.
 * What is not read yet is not written: accidentals are left for the reader to work out from the
 * pitches, tuplets get their time-modification but no bracket, and there are no ties, slurs,
 * dynamics or lyrics.
 */
object MusicXmlWriter {
    fun write(score: ScoreReading, title: String? = null): String {
        val divisions = divisions(score)
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" \"http://www.musicxml.org/dtds/partwise.dtd\">\n")
        out.append("<score-partwise version=\"4.0\">\n")
        title?.let { out.append("  <work><work-title>${escape(it)}</work-title></work>\n") }
        out.append("  <part-list>\n")
        score.parts.indices.forEach { p ->
            out.append("    <score-part id=\"P${p + 1}\"><part-name>Part ${p + 1}</part-name></score-part>\n")
        }
        out.append("  </part-list>\n")
        var firstStaff = 0
        score.parts.forEachIndexed { p, part ->
            out.append("  <part id=\"P${p + 1}\">\n")
            var previous: List<MeasureStaff>? = null
            for (measure in score.measures) {
                val staves = (0 until part.staves).mapNotNull { measure.staves.getOrNull(firstStaff + it) }
                writeMeasure(out, measure, staves, previous, firstStaff, part.staves, divisions)
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
    ) {
        out.append("    <measure number=\"${measure.rank}\" id=\"${ScoreReading.measureId(measure.rank)}\">\n")
        if (measure.rank > 1 && (measure.newPage || measure.newSystem)) {
            out.append(if (measure.newPage) "      <print new-page=\"yes\"/>\n" else "      <print new-system=\"yes\"/>\n")
        }
        // what changed since the measure before (everything in the first one)
        val first = staves.firstOrNull()
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
        var cursor = Fraction.ZERO
        staves.forEachIndexed { s, staff ->
            val voices = staff.events.withIndex().groupBy { it.value.event.voice }.toSortedMap()
            for ((voice, events) in voices) {
                if (cursor > Fraction.ZERO) out.append("      <backup><duration>${units(cursor, divisions)}</duration></backup>\n")
                cursor = Fraction.ZERO
                val changes = if (voice == voices.firstKey()) staff.clefChanges.toMutableList() else mutableListOf()
                for ((index, placed) in events.sortedWith(compareBy({ it.value.event.onset }, { if ((it.value.event as? Chord)?.grace == true) 0 else 1 }))) {
                    val event = placed.event
                    while (changes.isNotEmpty() && changes.first().x < placed.box.left) {
                        out.append("      <attributes>\n").append(clef(changes.removeAt(0).value, s + 1, staffCount)).append("      </attributes>\n")
                    }
                    if (event.onset > cursor) {
                        out.append("      <forward><duration>${units(event.onset - cursor, divisions)}</duration></forward>\n")
                        cursor = event.onset
                    }
                    val ids = { k: Int -> ScoreReading.noteId(measure.rank, firstStaff + s + 1, index, k) }
                    val musicXmlVoice = s * 4 + voice
                    writeEvent(out, event, length, divisions, musicXmlVoice, if (staffCount > 1) s + 1 else null, staff.clef, ids)
                    if (!(event is Chord && event.grace)) cursor = event.onset + duration(event, length)
                }
                changes.forEach { out.append("      <attributes>\n").append(clef(it.value, s + 1, staffCount)).append("      </attributes>\n") }
            }
            // a staff with nothing read fills its measure with a rest, so the parts keep in step
            if (voices.isEmpty()) {
                if (cursor > Fraction.ZERO) out.append("      <backup><duration>${units(cursor, divisions)}</duration></backup>\n")
                out.append("      <note><rest measure=\"yes\"/><duration>${units(length, divisions)}</duration><voice>${s * 4 + 1}</voice>")
                if (staffCount > 1) out.append("<staff>${s + 1}</staff>")
                out.append("</note>\n")
                cursor = length
            }
        }
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
        if (duration.tuplet != Fraction.of(1)) {
            tail.append("<time-modification><actual-notes>${duration.tuplet.denominator}</actual-notes>")
            tail.append("<normal-notes>${duration.tuplet.numerator}</normal-notes></time-modification>")
        }
        when (event) {
            is Rest -> {
                out.append("      <note><rest/><duration>${units(duration.length, divisions)}</duration>").append(tail)
                staff?.let { out.append("<staff>$it</staff>") }
                out.append("</note>\n")
            }
            is Chord -> {
                when (event.stem) {
                    Stem.UP -> tail.append("<stem>up</stem>")
                    Stem.DOWN -> tail.append("<stem>down</stem>")
                    Stem.NONE -> Unit
                }
                staff?.let { tail.append("<staff>$it</staff>") }
                if (Articulation.STACCATO in event.articulations) tail.append("<notations><articulations><staccato/></articulations></notations>")
                event.pitches.forEachIndexed { k, pitch ->
                    out.append("      <note id=\"${id(k)}\">")
                    if (event.grace) out.append("<grace/>")
                    if (k > 0) out.append("<chord/>")
                    out.append(pitchElement(pitch, clef))
                    if (!event.grace) out.append("<duration>${units(duration.length, divisions)}</duration>")
                    out.append(tail).append("</note>\n")
                }
            }
        }
    }

    /** A note's pitch, or where it sits on a percussion staff (or unknown: no clef read). */
    private fun pitchElement(pitch: Pitch?, clef: Clef?): String = when {
        pitch == null -> "<unpitched/>"
        clef?.sign == Clef.Sign.PERCUSSION ->
            "<unpitched><display-step>${Pitch.STEPS[pitch.step]}</display-step><display-octave>${pitch.octave}</display-octave></unpitched>"
        else -> "<pitch><step>${Pitch.STEPS[pitch.step]}</step>" +
            (if (pitch.alter != 0) "<alter>${pitch.alter}</alter>" else "") + "<octave>${pitch.octave}</octave></pitch>"
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
