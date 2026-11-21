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
 * Its [beams] say, level by level from the outer one (at the stem's end), what each beam on its stem
 * does there; empty when it has none. Under an octave line ("8va", "8vb"), its [pitches] are the
 * ones heard, [ottava] octaves away from where they are drawn (1 up, -1 down). [ties] are the
 * indexes in [pitches] of the notes tied on to the next note of their pitch (held, not struck again).
 * [accidentals] are the ones drawn, by index in [pitches]: their alter (1 a sharp, 0 a natural...).
 * An [arpeggiate] chord is rolled (a wavy line before it); a [fermata] note is held longer. Its
 * [lyric] is the syllable sung on it, if any.
 */
data class Chord(
    val pitches: List<Pitch?>,
    override val duration: Duration,
    val stem: Stem,
    override val onset: Fraction,
    override val voice: Int,
    val grace: Boolean = false,
    val articulations: Set<Articulation> = emptySet(),
    val beams: List<BeamMark> = emptyList(),
    val ottava: Int = 0,
    val ties: Set<Int> = emptySet(),
    val accidentals: Map<Int, Int> = emptyMap(),
    val arpeggiate: Boolean = false,
    val fermata: Boolean = false,
    val lyric: Lyric? = null,
    /** The notes (by index) drawn with an x head: a drum's, with no pitch. */
    val crossHeads: Set<Int> = emptySet(),
    /**
     * The notes (by index) a tie read ends on; null when not known (a tie then ends on the next
     * note of its pitch). The ties of [ties] in [openTies] go on over the line break: their end is
     * the next note of their pitch.
     */
    val tieEnds: Set<Int>? = null,
    val openTies: Set<Int> = emptySet(),
) : Event

/** What a beam does at one stem: begins, goes on or ends there, or is a hook on that stem alone. */
enum class BeamMark(val musicXml: String) {
    BEGIN("begin"),
    CONTINUE("continue"),
    END("end"),
    FORWARD_HOOK("forward hook"),
    BACKWARD_HOOK("backward hook"),
}

/** A mark on the notes that says how to play them, not how long they are written. */
enum class Articulation { STACCATO }

/**
 * A silence. A [wholeMeasure] rest fills its measure whatever its length (its [duration] is a whole);
 * a [fermata] one is held longer.
 */
data class Rest(
    override val duration: Duration,
    override val onset: Fraction,
    override val voice: Int,
    val wholeMeasure: Boolean = false,
    val fermata: Boolean = false,
) : Event

/** One measure of one staff: its events by onset, then voice. [index] counts the staff's measures from 0. */
data class StaffMeasure(val index: Int, val events: List<Event>)
