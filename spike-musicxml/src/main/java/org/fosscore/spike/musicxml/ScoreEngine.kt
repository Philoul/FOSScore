package org.fosscore.spike.musicxml

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Layout request: the viewport in pixels, a Verovio scale, a transposition in semitones and the
 * parts to show (empty = all).
 */
data class LayoutSpec(
    val width: Int,
    val height: Int,
    val scale: Int,
    val semitones: Int,
    val parts: Set<String> = emptySet()
)

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
 * Owns the Verovio toolkit and runs every call on one dedicated thread (the toolkit is not
 * thread-safe).
 */
class ScoreEngine(context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val cacheDir = context.cacheDir
    private val resourcePath = Verovio.prepareResources(context)
    private var tk: Verovio? = null
    private var score: MusicXml? = null
    private var prepMs = 0L
    private var loaded: Pair<Int, Set<String>>? = null
    private var laidOut: LayoutSpec? = null

    var pageCount = 0
        private set
    var lastTimings = Timings()
        private set

    suspend fun version(): String = run { toolkit().version }

    /** Parses the file; returns its parts. */
    suspend fun open(data: ByteArray): List<Part> = run {
        val t0 = System.nanoTime()
        score = MusicXml.parse(data)
        prepMs = ms(t0)
        loaded = null
        laidOut = null
        pageCount = 0
        score?.parts.orEmpty()
    }

    /** Loads (if the transposition changed) and lays out the score for [spec]. */
    suspend fun layout(spec: LayoutSpec): Boolean = run {
        val doc = score ?: return@run false
        val t = toolkit()
        var loadMs = 0L
        var layoutMs = 0L
        var serializeMs = 0L
        t.setOptions(optionsFor(spec))
        val wanted = spec.semitones to spec.parts
        if (loaded != wanted) {
            // Transposition and part selection are applied while loading: reload the file.
            val t0 = System.nanoTime()
            val xml = doc.serialize(spec.parts)
            serializeMs = ms(t0)
            val t1 = System.nanoTime()
            if (!t.loadXml(xml)) return@run false
            loadMs = ms(t1)
            loaded = wanted
        } else if (laidOut != spec) {
            val t0 = System.nanoTime()
            t.redoLayout()
            layoutMs = ms(t0)
        }
        laidOut = spec
        pageCount = t.pageCount
        lastTimings = Timings(prepMs = prepMs + serializeMs, loadMs = loadMs, layoutMs = layoutMs)
        true
    }

    suspend fun svg(page: Int): String = run {
        val t0 = System.nanoTime()
        val svg = toolkit().renderSvg(page)
        lastTimings = lastTimings.copy(svgMs = ms(t0), svgKb = svg.length / 1024)
        svg
    }

    fun recordRaster(ms: Long) {
        lastTimings = lastTimings.copy(rasterMs = ms)
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
            put("transpose", interval(spec.semitones))
        }.toString()

        private val INTERVALS = listOf("P1", "m2", "M2", "m3", "M3", "P4", "A4", "P5", "m6", "M6", "m7", "M7", "P8")

        fun interval(semitones: Int): String {
            val name = INTERVALS[kotlin.math.abs(semitones).coerceAtMost(12)]
            return if (semitones < 0) "-$name" else name
        }

        private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    }
}
