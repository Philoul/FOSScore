package com.musigatto.fosscore.pdfscore.bitmap

import com.musigatto.fosscore.pdfscore.MusicXmlWriter
import com.musigatto.fosscore.pdfscore.MxlNotes
import com.musigatto.fosscore.pdfscore.ScoreReader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * How well scanned pages are read: for each score of the private folder (-PpdfscorePrivate=<folder>,
 * never in the repository) with its MusicXML (`<name>.mxl` or `.musicxml`), the 300 dpi page images
 * `<name>-p<N>.png` (or `-p<N>-<variant>`, a variant being e.g. "clean", "scan", "rough"; PNG or
 * JPEG), rendered beforehand in -PpdfscoreScans=<folder> (else beside the MusicXML), are read by
 * [BitmapPage] and compared with the MusicXML, staff by staff and measure by measure. Renders
 * nothing itself: a score without images is skipped.
 */
class PrivateScansProbeTest {
    private val folder = File(System.getProperty("pdfscore.private") ?: "")
    private val scans = File(System.getProperty("pdfscore.scans") ?: "").takeIf { it.path.isNotEmpty() && it.isDirectory } ?: folder

    @Test
    fun probePrivateScans() {
        assumeTrue(folder.path.isNotEmpty() && folder.isDirectory)
        val model = Golden.model ?: return
        val pattern = Regex("""(.+)-p(\d+)(?:-([a-z]+))?\.(?:png|jpg)""")
        val images = scans.listFiles { f -> pattern.matches(f.name) }.orEmpty()
            .mapNotNull { f -> pattern.matchEntire(f.name)?.let { Triple(it.groupValues[1], it.groupValues[3], it.groupValues[2].toInt() to f) } }
            .groupBy({ it.first to it.second }, { it.third })
        for ((key, pages) in images.entries.sortedBy { "${it.key.first} ${it.key.second}" }) {
            val (name, variant) = key
            val reference = listOf(".mxl", ".musicxml", ".xml").map { File(folder, name + it) }.firstOrNull { it.isFile } ?: continue
            val want = if (reference.extension == "mxl") MxlNotes(reference) else MxlNotes(reference.readText())
            val start = System.nanoTime()
            val drawings = pages.sortedBy { it.first }.mapNotNull { (_, file) -> Golden.gray(file)?.let { BitmapPage.read(it, 300.0, model) } }
            val ms = (System.nanoTime() - start) / 1e6 / maxOf(1, drawings.size)
            val score = ScoreReader.read(drawings)
            val back = MxlNotes(MusicXmlWriter.write(score))
            val read = back.allStaves(back.timed)
            val wanted = want.allStaves(want.timed)
            val offset = (0..maxOf(0, wanted.size - read.size)).maxByOrNull { count(read, wanted, it) } ?: 0
            println(
                "== $name ${variant.ifEmpty { "-" }}: ${drawings.size} pages, ${"%.0f".format(Locale.ROOT, ms)} ms a page; " +
                    "${score.measures.size} measures; pitches ${right(back.allStaves(back.parts), want.allStaves(want.parts), offset)}, " +
                    "durations ${right(read, wanted, offset)} staff-measures right",
            )
            val heads = drawings.sumOf { d -> d.glyphs.count { it.name?.startsWith("notehead") == true } }
            println("   ${drawings.sumOf { it.glyphs.size }} symbols, $heads heads; " + drawings.flatMap { it.glyphs }.groupingBy { it.name }.eachCount().entries.sortedByDescending { it.value }.take(12))
            drawings.forEachIndexed { p, drawing ->
                val systems = com.musigatto.fosscore.pdfscore.Staves.find(drawing)
                val bars = com.musigatto.fosscore.pdfscore.StaffNotes.read(drawing, systems).map { system -> system.map { it.barlines.size } }
                println("   page ${p + 1}: staves per system ${systems.map { it.staves.size }}, barlines per staff $bars")
            }
            val pitches = back.allStaves(back.parts)
            val wantPitches = want.allStaves(want.parts)
            pitches.entries.flatMap { (staff, got) ->
                val measures = wantPitches[staff + offset].orEmpty()
                measures.indices.filter { it >= got.size || got[it] != measures[it] }.map { "staff $staff measure ${it + 1}: read ${got.getOrNull(it)} expected ${measures[it]}" }
            }.take(4).forEach { println("   $it") }
        }
    }

    private fun right(read: Map<Int, List<List<String>>>, want: Map<Int, List<List<String>>>, offset: Int): String =
        "${count(read, want, offset)} / ${read.keys.sumOf { want[it + offset]?.size ?: 0 }}"

    private fun count(read: Map<Int, List<List<String>>>, want: Map<Int, List<List<String>>>, offset: Int): Int =
        read.entries.sumOf { (staff, got) ->
            val measures = want[staff + offset].orEmpty()
            measures.indices.count { it < got.size && got[it] == measures[it] }
        }
}
