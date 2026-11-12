package com.musigatto.fosscore.pdfscore.music

/** A written pitch: [step] 0 to 6 for C to B, [octave] (4 holds middle C), [alter] in semitones. */
data class Pitch(val step: Int, val octave: Int, val alter: Int = 0) {
    /** White keys counted from C0: one per line or space of a staff. */
    val diatonic: Int get() = octave * 7 + step

    override fun toString(): String = "${STEPS[step]}${ALTERS[alter] ?: "($alter)"}$octave"

    companion object {
        const val STEPS = "CDEFGAB"
        private val ALTERS = mapOf(-2 to "bb", -1 to "b", 0 to "", 1 to "#", 2 to "x")

        fun ofDiatonic(diatonic: Int, alter: Int = 0) = Pitch(Math.floorMod(diatonic, 7), Math.floorDiv(diatonic, 7), alter)
    }
}

/**
 * A clef: [sign] G, F, C or percussion, on [line] (1 the bottom line, 5 the top one), sounding
 * [octaveShift] octaves off (-1 for the tenor's "8" below a G clef).
 */
data class Clef(val sign: Sign, val line: Int, val octaveShift: Int = 0) {
    enum class Sign(val pitch: Pitch) {
        G(Pitch(4, 4)),
        F(Pitch(3, 3)),
        C(Pitch(0, 4)),

        /** Unpitched: notes are placed as on a G clef (what MusicXML's display-step expects). */
        PERCUSSION(Pitch(4, 4)),
    }

    /** The white key written at a staff [step]: 0 on the bottom line, one per line and space up (negative below). */
    fun diatonicAt(step: Int): Int {
        val reference = if (sign == Sign.PERCUSSION) 2 else 2 * (line - 1)
        return sign.pitch.diatonic + 7 * octaveShift + (step - reference)
    }
}

/** A key signature: [fifths] sharps when positive, flats when negative. */
data class Key(val fifths: Int) {
    /** The alteration the key gives a step (0 to 6, C to B). */
    fun alter(step: Int): Int = when {
        fifths > 0 && SHARPS.take(fifths).contains(step) -> 1
        fifths < 0 && FLATS.take(-fifths).contains(step) -> -1
        else -> 0
    }

    private companion object {
        val SHARPS = listOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B
        val FLATS = SHARPS.reversed() // B E A D G C F
    }
}
