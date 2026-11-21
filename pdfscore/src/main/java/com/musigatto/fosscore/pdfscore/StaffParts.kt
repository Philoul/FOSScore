package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Clef
import kotlin.math.abs

/**
 * What a staff of a system shows, to tell which part it is: its clef at its start and at its end,
 * the middle [pitch] of its notes (white keys from C0, null without notes), whether it has
 * [lyrics], and whether it sounds in its first and in its last measure.
 */
internal data class StaffLook(
    val clefStart: Clef.Sign?,
    val clefEnd: Clef.Sign?,
    val pitch: Double?,
    val lyrics: Boolean,
    val startsSounding: Boolean,
    val endsSounding: Boolean,
    /** The top pitch of its first chord and of its last one. */
    val first: Double? = null,
    val last: Double? = null,
)

/** The staves of a system, top down, and its braces (the staves each joins). */
internal data class SystemLook(val staves: List<StaffLook>, val braces: List<IntRange>)

/**
 * Which staff of the score each staff of each system is, when systems leave out the parts that
 * rest. Each system's staves go to parts in their order, a braced group to a braced part of its
 * size; of all the ways to, the ones kept from system to system are those where the music goes
 * on most: a part keeps its pitch, its line (from its last note to its next one), its lyrics and
 * its clef, and a part sounding at a system's end goes on in the next one. Ties go to the staves counted from the brace up ([ScoreReader.staffPlaces]).
 */
internal object StaffParts {
    /** How far apart (white keys) two staves' pitches cost one part changing staff. */
    private const val PITCH_STEP = 7.0
    private const val PITCH_MOST = 1.0
    private const val LYRICS_CHANGE = 1.0
    private const val CLEF_CHANGE = 1.0
    // a part stopping while it sounds, and one starting: a voice resting is left out of a system,
    // but rarely in the middle of what it sings
    private const val STOPS_SOUNDING = 1.5
    private const val STOPS_QUIET = 1.0
    private const val STARTS_SOUNDING = 1.5
    private const val STARTS_QUIET = 1.0
    // what tells a way apart from the one counted from the brace, when nothing else does
    private const val NOT_THE_GUESS = 0.05

    fun places(systems: List<SystemLook>, parts: List<Part>): List<List<Int>> {
        if (systems.isEmpty()) return emptyList()
        val total = parts.sumOf { it.staves }
        val options = systems.map { system ->
            val guess = ScoreReader.staffPlaces(system.staves.size, system.braces, parts)
            val ways = if (system.staves.size >= total) emptyList() else ways(system, parts)
            (listOf(guess) + ways.filter { it != guess }).map { it to if (it == guess) 0.0 else NOT_THE_GUESS }
        }
        // the cheapest way through the systems, one way per system
        var cost = options[0].map { it.second }
        val from = mutableListOf<IntArray>()
        for (k in 1 until systems.size) {
            val back = IntArray(options[k].size)
            cost = options[k].mapIndexed { j, (place, own) ->
                val (i, best) = options[k - 1].indices.map { i ->
                    i to cost[i] + change(systems[k - 1], options[k - 1][i].first, systems[k], place, total)
                }.minBy { it.second }
                back[j] = i
                best + own
            }
            from += back
        }
        var j = cost.indices.minBy { cost[it] }
        val chosen = ArrayDeque<List<Int>>()
        for (k in systems.indices.reversed()) {
            chosen.addFirst(options[k][j].first)
            if (k > 0) j = from[k - 1][j]
        }
        return chosen.toList()
    }

    /** What it costs that the parts placed [before] in one system are placed [after] in the next. */
    private fun change(was: SystemLook, before: List<Int>, now: SystemLook, after: List<Int>, total: Int): Double =
        (0 until total).sumOf { n ->
            val a = before.indexOf(n).takeIf { it >= 0 }?.let { was.staves.getOrNull(it) }
            val b = after.indexOf(n).takeIf { it >= 0 }?.let { now.staves.getOrNull(it) }
            when {
                a != null && b != null -> {
                    val pitch = apart(a.pitch, b.pitch) + apart(a.last, b.first)
                    val lyrics = if (a.lyrics != b.lyrics) LYRICS_CHANGE else 0.0
                    val clef = if (a.clefEnd != null && b.clefStart != null && a.clefEnd != b.clefStart) CLEF_CHANGE else 0.0
                    pitch + lyrics + clef
                }
                a != null -> if (a.endsSounding) STOPS_SOUNDING else STOPS_QUIET
                b != null -> if (b.startsSounding) STARTS_SOUNDING else STARTS_QUIET
                else -> 0.0
            }
        }

    private fun apart(a: Double?, b: Double?): Double =
        if (a != null && b != null) minOf(abs(a - b) / PITCH_STEP, PITCH_MOST) else 0.0

    /** Every way to give the system's staves to the [parts] in order, a braced group to a braced part of its size. */
    private fun ways(system: SystemLook, parts: List<Part>): List<List<Int>> {
        // the system's groups: a brace's staves, or a staff alone
        val groups = buildList {
            var s = 0
            while (s < system.staves.size) {
                val size = system.braces.firstOrNull { it.first == s }?.let { it.last - it.first + 1 } ?: 1
                add(size)
                s += size
            }
        }
        val firsts = parts.runningFold(0) { s, p -> s + p.staves }
        val out = mutableListOf<List<Int>>()
        fun place(g: Int, p: Int, sofar: List<Int>) {
            if (g == groups.size) {
                out += sofar
                return
            }
            for (q in p until parts.size) {
                if (parts.size - q < groups.size - g) break
                if (parts[q].staves == groups[g]) place(g + 1, q + 1, sofar + (0 until groups[g]).map { firsts[q] + it })
            }
        }
        place(0, 0, emptyList())
        return out
    }
}
