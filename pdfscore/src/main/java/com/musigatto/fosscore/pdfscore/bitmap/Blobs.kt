package com.musigatto.fosscore.pdfscore.bitmap

/**
 * A candidate symbol of the line-free image: its [mask] cropped to its box [x0]..[x1] x [y0]..[y1]
 * (inclusive, deskewed pixels). [pass] 1 is a connected component; 2 a piece of one, its stems
 * taken out ([parent] is that component's blob index); 3 two components a staff line had cut
 * apart, joined again ([parts]). [stems] are the vertical segments of a pass-1 component.
 */
class Blob(
    val pass: Int,
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
    val mask: InkMask,
    val parent: Int = -1,
    val stems: List<Segment> = emptyList(),
    val parts: Pair<Int, Int>? = null,
    internal val label: Int = 0,
) {
    val ink: Int by lazy { mask.count() }
}

/** The 8-connected components of an image, numbered 1, 2... in the raster order of their first pixel. */
internal class Components(val labels: IntArray, val count: Int, val boxes: IntArray, val inks: IntArray) {
    fun x0(n: Int) = boxes[4 * (n - 1)]
    fun y0(n: Int) = boxes[4 * (n - 1) + 1]
    fun x1(n: Int) = boxes[4 * (n - 1) + 2]
    fun y1(n: Int) = boxes[4 * (n - 1) + 3]

    /** Component [n]'s pixels in its box, the others' left white. */
    fun mask(n: Int, width: Int): InkMask {
        val out = InkMask(x1(n) - x0(n) + 1, y1(n) - y0(n) + 1)
        for (y in 0 until out.height) for (x in 0 until out.width) {
            if (labels[(y0(n) + y) * width + x0(n) + x] == n) out[x, y] = true
        }
        return out
    }

    companion object {
        fun of(img: InkMask): Components {
            val w = img.width
            val labels = IntArray(w * img.height)
            val boxes = mutableListOf<Int>()
            val inks = mutableListOf<Int>()
            var stack = IntArray(1024)
            var n = 0
            for (start in labels.indices) {
                if (!img.ink[start] || labels[start] != 0) continue
                n++
                var x0 = start % w
                var x1 = x0
                var y0 = start / w
                var y1 = y0
                var ink = 0
                var top = 0
                stack[top++] = start
                labels[start] = n
                while (top > 0) {
                    val p = stack[--top]
                    ink++
                    val px = p % w
                    val py = p / w
                    if (px < x0) x0 = px
                    if (px > x1) x1 = px
                    if (py < y0) y0 = py
                    if (py > y1) y1 = py
                    for (dy in -1..1) {
                        val y = py + dy
                        if (y < 0 || y >= img.height) continue
                        for (dx in -1..1) {
                            val x = px + dx
                            if (x < 0 || x >= w) continue
                            val q = y * w + x
                            if (img.ink[q] && labels[q] == 0) {
                                labels[q] = n
                                if (top == stack.size) stack = stack.copyOf(stack.size * 2)
                                stack[top++] = q
                            }
                        }
                    }
                }
                boxes += listOf(x0, y0, x1, y1)
                inks += ink
            }
            return Components(labels, n, boxes.toIntArray(), inks.toIntArray())
        }
    }
}

