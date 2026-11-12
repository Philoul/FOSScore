package com.musigatto.fosscore.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.library.isTextStamp
import com.musigatto.fosscore.pdf.PdfAnnotMapper
import com.musigatto.fosscore.pdf.PdfPageSpace
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One annotation, already worked out, in the page's normalized coordinates AS IT IS SEEN (0..1):
 * the writer turns these into PDF points. The mapping is pure so it can be tested without MuPDF.
 */
internal sealed interface Annot {
    /** What a PDF reader's comment panel lists under the annotation. */
    val contents: String

    /** /Subj: the layer's name. */
    val layer: String

    /** ARGB. */
    val color: Int

    /** Free ink, a staff (5 lines) and any shape. */
    data class Path(
        val subpaths: List<List<Offset>>,
        /** Share of the page height, as [Stroke] keeps it. */
        val widthFrac: Float,
        val opacity: Float,
        /** The first point is the last. */
        val closed: Boolean,
        val filled: Boolean,
        /** true = ink (round caps); false = a polyline/polygon. */
        val ink: Boolean,
        override val contents: String,
        override val layer: String,
        override val color: Int,
    ) : Annot

    data class Highlight(
        val rect: RectPx,
        val opacity: Float,
        override val contents: String,
        override val layer: String,
        override val color: Int,
    ) : Annot

    data class Text(
        val rect: RectPx,
        val text: String,
        val font: TextFont,
        val bold: Boolean,
        val italic: Boolean,
        val sizeFrac: Float,
        override val contents: String,
        override val layer: String,
        override val color: Int,
    ) : Annot

    data class Glyph(
        val rect: RectPx,
        val codepoint: Int,
        override val contents: String,
        override val layer: String,
        override val color: Int,
    ) : Annot
}

internal data class ExportPlan(val pages: List<PageAnnots>) {
    data class PageAnnots(val page: Int, val annots: List<Annot>)
}

/**
 * Works out the annotations of [layerId] (null = every layer): each mark becomes the PDF annotation
 * the writer will create. Marks of an unknown symbol are skipped, not invented.
 *
 * @param fit the page as seen, in points (the rotation already applied).
 * @param measureText the measured box of a text stamp, in [fit] units.
 */
internal fun buildExportPlan(
    stamps: List<Stamp>,
    strokes: List<Stroke>,
    layerName: (Long) -> String,
    fit: (Int) -> RectPx,
    measureText: (Stamp, RectPx) -> Pair<Float, Float>,
    layerId: Long? = null,
): ExportPlan {
    val byPage = sortedMapOf<Int, MutableList<Annot>>()

    for (stroke in strokes) {
        if (layerId != null && stroke.layer != layerId) continue
        val annot = strokeAnnot(stroke, layerName(stroke.layer)) ?: continue
        byPage.getOrPut(stroke.page) { mutableListOf() } += annot
    }
    for (stamp in stamps) {
        if (layerId != null && stamp.layer != layerId) continue
        val annot = stampAnnot(stamp, fit(stamp.page), layerName(stamp.layer), measureText) ?: continue
        byPage.getOrPut(stamp.page) { mutableListOf() } += annot
    }

    return ExportPlan(byPage.map { (page, annots) -> ExportPlan.PageAnnots(page, annots) })
}

private fun strokeAnnot(stroke: Stroke, layer: String): Annot? {
    val points = decodePoints(stroke.points)
    if (points.isEmpty()) return null
    val color = stroke.color ?: 0xFF000000.toInt()
    return when (stroke.kind) {
        Stroke.HIGHLIGHTER -> Annot.Highlight(
            rect = strokeBounds(points),
            opacity = HIGHLIGHTER_ALPHA,
            contents = layer,
            layer = layer,
            color = color,
        )
        Stroke.SHAPE_STAFF -> Annot.Path(
            // a staff's points come in pairs: each pair is one line
            subpaths = points.chunked(2),
            widthFrac = stroke.width,
            opacity = 1f,
            closed = false,
            filled = false,
            ink = false,
            contents = layer,
            layer = layer,
            color = color,
        )
        else -> {
            val closed = points.first() == points.last()
            Annot.Path(
                subpaths = listOf(points),
                widthFrac = stroke.width,
                opacity = 1f,
                closed = closed,
                filled = Stroke.isFilled(stroke.kind),
                ink = stroke.kind == Stroke.INK,
                contents = layer,
                layer = layer,
                color = color,
            )
        }
    }
}

