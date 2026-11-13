package com.musigatto.fosscore.pdfscore.music

/** Which way a note's stem is drawn ([NONE] for a whole note), for drawing the score again. */
enum class Stem { UP, DOWN, NONE }

/**
 * Something that takes time in one voice of a staff: notes struck together, or a rest. [onset] is
 * when it starts, in whole notes from the start of its measure; [voice] counts from 1.
 */
sealed interface Event {
    val onset: Fraction
    val duration: Duration
    val voice: Int
}

/**
 * Notes struck together on one stem (one note alone is a chord of one). A [pitch][pitches] is null
 * while nothing says it (no clef read). A [grace] note takes no time of its own: its [onset] is the
 * one of the note it leads to. Its [articulations] are the marks over or under it (a staccato dot).
 */
data class Chord(
    val pitches: List<Pitch?>,
    override val duration: Duration,
    val stem: Stem,
    override val onset: Fraction,
    override val voice: Int,
    val grace: Boolean = false,
    val articulations: Set<Articulation> = emptySet(),
) : Event

/** A mark on the notes that says how to play them, not how long they are written. */
enum class Articulation { STACCATO }

/** A silence. A [wholeMeasure] rest fills its measure whatever its length (its [duration] is a whole). */
data class Rest(
    override val duration: Duration,
    override val onset: Fraction,
    override val voice: Int,
    val wholeMeasure: Boolean = false,
) : Event

/** One measure of one staff: its events by onset, then voice. [index] counts the staff's measures from 0. */
data class StaffMeasure(val index: Int, val events: List<Event>)
