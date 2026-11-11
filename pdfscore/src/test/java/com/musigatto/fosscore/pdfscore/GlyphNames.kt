package com.musigatto.fosscore.pdfscore

import org.apache.fontbox.cff.CFFCIDFont
import org.apache.fontbox.ttf.OTFParser
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.json.JSONObject
import java.awt.geom.AffineTransform
import java.awt.geom.GeneralPath
import java.awt.geom.PathIterator
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Names a PDF's music-font glyphs by their look: each subset glyph's outline is compared with the
 * reference font's glyphs (Leland, MScore), first by their bounds, then as [Mask]s, the same way
 * an outline or a scan's symbol will be.
 */
class GlyphNames(fontsDir: File) {
    private class Reference(val name: String, val polygons: List<List<Point>>, val box: Box)

    private val smuflNames: Map<Int, String> = JSONObject(File(fontsDir, "glyphnames.json").readText()).let { json ->
        json.keys().asSequence().toList().associateBy { json.getJSONObject(it).getString("codepoint").removePrefix("U+").toInt(16) }
    }
    private val leland by lazy { references(File(fontsDir, "Leland.otf")) }
    private val mscore by lazy { references(File(fontsDir, "MScore.otf")) }

    /**
     * The glyph's SMuFL name, or null when nothing looks like it. Same-looking glyphs (a font keeps
     * one shape under several names) are told apart by the plainest, shortest name. Not cached by
     * font and code: two PDFs' subsets can share a font name and give the same code to other glyphs.
     */
    fun name(glyph: FontGlyph): String? {
        val ranking = ranking(glyph)
        val top = ranking.firstOrNull()?.takeIf { it.second >= 0.65 } ?: return null
        return ranking.takeWhile { it.second >= top.second - 0.01 }.minByOrNull { it.first.length }?.first
    }

    /** The reference glyphs closest to [glyph], best first, with their overlap (for probing). */
    fun ranking(glyph: FontGlyph): List<Pair<String, Double>> {
        val refs = if (glyph.font.contains("MScore", ignoreCase = true)) mscore else leland
        return ranking(flatten(glyph.outline, 1.0), refs)
    }

    private fun ranking(polygons: List<List<Point>>, refs: List<Reference>): List<Pair<String, Double>> {
        if (polygons.isEmpty()) return emptyList()
        val box = bounds(polygons)
        val tolerance = max(20.0, 0.08 * max(box.width, box.height))
        val candidates = refs.filter {
            abs(it.box.left - box.left) < tolerance && abs(it.box.right - box.right) < tolerance &&
                abs(it.box.top - box.top) < tolerance && abs(it.box.bottom - box.bottom) < tolerance
        }
        return candidates.map { ref ->
            val frame = Box(
                minOf(box.left, ref.box.left), minOf(box.top, ref.box.top),
                maxOf(box.right, ref.box.right), maxOf(box.bottom, ref.box.bottom),
            )
            val scale = 48.0 / max(frame.width, frame.height)
            ref.name to Mask.rasterize(polygons, frame, scale).iou(Mask.rasterize(ref.polygons, frame, scale))
        }.sortedByDescending { it.second }
    }

    private fun references(file: File): List<Reference> {
        val otf = OTFParser().parse(RandomAccessReadBufferedFile(file))
        val cmap = otf.unicodeCmapLookup ?: return emptyList()
        val toThousand = 1000.0 / otf.unitsPerEm
        val cff = if (otf.isPostScript) otf.cff.font else null
        return smuflNames.mapNotNull { (codepoint, name) ->
            val gid = cmap.getGlyphId(codepoint)
            if (gid <= 0) return@mapNotNull null
            val path = when {
                cff is CFFCIDFont -> cff.getType2CharString(cff.charset.getCIDForGID(gid)).path
                cff != null -> cff.getType2CharString(gid).path
                else -> otf.glyph.getGlyph(gid)?.path
            } ?: return@mapNotNull null
            val polygons = flatten(path, toThousand)
            if (polygons.isEmpty()) null else Reference(name, polygons, bounds(polygons))
        }
    }

    companion object {
        /** A font outline (y up) as polygons, y flipped down like the page, scaled by [scale]. */
        fun flatten(path: GeneralPath, scale: Double): List<List<Point>> {
            val result = mutableListOf<List<Point>>()
            var current = mutableListOf<Point>()
            val it = path.getPathIterator(AffineTransform.getScaleInstance(scale, -scale), 1.0)
            val c = DoubleArray(6)
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    PathIterator.SEG_MOVETO -> {
                        if (current.size > 2) result += current
                        current = mutableListOf(Point(c[0], c[1]))
                    }
                    PathIterator.SEG_LINETO -> current += Point(c[0], c[1])
                }
                it.next()
            }
            if (current.size > 2) result += current
            return result
        }

        fun bounds(polygons: List<List<Point>>): Box {
            val points = polygons.flatten()
            return Box(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        }
    }
}
