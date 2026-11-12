package com.musigatto.fosscore.pdfscore.music

/** A length or a time in whole notes, exact (a triplet eighth is 1/12), always reduced. */
class Fraction private constructor(val numerator: Long, val denominator: Long) : Comparable<Fraction> {
    operator fun plus(other: Fraction) = of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)
    operator fun minus(other: Fraction) = of(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)
    operator fun times(other: Fraction) = of(numerator * other.numerator, denominator * other.denominator)

    override fun compareTo(other: Fraction): Int = (numerator * other.denominator).compareTo(other.numerator * denominator)
    override fun equals(other: Any?): Boolean = other is Fraction && numerator == other.numerator && denominator == other.denominator
    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()
    override fun toString(): String = if (denominator == 1L) "$numerator" else "$numerator/$denominator"

    companion object {
        val ZERO = Fraction(0, 1)

        fun of(numerator: Long, denominator: Long = 1): Fraction {
            require(denominator != 0L) { "no length has a zero denominator" }
            val sign = if (denominator < 0) -1 else 1
            val gcd = gcd(kotlin.math.abs(numerator), kotlin.math.abs(denominator)).coerceAtLeast(1)
            return Fraction(sign * numerator / gcd, sign * denominator / gcd)
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

/**
 * The written value of a note or a rest, as its head, flags and beams show it; [beams] is how many
 * flags or beams a note of this value has. [musicXml] is MusicXML's name for it.
 */
enum class NoteType(val length: Fraction, val beams: Int, val musicXml: String) {
    BREVE(Fraction.of(2), 0, "breve"),
    WHOLE(Fraction.of(1), 0, "whole"),
    HALF(Fraction.of(1, 2), 0, "half"),
    QUARTER(Fraction.of(1, 4), 0, "quarter"),
    EIGHTH(Fraction.of(1, 8), 1, "eighth"),
    SIXTEENTH(Fraction.of(1, 16), 2, "16th"),
    THIRTY_SECOND(Fraction.of(1, 32), 3, "32nd"),
    SIXTY_FOURTH(Fraction.of(1, 64), 4, "64th"),
    HUNDRED_TWENTY_EIGHTH(Fraction.of(1, 128), 5, "128th");

    companion object {
        /** A black note's value from its flags or beams (0 for a quarter); null past what is written. */
        fun withBeams(count: Int): NoteType? = entries.firstOrNull { it.beams == count && it.length <= QUARTER.length }
    }
}

/**
 * How long a note or rest lasts: its written [type], its augmentation [dots] (each adds half of
 * the last), and the [tuplet] ratio that scales it (2/3 for a triplet, 1 when none).
 */
data class Duration(val type: NoteType, val dots: Int = 0, val tuplet: Fraction = Fraction.of(1)) {
    val length: Fraction
        get() {
            var total = type.length
            var part = type.length
            repeat(dots) {
                part *= Fraction.of(1, 2)
                total += part
            }
            return total * tuplet
        }
}
