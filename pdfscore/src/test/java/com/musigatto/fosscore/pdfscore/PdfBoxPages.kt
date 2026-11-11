package com.musigatto.fosscore.pdfscore

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType3Font
import org.apache.pdfbox.pdmodel.font.PDVectorFont
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector
import java.awt.geom.AffineTransform
import java.awt.geom.GeneralPath
import java.awt.geom.PathIterator
import java.awt.geom.Point2D
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** A music font's glyph as the PDF holds it: its outline in 1000 units per em, for naming it. */
class FontGlyph(val font: String, val code: Int, val outline: GeneralPath)

/** One page read by PDFBox: the drawing, and the music fonts' glyph outlines it used. */
class ReadPage(val drawing: PageDrawing, val fontGlyphs: Map<Pair<String, Int>, FontGlyph>)

/** Reads a PDF's pages into [PageDrawing]s with PDFBox (tests only; the app reads PDFs with MuPDF). */
object PdfBoxPages {
    private val MUSIC_FONTS = listOf("Leland", "MScore", "Bravura", "Emmentaler", "Petaluma", "Gonville", "Maestro")

    fun isMusicFont(name: String): Boolean = MUSIC_FONTS.any { name.contains(it, ignoreCase = true) }

    fun read(file: File, pageIndex: Int, names: GlyphNames? = null): ReadPage =
        Loader.loadPDF(file).use { doc ->
            val engine = Engine(doc.getPage(pageIndex))
            engine.run()
            engine.result(names)
        }

    private class Engine(private val page: PDPage) : PDFGraphicsStreamEngine(page) {
        private val crop = page.cropBox
        private val path = GeneralPath()
        private var curved = false
        private val glyphs = mutableListOf<DrawnGlyph>()
        private val lines = mutableListOf<DrawnLine>()
        private val shapes = mutableListOf<DrawnShape>()
        private val texts = mutableListOf<DrawnText>()
        private val fontGlyphs = linkedMapOf<Pair<String, Int>, FontGlyph>()
        private var lastTextEnd = Double.NaN

        fun run() = processPage(page)

        fun result(names: GlyphNames?): ReadPage {
            val nameOf = fontGlyphs.mapValues { (_, glyph) -> names?.name(glyph) }
            val named = glyphs.map { g ->
                val name = nameOf[g.font to g.code]
                if (name == null) g else g.copy(name = name)
            }
            return ReadPage(
                PageDrawing(crop.width.toDouble(), crop.height.toDouble(), named, lines, shapes, texts),
                fontGlyphs,
            )
        }

        private fun pageX(x: Double) = x - crop.lowerLeftX
        private fun pageY(y: Double) = crop.upperRightY - y

        override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
            if (font is PDType3Font) {
                super.showGlyph(textRenderingMatrix, font, code, displacement)
                return
            }
            val fontName = font.name ?: ""
            val size = textRenderingMatrix.scalingFactorY.toDouble()
            val x = pageX(textRenderingMatrix.translateX.toDouble())
            val y = pageY(textRenderingMatrix.translateY.toDouble())
            if (isMusicFont(fontName) && font is PDVectorFont) {
                val outline = font.getNormalizedPath(code)
                fontGlyphs.getOrPut(fontName to code) { FontGlyph(fontName, code, outline) }
                val at = textRenderingMatrix.createAffineTransform()
                at.scale(0.001, 0.001)
                val b = at.createTransformedShape(outline).bounds2D
                val box = if (b.isEmpty) Box(x, y, x, y) else Box(pageX(b.minX), pageY(b.maxY), pageX(b.maxX), pageY(b.minY))
                glyphs += DrawnGlyph(null, box, font = fontName, code = code, x = x, y = y, size = size)
            } else {
                addText(font.toUnicode(code) ?: "", fontName, x, y, size, font.getWidth(code) / 1000.0 * textRenderingMatrix.scalingFactorX)
            }
        }

        private fun addText(text: String, font: String, x: Double, y: Double, size: Double, advance: Double) {
            val last = texts.lastOrNull()
            val gap = x - lastTextEnd
            if (last != null && last.font == font && abs(last.y - y) < 0.5 && gap > -0.1 * size && gap < 0.3 * size) {
                texts[texts.size - 1] = last.copy(text = last.text + (if (gap > 0.15 * size) " " else "") + text)
            } else {
                texts += DrawnText(text, font, x, y, size)
            }
            lastTextEnd = x + advance
        }

        override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
            path.moveTo(p0.x, p0.y)
            path.lineTo(p1.x, p1.y)
            path.lineTo(p2.x, p2.y)
            path.lineTo(p3.x, p3.y)
            path.closePath()
        }

        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            curved = true
            path.curveTo(x1, y1, x2, y2, x3, y3)
        }

        override fun getCurrentPoint(): Point2D = path.currentPoint ?: Point2D.Float()
        override fun closePath() = path.closePath()
        override fun endPath() = reset()
        override fun clip(windingRule: Int) = Unit
        override fun drawImage(pdImage: PDImage) = Unit
        override fun shadingFill(shadingName: COSName) = Unit

        override fun strokePath() {
            val ctm = graphicsState.currentTransformationMatrix
            val width = graphicsState.lineWidth * sqrt(abs(ctm.scaleX * ctm.scaleY - ctm.shearX * ctm.shearY).toDouble())
            subpaths().forEach { points ->
                points.zipWithNext().forEach { (a, b) -> lines += DrawnLine(a.x, a.y, b.x, b.y, width) }
            }
            reset()
        }

        override fun fillPath(windingRule: Int) {
            subpaths().filter { it.size >= 3 }.forEach { points ->
                val shape = DrawnShape(points, curved)
                shape.asLine()?.let { lines += it } ?: run { shapes += shape }
            }
            reset()
        }

        override fun fillAndStrokePath(windingRule: Int) = fillPath(windingRule)

        private fun reset() {
            path.reset()
            curved = false
        }

        /** The current path's subpaths, flattened, in page coordinates. */
        private fun subpaths(): List<List<Point>> {
            val result = mutableListOf<List<Point>>()
            var current = mutableListOf<Point>()
            val it = path.getPathIterator(AffineTransform(), 0.1)
            val c = DoubleArray(6)
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    PathIterator.SEG_MOVETO -> {
                        if (current.size > 1) result += current
                        current = mutableListOf(Point(pageX(c[0]), pageY(c[1])))
                    }
                    PathIterator.SEG_LINETO -> current += Point(pageX(c[0]), pageY(c[1]))
                    PathIterator.SEG_CLOSE -> current.firstOrNull()?.let { current += it }
                }
                it.next()
            }
            if (current.size > 1) result += current
            return result
        }
    }
}
