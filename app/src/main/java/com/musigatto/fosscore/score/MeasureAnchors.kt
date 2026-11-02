package com.musigatto.fosscore.score

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.ui.viewer.Band
import com.musigatto.fosscore.ui.viewer.decodePoints
import com.musigatto.fosscore.ui.viewer.encodePoints
import kotlin.math.abs

/**
 * The page of a mark hung on a measure: a MusicXML score is laid out again with every screen and
 * music size, so its marks belong to no page. Their [Stamp.measure] and [Stamp.staff] say where
 * they are.
 */
const val ANCHORED_PAGE = -1

/**
 * One staff of one measure on a page, in page coordinates (0..1 of the page's width and height,
 * as stamps and strokes are), with its staff space across ([spaceX]) and down ([spaceY]). Its
 * music runs from [start] (the first note) to [right]. [measure] is the measure's place in the
 * file (1 = the first `<measure>` of a part, [PageGeometry.Measure.rank]), not the number the
 * score prints, which is free text; [staff] is the staff's number (1 = the top staff of the score).
 */
data class StaffSlot(
    val measure: Int,
    val staff: Int,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val spaceX: Float,
    val spaceY: Float,
    val start: Float = left,
)

/**
 * The margin of a line of music (0..1 of the page): left of [right], where its staves start, from
 * [top] to [bottom], its boundaries. The instrument names are there.
 */
data class LineMargin(val top: Float, val bottom: Float, val right: Float)

/**
 * Between the page the viewer edits in and the measures the marks of a MusicXML score hang on, so
 * they follow the music when it is laid out again (another screen, music size, transposition,
 * parts shown). A mark is kept in the same rows as on a PDF, with its [Stamp.measure] and
 * [Stamp.staff] and [ANCHORED_PAGE] for its page; its place is then in that measure:
 * - x: a share of the measure's music, from its first note to its right end (a measure that
 *   begins a line, after its clef and key, keeps its marks over the same notes);
 * - y: staff spaces below the top line of the staff;
 * - a stamp's size, a stroke's width: staff spaces;
 * - a stroke's points: the first is the top left corner of its box (x and y as above), the next
 *   ones are in staff spaces from that corner, so the stroke keeps its shape and grows with the
 *   music.
 * Pure functions: no Android, so they are tested on the JVM.
 */
object MeasureAnchors {

    /**
     * The staves of [page]'s measures in page coordinates; empty without a page height. Every
     * measure laid out from [com.musigatto.fosscore.musicxml.MusicXmlScore] has its place in the
     * file, whatever its number ("12a", "X1").
     */
    fun slotsOf(page: PageGeometry): List<StaffSlot> {
        if (page.width <= 0f || page.height <= 0f) return emptyList()
        return page.measures.flatMap { m ->
            val number = m.rank ?: return@flatMap emptyList()
            m.staves.map { s ->
                StaffSlot(
                    number, s.n, s.left / page.width, s.right / page.width, s.top / page.height,
                    s.bottom / page.height, s.space / page.width, s.space / page.height, s.start / page.width,
                )
            }
        }
    }

    // one line of music (a system): the staves of the measures side by side, from its top staff
    // line to its bottom one
    private class Line(val top: Float, val bottom: Float, val slots: List<StaffSlot>)

    private fun linesOf(slots: List<StaffSlot>): List<Line> = slots.groupBy { it.measure }.values
        .map { m -> Line(m.minOf { it.top }, m.maxOf { it.bottom }, m) }
        .sortedBy { it.top }
        .fold(emptyList()) { lines, m ->
            lines.lastOrNull()?.takeIf { m.top <= it.bottom }
                ?.let { last -> lines.dropLast(1) + Line(last.top, maxOf(last.bottom, m.bottom), last.slots + m.slots) }
                ?: (lines + m)
        }

    /**
     * Where a line of music hands over to the next, down the page (0..1): in the middle of the
     * widest of the page's [blanks] (paper with nothing drawn on it) between their staves, so the
     * lyrics, the notes above or below and the text around a line stay on its side; halfway
     * between the staves when there is none. A mark above a boundary belongs to the line above
     * it, below to the line below; the viewer draws them while annotating.
     */
    fun boundaries(slots: List<StaffSlot>, blanks: List<Band> = emptyList()): List<Float> =
        linesOf(slots).zipWithNext { a, b ->
            blanks.map { Band(maxOf(it.start, a.bottom), minOf(it.end, b.top)) }
                .filter { it.end > it.start }
                .maxByOrNull { it.end - it.start }
                ?.let { (it.start + it.end) / 2 }
                ?: ((a.bottom + b.top) / 2)
        }

