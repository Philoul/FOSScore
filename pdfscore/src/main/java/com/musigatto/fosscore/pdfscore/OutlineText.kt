package com.musigatto.fosscore.pdfscore

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The text a page printed without its fonts draws as outlines, which the music needs read: the
 * number of a metronome mark ("♩ = 70": the note is named by [OutlineSymbols], the "= 70" is an
 * outline of no symbol), the numbers of the endings ("1. 2.", under their bracket) and the words
 * over the systems ("To Coda", "D.S. al Coda", "Rall."). Their letters are not read here: [spots]
 * says where they are, whoever has the page's pixels reads them (an OCR), and [withTexts] puts what
 * was read back as the page's text, from which the directions are read as from a PDF with fonts.
 */
object OutlineText {
    /** What a spot holds, so its reader knows what to expect. */
    enum class Kind { METRONOME, ENDING, WORDS }

    /** A piece of text to read: where it is, and what it is. */
    data class Spot(val box: Box, val kind: Kind)

    /** Where the text to read is on [page] (its outlines named), left to right within each kind. */
    fun spots(page: PageDrawing): List<Spot> {
        val systems = Staves.find(page)
        val staves = systems.flatMap { it.staves }
        if (staves.isEmpty()) return emptyList()
        val fills = page.shapes.withIndex().groupBy { (i, shape) -> if (shape.fill < 0) -1 - i else shape.fill }.values
        val boxes = fills.map { fill -> fill.map { it.value.box }.reduce(::union) }
        // how many outlines each fill has: a word has several (its letters, their holes), a slur one
        val outlines = fills.map { it.size }
        val metronome = page.glyphs.filter { it.name?.startsWith(METRONOME_NOTE) == true }.mapNotNull { note ->
            val space = staves.minBy { abs(it.top - note.box.bottom) }.space
            // the outlines on the note's line, one after the other from right after it
            var right = note.box.right
            val line = mutableListOf<Box>()
            // "=" may be two short lines: part of the mark, between the note and its number
            val bars = page.lines.filter { it.isHorizontal && it.length < 2 * space }
                .map { Box(minOf(it.x1, it.x2), it.y1 - it.width / 2, maxOf(it.x1, it.x2), it.y1 + it.width / 2) }
            for (box in (boxes + bars).filter { it.centerY in note.box.top..note.box.bottom + 0.5 * space && it.left > note.box.centerX }.sortedBy { it.left }) {
                if (box.left - right > GAP * space) break
                if (box.height > TALLEST * space) continue
                line += box
                right = max(right, box.right)
            }
            line.takeIf { it.isNotEmpty() }?.reduce(::union)
        }.distinct().sortedBy { it.left }.map { Spot(it, Kind.METRONOME) }
        val endings = systems.flatMap { system -> endings(page, system.staves.first(), boxes) }.map { Spot(it, Kind.ENDING) }
        val taken = (metronome + endings).map { it.box }
        val words = systems.flatMap { system ->
            val staff = system.staves.first()
            val space = staff.space
            // the outlines over the system: letters (not slurs, not wide lines), not already taken
            val letters = boxes.filterIndexed { i, box ->
                box.bottom <= staff.top && box.top >= staff.top - REACH * space &&
                    (box.width <= LETTER * space || outlines[i] >= 2) && box.height <= TALLEST * space && min(box.width, box.height) > 1.0 &&
                    taken.none { overlaps(it, box) }
            }
            // a row of several runs on one line is lyrics or chord symbols, not the words of a
            // direction; but words wider than a chord symbol in it may be one ("To Coda" at the end
            // of the chords' line, J'ai encore rêvé d'elle)
            // letters named as music symbols (the "o", "d", "a" of "To Coda") go with the word they
            // stand in, never making one alone (a tuplet's number)
            val named = page.glyphs.map { it.box }.filter { box ->
                box.bottom <= staff.top && box.top >= staff.top - REACH * space && box.height <= TALLEST * space && taken.none { overlaps(it, box) }
            }
            withNamedLetters(lines(letters, space), named, space).groupBy { run -> Math.round(run.bottom / (0.5 * space)) }.values
                .flatMap { row -> if (row.size < LYRICS_ROW) row else row.filter { it.width >= WIDE_WORDS * space } }
        }.map { Spot(it, Kind.WORDS) }
        return metronome + endings + words
    }

