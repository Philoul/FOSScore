package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.Pitch
import kotlin.math.abs

/** A notehead read on a staff: where it sits ([step], see [Staff.step]), in which measure, and its pitch. */
data class StaffNote(val head: DrawnGlyph, val step: Int, val measure: Int, val pitch: Pitch?)

/**
 * What one staff of a system says: its clef and key at the start, the barlines' xs, and its notes
 * from left to right. [pitch][StaffNote.pitch] is null while no clef is known. [symbols] are every
 * named symbol that belongs to the staff, left to right (what the rhythm is read from).
 */
data class StaffReading(
    val staff: Staff,
    val clef: Clef?,
    val key: Key,
    val barlines: List<Double>,
    val notes: List<StaffNote>,
    val symbols: List<DrawnGlyph> = emptyList(),
)

/**
 * Reads the notes' pitches on each staff, from the symbols' names and boxes only (not a font's
 * metrics), so that symbols found in a scan are read by the same code.
 */
object StaffNotes {
    fun read(page: PageDrawing, systems: List<StaffSystem>): List<List<StaffReading>> {
        // Each symbol goes to the closest staff of the whole page: a note on ledger lines between two
        // systems is within reach of both.
        val staves = systems.flatMap { system -> system.staves.map { system to it } }
        val assigned = page.glyphs.filter { it.name != null }
            .mapNotNull { glyph -> staffOf(glyph.box, staves.map { it.second })?.let { glyph to it } }
            .toMap(LinkedHashMap())
        keepChordsTogether(assigned, staves, page.lines)
        // A tuplet's number goes with the notes nearest to it, stems included (it stands at their
        // end): high over a melody's stems, it may be closer to the staff above.
        val notes = assigned.entries.filter { isHead(it.key.name) }.map { (head, staff) -> withStem(head, page.lines) to staff }
        for (number in tupletNumbers(page.texts)) {
            notes.minByOrNull { (box, _) -> distance(box, number.box.centerX, number.box.centerY) }?.let { assigned[number] = it.second }
        }
        val byStaff = assigned.entries.groupBy({ it.value }, { it.key })
        return systems.map { system ->
            system.staves.map { staff ->
                val index = staves.indexOfFirst { it.second === staff }
                read(staff, system, byStaff[index].orEmpty().sortedBy { it.box.left }, page.lines)
            }
        }
    }

    /**
     * The notes on one stem are one chord, on one staff: a bass chord's top note over the bass staff
     * may be closer to the treble staff. That staff is the one a note of the chord sits in; else the
     * one the stem runs into (a note between the staves points its stem to its own); else the one
     * closest to a note of the chord.
     */
    private fun keepChordsTogether(
        assigned: MutableMap<DrawnGlyph, Int>,
        staves: List<Pair<StaffSystem, Staff>>,
        lines: List<DrawnLine>,
    ) {
        val heads = assigned.keys.filter { isHead(it.name) }
        for (stem in lines.filter { it.isVertical }) {
            val top = minOf(stem.y1, stem.y2)
            val bottom = maxOf(stem.y1, stem.y2)
            val chord = heads.filter { head ->
                val space = assigned[head]?.let { staves[it].second.space } ?: return@filter false
                (abs(head.box.left - stem.x1) < 0.3 * space || abs(head.box.right - stem.x1) < 0.3 * space) &&
                    head.box.centerY >= top - 0.6 * space && head.box.centerY <= bottom + 0.6 * space
            }
            val system = chord.mapNotNull { head -> assigned[head]?.let { staves[it].first } }.distinct().singleOrNull() ?: continue
            val ofSystem = staves.indices.filter { staves[it].first === system }
            val headsY = chord.map { it.box.centerY }.average()
            val farEnd = if (headsY - top < bottom - headsY) bottom else top
            val target = chord.firstOrNull { head -> assigned[head]?.let { distance(head.box, staves[it].second) == 0.0 } == true }
                ?.let { assigned[it] }
                ?: ofSystem.firstOrNull { index ->
                    val staff = staves[index].second
                    farEnd >= staff.top - 0.3 * staff.space && farEnd <= staff.bottom + 0.3 * staff.space
                }
                ?: chord.minByOrNull { head -> assigned[head]?.let { distance(head.box, staves[it].second) } ?: Double.MAX_VALUE }
                    ?.let { assigned[it] }
                ?: continue
            chord.forEach { assigned[it] = target }
        }
    }

    /** A head's box with its stem's, when a stem runs from its side. */
    private fun withStem(head: DrawnGlyph, lines: List<DrawnLine>): Box {
        val box = head.box
        val space = box.height
        return lines.firstOrNull { line ->
            line.isVertical && line.length >= 1.5 * space &&
                minOf(abs(line.x1 - box.left), abs(line.x1 - box.right)) < 0.35 * space &&
                box.centerY >= minOf(line.y1, line.y2) - 0.6 * space && box.centerY <= maxOf(line.y1, line.y2) + 0.6 * space
        }?.let { Box(minOf(box.left, it.x1), minOf(box.top, it.y1, it.y2), maxOf(box.right, it.x1), maxOf(box.bottom, it.y1, it.y2)) } ?: box
    }

