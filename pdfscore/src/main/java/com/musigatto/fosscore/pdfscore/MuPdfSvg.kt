package com.musigatto.fosscore.pdfscore

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reads a PDF page as MuPDF writes it in SVG with its text as paths (`DocumentWriter`, format
 * "svg", options "text=path"): the app's way to see a page whole in one native call. Each glyph is
 * a `<use data-text="…" xlink:href="#font_N_M" transform="matrix(…)">` of an outline defined once
 * (1 unit per em, y up), so a glyph brings its character (a SMuFL code point for a music font),
 * its origin, its size and its ink; strokes and fills are paths with their transform.
 *
 * Glyphs whose character is a SMuFL code point are music, named by it ([smufl]); the others are
 * text. MuPDF does not say which font a glyph is in, only its number: a music font that keeps no
 * code point is read as text for now.
 */
object MuPdfSvg {
    fun read(svg: ByteArray, smufl: Map<Int, String>): PageDrawing {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        val root = ByteArrayInputStream(svg).use { factory.newDocumentBuilder().parse(it) }.documentElement
        val width = root.getAttribute("width").toDoubleOrNull() ?: 0.0
        val height = root.getAttribute("height").toDoubleOrNull() ?: 0.0
        val page = PageBuilder(width, height)

        // the glyph outlines, by id, as subpaths in em units
        val outlines = mutableMapOf<String, List<List<Point>>>()
        fun collect(element: Element) {
            for (child in children(element)) {
                if (child.tagName == "path" && child.getAttribute("id").isNotEmpty()) {
                    outlines[child.getAttribute("id")] = flatten(child.getAttribute("d"), Affine.IDENTITY).subpaths()
                } else if (child.tagName != "clipPath") {
                    collect(child)
                }
            }
        }
        collect(root)

        fun walk(element: Element, parent: Affine) {
            for (child in children(element)) {
                val at = parent * Affine.parse(child.getAttribute("transform"))
                when (child.tagName) {
                    "defs", "clipPath", "mask", "image" -> Unit
                    "g", "svg" -> walk(child, at)
                    "path" -> if (child.getAttribute("id").isEmpty()) path(page, child, at)
                    "use" -> glyph(page, child, at, outlines, smufl)
                }
            }
        }
        walk(root, Affine.IDENTITY)
        return page.build()
    }

    private fun path(page: PageBuilder, element: Element, at: Affine) {
        val flat = flatten(element.getAttribute("d"), at)
        val stroke = element.getAttribute("stroke")
        if (stroke.isNotEmpty() && stroke != "none") {
            val width = element.getAttribute("stroke-width").toDoubleOrNull() ?: 1.0
            page.stroke(flat.subpaths(), width * at.scale)
        }
        if (element.getAttribute("fill") != "none") page.fill(flat.subpaths(), flat.curved)
    }

