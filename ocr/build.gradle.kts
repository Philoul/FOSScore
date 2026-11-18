import java.io.IOException
import java.net.URI
import java.security.MessageDigest

// Reads the text a score draws as outlines or as pixels (tempo marks, titles, lyrics, measure
// numbers): Tesseract (Apache-2.0) through Tesseract4Android (Apache-2.0), with tessdata_fast's
// free language data (Apache-2.0). Behind TextReader, so another engine can take its place.
plugins {
    alias(libs.plugins.android.library)
}

// tessdata_fast's release and the SHA-256 of each language's file. To add a language: its code
// and the checksum of https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/<version>/<code>.traineddata
val tessdataVersion = "4.1.0"
val tessdataSha256 = mapOf(
    "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
    "fra" to "ced037562e8c80c13122dece28dd477d399af80911a28791a66a63ac1e3445ca",
    "ita" to "b8f89e1e785118dac4d51ae042c029a64edb5c3ee42ef73027a6d412748d8827",
    "deu" to "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d",
)

/**
 * The language data, downloaded by the first build like any other library and checked against
 * its checksum. It is kept in `third_party/` (ignored by Git and by Clean): later builds work offline.
 */
fun tessdata(version: String, sha256: Map<String, String>): File {
    val dir = file("third_party/tessdata_fast-$version")
    dir.mkdirs()
    for ((language, expected) in sha256) {
        val target = dir.resolve("$language.traineddata")
        if (target.isFile) continue
        val url = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$version/$language.traineddata"
        logger.lifecycle("Downloading tessdata_fast $version $language (first build only): $url")
        val part = dir.resolve("$language.traineddata.part")
        try {
            URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            part.delete()
            throw GradleException("Could not download $url: the first build needs an internet connection", e)
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(part.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            part.delete()
            throw GradleException("$url is not the expected file (SHA-256 $actual)")
        }
        check(part.renameTo(target)) { "could not move $part" }
    }
    return dir
}

val tessdataDir: File = tessdata(tessdataVersion, tessdataSha256)

android {
    namespace = "com.musigatto.fosscore.ocr"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

/** Copies the language data into the assets (`tessdata/`), from where the reader installs it. */
abstract class CopyTessdata : DefaultTask() {
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
                include("*.traineddata")
                into("tessdata")
            }
            into(outputDir)
        }
    }
}

val copyTessdata = tasks.register<CopyTessdata>("copyTessdata") {
    dataDir.set(tessdataDir)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyTessdata, CopyTessdata::outputDir)
    }
}

dependencies {
    implementation(libs.tesseract4android)
    testImplementation(libs.junit)
}
