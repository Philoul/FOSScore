package com.musigatto.fosscore.pdfscore.bitmap

import com.musigatto.fosscore.pdfscore.Box
import com.musigatto.fosscore.pdfscore.DrawnGlyph
import com.musigatto.fosscore.pdfscore.DrawnLine
import com.musigatto.fosscore.pdfscore.DrawnShape
import com.musigatto.fosscore.pdfscore.PageDrawing
import com.musigatto.fosscore.pdfscore.Point

/**
 * What the pixels of a scanned page give before any symbol is named (SPEC 2-8): the thresholds,
 * the units, the tilt, the staves and systems, the line-free image, its vertical segments and blobs.
 * Everything is in the deskewed frame, in pixels.
 */
class PageAnalysis(
    val otsu: Int,
    val threshold: Int,
    val metrics: Metrics,
    val deskew: Deskew,
    /** The page's staff space D: the median of the staves' spaces (D0 when there is no staff). */
    val space: Double,
    val ink: InkMask,
    val deskewed: InkMask,
    val lineFree: InkMask,
    /** The ledger lines erased (not in the reference: the reader needs them for notes far off a staff). */
    val ledgers: List<LineRemoval.Ledger>,
    val staves: List<BitmapStaff>,
    val systems: List<List<Int>>,
    val segments: List<Segment>,
    val blobs: List<Blob>,
) {
    val thickness: Double get() = metrics.thickness
}

/**
 * Reads a scanned page (an 8-bit gray image) into a [PageDrawing], so that the same readers as a
 * PDF's ([com.musigatto.fosscore.pdfscore.ScoreReader]) take it: the symbols named by SMuFL names, the staff lines,
 * stems and barlines as lines, the beams as four-corner shapes. The page is read deskewed; points
 * are pixels x 72 / dpi.
 */
object BitmapPage {
    /** The page drawing of [gray] scanned at [dpi], its symbols named by [model]. */
    fun read(gray: GrayImage, dpi: Double, model: OmrModel): PageDrawing = draw(analyse(gray), dpi, model)

    /** Every stage up to the blobs (SPEC 2-8). */
    fun analyse(gray: GrayImage): PageAnalysis {
        val hist = Binarize.histogram(gray)
        val otsu = Binarize.otsu(hist)
        val threshold = Binarize.threshold(hist, otsu)
        val ink = Binarize.ink(gray, threshold)
        val metrics = Metrics.of(ink)
        val deskew = Deskew.of(ink, metrics.space)
        val db = deskew.apply(ink)
        val found = StaffFinder.find(db, metrics.thickness, metrics.space)
        val space = if (found.staves.isEmpty()) metrics.space else median(found.staves.map { it.space })
        val ledgers = mutableListOf<LineRemoval.Ledger>()
        val lineFree = LineRemoval.remove(db, found.staves, metrics.thickness, ledgers)
        val segments = Segments.find(lineFree, space)
        val blobs = Blobs.find(lineFree, segments, found.staves, space, metrics.thickness)
        return PageAnalysis(otsu, threshold, metrics, deskew, space, ink, db, lineFree, ledgers, found.staves, found.systems, segments, blobs)
    }

    /** The kNN class scores of every blob (in the model's class order). */
    fun scores(page: PageAnalysis, model: OmrModel): List<DoubleArray> =
        page.blobs.map { model.symbols.scores(Features.of(it.mask, page.space, model.thresholds)) }

