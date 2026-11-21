package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Duration
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.NoteType
import com.musigatto.fosscore.pdfscore.music.Pitch
import com.musigatto.fosscore.pdfscore.music.Stem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A note tied over a barline keeps the alteration of the note it is tied from, written on that
 * one only; the notes after it in its measure take the key again, unless written (maintainer,
 * 2026-11-21: a C♯ tied to the next measure's first note read as a C).
 */
class TiedAlterationsTest {
    private val c = 0
    private fun note(alter: Int, onset: Fraction, tied: Boolean = false, written: Boolean = false) = Chord(
        listOf(Pitch(c, 5, alter)), Duration(NoteType.QUARTER), Stem.UP, onset, 1,
        ties = if (tied) setOf(0) else emptySet(),
        accidentals = if (written) mapOf(0 to alter) else emptyMap(),
    )

    private fun measure(rank: Int, vararg chords: Chord) = ScoreMeasure(
        rank, 0, false, false,
        listOf(MeasureStaff(StaffBox(1, 0.0, 100.0, 0.0, 20.0, 5.0, 10.0), Clef(Clef.Sign.G, 2), Key(0), null, emptyList(),
            chords.map { PlacedEvent(it, Box(0.0, 0.0, 5.0, 5.0)) })),
    )

    private fun alters(m: ScoreMeasure) = m.staves[0].events.map { (it.event as Chord).pitches[0]?.alter }

    @Test
    fun aNoteTiedOverABarlineKeepsItsSharp() {
        val read = ScoreReader.tiedAlterations(
            listOf(
                measure(1, note(1, Fraction.of(3, 4), tied = true, written = true)),
                // the tied C, then a C the key gives (natural), then a C♯ written again
                measure(2, note(0, Fraction.ZERO), note(0, Fraction.of(1, 4)), note(1, Fraction.of(1, 2), written = true)),
            )
        )
        assertEquals(listOf(1), alters(read[0]))
        assertEquals(listOf(1, 0, 1), alters(read[1]))
    }

    @Test
    fun aWrittenAccidentalOnTheTiedNoteIsKept() {
        val read = ScoreReader.tiedAlterations(
            listOf(measure(1, note(1, Fraction.ZERO, tied = true, written = true)), measure(2, note(0, Fraction.ZERO, written = true)))
        )
        assertEquals(listOf(0), alters(read[1]))
    }
}