    /** [page] with the [read] text of each of its [spots] as text, written on the spot's bottom line. */
    fun withTexts(page: PageDrawing, read: Map<Box, String>): PageDrawing = PageDrawing(
        page.width, page.height, page.glyphs, page.lines, page.shapes,
        page.texts + read.map { (box, text) -> DrawnText(text, FONT, box.left, box.bottom, box.height) },
    )

    /** The font of a text read from the pixels. */
    const val FONT = "ocr"

    // the numbers under the endings' brackets over [staff]: as Directions.endings finds them
    private fun endings(page: PageDrawing, staff: Staff, boxes: List<Box>): List<Box> {
        val space = staff.space
        val strokes = Strokes.of(page)
        val hooks = strokes.filter { it.isVertical && it.length in 0.5 * space..3.0 * space }
        return strokes.filter { line ->
            line.isHorizontal && line.length >= 2 * space && line.y1 < staff.top - 0.5 * space && line.y1 >= staff.top - Directions.ENDING_REACH * space
        }.mapNotNull { line ->
            val left = min(line.x1, line.x2)
            val hooked = hooks.any { abs(it.x1 - left) < 0.3 * space && abs(min(it.y1, it.y2) - line.y1) <= 0.3 * space }
            if (!hooked) return@mapNotNull null
            // its digits and dot may have been named as symbols (a tuplet's 2, an augmentation dot:
            // Pas de boogie woogie's "2." was never read)
            val parts = boxes.filter { it.width > 0.4 * space } + page.glyphs.map { it.box }
            parts.filter { it.left >= left - 0.2 * space && it.left <= left + 3 * space && it.top > line.y1 && it.bottom <= line.y1 + 3 * space }
                .takeIf { it.isNotEmpty() }?.reduce(::union)
        }
    }

    // [runs] of text with the [named] boxes beside them on their line, close enough to be letters of theirs
    private fun withNamedLetters(runs: List<Box>, named: List<Box>, space: Double): List<Box> {
        val out = runs.toMutableList()
        var grew = true
        val left = named.toMutableList()
        while (grew) {
            grew = false
            for (box in left.toList()) {
                val i = out.indexOfFirst { run ->
                    min(run.bottom, box.bottom) - max(run.top, box.top) >= 0.5 * min(run.height, box.height) &&
                        box.left - run.right <= GAP * space && run.left - box.right <= GAP * space
                }
                if (i >= 0) {
                    out[i] = union(out[i], box)
                    left.remove(box)
                    grew = true
                }
            }
        }
        return out
    }

    // [letters] in lines of text: side by side, at one height, close enough to be one run
    private fun lines(letters: List<Box>, space: Double): List<Box> {
        val runs = mutableListOf<Box>()
        for (box in letters.sortedBy { it.left }) {
            val i = runs.indexOfFirst { run ->
                min(run.bottom, box.bottom) - max(run.top, box.top) >= 0.5 * min(run.height, box.height) && box.left - run.right <= GAP * space
            }
            if (i >= 0) runs[i] = union(runs[i], box) else runs += box
        }
        // a lone dot or dash is no text
        return runs.filter { it.height >= FLAT * space }
    }

    private fun overlaps(a: Box, b: Box) = min(a.right, b.right) > max(a.left, b.left) && min(a.bottom, b.bottom) > max(a.top, b.top)

    private const val METRONOME_NOTE = "metNote"

    // the widest gap between two outlines of one text, the tallest of its outlines, the widest of
    // its letters (a whole word may be one fill of several outlines), and how far over its system
    // it may be, in spaces
    private const val GAP = 2.0
    private const val TALLEST = 4.0
    private const val LETTER = 3.0
    private const val REACH = 6.0

    // this many runs of text on one line over a system are a line of lyrics
    private const val LYRICS_ROW = 4

    // how wide (in spaces) words are, in a row of lyrics or chord symbols, to be read all the same
    private const val WIDE_WORDS = 8.0

    // a run less high than this (spaces) is a dot, a dash, an "=" alone: no text. The Tab 3 read
    // dozens of "=" 0.66 space high a score, a third of a second each, at 0.6
    private const val FLAT = 0.8

    private fun union(a: Box, b: Box) = Box(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}
