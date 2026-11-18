package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.BeamMark
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Duration
import com.musigatto.fosscore.pdfscore.music.Event
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.Rest

/**
 * Triplets whose number was not read, or not printed (LilyPond prints one "3" for several groups):
 * a measure holds exactly what its time signature says (the maintainer: in 4/4, exactly four beats
 * between two barlines). When a voice is too long and its beamed groups of 3, 6... equal notes,
 * made triplets, take off exactly what is too much, they are triplets.
 */
object ImpliedTuplets {
    private val TRIPLET = Fraction.of(2, 3)

    /** [events] of one staff in a measure [length] long, with the triplets its voices imply. */
    fun apply(events: List<PlacedEvent>, length: Fraction?): List<PlacedEvent> {
        if (length == null || events.isEmpty()) return events
        val fixed = events.groupBy { it.event.voice }.mapValues { (_, voice) -> fix(voice, length) }
        val byVoice = fixed.mapValues { (_, voice) -> voice.iterator() }
        // the events in their order, each replaced by its voice's fixed one
        return events.map { byVoice.getValue(it.event.voice).next() }
    }

    private fun fix(voice: List<PlacedEvent>, length: Fraction): List<PlacedEvent> {
        val timed = voice.filter { counts(it.event) }
        val end = timed.maxOfOrNull { it.event.onset + it.event.duration.length } ?: return voice
        val excess = end - length
        if (excess <= Fraction.ZERO) return voice
        val groups = groups(voice)
        val cut = { group: List<PlacedEvent> -> group.fold(Fraction.ZERO) { sum, it -> sum + it.event.duration.length } * Fraction.of(1, 3) }
        val chosen = when {
            groups.isEmpty() -> return voice
            groups.fold(Fraction.ZERO) { sum, it -> sum + cut(it) } == excess -> groups
            else -> groups.filter { cut(it) == excess }.takeIf { it.size == 1 } ?: return voice
        }
        val made = java.util.IdentityHashMap<PlacedEvent, Boolean>().apply { chosen.flatten().forEach { put(it, true) } }
        // each event moves earlier by what the triplets before it lost
        return voice.map { placed ->
            val shift = voice.filter { it in made && it.event.onset < placed.event.onset && counts(it.event) }
                .fold(Fraction.ZERO) { sum, it -> sum + it.event.duration.length * Fraction.of(1, 3) }
            val duration = if (placed in made) Duration(placed.event.duration.type, placed.event.duration.dots, TRIPLET) else placed.event.duration
            placed.copy(event = moved(placed.event, placed.event.onset - shift, duration))
        }
    }

    // the voice's beamed runs of 3, 6... notes of one value, not tuplets yet
    private fun groups(voice: List<PlacedEvent>): List<List<PlacedEvent>> {
        val runs = mutableListOf<MutableList<PlacedEvent>>()
        var open: MutableList<PlacedEvent>? = null
        for (placed in voice.sortedBy { it.event.onset }) {
            val chord = placed.event as? Chord
            val mark = chord?.beams?.firstOrNull()
            if (chord == null || chord.grace || mark == null) {
                open = null
                continue
            }
            if (mark == BeamMark.BEGIN || open == null) open = mutableListOf<PlacedEvent>().also { runs += it }
            open.add(placed)
            if (mark == BeamMark.END) open = null
        }
        return runs.filter { run ->
            run.size % 3 == 0 && run.all { it.event.duration.tuplet == Fraction.of(1) } &&
                run.map { it.event.duration }.distinct().size == 1
        }
    }

    private fun counts(event: Event) = !(event is Chord && event.grace) && !(event is Rest && event.wholeMeasure)

    private fun moved(event: Event, onset: Fraction, duration: Duration): Event = when (event) {
        is Chord -> event.copy(onset = onset, duration = duration)
        is Rest -> event.copy(onset = onset, duration = duration)
    }
}
