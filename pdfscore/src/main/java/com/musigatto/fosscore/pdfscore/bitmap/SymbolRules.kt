package com.musigatto.fosscore.pdfscore.bitmap

/**
 * A symbol of a scanned page: its SMuFL [name], its box in deskewed pixels ([x0]..[x1] x [y0]..[y1],
 * inclusive), how sure the classifier is, and the blob it came from.
 */
data class BitmapSymbol(val name: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val confidence: Double, val blob: Int) {
    val centerX: Double get() = (x0 + x1) / 2.0
    val centerY: Double get() = (y0 + y1) / 2.0
}

/** The classes a blob is predicted as, after the rules of SPEC 11.1, and their scores. */
class Prediction(val names: List<String>, val confidences: DoubleArray)

/**
 * From the blobs' classes to the page's named symbols (SPEC 11): which blobs are symbols, where a
 * symbol may stand, and the rules for the classes the classifier alone reads poorly (whole and half
 * rests, flags, time signature digits).
 */
class SymbolRules(
    private val blobs: List<Blob>,
    private val staves: List<BitmapStaff>,
    private val space: Double,
    private val thickness: Double,
    private val db: InkMask,
    private val model: OmrModel,
) {
    /**
     * The blobs' classes from their kNN [scores] (in [classes] order): no 'composite' for a piece,
     * no flag but for a piece (a flag only exists cut from its stem); a component cut into pieces
     * and read as a slur, a beam or nothing is a composite, and its pieces are heads, flags, dots,
     * accidentals or nothing.
     */
    fun predict(scores: List<DoubleArray>, classes: List<String>): Prediction {
        val composite = classes.indexOf("composite")
        val names = mutableListOf<String>()
        val confidences = DoubleArray(blobs.size)
        blobs.forEachIndexed { i, blob ->
            val p = scores[i].copyOf()
            if (blob.pass >= 2 && composite >= 0) p[composite] = 0.0
            if (blob.pass != 2) classes.forEachIndexed { c, name -> if (name in FLAGS) p[c] = 0.0 }
            val sum = p.sum()
            if (sum > 0) for (c in p.indices) p[c] /= sum
            var best = 0
            for (c in 1 until p.size) if (p[c] > p[best]) best = c
            names += classes[best]
            confidences[i] = p[best]
        }
        val forced = blobs.filter { it.pass == 2 }.map { it.parent }.toSet()
            .filter { names[it] == "slur" || names[it] == "other" || names[it] == "beam" }.toSet()
        forced.forEach { names[it] = "composite" }
        blobs.forEachIndexed { j, blob ->
            val name = names[j]
            if (blob.pass == 2 && blob.parent in forced && name !in NON_SYMBOLS &&
                !(name.startsWith("notehead") || name.startsWith("flag") || name.startsWith("augmentationDot") || name.startsWith("accidental"))
            ) {
                names[j] = "other"
            }
        }
        return Prediction(names, confidences)
    }

    /**
     * The blobs that are the page's symbols (SPEC 11.2): accepted pass-3 joins (surer than their
     * parts), the components not read as composites, and the pieces of those that are.
     */
    fun select(prediction: Prediction): List<Int> {
        val names = prediction.names
        val conf = prediction.confidences
        val dropped = mutableSetOf<Int>()
        val keep = mutableListOf<Int>()
        for (i in blobs.indices.filter { blobs[it].pass == 3 }.sortedWith(compareBy({ -conf[it] }, { it }))) {
            val (a, b) = blobs[i].parts ?: continue
            if (names[i] in NON_SYMBOLS || conf[i] < 0.5 || a in dropped || b in dropped) continue
            if (listOf(a, b).all { names[it] in NON_SYMBOLS || conf[it] < conf[i] }) {
                keep += i
                dropped += a
                dropped += b
            }
        }
        blobs.forEachIndexed { i, blob ->
            when (blob.pass) {
                1 -> if (names[i] != "composite" && i !in dropped) keep += i
                2 -> if (names[blob.parent] == "composite" && blob.parent !in dropped) keep += i
            }
        }
        return keep.sorted()
    }

    /** The named symbols of the page, every rule applied. */
    fun symbols(prediction: Prediction, keep: List<Int>): List<BitmapSymbol> {
        val named = keep.filter { prediction.names[it] !in NON_SYMBOLS }.map { i ->
            val b = blobs[i]
            BitmapSymbol(prediction.names[i], b.x0, b.y0, b.x1, b.y1, prediction.confidences[i], i)
        }
        return timeSignatures(flags(rests(context(named)), prediction)).filter(::clefFits)
    }

    /**
     * Symbols where they may stand: within their class's reach of a staff (in spaces); a dot only
     * beside or over a head or rest.
     */
    private fun context(symbols: List<BitmapSymbol>): List<BitmapSymbol> {
        val near = symbols.filter { staffDistance(it) <= (REACH[it.name] ?: 4.5) }
        val heads = near.filter { it.name in HEADS_AND_RESTS }
        return near.filter { s ->
            s.name != "augmentationDot" || heads.any { h ->
                val cx = s.centerX
                val cy = s.centerY
                (h.x1 - 0.2 * space <= cx && cx <= h.x1 + 2.5 * space && kotlin.math.abs(cy - h.centerY) <= 1.0 * space) ||
                    (kotlin.math.abs(cx - h.centerX) <= 0.8 * space && kotlin.math.abs(cy - h.centerY) <= 2.5 * space)
            }
        }
    }

    /** How far out of the nearest staff (by its first and last lines) a symbol's centre is, in spaces. */
    private fun staffDistance(s: BitmapSymbol): Double {
        var best = 1e9
        for (staff in staves) {
            if (s.centerX < staff.left - 3 * space || s.centerX > staff.right + space) continue
            val d = if (staff.top <= s.centerY && s.centerY <= staff.bottom) 0.0 else minOf(kotlin.math.abs(s.centerY - staff.top), kotlin.math.abs(s.centerY - staff.bottom))
            best = minOf(best, d / space)
        }
        return best
    }

    /**
     * Whole and half rests (SPEC 11.3): kept when not a piece, as wide as a rest, flat, and touching
     * a staff or ledger line of the nearest staff; named whole when hanging under the nearest line,
     * half when sitting on it.
     */
    private fun rests(symbols: List<BitmapSymbol>): List<BitmapSymbol> = symbols.mapNotNull { s ->
        if (s.name != "restWhole") return@mapNotNull s
        if (blobs[s.blob].pass == 2) return@mapNotNull null
        val w = s.x1 - s.x0 + 1
        val h = s.y1 - s.y0 + 1
        if (!(0.8 * space <= w && w <= 2.0 * space && h <= 0.75 * space + thickness)) return@mapNotNull null
        val staff = nearestStaff(s, 3.5) ?: return@mapNotNull null
        val off = staff.off((s.x0 + s.x1) / 2)
        val y = staff.rows
        val rows = (1..3).map { y[0] + off - rnd(it * space) } + y.map { it + off } + (1..3).map { y[4] + off + rnd(it * space) }
        if (rows.none { kotlin.math.abs(s.y0 - it) <= thickness + 1 || kotlin.math.abs(s.y1 - it) <= thickness + 1 }) return@mapNotNull null
        val lines = (1..3).map { y[0] + off - it * space } + y.map { (it + off).toDouble() } + (1..3).map { y[4] + off + it * space }
        val cy = (s.y0 + s.y1 + 1) / 2.0
        val line = lines.minByOrNull { kotlin.math.abs(cy - it) } ?: return@mapNotNull s
        s.copy(name = if (cy > line) "restWhole" else "restHalf")
    }

    /**
     * The staff nearest a symbol's centre vertically (0 within its lines), within [reach] spaces and
     * its extent (one space more each side); first on ties.
     */
    private fun nearestStaff(s: BitmapSymbol, reach: Double): BitmapStaff? {
        var best: BitmapStaff? = null
        var bestDistance = 0.0
        for (staff in staves) {
            if (!(staff.left - space <= s.centerX && s.centerX <= staff.right + space)) continue
            val d = if (staff.top <= s.centerY && s.centerY <= staff.bottom) 0.0 else minOf(kotlin.math.abs(s.centerY - staff.top), kotlin.math.abs(s.centerY - staff.bottom))
            if (d <= reach * space && (best == null || d < bestDistance)) {
                best = staff
                bestDistance = d
            }
        }
        return best
    }

    /**
     * Flags (SPEC 11.4): a flag piece is dropped when its note has no head; pieces of it a staff line
     * cut off join it; its name counts the hooks of its pieces and keeps the classifier's direction.
     */
    private fun flags(symbols: List<BitmapSymbol>, prediction: Prediction): List<BitmapSymbol> {
        val names = prediction.names
        val used = mutableSetOf<Int>()
        val out = mutableListOf<BitmapSymbol>()
        for (s in symbols) {
            if (s.blob in used) continue
            if (!s.name.startsWith("flag")) {
                out += s
                continue
            }
            val parent = blobs[s.blob].parent
            val hasHead = blobs.indices.any { j -> blobs[j].pass == 2 && blobs[j].parent == parent && names[j].startsWith("notehead") }
            if (!hasHead) continue
            var parts = listOf(s.blob) + blobs.indices.filter { j ->
                val b = blobs[j]
                j != s.blob && j !in used && b.pass == 2 && b.parent == parent && (names[j].startsWith("flag") || names[j] == "other") &&
                    b.x0 <= s.x1 && b.x1 >= s.x0 && maxOf(b.y0 - s.y1, s.y0 - b.y1) <= 0.5 * space + thickness
            }
            if (parts.drop(1).any { it in used }) parts = listOf(s.blob)
            used.addAll(parts)
            var flag = s
            if (parts.size > 1) {
                flag = s.copy(
                    x0 = parts.minOf { blobs[it].x0 },
                    y0 = parts.minOf { blobs[it].y0 },
                    x1 = parts.maxOf { blobs[it].x1 },
                    y1 = parts.maxOf { blobs[it].y1 },
                )
            }
            val stems = if (parent >= 0) blobs[parent].stems.filter { it.thin } else emptyList()
            val stem = stems.minWithOrNull(
                compareBy<Segment>({ kotlin.math.abs((it.x0 + it.x1) / 2.0 - flag.x0) }, { it.x0 }, { it.x1 }, { it.y0 }, { it.y1 }),
            )
            stem?.let {
                val hooks = minOf(parts.sumOf { hooks(blobs[it].mask) }, 3)
                flag = flag.copy(name = FLAG_NAMES[hooks - 1] + if (s.name.endsWith("Up")) "Up" else "Down")
            }
            out += flag
        }
        return out
    }

    /**
     * A flag piece's hooks: on its columns w/4 .. w/2, the ink runs at least max(1, rnd(T/2)) long;
     * the most frequent count of 1 or more (ties: the smaller; none: 1), at most 3.
     */
    private fun hooks(mask: InkMask): Int {
        val minRun = maxOf(1, rnd(thickness / 2))
        val counts = IntArray(8)
        for (x in mask.width / 4..mask.width / 2) {
            var n = 0
            var y = 0
            while (y < mask.height) {
                if (!mask[x, y]) {
                    y++
                    continue
                }
                var end = y
                while (end + 1 < mask.height && mask[x, end + 1]) end++
                if (end - y + 1 >= minRun) n++
                y = end + 1
            }
            if (n < counts.size) counts[n]++
        }
        var best = 1
        if ((1 until 8).any { counts[it] > 0 }) for (k in 2 until 8) if (counts[k] > counts[best]) best = k
        return best.coerceIn(1, 3)
    }

    /**
     * Time signature digits read again with the staff lines kept (SPEC 11.5): each digit-like
     * symbol's half of its staff, then the other half, cut from the deskewed image and read by the
     * digit classifier; the readings replace the symbols. A symbol as tall as the staff, across its
     * lines, may be a time signature whose digits touch through a staff line and make one blob the
     * classifier names otherwise (Mistral gagnant: 12/8 read as mf, 9/8 as a C clef, 6/8 as a
     * natural, the measures then 3/4 long): it is one when a digit is read in both halves of the
     * staff there, else it stays what it was named ([mayBeDigits]).
     */
    private fun timeSignatures(symbols: List<BitmapSymbol>): List<BitmapSymbol> {
        val keep = symbols.filterNot { isDigit(it) || mayBeDigits(it) }.toMutableList()
        val done = mutableSetOf<List<Int>>()
        for (s in symbols.filter(::mayBeDigits)) {
            val read = readDigits(s, done)
            if (read.count { it.second } > 0 && read.count { !it.second } > 0) keep += read.map { it.first } else keep += s
        }
        for (s in symbols.filter(::isDigit).sortedWith(compareBy({ -it.confidence }, { it.blob }))) keep += readDigits(s, done).map { it.first }
        return keep
    }

    private fun isDigit(s: BitmapSymbol) = s.name.startsWith("timeSig") && s.name.length > 7 && s.name.substring(7).all { it.isDigit() }

    /**
     * Whether a symbol named otherwise may be a time signature misread: one as tall as its staff,
     * across its lines; or a bass or C clef past a staff's start (Mistral gagnant, 81: a 3/4 read
     * as two bass clefs, the melody then read in the bass clef).
     */
    internal fun mayBeDigits(s: BitmapSymbol): Boolean = !isDigit(s) && s.name !in NEVER_DIGITS && staves.any { st ->
        st.left <= s.centerX && s.centerX <= st.right && (
            (s.y0 <= st.top + 0.5 * space && s.y1 >= st.bottom - 0.5 * space && s.y1 - s.y0 + 1 <= (4 + TIME_SIGNATURE_MARGIN) * space) ||
                ((s.name == "fClef" || s.name == "cClef") && st.left + OPENING_CLEF_REACH * space < s.centerX &&
                    s.centerY >= st.top - 0.5 * space && s.centerY <= st.bottom + 0.5 * space)
            )
    }

    /**
     * Whether a symbol named a treble clef is one: it reaches from over its staff's top line to
     * under its bottom line (a clef change drawn small too). An octave line's "8" and its hook, on
     * a scan, were read as a treble clef over the staff, and the notes after it a fifth off.
     */
    internal fun clefFits(s: BitmapSymbol): Boolean {
        if (!s.name.startsWith("gClef")) return true
        val staff = nearestStaff(s, 4.0) ?: return false
        return s.y0 <= staff.top + 0.3 * space && s.y1 >= staff.bottom + 0.3 * space
    }

    /** The digits read in [s]'s staff halves, each with whether it is in the upper half; each half once ([done]). */
    private fun readDigits(s: BitmapSymbol, done: MutableSet<List<Int>>): List<Pair<BitmapSymbol, Boolean>> {
        val si = staves.indexOfFirst { st ->
            st.top - 0.5 * space <= s.centerY && s.centerY <= st.bottom + 0.5 * space && st.left - space <= s.centerX && s.centerX <= st.right + space
        }
        if (si < 0) return emptyList()
        val staff = staves[si]
        val upper = s.centerY < staff.rows[2] + staff.off((s.x0 + s.x1) / 2)
        val out = mutableListOf<Pair<BitmapSymbol, Boolean>>()
        for (half in listOf(upper, !upper)) {
            val (crop, box) = digitSlot(staff, s.x0, s.x1, half) ?: continue
            val key = listOf(si, if (half) 1 else 0) + box.toList()
            if (!done.add(key)) continue
            for ((digit, at) in split(crop, box)) {
                val read = model.digits.classify(Features.of(digit, space, model.thresholds))
                if (read.name != "other") out += BitmapSymbol(read.name, at[0], at[1], at[2], at[3], read.score, s.blob) to half
            }
        }
        return out
    }

    /**
     * A number of two digits (12/8) cut in two at its thinnest column in the middle third, when
     * wider than [MAX_DIGIT_WIDTH] spaces: the digit classifier reads one digit, and two digits
     * often touch.
     */
    internal fun split(crop: InkMask, box: IntArray): List<Pair<InkMask, IntArray>> {
        if (crop.width <= MAX_DIGIT_WIDTH * space) return listOf(crop to box)
        val ink = IntArray(crop.width) { x -> (0 until crop.height).count { crop[x, it] } }
        val cut = (crop.width / 3..2 * crop.width / 3).minByOrNull { ink[it] } ?: return listOf(crop to box)
        return listOfNotNull(
            Blobs.crop(db, box[0], box[1], box[0] + cut - 1, box[3]),
            Blobs.crop(db, box[0] + cut + 1, box[1], box[2], box[3]),
        )
    }

    /**
     * The digit in the upper (lines 1-3) or lower (lines 3-5) half of [staff] around columns
     * [x0]..[x1], cut from the deskewed image with its staff lines; null when there is none.
     */
    private fun digitSlot(staff: BitmapStaff, x0: Int, x1: Int, upper: Boolean): Pair<InkMask, IntArray>? {
        val from = maxOf(x0, 0)
        val to = minOf(x1, db.width - 1)
        if (from > to) return null
        val (a, b) = if (upper) 0 to 2 else 2 to 4
        val off = staff.off(from + (to - from + 1) / 2)
        val m = rnd(thickness / 2)
        val r0 = staff.rows[a] + off - m
        val r1 = staff.rows[b] + off + m
        if (r0 < 0 || r1 >= db.height) return null
        val lines = intArrayOf(staff.rows[a] + off, staff.rows[a + 1] + off, staff.rows[b] + off)
        val inner = (r0..r1).filter { r -> lines.all { kotlin.math.abs(r - it) > m + 1 } }
        if (inner.isEmpty()) return null
        val inked = { x: Int -> inner.any { db[x, it] } }
        val start = (from..to).firstOrNull(inked) ?: return null
        val gapMax = rnd(0.2 * space)
        var right = start
        var gap = 0
        var x = start
        while (x + 1 < db.width) {
            x++
            if (inked(x)) {
                right = x
                gap = 0
            } else if (++gap > gapMax) {
                break
            }
        }
        var left = start
        gap = 0
        x = start
        while (x - 1 >= 0) {
            x--
            if (inked(x)) {
                left = x
                gap = 0
            } else if (++gap > gapMax) {
                break
            }
        }
        val (crop, box) = Blobs.crop(db, left, r0, right, r1) ?: return null
        return crop to box
    }

    companion object {
        /** Never a time signature read wrong: the treble clef (taller than a staff), and what names no symbol. */
        private val NEVER_DIGITS = setOf("gClef", "gClef8vb", "slur", "beam", "composite", "other", "text")

        /** A system's opening clef starts within this many spaces of its staff's left end. */
        private const val OPENING_CLEF_REACH = 4.0

        /** How much taller than the staff (in spaces) a time signature's blob may be. */
        private const val TIME_SIGNATURE_MARGIN = 0.6

        /** A time signature digit is at most this wide (in spaces); "12" is 2.6 wide, "8" 1.5. */
        private const val MAX_DIGIT_WIDTH = 2.2

        val NON_SYMBOLS = setOf("other", "text", "slur", "beam", "composite")
        val FLAGS = setOf("flag8thUp", "flag8thDown", "flag16thUp", "flag16thDown", "flag32ndUp", "flag32ndDown")
        private val FLAG_NAMES = listOf("flag8th", "flag16th", "flag32nd")
        private val HEADS_AND_RESTS = setOf("noteheadBlack", "noteheadHalf", "noteheadWhole", "restWhole", "restQuarter", "rest8th", "rest16th", "rest32nd")

        /** How far (in spaces) out of a staff each class may stand; 4.5 for the others. */
        private val REACH: Map<String, Double> = mapOf(
            "restWhole" to 1.0, "restQuarter" to 0.6, "rest8th" to 0.8, "rest16th" to 1.0, "rest32nd" to 1.2,
            "gClef" to 1.0, "fClef" to 0.5, "cClef" to 0.5, "timeSigCommon" to 0.3, "timeSigCutCommon" to 0.6,
        ) + (0..9).associate { "timeSig$it" to 0.3 }
    }
}
