package com.musigatto.fosscore.musicxml

import kotlin.math.roundToInt

/**
 * Changes a standard MIDI file in place, without Verovio: a few milliseconds instead of seconds
 * on a slow tablet, so the tempo and the parts heard change while the music plays. Every change
 * keeps the length of the bytes: tempos are rewritten in their 3 bytes, and the notes of a track
 * left out start with velocity 0, which MIDI reads as "note off".
 */
internal object MidiEdit {

    /**
     * [midi] with every tempo multiplied by [factor] (2.0 = twice as fast), and the notes of the
     * tracks in [silent] (0-based, in file order) muted.
     */
    fun edit(midi: ByteArray, factor: Double, silent: Set<Int>): ByteArray {
        val out = midi.copyOf()
        if (out.size < HEADER || String(out, 0, 4, Charsets.ISO_8859_1) != "MThd") return out
        var at = 8 + int32(out, 4)
        var track = 0
        while (at + 8 <= out.size) {
            val length = int32(out, at + 4)
            val start = at + 8
            val end = minOf(start + length, out.size)
            if (String(out, at, 4, Charsets.ISO_8859_1) == "MTrk") {
                editTrack(out, start, end, factor, track in silent)
                track++
            }
            at = end
        }
        return out
    }

    private fun editTrack(b: ByteArray, start: Int, end: Int, factor: Double, mute: Boolean) {
        var i = start
        var status = 0
        while (i < end) {
            // delta time
            while (i < end && b[i].toInt() and 0x80 != 0) i++
            i++
            if (i >= end) return
            var byte = b[i].toInt() and 0xFF
            if (byte >= 0x80) {
                i++
            } else {
                // running status: the data starts here
                byte = status
            }
            when {
                byte == 0xFF -> {
                    val type = b[i].toInt() and 0xFF
                    i++
                    var length = 0
                    while (i < end) {
                        val v = b[i].toInt() and 0xFF
                        i++
                        length = (length shl 7) or (v and 0x7F)
                        if (v and 0x80 == 0) break
                    }
                    if (type == TEMPO && length == 3 && i + 3 <= end) {
                        val micros = (b[i].toInt() and 0xFF shl 16) or (b[i + 1].toInt() and 0xFF shl 8) or (b[i + 2].toInt() and 0xFF)
                        val scaled = (micros / factor).roundToInt().coerceIn(1, 0xFFFFFF)
                        b[i] = (scaled shr 16).toByte()
                        b[i + 1] = (scaled shr 8).toByte()
                        b[i + 2] = scaled.toByte()
                    }
                    i += length
                }
                byte == 0xF0 || byte == 0xF7 -> {
                    var length = 0
                    while (i < end) {
                        val v = b[i].toInt() and 0xFF
                        i++
                        length = (length shl 7) or (v and 0x7F)
                        if (v and 0x80 == 0) break
                    }
                    i += length
                }
                else -> {
                    status = byte
                    val kind = byte and 0xF0
                    if (kind == NOTE_ON && mute && i + 1 < end) b[i + 1] = 0
                    i += if (kind == PROGRAM || kind == PRESSURE) 1 else 2
                }
            }
        }
    }

    private fun int32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    private const val HEADER = 14
    private const val TEMPO = 0x51
    private const val NOTE_ON = 0x90
    private const val PROGRAM = 0xC0
    private const val PRESSURE = 0xD0
}
