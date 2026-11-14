package com.musigatto.fosscore.pdfscore

import kotlin.math.max

/**
 * Names the music symbols a page draws as filled outlines: a score printed to PDF without its fonts
 * ("Microsoft Print to PDF" does), whose heads, clefs and rests are paths with no name. Each fill
 * (all its outlines: a half note's head is its outline and its hole) is compared with the reference
 * fonts' symbols ([SymbolClassifier]) at the scale of the staff nearest to it. A fill named becomes
 * a glyph; the others stay shapes (beams, slurs, ties, letters).
 */
class OutlineSymbols(private val classifier: SymbolClassifier) {
    fun name(page: PageDrawing): PageDrawing {
        // a page whose music font names its symbols draws none as outlines: its curves are ties and slurs
        if (page.shapes.isEmpty() || page.glyphs.any { it.name != null }) return page
        val staves = Staves.find(page).flatMap { it.staves }
        if (staves.isEmpty()) return page
        val glyphs = mutableListOf<DrawnGlyph>()
        val kept = mutableListOf<DrawnShape>()
        // a shape whose fill is not known is a fill of its own
        val fills = page.shapes.withIndex().groupBy { (i, shape) -> if (shape.fill < 0) -1 - i else shape.fill }.values.map { it.map { s -> s.value } }
        for (fill in fills) {
            val box = Box(fill.minOf { it.left }, fill.minOf { it.top }, fill.maxOf { it.right }, fill.maxOf { it.bottom })
            val staff = staves.minBy { distance(box.centerY, it) }
            val space = staff.space
            // a four-cornered fill is a beam (the rhythm counts it, whatever symbol it looks like),
            // unless it is a whole or half rest: upright, not long, hanging from a line or sitting on one
            val corners = fill.singleOrNull()?.corners()
            val beam = corners?.size == 4 && !(box.width <= BEAM * space && upright(corners) && (onLine(box.top, staff) || onLine(box.bottom, staff)))
            val match = if (!beam && distance(box.centerY, staff) <= REACH * space && max(box.width, box.height) <= LARGEST * space) {
                classifier.name(fill.map { shape -> shape.points.map { Point(it.x / space, it.y / space) } })
                    ?.takeIf { named(it.name) && (!it.name.startsWith("timeSig") || distance(box.centerY, staff) == 0.0) }
            } else {
                null
            }
            match?.let { glyphs += DrawnGlyph(it.name, box, confidence = it.score) } ?: kept.addAll(fill)
        }
        return PageDrawing(page.width, page.height, page.glyphs + glyphs, page.lines, kept, page.texts)
    }

    private companion object {
        /** How far from its staff (in spaces) a symbol may be: a note on ledger lines, a dynamic under it. */
        const val REACH = 8.0

        /** The tallest or widest symbol named, in spaces (a clef is about 7 high): a slur is longer. */
        const val LARGEST = 9.0

        /** A four-cornered fill wider than this (in spaces) is a beam: a whole rest is about 1.2 wide. */
        const val BEAM = 1.6

        /**
         * The symbols named by their look: those no letter or digit looks like. Text drawn as
         * outlines (measure numbers, lyrics, "rit.") would otherwise read as octave lines, dynamics,
         * ornaments. A time signature's digits are named inside the staff only.
         */
        private val NAMED = listOf("notehead", "accidental", "rest", "flag", "augmentationDot", "timeSig")

        fun named(name: String): Boolean = NAMED.any { name.startsWith(it) } || name.contains("Clef")

        /** Whether a four-cornered outline's sides are level and upright (a rest, not a slanting beam). */
        fun upright(corners: List<Point>): Boolean =
            (corners + corners[0]).zipWithNext().all { (a, b) -> kotlin.math.abs(a.x - b.x) < 0.05 || kotlin.math.abs(a.y - b.y) < 0.05 }

        /** Whether [y] is on a line of the staff, or where a ledger line would be. */
        fun onLine(y: Double, staff: Staff): Boolean {
            val halfSpaces = (staff.bottom - y) / (staff.space / 2)
            val step = Math.round(halfSpaces)
            return step % 2 == 0L && kotlin.math.abs(halfSpaces - step) < 0.3
        }

        /** How far [y] is from the staff's lines: 0 between them. */
        fun distance(y: Double, staff: Staff): Double = max(0.0, max(staff.top - y, y - staff.bottom))
    }
}
