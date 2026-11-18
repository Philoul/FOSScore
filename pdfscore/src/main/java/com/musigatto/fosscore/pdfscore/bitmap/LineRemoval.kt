package com.musigatto.fosscore.pdfscore.bitmap

/**
 * Staff and ledger lines taken out of the deskewed image (SPEC 6), leaving the symbols: in each
 * column under a line, the ink run at the line is erased when it is no taller than a line can be.
 * A symbol's stroke crossing the line makes the run longer, so the line is kept there.
 */
object LineRemoval {
    /**
     * A ledger line taken out: columns [x0]..[x1] around row [row] (deskewed pixels). Not part of
     * the reference (SPEC 6 only erases them); the reader needs them to read notes far off a staff.
     */
    data class Ledger(val x0: Int, val x1: Int, val row: Int)

    /**
     * The line-free image of [db]: the staves' lines, then the ledger lines above and below them.
     * The ledger lines erased are added to [found] (a run of ledger columns where at least one
     * column was erased: a head alone in the band, never erased, is no ledger line).
     */
    fun remove(db: InkMask, staves: List<BitmapStaff>, thickness: Double, found: MutableList<Ledger>? = null, ledgers: Int = 6): InkMask {
        val img = db.copy()
        val maxLength = rnd(1.6 * thickness + 2)
        for (staff in staves) {
            for (k in 0 until 5) {
                for (x in staff.left..staff.right) erase(img, x, staff.lineRow(k, x), maxLength)
            }
        }
        for (staff in staves) {
            val d = staff.space
            val x0 = maxOf(staff.left - floorInt(d), 0)
            val x1 = minOf(staff.right + floorInt(d), img.width - 1)
            val length = floorInt(1.1 * d)
            for (k in 1..ledgers) {
                for (y in doubleArrayOf(staff.top - k * d, staff.bottom + k * d)) {
                    if (y < 0 || y >= img.height) continue
                    val columns = ledgerColumns(img, floorInt(y), x0, x1, length)
                    val erased = columns.filter { x -> erase(img, x, rnd(y) + staff.off(x), maxLength) }.toSet()
                    found?.let { list ->
                        for (run in runs(columns)) {
                            if (run.none { it in erased }) continue
                            list += Ledger(run.first, run.last, rnd(y) + staff.off((run.first + run.last) / 2))
                        }
                    }
                }
            }
        }
        return img
    }

    /**
     * The columns [x0]..[x1] where a horizontal run at least [length] long lies within 2 rows of
     * row [yf] (cut at [x0]..[x1]): where a ledger line may be.
     */
    private fun ledgerColumns(img: InkMask, yf: Int, x0: Int, x1: Int, length: Int): List<Int> {
        val hit = BooleanArray(x1 - x0 + 1)
        for (y in maxOf(yf - 2, 0)..minOf(yf + 2, img.height - 1)) {
            var x = x0
            while (x <= x1) {
                if (!img[x, y]) {
                    x++
                    continue
                }
                var end = x
                while (end + 1 <= x1 && img[end + 1, y]) end++
                if (end - x + 1 >= length) for (i in x..end) hit[i - x0] = true
                x = end + 1
            }
        }
        return (x0..x1).filter { hit[it - x0] }
    }

    /**
     * In column [x]: the first ink pixel among rows [row], row - 1, row + 1; its vertical run
     * (counted up to [maxLength] + 1 each way) is erased when at most [maxLength] long.
     */
    internal fun erase(img: InkMask, x: Int, row: Int, maxLength: Int): Boolean {
        val y = intArrayOf(row, row - 1, row + 1).firstOrNull { it >= 0 && it < img.height && img[x, it] } ?: return false
        var up = 0
        while (up < maxLength + 1 && y - up - 1 >= 0 && img[x, y - up - 1]) up++
        var down = 0
        while (down < maxLength + 1 && y + down + 1 < img.height && img[x, y + down + 1]) down++
        if (up + down + 1 > maxLength) return false
        for (i in y - up..y + down) img[x, i] = false
        return true
    }

    /** Sorted columns grouped into runs of neighbours. */
    private fun runs(columns: List<Int>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        var last = -1
        for (x in columns) {
            if (start >= 0 && x == last + 1) {
                last = x
                continue
            }
            if (start >= 0) out += start..last
            start = x
            last = x
        }
        if (start >= 0) out += start..last
        return out
    }
}