    /**
     * The staff a mark at [at] belongs to: the line of music between the [boundaries] it is in,
     * one of its measures the mark is over, else the nearest across, then the nearest staff up or
     * down. Null on a page without measures, and in the margin of a line (its instrument names).
     */
    fun slotAt(at: Offset, slots: List<StaffSlot>, boundaries: List<Float> = boundaries(slots)): StaffSlot? {
        val lines = linesOf(slots)
        val line = lines.getOrNull(boundaries.count { at.y > it }) ?: return null
        if (at.x < line.slots.minOf { it.left }) return null
        val across = line.slots.filter { at.x >= it.left && at.x <= it.right }.ifEmpty {
            val nearest = line.slots.minOf { minOf(abs(at.x - it.left), abs(at.x - it.right)) }
            line.slots.filter { minOf(abs(at.x - it.left), abs(at.x - it.right)) == nearest }
        }
        return across.minByOrNull { if (at.y < it.top) it.top - at.y else if (at.y > it.bottom) at.y - it.bottom else 0f }
    }

    /**
     * The margins of the page's lines of music. Lines are laid out again with every screen, so a
     * mark in a margin would end up next to other measures: no mark goes there.
     */
    fun margins(slots: List<StaffSlot>, boundaries: List<Float> = boundaries(slots)): List<LineMargin> =
        linesOf(slots).mapIndexed { i, line ->
            LineMargin(boundaries.getOrElse(i - 1) { 0f }, boundaries.getOrElse(i) { 1f }, line.slots.minOf { it.left })
        }

    /** Whether [at] is in one of the [margins], where no mark goes. */
    fun inMargin(at: Offset, margins: List<LineMargin>): Boolean =
        margins.any { at.y >= it.top && at.y <= it.bottom && at.x < it.right }

    private fun xIn(slot: StaffSlot, x: Float): Float = (x - slot.start) / (slot.right - slot.start)
    private fun xOf(slot: StaffSlot, share: Float): Float = slot.start + share * (slot.right - slot.start)

    /**
     * [stroke], drawn on a page with these [slots] (its points 0..1 of the page), hung on the staff
     * its box is over; null without points, or off the music.
     */
    fun anchor(stroke: Stroke, slots: List<StaffSlot>, boundaries: List<Float> = boundaries(slots)): Stroke? {
        val points = decodePoints(stroke.points)
        if (points.isEmpty()) return null
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val centre = Offset((left + points.maxOf { it.x }) / 2, (top + points.maxOf { it.y }) / 2)
        val slot = slotAt(centre, slots, boundaries) ?: return null
        val corner = Offset(xIn(slot, left), (top - slot.top) / slot.spaceY)
        val shape = points.map { Offset((it.x - left) / slot.spaceX, (it.y - top) / slot.spaceY) }
        return stroke.copy(
            page = ANCHORED_PAGE,
            measure = slot.measure,
            staff = slot.staff,
            points = encodePoints(listOf(corner) + shape),
            width = stroke.width / slot.spaceY,
        )
    }

    /** [anchored] drawn on page [page] with these [slots], or null when its measure is not there. */
    fun project(anchored: Stroke, slots: List<StaffSlot>, page: Int): Stroke? {
        val slot = slots.firstOrNull { it.measure == anchored.measure && it.staff == anchored.staff } ?: return null
        val packed = decodePoints(anchored.points)
        val corner = packed.firstOrNull() ?: return null
        val left = xOf(slot, corner.x)
        val top = slot.top + corner.y * slot.spaceY
        return anchored.copy(
            page = page,
            points = encodePoints(packed.drop(1).map { Offset(left + it.x * slot.spaceX, top + it.y * slot.spaceY) }),
            width = anchored.width * slot.spaceY,
        )
    }

    /** [stamp], placed on a page with these [slots], hung on the staff its centre is over; null off the music. */
    fun anchor(stamp: Stamp, slots: List<StaffSlot>, boundaries: List<Float> = boundaries(slots)): Stamp? {
        val slot = slotAt(Offset(stamp.x, stamp.y), slots, boundaries) ?: return null
        return stamp.copy(
            page = ANCHORED_PAGE,
            measure = slot.measure,
            staff = slot.staff,
            x = xIn(slot, stamp.x),
            y = (stamp.y - slot.top) / slot.spaceY,
            size = stamp.size / slot.spaceY,
        )
    }

    /** [anchored] on page [page] with these [slots], or null when its measure is not there. */
    fun project(anchored: Stamp, slots: List<StaffSlot>, page: Int): Stamp? {
        val slot = slots.firstOrNull { it.measure == anchored.measure && it.staff == anchored.staff } ?: return null
        return anchored.copy(
            page = page,
            x = xOf(slot, anchored.x),
            y = slot.top + anchored.y * slot.spaceY,
            size = anchored.size * slot.spaceY,
        )
    }
}
