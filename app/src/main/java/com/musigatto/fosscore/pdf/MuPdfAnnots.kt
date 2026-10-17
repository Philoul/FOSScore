package com.musigatto.fosscore.pdf

import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import com.musigatto.fosscore.R
import java.io.File

private const val LOG_TAG = "FOSScore-Annots"

/** Error tipado de exportar tinta: cada caso lleva su string (ver values/strings.xml). */
sealed class ExportError(val res: Int) : Exception() {
    data object NoLocalFile : ExportError(R.string.err_no_local_file)
    data object NotEditable : ExportError(R.string.err_pdf_not_editable)
    data object ReplaceFailed : ExportError(R.string.err_replace_failed)
    data object NoInk : ExportError(R.string.err_no_ink)
}

/**
 * Tinta en coordenadas normalizadas de encaje (0..1, y-hacia-abajo): lo que guarda la BD.
 * Sin IDs ni metadatos de Room para no acoplar este paquete a la capa de datos.
 */
data class InkStroke(val points: List<Offset>, val widthFrac: Float, val colorArgb: Int?)

/**
 * Anotaciones dentro del propio PDF (/Annots), estilo Xodo: la tinta deja de vivir solo en el
 * sidecar SQLite y pasa a formar parte del documento (vale en cualquier visor).
 *
 * v1: solo tinta (Ink). Todo es síncrono y toca JNI: llamar bajo el mutex de render del visor
 * y desde un dispatcher de fondo. No toca la BD: quien llama borra el sidecar exportado.
 */
object MuPdfAnnots {

    /**
     * Escribe [strokes] por página como anotaciones Ink y guarda el archivo.
     * Devuelve las páginas escritas con éxito (para que el llamador borre ese sidecar).
     * No edita nada si el documento no abre como PDF editable.
     */
    fun exportInk(
        filePath: String,
        strokes: Map<Int, List<InkStroke>>,
        fallbackBox: (Int) -> PageBox?
    ): Set<Int> {
        if (strokes.isEmpty()) throw ExportError.NoInk
        val file = File(filePath)
        if (!file.isFile) throw ExportError.NoLocalFile
        val doc = Document.openDocument(filePath) as? PDFDocument
            ?: throw ExportError.NotEditable
        try {
            val done = mutableSetOf<Int>()
            for ((pageIdx, list) in strokes) {
                val usable = list.filter { it.points.size >= 2 }
                if (usable.isEmpty()) continue
                if (writePageInk(doc, pageIdx, usable, fallbackBox(pageIdx))) done += pageIdx
            }
            if (done.isEmpty()) return emptySet()
            // guardado completo a tmp + reemplazo: sin el peligro de reescribir el mismo
            // archivo que se está leyendo (el incremental lo evita, pero exige el doc abierto
            // del propio fichero y falla en PDFs reparados).
            val tmp = File("$filePath.fosscore-tmp")
            try {
                doc.save(tmp.absolutePath, "")
                if (!tmp.renameTo(file)) throw ExportError.ReplaceFailed
            } finally {
                tmp.delete()
            }
            return done
        } finally {
            runCatching { doc.destroy() }
        }
    }

    private fun writePageInk(
        doc: PDFDocument,
        pageIdx: Int,
        strokes: List<InkStroke>,
        fallback: PageBox?
    ): Boolean {
        if (pageIdx !in 0 until doc.countPages()) return false
        val page = try {
            doc.loadPage(pageIdx) as? PDFPage ?: return false
        } catch (e: Exception) {
            Log.e(LOG_TAG, "página $pageIdx no editable: ${e.message}", e)
            return false
        }
        return try {
            val space = readSpace(page, fallback) ?: return false
            var n = 0
            for (s in strokes) {
                val pts = s.points.map {
                    val p = PdfAnnotMapper.normalizedToPdf(it, space)
                    Point(p.x, p.y)
                }
                val annot = page.createAnnotation(PDFAnnotation.TYPE_INK)
                annot.setInkList(arrayOf(pts.toTypedArray()))
                annot.setRect(inkRect(pts, PdfAnnotMapper.widthFracToPt(s.widthFrac, space)))
                annot.setColor(PdfAnnotMapper.argbToPdfRgb(s.colorArgb ?: 0xFF000000.toInt()))
                annot.setBorderWidth(PdfAnnotMapper.widthFracToPt(s.widthFrac, space))
                annot.update()
                n++
            }
            n > 0
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo anotar la página $pageIdx: ${e.message}", e)
            false
        } finally {
            runCatching { page.destroy() }
        }
    }

    private fun readSpace(page: PDFPage, fallback: PageBox?): PdfPageSpace? {
        val parsed = runCatching {
            val obj = page.getObject()
            val mb = obj.get("MediaBox")
            val rot = runCatching { obj.getInheritable("Rotate")?.asInteger() ?: 0 }.getOrDefault(0)
            PdfPageSpace(
                mb.get(0).asFloat(), mb.get(1).asFloat(),
                mb.get(2).asFloat(), mb.get(3).asFloat(),
                PdfAnnotMapper.normalizeRotation(rot).let { if (it % 90 == 0) it else 0 }
            )
        }.getOrNull()
        if (parsed != null) return parsed
        // último recurso: caja de dibujo (ya rotada) como si no hubiera rotación
        Log.w(LOG_TAG, "MediaBox ilegible, uso caja de dibujo")
        return fallback?.let { PdfPageSpace(0f, 0f, it.width, it.height, 0) }
    }

    private fun inkRect(pts: List<Point>, widthPt: Float): Rect {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < x0) x0 = p.x
            if (p.y < y0) y0 = p.y
            if (p.x > x1) x1 = p.x
            if (p.y > y1) y1 = p.y
        }
        val pad = widthPt / 2f + 1f
        return Rect(x0 - pad, y0 - pad, x1 + pad, y1 + pad)
    }
}
