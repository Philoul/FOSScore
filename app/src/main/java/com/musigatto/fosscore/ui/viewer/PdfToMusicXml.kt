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
import com.musigatto.fosscore.pdfscore.MuPdfSvg
import com.musigatto.fosscore.pdfscore.MusicXmlWriter
import com.musigatto.fosscore.pdfscore.PageDrawing
import com.musigatto.fosscore.pdfscore.ScoreReader
import com.musigatto.fosscore.pdfscore.SmuflCodes
import com.musigatto.fosscore.pdfscore.music.Chord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Prototype: a vector PDF score read into MusicXML and added to the library, each stage timed, to
 * try the recognition (:pdfscore) on a device. The PDF's music glyphs are named by their SMuFL code
 * points only (what MuseScore's PDFs carry); a scan or a PDF of outlines reads no music yet.
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
            // MuPDF under the viewer's lock, a page at a time; the SVG read off it
            val svg = lock { withContext(Dispatchers.IO) { doc.pageSvg(index) } }
            svg?.let { pages += withContext(Dispatchers.Default) { MuPdfSvg.read(it, smufl) } }
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