/** The blobs of the line-free image (SPEC 8). */
object Blobs {
    /**
     * Pass 1, each component at least max(4, floor(0.03 D²)) pixels, followed by its pass-2 pieces;
     * then the pass-3 joins across staff lines.
     */
    fun find(img: InkMask, segments: List<Segment>, staves: List<BitmapStaff>, space: Double, thickness: Double): List<Blob> {
        val minArea = maxOf(4, floorInt(0.03 * space * space))
        val comps = Components.of(img)
        val segmentsOf = mutableMapOf<Int, MutableList<Segment>>()
        for (s in segments) {
            val cx = (s.x0 + s.x1) / 2
            val cy = (s.y0 + s.y1) / 2
            val label = comps.labels[cy * img.width + cx].takeIf { it != 0 }
                ?: (s.y0..s.y1).map { comps.labels[it * img.width + cx] }.firstOrNull { it != 0 }
                ?: continue
            segmentsOf.getOrPut(label) { mutableListOf() } += s
        }
        val blobs = mutableListOf<Blob>()
        for (n in 1..comps.count) {
            if (comps.inks[n - 1] < minArea) continue
            val x0 = comps.x0(n)
            val y0 = comps.y0(n)
            val mask = comps.mask(n, img.width)
            val stems = segmentsOf[n].orEmpty()
            val index = blobs.size
            blobs += Blob(1, x0, y0, comps.x1(n), comps.y1(n), mask, stems = stems, label = n)
            val thin = stems.filter { it.thin }.map { Segment(it.x0 - x0, it.x1 - x0, it.y0 - y0, it.y1 - y0, true) }
            if (thin.isEmpty()) continue
            val rest = removeStems(mask, thin)
            val pieces = Components.of(rest)
            for (j in 1..pieces.count) {
                if (pieces.inks[j - 1] < minArea) continue
                val piece = pieces.mask(j, rest.width)
                for ((m, box) in splitStack(piece, x0 + pieces.x0(j), y0 + pieces.y0(j), space).flatMap { (m, box) -> splitHead(m, box, space) }) {
                    blobs += Blob(2, box[0], box[1], box[2], box[3], m, parent = index)
                }
            }
        }
        if (staves.isNotEmpty()) blobs += lineJoins(blobs, comps, img.width, staves, space, thickness)
        return blobs
    }

    /**
     * A component's stems erased (local coordinates): on each row of a thin segment, the ink run
     * through its columns (widened over [mask]'s ink) is erased when at most 2 pixels wider than it.
     * Reads [mask], writes a copy.
     */
    private fun removeStems(mask: InkMask, segments: List<Segment>): InkMask {
        val out = mask.copy()
        for (s in segments) {
            for (y in maxOf(s.y0, 0)..minOf(s.y1, mask.height - 1)) {
                var a = s.x0
                while (a > 0 && mask[a - 1, y]) a--
                var b = s.x1
                while (b < mask.width - 1 && mask[b + 1, y]) b++
                if (b - a + 1 <= (s.x1 - s.x0 + 1) + 2) for (x in maxOf(a, 0)..minOf(b, mask.width - 1)) out[x, y] = false
            }
        }
        return out
    }

    /**
     * A head its flag touches, cut off it: a piece over two spaces tall that starts (or ends) with
     * a band as wide and tall as a head, then narrows to a neck under a third of a space, is cut at
     * the neck. A stem-down eighth's flag curls up from the stem's end to its head; on a scan it
     * may touch the head, and with the stem erased, head and flag made one piece named a flag, a
     * flag without a head that was dropped with its note (Mistral gagnant). Else the piece itself.
     */
    internal fun splitHead(m: InkMask, box: IntArray, space: Double): List<Pair<InkMask, IntArray>> {
        val whole = listOf(m to box)
        if (m.height <= 2 * space) return whole
        val widths = IntArray(m.height) { y -> (0 until m.width).count { m[it, y] } }
        val head = 0.8 * space..1.5 * space
        // the first row of the neck after a head-wide band from the top (or before one at the bottom)
        fun neck(rows: List<Int>): Int? {
            var band = 0
            for (y in rows) {
                if (widths[y] >= 0.9 * space) band++
                else return if (band.toDouble() in head && widths[y] <= NECK * space) y else null
            }
            return null
        }
        neck((0 until m.height).toList())?.let { y ->
            return listOfNotNull(cut(m, box, 0, y - 1), cut(m, box, y, m.height - 1))
        }
        neck((m.height - 1 downTo 0).toList())?.let { y ->
            return listOfNotNull(cut(m, box, 0, y), cut(m, box, y + 1, m.height - 1))
        }
        return whole
    }

    private fun cut(m: InkMask, box: IntArray, from: Int, to: Int): Pair<InkMask, IntArray>? =
        crop(m, 0, from, m.width - 1, to)?.let { (part, b) -> part to intArrayOf(box[0] + b[0], box[1] + b[1], box[0] + b[2], box[1] + b[3]) }

    /** A head's flag touches it through a neck at most this wide (in spaces). */
    private const val NECK = 0.33

