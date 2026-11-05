package com.musigatto.fosscore.sound

import org.junit.Assert.assertEquals
import org.junit.Test

class HeldNotesTest {

    // what reached the synth, in order: "on 0 60" / "off 0 60"
    private val heard = ArrayList<String>()
    private val held = HeldNotes(object : Keys {
        override fun on(channel: Int, key: Int, velocity: Int) {
            heard += "on $channel $key"
        }

        override fun off(channel: Int, key: Int) {
            heard += "off $channel $key"
        }
    })

    @Test
    fun aNewTranspositionLeavesTheNotesHeldAsTheyBegan() {
        held.start(0, 60, 100, 0.0)
        held.start(0, 64, 100, 0.0)
        heard.clear()
        held.semitones = 2
        // the chord is not struck again
        assertEquals(emptyList<String>(), heard)
        // it ends in its own key, and the next notes are heard in the new one
        held.stop(0, 60)
        held.stop(0, 64)
        held.start(0, 67, 100, 1.0)
        assertEquals(listOf("off 0 60", "off 0 64", "on 0 69"), heard)
    }

    @Test
    fun theKeyHeardStruckAgainEndsTheNoteStillSoundingThere() {
        // a 62 held, then two semitones down: the song's 64 is heard at 62 too
        held.start(0, 62, 100, 0.0)
        held.semitones = -2
        held.start(0, 64, 100, 0.5)
        assertEquals(listOf("on 0 62", "off 0 62", "on 0 62"), heard)
        // the old note's note off does not cut the new one
        held.stop(0, 62)
        assertEquals(1, held.size)
        held.stop(0, 64)
        assertEquals(listOf("on 0 62", "off 0 62", "on 0 62", "off 0 62"), heard)
    }

    @Test
    fun theDrumsAreNotTransposed() {
        held.semitones = 3
        held.start(Synth.DRUM_CHANNEL, 38, 100, 0.0)
        assertEquals(listOf("on ${Synth.DRUM_CHANNEL} 38"), heard)
    }
}
