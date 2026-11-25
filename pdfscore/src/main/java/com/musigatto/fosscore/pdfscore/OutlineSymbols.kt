package com.musigatto.fosscore.pdfscore

import kotlin.math.abs
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
        val boxes = fills.map { fill -> Box(fill.minOf { it.left }, fill.minOf { it.top }, fill.maxOf { it.right }, fill.maxOf { it.bottom }) }
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
                    // a metronome mark's note and dot, over the staff only (where the tempo is written)
                    ?.takeIf { !it.name.startsWith("met") || box.bottom < staff.top }
                    // a jump's sign must look like one: a false segno makes the music jump
                    ?.takeIf { !(it.name == "segno" || it.name.startsWith("coda")) || it.score >= JUMP_SIGN || (it.score >= DRAWN_SIGN && fill.size >= SIGN_OUTLINES) }
                    // and a fermata: a tie's arc looks a little like one (0.6, a fermata 0.96 and over)
                    ?.takeIf { !it.name.startsWith("fermata") || it.score >= JUMP_SIGN }
                    // so must a clef: a false one changes the pitch of every note after it
                    ?.takeIf { !it.name.contains("Clef") || it.score >= CLEF_SIGN }
                    // a tuplet's number out of the staff only (inside, a digit is a time signature's)
                    ?.takeIf { !it.name.startsWith("tuplet") || distance(box.centerY, staff) > 0.0 }
                    // a half or whole rest out of the staff hangs from a ledger line, or sits on one
                    ?.takeIf { it.name !in LEDGER_RESTS || inside(box, staff) || onLedger(box, page.lines, space) }
                    // held by a stem, the same rectangle is a beam's hook (a sixteenth after a dotted eighth)
                    ?.takeIf { it.name !in LEDGER_RESTS || !onStem(box, page.lines, space) }
            } else {
                null
            }
            // a small note (a cue line, "synthé"): its head looks like a head at a smaller scale only;
            // near the staff and held by a stem, so that no letter of the lyrics is one
            val cue = if (!beam && (match == null || match.score < CUE_OVER) && distance(box.centerY, staff) <= 2 * space &&
                max(box.width, box.height) <= 1.2 * space && onStem(box, page.lines, space, CUE * 2)
            ) {
                classifier.name(fill.map { shape -> shape.points.map { Point(it.x / (CUE * space), it.y / (CUE * space)) } })
                    ?.takeIf { it.name in CUE_HEADS && it.score > (match?.score ?: 0.0) }
            } else {
                null
            }
            // a metronome mark's note drawn as a head and a stem line ("♩ = 100"): well over the
            // staff, on a stem, on no ledger line, with "=" right after it
            val overStaff = !beam && box.bottom < staff.top - MET_HEIGHT * space && !onLedger(box, page.lines, space) &&
                equalAfter(box, boxes, page.lines, space)
            fun scaled(scale: Double) = fill.map { shape -> shape.points.map { Point(it.x / (scale * space), it.y / (scale * space)) } }
            val met = when {
                // the head alone, its stem a line
                overStaff && max(box.width, box.height) <= 1.2 * space && onStem(box, page.lines, space, CUE * 2) ->
                    listOf(1.0, CUE).mapNotNull { classifier.name(scaled(it)) }
                        .filter { it.name in CUE_HEADS }.maxByOrNull { it.score }
                        ?.let { SymbolMatch(if (it.name == "noteheadHalf") "metNoteHalfUp" else "metNoteQuarterUp", it.score) }
                // the note in one piece, often smaller than the font's: compared with the metronome notes only
                overStaff && match?.name?.startsWith("met") != true && box.height in 1.5 * space..4.0 * space && box.width <= 1.5 * space ->
                    MET_SCALES.flatMap { classifier.rank(scaled(it)) }
                        .filter { it.name.startsWith("metNote") && !it.name.contains("Dot") }.maxByOrNull { it.score }
                        ?.takeIf { it.score >= MET_SCORE }
                else -> null
            }
            (met ?: cue ?: match)?.let { glyphs += DrawnGlyph(it.name, box, confidence = it.score) } ?: kept.addAll(fill)
        }
        return PageDrawing(page.width, page.height, page.glyphs + glyphs, page.lines, kept, page.texts)
    }

    private companion object {
        /** How far from its staff (in spaces) a symbol may be: a note on ledger lines, a dynamic under it. */
        const val REACH = 8.0

        /** The tallest or widest symbol named, in spaces (a clef is about 7 high): a slur is longer. */
        const val LARGEST = 9.0

        /** The least score a segno or coda named by its look needs (real ones scored 0.82-0.91, a false one 0.71). */
        const val JUMP_SIGN = 0.8

        /**
         * Less is enough for a sign drawn in its parts, as a segno (its S, its two dots) or a coda
         * (its ring, its cross): at least [SIGN_OUTLINES] outlines. Pas de boogie woogie's segnos
         * scored 0.785 with three, its "al Coda" sign 0.747 with five; the false signs read so far
         * scored 0.71 or less.
         */
        const val DRAWN_SIGN = 0.74
        const val SIGN_OUTLINES = 3

        /** The least score a clef named by its look needs (real ones scored 0.91-0.97, a false bass clef change 0.57). */
        const val CLEF_SIGN = 0.75

        /** The size of a small note's head against a normal one's (cue notes are printed at 60 to 75 %). */
        const val CUE = 0.7

        /** A fill named less surely than this may be a small note's head. */
        const val CUE_OVER = 0.8

        /** How far over the staff (in spaces) a metronome mark's note is at least: a high note is on ledger lines nearer. */
        const val MET_HEIGHT = 2.5

        /** The scales a metronome mark's note in one piece is compared at: printed from 60 % of the font's to its size. */
        val MET_SCALES = listOf(1.0, 0.8, 0.65)

        /** The least score a metronome mark's note in one piece needs. */
        const val MET_SCORE = 0.6

        /**
         * Whether an "=" follows [box] on its line, within three spaces: a fill wider than twice its
         * height, or two short level lines one over the other.
         */
        fun equalAfter(box: Box, boxes: List<Box>, lines: List<DrawnLine>, space: Double): Boolean {
            fun after(left: Double, y: Double) = left > box.right && left - box.right < 3 * space && y in box.top - 0.5 * space..box.bottom + 0.5 * space
            if (boxes.any { after(it.left, it.centerY) && it.width > 2 * it.height && it.height < space }) return true
            val bars = lines.filter { it.isHorizontal && it.length < 2 * space && after(minOf(it.x1, it.x2), it.y1) }
            return bars.any { a -> bars.any { b -> b !== a && abs(a.y1 - b.y1) in 0.2 * space..space && abs(a.x1 - b.x1) < 0.3 * space } }
        }

        /** What a small note's fill may be named. */
        val CUE_HEADS = setOf("noteheadBlack", "noteheadHalf")

        /** A four-cornered fill wider than this (in spaces) is a beam: a whole rest is about 1.2 wide. */
        const val BEAM = 1.6

        /**
         * The symbols named by their look: those no letter or digit looks like. Text drawn as
         * outlines (measure numbers, lyrics, "rit.") would otherwise read as octave lines, dynamics,
         * ornaments; a fermata (an arc over a dot) looks like none. A time signature's digits are named inside the staff only; a metronome mark's
         * note and dot ("♩. = 60") over it only; a tuplet's number out of it only (it joins the
         * notes of a beam or a bracket, which a measure number or a fingering does not).
         */
        private val NAMED = listOf("notehead", "accidental", "rest", "flag", "augmentationDot", "timeSig", "segno", "coda", "metNote", "metAugmentationDot", "tuplet", "fermata")

        fun named(name: String): Boolean = NAMED.any { name.startsWith(it) } || name.contains("Clef")

        /** Whether a four-cornered outline's sides are level and upright (a rest, not a slanting beam). */
        fun upright(corners: List<Point>): Boolean =
            (corners + corners[0]).zipWithNext().all { (a, b) -> kotlin.math.abs(a.x - b.x) < 0.05 || kotlin.math.abs(a.y - b.y) < 0.05 }

        /** The rests drawn as a rectangle against a line: out of the staff, against a ledger line (a letter of the lyrics looks like a breve rest). */
        val LEDGER_RESTS = setOf("restWhole", "restHalf", "restDoubleWhole")

        /** Whether [box] is between the staff's top and bottom lines (a rest may touch them). */
        fun inside(box: Box, staff: Staff): Boolean =
            box.top >= staff.top - 0.1 * staff.space && box.bottom <= staff.bottom + 0.1 * staff.space

        /** Whether a ledger line (short, level) runs along the top or the bottom of [box]. */
        fun onLedger(box: Box, lines: List<DrawnLine>, space: Double): Boolean = lines.any { line ->
            line.isHorizontal && line.length < 4 * space &&
                minOf(line.x1, line.x2) <= box.right && maxOf(line.x1, line.x2) >= box.left &&
                (kotlin.math.abs(line.y1 - box.top) < 0.2 * space || kotlin.math.abs(line.y1 - box.bottom) < 0.2 * space)
        }

        /** Whether a stem (an upright line) runs along the left or right end of [box], across it. */
        fun onStem(box: Box, lines: List<DrawnLine>, space: Double, length: Double = 2.0): Boolean = lines.any { line ->
            line.isVertical && line.length >= length * space &&
                (kotlin.math.abs(line.x1 - box.left) < 0.2 * space || kotlin.math.abs(line.x1 - box.right) < 0.2 * space) &&
                minOf(line.y1, line.y2) <= box.bottom && maxOf(line.y1, line.y2) >= box.top
        }

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
