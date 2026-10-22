package com.musigatto.fosscore.score

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.musigatto.fosscore.musicxml.LayoutSpec
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.musicxml.Playback
import com.musigatto.fosscore.musicxml.ScoreEngine
import com.musigatto.fosscore.musicxml.ScoreInfo
import com.musigatto.fosscore.musicxml.SoundSpec
import com.musigatto.fosscore.pdf.MAX_RENDER_PIXELS
import com.musigatto.fosscore.pdf.PageBox
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking

private const val LOG_TAG = "FOSScore-MusicXML"

// bitmaps already drawn, keyed by (page, width in px): the same budget as MuPdfDoc
private const val MAX_CACHED_BITMAPS = 4

/**
 * A MusicXML score as pages for the viewer. Unlike a PDF, the pages do not exist before the score
 * is laid out for a screen: [layout] breaks the music into pages of the viewer's size, and again
 * whenever that size or the music size changes, so [pageCount] changes with it.
 *
 * The music is laid out and drawn by the musicxml module (Verovio, then MuPDF for the bitmap).
 */
class MusicXmlPages private constructor(
    private val engine: ScoreEngine,
    /** The score's parts, in the file's order. */
    val parts: List<Part>,
    /** Title, tempo, key... as the file gives them. */
    val info: ScoreInfo,
) : PageSource {

    @Volatile
    override var pageCount: Int = 0
        private set

    // every page of a layout has the viewport's shape
    @Volatile
    private var box: PageBox? = null
    private var laidOut: LayoutSpec? = null
    // where the notes are on each page drawn since the last layout, for the playback cursor
    private val geometries = ConcurrentHashMap<Int, PageGeometry>()
    private val bitmaps = object : LinkedHashMap<Long, Bitmap>(MAX_CACHED_BITMAPS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) =
            size > MAX_CACHED_BITMAPS
    }

    /**
     * Breaks the music into pages for [spec]. Returns false when nothing changed. Call it under
     * the viewer's render mutex, like [render].
     */
    suspend fun layout(spec: LayoutSpec): Boolean {
        if (spec == laidOut) return false
        val count = engine.layout(spec)
        // the old bitmaps show the old page breaks; the viewer may still be showing one, so they
        // are dropped, not recycled
        bitmaps.clear()
        geometries.clear()
        box = PageBox(0f, 0f, spec.width.toFloat(), spec.height.toFloat())
        laidOut = spec
        pageCount = count
        return true
    }

    override fun pageSize(index: Int): PageBox? = box?.takeIf { index in 0 until pageCount }

    override fun ensurePageSize(index: Int): PageBox? = pageSize(index)

    override fun render(index: Int, slotW: Int, slotH: Int): Bitmap? {
        if (index !in 0 until pageCount || slotW <= 0 || slotH <= 0) return null
        // same memory cap as a PDF page: past it a zoomed page would eat the RAM
        val k = sqrt(MAX_RENDER_PIXELS.toDouble() / (slotW.toLong() * slotH)).coerceAtMost(1.0)
        val w = (slotW * k).toInt().coerceAtLeast(1)
        val h = (slotH * k).toInt().coerceAtLeast(1)
        val key = index.toLong() * 1_000_000L + w
        bitmaps[key]?.let { return it }
        return try {
            // the engine runs Verovio on its own thread; the viewer already calls us off the main one
            runBlocking { engine.renderPage(index + 1, w, h) }
                .also { geometries[index] = it.geometry }
                .bitmap.also { bitmaps[key] = it }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "page $index could not be drawn: ${e.message}", e)
            null
        }
    }

    /** Where the notes of page [index] are, once the page has been drawn. Positions in SVG units. */
    fun geometry(index: Int): PageGeometry? = geometries[index]

    /** Page (0-based) of the note [id] in the current layout, or -1. */
    suspend fun pageOf(id: String): Int = engine.pageOf(id) - 1

    /** The MIDI and the timing maps for the current layout and [sound]. */
    suspend fun playback(sound: SoundSpec): Playback = engine.playback(sound)

    /** Makes the music ahead of the first ▶ (seconds on a slow tablet), so playing starts at once. */
    suspend fun preparePlayback() = engine.preparePlayback()

    override fun close() {
        bitmaps.clear()
        geometries.clear()
        engine.close()
    }

    companion object {
        /** Reads the score in [file]. Nothing is laid out yet: call [layout] once the viewport is known. */
        suspend fun open(context: Context, file: File): MusicXmlPages {
            val engine = ScoreEngine(context)
            val summary = try {
                engine.open(file.readBytes())
            } catch (e: Exception) {
                engine.close()
                throw e
            }
            return MusicXmlPages(engine, summary.parts, summary.info)
        }
    }
}
