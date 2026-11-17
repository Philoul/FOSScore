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
        // the app names the outlines of a PDF printed without its fonts by their look
        // (-PpdfscoreOnly=Bravura,Leipzig: only the fonts the app ships)
        val only = System.getProperty("pdfscore.only").orEmpty().split(",").filter { it.isNotBlank() }
        val references = TestFonts.all(fonts).filter { only.isEmpty() || it.name in only }
        val outlines = OutlineSymbols(SymbolClassifier(references.flatMap { font -> font.symbols.filter { it.name in CommonSymbols.NAMES } }))
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
                if (svgs.all { it.isFile }) listOf("MuPDF SVG, SMuFL code only (the app)" to { svgs.map { outlines.name(MuPdfSvg.read(it.readBytes(), smufl)) } }) else emptyList()
            for ((mode, readPages) in modes) {
                val pages = readPages()
                val score = ScoreReader.read(pages)
                val written = MusicXmlWriter.write(score)
                val back = MxlNotes(written)
                // the PDF may be one part of the score: its staves are the MusicXML's that match best
                val read = back.allStaves(back.timed)
                val reference = want.allStaves(want.timed)
                val offset = (0..maxOf(0, reference.size - read.size)).maxBy { o -> count(read, reference, o) }
                val unnamed = pages.sumOf { page -> page.glyphs.count { it.name == null } }
                println("  $mode: ${score.measures.size} measures read (MusicXML ${want.allStaves(want.parts).values.firstOrNull()?.size}), " +
                    "$unnamed glyphs unnamed; " +
                    "pitches ${right(back.allStaves(back.parts), want.allStaves(want.parts), offset)}, " +
                    "durations ${right(back.allStaves(back.timed), want.allStaves(want.timed), offset)}, " +
                    "keys and times ${right(back.allStaves(back.signatures), want.allStaves(want.signatures), offset)}, " +
                    "beams ${right(back.allStaves(back.beamed), want.allStaves(want.beamed), offset)} staff-measures right; " +
                    "${Regex("<octave-shift type=\"(up|down)").findAll(written).count()} octave lines; " +
                    "ties ${right(back.allStaves(back.tied), want.allStaves(want.tied), offset)} staff-measures right " +
                    "(${back.allStaves(back.tied).values.sumOf { m -> m.sumOf { it.size } }} read); " +
                    "accidentals ${right(back.allStaves(back.shown), want.allStaves(want.shown), offset)} staff-measures right " +
                    "(${back.allStaves(back.shown).values.sumOf { m -> m.sumOf { it.size } }} read)" +
                    if (offset > 0) " (the PDF's staves are the MusicXML's from staff ${offset + 1})" else "")
                differences(back.allStaves(back.timed), reference, offset).take(5).forEach { println("    $it") }
                differences(back.allStaves(back.beamed), want.allStaves(want.beamed), offset).take(3).forEach { println("    beams: $it") }
                differences(back.allStaves(back.tied), want.allStaves(want.tied), offset).take(5).forEach { println("    ties: $it") }
                val bars = Regex("(?s)<measure number=.([0-9]+)[^>]*>(?:(?!</measure>).)*?<bar-style>([a-z-]+)</bar-style>").findAll(written).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toList()
                println("    bars written: $bars")
                println("    written: " + listOf("arpeggiate", "fermata", "metronome", "words").joinToString { tag -> "$tag ${Regex("<$tag[ />]").findAll(written).count()}" } +
                    "; tempos " + Regex("<sound tempo=.([0-9.]+)").findAll(written).map { it.groupValues[1] }.toList() + "; words " + Regex("<words>([^<]*)").findAll(written).map { it.groupValues[1] }.toList())
                differences(back.allStaves(back.shown), want.allStaves(want.shown), offset).take(5).forEach { println("    accidentals: $it") }
                // lyrics: the measures the MusicXML sings in, whichever part sings
                val sungMeasures = want.sung.indices.filter { want.sung[it].isNotEmpty() }
                val sungRight = sungMeasures.count { back.sung.getOrNull(it) == want.sung[it] }
                println("    lyrics: $sungRight / ${sungMeasures.size} sung measures right, ${back.sung.sumOf { it.size }} syllables read (MusicXML ${want.sung.sumOf { it.size }})")
                sungMeasures.filter { back.sung.getOrNull(it) != want.sung[it] }.take(4)
                    .forEach { println("      measure ${it + 1}: read ${back.sung.getOrNull(it)} expected ${want.sung[it]}") }
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
