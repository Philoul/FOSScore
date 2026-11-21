package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Articulation
import com.musigatto.fosscore.pdfscore.music.BeamMark
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.NoteType
import com.musigatto.fosscore.pdfscore.music.Rest
import com.musigatto.fosscore.pdfscore.music.Stem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmsTest {
    private val space = 5.0
    private val top = 100.0
    private val bottomLine = top + 4 * space
    private val g4 = top + 3 * space // second line from the bottom
    private val c5 = top + 1.5 * space // third space
    private val stemLength = 3.5 * space

    private val staffLines = (0..4).map { DrawnLine(50.0, top + it * space, 550.0, top + it * space, 0.5) }
    private val clef = DrawnGlyph("gClef", Box(55.0, top - 1.5 * space, 55.0 + 2.7 * space, top + 5.5 * space))

    private fun head(x: Double, y: Double, name: String = "noteheadBlack", height: Double = 1.0) =
        DrawnGlyph(name, Box(x, y - height * space / 2, x + 1.3 * height * space, y + height * space / 2))

    private fun stemUp(head: DrawnGlyph) = DrawnLine(head.box.right, head.box.centerY, head.box.right, head.box.centerY - stemLength, 0.5)
    private fun stemDown(head: DrawnGlyph) = DrawnLine(head.box.left, head.box.centerY, head.box.left, head.box.centerY + stemLength, 0.5)

    private fun barline(x: Double) = DrawnLine(x, top, x, bottomLine, 0.8)

    /** The ledger line at [y] across [head] (a note out of the staff stands on its ledger lines). */
    private fun ledger(head: DrawnGlyph, y: Double) = DrawnLine(head.box.left - 1.0, y, head.box.right + 1.0, y, 0.5)

    /** A flat beam joining stems up from [x1] to [x2], [index] 0 at the stems' ends, 1 under it... */
    private fun flatBeamUp(x1: Double, x2: Double, endY: Double, index: Int) =
        DrawnLine(x1, endY + 0.25 * space + index * 0.75 * space, x2, endY + 0.25 * space + index * 0.75 * space, 0.5 * space)

    private fun dot(afterHead: DrawnGlyph, y: Double) =
        DrawnGlyph("augmentationDot", Box(afterHead.box.right + 0.5 * space, y - 0.2 * space, afterHead.box.right + 0.9 * space, y + 0.2 * space))

    private fun read(
        glyphs: List<DrawnGlyph>,
        lines: List<DrawnLine>,
        shapes: List<DrawnShape> = emptyList(),
        texts: List<DrawnText> = emptyList(),
    ): List<MeasureReading> {
        val page = PageDrawing(600.0, 800.0, listOf(clef) + glyphs, staffLines + lines, shapes, texts)
        val reading = StaffNotes.read(page, Staves.find(page)).single().single()
        return Rhythms.read(page, reading)
    }

    private fun types(measure: MeasureReading) = measure.events.map { it.event.duration.type }
    private fun onsets(measure: MeasureReading) = measure.events.map { it.event.onset }

    @Test
    fun theHeadAndItsStemGiveQuarterHalfAndWhole() {
        val quarter = head(100.0, g4)
        val half = head(130.0, g4, "noteheadHalf")
        val whole = head(160.0, g4, "noteheadWhole")
        val measure = read(listOf(quarter, half, whole), listOf(stemUp(quarter), stemUp(half))).single()
        assertEquals(listOf(NoteType.QUARTER, NoteType.HALF, NoteType.WHOLE), types(measure))
        assertEquals(listOf(Stem.UP, Stem.UP, Stem.NONE), measure.events.map { (it.event as Chord).stem })
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(3, 4)), onsets(measure))
        assertEquals(listOf("G4"), (measure.events[0].event as Chord).pitches.map { it.toString() })
        assertEquals(quarter.box, measure.events[0].box)
    }

    @Test
    fun aStemDownStartsAtTheHeadsLeftSide() {
        val note = head(100.0, c5)
        val chord = read(listOf(note), listOf(stemDown(note))).single().events.single().event as Chord
        assertEquals(Stem.DOWN, chord.stem)
        assertEquals(NoteType.QUARTER, chord.duration.type)
    }

    @Test
    fun aFlagMakesAnEighth() {
        val note = head(100.0, g4)
        val stem = stemUp(note)
        val flag = DrawnGlyph("flag8thUp", Box(stem.x1, stem.y2, stem.x1 + 0.9 * space, stem.y2 + 3 * space))
        val measure = read(listOf(note, flag), listOf(stem)).single()
        assertEquals(listOf(NoteType.EIGHTH), types(measure))
    }

    @Test
    fun beamsCountAtEachStem() {
        // two sixteenths beamed together, then two eighths: two beams, then one
        val heads = (0 until 4).map { head(100.0 + it * 3 * space, g4) }
        val stems = heads.map { stemUp(it) }
        val end = stems[0].y2
        val beams = listOf(
            flatBeamUp(stems[0].x1, stems[1].x1, end, 0), flatBeamUp(stems[0].x1, stems[1].x1, end, 1),
            flatBeamUp(stems[2].x1, stems[3].x1, end, 0),
        )
        val measure = read(heads, stems + beams).single()
        assertEquals(listOf(NoteType.SIXTEENTH, NoteType.SIXTEENTH, NoteType.EIGHTH, NoteType.EIGHTH), types(measure))
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 16), Fraction.of(1, 8), Fraction.of(1, 4)), onsets(measure))
    }

    private fun beamMarks(measure: MeasureReading) = measure.events.map { (it.event as Chord).beams }

    /** Each beam at a stem begins, goes on or ends there, level 1 the outer one (at the stems' ends). */
    @Test
    fun eachStemKnowsWhereItsBeamsBeginAndEnd() {
        // three sixteenths under two beams, then two eighths under one
        val heads = (0 until 5).map { head(100.0 + it * 3 * space, g4) }
        val stems = heads.map { stemUp(it) }
        val end = stems[0].y2
        val beams = listOf(
            flatBeamUp(stems[0].x1, stems[2].x1, end, 0), flatBeamUp(stems[0].x1, stems[2].x1, end, 1),
            flatBeamUp(stems[3].x1, stems[4].x1, end, 0),
        )
        val measure = read(heads, stems + beams).single()
        val (begin, go, stop) = Triple(BeamMark.BEGIN, BeamMark.CONTINUE, BeamMark.END)
        assertEquals(listOf(listOf(begin, begin), listOf(go, go), listOf(stop, stop), listOf(begin), listOf(stop)), beamMarks(measure))
    }

    /** A dotted eighth then a sixteenth: one beam joins them, the sixteenth's second is a hook pointing back. */
    @Test
    fun aShortBeamIsAHookOnItsStem() {
        val heads = listOf(head(100.0, g4), head(115.0, g4))
        val stems = heads.map { stemUp(it) }
        val end = stems[0].y2
        val hook = flatBeamUp(stems[1].x1 - 1.2 * space, stems[1].x1, end, 1)
        val measure = read(heads, stems + flatBeamUp(stems[0].x1, stems[1].x1, end, 0) + hook).single()
        assertEquals(listOf(listOf(BeamMark.BEGIN), listOf(BeamMark.END, BeamMark.BACKWARD_HOOK)), beamMarks(measure))
    }

    /** MuseScore 3 draws some beams twice, one exactly over the other: still one beam. */
    @Test
    fun aBeamDrawnTwiceIsOneBeam() {
        val heads = listOf(head(100.0, g4), head(115.0, g4))
        val stems = heads.map { stemUp(it) }
        val beam = flatBeamUp(stems[0].x1, stems[1].x1, stems[0].y2, 0)
        val measure = read(heads, stems + beam + beam).single()
        assertEquals(listOf(NoteType.EIGHTH, NoteType.EIGHTH), types(measure))
    }

    /** Verovio draws a beam in pieces from stem to stem: the middle stem, where two pieces meet, still has one beam there. */
    @Test
    fun aBeamInPiecesIsOneBeam() {
        val heads = (0 until 3).map { head(100.0 + it * 3 * space, g4) }
        val stems = heads.map { stemUp(it) }
        val end = stems[0].y2
        val pieces = listOf(flatBeamUp(stems[0].x1, stems[1].x1, end, 1), flatBeamUp(stems[1].x1, stems[2].x1, end, 1))
        val measure = read(heads, stems + flatBeamUp(stems[0].x1, stems[2].x1, end, 0) + pieces).single()
        assertEquals(listOf(NoteType.SIXTEENTH, NoteType.SIXTEENTH, NoteType.SIXTEENTH), types(measure))
    }

    /** The same with slanting pieces, as four-cornered shapes. */
    @Test
    fun aSlantingBeamInPiecesIsOneBeam() {
        val heads = (0 until 3).map { head(100.0 + it * 3 * space, g4 - it * space / 2) }
        val stems = heads.map { stemUp(it) }
        fun slanting(a: DrawnLine, b: DrawnLine, index: Int): DrawnShape {
            val dy = index * 0.75 * space
            return DrawnShape(
                listOf(Point(a.x1, a.y2 + dy), Point(b.x1, b.y2 + dy), Point(b.x1, b.y2 + dy + 0.5 * space), Point(a.x1, a.y2 + dy + 0.5 * space), Point(a.x1, a.y2 + dy)),
                curved = false,
            )
        }
        val shapes = listOf(slanting(stems[0], stems[2], 0), slanting(stems[0], stems[1], 1), slanting(stems[1], stems[2], 1))
        val measure = read(heads, stems, shapes).single()
        assertEquals(listOf(NoteType.SIXTEENTH, NoteType.SIXTEENTH, NoteType.SIXTEENTH), types(measure))
    }

    /** A sixteenth then a dotted eighth: the sixteenth's short beam stops before the next stem, and is not its. */
    @Test
    fun aShortBeamBelongsOnlyToTheStemItTouches() {
        val heads = listOf(head(100.0, g4), head(115.0, g4))
        val stems = heads.map { stemUp(it) }
        val end = stems[0].y2
        val hook = flatBeamUp(stems[0].x1, stems[1].x1 - 0.35 * space, end, 1)
        val measure = read(heads, stems + flatBeamUp(stems[0].x1, stems[1].x1, end, 0) + hook).single()
        assertEquals(listOf(NoteType.SIXTEENTH, NoteType.EIGHTH), types(measure))
    }

    /** A stem drawn in two pieces, one from the heads, one on to the beam: one stem. */
    @Test
    fun aStemInTwoPiecesReachesItsBeam() {
        val heads = listOf(head(100.0, g4), head(115.0, g4))
        val stems = heads.map { stemUp(it) }
        val first = stems[0]
        val middle = first.y1 - 1.5 * space
        val pieces = listOf(DrawnLine(first.x1, first.y1, first.x1, middle, 0.5), DrawnLine(first.x1, middle, first.x1, first.y2, 0.5))
        val measure = read(heads, pieces + stems[1] + flatBeamUp(first.x1, stems[1].x1, first.y2, 0)).single()
        assertEquals(listOf(NoteType.EIGHTH, NoteType.EIGHTH), types(measure))
    }

    @Test
    fun aSlantingBeamIsAFourCorneredShape() {
        val low = head(100.0, g4)
        val high = head(115.0, c5)
        val s1 = stemUp(low)
        val s2 = stemUp(high)
        val beam = DrawnShape(
            listOf(Point(s1.x1, s1.y2), Point(s2.x1, s2.y2), Point(s2.x1, s2.y2 + 0.5 * space), Point(s1.x1, s1.y2 + 0.5 * space), Point(s1.x1, s1.y2)),
            curved = false,
        )
        val measure = read(listOf(low, high), listOf(s1, s2), listOf(beam)).single()
        assertEquals(listOf(NoteType.EIGHTH, NoteType.EIGHTH), types(measure))
    }

    @Test
    fun aDotAfterTheHeadMakesItLongerByHalf() {
        val dotted = head(100.0, g4) // on a line: its dot sits in the space above
        val eighth = head(130.0, g4)
        val stem2 = stemUp(eighth)
        val flag = DrawnGlyph("flag8thUp", Box(stem2.x1, stem2.y2, stem2.x1 + 0.9 * space, stem2.y2 + 3 * space))
        val measure = read(listOf(dotted, dot(dotted, g4 - 0.5 * space), eighth, flag), listOf(stemUp(dotted), stem2)).single()
        assertEquals(listOf(1, 0), measure.events.map { it.event.duration.dots })
        assertEquals(listOf(Fraction.ZERO, Fraction.of(3, 8)), onsets(measure))
    }

    /** A dotted note just before a barline keeps its dot: repeat dots come in pairs, one over the other. */
    @Test
    fun aDotByTheBarlineIsTheLastNotesDot() {
        val note = head(100.0, g4)
        val dotGlyph = dot(note, g4 - 0.5 * space)
        val bar = barline(dotGlyph.box.right + 0.8 * space)
        val measures = read(listOf(note, dotGlyph), listOf(stemUp(note), bar))
        assertEquals(1, measures.first().events.single().event.duration.dots)
    }

    /** Two dots one over the other by a barline are a repeat's, no note's. */
    @Test
    fun repeatDotsByTheBarlineAreNoNotesDots() {
        val note = head(100.0, g4)
        val x = note.box.right + 0.5 * space
        val repeat = listOf(top + 1.5 * space, top + 2.5 * space).map { y -> DrawnGlyph("augmentationDot", Box(x, y - 0.2 * space, x + 0.4 * space, y + 0.2 * space)) }
        val measures = read(listOf(note) + repeat, listOf(stemUp(note), barline(x + 1.2 * space)))
        assertEquals(0, measures.first().events.single().event.duration.dots)
    }

    /** A dot on a head's edge is no augmentation dot, not even of the note before it. */
    @Test
    fun aDotOnAHeadIsNotTheDotOfTheNoteBefore() {
        val eighth = head(100.0, c5)
        val quarter = head(113.5, c5)
        val stem = stemUp(eighth)
        val flag = DrawnGlyph("flag8thUp", Box(stem.x1, stem.y2, stem.x1 + 0.9 * space, stem.y2 + 3 * space))
        val onHead = DrawnGlyph("augmentationDot", Box(quarter.box.right - 0.2 * space, c5 - 0.2 * space, quarter.box.right + 0.2 * space, c5 + 0.2 * space))
        val measure = read(listOf(eighth, flag, quarter, onHead), listOf(stem, stemUp(quarter))).single()
        assertEquals(listOf(0, 0), measure.events.map { it.event.duration.dots })
    }

    /** A thin arc from ([x1], [y1]) to ([x2], [y2]), bulging [bulge] down (up when negative), filled as a program draws a tie or a slur. */
    private fun arc(x1: Double, y1: Double, x2: Double, y2: Double, bulge: Double): DrawnShape {
        val outer = (0..16).map { i ->
            val t = i / 16.0
            Point(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t + 4 * bulge * t * (1 - t))
        }
        val inner = outer.reversed().map { Point(it.x, it.y - 0.15 * space * Math.signum(bulge)) }
        return DrawnShape(outer + inner + outer.first(), curved = true)
    }

    private fun tied(measure: MeasureReading) = measure.events.map { (it.event as Chord).ties }

    /** A wavy line drawn upright, in pieces, just before a chord: the chord is rolled (an arpeggio). */
    @Test
    fun anUprightWavyLineBeforeAChordIsAnArpeggio() {
        val low = head(100.0, g4)
        val high = head(100.0, c5)
        val stem = DrawnLine(low.box.right, g4, low.box.right, c5 - stemLength, 0.5)
        val wiggles = (0 until 3).map { i -> DrawnGlyph("wiggleTrill", Box(95.0, c5 - 1.0 + i * 5.0, 98.5, c5 + 6.0 + i * 5.0)) }
        val plain = head(140.0, c5)
        val trill = DrawnGlyph("wiggleTrill", Box(135.0, top - 3 * space, 150.0, top - 2.3 * space))
        val measure = read(listOf(low, high, plain) + wiggles + trill, listOf(stem, stemUp(plain))).single()
        assertEquals(listOf(true, false), measure.events.map { (it.event as Chord).arpeggiate })
    }

    /** A fermata over a note or a rest holds it: each gets it. */
    @Test
    fun aFermataIsOnTheNoteOrRestUnderIt() {
        val note = head(100.0, g4)
        val rest = DrawnGlyph("restQuarter", Box(140.0, top + 0.5 * space, 144.0, top + 3.5 * space))
        val fermatas = listOf(
            DrawnGlyph("fermataAbove", Box(99.0, top - 3 * space, 108.0, top - 1.5 * space)),
            DrawnGlyph("fermataAbove", Box(137.5, top - 3 * space, 146.5, top - 1.5 * space)),
        )
        val measure = read(listOf(note, rest) + fermatas, listOf(stemUp(note))).single()
        assertEquals(listOf(true, true), measure.events.map { (it.event as? Chord)?.fermata ?: (it.event as Rest).fermata })
    }

    /** A curve from beside a note to beside the next one on its line is a tie: the first note's. */
    @Test
    fun aCurveBetweenTwoNotesOnOneLineIsATie() {
        val a = head(100.0, g4)
        val b = head(130.0, g4)
        val tie = arc(a.box.right + 0.2 * space, g4 + 0.6 * space, b.box.left - 0.2 * space, g4 + 0.6 * space, 0.6 * space)
        val measure = read(listOf(a, b), listOf(stemUp(a), stemUp(b)), listOf(tie)).single()
        assertEquals(listOf(setOf(0), emptySet<Int>()), tied(measure))
    }

    /** A curve from one note to a note on another line is a slur, no tie. */
    @Test
    fun aCurveToAnotherLineIsNoTie() {
        val a = head(100.0, g4)
        val b = head(130.0, c5)
        val slur = arc(a.box.centerX, g4 + 0.8 * space, b.box.centerX, c5 + 0.8 * space, 0.8 * space)
        val measure = read(listOf(a, b), listOf(stemDown(a), stemDown(b)), listOf(slur)).single()
        assertEquals(listOf(emptySet<Int>(), emptySet<Int>()), tied(measure))
    }

    /** In a chord, the tie by each head says which of its notes is tied (counted from the lowest). */
    @Test
    fun aTieInAChordIsTheNoteItStartsBeside() {
        val low = head(100.0, g4)
        val high = head(100.0, c5)
        val next = head(130.0, c5)
        val tie = arc(high.box.right + 0.2 * space, c5, next.box.left - 0.2 * space, c5, -0.5 * space)
        val measure = read(listOf(low, high, next), listOf(DrawnLine(low.box.right, g4, low.box.right, c5 - stemLength, 0.5), stemUp(next)), listOf(tie)).single()
        assertEquals(listOf(setOf(1), emptySet<Int>()), tied(measure))
    }

    /** A tie running to the end of the staff goes on over the line break: still a tie. */
    @Test
    fun aTieToTheStaffsEndIsATie() {
        val a = head(520.0, g4)
        val tie = arc(a.box.right + 0.2 * space, g4 + 0.6 * space, 549.0, g4 + 0.6 * space, 0.4 * space)
        val measure = read(listOf(a), listOf(stemUp(a)), listOf(tie)).single()
        assertEquals(listOf(setOf(0)), tied(measure))
    }

    /**
     * A staccato over a note (its stem down) can sit at the height of the note before it: it is
     * the staccato of the note it is over, not a dot of the one before.
     */
    @Test
    fun aStaccatoOverANoteIsNoDotOfTheNoteBefore() {
        val eighth = head(100.0, c5)
        val quarter = head(111.0, c5 + 0.5 * space)
        val s1 = stemDown(eighth)
        val flag = DrawnGlyph("flag8thDown", Box(s1.x1, s1.y2 - 3 * space, s1.x1 + 0.9 * space, s1.y2))
        val staccato = DrawnGlyph(
            "augmentationDot",
            Box(quarter.box.centerX - 0.2 * space, quarter.box.centerY - 1.2 * space, quarter.box.centerX + 0.2 * space, quarter.box.centerY - 0.8 * space),
        )
        val measure = read(listOf(eighth, flag, quarter, staccato), listOf(s1, stemDown(quarter))).single()
        assertEquals(listOf(0, 0), measure.events.map { it.event.duration.dots })
        assertEquals(listOf(emptySet(), setOf(Articulation.STACCATO)), measure.events.map { (it.event as Chord).articulations })
    }

    /**
     * A dot well under a note (Verovio draws the dot of a note it hides) is no staccato: a staccato
     * sits in the space next to the head, or past the stem's end.
     */
    @Test
    fun aDotFarUnderANoteIsNoStaccato() {
        val note = head(100.0, g4)
        val dot = DrawnGlyph(
            "augmentationDot",
            Box(note.box.left - 0.2 * space, note.box.bottom + 1.3 * space, note.box.left + 0.2 * space, note.box.bottom + 1.7 * space),
        )
        val chord = read(listOf(note, dot), listOf(stemUp(note))).single().events.single().event as Chord
        assertEquals(emptySet<Articulation>(), chord.articulations)
    }

    /** A staccato past the end of the stem (where two voices share the staff) is the note's. */
    @Test
    fun aStaccatoPastTheStemsEndIsItsStaccato() {
        val note = head(100.0, g4)
        val stem = stemUp(note)
        val staccato = DrawnGlyph("augmentationDot", Box(note.box.centerX - 0.2 * space, stem.y2 - 1.0 * space, note.box.centerX + 0.2 * space, stem.y2 - 0.6 * space))
        val chord = read(listOf(note, staccato), listOf(stem)).single().events.single().event as Chord
        assertEquals(setOf(Articulation.STACCATO), chord.articulations)
    }

    /** A staccato pressed against its head (stem up, the dot just under it) is still its staccato. */
    @Test
    fun aStaccatoTouchingItsHeadIsItsStaccato() {
        val note = head(100.0, top - space)
        val staccato = DrawnGlyph(
            "augmentationDot",
            Box(note.box.centerX - 0.15 * space, note.box.bottom - 0.2 * space, note.box.centerX + 0.15 * space, note.box.bottom + 0.1 * space),
        )
        val chord = read(listOf(note, staccato), listOf(stemUp(note))).single().events.single().event as Chord
        assertEquals(0, chord.duration.dots)
        assertEquals(setOf(Articulation.STACCATO), chord.articulations)
    }

    /** A rest drawn under a head is hidden by it: no reader sees it, so it is not read. */
    @Test
    fun aRestHiddenUnderAHeadIsNotRead() {
        val note = head(100.0, top - 0.5 * space)
        val hidden = DrawnGlyph("restWhole", Box(note.box.left, note.box.centerY, note.box.right, note.box.bottom))
        val measure = read(listOf(note, hidden), listOf(stemUp(note))).single()
        assertEquals(listOf(NoteType.QUARTER), types(measure))
        assertTrue(measure.events.single().event is Chord)
    }

    /**
     * A rest under the chords, drawn at the time of one of them, is another voice's: it starts
     * then (the voice's time before it is hidden rests).
     */
    @Test
    fun aRestBesideAChordIsAnotherVoice() {
        val chords = (0 until 4).map { head(100.0 + it * 20, g4) }
        val ledger = bottomLine + space
        val rest = DrawnGlyph("restHalf", Box(137.0, ledger - 0.5 * space, 149.0, ledger))
        val measure = read(chords + rest, chords.map { stemUp(it) }).single()
        val voice1 = measure.events.filter { it.event.voice == 1 }
        val voice2 = measure.events.filter { it.event.voice == 2 }
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(1, 2), Fraction.of(3, 4)), voice1.map { it.event.onset })
        assertEquals(listOf(Fraction.of(1, 2)), voice2.map { it.event.onset })
        assertEquals(NoteType.HALF, (voice2.single().event as Rest).duration.type)
    }

    /**
     * A left hand in two voices: a half note stem down, and over it a rest then notes stem up (the
     * rest is drawn above the half note, at its time). The notes stem up are the rest's voice, the
     * half notes the other: put in one voice, the measure lasted twice its length and the music
     * played late, its bar going back and forth (J'te l'dis quand même, 2026-11-18).
     */
    @Test
    fun theNotesOnTheStemsOfARestsSideAreItsVoice() {
        val halves = listOf(head(100.0, bottomLine, "noteheadHalf"), head(150.0, bottomLine, "noteheadHalf"))
        val rests = listOf(100.0, 150.0).map { x -> DrawnGlyph("restQuarter", Box(x, top + 0.5 * space, x + 1.1 * space, top + 2.5 * space)) }
        val quarters = listOf(head(125.0, g4), head(175.0, c5))
        val measure = read(halves + rests + quarters, halves.map { stemDown(it) } + quarters.map { stemUp(it) }).single()
        val restVoice = measure.events.first { it.event is Rest }.event.voice
        val withRests = measure.events.filter { it.event.voice == restVoice }
        val others = measure.events.filter { it.event.voice != restVoice }
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(1, 2), Fraction.of(3, 4)), withRests.map { it.event.onset })
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 2)), others.map { it.event.onset })
        assertEquals(listOf(NoteType.HALF, NoteType.HALF), others.map { it.event.duration.type })
    }

    /** The same under a whole note, which has no stem: the notes with a stem are the rest's voice. */
    @Test
    fun theNotesWithAStemBesideARestOverAWholeNoteAreItsVoice() {
        val whole = head(100.0, bottomLine, "noteheadWhole")
        val rest = DrawnGlyph("restQuarter", Box(100.0, top + 0.5 * space, 100.0 + 1.1 * space, top + 2.5 * space))
        val quarters = listOf(head(125.0, g4), head(150.0, c5), head(175.0, g4))
        val measure = read(listOf(whole, rest) + quarters, quarters.map { stemUp(it) }).single()
        val restVoice = measure.events.first { it.event is Rest }.event.voice
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(1, 2), Fraction.of(3, 4)),
            measure.events.filter { it.event.voice == restVoice }.map { it.event.onset })
        assertEquals(listOf(NoteType.WHOLE), measure.events.filter { it.event.voice != restVoice }.map { it.event.duration.type })
    }

    /**
     * One head with two stems, up on its right and down on its left: a melody and an
     * accompaniment on one staff that strike the same note. It is a note of both voices (Lettre à
     * France, measure 9; it was given to one stem only, and the other voice lost it).
     */
    @Test
    fun aHeadWithTwoStemsIsANoteOfBothVoices() {
        val shared = head(100.0, g4)
        val measure = read(listOf(shared), listOf(stemUp(shared), stemDown(shared))).single()
        assertEquals(2, measure.events.size)
        assertEquals(2, measure.events.map { it.event.voice }.distinct().size)
        assertEquals(listOf("G4", "G4"), measure.events.map { (it.event as Chord).pitches.single().toString() })
        assertEquals(setOf(Stem.UP, Stem.DOWN), measure.events.map { (it.event as Chord).stem }.toSet())
    }

    @Test
    fun aWholeRestHangsFromALineAndAHalfRestSitsOnOne() {
        // measure 1: a half rest on the middle line, then a half note; measure 2: a whole rest alone
        val halfRest = DrawnGlyph("restWhole", Box(100.0, top + 1.5 * space, 106.0, top + 2 * space))
        val note = head(130.0, g4, "noteheadHalf")
        val wholeRest = DrawnGlyph("restWhole", Box(220.0, top + space, 226.0, top + 1.5 * space))
        val measures = read(listOf(halfRest, note, wholeRest), listOf(stemUp(note), barline(200.0)))
        assertEquals(listOf(NoteType.HALF, NoteType.HALF), types(measures[0]))
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 2)), onsets(measures[0]))
        val rest = measures[1].events.single().event as Rest
        assertEquals(NoteType.WHOLE, rest.duration.type)
        assertTrue(rest.wholeMeasure)
    }

    @Test
    fun stemsUpAndDownStruckTogetherAreTwoVoices() {
        // voice 1: two half notes, stems up; voice 2: a quarter rest, then three quarters, stems down
        val upper = listOf(head(100.0, top + 0.5 * space, "noteheadHalf"), head(140.0, top + 0.5 * space, "noteheadHalf"))
        val lowerRest = DrawnGlyph("restQuarter", Box(100.0, top + 2.5 * space, 104.0, top + 5 * space))
        val lower = listOf(head(120.0, bottomLine), head(140.0, bottomLine), head(160.0, bottomLine))
        val measure = read(upper + lowerRest + lower, upper.map { stemUp(it) } + lower.map { stemDown(it) }).single()
        val voice1 = measure.events.filter { it.event.voice == 1 }
        val voice2 = measure.events.filter { it.event.voice == 2 }
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 2)), voice1.map { it.event.onset })
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(1, 2), Fraction.of(3, 4)), voice2.map { it.event.onset })
        assertTrue(voice2.first().event is Rest)
    }

    /**
     * A melody over chords, both with stems up (the chords' stems stop under the melody's heads):
     * still two voices, the upper one first.
     */
    @Test
    fun twoVoicesWithStemsUpGoByHeight() {
        val melodyY = top - 1.5 * space // B5
        val melody = listOf(head(100.0, melodyY), head(115.0, melodyY))
        val melodyStems = melody.map { DrawnLine(it.box.right, melodyY, it.box.right, melodyY - 2.5 * space, 0.5) }
        val flag = DrawnGlyph("flag8thUp", Box(melodyStems[1].x1, melodyStems[1].y2, melodyStems[1].x1 + 0.9 * space, melodyStems[1].y2 + 2 * space))
        val chord = head(100.0, g4)
        val chordStem = DrawnLine(chord.box.right, g4, chord.box.right, melodyY + space, 0.5)
        val measure = read(melody + flag + chord, melodyStems + chordStem + melody.map { ledger(it, top - space) }).single()
        val voice1 = measure.events.filter { it.event.voice == 1 }.map { (it.event as Chord).pitches.map { p -> p.toString() } }
        val voice2 = measure.events.filter { it.event.voice == 2 }.map { (it.event as Chord).pitches.map { p -> p.toString() } }
        assertEquals(listOf(listOf("B5"), listOf("B5")), voice1)
        assertEquals(listOf(listOf("G4")), voice2)
    }

    /**
     * Chords whose stem runs up to a melody's half note, whose own stem goes on from it: one line on
     * the page, two voices (a stem holds notes of one value).
     */
    @Test
    fun aHalfNoteOnTheStemOfBlackOnesIsAnotherVoice() {
        val melodyY = top - 1.5 * space
        val half = head(100.0, melodyY, "noteheadHalf")
        val chord = listOf(head(100.0, g4), head(100.0, bottomLine))
        val line = DrawnLine(half.box.right, bottomLine, half.box.right, melodyY - 3 * space, 0.5)
        val next = head(120.0, g4)
        val measure = read(listOf(half) + chord + next, listOf(line, stemUp(next), ledger(half, top - space))).single()
        val voice1 = measure.events.filter { it.event.voice == 1 }.map { it.event.duration.type }
        val voice2 = measure.events.filter { it.event.voice == 2 }.map { it.event.duration.type }
        assertEquals(listOf(NoteType.HALF), voice1)
        assertEquals(listOf(NoteType.QUARTER, NoteType.QUARTER), voice2)
    }

    @Test
    fun stemsTurningAtTheMiddleLineStayOneVoice() {
        val low = head(100.0, g4)
        val high = head(125.0, top)
        val measure = read(listOf(low, high), listOf(stemUp(low), stemDown(high))).single()
        assertEquals(listOf(1, 1), measure.events.map { it.event.voice })
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4)), onsets(measure))
    }

    @Test
    fun aGraceNoteTakesNoTime() {
        val grace = head(100.0, c5, height = 0.65)
        val note = head(110.0, g4)
        val measure = read(listOf(grace, note), listOf(stemUp(grace), stemUp(note))).single()
        val chords = measure.events.map { it.event as Chord }
        assertEquals(listOf(true, false), chords.map { it.grace })
        assertEquals(listOf(Fraction.ZERO, Fraction.ZERO), chords.map { it.onset })
    }

    /**
     * A grace note alone, its flag too small to be read, is an eighth (an acciaccatura, slashed),
     * not a quarter (J'ai encore rêvé d'elle, tenor, measure 40).
     */
    /** X heads are a drum's, not pitches (a snare drum over a piano, J'ai encore rêvé d'elle measure 41). */
    @Test
    fun xHeadsAreMarked() {
        val drum = head(100.0, c5, name = "noteheadXBlack")
        val note = head(130.0, g4)
        val measure = read(listOf(drum, note), listOf(stemUp(drum), stemUp(note))).single()
        assertEquals(listOf(setOf(0), emptySet<Int>()), measure.events.map { (it.event as Chord).crossHeads })
    }

    @Test
    fun aGraceNoteAloneIsAnEighth() {
        val grace = head(100.0, c5, height = 0.65)
        val note = head(110.0, g4)
        val measure = read(listOf(grace, note), listOf(stemUp(grace), stemUp(note))).single()
        val chords = measure.events.map { it.event as Chord }
        assertEquals(listOf(NoteType.EIGHTH, NoteType.QUARTER), chords.map { it.duration.type })
    }

    /**
     * A grace note has a stem: a small whole head is a curve misread (a slur's end in J'ai encore
     * rêvé d'elle, measure 28), and read as a grace note it broke the beam it sat in.
     */
    @Test
    fun aSmallWholeHeadIsNoGraceNote() {
        val first = head(100.0, g4)
        val misread = head(110.0, c5, name = "noteheadWhole", height = 0.7)
        val second = head(120.0, g4)
        val measure = read(listOf(first, misread, second), listOf(stemUp(first), stemUp(second))).single()
        assertEquals(listOf("G4", "G4"), measure.events.map { (it.event as Chord).pitches.single().toString() })
    }

    /**
     * Small notes struck with the other voice's notes (a cue line, "synthé" over a piano's chords)
     * are a voice of their own, not grace notes: read as grace notes, their rest came first and
     * the piano's rest after it, the piano a beat late (J'ai encore rêvé d'elle, measure 18).
     */
    @Test
    fun smallNotesStruckWithTheOtherVoiceAreACueVoice() {
        val upperRest = DrawnGlyph("restQuarter", Box(100.0, top - 0.5 * space, 100.0 + 1.1 * space, top + 1.5 * space))
        val lowerRest = DrawnGlyph("restQuarter", Box(100.0, top + 2.5 * space, 100.0 + 1.1 * space, top + 4.5 * space))
        val cues = listOf(150.0, 200.0, 250.0).map { head(it, top, height = 0.65) }
        val notes = listOf(150.0, 200.0, 250.0).map { head(it, g4) }
        val measure = read(listOf(upperRest, lowerRest) + cues + notes, cues.map { stemUp(it) } + notes.map { stemDown(it) }).single()
        val cueChords = measure.events.filter { (it.event as? Chord)?.pitches?.single()?.toString() == "F5" }.map { it.event as Chord }
        assertEquals(listOf(false, false, false), cueChords.map { it.grace })
        assertEquals(listOf(Fraction.of(1, 4), Fraction.of(1, 2), Fraction.of(3, 4)), cueChords.map { it.onset })
        val rests = measure.events.filter { it.event is Rest }
        assertEquals(listOf(Fraction.ZERO, Fraction.ZERO), rests.map { it.event.onset })
        val lower = measure.events.filter { (it.event as? Chord)?.pitches?.single()?.toString() == "G4" }
        assertEquals(listOf(Fraction.of(1, 4), Fraction.of(1, 2), Fraction.of(3, 4)), lower.map { it.event.onset })
    }

    /**
     * A whole note over chords with a rest beside it: a rest further on, among the chords, is
     * theirs (it went to the whole note's voice, the chords' measure a beat too long: J'ai encore
     * rêvé d'elle, measure 19).
     */
    @Test
    fun aRestAmongAVoicesChordsIsTheirs() {
        val whole = head(100.0, top, "noteheadWhole")
        val firstRest = DrawnGlyph("restQuarter", Box(100.0, top + 2.5 * space, 100.0 + 1.1 * space, top + 4.5 * space))
        val notes = listOf(150.0, 250.0).map { head(it, g4) }
        val laterRest = DrawnGlyph("restQuarter", Box(200.0, top + 2.5 * space, 200.0 + 1.1 * space, top + 4.5 * space))
        val measure = read(listOf(whole, firstRest, laterRest) + notes, notes.map { stemDown(it) }).single()
        val lower = measure.events.filter { it.box.left > 99.0 && it.event.let { e -> e is Rest || (e as Chord).pitches.single().toString() == "G4" } }
        assertEquals(setOf(2), lower.map { it.event.voice }.toSet())
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(1, 2), Fraction.of(3, 4)), lower.map { it.event.onset })
    }

    /**
     * A whole note under a line of notes on stems, struck with its first: the whole note is a
     * voice, every note on a stem the other, however low (Lettre à France, measure 45: the low
     * notes of the line went to the whole note's voice, both past the measure's end).
     */
    @Test
    fun aWholeNoteUnderNotesOnStemsIsAVoiceOfItsOwn() {
        val whole = head(100.0, top + 6 * space, "noteheadWhole")
        val ledgers = listOf(DrawnLine(97.0, top + 5 * space, 110.0, top + 5 * space, 0.5), DrawnLine(97.0, top + 6 * space, 110.0, top + 6 * space, 0.5))
        val quarters = listOf(head(100.0, g4), head(125.0, top + 5 * space), head(150.0, g4), head(175.0, top + 5 * space))
        val measure = read(listOf(whole) + quarters, ledgers + quarters.map { stemUp(it) }).single()
        val chords = measure.events.map { it.event as Chord }
        assertEquals(listOf("G4", "C4", "G4", "C4"), chords.filter { it.voice == 1 }.map { it.pitches.single().toString() })
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4), Fraction.of(1, 2), Fraction.of(3, 4)), chords.filter { it.voice == 1 }.map { it.onset })
        assertEquals(listOf("A3"), chords.filter { it.voice == 2 }.map { it.pitches.single().toString() })
    }

    /** Stems of two staves on almost the same x stay two stems: the upper one keeps its flag. */
    @Test
    fun stemsOfTwoStavesOnOneXStayApart() {
        val lowerTop = top + 10 * space
        val lowerLines = (0..4).map { DrawnLine(50.0, lowerTop + it * space, 550.0, lowerTop + it * space, 0.5) }
        val system = listOf(DrawnLine(50.0, top, 50.0, lowerTop + 4 * space, 0.8))
        val upper = head(100.0, g4)
        val upperStem = stemUp(upper)
        val flag = DrawnGlyph("flag8thUp", Box(upperStem.x1, upperStem.y2, upperStem.x1 + 0.9 * space, upperStem.y2 + 3 * space))
        val lower = head(100.0 - 0.01, lowerTop + 3 * space)
        val fClef = DrawnGlyph("fClef", Box(55.0, lowerTop - 0.2 * space, 55.0 + 2.7 * space, lowerTop + 3.3 * space))
        val page = PageDrawing(
            600.0, 800.0, listOf(clef, fClef, upper, flag, lower),
            staffLines + lowerLines + system + upperStem + stemUp(lower), emptyList(), emptyList(),
        )
        val staves = StaffNotes.read(page, Staves.find(page)).single()
        assertEquals(listOf(NoteType.EIGHTH), types(Rhythms.read(page, staves[0]).single()))
        assertEquals(listOf(NoteType.QUARTER), types(Rhythms.read(page, staves[1]).single()))
    }

    /** An italic "3" in a text font, its baseline at [y], centred on [x]. */
    private fun number(text: String, x: Double, y: Double) = DrawnText(text, "Edwin-Italic", x - 0.3 * 2 * space, y, 2 * space)

    /** A quarter and an eighth under a bracketed "3": three eighths in the time of two. */
    @Test
    fun aBracketedThreeMakesATriplet() {
        val quarter = head(100.0, g4)
        val eighth = head(115.0, g4)
        val after = head(140.0, g4)
        val s2 = stemUp(eighth)
        val flag = DrawnGlyph("flag8thUp", Box(s2.x1, s2.y2, s2.x1 + 0.9 * space, s2.y2 + 3 * space))
        // the bracket over the stems' ends: a hook down at each end, the "3" in its middle
        val y = s2.y2 - 1.5 * space
        val bracket = listOf(
            DrawnLine(quarter.box.left, y, 108.0, y, 0.5), DrawnLine(112.0, y, s2.x1, y, 0.5),
            DrawnLine(quarter.box.left, y, quarter.box.left, y + 0.75 * space, 0.5), DrawnLine(s2.x1, y, s2.x1, y + 0.75 * space, 0.5),
        )
        val measure = read(
            listOf(quarter, eighth, flag, after), listOf(stemUp(quarter), s2, stemUp(after)) + bracket,
            texts = listOf(number("3", 110.0, y + 0.5 * space)),
        ).single()
        assertEquals(listOf(Fraction.of(2, 3), Fraction.of(2, 3), Fraction.of(1)), measure.events.map { it.event.duration.tuplet })
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 6), Fraction.of(1, 4)), onsets(measure))
    }

    /**
     * A rest that starts a bracket: the hook is drawn right of the rest's middle, the rest still
     * takes part in the triplet.
     */
    @Test
    fun aRestStartingABracketIsInTheTriplet() {
        val rest = DrawnGlyph("restQuarter", Box(100.0, top + space, 104.6, top + 4 * space))
        val eighth = head(112.0, g4)
        val after = head(130.0, g4)
        val s2 = stemUp(eighth)
        val flag = DrawnGlyph("flag8thUp", Box(s2.x1, s2.y2, s2.x1 + 0.9 * space, s2.y2 + 3 * space))
        val y = s2.y2 - 1.5 * space
        val hook = 103.0
        val bracket = listOf(
            DrawnLine(hook, y, 108.0, y, 0.5), DrawnLine(113.0, y, s2.x1, y, 0.5),
            DrawnLine(hook, y, hook, y + 0.75 * space, 0.5), DrawnLine(s2.x1, y, s2.x1, y + 0.75 * space, 0.5),
        )
        val measure = read(
            listOf(rest, eighth, flag, after), listOf(s2, stemUp(after)) + bracket,
            texts = listOf(number("3", (hook + s2.x1) / 2, y + 0.5 * space)),
        ).single()
        assertEquals(listOf(Fraction.of(2, 3), Fraction.of(2, 3), Fraction.of(1)), measure.events.map { it.event.duration.tuplet })
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 6), Fraction.of(1, 4)), onsets(measure))
    }

    /** Three beamed eighths with a "3" over their beam and no bracket. */
    @Test
    fun aThreeOverABeamMakesATriplet() {
        val heads = (0 until 3).map { head(100.0 + it * 3 * space, g4) }
        val after = head(150.0, g4)
        val stems = heads.map { stemUp(it) }
        val beam = flatBeamUp(stems[0].x1, stems[2].x1, stems[0].y2, 0)
        val measure = read(
            heads + after, stems + beam + stemUp(after),
            texts = listOf(number("3", stems[1].x1, stems[0].y2 - 0.5 * space)),
        ).single()
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 12), Fraction.of(1, 6), Fraction.of(1, 4)), onsets(measure))
    }

    /** A fingering "3" over a quarter is no triplet: a third of a quarter is no note's value. */
    @Test
    fun aFingeringIsNoTuplet() {
        val note = head(100.0, g4)
        val next = head(120.0, g4)
        val measure = read(listOf(note, next), listOf(stemDown(note), stemDown(next)), texts = listOf(number("3", 103.0, top - space))).single()
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 4)), onsets(measure))
    }

    /**
     * A "3" high over a melody's stems may be closer to the staff above it (another system): it
     * still goes with the notes it is nearest to.
     */
    @Test
    fun aTupletNumberGoesWithTheNearestNotes() {
        val lowerTop = top + 15 * space
        val lowerLines = (0..4).map { DrawnLine(50.0, lowerTop + it * space, 550.0, lowerTop + it * space, 0.5) }
        val lowerClef = DrawnGlyph("gClef", Box(55.0, lowerTop - 1.5 * space, 55.0 + 2.7 * space, lowerTop + 5.5 * space))
        val upperNote = head(100.0, top + 2 * space)
        val melodyY = lowerTop - 1.5 * space
        val quarter = head(100.0, melodyY)
        val eighth = head(115.0, melodyY)
        val s1 = stemUp(quarter)
        val s2 = stemUp(eighth)
        val flag = DrawnGlyph("flag8thUp", Box(s2.x1, s2.y2, s2.x1 + 0.9 * space, s2.y2 + 3 * space))
        val after = head(140.0, melodyY)
        val y = s2.y2 - 1.5 * space
        val bracket = listOf(
            DrawnLine(quarter.box.left, y, s2.x1, y, 0.5),
            DrawnLine(quarter.box.left, y, quarter.box.left, y + 0.75 * space, 0.5), DrawnLine(s2.x1, y, s2.x1, y + 0.75 * space, 0.5),
        )
        val page = PageDrawing(
            600.0, 800.0, listOf(clef, lowerClef, upperNote, quarter, eighth, flag, after),
            staffLines + lowerLines + bracket + listOf(stemUp(upperNote), s1, s2, stemUp(after)) + listOf(quarter, eighth, after).map { ledger(it, lowerTop - space) },
            emptyList(),
            listOf(number("3", 110.0, y + 0.3 * space)),
        )
        val systems = StaffNotes.read(page, Staves.find(page))
        assertEquals(2, systems.size)
        val lower = Rhythms.read(page, systems[1].single()).single()
        assertEquals(listOf(Fraction.ZERO, Fraction.of(1, 6), Fraction.of(1, 4)), onsets(lower))
    }
}
