plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties
import java.io.FileInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    namespace = "com.cruxcoach.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cruxcoach.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        // Only bundle native libs for ARM — removes MIPS/x86 bloat from
        // quartz-android's transitive JNA + secp256k1 + libsodium.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        resourceConfigurations += listOf("en", "de")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.2.12479018"

    androidResources {
        // Manual locales_config.xml — AGP's generateLocaleConfig wrongly
        // detects "de" as defaultLocale because values-de/ exists.
        // Our values/strings.xml is English (the actual default).
        generateLocaleConfig = false
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    signingConfigs {
        val storeFilePath = localProps.getProperty("RELEASE_STORE_FILE", "")
        if (storeFilePath.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS", "")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD", "")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

}

composeCompiler {
    metricsDestination = project.layout.buildDirectory.dir("compose_metrics")
    reportsDestination = project.layout.buildDirectory.dir("compose_reports")
}


kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    // Splash screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // AppCompat (per-app language switching)
    implementation(libs.appcompat)

    // Activity + Lifecycle
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.workmanager)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // ZXing (QR code generation for APK sharing)
    implementation(libs.zxing.core)

    // Nostr NIP-17 crash reporting (quartz requires Jackson for event hashing/signing)
    implementation(libs.quartz.android)
    implementation(libs.okhttp)

    // Encrypted SharedPreferences for Nostr key storage
    implementation(libs.security.crypto)
    implementation(libs.biometric)

    // SQLCipher (needed directly for data migration between plain and encrypted DBs)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // Zstd decompression is handled by native C library (see src/main/cpp/)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
