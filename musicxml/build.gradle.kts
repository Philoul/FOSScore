// MusicXML support: lays out MusicXML scores for the screen with Verovio (LGPL-3.0, built from
// the pinned submodule in verovio/, without any change) and draws the pages through MuPDF.
// See README.md.
plugins {
    alias(libs.plugins.android.library)
}

val verovioDir: File = file("verovio")

android {
    namespace = "com.musigatto.fosscore.musicxml"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // The app keeps only the ABIs it ships (its own abiFilters).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DVEROVIO_DIR=${verovioDir.invariantSeparatorsPath}",
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

/**
 * Copies Verovio's font data into the assets. Only the default music font (Leipzig), the
 * fallback (Bravura) and the text fonts are shipped: the other music fonts add about 4 MB.
 */
abstract class CopyVerovioData : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(dataDir) {
                exclude("Gootville*", "Gootville/**", "Leland*", "Leland/**", "Petaluma*", "Petaluma/**")
                into("verovio/data")
            }
            into(outputDir)
        }
    }
}

val copyVerovioData = tasks.register<CopyVerovioData>("copyVerovioData") {
    dataDir.set(verovioDir.resolve("data"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyVerovioData, CopyVerovioData::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mupdf.fitz)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
