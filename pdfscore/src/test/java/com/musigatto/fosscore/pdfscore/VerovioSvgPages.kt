package com.musigatto.fosscore.pdfscore

import org.w3c.dom.Element
import java.awt.geom.AffineTransform
import java.awt.geom.GeneralPath
import java.awt.geom.Point2D
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a page engraved by Verovio (its SVG) into the [PageDrawing] a PDF of it would give, so the
 * recognition can be tried on scores engraved in other fonts, with their MusicXML as the exact
 * reference (tests only). Like a PDF from a notation program: the music font's glyphs are glyphs
 * (named by their look, as [PdfBoxPages] does), straight strokes are lines, beams, ties and slurs
 * are filled outlines. Verovio draws augmentation dots as ellipses: they are named by their look
 * too, as an outline will be.
 */
object VerovioSvgPages {
    private const val A4_WIDTH = 595.2756
    private val NUMBER = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
    private val TRANSFORM = Regex("""(translate|scale|rotate|matrix)\(([^)]*)\)""")
    private val MUSIC_FAMILIES = setOf("Bravura", "Gootville", "Leipzig", "Leland", "Petaluma")

    /**
     * The page of [svg], engraved in the font [font] whose Verovio data is in [fontsDir]; its glyphs
     * named with [names] (null leaves them unnamed).
     */
    fun read(svg: File, font: String, fontsDir: File, names: GlyphNames?): PageDrawing {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = svg.inputStream().use { factory.newDocumentBuilder().parse(it) }
        val unitsPerEm = Regex("""units-per-em="(\d+)"""").find(File(fontsDir, "$font.xml").readText())
            ?.groupValues?.get(1)?.toDouble() ?: 1000.0
        return Reader(font, unitsPerEm, names).apply { walk(document.documentElement, AffineTransform()) }.result()
    }

    private class Reader(val font: String, val unitsPerEm: Double, val names: GlyphNames?) {
        val definitions = mutableMapOf<String, List<List<Point>>>() // glyph id → outline, font units, y up
        val glyphs = mutableListOf<DrawnGlyph>()
        val outlineOf = linkedMapOf<Int, GeneralPath>() // code → outline, 1000 units per em
        val dots = mutableListOf<List<Point>>()
        val lines = mutableListOf<DrawnLine>()
        val shapes = mutableListOf<DrawnShape>()
        val texts = mutableListOf<DrawnText>()
        var width = A4_WIDTH
        var height = A4_WIDTH * 29700 / 21000

        fun walk(element: Element, parent: AffineTransform) {
            val at = AffineTransform(parent)
            if (element.tagName == "svg" && element.getAttribute("class") == "definition-scale") {
                val box = element.getAttribute("viewBox").split(' ').mapNotNull { it.toDoubleOrNull() }
                if (box.size == 4) {
                    at.scale(A4_WIDTH / box[2], A4_WIDTH / box[2])
                    height = box[3] * A4_WIDTH / box[2]
                }
            }
            transform(element.getAttribute("transform"))?.let { at.concatenate(it) }
            when (element.tagName) {
                "defs" -> {
                    children(element).forEach { g ->
                        val d = children(g).firstOrNull { it.tagName == "path" }?.getAttribute("d")
                        val polygons = d?.let { SvgPath.parse(it) }
                        if (polygons != null) definitions[g.getAttribute("id")] = polygons
                    }
                    return
                }
                "use" -> use(element, at)
                "path" -> path(element, at)
                "polygon" -> filled(points(element.getAttribute("points")), at, curved = false)
                "polyline" -> if (element.getAttribute("fill") == "none") stroke(points(element.getAttribute("points")), at, element) else filled(points(element.getAttribute("points")), at, curved = false)
                "rect" -> {
                    val x = element.getAttribute("x").toDoubleOrNull() ?: 0.0
                    val y = element.getAttribute("y").toDoubleOrNull() ?: 0.0
                    val w = element.getAttribute("width").toDoubleOrNull() ?: 0.0
                    val h = element.getAttribute("height").toDoubleOrNull() ?: 0.0
                    filled(listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h), Point(x, y)), at, curved = false)
                }
                "ellipse" -> ellipse(element, at)
                "text" -> {
                    text(element, at)
                    return
                }
            }
            children(element).forEach { walk(it, at) }
        }

