package com.musigatto.fosscore.ui.viewer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.musigatto.fosscore.R
import com.musigatto.fosscore.musicxml.LayoutSpec
import com.musigatto.fosscore.ocr.GrayCrop
import com.musigatto.fosscore.ocr.OcrText
import com.musigatto.fosscore.ocr.TesseractReader
import com.musigatto.fosscore.ocr.TextReader
import com.musigatto.fosscore.pdf.MuPdfDoc
import com.musigatto.fosscore.pdfscore.CommonSymbols
import com.musigatto.fosscore.pdfscore.MuPdfSvg
import com.musigatto.fosscore.pdfscore.MusicFont
import com.musigatto.fosscore.pdfscore.MusicXmlWriter
import com.musigatto.fosscore.pdfscore.OutlineSymbols
import com.musigatto.fosscore.pdfscore.OutlineText
import com.musigatto.fosscore.pdfscore.PageDrawing
import com.musigatto.fosscore.pdfscore.ScoreReader
import com.musigatto.fosscore.pdfscore.SmuflCodes
import com.musigatto.fosscore.pdfscore.Staves
import com.musigatto.fosscore.pdfscore.SymbolClassifier
import com.musigatto.fosscore.pdfscore.bitmap.BitmapPage
import com.musigatto.fosscore.pdfscore.bitmap.GrayImage
import com.musigatto.fosscore.pdfscore.bitmap.OmrModel
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.score.MusicXmlPages
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Prototype: a PDF score read into MusicXML, kept beside the PDF as its companion ([PdfCompanion]):
 * the PDF stays the page shown, the companion is what ▶ plays. Each stage is timed, to try the
 * recognition (:pdfscore) on a device. The PDF's music glyphs are named by their SMuFL code points
 * (what MuseScore's PDFs carry), or, printed without fonts, by their look; a scanned page is drawn
 * in gray and read by its pixels.
 */
internal sealed interface Conversion {
    data class Reading(val page: Int, val pages: Int) : Conversion
    data object Recognising : Conversion
    data class Done(val report: ConversionReport) : Conversion
    data class Failed(val reason: String) : Conversion
}

internal data class ConversionReport(
    val pages: Int,
    /** The pages read as scans (by their pixels). */
    val scanned: Int,
    val measures: Int,
    val parts: Int,
    val notes: Int,
    val glyphs: Int,
    val named: Int,
    val traceMs: Long,
    val readMs: Long,
    val writeMs: Long,
)

/**
 * The MusicXML companion of a PDF score, kept as a file while the feature is a prototype (the
 * library's database gets it once it works). [key] names the PDF: its hash (the MD5 of its
 * content, which the library export keeps), or its uri outside the library. A PDF in which no music
 * was found gets a mark instead, so that ▶ goes to the metronome at once the next time.
 */
internal object PdfCompanion {
    /** The folder of the companions, inside `filesDir` (the library export carries it). */
    const val DIR = "companions"

    /** A companion's extension. */
    const val EXTENSION = "musicxml"

    private fun dir(context: Context) = File(context.filesDir, DIR)

    internal fun name(key: String) =
        if (HASH.matches(key)) key else key.hashCode().toUInt().toString(16) + "-" + key.length

    private val HASH = Regex("[0-9a-f]{32}")

    fun file(context: Context, key: String): File = File(dir(context), name(key) + "." + EXTENSION)

    /** A companion's places on the PDF ([CompanionPlaces]), beside it. */
    const val PLACES_EXTENSION = "places"

    private fun placesFile(context: Context, key: String): File = File(dir(context), name(key) + "." + PLACES_EXTENSION)

    /** Where the companion's notes are on the PDF; null when they were not kept. */
    fun places(context: Context, key: String): CompanionPlaces? =
        placesFile(context, key).takeIf { it.isFile }?.let { f -> runCatching { CompanionPlaces.read(f.readText()) }.getOrNull() }

    /**
     * Whether the PDF has a companion read by this reader ([READER_VERSION]): one read by an older
     * reader is read again (in the background), for what the newer one reads that it did not.
     */
    fun isCurrent(context: Context, key: String): Boolean = file(context, key).isFile &&
        runCatching { CompanionPlaces.readerOf(placesFile(context, key).readText()) }.getOrNull() == READER_VERSION

    /** The PDF left the library: its companion, or its mark, goes too. */
    fun forget(context: Context, key: String) {
        file(context, key).delete()
        placesFile(context, key).delete()
        noMusic(context, key).delete()
        File(context.cacheDir, "scores/" + file(context, key).name).deleteRecursively()
    }

    /** The mark of a PDF read without finding any music, by the reader of its version. */
    private fun noMusic(context: Context, key: String): File = File(dir(context), name(key) + ".none")

    /** Whether this reader ([READER_VERSION]) found no music in the PDF already. */
    fun hasNoMusic(context: Context, key: String): Boolean =
        runCatching { noMusic(context, key).readText().trim() == READER_VERSION.toString() }.getOrDefault(false)

    fun keep(context: Context, key: String, xml: String, places: String) {
        dir(context).mkdirs()
        noMusic(context, key).delete()
        // the places first: a companion found always has its own
        val placesPart = File(dir(context), name(key) + ".places.part")
        placesPart.writeText(places)
        if (!placesPart.renameTo(placesFile(context, key))) placesPart.delete()
        // written aside, then renamed: a companion is never seen half written
        val part = File(dir(context), name(key) + ".part")
        part.writeText(xml)
        // what MusicXmlPages kept of a former companion (its summary, its music) is of other notes
        File(context.cacheDir, "scores/" + file(context, key).name).deleteRecursively()
        if (!part.renameTo(file(context, key))) {
            part.delete()
            throw java.io.IOException("companion not kept")
        }
    }

    /**
     * The companion of [key] opened to be played, never shown: laid out once on a page of its own
     * (playback needs a layout), or null when there is none or it cannot be read.
     */
    suspend fun open(context: Context, key: String): MusicXmlPages? {
        val file = file(context, key).takeIf { it.isFile } ?: return null
        return try {
            // never drawn: its music is made at once, not after a first page that never comes
            MusicXmlPages.open(context, file, soundFirst = true).also {
                // played from what its preparation kept, without the seconds of a layout
                val spec = LayoutSpec(PLAY_WIDTH, PLAY_HEIGHT)
                it.playWithoutLayout(spec)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "companion not opened: ${e.message}", e)
            null
        }
    }

    /**
     * Makes and keeps what the first ▶ on the PDF needs (the companion's summary, layout and
     * music), right after it is read: the first ▶ then plays at once (it took 13 s on the Tab 3).
     */
    suspend fun prepare(context: Context, key: String) {
        val file = file(context, key).takeIf { it.isFile } ?: return
        try {
            MusicXmlPages.preparePlay(context, file, LayoutSpec(PLAY_WIDTH, PLAY_HEIGHT))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "companion not prepared: ${e.message}")
        }
    }

    // the page the companion is laid out on to be played (no one sees it)
    private const val PLAY_WIDTH = 1200
    private const val PLAY_HEIGHT = 1700

    fun markNoMusic(context: Context, key: String) {
        dir(context).mkdirs()
        noMusic(context, key).writeText(READER_VERSION.toString())
    }
}

