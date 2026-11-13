package com.musigatto.fosscore.pdfscore.music

/** A time signature: [beats] of a [beatType] (4 for quarters) to a measure; "C" is 4/4, "¢" 2/2. */
data class TimeSignature(val beats: Int, val beatType: Int) {
    /** How long a full measure is, in whole notes. */
    val measureLength: Fraction get() = Fraction.of(beats.toLong(), beatType.toLong())
}
