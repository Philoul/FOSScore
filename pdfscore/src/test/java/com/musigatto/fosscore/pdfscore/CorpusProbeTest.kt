package com.musigatto.fosscore.pdfscore

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Looks at the scores of a local corpus (-PpdfscoreCorpus=<folder>, never in the repository): what
 * the pages draw and which glyphs get named. Prints; asserts nothing yet. Skipped without a corpus.
 */
class CorpusProbeTest {
    private val corpus = File(System.getProperty("pdfscore.corpus") ?: "")

    @Test
    fun probeMuseScorePdfs() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val names = GlyphNames(File(corpus, "fonts"))
        val documents = File(corpus, "library/documents")
        listOf("Message Personnel.pdf", "Je_Suis_Malade.pdf", "Obispo Pascal - Lucie paroles MU3.pdf").forEach { pdfName ->
            val page = PdfBoxPages.read(File(documents, pdfName), 0, names)
            val drawing = page.drawing
            val unnamed = drawing.glyphs.count { it.name == null }
            println("== $pdfName page 1: ${drawing.glyphs.size} glyphs (${page.fontGlyphs.size} distinct, $unnamed unnamed), " +
                "${drawing.lines.size} lines, ${drawing.shapes.size} shapes, ${drawing.texts.size} texts")
            drawing.glyphs.groupingBy { it.name ?: "?${it.font.substringAfter('+')}#${it.code}" }.eachCount()
                .entries.sortedByDescending { it.value }.forEach { println("  ${it.value}\t${it.key}") }
            page.fontGlyphs.values.forEach { g ->
                val box = GlyphNames.flatten(g.outline, 1.0).takeIf { it.isNotEmpty() }?.let { GlyphNames.bounds(it) }
                val used = drawing.glyphs.count { it.font == g.font && it.code == g.code }
                println("  #${g.code} ${g.font.substringAfter('+')} x$used box=${box?.let { "%.0f,%.0f,%.0f,%.0f".format(it.left, it.top, it.right, it.bottom) }} " +
                    names.ranking(g).take(3).joinToString { "${it.first} %.2f".format(it.second) })
            }
            val staffLines = drawing.lines.filter { it.isHorizontal && it.length > 100 }.map { it.y1 }.distinct().sorted()
            println("  long horizontal lines: ${staffLines.size}, first ys: ${staffLines.take(10).map { "%.1f".format(it) }}")
        }
    }
}
