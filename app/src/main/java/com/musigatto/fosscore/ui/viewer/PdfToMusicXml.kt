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
import com.musigatto.fosscore.pdf.MuPdfDoc
import com.musigatto.fosscore.pdfscore.CommonSymbols
import com.musigatto.fosscore.pdfscore.MuPdfSvg
import com.musigatto.fosscore.pdfscore.MusicFont
import com.musigatto.fosscore.pdfscore.MusicXmlWriter
import com.musigatto.fosscore.pdfscore.OutlineSymbols
import com.musigatto.fosscore.pdfscore.PageDrawing
import com.musigatto.fosscore.pdfscore.ScoreReader
import com.musigatto.fosscore.pdfscore.SmuflCodes
import com.musigatto.fosscore.pdfscore.SymbolClassifier
import com.musigatto.fosscore.pdfscore.bitmap.BitmapPage
import com.musigatto.fosscore.pdfscore.bitmap.GrayImage
import com.musigatto.fosscore.pdfscore.bitmap.OmrModel
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.score.MusicXmlPages
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
 * library's database gets it once it works). [key] names the PDF: its uri. A PDF in which no music
 * was found gets a mark instead, so that ▶ goes to the metronome at once the next time.
 */
internal object PdfCompanion {
    private fun dir(context: Context) = File(context.filesDir, "companions")

    private fun name(key: String) = key.hashCode().toUInt().toString(16) + "-" + key.length

    fun file(context: Context, key: String): File = File(dir(context), name(key) + ".musicxml")

    /** The mark of a PDF read without finding any music. */
    fun noMusic(context: Context, key: String): File = File(dir(context), name(key) + ".none")

    /** Whether the PDF was read already, with or without music found. */
    fun known(context: Context, key: String): Boolean = file(context, key).isFile || noMusic(context, key).isFile

    fun keep(context: Context, key: String, xml: String) {
        dir(context).mkdirs()
        noMusic(context, key).delete()
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
            MusicXmlPages.open(context, file).also { it.layout(LayoutSpec(PLAY_WIDTH, PLAY_HEIGHT)) }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "companion not opened: ${e.message}", e)
            null
        }
    }

    // the page the companion is laid out on to be played (no one sees it)
    private const val PLAY_WIDTH = 1200
    private const val PLAY_HEIGHT = 1700

    fun markNoMusic(context: Context, key: String) {
        dir(context).mkdirs()
        noMusic(context, key).writeText("")
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

/** Fewer things drawn than this: the page is a picture (a scan), read by its pixels. */
private const val SCAN_MAX_DRAWN = 20

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
    onProgress: (Conversion) -> Unit,
): Conversion {
    return try {
        val smufl = withContext(Dispatchers.IO) { SmuflIndex.get(context) }
        val start = SystemClock.elapsedRealtime()
        val pages = mutableListOf<PageDrawing>()
        var scanned = 0
        for (index in 0 until doc.pageCount) {
            onProgress(Conversion.Reading(index + 1, doc.pageCount))
            val t0 = SystemClock.elapsedRealtime()
            // MuPDF under the viewer's lock, a page at a time; the SVG read off it
            val svg = lock { withContext(Dispatchers.IO) { doc.pageSvg(index) } } as? ByteArray ?: continue
            val t1 = SystemClock.elapsedRealtime()
            val raw = withContext(Dispatchers.Default) { MuPdfSvg.read(svg, smufl) }
            val t2 = SystemClock.elapsedRealtime()
            val page = when {
                raw.glyphs.any { it.name != null } -> raw
                raw.glyphs.size + raw.lines.size + raw.shapes.size < SCAN_MAX_DRAWN -> {
                    // a picture: drawn in gray and read by its pixels
                    val model = withContext(Dispatchers.IO) { ScanModel.get() } ?: continue
                    val gray = lock { withContext(Dispatchers.IO) { doc.pageGray(index, SCAN_DPI) } } as? MuPdfDoc.GrayPage ?: continue
                    val t3 = SystemClock.elapsedRealtime()
                    scanned++
                    withContext(Dispatchers.Default) {
                        BitmapPage.read(GrayImage(gray.width, gray.height, gray.pixels), SCAN_DPI, model)
                    }.also {
                        Log.i(LOG_TAG, "page $index: scan ${gray.width}x${gray.height} drawn in ${t3 - t2} ms, read in ${SystemClock.elapsedRealtime() - t3} ms")
                    }
                }
                else -> {
                    // printed without its fonts: the symbols named by their look (the reference
                    // shapes are built the first time a page needs them)
                    val outlines = withContext(Dispatchers.IO) { Outlines.get(context) }
                    Log.i(LOG_TAG, "page $index: references ready in ${SystemClock.elapsedRealtime() - t2} ms")
                    withContext(Dispatchers.Default) { outlines.name(raw) }
                }
            }
            Log.i(LOG_TAG, "page $index: svg ${t1 - t0} ms (${svg.size} bytes), parsed ${t2 - t1} ms, named ${SystemClock.elapsedRealtime() - t2} ms")
            pages += page
        }
        val traced = SystemClock.elapsedRealtime()
        onProgress(Conversion.Recognising)
        val score = withContext(Dispatchers.Default) { ScoreReader.read(pages) }
        val read = SystemClock.elapsedRealtime()
        val notes = score.measures.sumOf { m -> m.staves.sumOf { s -> s.events.sumOf { (it.event as? Chord)?.pitches?.size ?: 0 } } }
        if (notes == 0) {
            // no music in it: ▶ goes to the metronome from now on
            withContext(Dispatchers.IO) { PdfCompanion.markNoMusic(context, key) }
            return Conversion.Failed(context.getString(R.string.pdf_to_musicxml_no_music))
        }
        val xml = withContext(Dispatchers.Default) { MusicXmlWriter.write(score, title) }
        val written = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) { PdfCompanion.keep(context, key, xml) }
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