    /** How far a point is from a box (0 inside it). */
    private fun distance(box: Box, x: Double, y: Double): Double =
        kotlin.math.hypot(maxOf(box.left - x, 0.0, x - box.right), maxOf(box.top - y, 0.0, y - box.bottom))

    /** How far a symbol is out of a staff, in spaces (0 within its lines). */
    private fun distance(box: Box, staff: Staff): Double {
        val y = box.centerY
        return when {
            y < staff.top -> staff.top - y
            y > staff.bottom -> y - staff.bottom
            else -> 0.0
        } / staff.space
    }

    /** The index in [staves] of the staff a symbol belongs to: the closest one, within reach of ledger lines. */
    internal fun staffOf(box: Box, staves: List<Staff>): Int? =
        staves.withIndex()
            .filter { (_, staff) -> box.right >= staff.left - 3 * staff.space && box.left <= staff.right + staff.space }
            .map { (index, staff) -> index to distance(box, staff) }
            .filter { it.second <= MAX_LEDGER_SPACES }
            .minByOrNull { it.second }?.first

    private fun read(staff: Staff, system: StaffSystem, symbols: List<DrawnGlyph>, lines: List<DrawnLine>): StaffReading {
        val space = staff.space
        val heads = symbols.filter { isHead(it.name) }
        val clefs = symbols.mapNotNull { glyph ->
            clefOf(glyph.name.orEmpty())?.let { (sign, shift) ->
                val step = staff.step(glyph.box.top + originFraction(glyph.name.orEmpty()) * glyph.box.height)
                glyph to Clef(sign, (Math.round(step / 2.0).toInt() + 1).coerceIn(1, 5), shift)
            }
        }
        val barlines = barlines(staff, system, lines, heads)
        val firstClef = clefs.firstOrNull()
        val keyAccidentals = keyAccidentals(symbols, firstClef?.first, heads, barlines, space)
        val key = Key(keyAccidentals.sumOf { if (it.name == "accidentalSharp") 1 else -1 })

        // Each accidental before a note belongs to the nearest note to its right on the same step.
        val accidentals = symbols.filter { ALTERS.containsKey(it.name.orEmpty()) && it !in keyAccidentals }
        val written = mutableMapOf<DrawnGlyph, Int>()
        for (accidental in accidentals) {
            val step = staff.step(accidental.box.top + originFraction(accidental.name.orEmpty()) * accidental.box.height)
            heads.filter {
                staff.step(it.box.centerY) == step &&
                    it.box.left >= accidental.box.right - 0.3 * space && it.box.left - accidental.box.right <= 4 * space
            }.minByOrNull { it.box.left }?.let { head -> written[head] = ALTERS.getValue(accidental.name.orEmpty()) }
        }

        val ottavas = ottavas(staff, symbols, lines)

        // An accidental holds for the rest of its measure, on its line or space.
        val inMeasure = mutableMapOf<Int, Int>()
        var measure = 0
        val notes = heads.map { head ->
            val headMeasure = barlines.count { it < head.box.centerX }
            if (headMeasure != measure) {
                inMeasure.clear()
                measure = headMeasure
            }
            val step = staff.step(head.box.centerY)
            val clef = clefs.lastOrNull { it.first.box.left < head.box.left }?.second ?: firstClef?.second
            val pitch = clef?.let {
                val diatonic = it.diatonicAt(step)
                val alter = written[head]?.also { alter -> inMeasure[diatonic] = alter }
                    ?: inMeasure[diatonic]
                    ?: if (it.sign == Clef.Sign.PERCUSSION) 0 else key.alter(Math.floorMod(diatonic, 7))
                val octaves = ottavas.firstOrNull { (range, _) -> head.box.centerX in range }?.second ?: 0
                Pitch.ofDiatonic(diatonic + 7 * octaves, alter)
            }
            StaffNote(head, step, measure, pitch)
        }
        return StaffReading(staff, firstClef?.second, key, barlines, notes, symbols)
    }

    /**
     * The octave lines of a staff: from their "8" (or "15") to the end of the dashed line after it,
     * with the octaves they move the notes under them.
     */
    private fun ottavas(staff: Staff, symbols: List<DrawnGlyph>, lines: List<DrawnLine>): List<Pair<ClosedFloatingPointRange<Double>, Int>> {
        val space = staff.space
        return symbols.mapNotNull { glyph ->
            val name = glyph.name.orEmpty()
            val octaves = when {
                name.startsWith("ottava") -> 1
                name.startsWith("quindicesima") -> 2
                name.startsWith("ventiduesima") -> 3
                else -> return@mapNotNull null
            }
            val down = name.contains("Bassa") || (!name.contains("Alta") && glyph.box.centerY > staff.bottom)
            // The dashes (or one dashed stroke) run on from the number, each close after the last.
            var end = glyph.box.right
            lines.filter {
                it.isHorizontal && minOf(it.y1, it.y2) >= glyph.box.top - 0.5 * space &&
                    maxOf(it.y1, it.y2) <= glyph.box.bottom + 0.5 * space && minOf(it.x1, it.x2) >= glyph.box.left
            }.sortedBy { minOf(it.x1, it.x2) }.forEach { dash ->
                if (minOf(dash.x1, dash.x2) - end <= 2 * space) end = maxOf(end, maxOf(dash.x1, dash.x2))
            }
            (glyph.box.left - space..maxOf(end, glyph.box.right + 2 * space)) to (if (down) -octaves else octaves)
        }
    }

