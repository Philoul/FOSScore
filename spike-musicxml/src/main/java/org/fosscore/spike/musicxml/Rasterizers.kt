package org.fosscore.spike.musicxml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.caverock.androidsvg.SVG
import java.nio.ByteBuffer

/** The two native-bitmap paths compared in M0 (the WebView path lives in the UI). */
enum class Renderer { MUPDF, ANDROIDSVG, WEBVIEW }

object Rasterizers {

    /** SVG → bitmap through MuPDF's SVG document handler (the engine FOSScore already ships). */
    fun mupdf(svg: String, width: Int, height: Int): Bitmap {
        val doc = Document.openDocument(forMuPdf(svg).toByteArray(Charsets.UTF_8), "image/svg+xml")
        try {
            val page = doc.loadPage(0)
            try {
                val b = page.getBounds()
                val scale = minOf(width / (b.x1 - b.x0), height / (b.y1 - b.y0))
                val m = Matrix(scale, 0f, 0f, scale, -b.x0 * scale, -b.y0 * scale)
                val pix = page.toPixmap(m, ColorSpace.DeviceRGB, true, false)
                try {
                    val bmp = Bitmap.createBitmap(pix.getWidth(), pix.getHeight(), Bitmap.Config.ARGB_8888)
                    val samples = pix.getSamples()
                    val stride = pix.getStride()
                    val rowBytes = pix.getWidth() * 4
                    if (stride == rowBytes) {
                        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(samples))
                    } else {
                        for (y in 0 until pix.getHeight()) {
                            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(samples, y * stride, rowBytes))
                        }
                    }
                    return onWhite(bmp)
                } finally {
                    pix.destroy()
                }
            } finally {
                page.destroy()
            }
        } finally {
            doc.destroy()
        }
    }

    /** SVG → bitmap through AndroidSVG (pure Java, draws on a Canvas). */
    fun androidSvg(svg: String, width: Int, height: Int): Bitmap {
        val doc = SVG.getFromString(svg)
        val box = doc.documentViewBox
        val aspect = if (box != null && box.height() > 0f) box.width() / box.height() else width.toFloat() / height
        val w: Int
        val h: Int
        if (width / aspect <= height) {
            w = width; h = (width / aspect).toInt()
        } else {
            h = height; w = (height * aspect).toInt()
        }
        doc.setDocumentWidth(w.toFloat())
        doc.setDocumentHeight(h.toFloat())
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        doc.renderToCanvas(canvas)
        return bmp
    }

    private val STYLE = Regex("<style.*?</style>", RegexOption.DOT_MATCHES_ALL)
    private val UNSTROKED = Regex("<(path|rect|polygon|polyline|ellipse)(?![^>]*\\bstroke=)")

    /**
     * MuPDF's SVG reader ignores CSS and needs explicit sizes, so Verovio's output is adapted:
     * - the nested `definition-scale` <svg> gets width/height 100% (otherwise nothing is drawn);
     * - the CSS rule `path, rect... {stroke: currentColor}` becomes an attribute (otherwise staff
     *   lines, stems and bar lines are missing);
     * - the <style> blocks (including the embedded web font) are dropped.
     */
    fun forMuPdf(svg: String): String = svg
        .replace(STYLE, "")
        .replace("<svg class=\"definition-scale\"", "<svg class=\"definition-scale\" width=\"100%\" height=\"100%\"")
        .replace(UNSTROKED) { "<${it.groupValues[1]} stroke=\"currentColor\"" }

    private fun onWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        src.recycle()
        return out
    }
}
