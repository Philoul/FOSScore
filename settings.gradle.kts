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
        // MuPDF (prebuilt AAR with the JNI bundled, no NDK). AGPL-3.0: see LICENSE.
        maven { url = uri("https://maven.ghostscript.com") }
    }
}

rootProject.name = "FOSScore"
include(":app")
include(":musicxml")
