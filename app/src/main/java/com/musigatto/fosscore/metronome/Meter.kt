package com.musigatto.fosscore.metronome

import kotlin.math.roundToInt

const val MIN_BPM = 20
const val MAX_BPM = 300
const val DEFAULT_METRONOME_BPM = 100
const val MIN_BEATS = 2
const val MAX_BEATS = 20
val BEAT_UNITS = listOf(2, 4, 8)

/**
 * A time signature: [beats] of [unit] (2 = half, 4 = quarter, 8 = eighth note). In compound
 * time (6/8, 9/8, 12/8) the beat is a dotted quarter: 6/8 is counted in 2.
 */
data class Meter(val beats: Int = 4, val unit: Int = 4) {
    val compound: Boolean get() = unit == 8 && beats % 3 == 0 && beats > 3

    /** The beats counted, and clicked, in a bar. */
    val pulses: Int get() = if (compound) beats / 3 else beats

    override fun toString() = "$beats/$unit"

    companion object {
        /** "6/8", "C" (4/4) or "¢" (2/2), as MusicXML or the user writes it; null if unreadable. */
        fun parse(text: String?): Meter? {
            val t = text?.trim() ?: return null
            if (t == "C") return Meter(4, 4)
            if (t == "¢") return Meter(2, 2)
            val parts = t.split("/")
            val beats = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val unit = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
            return Meter(beats.coerceIn(1, MAX_BEATS), unit).takeIf { unit in BEAT_UNITS || unit == 16 || unit == 1 }
        }
    }
}

/** The Italian name of a tempo, shown as a hint: Andante, Allegro... */
fun tempoName(bpm: Int): String = when {
    bpm < 40 -> "Grave"
    bpm < 60 -> "Largo"
    bpm < 66 -> "Larghetto"
    bpm < 76 -> "Adagio"
    bpm < 108 -> "Andante"
    bpm < 120 -> "Moderato"
    bpm < 156 -> "Allegro"
    bpm < 176 -> "Vivace"
    bpm < 200 -> "Presto"
    else -> "Prestissimo"
}

/**
 * Tap tempo: the tempo of the last taps, averaged over the last 4 (3 intervals) so one uneven tap
 * does not make it jump. A tap long after the previous one starts again.
 */
class TapTempo(private val maxGapMs: Long = 2_000L) {
    private val taps = ArrayDeque<Long>()

    /** A tap at [now] (ms): the tempo in beats per minute, or null until there are two taps. */
    fun tap(now: Long): Int? {
        if (taps.isNotEmpty() && now - taps.last() !in 1..maxGapMs) taps.clear()
        taps.addLast(now)
        while (taps.size > 4) taps.removeFirst()
        if (taps.size < 2) return null
        val interval = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        return (60_000 / interval).roundToInt().coerceIn(MIN_BPM, MAX_BPM)
    }
}
