package org.fosscore.spike.musicxml

import java.io.File

/**
 * Verovio writes some music symbols as text in the SMuFL font (the note in a tempo mark,
 * "♩ = 96"). MuPDF has no such text font, so the symbol was missing. This draws those glyphs as
 * paths instead, taken from Verovio's own font data, and moves the rest of the text after them.
 *
 * Only glyphs at the start of a text element are handled, which covers tempo marks.
 */
class SmuflGlyphs(private val dataDir: String) {
    private class Glyph(val path: String, val advance: Float)

    private val glyphs = mutableMapOf<String, Glyph?>()
    private val advances = mutableMapOf<String, Map<String, Float>>()

    fun inline(svg: String): String {
        if (!svg.contains("font-family=\"")) return svg
        return LEADING_GLYPHS.replace(svg) { m ->
            val (x, y, rest, wrappers, font, size, text) = m.destructured
            val fontSize = size.toFloat()
            val parts = text.codePoints().toArray().map { code -> glyph(font, "%04X".format(code)) }
            if (parts.any { it == null }) return@replace m.value
            val scale = fontSize / 1000f
            var cursor = x.toFloat()
            val paths = StringBuilder()
            for (g in parts.filterNotNull()) {
                paths.append("<g transform=\"translate($cursor, $y) scale($scale, $scale)\">")
                    .append("<path transform=\"scale(1,-1)\" d=\"").append(g.path).append("\"/></g>")
                cursor += g.advance * scale
            }
            "$paths<text x=\"$cursor\" y=\"$y\"$rest>$wrappers"
        }
    }

    private fun glyph(font: String, code: String): Glyph? = glyphs.getOrPut("$font/$code") {
        val file = File(dataDir, "$font/$code.xml")
        if (!file.isFile) return@getOrPut null
        val path = PATH_D.find(file.readText())?.groupValues?.get(1) ?: return@getOrPut null
        Glyph(path, advances(font)[code] ?: 300f)
    }

    private fun advances(font: String): Map<String, Float> = advances.getOrPut(font) {
        val file = File(dataDir, "$font.xml")
        if (!file.isFile) return@getOrPut emptyMap()
        ADVANCE.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2].toFloat() }
    }

    companion object {
        // <text x y ...> then wrapper tspans, then the glyph tspan as the first content.
        private val LEADING_GLYPHS = Regex(
            """<text x="(-?[\d.]+)" y="(-?[\d.]+)"([^>]*)>((?:<tspan[^>]*>)*)<tspan font-family="([A-Za-z]+)" font-size="([\d.]+)px">([^<]+)</tspan>"""
        )
        private val PATH_D = Regex(""" d="([^"]+)"""")
        private val ADVANCE = Regex("""<g c="([0-9A-F]{4})"[^>]*h-a-x="([\d.]+)"""")
    }
}
