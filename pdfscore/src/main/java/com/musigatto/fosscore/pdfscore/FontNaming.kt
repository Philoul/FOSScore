package com.musigatto.fosscore.pdfscore

/**
 * Names the glyphs of one font of a document, all together. A SMuFL font gives each symbol its own
 * code point (E0A4 is a black notehead in every SMuFL font): when a font's code points agree with
 * what its glyphs look like for most of them, the font follows SMuFL, and the code names each of
 * its glyphs exactly, however unusual its drawing (a handwritten font). Otherwise (a font of its
 * own codes, or a scan) the look alone names them.
 *
 * [smufl] is SMuFL's code points and their names ([SmuflCodes.read]).
 */
class FontNaming(private val classifier: SymbolClassifier, private val smufl: Map<Int, String>) {
    /** A glyph of the font: its SMuFL [codePoint], if it has one, and its outline in staff spaces. */
    class Glyph(val codePoint: Int?, val polygons: List<List<Point>>)

    /** The names of [glyphs], in their order (null: not a symbol recognition looks for, or not recognised). */
    fun names(glyphs: List<Glyph>): List<String?> {
        val byShape = glyphs.map { classifier.name(it.polygons)?.name }
        val byCode = glyphs.map { glyph -> glyph.codePoint?.let { smufl[it] }?.let(CommonSymbols::canonical) }
        val compared = glyphs.indices.filter { byShape[it] != null && byCode[it] != null }
        val agree = compared.count { byShape[it] == byCode[it] }
        if (compared.isEmpty() || agree * 2 < compared.size) return byShape
        return glyphs.indices.map { i ->
            when {
                // a SMuFL symbol recognition does not look for stays unnamed: not taken for one it looks like
                glyphs[i].codePoint?.let { it in smufl } == true -> byCode[i]?.takeIf { it in COMMON }
                else -> byShape[i]
            }
        }
    }

    private companion object {
        val COMMON = CommonSymbols.NAMES.map(CommonSymbols::canonical).toSet()
    }
}

/** SMuFL's code points and the names they stand for. */
object SmuflCodes {
    private val GLYPH = Regex("""<g c="([0-9A-Fa-f]+)"[^>]* n="([^"]+)"""")

    /** Read from a font index of Verovio's data (`<font>.xml`), which lists every glyph's code and name. */
    fun read(index: String): Map<Int, String> =
        GLYPH.findAll(index).associate { it.groupValues[1].toInt(16) to it.groupValues[2] }

    /** The SMuFL code point a glyph carries: its Unicode value or else its code, in SMuFL's range; null without one. */
    fun codePoint(unicode: String?, code: Int): Int? =
        unicode?.takeIf { it.length == 1 }?.get(0)?.code?.takeIf { it in RANGE } ?: code.takeIf { it in RANGE }

    /** SMuFL's symbols are in Unicode's private use area. */
    private val RANGE = 0xE000..0xF8FF
}

/** Which of a PDF's glyphs are music symbols, to be named, rather than text. */
object MusicFonts {
    /** Music fonts known by name, for those that keep no SMuFL code point. */
    private val NAMES = listOf("Leland", "MScore", "Bravura", "Emmentaler", "Petaluma", "Gonville", "Maestro")

    fun isMusic(fontName: String, unicode: String?, code: Int): Boolean =
        SmuflCodes.codePoint(unicode, code) != null || NAMES.any { fontName.contains(it, ignoreCase = true) }
}
