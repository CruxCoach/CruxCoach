plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.licensee)
}

import java.io.File
import java.io.FileInputStream
import java.util.Properties
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val localPropertiesOverride = providers.gradleProperty("localPropertiesFile").orNull
val localPropertiesFile = localPropertiesOverride
    ?.let(rootProject::file)
    ?: rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropertiesFile.exists()) load(FileInputStream(localPropertiesFile))
}
val localPropertiesBaseDir = if (localPropertiesOverride == null) {
    rootProject.projectDir
} else {
    localPropertiesFile.parentFile
}
val releaseStorePath = localProps.getProperty("RELEASE_STORE_FILE", "").trim()
val releaseStoreFile = releaseStorePath.takeIf(String::isNotEmpty)?.let { path ->
    File(path).let { if (it.isAbsolute) it else File(localPropertiesBaseDir, path) }
}
val allowDebugSignedRelease = (
    providers.gradleProperty("allowDebugSignedRelease").orNull
        ?: localProps.getProperty("ALLOW_DEBUG_SIGNED_RELEASE", "false")
    ).toBooleanStrictOrNull() == true

android {
    namespace = "com.cruxcoach.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cruxcoach.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.1"

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
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS", "")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD", "")
                // v1 (JAR) signing MUST stay on. The in-app updater's ROM
                // fallback signer check (IntegrityVerifier.extractSignerFromZip)
                // reads the META-INF/*.RSA v1 signature when
                // getPackageArchiveInfo() returns null — observed on HTC
                // Android 9 / API 28. Without a v1 signature that fallback is
                // dead and self-update is unrecoverable on those ROMs.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs merged Android resources on the test classpath.
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            } else if (allowDebugSignedRelease) {
                signingConfig = signingConfigs.getByName("debug")
            }
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
        // Backport newer java.* APIs to the supported old-API range (minSdk 26).
        // Immunizes the SequencedCollection/SequencedMap class of bug (old-API
        // audit C-1: .reversed()/removeFirst()/etc. on java.util receivers binding
        // to API-35 platform members) on Android < 15.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Fail the build on lint errors (incl. NewApi unguarded-API calls) when
        // lint runs — neither C-1 nor C-2 was caught at build time before.
        abortOnError = true
        checkReleaseBuilds = true
    }

}

val generateLegalAssets = tasks.register<Sync>("generateLegalAssets") {
    group = "build"
    description = "Packages project and third-party legal notices into the APK."
    into(layout.buildDirectory.dir("generated/legalAssets/licenses"))

    from(rootProject.file("LICENSE")) {
        rename { "CruxCoach-GPL-3.0-only.txt" }
    }
    from(
        rootProject.files(
            "NOTICE",
            "THIRD_PARTY_LICENSES.md",
            "LEGAL.md",
            "TRADEMARK.md",
        )
    )
    from(rootProject.file("LICENSES"))
    from(rootProject.file("logos/LICENSE")) {
        rename { "CruxCoach-artwork-GPL-3.0-only.txt" }
    }
    from(file("src/main/cpp/zstd/LICENSE")) {
        rename { "zstd-BSD-3-Clause.txt" }
    }
    from(file("src/main/cpp/zstd/COPYING")) {
        rename { "zstd-GPL-2.0.txt" }
    }
}

android.sourceSets.getByName("main").assets.srcDir(
    layout.buildDirectory.dir("generated/legalAssets")
)
tasks.named("preBuild").configure {
    dependsOn(generateLegalAssets)
}

composeCompiler {
    metricsDestination = project.layout.buildDirectory.dir("compose_metrics")
    reportsDestination = project.layout.buildDirectory.dir("compose_reports")
}

licensee {
    allow("Apache-2.0")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("MIT")
    allowUrl("http://www.mozilla.org/MPL/2.0/index.txt") {
        because("MPL-2.0; legacy URL in lazysodium-android 5.2.0 metadata")
    }
    allowUrl("https://github.com/vitorpamplona/amethyst/blob/main/LICENSE") {
        because("MIT; Quartz 1.05.1 POM links to the upstream Amethyst license")
    }
    allowUrl("https://www.zetetic.net/sqlcipher/license/") {
        because("SQLCipher Community Edition BSD-style license; full text is bundled")
    }
    bundleAndroidAsset = true
}


kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Refuses accidental debug-signed or incomplete release builds."
    doLast {
        if (releaseStoreFile == null && !allowDebugSignedRelease) {
            throw GradleException(
                "Release builds require RELEASE_STORE_FILE. Refusing to create a release " +
                    "with the non-secret debug key. For a throwaway local build only, pass " +
                    "-PallowDebugSignedRelease=true. See CONTRIBUTING.md."
            )
        }
        if (releaseStoreFile == null) {
            logger.warn(
                "WARNING: release APK uses the DEBUG key because " +
                    "allowDebugSignedRelease=true. Do not distribute it."
            )
            return@doLast
        }

        val missingKeys = listOf(
            "RELEASE_STORE_PASSWORD",
            "RELEASE_KEY_ALIAS",
            "RELEASE_KEY_PASSWORD",
        ).filter { localProps.getProperty(it, "").isBlank() }
        if (missingKeys.isNotEmpty()) {
            throw GradleException(
                "Release signing properties are incomplete: ${missingKeys.joinToString()}"
            )
        }
        if (!releaseStoreFile.isFile) {
            throw GradleException("Release keystore does not exist: $releaseStoreFile")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    implementation(project(":shared"))

    // Core library desugaring — backports newer java.* APIs to old Android
    // (see compileOptions.isCoreLibraryDesugaringEnabled; old-API audit P1).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

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

    // Nostr NIP-17 crash reporting. Keep quartz's untrusted-JSON parser on a
    // patched, internally aligned Jackson line rather than its stale transitives.
    implementation(platform(libs.jackson.bom))
    implementation(libs.quartz.android)
    implementation(libs.okhttp)

    // security-crypto is deprecated but retained for the existing on-disk
    // EncryptedSharedPreferences format. Control its maintained crypto engine
    // directly until the documented read-old/write-new migration is complete.
    implementation(libs.security.crypto)
    implementation(libs.tink.android)
    implementation(libs.biometric)

    // SQLCipher (needed directly for data migration between plain and encrypted DBs)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // MapLibre Native (FEAT-006 Kilter Board Locations Map). Vector tiles via
    // OpenFreeMap vector tiles, no API key. Markers/clusters are drawn via
    // GeoJSON sources + symbol layers on the raw style spec, so the MapLibre
    // annotation plugin isn't needed (it was declared but never imported).
    implementation(libs.maplibre.android.sdk)

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
    // Android SQLite driver for Robolectric importer tests: creating the real
    // SQLDelight schema inside the Robolectric sandbox must NOT go through
    // JDBC — DriverManager initialised from the sandbox classloader makes
    // the sqlite-JDBC driver invisible to every plain-JVM JDBC test that
    // runs later in the same Gradle worker.
    testImplementation(libs.sqldelight.android.driver)
    // MockWebServer compatibility facade and the application both resolve the
    // same OkHttp 5.3.2 runtime; tests must not exercise a different major.
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
