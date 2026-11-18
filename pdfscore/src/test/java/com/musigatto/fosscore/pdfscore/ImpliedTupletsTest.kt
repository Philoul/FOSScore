package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.BeamMark
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Duration
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.NoteType
import com.musigatto.fosscore.pdfscore.music.Pitch
import com.musigatto.fosscore.pdfscore.music.Stem
import org.junit.Assert.assertEquals
import org.junit.Test

class ImpliedTupletsTest {
    private val whole = Fraction.of(1)
    private val c4 = Pitch(0, 4)

    // a voice's notes one after the other, from [types] (null beam: not beamed; else its mark at level 1)
    private fun voice(vararg notes: Pair<Duration, BeamMark?>): List<PlacedEvent> {
        var at = Fraction.ZERO
        return notes.mapIndexed { i, (duration, beam) ->
            val chord = Chord(listOf(c4), duration, Stem.UP, at, 1, beams = listOfNotNull(beam))
            at += duration.length
            PlacedEvent(chord, Box(10.0 * i, 0.0, 10.0 * i + 5, 5.0))
        }
    }

    private fun end(events: List<PlacedEvent>) = events.maxOf { it.event.onset + it.event.duration.length }

    private val eighth = Duration(NoteType.EIGHTH)
    private val sixteenth = Duration(NoteType.SIXTEENTH)

    /**
     * Lettre à France, measure 1, left hand: a dotted quarter, an eighth, a quarter and three
     * beamed eighths under a "3" that was not read: 9/8 in 4/4. The three eighths are the
     * triplet that makes the measure exact (the maintainer: in 4/4, exactly 4 beats).
     */
    @Test
    fun threeBeamedEighthsTooManyAreATriplet() {
        val read = voice(
            Duration(NoteType.QUARTER, 1) to null, eighth to null, Duration(NoteType.QUARTER) to null,
            eighth to BeamMark.BEGIN, eighth to BeamMark.CONTINUE, eighth to BeamMark.END,
        )
        val fixed = ImpliedTuplets.apply(read, whole)
        assertEquals(whole, end(fixed))
        assertEquals(listOf(Fraction.of(1), Fraction.of(1), Fraction.of(1), Fraction.of(2, 3), Fraction.of(2, 3), Fraction.of(2, 3)), fixed.map { it.event.duration.tuplet })
        assertEquals(Fraction.of(3, 4), fixed[3].event.onset)
        assertEquals(Fraction.of(3, 4) + Fraction.of(1, 12), fixed[4].event.onset)
    }

    /** Page 4: four groups of six sixteenths a measure, one "3" printed for some only: all are sextuplets. */
    @Test
    fun groupsOfSixSixteenthsAreTripletsWhenTheMeasureSaysSo() {
        val group = arrayOf(sixteenth to BeamMark.BEGIN) + Array(4) { sixteenth to BeamMark.CONTINUE } + arrayOf(sixteenth to BeamMark.END)
        val read = voice(*(group + group + group + group))
        assertEquals(Fraction.of(3, 2), end(read))
        val fixed = ImpliedTuplets.apply(read, whole)
        assertEquals(whole, end(fixed))
        assertEquals(Fraction.of(1, 4), fixed[6].event.onset)
    }

    @Test
    fun aMeasureOfTheRightLengthIsLeftAlone() {
        val read = voice(eighth to BeamMark.BEGIN, eighth to BeamMark.END, Duration(NoteType.HALF) to null, Duration(NoteType.QUARTER) to null)
        assertEquals(read, ImpliedTuplets.apply(read, whole))
    }

    /** Too long by what no triplet explains (a misread value): left to the writer. */
    @Test
    fun anExcessNoTripletExplainsIsLeftAlone() {
        val read = voice(Duration(NoteType.HALF) to null, Duration(NoteType.HALF) to null, eighth to null)
        assertEquals(read, ImpliedTuplets.apply(read, whole))
    }
}
