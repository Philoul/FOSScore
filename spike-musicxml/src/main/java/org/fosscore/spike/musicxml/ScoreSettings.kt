package org.fosscore.spike.musicxml

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything the musician sets for one score; saved per file and restored when it is opened again. */
data class ScoreSettings(
    val scale: Int,
    val semitones: Int = 0,
    /** Parts shown; empty shows all. */
    val parts: Set<String> = emptySet(),
    val lyrics: LyricsPlacement = LyricsPlacement.FILE,
    /** Parts heard; null hears the parts shown. */
    val audible: Set<String>? = null,
    val programs: Map<String, Int> = emptyMap(),
    val tempo: Double = 1.0
) {
    fun toJson(): String = JSONObject().apply {
        put("scale", scale)
        put("semitones", semitones)
        put("parts", JSONArray(parts.toList()))
        put("lyrics", lyrics.name)
        audible?.let { put("audible", JSONArray(it.toList())) }
        put("programs", JSONObject(programs.mapValues { it.value }))
        put("tempo", tempo)
    }.toString()

    companion object {
        private const val PREFS = "score_settings"

        fun load(context: Context, file: String): ScoreSettings? {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(file, null) ?: return null
            return runCatching {
                val o = JSONObject(json)
                fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }.toSet()
                ScoreSettings(
                    scale = o.getInt("scale"),
                    semitones = o.optInt("semitones"),
                    parts = o.optJSONArray("parts")?.let(::strings).orEmpty(),
                    lyrics = runCatching { LyricsPlacement.valueOf(o.optString("lyrics")) }.getOrDefault(LyricsPlacement.FILE),
                    audible = o.optJSONArray("audible")?.let(::strings),
                    programs = o.optJSONObject("programs")?.let { p -> p.keys().asSequence().associateWith { p.getInt(it) } }.orEmpty(),
                    tempo = o.optDouble("tempo", 1.0)
                )
            }.getOrNull()
        }

        fun save(context: Context, file: String, settings: ScoreSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(file, settings.toJson()).apply()
        }
    }
}

/** A short list of General MIDI programs (1-based) to pick an instrument per part. */
val GM_PROGRAMS = listOf(
    1 to "Piano", 5 to "Electric piano", 7 to "Harpsichord", 20 to "Organ", 22 to "Accordion",
    25 to "Nylon guitar", 26 to "Steel guitar", 33 to "Acoustic bass", 41 to "Violin", 43 to "Cello",
    49 to "Strings", 53 to "Choir", 54 to "Voice", 57 to "Trumpet", 66 to "Alto sax", 72 to "Clarinet",
    74 to "Flute", 81 to "Synth lead"
)

fun programName(program: Int?): String =
    program?.let { p -> GM_PROGRAMS.firstOrNull { it.first == p }?.second ?: "GM $p" } ?: "default"
