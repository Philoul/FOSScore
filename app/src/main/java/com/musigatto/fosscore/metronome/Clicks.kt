package com.musigatto.fosscore.metronome

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The sound of the clicks. */
enum class ClickSound { CLICK, HIHAT, WOODBLOCK, DIGITAL }

/** Which click: the first beat of the bar, another beat, or a subdivision between beats. */
enum class ClickKind { ACCENT, BEAT, SUBDIVISION }

/**
 * The click sounds, made in code: no sound file, so no licence to check. The accent is higher
 * and louder, a subdivision softer.
 */
object Clicks {
    const val SAMPLE_RATE = 44_100

    fun of(sound: ClickSound, kind: ClickKind): ShortArray {
        val volume = when (kind) {
            ClickKind.ACCENT -> 1.0
            ClickKind.BEAT -> 0.7
            ClickKind.SUBDIVISION -> 0.35
        }
        val high = kind == ClickKind.ACCENT
        val noise = Random(7)
        var previous = 0.0
        return when (sound) {
            // a short burst of noise, with a tone in it for the accent
            ClickSound.CLICK -> make(0.025, 0.003, volume) { t ->
                noise.nextDouble(-1.0, 1.0) * 0.7 + if (high) sin(2 * PI * 2_000 * t) * 0.5 else 0.0
            }
            // brighter noise, longer
            ClickSound.HIHAT -> make(0.06, if (high) 0.02 else 0.012, volume) {
                val n = noise.nextDouble(-1.0, 1.0)
                (n - previous).also { previous = n } * 0.6
            }
            ClickSound.WOODBLOCK -> make(0.05, 0.012, volume) { t -> sin(2 * PI * (if (high) 1_250 else 830) * t) }
            ClickSound.DIGITAL -> make(0.03, 0.03, volume) { t ->
                if (sin(2 * PI * (if (high) 1_500 else 1_000) * t) >= 0) 0.5 else -0.5
            }
        }
    }

    // [seconds] of [wave] (from -1 to 1 at the time t in seconds), fading out with [decay] seconds
    private fun make(seconds: Double, decay: Double, volume: Double, wave: (Double) -> Double): ShortArray =
        ShortArray((seconds * SAMPLE_RATE).toInt()) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            (wave(t) * exp(-t / decay) * volume * Short.MAX_VALUE * 0.8).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
}
