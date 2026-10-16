package org.fosscore.spike.musicxml

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Layout request: the viewport in pixels, a Verovio scale, a transposition in semitones, the
 * parts to show (empty = all) and where to put the lyrics.
 */
data class LayoutSpec(
    val width: Int,
    val height: Int,
    val scale: Int,
    val semitones: Int,
    val parts: Set<String> = emptySet(),
    val lyrics: LyricsPlacement = LyricsPlacement.FILE
)

/**
 * Playback request: the parts to hear (null = the shown parts), instruments that replace the
 * file's, and a tempo factor (1.0 = as written).
 */
data class SoundSpec(
    val audible: Set<String>? = null,
    val programs: Map<String, Int> = emptyMap(),
    val tempo: Double = 1.0
)

/** One onset of the shown score: [quarters] from the start, and the notes that start there. */
class Onset(val quarters: Double, val notes: List<String>)

/**
 * What playback needs: the MIDI file, a map from audio time to score position, and the onsets
 * of the shown score. Score positions in quarter notes link the two, so the sound may come from
 * another selection of parts than the one on screen.
 */
class Playback(val midi: File, private val audioMs: LongArray, private val audioQuarters: DoubleArray, val onsets: List<Onset>) {
    /** Score position in quarter notes at [ms] of audio. */
    fun quartersAt(ms: Long): Double {
        if (audioMs.isEmpty()) return 0.0
        val i = (audioMs.binarySearch(ms).let { if (it >= 0) it else -it - 2 }).coerceIn(0, audioMs.size - 1)
        if (i == audioMs.size - 1) return audioQuarters[i]
        val span = audioMs[i + 1] - audioMs[i]
        val f = if (span > 0) (ms - audioMs[i]).toDouble() / span else 0.0
        return audioQuarters[i] + f.coerceIn(0.0, 1.0) * (audioQuarters[i + 1] - audioQuarters[i])
    }

    /** Audio time of the score position [quarters]. */
    fun msAt(quarters: Double): Long {
        if (audioMs.isEmpty()) return 0
        val i = (audioQuarters.toList().binarySearch(quarters).let { if (it >= 0) it else -it - 2 })
            .coerceIn(0, audioMs.size - 1)
        return audioMs[i]
    }

