// Reads a score's PDF into music (MusicXML): the staves, clefs, notes and their durations that a
// notation program drew. Pure Kotlin, no Android dependency: what reads the PDF is given to it.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.musigatto.fosscore.pdfscore"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.all {
            // The scores the recognition is checked against, outside the repository (the
            // user's own scores are not ours to publish): -PpdfscoreCorpus=<folder>
            it.systemProperty("pdfscore.corpus", providers.gradleProperty("pdfscoreCorpus").getOrElse(""))
            // One system to look at in detail (CorpusProbeTest.probeSystem): "<pdf>|<page>|<system>"
            it.systemProperty("pdfscore.probe", providers.gradleProperty("pdfscoreProbe").getOrElse(""))
            // Scores engraved by Verovio in each free font, with their MusicXML (VerovioFontsProbeTest):
            // -PpdfscoreEngraved=<folder>, outside the repository like the corpus
            it.systemProperty("pdfscore.engraved", providers.gradleProperty("pdfscoreEngraved").getOrElse(""))
            // Only these parts or fonts (VerovioFontsProbeTest), comma separated
            it.systemProperty("pdfscore.only", providers.gradleProperty("pdfscoreOnly").getOrElse(""))
            // Compare tunings of the symbol classifier (FontTuningsProbeTest): -PpdfscoreTunings=yes
            it.systemProperty("pdfscore.tunings", providers.gradleProperty("pdfscoreTunings").getOrElse(""))
            // PDFs engraved in fonts none of the references, each beside its MusicXML, kept private
            // outside the repository (PrivateScoresProbeTest): -PpdfscorePrivate=<folder>
            it.systemProperty("pdfscore.private", providers.gradleProperty("pdfscorePrivate").getOrElse(""))
            // The free music fonts the recognition compares symbols with: Verovio's font data,
            // which :musicxml downloads with Verovio's source.
            it.systemProperty(
                "pdfscore.fonts",
                rootProject.file("musicxml/third_party").listFiles { file -> file.name.startsWith("verovio-") }
                    ?.maxOrNull()?.resolve("data")?.path ?: "",
            )
            it.maxHeapSize = "2g"
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.pdfbox)
}
