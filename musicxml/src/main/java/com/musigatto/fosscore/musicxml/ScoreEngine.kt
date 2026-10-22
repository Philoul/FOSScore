package com.musigatto.fosscore.musicxml

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** A score could not be read or laid out. [message] includes Verovio's log when it has one. */
class MusicXmlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What an opened score offers: its parts and the details read from the file. */
data class ScoreSummary(val parts: List<Part>, val info: ScoreInfo)

/** One rendered page: the bitmap and where its notes are, for a playback cursor. */
class RenderedPage(val bitmap: Bitmap, val geometry: PageGeometry)

/**
 * Lays out, draws and plays one MusicXML score.
 *
 * Every Verovio call runs on one dedicated thread (a toolkit is not thread-safe); all functions
 * are safe to call from any coroutine. A second toolkit holds a clean copy of the score for the
 * sound when the score shown cannot play as it is. Call [close] when the score is closed: it
 * frees the native memory.
 *
 * Pages are drawn by MuPDF. If the app also uses MuPDF from other threads and needs its calls
 * serialized, pass the same lock as [rasterLock].
 */
class ScoreEngine(context: Context, private val rasterLock: Any = Any()) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fosscore-verovio") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val appContext = context.applicationContext

    // Set on the engine thread only.
    private var resourcePath: String? = null
    private var glyphs: SmuflGlyphs? = null
    private var display: Verovio? = null
    private var audio: Verovio? = null
    private var score: MusicXmlScore? = null
    private var loadedFor: Any? = null
    private var audioLoadedFor: Any? = null
    private var laidOut: LayoutSpec? = null
    // the music at the written tempo with every part, made once per loaded score: the tempo and
    // the parts heard are then rewritten in its MIDI (MidiEdit), without Verovio
    private var base: Playback? = null
    private var baseFor: Any? = null

    /** Verovio's version, for an "about" screen. */
    suspend fun version(): String = run { toolkit().version }

    /** Reads plain MusicXML or .mxl. Replaces the score opened before, if any. */
    suspend fun open(bytes: ByteArray): ScoreSummary = run {
        val parsed = try {
            MusicXmlScore.parse(bytes)
        } catch (e: Exception) {
            throw MusicXmlException("not a readable MusicXML file", e)
        }
        score = parsed
        loadedFor = null
        audioLoadedFor = null
        laidOut = null
        ScoreSummary(parsed.parts, parsed.info)
    }

    /**
     * Lays out the score for [spec] and returns the number of pages.
     *
     * Transposition, parts and lyrics are applied while loading: changing them reloads the score
     * (about a second on a large score on a slow tablet). Changing only the viewport or the scale
     * lays the loaded score out again, measuring the measures anew: widths kept from another
     * layout break the lines differently from a score opened at this size (going 90 % → 80 % →
     * 90 %, or turning the screen and back, put one more measure on a line).
     */
    suspend fun layout(spec: LayoutSpec): Int = run {
        val doc = score ?: throw MusicXmlException("no score open")
        val t = toolkit()
        val wanted = listOf(spec.semitones, spec.parts, spec.lyrics, spec.hiddenLyrics)
        val options = VerovioOptions.layout(spec)
        if (loadedFor != wanted) {
            loadedFor = null
            val xml = doc.prepare(ScoreFilter(spec.parts, spec.lyrics, hiddenLyrics = spec.hiddenLyrics))
            val above = doc.lyricsAbove(spec.lyrics)
            val credits = MeiFixes.forScreen(doc.creditSizes)
            if (MeiFixes.needed(above, credits)) {
                // Load once only to get the MEI (one endless system is the cheapest layout), fix
                // what the MusicXML import dropped, then load the MEI for real. The MEI is
                // already transposed, so it must not be transposed again.
                t.setOptions(VerovioOptions.unpaged(spec.semitones).toString())
                load(t, xml)
                val mei = MeiFixes.apply(t.mei(), above, credits)
                t.setOptions(options.put("transpose", "").toString())
                load(t, mei)
            } else {
                t.setOptions(options.toString())
                load(t, xml)
            }
            loadedFor = wanted
        } else if (laidOut != spec) {
            t.setOptions(options.toString())
            t.redoLayout("""{"resetCache": true}""")
        }
        laidOut = spec
        t.pageCount
    }

    /** Page [page] (1-based) of the current layout as Verovio's SVG, unchanged. */
    suspend fun pageSvg(page: Int): String = run { svgOf(page) }

    /**
     * Draws page [page] (1-based) of the current layout to fit [maxWidth] × [maxHeight] pixels,
     * on a white background.
     */
    suspend fun renderPage(page: Int, maxWidth: Int, maxHeight: Int): RenderedPage = run {
        val svg = svgOf(page)
        val ready = glyphs().inline(SvgForMuPdf.fix(svg))
        val bitmap = synchronized(rasterLock) { SvgRasterizer.render(ready, maxWidth, maxHeight) }
        RenderedPage(bitmap, PageGeometry.parse(svg))
    }

    /** The page (1-based) that shows the element [id] (a note id from [Playback.onsets]), or 0. */
    suspend fun pageOf(id: String): Int = run { toolkit().pageWithElement(id) }

    /**
     * MIDI and timing maps for the current layout and [sound]. Only the first call after a new
     * load runs Verovio (seconds on a slow tablet, see [preparePlayback]); then a new tempo or
     * new parts heard take milliseconds.
     */
    suspend fun playback(sound: SoundSpec): Playback {
        val (music, spec, doc) = run { Triple(basePlayback(sound.programs), laidOut!!, score!!) }
        val heard = (sound.audible ?: spec.parts).ifEmpty { doc.parts.map { it.id }.toSet() }
        // staff n plays on MIDI track n
        val silent = mutableSetOf<Int>()
        var staff = 0
        for ((id, staves) in doc.staffCounts) {
            repeat(staves) {
                staff++
                if (id !in heard) silent += staff
            }
        }
        return withContext(Dispatchers.Default) { music.with(sound.tempo.coerceIn(VerovioOptions.MIN_TEMPO, VerovioOptions.MAX_TEMPO), silent) }
    }

    /**
     * Makes the music of the current layout ahead of the first ▶, so that playing starts at
     * once. A failure is left for [playback] to report.
     */
    suspend fun preparePlayback() {
        run { runCatching { basePlayback(emptyMap()) } }
    }

    /**
     * Frees the toolkits and stops the engine thread. Pending calls finish first; calls made
     * after this one fail.
     */
    override fun close() {
        executor.execute {
            display?.close()
            audio?.close()
            display = null
            audio = null
            score = null
        }
        executor.shutdown()
    }

    // On the engine thread: the music at the written tempo, with every part.
    private fun basePlayback(programs: Map<String, Int>): Playback {
        val spec = laidOut ?: throw MusicXmlException("not laid out")
        val key = loadedFor to programs
        base?.takeIf { baseFor == key }?.let { return it }
        val shown = toolkit()
        val displayMap = shown.renderTimemap()
        // the score shown plays as it is when it holds every part, no voice of lyrics was added
        // for the display and the instruments are the file's; otherwise a clean copy plays
        val clean = spec.parts.isEmpty() && spec.lyrics != LyricsPlacement.BOTTOM && programs.isEmpty()
        val player = if (clean) shown else {
            val a = audio ?: Verovio(resources()).also { audio = it }
            val wanted = spec.semitones to programs
            if (audioLoadedFor != wanted) {
                audioLoadedFor = null
                // This copy is never drawn: make its layout as cheap as possible.
                a.setOptions(VerovioOptions.unpaged(spec.semitones).toString())
                load(a, score!!.prepare(ScoreFilter(programs = programs)))
                audioLoadedFor = wanted
            }
            a
        }
        val audioMap = if (player === shown) displayMap else player.renderTimemap()
        val midi = Base64.decode(player.renderMidi(), Base64.DEFAULT)
        return Playback.from(midi, displayMap, audioMap).also {
            base = it
            baseFor = key
        }
    }

    private fun load(t: Verovio, data: String) {
        if (!t.load(data)) throw MusicXmlException("Verovio could not load the score: ${t.log.trim()}")
    }

    private fun svgOf(page: Int): String {
        val t = toolkit()
        if (laidOut == null) throw MusicXmlException("not laid out")
        if (page !in 1..t.pageCount) throw IndexOutOfBoundsException("page $page of ${t.pageCount}")
        return t.renderSvg(page)
    }

    private fun resources(): String = resourcePath ?: Verovio.prepareResources(appContext).also { resourcePath = it }

    private fun glyphs(): SmuflGlyphs = glyphs ?: SmuflGlyphs(File(resources())).also { glyphs = it }

    private fun toolkit(): Verovio = display ?: Verovio(resources()).also { display = it }

    private suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }
}
