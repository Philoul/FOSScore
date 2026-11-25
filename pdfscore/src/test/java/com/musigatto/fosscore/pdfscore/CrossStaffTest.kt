package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Duration
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.NoteType
import com.musigatto.fosscore.pdfscore.music.Pitch
import com.musigatto.fosscore.pdfscore.music.Stem
import com.musigatto.fosscore.pdfscore.music.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The time on a piano's two staves runs left to right on both (maintainer, 2026-11-21: every voice
 * of a measure fills it, its notes neither early nor late nor cut). An arpeggio up from the left
 * hand goes on in the right hand (Pas de boogie woogie, measure 1): the right hand starts where the
 * left hand's notes drawn before it end, not with them.
 */
class CrossStaffTest {
    private val four = TimeSignature(4, 4)

    private fun note(x: Double, onset: Fraction, type: NoteType, step: Int = 0, octave: Int = 4) = PlacedEvent(
        Chord(listOf(Pitch(step, octave)), Duration(type), Stem.UP, onset, 1),
        Box(x, 0.0, x + 5.0, 5.0),
    )

    private fun staff(n: Int, clef: Clef, events: List<PlacedEvent>) =
        MeasureStaff(StaffBox(n, 0.0, 400.0, 0.0, 20.0, 5.0, 10.0), clef, Key(0), four, emptyList(), events)

    private fun onsets(staff: MeasureStaff) = staff.events.map { it.event.onset }

    @Test
    fun anArpeggioGoesOnInTheRightHandAfterTheLeftHand() {
        val sixteenth = Fraction.of(1, 16)
        // the left hand: three sixteenths, then nothing
        val left = (0 until 3).map { note(100.0 + 10 * it, sixteenth * Fraction.of(it.toLong()), NoteType.SIXTEENTH, octave = 3) }
        // the right hand, drawn after them: nine sixteenths and a quarter, read from the measure's start
        val right = (0 until 9).map { note(130.0 + 10 * it, sixteenth * Fraction.of(it.toLong()), NoteType.SIXTEENTH) } +
            note(230.0, Fraction.of(9, 16), NoteType.QUARTER)
        val measure = ScoreMeasure(1, 0, true, true, listOf(staff(1, Clef(Clef.Sign.G, 2), right), staff(2, Clef(Clef.Sign.F, 4), left)))
        val read = ScoreReader.crossStaffStarts(listOf(measure), listOf(Part(2))).single()
        assertEquals(Fraction.of(3, 16), onsets(read.staves[0]).first())
        assertEquals(Fraction.of(12, 16), onsets(read.staves[0]).last())
        assertEquals(onsets(measure.staves[1]), onsets(read.staves[1]))
    }

    /**
     * Every voice fills its measure: an eighth rest given to a whole note's voice, after it (9/8),
     * while the melody it is drawn in lacks an eighth (7/8), goes to the melody, where it is drawn
     * (Pas de boogie woogie, measure 6).
     */
    @Test
    fun anEventGoesToTheVoiceItMakesFitItsMeasure() {
        fun rest(x: Double, onset: Fraction, type: NoteType, voice: Int) =
            PlacedEvent(com.musigatto.fosscore.pdfscore.music.Rest(Duration(type), onset, voice), Box(x, 0.0, x + 5.0, 5.0))
        fun on(placed: PlacedEvent, voice: Int) = placed.copy(event = (placed.event as Chord).copy(voice = voice))
        val events = listOf(
            rest(205.0, Fraction.ZERO, NoteType.QUARTER, 1),
            on(note(205.0, Fraction.ZERO, NoteType.WHOLE), 2),
            note(231.0, Fraction.of(1, 4), NoteType.EIGHTH),
            note(247.0, Fraction.of(3, 8), NoteType.QUARTER),
            note(262.0, Fraction.of(5, 8), NoteType.EIGHTH),
            note(277.0, Fraction.of(3, 4), NoteType.EIGHTH),
            rest(220.0, Fraction.of(1), NoteType.EIGHTH, 2),
        )
        val measure = ScoreMeasure(1, 0, true, true, listOf(staff(1, Clef(Clef.Sign.G, 2), events)))
        val read = ScoreReader.fitVoices(listOf(measure)).single().staves[0].events
        val moved = read.single { it.box.left == 220.0 }.event
        assertEquals(1, moved.voice)
        assertEquals(Fraction.of(1, 4), moved.onset)
        assertEquals(listOf(Fraction.of(3, 8), Fraction.of(1, 2), Fraction.of(3, 4), Fraction.of(7, 8)),
            read.filter { it.event is Chord && it.event.voice == 1 }.map { it.event.onset })
    }

    /**
     * A voice too long by a note, and no voice of its staff with room for it: the note gets a
     * voice of its own, on the beat where it is drawn (Né en 17 à Leidenstadt, 21, in 2/4: a half
     * and, beside it on the second beat, a quarter, both stems up, read one after the other).
     */
    @Test
    fun aNoteNoVoiceHasRoomForGetsAVoiceOfItsOwnWhereItIsDrawn() {
        val events = listOf(note(10.0, Fraction.ZERO, NoteType.HALF), note(205.0, Fraction.of(1, 2), NoteType.QUARTER))
        val staff = MeasureStaff(StaffBox(1, 0.0, 400.0, 0.0, 20.0, 5.0, 10.0), Clef(Clef.Sign.F, 4), Key(0), TimeSignature(2, 4), emptyList(), events)
        val read = ScoreReader.fitVoices(listOf(ScoreMeasure(1, 0, true, true, listOf(staff)))).single().staves[0].events
        assertEquals(listOf(1 to Fraction.ZERO, 2 to Fraction.of(1, 4)), read.map { it.event.voice to it.event.onset })
    }

    /**
     * A voice struck with another voice of its staff at the measure's start starts then, whatever
     * the other hand draws a little left of it (Lettre à France, measure 24's left hand).
     */
    @Test
    fun aVoiceStruckWithTheMeasuresStartStaysThere() {
        fun on(placed: PlacedEvent, voice: Int) = placed.copy(event = (placed.event as Chord).copy(voice = voice))
        val right = listOf(note(90.0, Fraction.ZERO, NoteType.QUARTER))
        val left = listOf(
            note(100.0, Fraction.ZERO, NoteType.HALF, octave = 3),
            note(200.0, Fraction.of(1, 2), NoteType.HALF, octave = 3),
            on(note(100.0, Fraction.ZERO, NoteType.QUARTER, octave = 2), 2),
        )
        val measure = ScoreMeasure(1, 0, true, true, listOf(staff(1, Clef(Clef.Sign.G, 2), right), staff(2, Clef(Clef.Sign.F, 4), left)))
        val read = ScoreReader.crossStaffStarts(listOf(measure), listOf(Part(2))).single()
        assertEquals(onsets(measure.staves[1]), onsets(read.staves[1]))
    }

    /** A hand that fills its measure already, or that would overflow it, keeps its time. */
    @Test
    fun aHandThatFillsItsMeasureKeepsItsTime() {
        val left = listOf(note(100.0, Fraction.ZERO, NoteType.HALF, octave = 3))
        val right = listOf(note(130.0, Fraction.ZERO, NoteType.HALF), note(200.0, Fraction.of(1, 2), NoteType.HALF))
        val measure = ScoreMeasure(1, 0, true, true, listOf(staff(1, Clef(Clef.Sign.G, 2), right), staff(2, Clef(Clef.Sign.F, 4), left)))
        val read = ScoreReader.crossStaffStarts(listOf(measure), listOf(Part(2))).single()
        assertEquals(onsets(measure.staves[0]), onsets(read.staves[0]))
    }
}
