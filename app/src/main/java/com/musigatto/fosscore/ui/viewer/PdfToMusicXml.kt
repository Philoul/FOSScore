package com.musigatto.fosscore.ui.viewer

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.ImportResult
import com.musigatto.fosscore.library.LibraryRepository
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
import com.musigatto.fosscore.pdfscore.music.Chord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Prototype: a vector PDF score read into MusicXML and added to the library, each stage timed, to
 * try the recognition (:pdfscore) on a device. The PDF's music glyphs are named by their SMuFL code
 * points (what MuseScore's PDFs carry), or, printed without fonts, by their look; a scan reads no
 * music yet.
 */
internal sealed interface Conversion {
    data class Reading(val page: Int, val pages: Int) : Conversion
    data object Recognising : Conversion
    data class Done(val report: ConversionReport) : Conversion
    data class Failed(val reason: String) : Conversion
}

internal data class ConversionReport(
    val pages: Int,
    val measures: Int,
    val parts: Int,
    val notes: Int,
    val glyphs: Int,
    val named: Int,
    val traceMs: Long,
    val readMs: Long,
    val writeMs: Long,
    /** The title the new score got in the library, or null when it was there already. */
    val addedAs: String?,
)

/** SMuFL's code points and names, from the Bravura index Verovio's data ships in the assets. */
private object SmuflIndex {
    @Volatile
    private var codes: Map<Int, String>? = null

    fun get(context: Context): Map<Int, String> = codes ?: context.assets.open("verovio/data/Bravura.xml")
        .use { SmuflCodes.read(it.readBytes().toString(Charsets.UTF_8)) }
        .also { codes = it }
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

/**
 * Reads [doc] page by page under the viewer's lock ([lock]), so the pages keep being drawn between
 * two (MuPDF writes each as SVG, [MuPdfSvg] reads it), then recognises the score, writes it as
 * MusicXML and imports it into the library as
 * "[title] (MusicXML)". [onProgress] follows the stages.
 */
internal suspend fun convertPdfToMusicXml(
    context: Context,
    doc: MuPdfDoc,
    title: String,
    repository: LibraryRepository,
    lock: suspend (suspend () -> ByteArray?) -> ByteArray?,
    onProgress: (Conversion) -> Unit,
): Conversion {
    return try {
        val smufl = withContext(Dispatchers.IO) { SmuflIndex.get(context) }
        val start = SystemClock.elapsedRealtime()
        val pages = mutableListOf<PageDrawing>()
        for (index in 0 until doc.pageCount) {
            onProgress(Conversion.Reading(index + 1, doc.pageCount))
            val t0 = SystemClock.elapsedRealtime()
            // MuPDF under the viewer's lock, a page at a time; the SVG read off it
            val svg = lock { withContext(Dispatchers.IO) { doc.pageSvg(index) } } ?: continue
            val t1 = SystemClock.elapsedRealtime()
            val raw = withContext(Dispatchers.Default) { MuPdfSvg.read(svg, smufl) }
            val t2 = SystemClock.elapsedRealtime()
            // a page printed without its fonts has its symbols named by their look (the reference
            // shapes are built the first time a page needs them)
            val page = if (raw.glyphs.any { it.name != null }) raw else {
                val outlines = withContext(Dispatchers.IO) { Outlines.get(context) }
                val t3 = SystemClock.elapsedRealtime()
                Log.i(LOG_TAG, "page $index: references ready in ${t3 - t2} ms")
                withContext(Dispatchers.Default) { outlines.name(raw) }
            }
            Log.i(LOG_TAG, "page $index: svg ${t1 - t0} ms (${svg.size} bytes), parsed ${t2 - t1} ms, named ${SystemClock.elapsedRealtime() - t2} ms")
            pages += page
        }
        val traced = SystemClock.elapsedRealtime()
        onProgress(Conversion.Recognising)
        val score = withContext(Dispatchers.Default) { ScoreReader.read(pages) }
        val read = SystemClock.elapsedRealtime()
        val notes = score.measures.sumOf { m -> m.staves.sumOf { s -> s.events.sumOf { (it.event as? Chord)?.pitches?.size ?: 0 } } }
        // a scan, or a PDF drawn without a music font: nothing to add to the library
        if (notes == 0) return Conversion.Failed(context.getString(R.string.pdf_to_musicxml_no_music))
        val name = "$title (MusicXML)"
        val xml = withContext(Dispatchers.Default) { MusicXmlWriter.write(score, name) }
        val written = SystemClock.elapsedRealtime()
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "pdf-to-musicxml").apply { mkdirs() }.let { dir ->
                File(dir, name.replace(Regex("""[\\/:*?"<>|]"""), "_") + ".musicxml").apply { writeText(xml) }
            }
        }
        val imported = repository.importSheet(Uri.fromFile(file))
        withContext(Dispatchers.IO) { file.delete() }
        val glyphs = pages.flatMap { it.glyphs }
        val report = ConversionReport(
            pages = pages.size,
            measures = score.measures.size,
            parts = score.parts.size,
            notes = notes,
            glyphs = glyphs.size,
            named = glyphs.count { it.name != null },
            traceMs = traced - start,
            readMs = read - traced,
            writeMs = written - read,
            addedAs = (imported as? ImportResult.Ok)?.sheet?.title,
        )
        Log.i(LOG_TAG, "converted: $report")
        when (imported) {
            is ImportResult.Ok, ImportResult.Duplicate -> Conversion.Done(report)
            else -> Conversion.Failed(context.getString(R.string.pdf_to_musicxml_import_failed))
        }
    } catch (e: Exception) {
        Log.e(LOG_TAG, "conversion failed: ${e.message}", e)
        Conversion.Failed(e.message ?: e.javaClass.simpleName)
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
                        ) + "\n\n" + (addedAs?.let { stringResource(R.string.pdf_to_musicxml_added, it) }
                            ?: stringResource(R.string.pdf_to_musicxml_already))
                    }
                },
            )
        },
        confirmButton = {
            if (!running) TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_to_musicxml_close)) }
        },
    )
}
