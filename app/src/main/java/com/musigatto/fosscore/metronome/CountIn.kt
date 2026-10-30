package com.musigatto.fosscore.metronome

/**
 * The bars counted before the music starts at [from] (quarter notes into the piece): [bars] full
 * bars of [meter], clicked as [BeatGrid] clicks the piece, numbered below 0 (-1 the last before
 * the music) as the metronome alone numbers its count-in. The music waits [length] quarter notes.
 */
class CountIn(bars: Int, meter: Meter, subdivision: Int, accent: Boolean, val from: Double) {
    /** How long the count-in lasts, in quarter notes of the piece. */
    val length: Double = bars.coerceAtLeast(0) * meter.beats * 4.0 / meter.unit

    /** Where the count-in starts: [length] before [from]. */
    val start: Double get() = from - length

    private val count = bars.coerceAtLeast(0)
    private val grid = BeatGrid(
        List(count) { start + it * length / count },
        end = from,
        meter = meter,
        subdivision = subdivision,
        accent = accent,
    )

    /** Where each click is, in time order, all before [from]. */
    val at: DoubleArray = if (count == 0) DoubleArray(0) else grid.at

    /** What each click is, as [BeatGrid.kinds]. */
    val kinds: Array<ClickKind> = if (count == 0) emptyArray() else grid.kinds

    /** The beat counted at [quarters], its bar below 0; null outside the count-in. */
    fun tickAt(quarters: Double): Tick? {
        if (count == 0 || quarters >= from - EPSILON || quarters < start - EPSILON) return null
        return grid.tickAt(quarters)?.let { it.copy(bar = it.bar - count) }
    }

    private companion object {
        // two positions this close are the same moment of the music
        const val EPSILON = 1e-6
    }
}
