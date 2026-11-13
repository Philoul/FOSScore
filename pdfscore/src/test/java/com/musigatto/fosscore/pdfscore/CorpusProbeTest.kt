package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Articulation
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Fraction
import com.musigatto.fosscore.pdfscore.music.Rest
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
    private val fonts = File(System.getProperty("pdfscore.fonts") ?: "")

    private val museScore = listOf("Message Personnel.pdf", "Je_Suis_Malade.pdf", "Obispo Pascal - Lucie paroles MU3.pdf")

    // MuseScore PDFs with no MusicXML to compare with
    private val otherMuseScore = listOf(
        "Aux-Champs-Elysees Joe Dassin.pdf", "Bruel_Patrick_Qui_A_Le_Droit.pdf", "Chanson_sur_ma_drole_de_vie 2.pdf",
        "Mad World.pdf", "Quelques mots d'amour Paroles.pdf",
    )
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
     * Staff-measures (staff and measure, 1 up) where the paired MusicXML is another edition than
     * the PDF, checked by eye: the PDF is read right there. Any other gap is the recognition's.
     */
    private val referenceDiffers = mapOf(
        // the .mxl has one measure less before 84 and one more after it
        "Je_Suis_Malade.pdf" to setOf(2 to 84, 2 to 85),
        // the .mxl has F#4 (its sharp written) where the PDF has F4
        "Obispo Pascal - Lucie paroles MU3.pdf" to setOf(1 to 14, 1 to 22),
    )

    /**
     * The pitches read on each staff, measure by measure, against the paired MusicXML's part that
     * matches best (the PDF can be one part's extract).
     */
    @Test
    fun probePitches() {
        probe("pitches") { _, staff, m -> staff.notes.filter { it.measure == m }.map { it.pitch.toString() }.sorted() }
    }

    /**
     * Each note's pitch, written value and staccato ("E4 quarter. staccato"), grace notes left out, against the paired
     * MusicXML: a measure is right when all its notes are.
     */
    @Test
    fun probeDurations() {
        probe("durations", timed = true, readMeasure = ::durations)
    }

    private fun durations(drawing: PageDrawing, staff: StaffReading, m: Int): List<String> =
        Rhythms.read(drawing, staff).firstOrNull { it.index == m }?.events.orEmpty()
            .mapNotNull { it.event as? Chord }.filter { !it.grace }
            .flatMap { chord ->
                val staccato = if (Articulation.STACCATO in chord.articulations) " staccato" else ""
                chord.pitches.map { "$it ${chord.duration.type.musicXml}${".".repeat(chord.duration.dots)}$staccato" }
            }
            .sorted()

    /**
     * Whether the voices of each measure agree: every voice of every staff of a system should last
     * as long in one measure (a whole-measure rest fills any length). Checks the rests, the voices
     * and the onsets, which the MusicXML comparison does not. All MuseScore PDFs of the corpus.
     */
    @Test
    fun probeMeasureLengths() {
        measureLengths()
    }

    /** [probeMeasureLengths] on pages passed through [shake]: how many system-measures are even, of how many. */
    private fun measureLengths(shake: (PageDrawing) -> PageDrawing = { it }, verbose: Boolean = true): Pair<Int, Int> {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val names = GlyphNames(fonts)
        var allEven = 0
        var all = 0
        (museScore + otherMuseScore).forEach { pdfName ->
            val file = File(documents, pdfName)
            val pages = Loader.loadPDF(file).use { it.numberOfPages }
            var measures = 0
            val uneven = mutableListOf<String>()
            for (index in 0 until pages) {
                val drawing = shake(page(file, index, names))
                StaffNotes.read(drawing, Staves.find(drawing)).forEachIndexed { si, system ->
                    val read = system.map { Rhythms.read(drawing, it) }
                    val count = read.maxOf { staff -> (staff.maxOfOrNull { it.index } ?: -1) + 1 }
                    for (m in 0 until count) {
                        measures++
                        // staff/voice → where it ends, whole-measure rests left out
                        val lengths = read.flatMapIndexed { s, staff ->
                            staff.firstOrNull { it.index == m }?.events.orEmpty()
                                .filter { (it.event as? Rest)?.wholeMeasure != true && (it.event as? Chord)?.grace != true }
                                .groupBy { it.event.voice }
                                .map { (voice, events) -> "s${s + 1}v$voice" to events.maxOf { it.event.onset + it.event.duration.length } }
                        }
                        if (lengths.map { it.second }.distinct().size > 1) {
                            uneven += "p${index + 1}/s${si + 1} m${m + 1}: " + lengths.joinToString { "${it.first}=${it.second}" }
                        }
                    }
                }
            }
            allEven += measures - uneven.size
            all += measures
            if (verbose) {
                println("== measure lengths: $pdfName: ${measures - uneven.size} / $measures system-measures even")
                uneven.forEach { println("  $it") }
            }
        }
        return allEven to all
    }

    /** The pages read so far: one test reads each page once, however often it looks at it. */
    private val pages = mutableMapOf<Pair<String, Int>, PageDrawing>()

    private fun page(file: File, index: Int, names: GlyphNames): PageDrawing =
        pages.getOrPut(file.name to index) { PdfBoxPages.read(file, index, names).drawing }

    /**
     * How much an untidy page costs: the symbols, the strokes, the outlines or the texts (one kind
     * at a time, then all) moved at random, each on its own by about [SHAKES] of a staff space (the
     * staff lines stay), then read again. Which kind breaks first shows the tolerance to widen.
     */
    @Test
    fun probeRobustness() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        for (kind in Shaken.entries) {
            for (fraction in SHAKES) {
                // the corpus is engraved with a staff space of about 5 points
                val shake = { page: PageDrawing -> shaken(page, kind, fraction * 5.0, seed = 1) }
                val (wrong, total) = probe("durations", timed = true, shake = shake, verbose = false, readMeasure = ::durations)
                val (even, measures) = measureLengths(shake, verbose = false)
                println("== %s shaken by %.2f space: %d / %d staff-measures wrong against the MusicXML, %d / %d system-measures even"
                    .format(kind, fraction, wrong, total, even, measures))
            }
        }
    }

    private val SHAKES = listOf(0.05, 0.1, 0.2)

    private enum class Shaken { SYMBOLS, STROKES, OUTLINES, TEXTS, ALL }

    private fun shaken(page: PageDrawing, kind: Shaken, sigma: Double, seed: Long): PageDrawing {
        val random = java.util.Random(seed)
        fun d() = random.nextGaussian() * sigma
        fun Box.moved(dx: Double, dy: Double) = Box(left + dx, top + dy, right + dx, bottom + dy)
        fun on(what: Shaken) = kind == what || kind == Shaken.ALL
        // staff lines, drawn whole or a measure at a time
        val staffLine = { l: DrawnLine -> l.isHorizontal && l.length > 20 && l.width < 1.5 }
        return PageDrawing(
            page.width, page.height,
            if (!on(Shaken.SYMBOLS)) page.glyphs
            else page.glyphs.map { g -> val dx = d(); val dy = d(); g.copy(box = g.box.moved(dx, dy), x = g.x + dx, y = g.y + dy) },
            if (!on(Shaken.STROKES)) page.lines
            else page.lines.map { l -> if (staffLine(l)) l else { val dx = d(); val dy = d(); l.copy(x1 = l.x1 + dx, y1 = l.y1 + dy, x2 = l.x2 + dx, y2 = l.y2 + dy) } },
            if (!on(Shaken.OUTLINES)) page.shapes
            else page.shapes.map { sh -> val dx = d(); val dy = d(); sh.copy(points = sh.points.map { Point(it.x + dx, it.y + dy) }) },
            if (!on(Shaken.TEXTS)) page.texts else page.texts.map { t -> t.copy(x = t.x + d(), y = t.y + d()) },
        )
    }

    /**
     * What [readMeasure] gives for each staff and measure of the paired PDFs, against the
     * MusicXML's part that matches best (the PDF can be one part's extract).
     */
    private fun probe(
        what: String,
        timed: Boolean = false,
        shake: (PageDrawing) -> PageDrawing = { it },
        verbose: Boolean = true,
        readMeasure: (PageDrawing, StaffReading, Int) -> List<String>,
    ): Pair<Int, Int> {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val names = GlyphNames(fonts)
        var allWrong = 0
        var all = 0
        pairs.forEach { (pdfName, mxlName) ->
            val file = File(documents, pdfName)
            val pages = Loader.loadPDF(file).use { it.numberOfPages }
            // staff index → measures, each what was read in it
            val read = mutableMapOf<Int, MutableList<List<String>>>()
            val where = mutableListOf<String>() // measure → "page/system"
            for (index in 0 until pages) {
                val drawing = shake(page(file, index, names))
                StaffNotes.read(drawing, Staves.find(drawing)).forEachIndexed { si, system ->
                    val measures = system.maxOf { staff -> maxOf(staff.barlines.size, (staff.notes.maxOfOrNull { it.measure } ?: -1) + 1) }
                    repeat(measures) { where += "p${index + 1}/s${si + 1}" }
                    system.forEachIndexed { s, staff ->
                        val list = read.getOrPut(s) { mutableListOf() }
                        (0 until measures).forEach { m -> list += readMeasure(drawing, staff, m) }
                    }
                }
            }
            val mxl = MxlNotes(File(documents, mxlName))
            val expected = if (timed) mxl.timed else mxl.parts
            val (partId, score) = expected.map { (id, staves) ->
                id to read.entries.sumOf { (s, measures) ->
                    val want = staves[s + 1].orEmpty()
                    measures.indices.count { it < want.size && want[it] == measures[it] }
                }
            }.maxBy { it.second }
            val total = read.values.sumOf { it.size }
            val known = referenceDiffers[pdfName].orEmpty()
            val staves = expected.getValue(partId)
            val gaps = read.flatMap { (s, measures) ->
                val want = staves[s + 1].orEmpty()
                measures.indices.filter { it >= want.size || want[it] != measures[it] }.map { m -> Triple(s + 1, m + 1, want.getOrNull(m)) to measures[m] }
            }
            val unexplained = gaps.count { (at, _) -> (at.first to at.second) !in known }
            allWrong += unexplained
            all += total
            if (!verbose) return@forEach
            println("== $what: $pdfName vs $mxlName part $partId: $score / $total staff-measures right, " +
                "${gaps.size - unexplained} where the MusicXML is another edition, $unexplained wrong " +
                "(read ${read[0]?.size} measures, MusicXML ${expected[partId]?.get(1)?.size})")
            gaps.forEach { (at, readThere) ->
                val note = if ((at.first to at.second) in known) " [the MusicXML differs]" else ""
                println("  staff ${at.first} measure ${at.second} (${where.getOrNull(at.second - 1)}): read $readThere expected ${at.third}$note")
            }
        }
        return allWrong to all
    }

    /** One system in detail: -PpdfscoreProbe="<pdf>|<page>|<system>" (1 up). */
    @Test
    fun probeSystem() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val target = System.getProperty("pdfscore.probe").orEmpty().split("|")
        assumeTrue(target.size == 3)
        val names = GlyphNames(fonts)
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
            staff.symbols.forEach { println("  symbol ${it.name} x %.1f-%.1f y %.1f-%.1f".format(it.box.left, it.box.right, it.box.top, it.box.bottom)) }
            staff.notes.forEach { n ->
                println("  x %.1f y %.1f h %.1f step %d m %d %s %s".format(
                    n.head.box.left, n.head.box.centerY, n.head.box.height, n.step, n.measure, n.pitch, n.head.name))
            }
            Rhythms.read(drawing, staff).forEach { measure ->
                measure.events.forEach { placed ->
                    val event = placed.event
                    val what = (event as? Chord)?.let { "${it.pitches} ${it.stem}${if (it.grace) " grace" else ""}" } ?: "rest"
                    println("  rhythm m %d x %.1f voice %d onset %s %s%s %s".format(
                        measure.index, placed.box.left, event.voice, event.onset, event.duration.type.musicXml, ".".repeat(event.duration.dots), what))
                }
            }
        }
        val glyphs = drawing.glyphs.filter { it.box.centerY in system.top - 60..system.bottom + 60 && it.name?.startsWith("notehead") != true }
        glyphs.sortedBy { it.box.left }.forEach { println("  other ${it.name} ${it.font.substringAfter('+')}#${it.code} x %.1f-%.1f y %.1f-%.1f".format(it.box.left, it.box.right, it.box.top, it.box.bottom)) }
        drawing.lines.filter { it.isVertical && it.length > 15 && minOf(it.y1, it.y2) in system.top - 5..system.bottom }
            .sortedBy { it.x1 }.forEach { println("  vertical x %.1f y %.1f-%.1f w %.2f".format(it.x1, minOf(it.y1, it.y2), maxOf(it.y1, it.y2), it.width)) }
        val inSystem = { y: Double -> y in system.top - 40..system.bottom + 40 }
        drawing.lines.filter { it.isVertical && it.length <= 15 && inSystem(it.y1) }
            .sortedBy { it.x1 }.forEach { println("  short vertical x %.1f y %.1f-%.1f w %.2f".format(it.x1, minOf(it.y1, it.y2), maxOf(it.y1, it.y2), it.width)) }
        drawing.lines.filter { !it.isVertical && !it.isHorizontal && inSystem(it.y1) }
            .sortedBy { minOf(it.x1, it.x2) }.forEach { println("  slanted %.1f,%.1f-%.1f,%.1f w %.2f".format(it.x1, it.y1, it.x2, it.y2, it.width)) }
        drawing.lines.filter { it.isHorizontal && it.width > 1.0 && inSystem(it.y1) }
            .sortedBy { minOf(it.x1, it.x2) }.forEach { println("  thick horizontal x %.1f-%.1f y %.1f w %.2f".format(minOf(it.x1, it.x2), maxOf(it.x1, it.x2), it.y1, it.width)) }
        drawing.shapes.filter { inSystem(it.box.centerY) }.sortedBy { it.left }.forEach { shape ->
            println("  shape ${if (shape.curved) "curved" else "straight"} ${shape.points.size} pts x %.1f-%.1f y %.1f-%.1f".format(shape.left, shape.right, shape.top, shape.bottom) +
                if (!shape.curved && shape.points.size <= 6) " " + shape.points.joinToString(" ") { "%.1f,%.1f".format(it.x, it.y) } else "")
        }
    }

    @Test
    fun probeGlyphs() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val names = GlyphNames(fonts)
        (museScore + otherMuseScore).forEach { pdfName ->
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
                    println("    rank: " + names.ranking(fg).take(3).joinToString { "${it.name} %.2f".format(it.score) })
                }
            }
        }
    }

    /** The tempo's note ("♩ = 64") comes from a text font (Leland Text, Bravura Text) of other metrics. */
    @Test
    fun tempoNotesOfTextFontsAreNamed() {
        assumeTrue(corpus.path.isNotEmpty() && corpus.isDirectory)
        val names = GlyphNames(fonts)
        (museScore + otherMuseScore).forEach { pdfName ->
            val glyphs = PdfBoxPages.read(File(documents, pdfName), 0, names).drawing.glyphs
            val tempo = glyphs.filter { it.font.contains("Text") }.map { it.name }
            // none, one, or more (a change further down the page): each named
            org.junit.Assert.assertTrue(pdfName, tempo.all { it == "metNoteQuarterUp" })
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
        val names = GlyphNames(fonts)
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
