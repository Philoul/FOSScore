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

    private val pairs = mapOf(
        // Message Personnel.mxl is another arrangement than the PDF (three times the notes): no reference.
        "Je_Suis_Malade.pdf" to "Je suis malade - Serge Lama.mxl",
        "Obispo Pascal - Lucie paroles MU3.pdf" to "Lucie - Obispo Pascal.mxl",
    )

    /**
     * The pitches read on each staff, measure by measure, against the paired MusicXML's part that
     * matches best (the PDF can be one part's extract).
     */
    @Test
    fun probePitches() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val names = GlyphNames(File(corpus, "fonts"))
        pairs.forEach { (pdfName, mxlName) ->
            val file = File(documents, pdfName)
            val pages = Loader.loadPDF(file).use { it.numberOfPages }
            // staff index → measures, each the pitches read in it, sorted
            val read = mutableMapOf<Int, MutableList<List<String>>>()
            val where = mutableListOf<String>() // measure → "page/system"
            for (index in 0 until pages) {
                val drawing = PdfBoxPages.read(file, index, names).drawing
                StaffNotes.read(drawing, Staves.find(drawing)).forEachIndexed { si, system ->
                    val measures = system.maxOf { staff -> maxOf(staff.barlines.size, (staff.notes.maxOfOrNull { it.measure } ?: -1) + 1) }
                    repeat(measures) { where += "p${index + 1}/s${si + 1}" }
                    system.forEachIndexed { s, staff ->
                        val list = read.getOrPut(s) { mutableListOf() }
                        (0 until measures).forEach { m ->
                            list += staff.notes.filter { it.measure == m }.map { it.pitch.toString() }.sorted()
                        }
                    }
                }
            }
            val expected = MxlNotes(File(documents, mxlName)).parts
            val (partId, score) = expected.map { (id, staves) ->
                id to read.entries.sumOf { (s, measures) ->
                    val want = staves[s + 1].orEmpty()
                    measures.indices.count { it < want.size && want[it] == measures[it] }
                }
            }.maxBy { it.second }
            val total = read.values.sumOf { it.size }
            println("== $pdfName vs $mxlName part $partId: $score / $total staff-measures right " +
                "(read ${read[0]?.size} measures, MusicXML ${expected[partId]?.get(1)?.size})")
            val staves = expected.getValue(partId)
            read.forEach { (s, measures) ->
                val want = staves[s + 1].orEmpty()
                measures.indices.filter { it >= want.size || want[it] != measures[it] }.take(6).forEach { m ->
                    println("  staff ${s + 1} measure ${m + 1} (${where.getOrNull(m)}): read ${measures[m]} expected ${want.getOrNull(m)}")
                }
            }
        }
    }

    /** One system in detail: -PpdfscoreProbe="<pdf>|<page>|<system>" (1 up). */
    @Test
    fun probeSystem() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val target = System.getProperty("pdfscore.probe").orEmpty().split("|")
        assumeTrue(target.size == 3)
        val names = GlyphNames(File(corpus, "fonts"))
        val drawing = PdfBoxPages.read(File(documents, target[0]), target[1].toInt() - 1, names).drawing
        val systems = Staves.find(drawing)
        val system = systems[target[2].toInt() - 1]
        // The system as an image, to look at (in the build folder, never committed).
        val scale = 3f
        val image = Loader.loadPDF(File(documents, target[0])).use {
            org.apache.pdfbox.rendering.PDFRenderer(it).renderImage(target[1].toInt() - 1, scale)
        }
        val top = ((system.top - 40) * scale).toInt().coerceAtLeast(0)
        val bottom = ((system.bottom + 40) * scale).toInt().coerceAtMost(image.height)
        javax.imageio.ImageIO.write(image.getSubimage(0, top, image.width, bottom - top), "png", File("build/probe-system.png"))
        StaffNotes.read(drawing, systems)[target[2].toInt() - 1].forEachIndexed { s, staff ->
            println("staff ${s + 1}: top %.1f space %.2f x %.1f-%.1f clef ${staff.clef} key ${staff.key}".format(
                staff.staff.top, staff.staff.space, staff.staff.left, staff.staff.right))
            println("  barlines " + staff.barlines.joinToString { "%.1f".format(it) })
            staff.notes.forEach { n ->
                println("  x %.1f y %.1f h %.1f step %d m %d %s %s".format(
                    n.head.box.left, n.head.box.centerY, n.head.box.height, n.step, n.measure, n.pitch, n.head.name))
            }
        }
        val glyphs = drawing.glyphs.filter { it.box.centerY in system.top - 60..system.bottom + 60 && it.name?.startsWith("notehead") != true }
        glyphs.sortedBy { it.box.left }.forEach { println("  other ${it.name} x %.1f-%.1f y %.1f-%.1f".format(it.box.left, it.box.right, it.box.top, it.box.bottom)) }
        drawing.lines.filter { it.isVertical && it.length > 15 && minOf(it.y1, it.y2) in system.top - 5..system.bottom }
            .sortedBy { it.x1 }.forEach { println("  vertical x %.1f y %.1f-%.1f w %.2f".format(it.x1, minOf(it.y1, it.y2), maxOf(it.y1, it.y2), it.width)) }
    }

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
