package com.musigatto.fosscore.metronome

/**
 * The clicks of the metronome over a piece, in quarter notes from its start, as the live player
 * plays them: each bar from where the score starts it ([bars]; a pickup bar is shorter), its
 * pulses (a dotted quarter in 6/8, 9/8 or 12/8) and the [subdivision]s between them, until [end].
 * The clock is the music's: the clicks follow a new tempo, the loop and the repeats with it.
 */
class BeatGrid(bars: List<Double>, end: Double, meter: Meter, subdivision: Int, accent: Boolean) {
    /** Where each click is, in time order. */
    val at: DoubleArray

    /** What each click is: the first beat of a bar, another beat, or a subdivision. */
    val kinds: Array<ClickKind>

    // the pulses only, with their bar and beat: what the beat dots show
    private val pulseAt: DoubleArray
    private val pulseTick: Array<Tick>

    init {
        val pulse = 4.0 / meter.unit * (if (meter.compound) 3 else 1)
        val starts = bars.ifEmpty { listOf(0.0) }
        val times = ArrayList<Double>()
        val types = ArrayList<ClickKind>()
        val pulses = ArrayList<Double>()
        val ticks = ArrayList<Tick>()
        for ((bar, start) in starts.withIndex()) {
            val stop = starts.getOrNull(bar + 1) ?: end
            var t = start
            var beat = 0
            while (t < stop - EPSILON) {
                times += t
                types += if (beat == 0 && accent) ClickKind.ACCENT else ClickKind.BEAT
                pulses += t
                ticks += Tick(bar, beat)
                for (s in 1 until subdivision) {
                    val sub = t + pulse * s / subdivision
                    if (sub >= stop - EPSILON) break
                    times += sub
                    types += ClickKind.SUBDIVISION
                }
                t += pulse
                beat++
            }
        }
        at = times.toDoubleArray()
        kinds = types.toTypedArray()
        pulseAt = pulses.toDoubleArray()
        pulseTick = ticks.toTypedArray()
    }

    /** The beat heard at [quarters]: its bar and its beat in the bar; null before the first. */
    fun tickAt(quarters: Double): Tick? {
        var lo = 0
        var hi = pulseAt.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (pulseAt[mid] <= quarters + EPSILON) lo = mid + 1 else hi = mid
        }
        return pulseTick.getOrNull(lo - 1)
    }

    private companion object {
        // two positions this close are the same moment of the music
        const val EPSILON = 1e-6
    }
}
