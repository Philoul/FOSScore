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

object TestFonts {
    fun all(dir: File): List<MusicFont> =
        dir.listFiles { file -> file.isDirectory && File(dir, "${file.name}.xml").isFile }.orEmpty().sortedBy { it.name }
            .mapNotNull { folder -> MusicFont.readVerovio(folder.name) { path -> File(dir, path).takeIf { it.isFile }?.readText() } }
}
