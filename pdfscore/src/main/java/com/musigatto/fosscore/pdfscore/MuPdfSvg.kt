package com.musigatto.fosscore.pdfscore

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
    /**
     * The page drawn in [svg]. Read with a small scanner of its tags rather than an XML library:
     * MuPDF's SVG is regular, and a DOM (with regular expressions for the paths) took about five
     * seconds a page on a slow tablet.
     */
    fun read(svg: ByteArray, smufl: Map<Int, String>): PageDrawing {
        val tags = SvgTags.scan(svg.toString(Charsets.UTF_8))
        val root = tags.firstOrNull { it.name == "svg" && !it.closing }
        val page = PageBuilder(root?.get("width")?.toDoubleOrNull() ?: 0.0, root?.get("height")?.toDoubleOrNull() ?: 0.0)

        // the glyph outlines, by id, as subpaths in em units (a clip path's are none)
        val outlines = mutableMapOf<String, List<List<Point>>>()
        var inClip = 0
        for (tag in tags) {
            when {
                tag.name == "clipPath" && !tag.selfClosing -> inClip += if (tag.closing) -1 else 1
                inClip == 0 && tag.name == "path" && !tag.closing && tag.get("id").isNotEmpty() ->
                    outlines[tag.get("id")] = flatten(tag.get("d"), Affine.IDENTITY).subpaths()
            }
        }

        // the drawing: groups compose their transforms; defs, clip paths, masks and images draw nothing
        val transforms = ArrayDeque<Affine>().apply { addLast(Affine.IDENTITY) }
        var hidden = 0
        for (tag in tags) {
            if (tag.name in HIDDEN && !tag.selfClosing) {
                hidden += if (tag.closing) -1 else 1
                continue
            }
            if (hidden > 0 || tag.name in HIDDEN) continue
            if (tag.name == "g" || tag.name == "svg") {
                when {
                    tag.closing -> if (transforms.size > 1) transforms.removeLast()
                    !tag.selfClosing -> transforms.addLast(transforms.last() * Affine.parse(tag.get("transform")))
                }
                continue
            }
            if (tag.closing) continue
            val at = transforms.last() * Affine.parse(tag.get("transform"))
            when (tag.name) {
                "path" -> if (tag.get("id").isEmpty()) path(page, tag, at)
                "use" -> glyph(page, tag, at, outlines, smufl)
            }
        }
        return page.build()
    }

    private val HIDDEN = setOf("defs", "clipPath", "mask", "image")

    private fun path(page: PageBuilder, element: SvgTags.Tag, at: Affine) {
        val flat = flatten(element.get("d"), at)
        val stroke = element.get("stroke")
        if (stroke.isNotEmpty() && stroke != "none") {
            val width = element.get("stroke-width").toDoubleOrNull() ?: 1.0
            page.stroke(flat.subpaths(), width * at.scale)
        }
        if (element.get("fill") != "none") page.fill(flat.subpaths(), flat.curved)
    }

    private fun glyph(page: PageBuilder, element: SvgTags.Tag, at: Affine, outlines: Map<String, List<List<Point>>>, smufl: Map<Int, String>) {
        val id = element.get("xlink:href").removePrefix("#")
        val text = element.get("data-text")
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
        val tokens = PathTokens(d)
        var i = 0
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        var controlX = 0.0
        var controlY = 0.0
        var command = ' '
        fun hasNumber(): Boolean = i < tokens.size && tokens.isNumber(i)
        fun number(): Double = if (hasNumber()) tokens.value(i++) else 0.0
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
            if (!hasNumber()) command = tokens.command(i++)
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

    /** A path's data as commands (letters) and numbers, read in one pass. */
    private class PathTokens(d: String) {
        private val kinds = CharArray(d.length)
        private val values = DoubleArray(d.length)
        var size = 0
            private set

        init {
            var at = 0
            while (at < d.length) {
                val c = d[at]
                when {
                    c.isLetter() -> {
                        kinds[size++] = c
                        at++
                    }
                    c == '-' || c == '+' || c == '.' || c.isDigit() -> {
                        val end = numberEnd(d, at)
                        // a sign or point with no digit is no number
                        d.substring(at, end).toDoubleOrNull()?.let {
                            values[size] = it
                            kinds[size++] = NUMBER
                        }
                        at = maxOf(end, at + 1)
                    }
                    else -> at++ // a space or comma
                }
            }
        }

        fun isNumber(i: Int) = kinds[i] == NUMBER
        fun value(i: Int) = values[i]
        fun command(i: Int) = kinds[i]

        private companion object {
            const val NUMBER = '#'
        }
    }

    /** Where a number written from [start] ends: a sign, digits, one point, digits, an exponent ("-1.5e-3", ".5"). */
    private fun numberEnd(text: String, start: Int): Int {
        var at = start
        if (at < text.length && (text[at] == '-' || text[at] == '+')) at++
        while (at < text.length && text[at].isDigit()) at++
        if (at < text.length && text[at] == '.') {
            at++
            while (at < text.length && text[at].isDigit()) at++
        }
        if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
            var exponent = at + 1
            if (exponent < text.length && (text[exponent] == '-' || text[exponent] == '+')) exponent++
            if (exponent < text.length && text[exponent].isDigit()) {
                at = exponent
                while (at < text.length && text[at].isDigit()) at++
            }
        }
        return at
    }

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

            /** MuPDF writes only matrix(…) transforms; anything else is taken as none. */
            fun parse(text: String): Affine {
                val open = text.indexOf("matrix(")
                if (open < 0) return IDENTITY
                val close = text.indexOf(')', open)
                if (close < 0) return IDENTITY
                val values = PathTokens(text.substring(open + 7, close))
                if (values.size != 6 || (0 until 6).any { !values.isNumber(it) }) return IDENTITY
                return Affine(values.value(0), values.value(1), values.value(2), values.value(3), values.value(4), values.value(5))
            }
        }
    }
}
