package com.musigatto.fosscore.pdfscore

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Builds a [PageDrawing] from what a PDF reader traces of a page, in page points, y downward: its
 * music glyphs, its text, its stroked and filled paths. Every PDF reader (MuPDF in the app, PDFBox
 * in the tests) feeds one, so that the page the recognition gets is the same whichever read it.
 */
class PageBuilder(private val width: Double, private val height: Double) {
    private val glyphs = mutableListOf<DrawnGlyph>()
    private val lines = mutableListOf<DrawnLine>()
    private val shapes = mutableListOf<DrawnShape>()
    private val texts = mutableListOf<DrawnText>()
    private var lastTextEnd = Double.NaN

    fun glyph(glyph: DrawnGlyph) {
        glyphs += glyph
    }

    /**
     * One character of text at ([x], [y]) on its baseline, [advance] wide: characters that follow
     * each other on a line, in one font, make one run (a space where the gap is wide).
     */
    fun text(text: String, font: String, x: Double, y: Double, size: Double, advance: Double) {
        val last = texts.lastOrNull()
        val gap = x - lastTextEnd
        if (last != null && last.font == font && abs(last.y - y) < 0.5 && gap > -0.1 * size && gap < 0.3 * size) {
            texts[texts.size - 1] = last.copy(text = last.text + (if (gap > 0.15 * size) " " else "") + text)
        } else {
            texts += DrawnText(text, font, x, y, size)
        }
        lastTextEnd = x + advance
    }

    /** A stroked path, its subpaths flattened: each segment is a line [width] thick. */
    fun stroke(subpaths: List<List<Point>>, width: Double) {
        subpaths.forEach { points ->
            points.zipWithNext().forEach { (a, b) -> lines += DrawnLine(a.x, a.y, b.x, b.y, width) }
        }
    }

    /** A filled path, its subpaths flattened: a thin rectangle is a line, anything else a shape. */
    fun fill(subpaths: List<List<Point>>, curved: Boolean) {
        subpaths.filter { it.size >= 3 }.forEach { points ->
            val shape = DrawnShape(points, curved)
            shape.asLine()?.let { lines += it } ?: run { shapes += shape }
        }
    }

    /** The page, each glyph named by [name] (null: unnamed). */
    fun build(name: (DrawnGlyph) -> String? = { it.name }): PageDrawing =
        PageDrawing(width, height, glyphs.map { g -> name(g)?.let { g.copy(name = it) } ?: g }, lines, shapes, texts)
}

/**
 * Flattens a path given as moves, lines, cubic curves and closes (points already on the page) into
 * subpaths of points, as [PageBuilder] takes them. [curved] tells whether it had curves.
 */
class PathFlattener {
    private val result = mutableListOf<List<Point>>()
    private var current = mutableListOf<Point>()

    var curved = false
        private set

    fun moveTo(x: Double, y: Double) {
        if (current.size > 1) result += current
        current = mutableListOf(Point(x, y))
    }

    fun lineTo(x: Double, y: Double) {
        current += Point(x, y)
    }

    /** A cubic curve from the current point, cut in pieces about a point long. */
    fun curveTo(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        curved = true
        val start = current.lastOrNull() ?: Point(x1, y1)
        val length = hypot(x1 - start.x, y1 - start.y) + hypot(x2 - x1, y2 - y1) + hypot(x3 - x2, y3 - y2)
        val pieces = ceil(length / PIECE).toInt().coerceIn(2, 64)
        for (i in 1..pieces) {
            val t = i.toDouble() / pieces
            val u = 1 - t
            val a = u * u * u
            val b = 3 * u * u * t
            val c = 3 * u * t * t
            val d = t * t * t
            current += Point(a * start.x + b * x1 + c * x2 + d * x3, a * start.y + b * y1 + c * y2 + d * y3)
        }
    }

    fun closePath() {
        current.firstOrNull()?.let { current += it }
    }

    fun subpaths(): List<List<Point>> = if (current.size > 1) result + listOf(current.toList()) else result.toList()

    private companion object {
        /** About a point per piece: a slur's flattened outline stays well within a tenth of a staff space. */
        const val PIECE = 1.0
    }
}