    /**
     * A piece as wide as a head, tall and dense (a chord's heads stacked on each other) cut into
     * k horizontal bands, each cropped to its ink; else the piece itself. Boxes are (x0, y0, x1, y1).
     */
    private fun splitStack(m: InkMask, x0: Int, y0: Int, space: Double): List<Pair<InkMask, IntArray>> {
        val w = m.width
        val h = m.height
        val whole = listOf(m to intArrayOf(x0, y0, x0 + w - 1, y0 + h - 1))
        if (!(space <= w && w <= 1.9 * space && h >= 1.6 * space && m.count().toLong() * 100 > 45L * w * h)) return whole
        val k = maxOf(2, rnd((h - 1.05 * space) / space) + 1)
        val edges = IntArray(k + 1) { (2 * it * h + k) / (2 * k) }
        val out = mutableListOf<Pair<InkMask, IntArray>>()
        for (i in 0 until k) {
            crop(m, 0, edges[i], w - 1, edges[i + 1] - 1)?.let { (part, box) ->
                out += part to intArrayOf(x0 + box[0], y0 + box[1], x0 + box[2], y0 + box[3])
            }
        }
        return out
    }

    /**
     * The ink of [m] in columns [cx0]..[cx1], rows [cy0]..[cy1], cropped to its own box (given in
     * [m]'s coordinates); null when there is none.
     */
    internal fun crop(m: InkMask, cx0: Int, cy0: Int, cx1: Int, cy1: Int): Pair<InkMask, IntArray>? {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        for (y in cy0..cy1) for (x in cx0..cx1) if (m[x, y]) {
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        val out = InkMask(x1 - x0 + 1, y1 - y0 + 1)
        for (y in y0..y1) for (x in x0..x1) if (m[x, y]) out[x - x0, y - y0] = true
        return out to intArrayOf(x0, y0, x1, y1)
    }

    /** A staff line for the pass-3 joins: its row (with the staff's median offset) and its columns. */
    private class StaffLine(val y: Double, val left: Int, val right: Int)

    /**
     * Pass 3: two pass-1 blobs at most 0.35 D apart side by side, one whose top or bottom edge lies
     * against a staff line the other reaches, their union at most 3 D wide and 4.5 D tall: the
     * union is one more blob (a symbol a staff line had cut in two).
     */
    private fun lineJoins(blobs: List<Blob>, comps: Components, width: Int, staves: List<BitmapStaff>, space: Double, thickness: Double): List<Blob> {
        val lines = staves.flatMap { staff -> (0 until 5).map { StaffLine(staff.ys[it] + staff.medianOffset, staff.left, staff.right) } }
        val tol = thickness / 2 + 2.5
        val first = blobs.indices.filter { blobs[it].pass == 1 }
        val cut = first.associateWith { i ->
            val b = blobs[i]
            lines.indices.filter { li ->
                val line = lines[li]
                !(b.x1 < line.left || b.x0 > line.right) &&
                    (kotlin.math.abs(b.y1 + 1 - (line.y - thickness / 2)) <= tol || kotlin.math.abs(b.y0 - 1 - (line.y + thickness / 2)) <= tol)
            }
        }
        fun reaches(b: Blob, li: Int): Boolean = b.y0 - tol <= lines[li].y && lines[li].y <= b.y1 + tol
        val order = first.sortedWith(compareBy({ blobs[it].x0 }, { it }))
        val gap = 0.35 * space
        val out = mutableListOf<Blob>()
        for ((ai, a) in order.withIndex()) {
            val ba = blobs[a]
            for (b in order.subList(ai + 1, order.size)) {
                val bb = blobs[b]
                if (bb.x0 > ba.x1 + gap) break
                if (maxOf(ba.x0, bb.x0) - minOf(ba.x1, bb.x1) > gap) continue
                val mx0 = minOf(ba.x0, bb.x0)
                val my0 = minOf(ba.y0, bb.y0)
                val mx1 = maxOf(ba.x1, bb.x1)
                val my1 = maxOf(ba.y1, bb.y1)
                if (mx1 - mx0 > 3 * space || my1 - my0 > 4.5 * space) continue
                val joined = cut[a].orEmpty().any { reaches(bb, it) } || cut[b].orEmpty().any { reaches(ba, it) }
                if (!joined) continue
                val m = InkMask(mx1 - mx0 + 1, my1 - my0 + 1)
                for (y in my0..my1) for (x in mx0..mx1) {
                    val l = comps.labels[y * width + x]
                    if (l == ba.label || l == bb.label) m[x - mx0, y - my0] = true
                }
                out += Blob(3, mx0, my0, mx1, my1, m, parts = a to b)
            }
        }
        return out
    }
}
