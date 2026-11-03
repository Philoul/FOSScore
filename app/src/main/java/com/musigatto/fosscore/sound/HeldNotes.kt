package com.musigatto.fosscore.sound

/** Where the live player's notes go: the synth, or a test's record. */
internal interface Keys {
    fun on(channel: Int, key: Int, velocity: Int)
    fun off(channel: Int, key: Int)
}

/**
 * The notes the live player holds, from their note on to their note off. A new transposition
 * applies to the next notes only: a note already sounding ends in the key it began in.
 */
internal class HeldNotes(private val keys: Keys) {

    // a note on: its channel, its key in the song and the key heard (transposed), and where it
    // started in the song
    private class Note(val channel: Int, val key: Int, val played: Int, val quarters: Double)

    private val notes = ArrayList<Note>()

    /** Semitones the next notes are heard up (or down), the drums excepted. */
    var semitones = 0

    val size: Int get() = notes.size

    /** A note on of the song, [quarters] into it. */
    fun start(channel: Int, key: Int, velocity: Int, quarters: Double) {
        val played = heard(channel, key)
        // the key heard struck again while it still sounds (another note of the song, heard there
        // since a new transposition): the old note ends, as on a piano, and its note off later
        // does not cut the new one
        release { it.channel == channel && it.played == played }
        keys.on(channel, played, velocity)
        notes += Note(channel, key, played, quarters)
    }

    /** A note off of the song. */
    fun stop(channel: Int, key: Int) = release { it.channel == channel && it.key == key }

    /** Ends the notes not in [wanted] (channel, key in the song, start): a new song without them. */
    fun keepOnly(wanted: Set<Triple<Int, Int, Double>>) = release { Triple(it.channel, it.key, it.quarters) !in wanted }

    /** Forgets every note: the synth was silenced. */
    fun clear() = notes.clear()

    private inline fun release(which: (Note) -> Boolean) {
        val it = notes.iterator()
        while (it.hasNext()) {
            val n = it.next()
            if (which(n)) {
                keys.off(n.channel, n.played)
                it.remove()
            }
        }
    }

    // the key heard for [key] of the song
    private fun heard(channel: Int, key: Int) =
        if (channel == Synth.DRUM_CHANNEL) key else (key + semitones).coerceIn(0, 127)
}
