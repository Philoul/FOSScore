package com.musigatto.fosscore.pdfscore

import java.awt.geom.AffineTransform
import java.awt.geom.GeneralPath
import java.awt.geom.PathIterator
import java.io.File

/**
 * Names a PDF's music-font glyphs with the [SymbolClassifier] the outlines and scans will use,
 * against the free fonts of [fontsDir] (Verovio's font data): a glyph is read as a SMuFL font
 * draws it, 4 staff spaces per em, whichever font it comes from.
 */
class GlyphNames(fontsDir: File) {
    private val classifier = SymbolClassifier(
        TestFonts.all(fontsDir).flatMap { font -> font.symbols.filter { it.name in CommonSymbols.NAMES } },
    )

    /** Not cached by font and code: two PDFs' subsets can share a font name and give the same code to other glyphs. */
    fun name(glyph: FontGlyph): String? = classifier.name(inSpaces(glyph))?.name

    /** The names closest to [glyph], best first (for probing). */
    fun ranking(glyph: FontGlyph): List<SymbolMatch> = classifier.rank(inSpaces(glyph))

    companion object {
        /** A glyph's outline (1000 units per em, y up) in staff spaces, y down. */
        fun inSpaces(glyph: FontGlyph): List<List<Point>> = flatten(glyph.outline, 4.0 / 1000)

        /** A font outline (y up) as polygons, y flipped down like the page, scaled by [scale]. */
        fun flatten(path: GeneralPath, scale: Double): List<List<Point>> {
            val result = mutableListOf<List<Point>>()
            var current = mutableListOf<Point>()
            val it = path.getPathIterator(AffineTransform.getScaleInstance(scale, -scale), 0.01)
            val c = DoubleArray(6)
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    PathIterator.SEG_MOVETO -> {
                        if (current.size > 2) result += current
                        current = mutableListOf(Point(c[0], c[1]))
                    }
                    PathIterator.SEG_LINETO -> current += Point(c[0], c[1])
                }
                it.next()
            }
            if (current.size > 2) result += current
            return result
        }
    }
}
