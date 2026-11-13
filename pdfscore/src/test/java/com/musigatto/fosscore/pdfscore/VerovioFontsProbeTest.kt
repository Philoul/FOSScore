package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Articulation
import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Rest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reads scores engraved by Verovio in each free music font against the MusicXML they were engraved
 * from, an exact reference: what another font and another engraver's layout cost. Each font is read
 * twice: with every font as a reference (a known font), then with that font left out of the
 * references (a font never seen). Prints; asserts nothing.
 *
 * -PpdfscoreEngraved=<folder> (outside the repository): one folder per part, holding `part.mxl`
 * and one folder per font of `page-N.svg`. -PpdfscoreOnly=<names> limits it to these parts and
 * fonts and prints every measure read wrong.
 */
class VerovioFontsProbeTest {
    private val engraved = File(System.getProperty("pdfscore.engraved") ?: "")
    private val fonts = File(System.getProperty("pdfscore.fonts") ?: "")
    private val only = (System.getProperty("pdfscore.only") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** What one part in one font gave: staff-measures wrong in pitch, in duration, of how many; system-measures even, of how many. */
    private data class Tally(
        val pitches: Int = 0,
        val durations: Int = 0,
        val signatures: Int = 0,
        val written: Int = 0,
        val total: Int = 0,
        val even: Int = 0,
        val measures: Int = 0,
    ) {
        operator fun plus(o: Tally) =
            Tally(pitches + o.pitches, durations + o.durations, signatures + o.signatures, written + o.written, total + o.total, even + o.even, measures + o.measures)
        override fun toString() = "pitches %d wrong, durations %d wrong, keys or times %d wrong, written MusicXML %d wrong / %d staff-measures; %d / %d system-measures even"
            .format(pitches, durations, signatures, written, total, even, measures)
    }

    @Test
    fun probeEngravedFonts() {
        assumeTrue(engraved.path.isNotEmpty() && engraved.isDirectory)
        val parts = engraved.listFiles { f -> File(f, "part.mxl").isFile }.orEmpty().sortedBy { it.name }
            .filter { only.isEmpty() || it.name in only || only.none { o -> parts(o) } }
        val fontNames = TestFonts.all(fonts).map { it.name }.filter { only.isEmpty() || it in only || only.none { o -> o in FONTS } }
        val verbose = only.isNotEmpty()
        val known = GlyphNames(fonts)
        for ((mode, namesFor) in listOf<Pair<String, (String) -> GlyphNames>>(
            "known font" to { _ -> known },
            "font left out of the references" to { font -> GlyphNames(fonts, setOf(font)) },
        )) {
            println("==== $mode")
            for (font in fontNames) {
                val names = namesFor(font)
                var sum = Tally()
                for (part in parts) {
                    val tally = probe(part, font, names, verbose)
                    if (verbose || tally.pitches + tally.durations + tally.signatures + tally.written > 0 || tally.even < tally.measures) println("  ${part.name} $font: $tally")
                    sum += tally
                }
                println("== $mode, $font: $sum")
            }
        }
    }

    private fun parts(name: String) = name.contains('-')

    private fun probe(part: File, font: String, names: GlyphNames, verbose: Boolean): Tally {
        val pages = File(part, font).listFiles { f -> f.name.matches(Regex("""page-\d+\.svg""")) }.orEmpty()
            .sortedBy { it.name.removePrefix("page-").removeSuffix(".svg").toInt() }
            .map { VerovioSvgPages.read(it, font, fonts, names) }
        val mxl = MxlNotes(File(part, "part.mxl"))
        val pitchesWant = mxl.parts.values.first()
        val durationsWant = mxl.timed.values.first()
        val signaturesWant = mxl.signatures.values.first()
        // staff (0 up) → measures, each what was read in it
        val pitches = mutableMapOf<Int, MutableList<List<String>>>()
        val durations = mutableMapOf<Int, MutableList<List<String>>>()
        val signatures = mutableMapOf<Int, MutableList<List<String>>>()
        val where = mutableListOf<String>()
        var even = 0
        var measures = 0
        val uneven = mutableListOf<String>()
        pages.forEachIndexed { index, drawing ->
            StaffNotes.read(drawing, Staves.find(drawing)).forEachIndexed { si, system ->
                val rhythms = system.map { Rhythms.read(drawing, it) }
                // a system may end without a barline: its last measure counts by what is in it, rests too
                val count = system.indices.maxOf { s ->
                    maxOf(system[s].barlines.size, (system[s].notes.maxOfOrNull { it.measure } ?: -1) + 1, (rhythms[s].maxOfOrNull { it.index } ?: -1) + 1)
                }
                repeat(count) { where += "p${index + 1}/s${si + 1}" }
                system.forEachIndexed { s, staff ->
                    val p = pitches.getOrPut(s) { mutableListOf() }
                    val d = durations.getOrPut(s) { mutableListOf() }
                    val k = signatures.getOrPut(s) { mutableListOf() }
                    for (m in 0 until count) {
                        k += Signatures.read(staff, m)
                        p += staff.notes.filter { it.measure == m }.map { it.pitch.toString() }.sorted()
                        d += rhythms[s].firstOrNull { it.index == m }?.events.orEmpty().mapNotNull { it.event as? Chord }.filter { !it.grace }
                            .flatMap { chord ->
                                val staccato = if (Articulation.STACCATO in chord.articulations) " staccato" else ""
                                chord.pitches.map { "$it ${chord.duration.type.musicXml}${".".repeat(chord.duration.dots)}$staccato" }
                            }.sorted()
                    }
                }
                for (m in 0 until count) {
                    measures++
                    val lengths = rhythms.flatMapIndexed { s, staff ->
                        staff.firstOrNull { it.index == m }?.events.orEmpty()
                            .filter { (it.event as? Rest)?.wholeMeasure != true && (it.event as? Chord)?.grace != true }
                            .groupBy { it.event.voice }
                            .map { (voice, events) -> "s${s + 1}v$voice" to events.maxOf { it.event.onset + it.event.duration.length } }
                    }
                    if (lengths.map { it.second }.distinct().size > 1) uneven += "p${index + 1}/s${si + 1} m${m + 1}: " + lengths.joinToString { "${it.first}=${it.second}" }
                    else even++
                }
            }
        }
        // the whole part read, written as MusicXML and read back: pitches and values together
        val back = MxlNotes(MusicXmlWriter.write(ScoreReader.read(pages)))
        val writtenBack = back.allStaves(back.timed).mapKeys { it.key - 1 }
        fun wrong(read: Map<Int, List<List<String>>>, want: Map<Int, List<List<String>>>, what: String): Int {
            var count = 0
            read.forEach { (s, list) ->
                val expected = want[s + 1].orEmpty()
                list.forEachIndexed { m, got ->
                    val exp = expected.getOrNull(m)
                    if (exp != got) {
                        count++
                        if (verbose) println("    $what staff ${s + 1} measure ${m + 1} (${where.getOrNull(m)}): read $got expected $exp")
                    }
                }
            }
            return count
        }
        if (verbose) {
            println("  ${part.name} $font: read ${pitches[0]?.size} measures, MusicXML ${pitchesWant[1]?.size}")
            uneven.forEach { println("    uneven $it") }
        }
        return Tally(
            pitches = wrong(pitches, pitchesWant, "pitches"),
            durations = wrong(durations, durationsWant, "durations"),
            signatures = wrong(signatures, signaturesWant, "signatures"),
            written = wrong(writtenBack, durationsWant, "written MusicXML"),
            total = pitches.values.sumOf { it.size },
            even = even,
            measures = measures,
        )
    }

    private companion object {
        val FONTS = setOf("Bravura", "Gootville", "Leipzig", "Leland", "Petaluma")
    }
}