    /** The page drawing of an analysed page (SPEC 11-12). */
    fun draw(page: PageAnalysis, dpi: Double, model: OmrModel): PageDrawing {
        val scale = 72.0 / dpi
        val rules = SymbolRules(page.blobs, page.staves, page.space, page.thickness, page.deskewed, model)
        val prediction = rules.predict(scores(page, model), model.symbols.classes)
        val keep = rules.select(prediction)
        val glyphs = trusted(rules.symbols(prediction, keep)).map { s ->
            DrawnGlyph(s.name, Box(s.x0 * scale, s.y0 * scale, (s.x1 + 1) * scale, (s.y1 + 1) * scale), confidence = s.confidence, font = "bitmap")
        }
        val lines = staffLines(page) + ledgerLines(page) + stemsAndBarlines(page, prediction)
        // the braces, as the tall curved shapes the systems are read with (Staves.braces)
        val shapes = beams(page, prediction, keep) + braces(page.deskewed, page.staves, page.systems).map { b ->
            DrawnShape(listOf(Point(b.x0.toDouble(), b.y0.toDouble()), Point(b.x1.toDouble(), b.y0.toDouble()), Point(b.x1.toDouble(), b.y1.toDouble()), Point(b.x0.toDouble(), b.y1.toDouble())), curved = true)
        }
        return PageDrawing(
            width = page.ink.width * scale,
            height = page.ink.height * scale,
            glyphs = glyphs,
            lines = lines.map { DrawnLine(it.x1 * scale, it.y1 * scale, it.x2 * scale, it.y2 * scale, it.width * scale) },
            shapes = shapes.map { shape -> DrawnShape(shape.points.map { Point(it.x * scale, it.y * scale) }, curved = shape.curved) },
            texts = emptyList(),
        )
    }

    /**
     * The symbols to read: a head named with less confidence than [MIN_HEAD_CONFIDENCE] is left
     * out (junk read as a half note made its measure two beats long). Benchmark: 0.3 changed
     * nothing, 0.45 the best (699 -> 701 measures right), 0.6 lost real heads (678).
     */
    internal fun trusted(symbols: List<BitmapSymbol>): List<BitmapSymbol> {
        val heads = symbols.filter { it.name in HEADS && it.confidence >= MIN_HEAD_CONFIDENCE }
        // a head over most of a surer one at its height is a second reading of it
        fun doubled(h: BitmapSymbol) = heads.any { g ->
            g !== h && g.confidence > h.confidence && overlap(g, h) >= DOUBLE_OVERLAP * minOf(area(g), area(h))
        }
        return symbols.filter { it.name !in HEADS || (it.confidence >= MIN_HEAD_CONFIDENCE && !doubled(it)) }
    }

    private fun area(s: BitmapSymbol) = (s.x1 - s.x0 + 1) * (s.y1 - s.y0 + 1)

    private fun overlap(a: BitmapSymbol, b: BitmapSymbol) =
        maxOf(0, minOf(a.x1, b.x1) - maxOf(a.x0, b.x0) + 1) * maxOf(0, minOf(a.y1, b.y1) - maxOf(a.y0, b.y0) + 1)

    private const val DOUBLE_OVERLAP = 0.3

    private const val MIN_HEAD_CONFIDENCE = 0.45

    /** A brace found on a scan, in pixels. */
    internal class BraceBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /**
     * The braces of a scan (the model names no brace): ink in almost every row of a strip left of
     * two neighbouring staves of a system, from the top of the first to the bottom of the second.
     * The system's opening line, at the staves' edge, is left out of the strip.
     */
    internal fun braces(ink: InkMask, staves: List<BitmapStaff>, systems: List<List<Int>>): List<BraceBox> =
        systems.flatMap { system -> system.zipWithNext() }.mapNotNull { (a, b) ->
            val upper = staves[a]
            val lower = staves[b]
            val space = (upper.space + lower.space) / 2
            val right = minOf(upper.left, lower.left) - rnd(BRACE_GAP * space)
            val left = maxOf(0, right - rnd(BRACE_WIDTH * space))
            val y0 = rnd(upper.top)
            val y1 = minOf(ink.height - 1, rnd(lower.bottom))
            if (right <= left || y1 <= y0) return@mapNotNull null
            val inked = (y0..y1).count { y -> (left until right).any { x -> ink[x, y] } }
            if (inked < BRACE_COVER * (y1 - y0 + 1)) return@mapNotNull null
            val columns = (left until right).filter { x -> (y0..y1).any { y -> ink[x, y] } }
            BraceBox(columns.first(), y0, columns.last() + 1, y1)
        }

