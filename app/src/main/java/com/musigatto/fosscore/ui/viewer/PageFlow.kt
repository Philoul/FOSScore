package com.musigatto.fosscore.ui.viewer

import kotlin.math.abs

// Where a turned half page may cut, as fractions of the page height from its top: between these
// two, on this page and on the next one. A cut higher or lower would turn nothing, or show little
// more than a margin or a page number.
private const val CUT_ZONE_TOP = 1f / 3
private const val CUT_ZONE_BOTTOM = 2f / 3
// The cut a half page aims for, and the one a page without blank paper gets.
private const val PAGE_MIDDLE = 0.5f

data class PageFlow(
    val page: Int,
    val halfTurned: Boolean,
    val halfEnabled: Boolean,
    val twoUp: Boolean,
    val isLandscape: Boolean,
    val pageCount: Int,
) {
    val showTwoUp get() = isLandscape && twoUp && !halfEnabled
    val showHalf get() = halfEnabled && !showTwoUp
    val step get() = if (showTwoUp) 2 else 1

    // a turned half page of the first page goes back to the whole first page
    val canPrev get() = page > 0 || (showHalf && halfTurned)
    val canNext get() = when {
        showHalf -> if (!halfTurned) page + 1 < pageCount else page < pageCount - 1
        else -> page < pageCount - 1
    }

    fun next(): PageFlow {
        if (!canNext) return this
        return if (showHalf) {
            if (!halfTurned) copy(halfTurned = true)
            else copy(page = (page + 1).coerceAtMost(pageCount - 1), halfTurned = false)
        } else {
            copy(page = (page + step).coerceAtMost(pageCount - 1))
        }
    }

    fun prev(): PageFlow {
        if (!canPrev) return this
        return if (showHalf && halfTurned) copy(halfTurned = false)
        else copy(page = (page - step).coerceAtLeast(0))
    }

    /**
     * Where the music played takes the reader: it is on page [target], already on screen when
     * [onScreen]. The reader looks a few bars ahead (the bars to turn ahead, 0 to 2): the music
     * there is on page [ahead], in its lower part (the part a turned half page does not show) when
     * [aheadInLowerPart]. Nothing ever goes back and forth.
     *
     * Half page: when the music ahead gets to the next page, its top shows above the end of this
     * one; when the music is on the next page and the music ahead gets to its lower part, the
     * whole next page shows. The music played always stays on screen.
     *
     * Whole pages: the page turns when the music ahead is on the next one, and stays turned while
     * the music ends the page before.
     */
    fun follow(target: Int, aheadInLowerPart: Boolean, onScreen: Boolean, ahead: Int = target): PageFlow = when {
        showHalf -> when {
            !halfTurned && ahead > page && target in page..page + 1 && page + 1 < pageCount -> copy(halfTurned = true)
            halfTurned && target == page + 1 && (ahead > page + 1 || aheadInLowerPart) -> copy(page = target, halfTurned = false)
            // turned, the music at the end of this page or at the top of the next: both on screen
            halfTurned && target in page..page + 1 -> this
            onScreen -> this
            else -> copy(page = target, halfTurned = false)
        }
        // the page shown is the one ahead, the music ends the page before
        ahead in page until page + step && target in page - step until page -> this
        onScreen && ahead < page + step -> this
        onScreen -> copy(page = ahead.coerceAtMost(pageCount - 1))
        else -> copy(page = target, halfTurned = false)
    }
}

/** A band of a page, from [start] to [end], as fractions of the page height. */
data class Band(val start: Float, val end: Float)

/**
 * Where a turned half page cuts, as fractions of the page height: this page shows below [lower],
 * the next one above [upper], blank paper between them. [onBlank] is false when the page has no
 * blank band and is cut at its middle, through whatever is there.
 */
data class HalfSplit(val lower: Float = PAGE_MIDDLE, val upper: Float = lower, val onBlank: Boolean = true) {
    companion object {
        /**
         * The cut goes through blank bands of the pages, the space between two systems, so no
         * system is cut in two: the next page shows down to one of its bands, above one of this
         * page's, both between [CUT_ZONE_TOP] and [CUT_ZONE_BOTTOM] of their page. Of every such
         * pair, the one that keeps the most of the smaller part, so both pages keep as close to a
         * half as their systems allow (five systems: never four over one); then the one that
         * cuts this page nearest its middle, so the turn comes at the same place on every page,
         * even if blank paper is left between the parts; then the one with the least of that
         * blank paper. Without such a pair, this page cuts at its band nearest the middle and the
         * next one shows down to there; a page without any band cuts at its middle.
         * [bands] and [nextBands] are the blank bands of this page and the next (the top margin
         * left out).
         */
        fun of(bands: List<Band>, nextBands: List<Band>): HalfSplit {
            val cuttable = bands.filter { it.end >= CUT_ZONE_TOP && it.start <= CUT_ZONE_BOTTOM }
            val nearest = cuttable.minByOrNull { abs((it.start + it.end) / 2 - PAGE_MIDDLE) } ?: return HalfSplit(onBlank = false)
            return cuttable.flatMap { here ->
                nextBands.filter { it.end >= CUT_ZONE_TOP && it.start <= here.end }.map { next ->
                    val upper = minOf(next.end, here.end)
                    HalfSplit(maxOf(here.start, upper), upper)
                }
            }.maxWithOrNull(
                compareBy<HalfSplit> { minOf(it.upper, 1f - it.lower) }
                    .thenBy { -abs(it.lower - PAGE_MIDDLE) }
                    .thenBy { it.upper - it.lower }
            )
                ?: ((nearest.start + nearest.end) / 2).let { HalfSplit(it, it) }
        }

        /**
         * The blank bands of a page whose rows are [blank] or not, at least [minRows] high, from
         * the top; the top margin is left out: the next page shows nothing above it.
         */
        fun bandsOf(blank: BooleanArray, minRows: Int): List<Band> {
            val h = blank.size.toFloat()
            val out = mutableListOf<Band>()
            var from = -1
            for (y in 0..blank.size) {
                val isBlank = y < blank.size && blank[y]
                if (isBlank && from < 0) from = y
                if (!isBlank && from >= 0) {
                    if (from > 0 && y - from >= minRows) out += Band(from / h, y / h)
                    from = -1
                }
            }
            return out
        }
    }
}

/**
 * Pages visible at once, 0-based: [currentPage] alone, or it and the next one in a spread.
 * `IntRange.EMPTY` while the PDF still has no pages.
 */
fun visiblePageRange(currentPage: Int, pageCount: Int, showSpread: Boolean): IntRange = when {
    pageCount <= 0 -> IntRange.EMPTY
    showSpread && currentPage + 1 < pageCount -> currentPage..currentPage + 1
    else -> currentPage..currentPage
}

/**
 * The pages a draw has to produce: one, or the two of a spread (two-up, or the turned half page
 * that shows the top of the next one). The last page of an odd score has no companion.
 */
fun pagesToDraw(
    showTwoUp: Boolean,
    showHalf: Boolean,
    halfTurned: Boolean,
    currentPage: Int,
    pageCount: Int,
): List<Int> = if (showTwoUp || (showHalf && halfTurned)) {
    listOf(currentPage, currentPage + 1).filter { it < pageCount }
} else {
    listOf(currentPage)
}