plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.musigatto.fosscore"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.musigatto.fosscore"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // MuPDF ships one .so per ABI (~8-11 MB each). minSdk 23 => 32-bit devices still exist on
        // Android 6/7: armeabi-v7a phones and x86 tablets (e.g. Galaxy Tab 3 10.1 on LineageOS).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    bundle {
        // The language is picked inside the app (LocaleHelper), so an App Bundle must ship every
        // translation instead of only those matching the phone's languages.
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.mupdf.fitz)
    implementation(project(":musicxml"))
    testImplementation(libs.junit)
}