import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Application core of the native iOS app: everything between the SwiftUI
// views and :shared (sync, Nostr, BLE control, presenters).
//
// :androidApp does NOT depend on this module, so nothing here can change the
// Android APK. The Android target exists only so commonTest runs on the JVM
// (`:appcore:testDebugUnitTest`) on hosts without Xcode.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            // Umbrella framework consumed by iosApp/. Static: the app's final
            // link decides which SQLite is used, and it must be SQLCipher.
            baseName = "CruxCoachCore"
            isStatic = true
            export(project(":shared"))
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.experimental.ExperimentalObjCName")
        }
        commonMain.dependencies {
            api(project(":shared"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // Kotlin binding of bitcoin-core/libsecp256k1 (BIP-340 Schnorr, ECDH).
            implementation(libs.secp256k1.kmp)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidUnitTest").dependencies {
            // Test-only: real SQLite and real libsecp256k1 on the JVM, so the
            // portable sync/Nostr logic is exercised without an Apple host.
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.secp256k1.kmp.jni.jvm)
            // Test-only: Quartz, the library Android's app uses, as an
            // independent implementation to check ours against. It is built
            // with a newer Kotlin than this project uses, which is why the
            // metadata check is relaxed for the test compilation only — see
            // NOSTR-DEPENDENCY-MIGRATION.md. Never a production dependency.
            implementation(libs.quartz.kmp.jvm)
        }
    }
}

// The suite opens many in-memory SQLite databases and loads libsecp256k1. On the
// default worker heap it spent its last tests in GC, which made timing-sensitive
// presenter tests fail at random while the same tests passed in isolation.
tasks.withType<Test>().configureEach {
    maxHeapSize = "1500m"
}

android {
    namespace = "com.cruxcoach.appcore"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The test-only Quartz reference is compiled with Kotlin 2.4.x while this
// project is on 2.3. Relaxing the metadata check for the *unit-test*
// compilation lets the real library run beside our implementation so the two
// can be checked against each other. Production code is untouched by this.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest", ignoreCase = true)) {
        compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