    // a brace: ink in this share of the rows, in a strip this wide (spaces) left of the staves,
    // this far from their edge (where the system's opening line is)
    private const val BRACE_COVER = 0.9
    private const val BRACE_WIDTH = 3.0
    private const val BRACE_GAP = 0.3

    /** Each staff's five lines, evenly spaced, plus the staff's median offset (pixels). */
    private fun staffLines(page: PageAnalysis): List<DrawnLine> = page.staves.flatMap { staff ->
        (0 until 5).map { k ->
            // evenly spaced between the top and bottom lines (SPEC 12 has c_k): the centres found
            // jitter by a pixel, and the reader measures a staff's space on its first two lines
            val y = staff.ys[0] + k * staff.space + staff.medianOffset + 0.5
            DrawnLine(staff.left.toDouble(), y, staff.right + 1.0, y, page.thickness)
        }
    }

    /** The ledger lines, as thick as the staff lines, through the middle of their row. */
    private fun ledgerLines(page: PageAnalysis): List<DrawnLine> = page.ledgers.map {
        DrawnLine(it.x0.toDouble(), it.row + 0.5, it.x1 + 1.0, it.row + 0.5, page.thickness)
    }

    /**
     * Stems: the thin segments of a composite with a notehead piece at one end. Barlines: the
     * segments of a composite or 'other' component without a head, running from a staff's top
     * line to its bottom one (half a space either way). A barline is as wide as its ink ([spread]).
     */
    private fun stemsAndBarlines(page: PageAnalysis, prediction: Prediction): List<DrawnLine> {
        val d = page.space
        val names = prediction.names
        val headsOf = page.blobs.indices.filter { page.blobs[it].pass == 2 && names[it] in HEADS }.groupBy { page.blobs[it].parent }
        val out = mutableListOf<DrawnLine>()
        page.blobs.forEachIndexed { bi, blob ->
            for (s in blob.stems) {
                val xc = (s.x0 + s.x1 + 1) / 2.0
                val line = DrawnLine(xc, s.y0.toDouble(), xc, s.y1 + 1.0, (s.x1 - s.x0 + 1).toDouble())
                val heads = headsOf[bi].orEmpty()
                if (names[bi] == "composite" && s.thin && heads.any { h ->
                        val hb = page.blobs[h]
                        hb.x1 >= s.x0 - 0.3 * d && hb.x0 <= s.x1 + 0.3 * d && s.y0 - 0.6 * d <= (hb.y0 + hb.y1) / 2.0 && (hb.y0 + hb.y1) / 2.0 <= s.y1 + 0.6 * d
                    }
                ) {
                    out += line
                    continue
                }
                if ((names[bi] == "composite" || names[bi] == "other") && heads.isEmpty() &&
                    page.staves.any { st -> s.y0 <= st.top + 0.5 * d && s.y1 + 1 >= st.bottom - 0.5 * d && st.left - d <= xc && xc <= st.right + d } &&
                    spread(s, page.lineFree) <= MAX_BARLINE_SPREAD
                ) {
                    out += line
                }
            }
        }
        return out
    }

    /**
     * The median width of the horizontal ink runs through [s]'s middle column, in [mask], over the
     * segment's width: about 1 for a barline, 2 to 10 for an arpeggio's wavy line, whose spine is a
     * column inked all along (read as a barline, it added an empty measure that shifted the rest
     * of the staff). Benchmark renders: barlines at most 1.5. Not for stems: the heads of a chord
     * widen a stem's runs as much.
     */
    internal fun spread(s: Segment, mask: InkMask): Double {
        val x = (s.x0 + s.x1) / 2
        val widths = (s.y0..s.y1).mapNotNull { y ->
            if (!mask[x, y]) return@mapNotNull null
            var a = x
            var b = x
            while (a > 0 && mask[a - 1, y]) a--
            while (b + 1 < mask.width && mask[b + 1, y]) b++
            (b - a + 1).toDouble()
        }
        return if (widths.isEmpty()) 0.0 else median(widths) / (s.x1 - s.x0 + 1)
    }

