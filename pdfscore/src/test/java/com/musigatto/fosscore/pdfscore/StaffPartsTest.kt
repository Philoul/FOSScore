package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Clef
import org.junit.Assert.assertEquals
import org.junit.Test

class StaffPartsTest {

    private val g = Clef.Sign.G
    private val f = Clef.Sign.F

    private fun look(pitch: Int?, lyrics: Boolean = false, clef: Clef.Sign = g, starts: Boolean = pitch != null, ends: Boolean = pitch != null) =
        StaffLook(clef, clef, pitch?.toDouble(), lyrics, starts, ends, pitch?.toDouble(), pitch?.toDouble())

    // a piano's two staves, braced
    private val piano = listOf(look(30), look(16, clef = f))

    private fun system(vararg voices: StaffLook) = SystemLook(voices.toList() + piano, listOf(voices.size..voices.size + 1))

    /**
     * Which voice a staff is, from the music around it (J'ai encore rêvé d'elle, 2026-11-21): a
     * soprano, a tenor and choirs over a piano. The tenor sings alone first (measures 1-42), the
     * soprano joins it over it (43-51), then the choirs under it (52-71), which leave first. Counted
     * from the piano's brace up, the tenor was put on the choirs' staff and the soprano on its own:
     * the voice going on is the one whose lyrics go on, at its pitch.
     */
    @Test
    fun aVoiceGoesOnOnTheStaffWhoseMusicGoesOn() {
        val parts = listOf(Part(1), Part(1), Part(1), Part(2))
        val tenor = look(32, lyrics = true)
        val soprano = look(36)
        val choirs = look(20, lyrics = true, clef = f, starts = false)
        val systems = listOf(system(tenor), system(tenor), system(soprano, tenor), system(soprano, tenor, choirs), system(soprano, tenor))
        val places = StaffParts.places(systems, parts)
        assertEquals(
            listOf(listOf(1, 3, 4), listOf(1, 3, 4), listOf(0, 1, 3, 4), listOf(0, 1, 2, 3, 4), listOf(0, 1, 3, 4)),
            places,
        )
    }

    /**
     * The voice going on is the one whose line goes on, more than the one at its pitch: in J'ai
     * encore rêvé d'elle the soprano enters (measure 43) after a rest, a little below where the
     * tenor sang last, and the tenor goes on from its last note ("rêvé" → "d'elle", lyrics drawn,
     * not written, so not read).
     */
    @Test
    fun aVoiceGoesOnFromItsLastNote() {
        val parts = listOf(Part(1), Part(1), Part(1), Part(2))
        val tenor = StaffLook(g, g, 31.0, false, true, true, first = 30.0, last = 35.0)
        val soprano = StaffLook(g, g, 31.0, false, false, true, first = 28.0, last = 30.0)
        val tenorOn = StaffLook(g, g, 34.0, false, true, true, first = 35.0, last = 34.0)
        val choirs = look(20, clef = f, starts = false)
        val systems = listOf(system(tenor), system(soprano, tenorOn), system(look(36), look(34), choirs))
        assertEquals(listOf(listOf(1, 3, 4), listOf(0, 1, 3, 4), listOf(0, 1, 2, 3, 4)), StaffParts.places(systems, parts))
    }

    /** The brace tells the piano: with no voice above it, its staves are the piano's (page 1 of a song). */
    @Test
    fun aBracedPartAloneIsWhereItsBraceSays() {
        val parts = listOf(Part(1), Part(2))
        val places = StaffParts.places(listOf(SystemLook(piano, listOf(0..1)), system(look(32, lyrics = true))), parts)
        assertEquals(listOf(listOf(1, 2), listOf(0, 1, 2)), places)
    }

    @Test
    fun aSystemWithEveryStaffHasThemInOrder() {
        val parts = listOf(Part(1), Part(1))
        assertEquals(listOf(listOf(0, 1)), StaffParts.places(listOf(SystemLook(listOf(look(30), look(20)), emptyList())), parts))
    }
}
