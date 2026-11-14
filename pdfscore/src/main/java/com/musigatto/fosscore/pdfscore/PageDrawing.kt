package com.musigatto.fosscore.pdfscore

/**
 * What one page of a score shows, as the recognition reads it: the lines, the music symbols and the
 * text. Every kind of page ends here, so that everything after it is shared:
 * - a PDF from a notation program gives its font's glyphs, its strokes and its filled outlines;
 * - a PDF printed to outlines gives strokes and outlines only;
 * - a scanned page gives the lines and blobs found in its pixels.
 * Whatever reads the page (MuPDF in the app, PDFBox in the tests) gives this; nothing here depends on it.
 *
 * Coordinates are in points, from the page's top left corner, y downward (as on screen).
 */
class PageDrawing(
    val width: Double,
    val height: Double,
    val glyphs: List<DrawnGlyph>,
    val lines: List<DrawnLine>,
    val shapes: List<DrawnShape>,
    val texts: List<DrawnText>,
)

/** A rectangle on the page, in points, y downward. */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2
}

/**
 * A music symbol. [name] is its SMuFL name ("noteheadBlack", "gClef") once recognised, else null.
 * [box] is where its ink is on the page: the recognition places symbols by their box, the same way
 * whether they came from a font glyph, an outline or the pixels of a scan.
 * [confidence] is 1 when the name is certain (a known font's glyph) and lower when it was guessed
 * from the symbol's look.
 * For a font glyph, [font] and [code] identify it, and ([x], [y]) is its origin on the baseline with
 * [size] the font size (one staff space is [size] / 4 in SMuFL fonts).
 */
data class DrawnGlyph(
    val name: String?,
    val box: Box,
    val confidence: Double = 1.0,
    val font: String = "",
    val code: Int = -1,
    val x: Double = box.left,
    val y: Double = box.bottom,
    val size: Double = 0.0,
)

/** A straight stroke from ([x1], [y1]) to ([x2], [y2]): a staff line, a stem, a barline, a ledger line. */
data class DrawnLine(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val width: Double,
) {
    val isHorizontal: Boolean get() = kotlin.math.abs(y2 - y1) < 0.01 * kotlin.math.abs(x2 - x1) + 0.05
    val isVertical: Boolean get() = kotlin.math.abs(x2 - x1) < 0.01 * kotlin.math.abs(y2 - y1) + 0.05
    val length: Double get() = kotlin.math.hypot(x2 - x1, y2 - y1)
}

/**
 * A filled shape (a beam, a slur or tie, a bracket, or a symbol drawn as an outline), its outline
 * flattened to [points]; [curved] when it had curves (slurs and ties do, beams do not). The shapes
 * of one fill share its [fill] number (a half note's head is its outline and its hole); -1 when
 * nothing says which fill it was.
 */
data class DrawnShape(
    val points: List<Point>,
    val curved: Boolean,
    val fill: Int = -1,
) {
    val left: Double get() = points.minOf { it.x }
    val right: Double get() = points.maxOf { it.x }
    val top: Double get() = points.minOf { it.y }
    val bottom: Double get() = points.maxOf { it.y }
    val box: Box get() = Box(left, top, right, bottom)
}

data class Point(val x: Double, val y: Double)

/** A run of text (a title, a lyric syllable, an instrument's name, "D.S. al Coda"). */
data class DrawnText(
    val text: String,
    val font: String,
    val x: Double,
    val y: Double,
    val size: Double,
)