    /** The beams of the blobs read as beams, kept or pieces of a composite. */
    private fun beams(page: PageAnalysis, prediction: Prediction, keep: List<Int>): List<DrawnShape> {
        val kept = keep.toSet()
        return page.blobs.indices.filter { i ->
            val b = page.blobs[i]
            prediction.names[i] == "beam" && (i in kept || (b.pass == 2 && prediction.names[b.parent] == "composite"))
        }.flatMap { beamShapes(page.blobs[it], page.space) }
    }

    /**
     * A beam blob's beams (SPEC 12): per column, the ink runs at least max(2, rnd(D / 4)) long (a run
     * thicker than 1.5 beams cut into beams that touch); each run continues the previous column's
     * track it overlaps most; a track at least 0.8 D long is a beam, the least-squares line through
     * its run centres, as thick as its median run.
     */
    internal fun beamShapes(blob: Blob, space: Double): List<DrawnShape> {
        val mask = blob.mask
        val minRun = maxOf(2, rnd(0.25 * space))
        val b = rnd(0.5 * space)
        // per track: x, a, b of each column's run
        val tracks = mutableListOf<MutableList<IntArray>>()
        var previous = listOf<IntArray>() // a, b, track
        for (x in 0 until mask.width) {
            val runs = mutableListOf<IntArray>()
            var y = 0
            while (y < mask.height) {
                if (!mask[x, y]) {
                    y++
                    continue
                }
                var end = y
                while (end + 1 < mask.height && mask[x, end + 1]) end++
                val length = end - y + 1
                if (length >= minRun) {
                    if (length > 1.5 * b) {
                        val n = maxOf(1, rnd(length / (b + 0.25 * space)))
                        for (i in 0 until n) runs += intArrayOf(y + (i * length) / n, y + ((i + 1) * length) / n - 1)
                    } else {
                        runs += intArrayOf(y, end)
                    }
                }
                y = end + 1
            }
            val current = mutableListOf<IntArray>()
            val taken = mutableSetOf<Int>()
            for (run in runs) {
                var best = -1
                var bestOverlap = 0
                for (p in previous) {
                    val o = minOf(run[1], p[1]) - maxOf(run[0], p[0]) + 1
                    if (o >= 1 && o > bestOverlap && p[2] !in taken) {
                        best = p[2]
                        bestOverlap = o
                    }
                }
                if (best < 0) {
                    best = tracks.size
                    tracks += mutableListOf<IntArray>()
                }
                taken += best
                tracks[best] += intArrayOf(x, run[0], run[1])
                current += intArrayOf(run[0], run[1], best)
            }
            previous = current
        }
        return tracks.filter { it.size >= 0.8 * space }.map { track ->
            val xs = track.map { it[0] + 0.5 + blob.x0 }
            val cs = track.map { (it[1] + it[2] + 1) / 2.0 + blob.y0 }
            val th = median(track.map { (it[2] - it[1] + 1).toDouble() })
            val n = xs.size
            val mx = xs.sum() / n
            val my = cs.sum() / n
            var sxx = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                sxx += (xs[i] - mx) * (xs[i] - mx)
                sxy += (xs[i] - mx) * (cs[i] - my)
            }
            val slope = if (sxx > 0) sxy / sxx else 0.0
            val xl = (blob.x0 + track.first()[0]).toDouble()
            val xr = (blob.x0 + track.last()[0] + 1).toDouble()
            val yl = my + slope * (xl - mx)
            val yr = my + slope * (xr - mx)
            DrawnShape(listOf(Point(xl, yl - th / 2), Point(xr, yr - th / 2), Point(xr, yr + th / 2), Point(xl, yl + th / 2)), curved = false)
        }
    }

    private const val MAX_BARLINE_SPREAD = 1.75

    private val HEADS = setOf("noteheadBlack", "noteheadHalf", "noteheadWhole")
}