    /**
     * The sharps or flats right after the first clef, one beside the other, before any note or
     * barline: the key signature.
     */
    private fun keyAccidentals(
        symbols: List<DrawnGlyph>,
        clef: DrawnGlyph?,
        heads: List<DrawnGlyph>,
        barlines: List<Double>,
        space: Double,
    ): List<DrawnGlyph> {
        if (clef == null) return emptyList()
        val end = minOf(heads.minOfOrNull { it.box.left } ?: Double.MAX_VALUE, barlines.minOrNull() ?: Double.MAX_VALUE)
        val result = mutableListOf<DrawnGlyph>()
        var right = clef.box.right
        for (glyph in symbols.filter { it.box.left > clef.box.left && it.box.right < end }) {
            if (glyph.box.left - right > 1.5 * space) break
            if (glyph.name != "accidentalSharp" && glyph.name != "accidentalFlat") break
            if (result.isNotEmpty() && result[0].name != glyph.name) break
            result += glyph
            right = glyph.box.right
        }
        return result
    }

    /**
     * The barlines' xs: vertical lines running from the staff's top line to its bottom one (or
     * through it, across the system), but not the system's opening line nor a note's stem.
     */
    private fun barlines(staff: Staff, system: StaffSystem, lines: List<DrawnLine>, heads: List<DrawnGlyph>): List<Double> {
        val space = staff.space
        val xs = lines.filter { it.isVertical }
            .filter { line ->
                val top = minOf(line.y1, line.y2)
                val bottom = maxOf(line.y1, line.y2)
                top <= staff.top + 0.3 * space && bottom >= staff.bottom - 0.3 * space &&
                    top >= system.top - 0.3 * space && bottom <= system.bottom + 0.3 * space &&
                    line.x1 > staff.left + 0.5 * space && line.x1 <= staff.right + 0.5 * space &&
                    heads.none { abs(it.box.left - line.x1) < 0.3 * space || abs(it.box.right - line.x1) < 0.3 * space }
            }
            .map { it.x1 }
            .sorted()
        // A double or final barline is one barline.
        return xs.fold(mutableListOf()) { kept, x ->
            if (kept.isEmpty() || x - kept.last() > space) kept += x
            kept
        }
    }

    /**
     * A tuplet's number written in a text font (MuseScore's italic "3"), as the symbol a music font
     * would draw ("tuplet3"), placed by its box: it then goes to its staff like any symbol. Whether it
     * is a tuplet (and not a fingering) is for [Rhythms] to say.
     */
    private fun tupletNumbers(texts: List<DrawnText>): List<DrawnGlyph> = texts.mapNotNull { text ->
        text.text.trim().takeIf { it.length == 1 && it[0] in '2'..'9' }?.let { digit ->
            DrawnGlyph("tuplet$digit", Box(text.x, text.y - 0.7 * text.size, text.x + 0.55 * text.size, text.y))
        }
    }

    /** A notehead, not the parenthesis drawn around one. */
    internal fun isHead(name: String?): Boolean =
        name != null && name.startsWith("notehead") && !name.startsWith("noteheadParenthesis")

    /** The clef a SMuFL name stands for, and the octaves it shifts; its line is read from where it sits. */
    private fun clefOf(name: String): Pair<Clef.Sign, Int>? {
        val base = name.removeSuffix("Change")
        val shift = when {
            base.endsWith("8vb") -> -1
            base.endsWith("8va") -> 1
            base.endsWith("15mb") -> -2
            base.endsWith("15ma") -> 2
            else -> 0
        }
        return when {
            base.startsWith("gClef") -> Clef.Sign.G to shift
            base.startsWith("fClef") -> Clef.Sign.F to shift
            base.startsWith("cClef") -> Clef.Sign.C to shift
            base.startsWith("unpitchedPercussionClef") -> Clef.Sign.PERCUSSION to 0
            else -> null
        }
    }

    /**
     * How far down its box a symbol's reference line is (where a clef's line or an accidental's step
     * is read), as a share of the box's height. Same for all SMuFL fonts within a few hundredths.
     */
    private fun originFraction(name: String): Double = when {
        name.startsWith("gClef") -> 0.64
        name.startsWith("fClef") -> 0.31
        name == "accidentalFlat" || name == "accidentalDoubleFlat" -> 0.72
        else -> 0.5
    }

    private val ALTERS = mapOf(
        "accidentalSharp" to 1,
        "accidentalFlat" to -1,
        "accidentalNatural" to 0,
        "accidentalDoubleSharp" to 2,
        "accidentalDoubleFlat" to -2,
    )

    /** How far from its staff (in spaces) a symbol may be and still belong to it. */
    private const val MAX_LEDGER_SPACES = 8.0
}
