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
        versionCode = 5
        versionName = "0.1.4"

        // Only bundle arm64 native libs. armeabi-v7a alone added ~10.7 MB
        // to the APK (libmaplibre 8 MB + sqlcipher + secp256k1 + sodium +
        // a few small libs). minSdk=26 (Android 8.0+) already targets the
        // 64-bit ARM era; 32-bit-only Android 8+ devices are <1% in DE
        // (mostly Android Go on entry-level SoCs, almost absent here).
        // Affected devices simply can't install (clean "incompatible"
        // message), no partial breakage. x86/MIPS were never targeted.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        resourceConfigurations += listOf("en", "de")

        // Maintainer-bound constants exposed as BuildConfig fields so forks
        // can override them in their own local.properties without source
        // edits. See CONTRIBUTING.md "Customizing for forks" and
        // TRADEMARK.md for the rationale.
        buildConfigField("String", "MAINTAINER_PUBKEY",
            "\"${localProps.getProperty("MAINTAINER_PUBKEY", "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d")}\"")
        buildConfigField("String", "MAINTAINER_KOFI_URL",
            "\"${localProps.getProperty("MAINTAINER_KOFI_URL", "https://ko-fi.com/cruxcoach")}\"")
        buildConfigField("String", "MAINTAINER_LIGHTNING_ADDRESS",
            "\"${localProps.getProperty("MAINTAINER_LIGHTNING_ADDRESS", "cruxcoach@npub.cash")}\"")
        buildConfigField("String", "ANNOUNCE_NAMESPACE",
            "\"${localProps.getProperty("ANNOUNCE_NAMESPACE", "com.cruxcoach.announce")}\"")

        // FEAT-004 in-app updater: source of truth for release polling.
        // Hardcoded for release builds; forks override via local.properties
        // to point at their own Codeberg repo (changing the source repo
        // invalidates the TOFU cert pin, so this cannot be user-configurable).
        buildConfigField("String", "UPDATER_API_BASE",
            "\"${localProps.getProperty("UPDATER_API_BASE", "https://codeberg.org/api/v1")}\"")
        buildConfigField("String", "UPDATER_REPO_OWNER",
            "\"${localProps.getProperty("UPDATER_REPO_OWNER", "CruxCoach")}\"")
        buildConfigField("String", "UPDATER_REPO_NAME",
            "\"${localProps.getProperty("UPDATER_REPO_NAME", "CruxCoach")}\"")

        // Zapstore app-listing URL shown as a share QR / link in the
        // Settings → "Share app" section. Forks override via
        // local.properties to point at their own Zapstore namespace.
        buildConfigField("String", "ZAPSTORE_APP_URL",
            "\"${localProps.getProperty("ZAPSTORE_APP_URL", "https://zapstore.dev/apps/com.cruxcoach.android")}\"")

        // Brand-bound constants used in outgoing HTTP traffic, App Links,
        // and the Kind-1 Auto-Note publish path. Forks override via
        // local.properties so they can present as their own brand on the
        // wire (User-Agent visible to Kilter operators, host of any
        // shareable climb URL, p-tag amplification on auto-Note).
        val appLinkHost = localProps.getProperty("APP_LINK_HOST", "cruxcoach.org")
        buildConfigField("String", "USER_AGENT_PRODUCT",
            "\"${localProps.getProperty("USER_AGENT_PRODUCT", "CruxCoach")}\"")
        buildConfigField("String", "APP_LINK_HOST",
            "\"$appLinkHost\"")
        // Auto-Note p-tag mention of MAINTAINER_PUBKEY. Default true for
        // upstream (the maintainer self-mention is a known growth-hack
        // for upstream installs); forks set false so their users don't
        // accidentally amplify whoever the fork's MAINTAINER_PUBKEY
        // resolves to.
        buildConfigField("Boolean", "AUTO_NOTE_PTAG_MAINTAINER",
            localProps.getProperty("AUTO_NOTE_PTAG_MAINTAINER", "true"))
        // Mirror the App Link host into a manifest placeholder so the
        // <intent-filter><data android:host=…> entry stays in lockstep
        // with what BuildConfig.APP_LINK_HOST tells the runtime parser.
        manifestPlaceholders["appLinkHost"] = appLinkHost
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
        // Robolectric needs merged Android resources on the test classpath.
        unitTests.isIncludeAndroidResources = true
    }

    // Pin OkHttp on the *unit-test* classpath to 4.12.0 so MockWebServer 4.12
    // resolves the `okhttp3.internal.Util` it expects. Production keeps the
    // 5.3.2 that quartz-android transitively pulls — only the test runtime
    // needs the older internals layout. The 5.x-only artifacts (okhttp-android,
    // okhttp-coroutines) are excluded from test classpaths so they don't drag
    // 5.x .class files in alongside the forced 4.12 okhttp jar.
    configurations.matching {
        it.name.endsWith("UnitTestRuntimeClasspath") ||
        it.name.endsWith("UnitTestCompileClasspath")
    }.configureEach {
        resolutionStrategy {
            force("com.squareup.okhttp3:okhttp:4.12.0")
        }
        exclude(group = "com.squareup.okhttp3", module = "okhttp-android")
        exclude(group = "com.squareup.okhttp3", module = "okhttp-coroutines")
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
        buildConfig = true
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

    // MapLibre Native (FEAT-006 Kilter Board Locations Map). Vector tiles via
    // OpenFreeMap, no API key. Annotation plugin gives us higher-level marker
    // / cluster APIs over the raw style spec.
    implementation(libs.maplibre.android.sdk)
    implementation(libs.maplibre.android.plugin.annotation)

    // Zstd decompression is handled by native C library (see src/main/cpp/)

    // FEAT-010 profile editor: image loading + markdown preview
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.compose.richtext.commonmark)
    implementation(libs.compose.richtext.ui.material3)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // JDBC SQLite driver for real-SQL repository races / TOCTOU regression tests
    testImplementation(libs.sqldelight.sqlite.driver)
    // MockWebServer for KilterApiClient HTTP-error-mapping tests.
    // Pulls okhttp explicitly on the test classpath so okhttp3.internal.*
    // is resolvable at test runtime (mockwebserver depends on internals
    // that the production-only `implementation(okhttp)` doesn't expose
    // here through the ASM-transformed test runtime).
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    // Robolectric: Android-framework shim on JVM. Activity lifecycle,
    // SharedPreferences, Resources, Context — needed for ViewModel tests
    // that pull anything from the Android side.
    testImplementation(libs.robolectric)
    // Turbine: ergonomic StateFlow/Flow assertions in tests.
    testImplementation(libs.turbine)
    // Compose UI test rules + semantics-tree assertions.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Hilt-Android-Testing: TestApplication + module-replacement plumbing
    // for ViewModels that depend on @HiltAndroidApp injection.
    testImplementation(libs.hilt.android.testing)
}