/** SMuFL's code points and names, from the Bravura index Verovio's data ships in the assets. */
private object SmuflIndex {
    @Volatile
    private var codes: Map<Int, String>? = null

    fun get(context: Context): Map<Int, String> = codes ?: context.assets.open("verovio/data/Bravura.xml")
        .use { SmuflCodes.read(it.readBytes().toString(Charsets.UTF_8)) }
        .also { codes = it }
}

/** The reader of the text a page draws as outlines or pixels (Tesseract, in :ocr), started the first time it is needed. */
private object Ocr {
    @Volatile
    private var reader: TextReader? = null

    @Synchronized
    fun get(context: Context): TextReader? = reader ?: TesseractReader.open(context)?.also { reader = it }
}

/** The resolution the text of a page is drawn at to be read. */
private const val OCR_DPI = 300.0

/** Around a piece of text, the white kept when it is drawn to be read, in its heights. */
private const val OCR_MARGIN = 0.3

/**
 * [page] (page [index] of [doc], printed without its fonts) with what the music needs of its text
 * drawn as outlines, read from its pixels ([OutlineText]): the number of each metronome mark, the
 * numbers of the endings, the words over the systems ("To Coda", "D.S. al Coda", "Rall.").
 */
private suspend fun readOutlineText(
    context: Context,
    doc: MuPdfDoc,
    index: Int,
    page: PageDrawing,
    lock: suspend (suspend () -> Any?) -> Any?,
    work: () -> CoroutineDispatcher,
): PageDrawing {
    val spots = OutlineText.spots(page)
    if (spots.isEmpty()) return page
    val reader = withContext(Dispatchers.IO) { Ocr.get(context) } ?: return page
    val read = spots.mapNotNull { spot ->
        val box = spot.box
        val margin = OCR_MARGIN * box.height
        val crop = lock {
            withContext(work()) { doc.cropGray(index, OCR_DPI, box.left - margin, box.top - margin, box.right + margin, box.bottom + margin) }
        } as? MuPdfDoc.GrayPage ?: return@mapNotNull null
        val text = withContext(work()) { reader.readLine(GrayCrop(crop.width, crop.height, crop.pixels), OCR_DPI.toInt()) }
        // what the music needs of it, written as a PDF with fonts would
        val meant = text?.let { read ->
            when (spot.kind) {
                OutlineText.Kind.METRONOME -> OcrText.perMinute(read)?.let { "= $it" }
                OutlineText.Kind.ENDING -> OcrText.endingNumbers(read)
                OutlineText.Kind.WORDS -> read
            }
        }
        Log.i(LOG_TAG, "page $index: ${spot.kind} read \"$text\" -> $meant")
        meant?.let { box to it }
    }.toMap()
    return OutlineText.withTexts(page, read)
}

