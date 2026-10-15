// M0 prototype (throwaway): renders MusicXML with Verovio to measure size, speed and quality.
// Separate applicationId, so it installs next to FOSScore and never touches its data.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Verovio source checkout (version-6.3.0), outside the repository.
val verovioDir: File = rootDir.resolve(
    (findProperty("verovioDir") as String?) ?: "../m0-deps/verovio"
).canonicalFile
val verovioCmakePath: String = verovioDir.invariantSeparatorsPath

android {
    namespace = "org.fosscore.spike.musicxml"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "org.fosscore.spike.musicxml"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "m0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DVEROVIO_DIR=$verovioCmakePath",
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

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Only the default music font (Leipzig) and the fallback (Bravura) are shipped, to measure
// the smallest realistic resource set.
val copyVerovioData by tasks.registering(Copy::class) {
    from(verovioDir.resolve("data")) {
        exclude("Gootville*", "Gootville/**", "Leland*", "Leland/**", "Petaluma*", "Petaluma/**")
    }
    into("src/main/assets/verovio/data")
}
tasks.named("preBuild") { dependsOn(copyVerovioData) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.mupdf.fitz)
    implementation("com.caverock:androidsvg-aar:1.4")
}
