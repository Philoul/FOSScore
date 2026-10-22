package com.musigatto.fosscore.musicxml

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * A toolkit is not thread-safe, but Verovio keeps its state per toolkit and per thread: the pages
 * are laid out and drawn by one toolkit on one thread, while a second toolkit on another thread
 * makes the music, once, as soon as the score is open. All functions are safe to call from any
 * coroutine. Call [close] when the score is closed: it frees the native memory.
 *
 * Pages are drawn by MuPDF. If the app also uses MuPDF from other threads and needs its calls
 * serialized, pass the same lock as [rasterLock].
 */
class ScoreEngine(context: Context, private val rasterLock: Any = Any()) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fosscore-verovio") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val soundExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fosscore-verovio-sound") }
    private val soundDispatcher = soundExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())
    private val appContext = context.applicationContext
    // calls queued or running on the engine thread: the cursor's onsets wait until it is free
    private val busy = AtomicInteger()

    // Set on the engine thread only.
    private var glyphs: SmuflGlyphs? = null
    private var display: Verovio? = null
    private var score: MusicXmlScore? = null
    private var loadedFor: Any? = null
    private var laidOut: LayoutSpec? = null
    // when notes start in the score shown, and which: the cursor and the page turns
    private var onsets: List<Onset>? = null
    private var onsetsFor: Any? = null
    // the MIDI of every part at the written tempo, not transposed: the tempo, the transposition
    // and the parts heard are then rewritten in it (MidiEdit), without Verovio
    private var music: Deferred<ByteArray>? = null

    private var resourcePath: String? = null

    /** Verovio's version, for an "about" screen. */
    suspend fun version(): String = run { toolkit().version }

    /**
     * Reads plain MusicXML or .mxl. Replaces the score opened before, if any. The music is made
     * at once on the sound thread, or read from [musicCache] when it was made before (making it
     * takes seconds on a slow tablet).
     */
    suspend fun open(bytes: ByteArray, musicCache: File? = null): ScoreSummary = run {
        val parsed = try {
            MusicXmlScore.parse(bytes)
        } catch (e: Exception) {
            throw MusicXmlException("not a readable MusicXML file", e)
        }
        score = parsed
        loadedFor = null
        laidOut = null
        onsets = null
        music?.cancel()
        music = scope.async(soundDispatcher) { musicOf(parsed, musicCache) }
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
            val xml = synchronized(doc) {
                doc.prepare(ScoreFilter(spec.parts, spec.lyrics, hiddenLyrics = spec.hiddenLyrics))
            }
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
     * MIDI and timing maps for the current layout and [sound]. Once the music is made (see
     * [open]) and the onsets known (see [preparePlayback]), a new tempo or new parts heard take
     * milliseconds.
     */
    suspend fun playback(sound: SoundSpec): Playback {
        val made = music ?: throw MusicXmlException("no score open")
        val (notes, spec, doc) = run {
            val spec = laidOut ?: throw MusicXmlException("not laid out")
            Triple(onsets(), spec, score!!)
        }
        val midi = made.await()
        val heard = (sound.audible ?: spec.parts).ifEmpty { doc.parts.map { it.id }.toSet() }
        // staff n plays on MIDI track n
        val silent = mutableSetOf<Int>()
        val programs = mutableMapOf<Int, Int>()
        var staff = 0
        for ((id, staves) in doc.staffCounts) {
            repeat(staves) {
                staff++
                if (id !in heard) silent += staff
                sound.programs[id]?.let { programs[staff] = it }
            }
        }
        val tempo = sound.tempo.coerceIn(VerovioOptions.MIN_TEMPO, VerovioOptions.MAX_TEMPO)
        return withContext(Dispatchers.Default) {
            Playback.of(MidiEdit.edit(midi, tempo, silent, spec.semitones, programs), notes)
        }
    }

    /**
     * Gets playback ready ahead of the first ▶: waits for the music, then finds the onsets of the
     * score shown once the pages are drawn (they take the engine thread for a second or so on a
     * slow tablet). A failure is left for [playback] to report.
     */
    suspend fun preparePlayback() {
        runCatching { music?.await() }
        while (busy.get() > 0) delay(IDLE_POLL_MS)
        run { runCatching { onsets() } }
    }

    /**
     * Frees the toolkits and stops the engine threads. Pending calls finish first; calls made
     * after this one fail.
     */
    override fun close() {
        scope.cancel()
        executor.execute {
            display?.close()
            display = null
            score = null
        }
        executor.shutdown()
        soundExecutor.shutdown()
    }

    // On the sound thread: the MIDI of every part at the written tempo, from [cache] when it was
    // made before, otherwise from a clean copy of the score, laid out as cheaply as possible.
    private fun musicOf(doc: MusicXmlScore, cache: File?): ByteArray {
        cache?.takeIf { it.isFile }?.let { file -> runCatching { return file.readBytes() } }
        val t = Verovio(resources())
        try {
            t.setOptions(VerovioOptions.unpaged(0).toString())
            load(t, synchronized(doc) { doc.prepare() })
            val midi = Base64.decode(t.renderMidi(), Base64.DEFAULT)
            cache?.let { runCatching { it.parentFile?.mkdirs(); it.writeBytes(midi) } }
            return midi
        } finally {
            t.close()
        }
    }

    // On the engine thread: the onsets of the score shown.
    private fun onsets(): List<Onset> {
        onsets?.takeIf { onsetsFor == loadedFor }?.let { return it }
        if (laidOut == null) throw MusicXmlException("not laid out")
        return Playback.onsetsOf(toolkit().renderTimemap()).also {
            onsets = it
            onsetsFor = loadedFor
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

    // both threads need it
    @Synchronized
    private fun resources(): String = resourcePath ?: Verovio.prepareResources(appContext).also { resourcePath = it }

    private fun glyphs(): SmuflGlyphs = glyphs ?: SmuflGlyphs(File(resources())).also { glyphs = it }

    private fun toolkit(): Verovio = display ?: Verovio(resources()).also { display = it }

    private suspend fun <T> run(block: () -> T): T {
        busy.incrementAndGet()
        try {
            return withContext(dispatcher) { block() }
        } finally {
            busy.decrementAndGet()
        }
    }

    private companion object {
        // how often [preparePlayback] looks whether the pages are drawn
        const val IDLE_POLL_MS = 300L
    }
}
