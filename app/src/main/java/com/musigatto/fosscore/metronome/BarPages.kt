package com.musigatto.fosscore.metronome

/**
 * The bars of each page of a PDF score, as the user counted them, for the automatic page turn.
 * A page without its own count has the count of the last page before it that has one, so one
 * count given for the first page fits a score with the same number of bars on every page.
 * Bars are counted from 0, from the start of the piece; repeats are not followed.
 */
class BarPages(private val counts: List<Int>, private val pageCount: Int) {

    /** Whether any page has a count: without one, nothing can turn. */
    val known: Boolean get() = counts.any { it > 0 }

    /** The bars of page [index]. */
    fun barsOf(index: Int): Int =
        (counts.getOrNull(index)?.takeIf { it > 0 } ?: counts.take(index).lastOrNull { it > 0 } ?: 0)

    /** The first bar of page [index]. */
    fun firstBar(index: Int): Int = (0 until index).sumOf { barsOf(it) }

    /** The page of [bar]: the last page once the bars counted are over. */
    fun pageOf(bar: Int): Int {
        var first = 0
        for (index in 0 until pageCount) {
            val n = barsOf(index)
            if (n <= 0) return index
            if (bar < first + n) return index
            first += n
        }
        return (pageCount - 1).coerceAtLeast(0)
    }

    /** Whether [bar] is in the lower half of its page: from the middle bar on (the 4th of 6, the 4th of 7). */
    fun inLowerHalf(bar: Int): Boolean {
        val page = pageOf(bar)
        val n = barsOf(page)
        return n > 0 && bar - firstBar(page) >= (n + 1) / 2
    }

    /** Where the metronome starts counting on a page: its first bar, or its middle bar on a turned half page. */
    fun startBar(page: Int, lowerHalf: Boolean): Int = firstBar(page) + if (lowerHalf) (barsOf(page) + 1) / 2 else 0

    companion object {
        /** The counts as the settings keep them: "6,8,8". */
        fun parse(text: String?): List<Int> = text?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

        fun format(counts: List<Int>): String = counts.joinToString(",")
    }
}