        fun use(element: Element, at: AffineTransform) {
            val id = (element.getAttribute("xlink:href").ifEmpty { element.getAttribute("href") }).removePrefix("#")
            val outline = definitions[id] ?: return
            val code = id.substringBefore('-').toIntOrNull(16) ?: return
            // the glyph's outline is in font units, y up
            val glyphAt = AffineTransform(at).apply { scale(1.0, -1.0) }
            val onPage = outline.map { polygon -> polygon.map { map(glyphAt, it.x, it.y) } }
            val origin = map(at, 0.0, 0.0)
            val size = unitsPerEm * kotlin.math.sqrt(kotlin.math.abs(at.determinant))
            val path = GeneralPath()
            outline.forEach { polygon ->
                polygon.forEachIndexed { i, p ->
                    val x = p.x * 1000 / unitsPerEm
                    val y = p.y * 1000 / unitsPerEm
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.closePath()
            }
            // one font per page: a code is one glyph, named with the others in result()
            outlineOf.getOrPut(code) { path }
            glyphs += DrawnGlyph(null, FontSymbol.bounds(onPage), font = font, code = code, x = origin.x, y = origin.y, size = size)
        }

        fun path(element: Element, at: AffineTransform) {
            val d = element.getAttribute("d")
            if (d.any { it in "CcQqSsTtAa" }) {
                SvgPath.parse(d)?.forEach { polygon -> filled(polygon, at, curved = true) }
            } else {
                stroke(NUMBER.findAll(d).map { it.value.toDouble() }.chunked(2).filter { it.size == 2 }.map { Point(it[0], it[1]) }.toList(), at, element)
            }
        }

        fun stroke(points: List<Point>, at: AffineTransform, element: Element) {
            val width = (element.getAttribute("stroke-width").toDoubleOrNull() ?: 1.0) * kotlin.math.sqrt(kotlin.math.abs(at.determinant))
            points.map { map(at, it.x, it.y) }.zipWithNext().forEach { (a, b) -> lines += DrawnLine(a.x, a.y, b.x, b.y, width) }
        }

        fun filled(points: List<Point>, at: AffineTransform, curved: Boolean) {
            if (points.size < 3) return
            val shape = DrawnShape(points.map { map(at, it.x, it.y) }, curved)
            shape.asLine()?.let { lines += it } ?: run { shapes += shape }
        }

        fun ellipse(element: Element, at: AffineTransform) {
            val cx = element.getAttribute("cx").toDoubleOrNull() ?: return
            val cy = element.getAttribute("cy").toDoubleOrNull() ?: return
            val rx = element.getAttribute("rx").toDoubleOrNull() ?: return
            val ry = element.getAttribute("ry").toDoubleOrNull() ?: return
            dots += (0 until 24).map { i ->
                val angle = 2 * Math.PI * i / 24
                map(at, cx + rx * kotlin.math.cos(angle), cy + ry * kotlin.math.sin(angle))
            }
        }

        fun text(element: Element, at: AffineTransform) {
            val parts = mutableListOf<Pair<String, Double>>()
            fun collect(e: Element, size: Double, music: Boolean) {
                val s = e.getAttribute("font-size").removeSuffix("px").toDoubleOrNull() ?: size
                val m = music || e.getAttribute("font-family") in MUSIC_FAMILIES
                val nodes = e.childNodes
                for (i in 0 until nodes.length) {
                    when (val node = nodes.item(i)) {
                        is Element -> collect(node, s, m)
                        else -> if (node.nodeType == org.w3c.dom.Node.TEXT_NODE && !m && node.textContent.isNotBlank()) parts += node.textContent to s
                    }
                }
            }
            collect(element, 0.0, false)
            if (parts.isEmpty()) return
            val text = parts.joinToString("") { it.first }.trim()
            val size = parts.maxOf { it.second } * kotlin.math.sqrt(kotlin.math.abs(at.determinant))
            val x = element.getAttribute("x").toDoubleOrNull() ?: 0.0
            val y = element.getAttribute("y").toDoubleOrNull() ?: 0.0
            val p = map(at, x, y)
            // about half an em per character, for anchors other than the start
            val width = 0.5 * size * text.length
            val left = when (element.getAttribute("text-anchor")) {
                "middle" -> p.x - width / 2
                "end" -> p.x - width
                else -> p.x
            }
            texts += DrawnText(text, "Times", left, p.y, size)
        }

        fun result(): PageDrawing {
            // a dot is named in staff spaces, the glyphs' (4 spaces per em)
            val space = glyphs.map { it.size / 4 }.filter { it > 0 }.sorted().let { it.getOrNull(it.size / 2) } ?: 5.0
            val named = dots.map { polygon ->
                val box = FontSymbol.bounds(listOf(polygon))
                val path = GeneralPath()
                polygon.forEachIndexed { i, p ->
                    val x = (p.x - box.left) / space * 250
                    val y = -(p.y - box.bottom) / space * 250
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.closePath()
                DrawnGlyph(names?.name(FontGlyph("", -1, path)), box, confidence = 0.9)
            }
            // the page's glyphs named together, as one font's ([GlyphNames.names])
            val codes = outlineOf.keys.toList()
            val nameOf = codes.zip(names?.names(codes.map { FontGlyph(font, it, outlineOf.getValue(it)) }) ?: codes.map { null }).toMap()
            return PageDrawing(width, height, glyphs.map { it.copy(name = nameOf[it.code]) } + named, lines, shapes, texts)
        }

        fun map(at: AffineTransform, x: Double, y: Double): Point {
            val p = at.transform(Point2D.Double(x, y), null)
            return Point(p.x, p.y)
        }

        fun points(text: String): List<Point> =
            NUMBER.findAll(text).map { it.value.toDouble() }.chunked(2).filter { it.size == 2 }.map { Point(it[0], it[1]) }.toList()

        fun children(element: Element): List<Element> =
            (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }

        fun transform(text: String): AffineTransform? {
            if (text.isBlank()) return null
            val at = AffineTransform()
            TRANSFORM.findAll(text).forEach { match ->
                val v = NUMBER.findAll(match.groupValues[2]).map { it.value.toDouble() }.toList()
                when (match.groupValues[1]) {
                    "translate" -> at.translate(v.getOrElse(0) { 0.0 }, v.getOrElse(1) { 0.0 })
                    "scale" -> at.scale(v.getOrElse(0) { 1.0 }, v.getOrElse(1) { v.getOrElse(0) { 1.0 } })
                    "rotate" -> at.rotate(Math.toRadians(v.getOrElse(0) { 0.0 }), v.getOrElse(1) { 0.0 }, v.getOrElse(2) { 0.0 })
                    "matrix" -> if (v.size == 6) at.concatenate(AffineTransform(v[0], v[1], v[2], v[3], v[4], v[5]))
                }
            }
            return at
        }
    }
}
