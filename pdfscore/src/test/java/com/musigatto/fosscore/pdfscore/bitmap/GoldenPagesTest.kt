package com.musigatto.fosscore.pdfscore.bitmap

import com.musigatto.fosscore.pdfscore.ScoreReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

/**
 * The golden pages (SPEC 14, free synthetic data): a clean staff and the same staff from a rough
 * scan (rotated, JPEG-degraded). Every value the reference lists is checked: the thresholds, T, D0,
 * the slope, D, the ink counts, the staves, the systems, every segment and every blob with its
 * features and class.
 */
class GoldenPagesTest {
    @Test
    fun cleanPage() = check("page")

    @Test
    fun roughPage() = check("page_rough")

    /** The clean page read whole: the drawing goes through the PDF reader and gives notes. */
    @Test
    fun cleanPageReadsAsMusic() {
        val gray = Golden.gray("page.png")
        val model = Golden.model
        assertNotNull(gray)
        assertNotNull(model)
        if (gray == null || model == null) return
        val start = System.nanoTime()
        val drawing = BitmapPage.read(gray, 300.0, model)
        val ms = (System.nanoTime() - start) / 1e6
        val score = ScoreReader.read(listOf(drawing))
        val notes = score.measures.flatMap { m -> m.staves.flatMap { it.events } }
        println("golden page: ${drawing.glyphs.size} glyphs, ${drawing.lines.size} lines, ${drawing.shapes.size} beams, " +
            "${score.measures.size} measures, ${notes.size} events; read in ${"%.0f".format(Locale.ROOT, ms)} ms (first run)")
        println("  glyphs: " + drawing.glyphs.groupingBy { it.name }.eachCount())
        assertTrue("staff found", drawing.lines.count { it.isHorizontal } >= 5)
        assertTrue("measures read", score.measures.isNotEmpty())
        assertTrue("notes read", notes.isNotEmpty())
    }

    /** The time [BitmapPage.read] takes on the golden page, warmed up. */
    @Test
    fun timing() {
        val gray = Golden.gray("page.png") ?: return
        val model = Golden.model ?: return
        repeat(3) { BitmapPage.read(gray, 300.0, model) }
        val runs = 10
        val start = System.nanoTime()
        repeat(runs) { BitmapPage.read(gray, 300.0, model) }
        println("BitmapPage.read on page.png (${gray.width} x ${gray.height}): ${"%.1f".format(Locale.ROOT, (System.nanoTime() - start) / 1e6 / runs)} ms")
    }

    private fun check(name: String) {
        val gray = Golden.gray("$name.png")
        val model = Golden.model
        assertNotNull(gray)
        assertNotNull(model)
        if (gray == null || model == null) return
        val want = Golden.lines("$name.txt").filter { !it.startsWith("#") }.map { it.substringBefore("  #") }
        val got = describe(gray, model)
        for ((i, line) in want.withIndex()) {
            val mine = got.getOrNull(i) ?: fail("$name: line ${i + 1} missing, want: $line")
            if (!same(line, mine.toString())) fail("$name: first difference at line ${i + 1}\nwant: $line\n got: $mine")
        }
        assertEquals("$name: line count", want.size, got.size)
    }

    /** The page described in the golden files' format (one line per value or item). */
    private fun describe(gray: GrayImage, model: OmrModel): List<String> {
        val page = BitmapPage.analyse(gray)
        val out = mutableListOf(
            "size ${gray.width} ${gray.height}",
            "otsu ${page.otsu}",
            "threshold ${page.threshold}",
            "T ${page.metrics.thickness}",
            "D0 ${page.metrics.space}",
            "slope ${page.deskew.slope}",
            "D ${page.space}",
            "ink_pixels ${page.ink.count()}",
            "deskewed_ink ${page.deskewed.count()}",
            "line_free_ink ${page.lineFree.count()}",
            "restored_ink ${page.lineFree.count()}",
            "staves ${page.staves.size}",
        )
        for (s in page.staves) {
            out += "staff ys ${s.ys.joinToString(" ")} Y ${s.rows.joinToString(" ")} left ${s.left} right ${s.right} strip ${s.strip} offsets ${s.stripOffsets.joinToString(" ")}"
        }
        out += "systems ${page.systems.joinToString(" ") { it.size.toString() }}"
        out += "segments ${page.segments.size}"
        for (s in page.segments) out += "seg ${s.x0} ${s.x1} ${s.y0} ${s.y1} ${if (s.thin) 1 else 0}"
        out += "blobs ${page.blobs.size}"
        page.blobs.forEachIndexed { i, b ->
            val q = Features.of(b.mask, page.space, model.thresholds)
            val c = model.symbols.classify(q)
            val parent = when (b.pass) {
                2 -> b.parent.toString()
                3 -> b.parts?.let { "${it.first},${it.second}" } ?: "?"
                else -> "-1"
            }
            out += "blob $i ${b.pass} $parent ${b.x0} ${b.y0} ${b.x1} ${b.y1} ${b.ink} | ${q.joinToString(" ")} | ${c.name} ${String.format(Locale.ROOT, "%.4f", c.score)}"
        }
        return out
    }

    /** Two lines alike: the same words, numbers with a decimal point equal as doubles. */
    private fun same(want: String, got: String): Boolean {
        val a = want.trim().split(Regex("\\s+"))
        val b = got.trim().split(Regex("\\s+"))
        return a.size == b.size && a.indices.all { i ->
            a[i] == b[i] || (a[i].contains('.') && a[i].toDoubleOrNull()?.let { it == b[i].toDoubleOrNull() } == true)
        }
    }
}