/** The model that names a scan's symbols, shipped in :pdfscore's resources. */
private object ScanModel {
    @Volatile
    private var model: OmrModel? = null

    fun get(): OmrModel? = model ?: OmrModel.load()?.also { model = it }
}

/**
 * Names the symbols of a PDF printed without its fonts by their look, compared with the music fonts
 * Verovio's data ships in the assets (Bravura, Leipzig): only the symbols the recognition knows.
 */
private object Outlines {
    private val FONTS = listOf("Bravura", "Leipzig")

    @Volatile
    private var outlines: OutlineSymbols? = null

    fun get(context: Context): OutlineSymbols = outlines ?: OutlineSymbols(cached(context) ?: built(context)).also { outlines = it }

    /** The references kept by a former build: named after the fonts and the symbols they cover, so a change builds them again. */
    private fun file(context: Context) =
        File(context.cacheDir, "pdfscore-references-${(FONTS + CommonSymbols.NAMES).hashCode().toUInt().toString(16)}.bin")

    private fun cached(context: Context): SymbolClassifier? = file(context).takeIf { it.isFile }?.let { f ->
        runCatching { java.io.DataInputStream(f.inputStream().buffered()).use { SymbolClassifier.load(it) } }.getOrNull()
    }

    /** Built from the fonts' glyph files (several seconds on a slow tablet), then kept for the next time. */
    private fun built(context: Context): SymbolClassifier = SymbolClassifier(
        FONTS.mapNotNull { font ->
            MusicFont.readVerovio(font, CommonSymbols.NAMES.toSet()) { path ->
                runCatching { context.assets.open("verovio/data/$path").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
        }.flatMap { it.symbols },
    ).also { classifier ->
        runCatching { java.io.DataOutputStream(file(context).outputStream().buffered()).use { classifier.save(it) } }
            .onFailure { Log.w(LOG_TAG, "references not kept: ${it.message}") }
    }
}

/** The resolution a scanned page is drawn at to be read (the scans the reader is made for). */
private const val SCAN_DPI = 300.0

// an A4 page at [SCAN_DPI], in pixels
private const val SCAN_MAX_WIDTH = 2480.0

/** The resolution a page [width] points wide is drawn at to be read as a scan. */
internal fun scanDpi(width: Double): Double = if (width > 0) minOf(SCAN_DPI, SCAN_MAX_WIDTH * 72 / width) else SCAN_DPI

/**
 * Whether a page is a picture of music (a scan), read by its pixels: it holds an image, and no
 * staff is drawn by its lines (a scan may carry a few: a frame, a text layer, a stamp).
 */
internal fun isScan(svg: ByteArray, page: PageDrawing): Boolean =
    containsImage(svg) && Staves.find(page.withLines(page.lines.filter { it.width <= MAX_STAFF_LINE })).isEmpty()

private fun PageDrawing.withLines(lines: List<com.musigatto.fosscore.pdfscore.DrawnLine>) =
    PageDrawing(width, height, glyphs, lines, shapes, texts)

/**
 * The thickest a staff line is drawn, in points (a staff space is 5 to 9): a scan stored as strips
 * of picture may lay a band of paper under each, evenly spaced like staff lines, far thicker.
 */
private const val MAX_STAFF_LINE = 2.0

private val IMAGE_TAG = "<image".toByteArray(Charsets.US_ASCII)

private fun containsImage(svg: ByteArray): Boolean {
    val first = IMAGE_TAG[0]
    for (i in 0..svg.size - IMAGE_TAG.size) {
        if (svg[i] != first) continue
        if (IMAGE_TAG.indices.all { svg[i + it] == IMAGE_TAG[it] }) return true
    }
    return false
}

/**
 * The reader's version: a PDF in which an older reader found no music is read again by a newer
 * one, and a companion an older reader wrote is read again. Bumped when the reader learns to read
 * pages it could not, or more of them (3: the tempo of PDFs without fonts, by OCR; 4: voices that
 * keep their measure's length, rests and beam hooks told apart; 5: companions prepared to play at once; 6: played in the order of their repeats and jumps; 7: heads with two stems, implied triplets; 8: endings and words read by OCR; 9: clefs of scans; 10: staves drawn in pieces, endings over lyrics; 11: no false segno).
 */
internal const val READER_VERSION = 16

/**
 * Reads [doc] page by page under the viewer's lock ([lock]), so the pages keep being drawn between
 * two (MuPDF writes each as SVG, [MuPdfSvg] reads it; a scanned page is drawn in gray and read by
 * [BitmapPage]), then recognises the score, writes it as MusicXML and keeps it as the PDF's
 * companion under [key] ([PdfCompanion]). [onProgress] follows the stages.
 */
internal suspend fun convertPdfToMusicXml(
    context: Context,
    doc: MuPdfDoc,
    title: String,
    key: String,
    lock: suspend (suspend () -> Any?) -> Any?,
    /** Called before each page: the reading in the background waits there for the user to leave the tablet. */
    pause: suspend () -> Unit = {},
    /** Where the reading works: anywhere for ▶, a thread at a low priority in the background. */
    work: () -> CoroutineDispatcher = { Dispatchers.Default },
    onProgress: (Conversion) -> Unit,
): Conversion {
    return try {
        val smufl = withContext(Dispatchers.IO) { SmuflIndex.get(context) }
        val start = SystemClock.elapsedRealtime()
        val pages = mutableListOf<PageDrawing>()
        // the PDF page each of [pages] is (a page that cannot be read is left out)
        val pdfPages = mutableListOf<Int>()
        var scanned = 0
        for (index in 0 until doc.pageCount) {
            pause()
            onProgress(Conversion.Reading(index + 1, doc.pageCount))
            val t0 = SystemClock.elapsedRealtime()
            // MuPDF under the viewer's lock, a page at a time; the SVG read off it
            val svg = lock { withContext(work()) { doc.pageSvg(index) } } as? ByteArray ?: continue
            val t1 = SystemClock.elapsedRealtime()
            val raw = withContext(work()) { MuPdfSvg.read(svg, smufl) }
            val t2 = SystemClock.elapsedRealtime()
            val page = when {
                raw.glyphs.any { it.name != null } -> raw
                isScan(svg, raw) -> {
                    // a picture: drawn in gray and read by its pixels
                    val model = withContext(Dispatchers.IO) { ScanModel.get() } ?: continue
                    // a page larger than A4 (a scan saved on A3) is drawn as wide as an A4 one: the staves
                    // come out the size the reader is made for, in half the pixels
                    val dpi = scanDpi(raw.width)
                    val gray = lock { withContext(work()) { doc.pageGray(index, dpi) } } as? MuPdfDoc.GrayPage ?: continue
                    val t3 = SystemClock.elapsedRealtime()
                    scanned++
                    withContext(work()) {
                        BitmapPage.read(GrayImage(gray.width, gray.height, gray.pixels), dpi, model)
                    }.also {
                        Log.i(LOG_TAG, "page $index: scan ${gray.width}x${gray.height} drawn in ${t3 - t2} ms, read in ${SystemClock.elapsedRealtime() - t3} ms")
                    }
                }
                else -> {
                    // printed without its fonts: the symbols named by their look (the reference
                    // shapes are built the first time a page needs them)
                    val outlines = withContext(Dispatchers.IO) { Outlines.get(context) }
                    Log.i(LOG_TAG, "page $index: references ready in ${SystemClock.elapsedRealtime() - t2} ms")
                    val named = withContext(work()) { outlines.name(raw) }
                    // its text drawn as outlines too: what the music needs of it is read from the pixels
                    readOutlineText(context, doc, index, named, lock, work)
                }
            }
            Log.i(LOG_TAG, "page $index: svg ${t1 - t0} ms (${svg.size} bytes), parsed ${t2 - t1} ms, named ${SystemClock.elapsedRealtime() - t2} ms")
            pages += page
            pdfPages += index
        }
        val traced = SystemClock.elapsedRealtime()
        onProgress(Conversion.Recognising)
        val score = withContext(work()) { ScoreReader.read(pages) }
        val read = SystemClock.elapsedRealtime()
        val notes = score.measures.sumOf { m -> m.staves.sumOf { s -> s.events.sumOf { (it.event as? Chord)?.pitches?.size ?: 0 } } }
        if (notes == 0) {
            // no music in it: ▶ goes to the metronome from now on
            withContext(Dispatchers.IO) { PdfCompanion.markNoMusic(context, key) }
            return Conversion.Failed(context.getString(R.string.pdf_to_musicxml_no_music))
        }
        val xml = withContext(work()) { MusicXmlWriter.write(score, title) }
        val written = SystemClock.elapsedRealtime()
        // where each note is on the PDF, for the playback bar and the page turns
        val places = withContext(work()) {
            CompanionPlaces.write(
                pages.indices.associate { pdfPages[it] to PageSize(pages[it].width, pages[it].height) },
                score.followPlaces().mapValues { (_, place) -> place.copy(page = pdfPages[place.page]) },
                READER_VERSION,
            )
        }
        withContext(Dispatchers.IO) { PdfCompanion.keep(context, key, xml, places) }
        val glyphs = pages.flatMap { it.glyphs }
        val report = ConversionReport(
            pages = pages.size,
            scanned = scanned,
            measures = score.measures.size,
            parts = score.parts.size,
            notes = notes,
            glyphs = glyphs.size,
            named = glyphs.count { it.name != null },
            traceMs = traced - start,
            readMs = read - traced,
            writeMs = written - read,
        )
        Log.i(LOG_TAG, "converted: $report")
        Conversion.Done(report)
    } catch (e: Exception) {
        Log.e(LOG_TAG, "conversion failed: ${e.message}", e)
        Conversion.Failed(e.message ?: e.javaClass.simpleName)
    } catch (e: OutOfMemoryError) {
        // a scan drawn at 300 dpi is the biggest thing read: say so rather than close the app
        Log.e(LOG_TAG, "conversion out of memory", e)
        Conversion.Failed(e.javaClass.simpleName)
    }
}

private const val LOG_TAG = "FOSScore-PdfScore"

private fun seconds(ms: Long): String = String.format(Locale.getDefault(), "%.1f", ms / 1000.0)

/** Where the conversion is, then what it read and how long each stage took. */
@Composable
internal fun ConversionDialog(state: Conversion, onDismiss: () -> Unit) {
    val running = state is Conversion.Reading || state is Conversion.Recognising
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.pdf_to_musicxml_title)) },
        text = {
            Text(
                when (state) {
                    is Conversion.Reading -> stringResource(R.string.pdf_to_musicxml_reading, state.page, state.pages)
                    Conversion.Recognising -> stringResource(R.string.pdf_to_musicxml_recognising)
                    is Conversion.Failed -> stringResource(R.string.pdf_to_musicxml_failed, state.reason)
                    is Conversion.Done -> with(state.report) {
                        stringResource(
                            R.string.pdf_to_musicxml_report,
                            pages, measures, parts, notes, named, glyphs,
                            seconds(traceMs), seconds(readMs), seconds(writeMs), seconds(traceMs + readMs + writeMs),
                        ) + (if (scanned > 0) "\n" + stringResource(R.string.pdf_to_musicxml_scanned, scanned) else "") +
                            "\n\n" + stringResource(R.string.pdf_to_musicxml_kept)
                    }
                },
            )
        },
        confirmButton = {
            if (!running) TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_to_musicxml_close)) }
        },
    )
}

/** What a PDF's companion is attached to playback with: it is laid out once, always the same way. */
internal const val COMPANION_LOADED = "companion"
