package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Articulation
import com.musigatto.fosscore.pdfscore.music.BeamMark
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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class MusicXmlWriterTest {
    private val treble = Clef(Clef.Sign.G, 2)
    private val bass = Clef(Clef.Sign.F, 4)

    private fun box(x: Double) = Box(x, 100.0, x + 6.0, 105.0)
    private fun staffBox(n: Int) = StaffBox(n, 50.0, 300.0, 100.0, 120.0, 5.0, 70.0)

    private fun placed(event: Event, x: Double) = PlacedEvent(event, box(x))

    private fun chord(vararg pitches: Pitch, type: NoteType, onset: Fraction, voice: Int = 1, stem: Stem = Stem.UP, tuplet: Fraction = Fraction.of(1)) =
        Chord(pitches.toList(), Duration(type, tuplet = tuplet), stem, onset, voice)

    private fun staff(n: Int, events: List<PlacedEvent>, clef: Clef = treble, key: Key = Key(0), time: TimeSignature? = TimeSignature(3, 4), changes: List<Change<Clef>> = emptyList()) =
        MeasureStaff(staffBox(n), clef, key, time, changes, events)

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory.newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun Document.all(tag: String): List<Element> = getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
    private fun Element.all(tag: String): List<Element> = getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }
    private fun Element.text(tag: String): String? = all(tag).firstOrNull()?.textContent

    /** A chord, a triplet, a rest and a whole-measure rest: divisions fit them all, the triplet has its ratio. */
    @Test
    fun durationsChordsTripletsAndRests() {
        val c5 = Pitch(0, 5)
        val e5 = Pitch(2, 5)
        val third = Fraction.of(2, 3)
        val m1 = listOf(
            placed(chord(c5, e5, type = NoteType.QUARTER, onset = Fraction.ZERO), 70.0),
            placed(chord(c5, type = NoteType.EIGHTH, onset = Fraction.of(1, 4), tuplet = third), 90.0),
            placed(chord(e5, type = NoteType.EIGHTH, onset = Fraction.of(1, 3), tuplet = third), 100.0),
            placed(chord(c5, type = NoteType.EIGHTH, onset = Fraction.of(5, 12), tuplet = third), 110.0),
            placed(Rest(Duration(NoteType.QUARTER), Fraction.of(1, 2), 1), 130.0),
        )
        val m2 = listOf(placed(Rest(Duration(NoteType.WHOLE), Fraction.ZERO, 1, wholeMeasure = true), 170.0))
        val score = ScoreReading(
            listOf(Part(1)),
            listOf(
                ScoreMeasure(1, 0, newSystem = true, newPage = true, staves = listOf(staff(1, m1, key = Key(1)))),
                ScoreMeasure(2, 0, newSystem = false, newPage = false, staves = listOf(staff(1, m2, key = Key(1)))),
            ),
        )
        val doc = parse(MusicXmlWriter.write(score))
        assertEquals("3", doc.all("divisions").single().textContent)
        assertEquals("1", doc.all("fifths").single().textContent)
        assertEquals(listOf("3", "4"), listOf(doc.all("beats").single().textContent, doc.all("beat-type").single().textContent))
        val notes = doc.all("note")
        // the chord's two notes, three triplet eighths, a rest, the measure rest
        assertEquals(listOf("3", "3", "1", "1", "1", "3", "9"), notes.map { it.text("duration") })
        assertEquals(1, notes[1].all("chord").size)
        assertEquals(listOf("C5", "E5"), notes.take(2).map { it.text("step") + it.text("octave") })
        assertEquals("3", notes[2].text("actual-notes"))
        assertEquals("2", notes[2].text("normal-notes"))
        assertEquals("yes", notes[6].all("rest").single().getAttribute("measure"))
        assertEquals(ScoreReading.noteId(1, 1, 0, 1), notes[1].getAttribute("id"))
        assertEquals(listOf("fosscore-measure-1", "fosscore-measure-2"), doc.all("measure").map { it.getAttribute("id") })
    }

    /** Two voices on one staff, a piano's second staff: each written after a backup, numbered apart. */
    @Test
    fun voicesAndStavesFollowEachOtherWithBackups() {
        val upper = listOf(
            placed(chord(Pitch(4, 5), type = NoteType.HALF, onset = Fraction.ZERO), 70.0),
            placed(chord(Pitch(0, 5), type = NoteType.QUARTER, onset = Fraction.ZERO, voice = 2, stem = Stem.DOWN), 70.0),
            placed(chord(Pitch(1, 5), type = NoteType.QUARTER, onset = Fraction.of(1, 4), voice = 2, stem = Stem.DOWN), 90.0),
            placed(chord(Pitch(2, 5), type = NoteType.QUARTER, onset = Fraction.of(1, 2)), 110.0),
        )
        val lower = listOf(placed(chord(Pitch(0, 3), type = NoteType.HALF, onset = Fraction.of(1, 4)), 90.0))
        val score = ScoreReading(
            listOf(Part(2)),
            listOf(ScoreMeasure(1, 0, true, true, listOf(staff(1, upper), staff(2, lower, clef = bass)))),
        )
        val doc = parse(MusicXmlWriter.write(score))
        assertEquals("2", doc.all("staves").single().textContent)
        assertEquals(listOf("G", "F"), doc.all("clef").map { it.text("sign") })
        val measure = doc.all("measure").single()
        val steps = (0 until measure.childNodes.length).mapNotNull { measure.childNodes.item(it) as? Element }.mapNotNull { child ->
            when (child.tagName) {
                "note" -> "${child.text("step")}${child.text("octave")} v${child.text("voice")}" + (child.text("staff")?.let { " s$it" } ?: "")
                "backup" -> "backup ${child.text("duration")}"
                "forward" -> "forward ${child.text("duration")}"
                else -> null
            }
        }
        assertEquals(
            listOf("G5 v1 s1", "E5 v1 s1", "backup 3", "C5 v2 s1", "D5 v2 s1", "backup 2", "forward 1", "C3 v5 s2"),
            steps,
        )
    }

    /**
     * A tempo mark and words over the staff are written before the note they stand over, the tempo
     * with the sound it gives (in quarters a minute: a dotted quarter at 40 is 60); an arpeggio
     * and a fermata on their notes.
     */
    @Test
    fun directionsArpeggiosAndFermatas() {
        val events = listOf(
            placed(Chord(listOf(Pitch(0, 5), Pitch(2, 5)), Duration(NoteType.HALF), Stem.UP, Fraction.ZERO, 1, arpeggiate = true), 70.0),
            placed(Chord(listOf(Pitch(0, 5)), Duration(NoteType.HALF), Stem.UP, Fraction.of(1, 2), 1, fermata = true), 150.0),
            placed(Rest(Duration(NoteType.HALF), Fraction.of(1, 1), 1, fermata = true), 200.0),
        )
        val directions = listOf(TempoMark(68.0, NoteType.QUARTER, 1, 40), Words(148.0, "Rall."))
        val score = ScoreReading(listOf(Part(1)), listOf(ScoreMeasure(1, 0, true, true, listOf(staff(1, events, time = TimeSignature(6, 4)).copy(directions = directions)))))
        val xml = MusicXmlWriter.write(score)
        val doc = parse(xml)
        assertEquals(listOf("60"), doc.all("sound").map { it.getAttribute("tempo") })
        assertEquals(listOf("quarter"), doc.all("beat-unit").map { it.textContent })
        assertEquals(1, doc.all("beat-unit-dot").size)
        assertEquals(listOf("40"), doc.all("per-minute").map { it.textContent })
        assertEquals(listOf("Rall."), doc.all("words").map { it.textContent })
        assertEquals(2, doc.all("arpeggiate").size)
        assertEquals(2, doc.all("fermata").size)
        // each direction before the note it stands over
        val order = Regex("<note[ >]|<metronome|<words").findAll(xml).map { it.value }.toList()
        assertEquals(listOf("<metronome", "<note ", "<note ", "<words", "<note ", "<note>"), order)
    }

    /** A final barline is written at the end of its measure; a plain one is not. */
    @Test
    fun aFinalBarlineIsWritten() {
        val whole = { placed(Chord(listOf(Pitch(0, 5)), Duration(NoteType.WHOLE), Stem.NONE, Fraction.ZERO, 1), 70.0) }
        val score = ScoreReading(
            listOf(Part(1)),
            listOf(
                ScoreMeasure(1, 0, true, true, listOf(staff(1, listOf(whole()), time = TimeSignature(4, 4)).copy(barStyle = BarStyle.REGULAR))),
                ScoreMeasure(2, 0, false, false, listOf(staff(1, listOf(whole()), time = TimeSignature(4, 4)).copy(barStyle = BarStyle.LIGHT_HEAVY))),
            ),
        )
        val measures = parse(MusicXmlWriter.write(score)).all("measure")
        assertEquals(listOf(emptyList(), listOf("right light-heavy")), measures.map { m -> m.all("barline").map { "${it.getAttribute("location")} ${it.text("bar-style")}" } })
    }

    /** The accidentals drawn are written, each on its note: a renderer does not work them out. */
    @Test
    fun drawnAccidentalsAreWritten() {
        val chord = Chord(
            listOf(Pitch(3, 4, 1), Pitch(0, 5), Pitch(6, 5, -1)), Duration(NoteType.WHOLE), Stem.NONE, Fraction.ZERO, 1,
            accidentals = mapOf(0 to 1, 1 to 0, 2 to -1),
        )
        val score = ScoreReading(listOf(Part(1)), listOf(ScoreMeasure(1, 0, true, true, listOf(staff(1, listOf(placed(chord, 70.0)), time = TimeSignature(4, 4))))))
        val notes = parse(MusicXmlWriter.write(score)).all("note")
        assertEquals(listOf("sharp", "natural", "flat"), notes.map { it.text("accidental") })
    }

    /** A tie starts on its note and stops on the next note of that pitch on the staff, over the barline. */
    @Test
    fun aTieStopsOnTheNextNoteOfItsPitch() {
        val c5 = Pitch(0, 5)
        val e5 = Pitch(2, 5)
        val m1 = listOf(placed(Chord(listOf(c5, e5), Duration(NoteType.WHOLE), Stem.NONE, Fraction.ZERO, 1, ties = setOf(1)), 70.0))
        val m2 = listOf(placed(Chord(listOf(c5, e5), Duration(NoteType.WHOLE), Stem.NONE, Fraction.ZERO, 1), 70.0))
        val score = ScoreReading(
            listOf(Part(1)),
            listOf(
                ScoreMeasure(1, 0, true, true, listOf(staff(1, m1, time = TimeSignature(4, 4)))),
                ScoreMeasure(2, 0, false, false, listOf(staff(1, m2, time = TimeSignature(4, 4)))),
            ),
        )
        val notes = parse(MusicXmlWriter.write(score)).all("note")
        assertEquals(listOf("", "start", "", "stop"), notes.map { n -> n.all("tie").joinToString { it.getAttribute("type") } })
        assertEquals(listOf("", "start", "", "stop"), notes.map { n -> n.all("tied").joinToString { it.getAttribute("type") } })
    }

    /** Each note says where its beams begin, go on and end, by level. */
    @Test
    fun beamsAreWrittenByLevel() {
        val events = listOf(
            placed(Chord(listOf(Pitch(0, 5)), Duration(NoteType.EIGHTH, dots = 1), Stem.UP, Fraction.ZERO, 1, beams = listOf(BeamMark.BEGIN)), 70.0),
            placed(Chord(listOf(Pitch(0, 5)), Duration(NoteType.SIXTEENTH), Stem.UP, Fraction.of(3, 16), 1, beams = listOf(BeamMark.END, BeamMark.BACKWARD_HOOK)), 90.0),
        )
        val score = ScoreReading(listOf(Part(1)), listOf(ScoreMeasure(1, 0, true, true, listOf(staff(1, events, time = TimeSignature(1, 4))))))
        val notes = parse(MusicXmlWriter.write(score)).all("note")
        assertEquals(listOf("1:begin"), notes[0].all("beam").map { "${it.getAttribute("number")}:${it.textContent}" })
        assertEquals(listOf("1:end", "2:backward hook"), notes[1].all("beam").map { "${it.getAttribute("number")}:${it.textContent}" })
    }

    /**
     * Notes under an "8" sound an octave up: their pitches are the ones heard, and an octave-shift
     * (MusicXML's "down": drawn lower than heard) runs from the first to the last, over the barline.
     */
    @Test
    fun anOttavaIsAnOctaveShiftOverItsNotes() {
        val c6 = Pitch(0, 6)
        fun note(onset: Fraction, ottava: Int) = Chord(listOf(c6), Duration(NoteType.HALF), Stem.DOWN, onset, 1, ottava = ottava)
        val m1 = listOf(placed(note(Fraction.ZERO, 0), 70.0), placed(note(Fraction.of(1, 2), 1), 120.0))
        val m2 = listOf(placed(note(Fraction.ZERO, 1), 70.0), placed(note(Fraction.of(1, 2), 0), 120.0))
        val score = ScoreReading(
            listOf(Part(1)),
            listOf(
                ScoreMeasure(1, 0, true, true, listOf(staff(1, m1, time = TimeSignature(4, 4)))),
                ScoreMeasure(2, 0, false, false, listOf(staff(1, m2, time = TimeSignature(4, 4)))),
            ),
        )
        val xml = MusicXmlWriter.write(score)
        val shifts = parse(xml).all("octave-shift").map { "${it.getAttribute("type")} ${it.getAttribute("size")}" }
        assertEquals(listOf("down 8", "stop 8"), shifts)
        assertEquals(listOf("6", "6", "6", "6"), parse(xml).all("octave").map { it.textContent })
        // it starts before the second note of measure 1, stops before the last one of measure 2
        val notes = Regex("<note |<octave-shift").findAll(xml).map { it.value }.toList()
        assertEquals(listOf("<note ", "<octave-shift", "<note ", "<note ", "<octave-shift", "<note "), notes)
    }

    /** A grace note takes no time; a staccato is written; a clef drawn mid-measure comes before the note after it. */
    @Test
    fun graceNotesStaccatoAndAClefChange() {
        val events = listOf(
            placed(Chord(listOf(Pitch(1, 5)), Duration(NoteType.EIGHTH), Stem.UP, Fraction.ZERO, 1, grace = true), 66.0),
            placed(Chord(listOf(Pitch(0, 5)), Duration(NoteType.HALF), Stem.UP, Fraction.ZERO, 1, articulations = setOf(Articulation.STACCATO)), 70.0),
            placed(chord(Pitch(4, 2), type = NoteType.QUARTER, onset = Fraction.of(1, 2)), 150.0),
        )
        val score = ScoreReading(
            listOf(Part(1)),
            listOf(ScoreMeasure(1, 0, true, true, listOf(staff(1, events, changes = listOf(Change(130.0, bass)))))),
        )
        val xml = MusicXmlWriter.write(score)
        val doc = parse(xml)
        val notes = doc.all("note")
        assertEquals(1, notes[0].all("grace").size)
        assertEquals(null, notes[0].text("duration"))
        assertEquals(1, notes[1].all("staccato").size)
        assertEquals(listOf("G", "F"), doc.all("clef").map { it.text("sign") })
        assert(xml.indexOf("<sign>F</sign>") in xml.indexOf("C</step>")..xml.indexOf("<step>G</step><octave>2"))
    }
}
