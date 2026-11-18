pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MuPDF (AAR prebuilt con el JNI incluido, sin NDK). AGPL-3.0: ver LICENSE.
        maven { url = uri("https://maven.ghostscript.com") }
        // Tesseract4Android (Apache-2.0), published there only; nothing else is taken from it
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "FOSScore"
include(":app")
include(":musicxml")
include(":pdfscore")
include(":ocr")
