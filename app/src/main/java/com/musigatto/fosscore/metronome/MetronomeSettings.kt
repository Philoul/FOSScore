package com.musigatto.fosscore.metronome

/** What the metronome gives: clicks, a flash of the page border, or both. */
enum class MetronomeMode { SOUND, VISUAL, BOTH }

// the page turns ahead of the music by up to this many bars
const val MAX_TURN_AHEAD = 2
const val MAX_COUNT_IN = 2
const val MAX_SUBDIVISION = 4

/**
 * The metronome's settings. The first ones belong to the score (its tempo, its time signature,
 * the bars of its pages...); [mode], [sound], [accent], [volume] and [turnAhead] are the same
 * for every score.
 */
data class MetronomeSettings(
    val bpm: Int = DEFAULT_METRONOME_BPM,
    val meter: Meter = Meter(),
    /** Clicks per beat: 1 (none between beats) to 4. */
    val subdivision: Int = 1,
    /** Bars clicked before the piece starts: 0 to 2. */
    val countIn: Int = 0,
    /** The pages turn by themselves as the bars go by. */
    val autoTurn: Boolean = false,
    /** The bars of each page of a PDF score (BarPages). */
    val barCounts: List<Int> = emptyList(),
    val mode: MetronomeMode = MetronomeMode.SOUND,
    val sound: ClickSound = ClickSound.CLICK,
    val accent: Boolean = true,
    /** How loud the clicks are, 0 to 100 (100: as made). */
    val volume: Int = 80,
    /** Bars ahead of the music the page turns (0 to 2), with the metronome and with playback. */
    val turnAhead: Int = 0,
)
