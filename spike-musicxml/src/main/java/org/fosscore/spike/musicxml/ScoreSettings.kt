package org.fosscore.spike.musicxml

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything the musician sets for one score; saved with the file and restored when it is opened again. */
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
    fun toJson(): JSONObject = JSONObject().apply {
        put("scale", scale)
        put("semitones", semitones)
        put("parts", JSONArray(parts.toList()))
        put("lyrics", lyrics.name)
        audible?.let { put("audible", JSONArray(it.toList())) }
        put("programs", JSONObject(programs.mapValues { it.value }))
        put("tempo", tempo)
    }

    companion object {
        fun fromJson(o: JSONObject): ScoreSettings {
            fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }.toSet()
            return ScoreSettings(
                scale = o.getInt("scale"),
                semitones = o.optInt("semitones"),
                parts = o.optJSONArray("parts")?.let(::strings).orEmpty(),
                lyrics = runCatching { LyricsPlacement.valueOf(o.optString("lyrics")) }.getOrDefault(LyricsPlacement.FILE),
                audible = o.optJSONArray("audible")?.let(::strings),
                programs = o.optJSONObject("programs")?.let { p -> p.keys().asSequence().associateWith { p.getInt(it) } }.orEmpty(),
                tempo = o.optDouble("tempo", 1.0)
            )
        }
    }
}

/**
 * The named configurations of one score (for example one to practise alone and one to rehearse
 * with the band), and which one is in use. Saved per file.
 */
data class ScoreConfigs(val current: String, val configs: Map<String, ScoreSettings>) {
    val settings: ScoreSettings get() = configs.getValue(current)

    fun with(settings: ScoreSettings) = copy(configs = configs + (current to settings))

    fun select(name: String) = if (name in configs) copy(current = name) else this

    /** A copy of the current configuration under a new name, which becomes current. */
    fun duplicate(): ScoreConfigs {
        val name = generateSequence(configs.size + 1) { it + 1 }.map { "Config $it" }.first { it !in configs }
        return ScoreConfigs(name, configs + (name to settings))
    }

    fun rename(name: String): ScoreConfigs {
        val clean = name.trim()
        if (clean.isEmpty() || clean in configs) return this
        // Keep the order of the menu.
        return ScoreConfigs(clean, configs.entries.associate { (k, v) -> (if (k == current) clean else k) to v })
    }

    fun delete(): ScoreConfigs {
        if (configs.size < 2) return this
        val rest = configs - current
        return ScoreConfigs(rest.keys.first(), rest)
    }

    companion object {
        private const val PREFS = "score_settings"
        const val DEFAULT = "Default"

        fun new(settings: ScoreSettings) = ScoreConfigs(DEFAULT, mapOf(DEFAULT to settings))

        fun load(context: Context, file: String): ScoreConfigs? {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(file, null) ?: return null
            return runCatching {
                val o = JSONObject(json)
                // First spike builds saved a single configuration.
                if (o.has("scale")) return@runCatching new(ScoreSettings.fromJson(o))
                val list = o.getJSONObject("configs")
                val configs = list.keys().asSequence().associateWith { ScoreSettings.fromJson(list.getJSONObject(it)) }
                ScoreConfigs(o.getString("current"), configs).takeIf { it.current in configs }
            }.getOrNull()
        }

        fun save(context: Context, file: String, configs: ScoreConfigs) {
            val o = JSONObject().put("current", configs.current)
                .put("configs", JSONObject().apply { configs.configs.forEach { (k, v) -> put(k, v.toJson()) } })
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(file, o.toString()).apply()
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