    /** Index of the last onset at or before [quarters], or -1. */
    fun onsetIndexAt(quarters: Double): Int {
        var lo = 0
        var hi = onsets.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (onsets[mid].quarters <= quarters + 1e-6) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

data class Timings(
    val prepMs: Long = 0,
    val loadMs: Long = 0,
    val layoutMs: Long = 0,
    val svgMs: Long = 0,
    val rasterMs: Long = 0,
    val svgKb: Int = 0
) {
    override fun toString() =
        "parse ${prepMs}ms · load ${loadMs}ms · layout ${layoutMs}ms · svg ${svgMs}ms (${svgKb} KB) · raster ${rasterMs}ms"
}

/**
 * Owns the Verovio toolkits and runs every call on one dedicated thread (a toolkit is not
 * thread-safe). A second toolkit holds the parts to hear when they differ from the parts shown.
 */
class ScoreEngine(context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val cacheDir = context.cacheDir
    private val resourcePath = Verovio.prepareResources(context)
    private val glyphs = SmuflGlyphs(resourcePath)
    private var tk: Verovio? = null
    private var audioTk: Verovio? = null
    private var score: MusicXml? = null
    private var prepMs = 0L
    private var loaded: Any? = null
    private var audioLoaded: Any? = null
    private var laidOut: LayoutSpec? = null

    var pageCount = 0
        private set
    var lastTimings = Timings()
        private set

    /** The first tempo written in the file, in quarter notes per minute (Verovio's default is 120). */
    var baseTempo = 120.0
        private set

    suspend fun version(): String = run { toolkit().version }

    /** Parses the file; returns its parts. */
    suspend fun open(data: ByteArray): List<Part> = run {
        val t0 = System.nanoTime()
        score = MusicXml.parse(data)
        prepMs = ms(t0)
        baseTempo = score?.tempo ?: 120.0
        loaded = null
        audioLoaded = null
        laidOut = null
        pageCount = 0
        score?.parts.orEmpty()
    }

    /** Loads (if the transposition, parts or lyrics changed) and lays out the score for [spec]. */
    suspend fun layout(spec: LayoutSpec): Boolean = run {
        val doc = score ?: return@run false
        val t = toolkit()
        var loadMs = 0L
        var layoutMs = 0L
        var serializeMs = 0L
        t.setOptions(optionsFor(spec))
        val wanted = Triple(spec.semitones, spec.parts, spec.lyrics)
        if (loaded != wanted) {
            // Transposition, part selection and lyrics are applied while loading: reload the file.
            val t0 = System.nanoTime()
            val xml = doc.serialize(ScoreFilter(spec.parts, spec.lyrics))
            serializeMs = ms(t0)
            val t1 = System.nanoTime()
            val above = doc.lyricsAbove(spec.lyrics)
            if (MeiFixes.needed(above, doc.creditSizes)) {
                // Load once only to get the MEI (one endless system is the cheapest layout), fix
                // what the MusicXML import dropped, then load the MEI for real. The MEI is
                // already transposed, so it must not be transposed again.
                t.setOptions("""{"breaks": "none"}""")
                if (!t.loadXml(xml)) return@run false
                val mei = MeiFixes.apply(t.mei(), above, doc.creditSizes)
                t.setOptions(JSONObject(optionsFor(spec)).put("transpose", "").toString())
                if (!t.loadXml(mei)) return@run false
            } else if (!t.loadXml(xml)) {
                return@run false
            }
            loadMs = ms(t1)
            loaded = wanted
        } else if (laidOut != spec) {
            val t0 = System.nanoTime()
            // Only the page size or the scale changed: the measure widths Verovio cached at load
            // time are still valid, so it only has to break lines and pages again.
            t.redoLayout("""{"resetCache": false}""")
            layoutMs = ms(t0)
        }
        laidOut = spec
        pageCount = t.pageCount
        lastTimings = Timings(prepMs = prepMs + serializeMs, loadMs = loadMs, layoutMs = layoutMs)
        true
    }

    suspend fun svg(page: Int): String = run {
        val t0 = System.nanoTime()
        val svg = glyphs.inline(toolkit().renderSvg(page))
        lastTimings = lastTimings.copy(svgMs = ms(t0), svgKb = svg.length / 1024)
        svg
    }

    /** The page that shows the note [id], or 0. */
    suspend fun pageOf(id: String): Int = run { toolkit().pageWithElement(id) }

    fun recordRaster(ms: Long) {
        lastTimings = lastTimings.copy(rasterMs = ms)
    }

    /** Builds the MIDI file and the timing maps for the current layout and [sound]. */
    suspend fun playback(sound: SoundSpec): Playback = run {
        val display = toolkit()
        val spec = laidOut ?: error("not laid out")
        val tempo = JSONObject().put("midiTempoAdjustment", sound.tempo.coerceIn(0.2, 4.0)).toString()
        display.setOptions(tempo)
        val displayMap = JSONArray(display.renderTimemap())
        val separate = (sound.audible != null && sound.audible != spec.parts) || sound.programs.isNotEmpty()
        val audio = if (!separate) display else {
            val a = audioTk ?: Verovio(resourcePath).also { audioTk = it }
            val wanted = Triple(spec.semitones, sound.audible ?: spec.parts, sound.programs)
            if (audioLoaded != wanted) {
                // One endless system: this copy is never drawn, so make its layout as cheap as possible.
                a.setOptions(JSONObject().put("transpose", interval(spec.semitones)).put("breaks", "none").toString())
                val xml = score!!.serialize(ScoreFilter(sound.audible ?: spec.parts, programs = sound.programs))
                check(a.loadXml(xml)) { "could not load the parts to play" }
                audioLoaded = wanted
            }
            a.setOptions(tempo)
            a
        }
        val audioMap = if (audio === display) displayMap else JSONArray(audio.renderTimemap())
        val midi = Base64.decode(audio.renderMidi(), Base64.DEFAULT)
        val file = File(cacheDir, "score.mid").apply { writeBytes(midi) }
        val onsets = mutableListOf<Onset>()
        for (i in 0 until displayMap.length()) {
            val entry = displayMap.getJSONObject(i)
            val on = entry.optJSONArray("on") ?: continue
            if (on.length() == 0) continue
            onsets += Onset(entry.getDouble("qstamp"), List(on.length()) { on.getString(it) })
        }
        val ms = LongArray(audioMap.length()) { audioMap.getJSONObject(it).getDouble("tstamp").toLong() }
        val quarters = DoubleArray(audioMap.length()) { audioMap.getJSONObject(it).getDouble("qstamp") }
        Playback(file, ms, quarters, onsets)
    }

    /** Writes the score as a standard MIDI file and returns it. */
    suspend fun midiFile(): File = run {
        val midi = Base64.decode(toolkit().renderMidi(), Base64.DEFAULT)
        File(cacheDir, "score.mid").apply { writeBytes(midi) }
    }

    suspend fun timemapMs(): Long = run {
        val t0 = System.nanoTime()
        toolkit().renderTimemap()
        ms(t0)
    }

    suspend fun log(): String = run { toolkit().log }

    private fun toolkit(): Verovio = tk ?: Verovio(resourcePath).also { tk = it }

    private suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }

    companion object {
        // Verovio page units are 1/10 mm at scale 100; this maps one page to the viewport.
        fun optionsFor(spec: LayoutSpec): String = JSONObject().apply {
            put("pageWidth", (spec.width * 100 / spec.scale).coerceIn(100, 100_000))
            put("pageHeight", (spec.height * 100 / spec.scale).coerceIn(100, 60_000))
            put("scale", spec.scale)
            put("pageMarginLeft", 40)
            put("pageMarginRight", 40)
            put("pageMarginTop", 40)
            put("pageMarginBottom", 40)
            put("breaks", "auto")
            put("footer", "none")
            put("header", "auto")
            put("adjustPageHeight", false)
            put("svgViewBox", true)
            // No indentation: about half the size, so MuPDF parses it faster.
            put("svgFormatRaw", true)
            put("transpose", interval(spec.semitones))
            // Verovio's default (1.2) let words touch in narrow measures of real songs.
            put("lyricWordSpace", 2.5)
        }.toString()

        private val INTERVALS = listOf("P1", "m2", "M2", "m3", "M3", "P4", "A4", "P5", "m6", "M6", "m7", "M7", "P8")

        fun interval(semitones: Int): String {
            val name = INTERVALS[kotlin.math.abs(semitones).coerceAtMost(12)]
            return if (semitones < 0) "-$name" else name
        }

        private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    }
}
