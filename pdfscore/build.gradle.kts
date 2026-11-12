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
            it.maxHeapSize = "2g"
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.pdfbox)
    testImplementation(libs.org.json)
}
