package com.musigatto.fosscore.pdfscore

/**
 * What a staff shows of its key and time, measure by measure, as the probes compare it with a
 * MusicXML file: the key in force in the measure, and the time signature drawn in it, if any.
 */
internal object Signatures {
    /** "key -3", or "key -3 time 3/4" in a measure that draws a time signature; nothing past the staff's measures. */
    fun read(staff: StaffReading, m: Int): List<String> {
        val start = if (m == 0) Double.NEGATIVE_INFINITY else staff.barlines.getOrNull(m - 1) ?: return emptyList()
        val end = staff.barlines.getOrNull(m) ?: Double.POSITIVE_INFINITY
        val key = staff.keys.lastOrNull { it.x < end }?.value?.fifths ?: 0
        val time = staff.times.lastOrNull { it.x > start && it.x < end }?.value
        return listOf(of(key, time?.let { "${it.beats}/${it.beatType}" }))
    }

    fun of(fifths: Int, time: String?): String = "key $fifths" + (time?.let { " time $it" } ?: "")
}
