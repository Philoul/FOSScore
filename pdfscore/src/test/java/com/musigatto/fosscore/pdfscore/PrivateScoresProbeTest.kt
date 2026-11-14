package com.musigatto.fosscore.pdfscore

import org.apache.pdfbox.Loader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * How well scores engraved in fonts that are none of the references are read: each PDF of a private
 * folder (-PpdfscorePrivate=<folder>, never in the repository: the scores and their fonts are not
 * ours to publish) is read whole, written as MusicXML, read back and compared with the MusicXML of
 * the same name beside it (.mxl or .musicxml, exported from the same file), staff by staff and
 * measure by measure. Each PDF is read twice: its glyphs named by their look only, then by their
 * SMuFL code points when the font has them. Prints its fonts too, and whether they follow SMuFL.
 * Nothing of the fonts is kept: their glyphs are only compared with the free references.
 */
class PrivateScoresProbeTest {
    private val folder = File(System.getProperty("pdfscore.private") ?: "")
    private val fonts = File(System.getProperty("pdfscore.fonts") ?: "")

    @Test
    fun probePrivateScores() {
        assumeTrue(folder.path.isNotEmpty() && folder.isDirectory && fonts.isDirectory)
        val namings = listOf(
            "look only" to GlyphNames(fonts, codes = false),
            "SMuFL code and look" to GlyphNames(fonts),
            "SMuFL code only" to GlyphNames(fonts, codesOnly = true),
        )
        val smufl = SmuflCodes.read(File(fonts, "Bravura.xml").readText())
        val pdfs = folder.listFiles { f -> f.name.endsWith(".pdf", ignoreCase = true) }.orEmpty().sortedBy { it.name }
        for (pdf in pdfs) {
            val reference = listOf(".mxl", ".musicxml", ".xml").map { File(folder, pdf.nameWithoutExtension + it) }.firstOrNull { it.isFile }
            if (reference == null) {
                println("== ${pdf.name}: no MusicXML beside it, skipped")
                continue
            }
            val want = (if (reference.extension == "mxl") MxlNotes(reference) else MxlNotes(reference.readText()))
            val pageCount = Loader.loadPDF(pdf).use { it.numberOfPages }
            val fontGlyphs = (0 until pageCount).flatMap { PdfBoxPages.read(pdf, it).fontGlyphs.values }.distinctBy { it.font to it.code }
            println("== ${pdf.name} ($pageCount pages) vs ${reference.name}")
            fontGlyphs.groupBy { it.font.substringAfter('+') }.forEach { (font, glyphs) ->
                val smufl = glyphs.count { SmuflCodes.codePoint(it.unicode, it.code) != null }
                println("  font $font: ${glyphs.size} glyphs, $smufl with a SMuFL code point")
            }
            // read by PDFBox with each naming; and as the app reads it, from MuPDF's SVG of each page
            // (<name>-p<N>.svg beside the PDF, when there)
            val svgs = (1..pageCount).map { File(folder, "${pdf.nameWithoutExtension}-p$it.svg") }
            val modes = namings.map { (mode, names) -> mode to { (0 until pageCount).map { PdfBoxPages.read(pdf, it, names).drawing } } } +
                if (svgs.all { it.isFile }) listOf("MuPDF SVG, SMuFL code only (the app)" to { svgs.map { MuPdfSvg.read(it.readBytes(), smufl) } }) else emptyList()
            for ((mode, readPages) in modes) {
                val pages = readPages()
                val score = ScoreReader.read(pages)
                val back = MxlNotes(MusicXmlWriter.write(score))
                // the PDF may be one part of the score: its staves are the MusicXML's that match best
                val read = back.allStaves(back.timed)
                val reference = want.allStaves(want.timed)
                val offset = (0..maxOf(0, reference.size - read.size)).maxBy { o -> count(read, reference, o) }
                val unnamed = pages.sumOf { page -> page.glyphs.count { it.name == null } }
                println("  $mode: ${score.measures.size} measures read (MusicXML ${want.allStaves(want.parts).values.firstOrNull()?.size}), " +
                    "$unnamed glyphs unnamed; " +
                    "pitches ${right(back.allStaves(back.parts), want.allStaves(want.parts), offset)}, " +
                    "durations ${right(back.allStaves(back.timed), want.allStaves(want.timed), offset)}, " +
                    "keys and times ${right(back.allStaves(back.signatures), want.allStaves(want.signatures), offset)} staff-measures right" +
                    if (offset > 0) " (the PDF's staves are the MusicXML's from staff ${offset + 1})" else "")
                differences(back.allStaves(back.timed), reference, offset).take(5).forEach { println("    $it") }
            }
        }
    }

    /** "right / all": the staves read against the MusicXML's from staff [offset] + 1, measure by measure. */
    private fun right(read: Map<Int, List<List<String>>>, want: Map<Int, List<List<String>>>, offset: Int): String =
        "${count(read, want, offset)} / ${read.keys.sumOf { want[it + offset]?.size ?: 0 }}"

    /** The staff-measures read otherwise than the MusicXML has them, to look at. */
    private fun differences(read: Map<Int, List<List<String>>>, want: Map<Int, List<List<String>>>, offset: Int): List<String> =
        read.entries.flatMap { (staff, got) ->
            val measures = want[staff + offset].orEmpty()
            measures.indices.filter { it >= got.size || got[it] != measures[it] }
                .map { "staff $staff measure ${it + 1}: read ${got.getOrNull(it)} expected ${measures[it]}" }
        }

    private fun count(read: Map<Int, List<List<String>>>, want: Map<Int, List<List<String>>>, offset: Int): Int =
        read.entries.sumOf { (staff, got) ->
            val measures = want[staff + offset].orEmpty()
            measures.indices.count { it < got.size && got[it] == measures[it] }
        }
}
