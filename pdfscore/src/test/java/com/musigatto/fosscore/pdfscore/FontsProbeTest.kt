package com.musigatto.fosscore.pdfscore

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * How well a font none of the references is read: each free font's everyday symbols
 * ([CommonSymbols]) are named with the other fonts only. Prints, per font, how many come first in
 * the ranking, are named right, wrong, or left unnamed, and how many of its other symbols (not
 * everyday ones) get an everyday name by mistake. Skipped without Verovio's font data (downloaded
 * by the :musicxml build).
 */
class FontsProbeTest {
    private val dir = File(System.getProperty("pdfscore.fonts") ?: "")

    @Test
    fun probeLeaveOneFontOut() {
        assumeTrue(dir.path.isNotEmpty() && dir.isDirectory)
        val fonts = TestFonts.all(dir)
        println("== fonts: " + fonts.joinToString { "${it.name} ${it.symbols.size}" })
        val common = CommonSymbols.NAMES.toSet()
        for (font in fonts) {
            val references = fonts.filter { it !== font }.flatMap { f -> f.symbols.filter { it.name in common } }
            val known = references.map { CommonSymbols.canonical(it.name) }.toSet()
            val classifier = SymbolClassifier(references)
            val tested = font.symbols.filter { CommonSymbols.canonical(it.name) in known }
            var first = 0
            var right = 0
            var unnamed = 0
            val wrong = mutableListOf<String>()
            for (symbol in tested) {
                val expected = CommonSymbols.canonical(symbol.name)
                val ranking = classifier.rank(symbol.polygons)
                if (ranking.firstOrNull()?.name == expected) first++
                val got = classifier.name(symbol.polygons)
                when (got?.name) {
                    expected -> right++
                    null -> unnamed++
                    else -> wrong += "${symbol.name} → ${got.name}; " + ranking.take(3).joinToString { "${it.name} %.2f".format(it.score) }
                }
            }
            val others = font.symbols.filter { it.name !in common }
            val falseNames = others.mapNotNull { symbol -> classifier.name(symbol.polygons)?.let { "${symbol.name} → ${it.name} %.2f".format(it.score) } }
            println("  ${font.name}: ${tested.size} everyday symbols: $first first, $right named right, ${wrong.size} wrong, $unnamed unnamed; " +
                "${falseNames.size} / ${others.size} other symbols named")
            wrong.forEach { println("    wrong: $it") }
            if (System.getProperty("pdfscore.verbose") != null) {
                tested.filter { classifier.name(it.polygons) == null }.forEach { symbol ->
                    println("    unnamed: ${symbol.name}; " + classifier.rank(symbol.polygons).take(3).joinToString { "${it.name} %.2f".format(it.score) })
                }
                falseNames.take(15).forEach { println("    other: $it") }
            }
        }
    }
}

/**
 * The leave-one-font-out test for several tunings of the classifier, on the symbols every score
 * has ([CORE]): which compares shapes best across fonts. -PpdfscoreTunings=<anything> to run it.
 */
class FontTuningsProbeTest {
    private val dir = File(System.getProperty("pdfscore.fonts") ?: "")

    @Test
    fun probeTunings() {
        assumeTrue(dir.path.isNotEmpty() && dir.isDirectory && System.getProperty("pdfscore.tunings").orEmpty().isNotEmpty())
        val fonts = TestFonts.all(dir)
        val common = CommonSymbols.NAMES.toSet()
        val tunings = listOf(
            SymbolClassifier.Tuning(),
            SymbolClassifier.Tuning(tolerance = 2),
            SymbolClassifier.Tuning(resolution = 24),
            SymbolClassifier.Tuning(resolution = 16),
            SymbolClassifier.Tuning(resolution = 24, tolerance = 2),
            SymbolClassifier.Tuning(resolution = 48, tolerance = 2),
            SymbolClassifier.Tuning(resolution = 48, tolerance = 3),
        )
        for (tuning in tunings) {
            val line = fonts.map { font ->
                val references = fonts.filter { it !== font }.flatMap { f -> f.symbols.filter { it.name in common } }
                val known = references.map { CommonSymbols.canonical(it.name) }.toSet()
                val classifier = SymbolClassifier(references, tuning)
                val tested = font.symbols.filter { CommonSymbols.canonical(it.name) in known && it.name in CORE }
                val got = tested.map { CommonSymbols.canonical(it.name) to classifier.name(it.polygons)?.name }
                val right = got.count { it.first == it.second }
                val wrong = got.count { it.second != null && it.first != it.second }
                if (tuning == tunings.first()) {
                    tested.filter { classifier.name(it.polygons)?.name != CommonSymbols.canonical(it.name) }.forEach { symbol ->
                        println("  ${font.name} ${symbol.name} → ${classifier.name(symbol.polygons)?.name}; " +
                            classifier.rank(symbol.polygons).take(4).joinToString { "${it.name} %.2f".format(it.score) })
                    }
                }
                "${font.name} $right/${tested.size} ($wrong wrong)"
            }
            println("== res ${tuning.resolution} tol ${tuning.tolerance}: " + line.joinToString("; "))
        }
    }

    private companion object {
        val CORE = setOf(
            "noteheadBlack", "noteheadHalf", "noteheadWhole", "restWhole", "restHalf", "restQuarter", "rest8th", "rest16th", "rest32nd",
            "flag8thUp", "flag8thDown", "flag16thUp", "flag16thDown", "flag32ndUp", "flag32ndDown",
            "accidentalSharp", "accidentalFlat", "accidentalNatural", "accidentalDoubleSharp", "accidentalDoubleFlat",
            "gClef", "gClef8vb", "fClef", "cClef", "unpitchedPercussionClef1",
            "timeSig0", "timeSig1", "timeSig2", "timeSig3", "timeSig4", "timeSig5", "timeSig6", "timeSig7", "timeSig8", "timeSig9",
            "timeSigCommon", "timeSigCutCommon", "tuplet3", "tuplet5", "tuplet6", "augmentationDot", "brace",
            "articAccentAbove", "articTenutoAbove", "fermataAbove", "dynamicPiano", "dynamicForte", "dynamicMezzo", "segno", "coda",
        )
    }
}

object TestFonts {
    fun all(dir: File): List<MusicFont> =
        dir.listFiles { file -> file.isDirectory && File(dir, "${file.name}.xml").isFile }.orEmpty().sortedBy { it.name }
            .mapNotNull { folder -> MusicFont.readVerovio(folder.name) { path -> File(dir, path).takeIf { it.isFile }?.readText() } }
}
