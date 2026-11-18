package com.musigatto.fosscore.pdfscore

import kotlin.math.max
import kotlin.math.min

/**
 * The text a page printed without its fonts draws as outlines, which the music needs read: the
 * number of a metronome mark ("♩ = 70": the note is named by [OutlineSymbols], the "= 70" is an
 * outline of no symbol). Its letters are not read here: [spots] says where it is, whoever has the
 * page's pixels reads them (an OCR), and [withTexts] puts what was read back as the page's text.
 */
object OutlineText {
    /** Where the text to read is on [page] (its outlines named), left to right. */
    fun spots(page: PageDrawing): List<Box> {
        val staves = Staves.find(page).flatMap { it.staves }
        if (staves.isEmpty()) return emptyList()
        val boxes = page.shapes.withIndex().groupBy { (i, shape) -> if (shape.fill < 0) -1 - i else shape.fill }.values
            .map { fill -> fill.map { it.value.box }.reduce(::union) }
        return page.glyphs.filter { it.name?.startsWith(METRONOME_NOTE) == true }.mapNotNull { note ->
            val space = staves.minBy { kotlin.math.abs(it.top - note.box.bottom) }.space
            // the outlines on the note's line, one after the other from right after it
            var right = note.box.right
            val line = mutableListOf<Box>()
            for (box in boxes.filter { it.centerY in note.box.top..note.box.bottom + 0.5 * space && it.left > note.box.centerX }.sortedBy { it.left }) {
                if (box.left - right > GAP * space) break
                if (box.height > TALLEST * space) continue
                line += box
                right = max(right, box.right)
            }
            line.takeIf { it.isNotEmpty() }?.reduce(::union)
        }.distinct().sortedBy { it.left }
    }

    /** [page] with the [read] text of each of its [spots] as text, written on the spot's bottom line. */
    fun withTexts(page: PageDrawing, read: Map<Box, String>): PageDrawing = PageDrawing(
        page.width, page.height, page.glyphs, page.lines, page.shapes,
        page.texts + read.map { (box, text) -> DrawnText(text, FONT, box.left, box.bottom, box.height) },
    )

    /** The font of a text read from the pixels. */
    const val FONT = "ocr"

    private const val METRONOME_NOTE = "metNote"

    // the widest gap between two outlines of one text, and the tallest of its outlines, in spaces
    private const val GAP = 2.0
    private const val TALLEST = 4.0

    private fun union(a: Box, b: Box) = Box(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}
