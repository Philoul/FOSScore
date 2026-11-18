package com.musigatto.fosscore.pdfscore.bitmap

/**
 * A staff found in the deskewed image (pixels): its five line centres [ys] top to bottom, their rows
 * [rows], the columns it spans ([left]..[right]) and, per strip of [strip] columns from [left], how
 * many rows its lines lie off [rows] there ([stripOffsets]: residual tilt, paper curl).
 */
class BitmapStaff(val ys: DoubleArray, val left: Int, val right: Int, val strip: Int, val stripOffsets: IntArray) {
    val rows: IntArray = IntArray(5) { rnd(ys[it]) }
    val top: Double get() = ys[0]
    val bottom: Double get() = ys[4]
    val space: Double get() = (ys[4] - ys[0]) / 4

    /** The offset at each column [left]..[right]. */
    val offsets: IntArray = IntArray(right - left + 1) { stripOffsets[it / strip] }

    /** The offset at column [x], clamped to the staff's extent. */
    fun off(x: Int): Int = offsets[(x.coerceIn(left, right) - left)]

    /** The row of line [k] (0 the top one) at column [x]. */
    fun lineRow(k: Int, x: Int): Int = rows[k] + off(x)

    /** The median of the per-column offsets (the mean of the two middle ones for an even count). */
    val medianOffset: Double by lazy { median(offsets.map { it.toDouble() }) }
}

/** The staves and systems of a deskewed page, and the mask of its long horizontal runs [longRuns]. */
class StaffFinding(val staves: List<BitmapStaff>, val systems: List<List<Int>>, val longRuns: InkMask)

/**
 * Finds the staves in the deskewed image (SPEC 5): rows where long horizontal runs pile up are
 * lines; five evenly spaced lines are a staff; then where each staff starts and ends, its line
 * offsets strip by strip, and which staves a barline joins into a system.
 */
object StaffFinder {
    fun find(db: InkMask, thickness: Double, space: Double): StaffFinding {
        val closed = db.closeGaps(maxOf(1, rnd(space / 4)))
        val lm = closed.longRuns(floorInt(3 * space))
        val proj = LongArray(db.height)
        for (y in 0 until db.height) {
            var n = 0L
            for (x in 0 until db.width) if (lm[x, y]) n++
            proj[y] = n
        }
        val half = closed.longRuns(maxOf(2, floorInt(space / 2)))
        val staves = groups(lines(proj, thickness, space), space).mapNotNull { ys -> extent(ys, half, thickness, space)?.let { (left, right) -> refine(ys, left, right, lm, space) } }
        return StaffFinding(staves, systems(staves, db), lm)
    }

    /** A line found: its centre row (weighted by the projection) and its strength (largest count). */
    private class Line(val centre: Double, val strength: Long)

    /**
     * The lines: runs of rows of high projection (at least 12 % of the largest, 6 D0 and 80 % of
     * their neighbourhood's peak), at most max(2 T, T + 3) rows tall (taller runs are beams or text).
     */
    private fun lines(proj: LongArray, thickness: Double, space: Double): List<Line> {
        val h = proj.size
        val threshold = maxOf((proj.maxOrNull() ?: 0L).toDouble() * 0.12, 6.0 * space)
        val r = maxOf(1, floorInt(space / 2))
        val on = BooleanArray(h) { y ->
            var win = 0L
            for (i in maxOf(y - r, 0)..minOf(y + r, h - 1)) win = maxOf(win, proj[i])
            proj[y] >= threshold && proj[y] >= 0.8 * win
        }
        val maxRun = maxOf(2.0 * thickness, thickness + 3.0)
        val out = mutableListOf<Line>()
        var y = 0
        while (y < h) {
            if (!on[y]) {
                y++
                continue
            }
            var end = y
            while (end + 1 < h && on[end + 1]) end++
            if (end - y + 1 <= maxRun) {
                var num = 0L
                var den = 0L
                var strength = 0L
                for (i in y..end) {
                    num += i * proj[i]
                    den += proj[i]
                    strength = maxOf(strength, proj[i])
                }
                out += Line(num.toDouble() / den.toDouble(), strength)
            }
            y = end + 1
        }
        return out
    }