private fun stampAnnot(
    stamp: Stamp,
    fit: RectPx,
    layer: String,
    measureText: (Stamp, RectPx) -> Pair<Float, Float>,
): Annot? {
    // the page could not be measured (unreadable MediaBox): leave the mark out
    if (fit.width <= 0f || fit.height <= 0f) return null
    val color = stamp.color ?: 0xFF000000.toInt()
    if (stamp.isTextStamp()) {
        val (w, h) = measureText(stamp, fit)
        return Annot.Text(
            rect = textBoxRect(stamp, w, h, fit).normalized(fit),
            text = stamp.text.orEmpty(),
            font = TextFont.of(stamp.font),
            bold = stamp.bold,
            italic = stamp.italic,
            sizeFrac = stamp.size,
            contents = stamp.text.orEmpty(),
            layer = layer,
            color = color,
        )
    }
    val symbol = StampSymbol.entries.firstOrNull { it.name == stamp.symbol } ?: return null
    return Annot.Glyph(
        rect = stampRect(stamp, fit).normalized(fit),
        codepoint = symbol.codepoint,
        contents = stamp.symbol,
        layer = layer,
        color = color,
    )
}

/** A box measured on the page as it is seen (0..fit) as a share of it, which is what [Annot] holds. */
private fun RectPx.normalized(fit: RectPx): RectPx = RectPx(
    left = (left - fit.left) / fit.width,
    top = (top - fit.top) / fit.height,
    width = width / fit.width,
    height = height / fit.height,
)

/** The rect (normalized, as seen) in PDF points, counting from the bottom, page turn removed. */
internal fun pdfRect(rect: RectPx, space: PdfPageSpace): RectPx {
    val a = PdfAnnotMapper.normalizedToPdf(Offset(rect.left, rect.top), space)
    val b = PdfAnnotMapper.normalizedToPdf(Offset(rect.right, rect.bottom), space)
    val left = min(a.x, b.x)
    val right = max(a.x, b.x)
    val top = min(a.y, b.y)
    val bottom = max(a.y, b.y)
    return RectPx(left, top, right - left, bottom - top)
}

private const val LOG_TAG = "FOSScore-Export"

/** /T of every annotation: the PC's comment panel shows who wrote it. */
private const val AUTHOR = "FOSScore"

/** The glyph is rasterised at twice the size it takes on the page, so it stays sharp on zoom. */
private const val GLYPH_SCALE = 2f

/** Typed export error: each case carries its own string (see values/strings.xml). */
internal sealed class ExportError(val res: Int) : Exception() {
    class NotEditable : ExportError(R.string.err_pdf_not_editable)
    class NoMarks : ExportError(R.string.err_no_marks)
    class WriteFailed : ExportError(R.string.err_export_failed)
}

/**
 * Writes the annotations inside a COPY of the score's PDF, so a PC shows the marks in its own
 * comment panel. The original file and Room's sidecar stay as they are: the export can be repeated,
 * and nothing depends on it.
 */
internal object AnnotationExport {

