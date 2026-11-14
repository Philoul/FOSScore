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
class FontGlyph(val font: String, val code: Int, val outline: GeneralPath, val unicode: String? = null)

/** One page read by PDFBox: the drawing, and the music fonts' glyph outlines it used. */
class ReadPage(val drawing: PageDrawing, val fontGlyphs: Map<Pair<String, Int>, FontGlyph>)

/** Reads a PDF's pages into [PageDrawing]s with PDFBox (tests only; the app reads PDFs with MuPDF). */
object PdfBoxPages {
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
        private val drawing = PageBuilder(crop.width.toDouble(), crop.height.toDouble())
        private val fontGlyphs = linkedMapOf<Pair<String, Int>, FontGlyph>()

        fun run() = processPage(page)

        fun result(names: GlyphNames?): ReadPage {
            // each font's glyphs named together: a SMuFL font is named by its code points
            val nameOf = fontGlyphs.values.groupBy { it.font }.values.flatMap { font ->
                val named = names?.names(font) ?: font.map { null }
                font.map { it.font to it.code }.zip(named)
            }.toMap()
            return ReadPage(drawing.build { g -> nameOf[g.font to g.code] }, fontGlyphs)
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
            val unicode = font.toUnicode(code)
            if (font is PDVectorFont && MusicFonts.isMusic(fontName, unicode, code)) {
                val outline = font.getNormalizedPath(code)
                fontGlyphs.getOrPut(fontName to code) { FontGlyph(fontName, code, outline, unicode) }
                val at = textRenderingMatrix.createAffineTransform()
                at.scale(0.001, 0.001)
                val b = at.createTransformedShape(outline).bounds2D
                val box = if (b.isEmpty) Box(x, y, x, y) else Box(pageX(b.minX), pageY(b.maxY), pageX(b.maxX), pageY(b.minY))
                drawing.glyph(DrawnGlyph(null, box, font = fontName, code = code, x = x, y = y, size = size))
            } else {
                drawing.text(unicode ?: "", fontName, x, y, size, font.getWidth(code) / 1000.0 * textRenderingMatrix.scalingFactorX)
            }
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
            drawing.stroke(subpaths(), width)
            reset()
        }

        override fun fillPath(windingRule: Int) {
            drawing.fill(subpaths(), curved)
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