    /**
     * Five evenly spaced lines make a candidate; candidates are taken by the strength of their
     * weakest line (strongest first), then their spacing (tightest first), sharing no line.
     */
    private fun groups(lines: List<Line>, space: Double): List<DoubleArray> {
        class Candidate(val strength: Long, val spacing: Double, val order: Int, val lines: IntArray)
        val candidates = mutableListOf<Candidate>()
        val n = lines.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val sp = lines[j].centre - lines[i].centre
                if (sp > 1.4 * space) break
                if (sp < 0.7 * space) continue
                val tol = maxOf(0.15 * sp, 1.5)
                val idx = mutableListOf(i, j)
                for (k in 2..4) {
                    val want = lines[i].centre + k * sp
                    var best = -1
                    var bestDistance = 0.0
                    for (m in j until n) {
                        val d = kotlin.math.abs(lines[m].centre - want)
                        if (d <= tol && (best < 0 || d < bestDistance)) {
                            best = m
                            bestDistance = d
                        }
                    }
                    if (best < 0) break
                    idx += best
                }
                if (idx.size == 5) candidates += Candidate(idx.minOf { lines[it].strength }, sp, candidates.size, idx.toIntArray())
            }
        }
        candidates.sortWith(compareBy<Candidate>({ -it.strength }, { it.spacing }, { it.order }))
        val used = mutableSetOf<Int>()
        val out = mutableListOf<DoubleArray>()
        for (c in candidates) {
            if (c.lines.any { it in used }) continue
            used.addAll(c.lines.toList())
            out += DoubleArray(5) { lines[c.lines[it]].centre }
        }
        return out.sortedBy { it[0] }
    }

    /**
     * Where a staff runs: the columns where at least 4 of its 5 lines show half-space runs (within
     * a few rows), split at gaps over 2 D0; the longest span (first on ties). Null when there is none.
     */
    private fun extent(ys: DoubleArray, half: InkMask, thickness: Double, space: Double): Pair<Int, Int>? {
        val r2 = maxOf(1, floorInt(thickness / 2 + 1.5))
        val count = IntArray(half.width)
        for (c in ys) {
            val yi = rnd(c)
            for (x in 0 until half.width) {
                for (y in maxOf(yi - r2, 0)..minOf(yi + r2, half.height - 1)) {
                    if (half[x, y]) {
                        count[x]++
                        break
                    }
                }
            }
        }
        var bestStart = -1
        var bestEnd = -1
        var start = -1
        var last = -1
        for (x in 0 until half.width) {
            if (count[x] < 4) continue
            if (start < 0) {
                start = x
            } else if (x - last > 2 * space) {
                if (bestStart < 0 || last - start > bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = last
                }
                start = x
            }
            last = x
        }
        if (start < 0) return null
        if (bestStart < 0 || last - start > bestEnd - bestStart) {
            bestStart = start
            bestEnd = last
        }
        return bestStart to bestEnd
    }

    /**
     * The staff with its offsets: per strip of max(8, floor(2 D0)) columns, the offset (0, -1, 1...
     * to 3) putting the most long-run pixels under its five lines; an empty strip takes the previous
     * strip's offset (else the next one's, else 0); then a median of 3.
     */
    private fun refine(ys: DoubleArray, left: Int, right: Int, lm: InkMask, space: Double): BitmapStaff {
        val strip = maxOf(8, floorInt(2 * space))
        val rows = IntArray(5) { rnd(ys[it]) }
        val found = mutableListOf<Int?>()
        for (x0 in left..right step strip) {
            val x1 = minOf(x0 + strip, right + 1)
            var best = -1L
            var bestOffset = 0
            for (o in intArrayOf(0, -1, 1, -2, 2, -3, 3)) {
                var s = 0L
                for (row in rows) {
                    val y = row + o
                    if (y < 0 || y >= lm.height) continue
                    for (x in x0 until x1) if (lm[x, y]) s++
                }
                if (s > best) {
                    best = s
                    bestOffset = o
                }
            }
            found += if (best > 0) bestOffset else null
        }
        val filled = filled(found)
        val smooth = if (filled.size >= 3) {
            IntArray(filled.size) { i ->
                intArrayOf(filled[maxOf(i - 1, 0)], filled[i], filled[minOf(i + 1, filled.size - 1)]).sorted()[1]
            }
        } else {
            filled
        }
        return BitmapStaff(ys, left, right, strip, smooth)
    }

    /** Empty strips ([found] null) filled from the previous found one, else the next one, else 0. */
    private fun filled(found: List<Int?>): IntArray {
        if (found.all { it == null }) return IntArray(found.size)
        val out = found.toMutableList()
        var last: Int? = null
        for (i in out.indices) out[i]?.let { last = it } ?: run { out[i] = last }
        var next: Int? = null
        for (i in out.indices.reversed()) out[i]?.let { next = it } ?: run { out[i] = next }
        return IntArray(out.size) { out[it] ?: 0 }
    }

    /**
     * The systems, as lists of staff indices: a staff joins the previous staff's system when a
     * column near their left ends is at least 90 % ink from the upper staff's bottom line to the
     * lower staff's top line (a barline or a bracket joining them).
     */
    private fun systems(staves: List<BitmapStaff>, db: InkMask): List<List<Int>> {
        val groups = mutableListOf<MutableList<Int>>()
        staves.forEachIndexed { i, staff ->
            groups.lastOrNull()?.let { group ->
                val upper = staves[group.last()]
                val d = staff.space
                val x0 = maxOf(maxOf(upper.left, staff.left) - floorInt(d), 0)
                val x1 = minOf(minOf(upper.left, staff.left) + floorInt(1.5 * d), db.width - 1)
                val y0 = upper.rows[4]
                val y1 = staff.rows[0]
                if (y1 > y0 && x1 >= x0) {
                    val n = y1 - y0 + 1
                    val joined = (x0..x1).any { x -> 10 * (y0..y1).count { y -> db[x, y] } >= 9 * n }
                    if (joined) {
                        group += i
                        return@forEachIndexed
                    }
                }
            }
            groups += mutableListOf(i)
        }
        return groups
    }
}
