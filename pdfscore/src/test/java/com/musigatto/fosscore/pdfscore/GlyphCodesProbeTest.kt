package com.musigatto.fosscore.pdfscore

import org.apache.pdfbox.Loader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Whether the PDFs keep their music glyphs' SMuFL code points (the code itself, or its Unicode
 * value): a SMuFL font's code names the symbol whatever its look. Prints, per PDF and font, how
 * many glyphs carry one, and how often it agrees with the name read from the shape.
 */
class GlyphCodesProbeTest {
    private val corpus = File(System.getProperty("pdfscore.corpus") ?: "")
    private val fonts = File(System.getProperty("pdfscore.fonts") ?: "")

    @Test
    fun probeGlyphCodes() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory && fonts.isDirectory)
        val smufl = SmuflCodes.read(File(fonts, "Bravura.xml").readText())
        val names = GlyphNames(fonts, codes = false)
        val documents = File(corpus, "library/documents")
        documents.listFiles { f -> f.name.endsWith(".pdf") }.orEmpty().sortedBy { it.name }.forEach { file ->
            val pages = Loader.loadPDF(file).use { it.numberOfPages }
            val glyphs = (0 until pages).flatMap { PdfBoxPages.read(file, it).fontGlyphs.values }.distinctBy { it.font to it.code }
            glyphs.groupBy { it.font.substringAfter('+') }.forEach { (font, list) ->
                val byCode = list.count { it.code in SMUFL_RANGE }
                val byUnicode = list.count { g -> g.unicode?.singleOrNull()?.code in SMUFL_RANGE }
                var agree = 0
                var differ = 0
                val examples = mutableListOf<String>()
                for (g in list) {
                    val point = g.unicode?.singleOrNull()?.code?.takeIf { it in SMUFL_RANGE } ?: g.code.takeIf { it in SMUFL_RANGE } ?: continue
                    val byPoint = smufl[point] ?: continue
                    val byShape = names.name(g) ?: continue
                    if (CommonSymbols.canonical(byPoint) == CommonSymbols.canonical(byShape)) agree++ else {
                        differ++
                        if (examples.size < 4) examples += "%04X %s / shape %s".format(point, byPoint, byShape)
                    }
                }
                println("== ${file.name} $font: ${list.size} glyphs, $byCode with a SMuFL code, $byUnicode with a SMuFL Unicode; " +
                    "name by code and by shape: $agree agree, $differ differ ${examples.joinToString("; ")}")
            }
        }
    }

    private companion object {
        val SMUFL_RANGE = 0xE000..0xF8FF
    }
}