    private fun glyph(page: PageBuilder, element: Element, at: Affine, outlines: Map<String, List<List<Point>>>, smufl: Map<Int, String>) {
        val id = element.getAttribute("xlink:href").removePrefix("#")
        val text = element.getAttribute("data-text")
        val outline = outlines[id].orEmpty()
        val font = id.substringBeforeLast('_')
        val x = at.e
        val y = at.f
        val size = at.scale
        val points = outline.flatten().map { at.apply(it.x, it.y) }
        if (MusicFonts.isMusic(font, text, -1)) {
            val box = if (points.isEmpty()) Box(x, y, x, y)
            else Box(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
            val name = SmuflCodes.name(smufl, SmuflCodes.codePoint(text, -1))
            page.glyph(DrawnGlyph(name, box, font = font, code = text.firstOrNull()?.code ?: -1, x = x, y = y, size = size))
        } else {
            // the advance is not written: the ink's right edge stands for it
            val right = points.maxOfOrNull { it.x } ?: x
            page.text(text, font, x, y, size, right - x)
        }
    }

    /** The path [d] (absolute or relative M, L, H, V, C, S, Q, T, Z) on the page through [at]. */
    private fun flatten(d: String, at: Affine): PathFlattener {
        val flat = PathFlattener()
        val tokens = TOKEN.findAll(d).map { it.value }.toList()
        var i = 0
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        var controlX = 0.0
        var controlY = 0.0
        var command = ' '
        fun number(): Double = tokens.getOrNull(i)?.toDoubleOrNull()?.also { i++ } ?: 0.0
        fun hasNumber(): Boolean = tokens.getOrNull(i)?.toDoubleOrNull() != null
        fun move(nx: Double, ny: Double) {
            x = nx
            y = ny
            startX = nx
            startY = ny
            val p = at.apply(nx, ny)
            flat.moveTo(p.x, p.y)
        }
        fun line(nx: Double, ny: Double) {
            x = nx
            y = ny
            val p = at.apply(nx, ny)
            flat.lineTo(p.x, p.y)
        }
        fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, nx: Double, ny: Double) {
            val a = at.apply(x1, y1)
            val b = at.apply(x2, y2)
            val c = at.apply(nx, ny)
            flat.curveTo(a.x, a.y, b.x, b.y, c.x, c.y)
            controlX = x2
            controlY = y2
            x = nx
            y = ny
        }
        while (i < tokens.size) {
            if (!hasNumber()) command = tokens[i++].first()
            val relative = command.isLowerCase()
            val ox = if (relative) x else 0.0
            val oy = if (relative) y else 0.0
            val previous = command
            when (command.uppercaseChar()) {
                'M' -> {
                    move(ox + number(), oy + number())
                    // numbers after a move are lines
                    command = if (relative) 'l' else 'L'
                }
                'L' -> line(ox + number(), oy + number())
                'H' -> line(ox + number(), y)
                'V' -> line(x, oy + number())
                'C' -> cubic(ox + number(), oy + number(), ox + number(), oy + number(), ox + number(), oy + number())
                'S' -> {
                    val smooth = previous.uppercaseChar() in "CS"
                    val x1 = if (smooth) 2 * x - controlX else x
                    val y1 = if (smooth) 2 * y - controlY else y
                    cubic(x1, y1, ox + number(), oy + number(), ox + number(), oy + number())
                }
                'Q', 'T' -> {
                    val qx: Double
                    val qy: Double
                    if (command.uppercaseChar() == 'Q') {
                        qx = ox + number()
                        qy = oy + number()
                    } else {
                        qx = 2 * x - controlX
                        qy = 2 * y - controlY
                    }
                    val nx = ox + number()
                    val ny = oy + number()
                    val sx = x
                    val sy = y
                    cubic(sx + 2.0 / 3 * (qx - sx), sy + 2.0 / 3 * (qy - sy), nx + 2.0 / 3 * (qx - nx), ny + 2.0 / 3 * (qy - ny), nx, ny)
                    controlX = qx
                    controlY = qy
                }
                'Z' -> {
                    flat.closePath()
                    x = startX
                    y = startY
                }
                else -> i++ // a command not read here (arcs): skip its numbers
            }
        }
        return flat
    }

    private fun children(element: Element): List<Element> {
        val nodes = element.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private val TOKEN = Regex("""[A-Za-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    /** An affine transform as SVG writes it: x' = a x + c y + e, y' = b x + d y + f. */
    private data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        operator fun times(o: Affine) = Affine(
            a * o.a + c * o.b, b * o.a + d * o.b,
            a * o.c + c * o.d, b * o.c + d * o.d,
            a * o.e + c * o.f + e, b * o.e + d * o.f + f,
        )

        fun apply(x: Double, y: Double) = Point(a * x + c * y + e, b * x + d * y + f)

        /** How much it scales lengths (a stroke's width, a glyph's size). */
        val scale: Double get() = sqrt(abs(a * d - b * c))

        companion object {
            val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            private val MATRIX = Regex("""matrix\(([^)]*)\)""")
            private val NUMBER = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

            /** MuPDF writes only matrix(…) transforms; anything else is taken as none. */
            fun parse(text: String): Affine {
                val values = MATRIX.find(text)?.groupValues?.get(1)?.let { m -> NUMBER.findAll(m).map { it.value.toDouble() }.toList() }
                return if (values?.size == 6) Affine(values[0], values[1], values[2], values[3], values[4], values[5]) else IDENTITY
            }
        }
    }
}
