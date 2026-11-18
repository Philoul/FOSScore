package com.musigatto.fosscore.pdfscore

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A name a symbol may have, how well it looks like it (0 to 1), and the names drawn just like it
 * ([CommonSymbols.LOOK_ALIKE], [name] first), which only where it sits tells apart.
 */
data class SymbolMatch(val name: String, val score: Double, val alike: List<String> = listOf(name))

/**
 * Names a symbol by its look, whatever drew it: a PDF's font glyph, a PDF's outline or a scan's
 * blob, given as an outline in staff spaces. It is compared with every reference font's drawing of
 * every symbol: the shapes each fitted to their own bounds (within a pixel, as two fonts' strokes
 * never fall exactly on each other), then their sizes in spaces. So a font that is none of the
 * references, but draws a flat as a flat, is still read; and a font is added to the references
 * without changing any of this.
 */
class SymbolClassifier private constructor(references: References, private val tuning: Tuning) {
    constructor(symbols: List<FontSymbol>, tuning: Tuning = Tuning()) : this(References(build(symbols, tuning)), tuning)

    /** The references, wrapped: a list of them and a list of font symbols are one type to the JVM. */
    private class References(val templates: List<Template>)

    private val templates = references.templates

    /**
     * [minScore]: below it a symbol is left unnamed; [margin]: how far ahead of the next name the
     * best one must be, unless it scores [sure]; [minSizeAgreement]: a symbol and a reference of
     * sizes further apart are not compared; [sizeWeight]: how much the size counts against the shape;
     * [resolution]: the side of the masks the shapes are compared on, in pixels; [tolerance]: how
     * many pixels apart two strokes still fall on each other.
     */
    data class Tuning(
        val minScore: Double = 0.45,
        val margin: Double = 0.03,
        val sure: Double = 0.75,
        val minSizeAgreement: Double = 0.45,
        val sizeWeight: Double = 0.5,
        val resolution: Int = 32,
        val tolerance: Int = 1,
    )

    /** A reference drawing: its fitted mask, and the same grown by a pixel. */
    private class Template(val name: String, val box: Box, val mask: Mask, val near: Mask)

    /**
     * Writes the references (names, sizes, masks) so that [load] gives them back without reading
     * and rasterising the fonts again: a slow device builds them once.
     */
    fun save(out: java.io.DataOutputStream) {
        out.writeInt(FORMAT)
        out.writeInt(tuning.resolution)
        out.writeInt(tuning.tolerance)
        out.writeInt(templates.size)
        for (template in templates) {
            out.writeUTF(template.name)
            with(template.box) { listOf(left, top, right, bottom).forEach { out.writeDouble(it) } }
            out.writeInt(template.mask.width)
            out.writeInt(template.mask.height)
            out.write(template.mask.toBytes())
        }
    }

    /** The names [polygons] (in staff spaces) may have, best first, each once (its best font). */
    fun rank(polygons: List<List<Point>>): List<SymbolMatch> {
        if (polygons.isEmpty()) return emptyList()
        val box = FontSymbol.bounds(polygons)
        val mask = fitted(polygons, box, tuning.resolution)
        val near = near(mask)
        val best = HashMap<String, Double>()
        for (template in templates) {
            val size = sizeAgreement(box, template.box)
            if (size < tuning.minSizeAgreement) continue
            val score = Mask.closeness(mask, near, template.mask, template.near) * size.pow(tuning.sizeWeight)
            if (score > (best[template.name] ?: 0.0)) best[template.name] = score
        }
        return best.map { SymbolMatch(it.key, it.value, CommonSymbols.alike(it.key)) }.sortedByDescending { it.score }
    }

    /**
     * The symbol's name, or null when nothing looks enough like it, or two names look alike enough
     * to be a guess. Same-looking symbols of the references go to the plainest name
     * ([CommonSymbols.rank], then the shortest).
     */
    fun name(polygons: List<List<Point>>): SymbolMatch? {
        val ranking = rank(polygons)
        val top = ranking.firstOrNull()?.takeIf { it.score >= tuning.minScore } ?: return null
        val tied = ranking.takeWhile { it.score >= top.score - TIE }
        val next = ranking.getOrNull(tied.size)?.score ?: 0.0
        if (top.score < tuning.sure && top.score - next < tuning.margin) return null
        return tied.minWithOrNull(compareBy<SymbolMatch>({ CommonSymbols.rank(it.name) }, { it.name.length }))
    }

    /** The mask grown by [Tuning.tolerance] pixels: where a stroke of another font may fall. */
    private fun near(mask: Mask): Mask = grown(mask, tuning.tolerance)

    companion object {
        private const val TIE = 0.01

        /** The layout [save] writes: a file of another one is not read. */
        private const val FORMAT = 1

        /** The references built from the fonts' [symbols]. */
        private fun build(symbols: List<FontSymbol>, tuning: Tuning): List<Template> =
            symbols.filter { it.box.width > 0 || it.box.height > 0 }.map {
                val mask = fitted(it.polygons, it.box, tuning.resolution)
                Template(CommonSymbols.canonical(it.name), it.box, mask, grown(mask, tuning.tolerance))
            }

        /**
         * The references [SymbolClassifier.save] wrote, with [tuning] (its resolution and tolerance
         * as they were saved); null when the data is of another layout or cut short.
         */
        fun load(input: java.io.DataInputStream, tuning: Tuning = Tuning()): SymbolClassifier? = runCatching {
            if (input.readInt() != FORMAT) return null
            val resolved = tuning.copy(resolution = input.readInt(), tolerance = input.readInt())
            val templates = List(input.readInt()) {
                val name = input.readUTF()
                val box = Box(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble())
                val width = input.readInt()
                val height = input.readInt()
                val bytes = ByteArray((width * height + 7) / 8).also { input.readFully(it) }
                val mask = Mask.fromBytes(width, height, bytes)
                Template(name, box, mask, grown(mask, resolved.tolerance))
            }
            SymbolClassifier(References(templates), resolved)
        }.getOrNull()

        /** The mask grown by [tolerance] pixels: where a stroke of another font may fall. */
        private fun grown(mask: Mask, tolerance: Int): Mask = (1..tolerance).fold(mask) { grown, _ -> grown.dilated() }

        /** A side under this (in spaces) counts as this: a line's thickness says little. */
        private const val MIN_SIDE = 0.15

        /** 1 for the same width and height, less the more they differ. */
        private fun sizeAgreement(a: Box, b: Box): Double {
            fun ratio(x: Double, y: Double) = min(max(x, MIN_SIDE), max(y, MIN_SIDE)) / max(max(x, MIN_SIDE), max(y, MIN_SIDE))
            return ratio(a.width, b.width) * ratio(a.height, b.height)
        }

        /** The shape stretched to a square: compares shapes whatever their size and proportions. */
        private fun fitted(polygons: List<List<Point>>, box: Box, resolution: Int): Mask {
            val width = max(box.width, 1e-6)
            val height = max(box.height, 1e-6)
            val unit = polygons.map { polygon -> polygon.map { Point((it.x - box.left) / width, (it.y - box.top) / height) } }
            return Mask.rasterize(unit, Box(0.0, 0.0, 1.0, 1.0), resolution.toDouble())
        }
    }
}
