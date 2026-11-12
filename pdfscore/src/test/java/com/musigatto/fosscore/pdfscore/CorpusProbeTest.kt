package com.musigatto.fosscore.pdfscore

import org.apache.pdfbox.Loader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Looks at the scores of a local corpus (-PpdfscoreCorpus=<folder>, never in the repository): what
 * the pages draw, which glyphs get named, which staves and systems are found. Prints; asserts
 * nothing yet. Skipped without a corpus.
 */
class CorpusProbeTest {
    private val corpus = File(System.getProperty("pdfscore.corpus") ?: "")
    private val documents get() = File(corpus, "library/documents")

    private val museScore = listOf("Message Personnel.pdf", "Je_Suis_Malade.pdf", "Obispo Pascal - Lucie paroles MU3.pdf")
    private val printToPdf = listOf(
        "Savoir Aimer MU3.pdf",
        "Je veux être un homme heureux William Sheller.pdf",
    )

    @Test
    fun probeGlyphs() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val names = GlyphNames(File(corpus, "fonts"))
        museScore.forEach { pdfName ->
            val page = PdfBoxPages.read(File(documents, pdfName), 0, names)
            val drawing = page.drawing
            val unnamed = drawing.glyphs.count { it.name == null }
            println("== $pdfName page 1: ${drawing.glyphs.size} glyphs (${page.fontGlyphs.size} distinct, $unnamed unnamed), " +
                "${drawing.lines.size} lines, ${drawing.shapes.size} shapes, ${drawing.texts.size} texts")
            drawing.glyphs.groupingBy { it.name ?: "?${it.font.substringAfter('+')}#${it.code}" }.eachCount()
                .entries.sortedByDescending { it.value }.forEach { println("  ${it.value}\t${it.key}") }
            drawing.glyphs.filter { it.name == null }.forEach { g ->
                val near = drawing.texts.filter { kotlin.math.abs(it.y - g.y) < 15 && kotlin.math.abs(it.x - g.x) < 120 }
                println("  unnamed ${g.font}#${g.code} at %.1f,%.1f size %.1f box %.1f-%.1f x %.1f-%.1f; texts near: %s".format(
                    g.x, g.y, g.size, g.box.left, g.box.right, g.box.top, g.box.bottom, near.joinToString { "'${it.text}'@%.0f".format(it.x) }))
                page.fontGlyphs[g.font to g.code]?.let { fg ->
                    println("    rank: " + names.ranking(fg).take(3).joinToString { "${it.first} %.2f".format(it.second) })
                }
            }
        }
    }

    /** The tempo's note ("♩ = 64") comes from a text font (Leland Text, Bravura Text) of other metrics. */
    @Test
    fun tempoNotesOfTextFontsAreNamed() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val names = GlyphNames(File(corpus, "fonts"))
        museScore.forEach { pdfName ->
            val glyphs = PdfBoxPages.read(File(documents, pdfName), 0, names).drawing.glyphs
            val tempo = glyphs.filter { it.font.contains("Text") }.map { it.name }
            org.junit.Assert.assertEquals(pdfName, listOf("metNoteQuarterUp"), tempo)
        }
    }

    @Test
    fun probeShapes() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        listOf("Savoir Aimer MU3.pdf", "Obispo Pascal - Lucie paroles MU3.pdf").forEach { pdfName ->
            val drawing = PdfBoxPages.read(File(documents, pdfName), 0).drawing
            println("== shapes of $pdfName page 1")
            drawing.shapes.groupingBy { s ->
                val b = s.box
                "w=%.0f h=%.1f pts=%d curved=%b".format(b.width, b.height, s.points.size, s.curved)
            }.eachCount().entries.sortedByDescending { it.value }.take(25).forEach { println("  ${it.value}\t${it.key}") }
            drawing.shapes.filter { it.box.height > 30 && it.box.width < 15 }.take(5).forEach { s ->
                println("  tall: box=%.1f,%.1f,%.1f,%.1f pts=%d curved=%b".format(s.left, s.top, s.right, s.bottom, s.points.size, s.curved))
            }
        }
    }

    @Test
    fun probeSystems() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val names = GlyphNames(File(corpus, "fonts"))
        (museScore + printToPdf).forEach { pdfName ->
            val file = File(documents, pdfName)
            val pages = Loader.loadPDF(file).use { it.numberOfPages }
            println("== $pdfName ($pages pages)")
            for (index in 0 until pages) {
                val drawing = PdfBoxPages.read(file, index, names).drawing
                val systems = Staves.find(drawing)
                val space = systems.flatMap { it.staves }.map { it.space }.average()
                println("  p${index + 1}: ${systems.size} systems " +
                    systems.joinToString(" ") { s -> "${s.staves.size}" + s.braces.joinToString("") { "{${it.first}-${it.last}}" } } +
                    ", space %.2f, lines %d, shapes %d".format(space, drawing.lines.size, drawing.shapes.size))
            }
        }
    }
}
