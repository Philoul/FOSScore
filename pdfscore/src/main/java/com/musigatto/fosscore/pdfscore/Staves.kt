package com.musigatto.fosscore.pdfscore

import kotlin.math.abs

/** A five-line staff: its lines' ys, top to bottom, and where it starts and ends on the page. */
data class Staff(val lineYs: List<Double>, val left: Double, val right: Double) {
    val top: Double get() = lineYs.first()
    val bottom: Double get() = lineYs.last()

    /** The distance between two lines, the unit every symbol's size is measured in. */
    val space: Double get() = (bottom - top) / (lineYs.size - 1)

    /**
     * Where [y] falls on the staff, in steps of half a space: 0 on the bottom line, 1 in the space
     * above it, 8 on the top line, negative below the staff and above 8 over it (ledger lines).
     */
    fun step(y: Double): Int = Math.round((bottom - y) / (space / 2)).toInt()
}

/**
 * Staves read together, top to bottom (a system). [braces] are the ranges of staff indices joined by
 * a brace: one instrument written on several staves, a piano's grand staff.
 */
data class StaffSystem(val staves: List<Staff>, val braces: List<IntRange>) {
    val top: Double get() = staves.first().top
    val bottom: Double get() = staves.last().bottom
}

/**
 * Finds a page's staves and groups them into systems, from its lines and symbols only, so a scan's
 * lines and a PDF's strokes are read the same way.
 */
object Staves {
    fun find(page: PageDrawing): List<StaffSystem> {
        val staves = staves(staffLines(page.lines))
        return systems(staves, page.lines, braces(page))
    }

    /**
     * Where braces may be: a font's brace glyph, or any curved shape far taller than wide (a brace
     * drawn as an outline, as MuseScore 3 does, or found in a scan); [holds] keeps those beside staves.
     */
    internal fun braces(page: PageDrawing): List<Box> =
        page.glyphs.filter { it.name == "brace" }.map { it.box } +
            page.shapes.filter { it.curved && it.box.height > 6 * it.box.width }.map { it.box }

    /**
     * The horizontal lines, one per y: segments on the same y that follow each other (a staff drawn
     * a measure at a time) are joined when they are as thick: a beam lying on a staff line stays apart.
     */
    internal fun staffLines(lines: List<DrawnLine>): List<DrawnLine> {
        val horizontal = lines.filter { it.isHorizontal && it.length > 0 }
            .map { if (it.x1 <= it.x2) it else DrawnLine(it.x2, it.y2, it.x1, it.y1, it.width) }
            .sortedWith(compareBy({ it.y1 }, { it.x1 }))
        val joined = mutableListOf<DrawnLine>()
        for (line in horizontal) {
            val i = joined.indexOfLast {
                abs(it.y1 - line.y1) < 0.3 && line.x1 <= it.x2 + JOIN_GAP &&
                    abs(it.width - line.width) < 0.2 * maxOf(it.width, line.width)
            }
            if (i >= 0) {
                val last = joined[i]
                joined[i] = last.copy(x2 = maxOf(last.x2, line.x2), y2 = last.y1)
            } else {
                joined += line
            }
        }
        return joined.sortedBy { it.y1 }
    }

    /**
     * Five lines one under the other, evenly spaced, of the same extent and thin beside their
     * spacing, make a staff. Other lines between them (a beam, a hairpin, a ledger line) are skipped.
     */
    internal fun staves(lines: List<DrawnLine>): List<Staff> {
        val long = lines.filter { it.length >= MIN_STAFF_LENGTH }
        val used = BooleanArray(long.size)
        val result = mutableListOf<Staff>()
        for (i in long.indices) {
            if (used[i]) continue
            val first = long[i]
            // The closest line below that can be the second one sets the spacing.
            val staff = (i + 1 until long.size).asSequence()
                .filter { !used[it] }
                .map { it to long[it].y1 - first.y1 }
                .takeWhile { it.second <= MAX_SPACE }
                .filter { it.second >= MIN_SPACE && alike(first, long[it.first], it.second) }
                .mapNotNull { (second, space) -> staffFrom(long, used, i, second, space) }
                .firstOrNull() ?: continue
            staff.forEach { used[it] = true }
            val five = staff.map { long[it] }
            result += Staff(five.map { it.y1 }, five.minOf { it.x1 }, five.maxOf { it.x2 })
        }
        return result
    }

    /** The indices of the five lines starting with [first] and [second], or null when they are not there. */
    private fun staffFrom(lines: List<DrawnLine>, used: BooleanArray, first: Int, second: Int, space: Double): List<Int>? {
        val found = mutableListOf(first, second)
        for (k in 2..4) {
            val y = lines[first].y1 + k * space
            val next = lines.indices.firstOrNull {
                !used[it] && abs(lines[it].y1 - y) <= 0.15 * space && alike(lines[first], lines[it], space)
            } ?: return null
            found += next
        }
        return found
    }

    /** Two lines of one staff: same start and end, both thin beside the spacing. */
    private fun alike(a: DrawnLine, b: DrawnLine, space: Double): Boolean =
        abs(a.x1 - b.x1) < 2 * space && abs(a.x2 - b.x2) < 2 * space &&
            a.width < MAX_THICKNESS * space && b.width < MAX_THICKNESS * space

    /**
     * Neighbouring staves are in one system when a vertical line runs from one to the other (the
     * line at the start of a system, or barlines drawn through both) or a brace holds them.
     */
    internal fun systems(staves: List<Staff>, lines: List<DrawnLine>, braces: List<Box>): List<StaffSystem> {
        val verticals = lines.filter { it.isVertical }.map { Triple(it.x1, minOf(it.y1, it.y2), maxOf(it.y1, it.y2)) }
        val sorted = staves.sortedBy { it.top }
        val groups = mutableListOf<MutableList<Staff>>()
        for (staff in sorted) {
            val last = groups.lastOrNull()?.last()
            val joined = last != null && (
                verticals.any { (x, top, bottom) ->
                    x >= maxOf(last.left, staff.left) - last.space && x <= minOf(last.right, staff.right) + last.space &&
                        top <= last.bottom + 0.5 * last.space && bottom >= staff.top - 0.5 * last.space
                } || braces.any { holds(it, last) && holds(it, staff) }
                )
            if (joined) groups.last() += staff else groups += mutableListOf(staff)
        }
        return groups.map { group ->
            val braced = braces.mapNotNull { brace ->
                val inside = group.indices.filter { holds(brace, group[it]) }
                if (inside.size >= 2) inside.first()..inside.last() else null
            }.distinct()
            StaffSystem(group, braced)
        }
    }

    /** A brace holds a staff when it stands left of it and covers most of its height. */
    private fun holds(brace: Box, staff: Staff): Boolean =
        brace.right <= staff.left + staff.space &&
            brace.right >= staff.left - 4 * staff.space &&
            brace.top <= staff.top + staff.space && brace.bottom >= staff.bottom - staff.space

    private const val MIN_SPACE = 2.0
    private const val MAX_SPACE = 40.0

    /** A staff line is far thinner than a space; a beam is half a space thick. */
    private const val MAX_THICKNESS = 0.3

    /** Segments of one line further apart than this are two lines. */
    private const val JOIN_GAP = 2.0

    /** Shorter lines are ledger lines, hairpins or underlines, not staff lines. */
    private const val MIN_STAFF_LENGTH = 40.0
}