    /**
     * Writes the marks of [layerId] (null = every layer) into a copy of [source] and leaves it in
     * [destination]. [layerName] is the /Subj a reader lists the annotation under.
     *
     * Runs to the end even if the viewer is closed (NonCancellable): a half-written file at
     * [destination] would be worse than a slower export.
     */
    suspend fun export(
        context: Context,
        source: Uri,
        destination: Uri,
        stamps: List<Stamp>,
        strokes: List<Stroke>,
        layerName: (Long) -> String,
        layerId: Long? = null,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (stamps.isEmpty() && strokes.isEmpty()) throw ExportError.NoMarks()
            val tmp = File(context.cacheDir, "share-src.pdf")
            val out = File(context.cacheDir, "share-out.pdf")
            try {
                copy(context, source, tmp)
                // never write the file that is being read: save to a second one and copy that
                val doc = Document.openDocument(tmp.path) as? PDFDocument
                    ?: throw ExportError.NotEditable()
                try {
                    val spaces = HashMap<Int, PdfPageSpace>()
                    fun spaceOf(index: Int): PdfPageSpace? {
                        if (spaces.containsKey(index)) return spaces[index]
                        val page = runCatching { doc.loadPage(index) as? PDFPage }.getOrNull()
                        val space = page?.let { readSpace(it) ?: boundsSpace(it) }
                        page?.let { runCatching { it.destroy() } }
                        if (space != null) spaces[index] = space
                        return space
                    }
                    fun fitOf(index: Int): RectPx {
                        val s = spaceOf(index) ?: return RectPx(0f, 0f, 0f, 0f)
                        // the page as it is SEEN: a turned page swaps its sides
                        return if (s.rotation % 180 == 0) RectPx(0f, 0f, s.w, s.h)
                        else RectPx(0f, 0f, s.h, s.w)
                    }
                    val plan = buildExportPlan(
                        stamps = stamps,
                        strokes = strokes,
                        layerName = layerName,
                        fit = { page -> fitOf(page) },
                        measureText = { stamp, fit -> measureTextBox(stamp, fit) },
                        layerId = layerId,
                    )
                    for ((page1, annots) in plan.pages) {
                        val space = spaceOf(page1) ?: continue
                        val page = doc.loadPage(page1) as PDFPage
                        try {
                            writePage(context, page, annots, space, fitOf(page1))
                        } finally {
                            runCatching { page.destroy() }
                        }
                    }
                    doc.save(out.path, "")
                } finally {
                    runCatching { doc.destroy() }
                }
                copy(context, out, destination)
            } catch (e: ExportError) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "no se pudo exportar: ${e::class.simpleName}: ${e.message}", e)
                throw ExportError.WriteFailed()
            } finally {
                tmp.delete()
                out.delete()
            }
        }
    }

    private fun copy(context: Context, from: Uri, to: File) {
        val input = context.contentResolver.openInputStream(from) ?: throw ExportError.WriteFailed()
        input.use { i -> to.outputStream().use { o -> i.copyTo(o) } }
    }

    private fun copy(context: Context, from: File, to: Uri) {
        val output = context.contentResolver.openOutputStream(to) ?: throw ExportError.WriteFailed()
        from.inputStream().use { i -> output.use { o -> i.copyTo(o) } }
    }

    /** Where the page sits and how far it is turned, read from the file itself. */
    private fun readSpace(page: PDFPage): PdfPageSpace? = runCatching {
        val obj = page.getObject()
        val mb = obj.getInheritable("MediaBox")
        val rot = runCatching { obj.getInheritable("Rotate")?.asInteger() ?: 0 }.getOrDefault(0)
        PdfPageSpace(
            mb.get(0).asFloat(), mb.get(1).asFloat(), mb.get(2).asFloat(), mb.get(3).asFloat(),
            PdfAnnotMapper.normalizeRotation(rot).let { if (it % 90 == 0) it else 0 },
        )
    }.getOrNull()

    /** Last resort for a page with an unreadable MediaBox: its drawing box, as if it were straight. */
    private fun boundsSpace(page: PDFPage): PdfPageSpace? = runCatching {
        val b = page.bounds
        PdfPageSpace(b.x0, b.y0, b.x1, b.y1, 0)
    }.getOrNull()

    /** The box of a text stamp, laid out as the page draws it (same font, size and wrap). */
    private fun measureTextBox(stamp: Stamp, fit: RectPx): Pair<Float, Float> {
        val text = stamp.text.orEmpty()
        val wrap = textWrapWidthPx(stamp.textWidth, fit)
        val family = when (TextFont.of(stamp.font)) {
            TextFont.SANS -> Typeface.SANS_SERIF
            TextFont.SERIF -> Typeface.SERIF
            TextFont.MONO -> Typeface.MONOSPACE
        }
        val style = when {
            stamp.bold && stamp.italic -> Typeface.BOLD_ITALIC
            stamp.bold -> Typeface.BOLD
            stamp.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textFontPx(stamp.size, fit)
            typeface = Typeface.create(family, style)
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, wrap.roundToInt().coerceAtLeast(1))
            .setLineSpacing(0f, TEXT_LINE_EM)
            .build()
        return textBoxWidthPx(stamp.textWidth, layout.width.toFloat(), wrap) to layout.height.toFloat()
    }

    private fun writePage(
        context: Context,
        page: PDFPage,
        annots: List<Annot>,
        space: PdfPageSpace,
        fit: RectPx,
    ) {
        val typeface = bravura(context)
        val now = Date()
        for (a in annots) {
            when (a) {
                is Annot.Path -> page.writePath(a, space, now)
                is Annot.Highlight -> page.writeHighlight(a, space, now)
                is Annot.Text -> page.writeText(a, space, fit, now)
                is Annot.Glyph -> page.writeGlyph(a, space, typeface, now)
            }
        }
    }

    /** Free ink, a staff and any shape. Ink is the only type that holds several separate lines. */
    private fun PDFPage.writePath(a: Annot.Path, space: PdfPageSpace, now: Date) {
        val paths = a.subpaths.map { sub -> sub.map { PdfAnnotMapper.normalizedToPdf(it, space) } }
        if (paths.isEmpty() || paths.any { it.isEmpty() }) return
        val closed = a.closed || a.filled
        val type = when {
            a.ink || paths.size > 1 -> PDFAnnotation.TYPE_INK
            closed -> PDFAnnotation.TYPE_POLYGON
            else -> PDFAnnotation.TYPE_POLY_LINE
        }
        val annot = createAnnotation(type)
        if (type == PDFAnnotation.TYPE_INK) {
            annot.inkList = paths.map { path -> path.map { Point(it.x, it.y) }.toTypedArray() }.toTypedArray()
        } else {
            // a polygon closes itself: the repeated last point would only add an empty edge
            val vertices = paths.single().let { if (closed) it.dropLastWhile { p -> p == it.first() } else it }
            annot.vertices = vertices.map { Point(it.x, it.y) }.toTypedArray()
        }
        val widthPt = PdfAnnotMapper.widthFracToPt(a.widthFrac, space)
        val bounds = pathBounds(paths, widthPt)
        // setRect is refused for ink (its rect comes from the points), so the value goes straight in
        annot.getObject().put("Rect", bounds)
        annot.borderWidth = widthPt
        annot.color = PdfAnnotMapper.argbToPdfRgb(a.color)
        annot.opacity = a.opacity
        if (a.filled && type == PDFAnnotation.TYPE_POLYGON) {
            annot.interiorColor = PdfAnnotMapper.argbToPdfRgb(a.color)
        }
        annot.decorate(a, bounds, now)
    }

    /**
     * A highlight is one quad over the covered area: the marks of a marker are not the path of its
     * edge. A single rectangle loses the shape of the stroke, but reads the same on the page.
     */
    private fun PDFPage.writeHighlight(a: Annot.Highlight, space: PdfPageSpace, now: Date) {
        val r = pdfRect(a.rect, space)
        val annot = createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT)
        annot.rect = Rect(r.left, r.top, r.right, r.bottom)
        // ul, ur, ll, lr: PDF coordinates count up, so the "upper" edge is the larger y
        annot.quadPoints = arrayOf(Quad(r.left, r.bottom, r.right, r.bottom, r.left, r.top, r.right, r.top))
        annot.color = PdfAnnotMapper.argbToPdfRgb(a.color)
        annot.opacity = a.opacity
        annot.decorate(a, annot.rect, now)
    }

    private fun PDFPage.writeText(a: Annot.Text, space: PdfPageSpace, fit: RectPx, now: Date) {
        val r = pdfRect(a.rect, space)
        val annot = createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
        annot.rect = Rect(r.left, r.top, r.right, r.bottom)
        annot.setDefaultAppearance(
            pdfFont(a.font, a.bold, a.italic),
            textFontPx(a.sizeFrac, fit),
            PdfAnnotMapper.argbToPdfRgb(a.color),
        )
        annot.decorate(a, annot.rect, now)
    }

    /** A symbol stamp: the Bravura glyph is drawn into a picture the PDF carries. */
    private fun PDFPage.writeGlyph(a: Annot.Glyph, space: PdfPageSpace, typeface: Typeface?, now: Date) {
        // without the font the stamp would be an empty box: leave it out rather than lie
        if (typeface == null) return
        val r = pdfRect(a.rect, space)
        val side = max(r.width, r.height).coerceAtLeast(1f) * GLYPH_SCALE
        val px = side.roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(px, px)
        drawStampGlyph(Canvas(bitmap), typeface, a.codepoint, px.toFloat(), a.color)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val image = Image(png.toByteArray())
        try {
            val annot = createAnnotation(PDFAnnotation.TYPE_STAMP)
            annot.rect = Rect(r.left, r.top, r.right, r.bottom)
            annot.setStampImage(image)
            annot.decorate(a, annot.rect, now)
        } finally {
            runCatching { image.destroy() }
        }
    }

    /**
     * What every reader needs to show the mark in its comment panel: the text, who wrote it, the
     * layer as the subject, a stable name, the date, and the popup the panel hangs it from.
     */
    private fun PDFAnnotation.decorate(a: Annot, rect: Rect, now: Date) {
        contents = a.contents
        author = AUTHOR
        subject = a.layer
        name = UUID.randomUUID().toString()
        creationDate = now
        modificationDate = now
        // MuPDF creates the popup annotation, links it to this one (/Parent) and does not draw it
        popup = Rect(rect.x1 + 4f, rect.y1 - 60f, rect.x1 + 204f, rect.y1)
        update()
    }

    /** Base-14 fonts every reader already has, so nothing needs embedding. */
    private fun pdfFont(font: TextFont, bold: Boolean, italic: Boolean): String = when (font) {
        TextFont.SANS -> when {
            bold && italic -> "HeBoOb"
            bold -> "HeBo"
            italic -> "HeOb"
            else -> "Helv"
        }
        TextFont.SERIF -> when {
            bold && italic -> "TiBoIt"
            bold -> "TiBo"
            italic -> "TiIt"
            else -> "TiRo"
        }
        TextFont.MONO -> when {
            bold && italic -> "CoBoOb"
            bold -> "CoBo"
            italic -> "CoOb"
            else -> "Cour"
        }
    }

    /** The box around every point, plus half the line: an annotation must cover its own drawing. */
    private fun pathBounds(paths: List<List<Offset>>, widthPt: Float): Rect {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (path in paths) for (p in path) {
            if (p.x < x0) x0 = p.x
            if (p.y < y0) y0 = p.y
            if (p.x > x1) x1 = p.x
            if (p.y > y1) y1 = p.y
        }
        val pad = widthPt / 2f + 1f
        return Rect(x0 - pad, y0 - pad, x1 + pad, y1 + pad)
    }
}