plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

import java.io.FileInputStream
import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}

// Disabled in ordinary and fork builds. The upstream release workflow injects
// the public first-party endpoint explicitly for CruxCoach/CruxCoach only;
// forks may opt into their own endpoint through the same Gradle/local property.
val anonymousMetricsEndpoint = providers.gradleProperty("ANONYMOUS_METRICS_ENDPOINT").orNull
    ?: localProps.getProperty("ANONYMOUS_METRICS_ENDPOINT", "")
if (anonymousMetricsEndpoint.isNotBlank()) {
    val uri = runCatching { URI(anonymousMetricsEndpoint) }.getOrNull()
    require(
        uri != null &&
            uri.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null,
    ) { "ANONYMOUS_METRICS_ENDPOINT must be empty or an HTTPS URL without credentials/fragment" }
}
val anonymousMetricsEndpointBuildConfig = anonymousMetricsEndpoint
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

// Every development branch installs as its own app. Keep the source namespace
// stable (R/BuildConfig imports do not change), but derive a deterministic,
// collision-resistant applicationId suffix from the full Git branch name.
// This lets stable, multiple feature branches, and their independent app data
// coexist on the same Android device and across Android user/work profiles.
val checkedOutGitBranch = providers.exec {
    commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
}.standardOutput.asText.get().trim()
val ciBranchName = listOf(
    "GITHUB_HEAD_REF",
    "GITHUB_REF_NAME",
    "CI_COMMIT_REF_NAME",
    "BRANCH_NAME",
).asSequence()
    .mapNotNull { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
    .firstOrNull()
val developmentBranchName = checkedOutGitBranch.takeUnless { it == "HEAD" }
    ?: ciBranchName
    ?: "detached"
val developmentBranchParts = developmentBranchName.split('/', limit = 2)
val developmentBranchKind = developmentBranchParts
    .takeIf { it.size == 2 }
    ?.first()
    ?.lowercase()
    ?.replace(Regex("[^a-z0-9]+"), "_")
    ?.trim('_')
    ?.ifEmpty { null }
    ?: "branch"
val developmentFeatureName = developmentBranchParts.last()
    // Worktree/date suffixes describe when a branch was created, not the
    // logical feature app users should see on their device.
    .replace(Regex("[-_]?20[0-9]{6}$"), "")
    .ifEmpty { developmentBranchParts.last() }
val developmentBranchSlug = developmentFeatureName
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "_")
    .trim('_')
    .ifEmpty { "detached" }
    .take(48)
val developmentAppIdSuffix = ".dev.$developmentBranchKind.$developmentBranchSlug"
val developmentLabelBranch = developmentFeatureName.take(40)

// CI passes a reviewed, deterministic identity for feat/* builds. The
// production namespace is never accepted as a feature identity, and all
// values are validated again by APKTrack after server-side signing.
val featureBranch = providers.gradleProperty("featureBranch").orNull
val featureTrack = providers.gradleProperty("featureTrack").orNull
val featurePackage = providers.gradleProperty("featurePackage").orNull
val featureLabel = providers.gradleProperty("featureLabel").orNull
val featureVersionCode = providers.gradleProperty("featureVersionCode").orNull?.toIntOrNull()
val featureValues = listOf(featureBranch, featureTrack, featurePackage, featureLabel)
require(featureValues.all { it == null } || featureValues.all { !it.isNullOrBlank() }) {
    "featureBranch, featureTrack, featurePackage, and featureLabel must be supplied together"
}
if (featureBranch != null) {
    require(featureBranch.startsWith("feat/")) { "published feature branches must start with feat/" }
    require(featureTrack!!.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))) {
        "featureTrack must be an APKTrack slug"
    }
    require(featurePackage!!.matches(Regex("com\\.cruxcoach\\.android\\.dev(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
        "featurePackage must be inside the CruxCoach development namespace"
    }
    require(featureVersionCode != null && featureVersionCode in 1..Int.MAX_VALUE) {
        "featureVersionCode must be a positive Android version code"
    }
}

android {
    namespace = "com.cruxcoach.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cruxcoach.android"
        minSdk = 28
        targetSdk = 35
        versionCode = featureVersionCode ?: 24
        versionName = "0.2.3"

        // Only bundle arm64 native libs. armeabi-v7a alone added ~10.7 MB
        // to the APK (libmaplibre 8 MB + sqlcipher + secp256k1 + sodium +
        // a few small libs). minSdk=28 (Android 9+) already targets the
        // 64-bit ARM era; 32-bit-only Android 9+ devices are <1% in DE
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
            "\"${localProps.getProperty("MAINTAINER_LIGHTNING_ADDRESS", "npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s@npub.cash")}\"")
        buildConfigField("String", "ANNOUNCE_NAMESPACE",
            "\"${localProps.getProperty("ANNOUNCE_NAMESPACE", "com.cruxcoach.announce")}\"")

        // FEAT-004 in-app updater: the compiled-in *default* forge for release
        // polling. Forks override via local.properties to point at their own
        // forge. Note this is only the first entry of
        // UpdateSourceRegistry.EMBEDDED — since FEAT-050 the effective source
        // list is data, and the runtime manifest below can reorder or replace
        // it without a new APK.
        //
        // The API root works for Forgejo/Gitea and GitHub alike; their release
        // JSON is field-compatible and both expose {base}/repos/{owner}/{repo}.
        //   Forgejo: https://<host>/api/v1     GitHub: https://api.github.com
        //
        // Changing the forge does NOT invalidate the TOFU pin: that pin is on
        // the APK *signing certificate* (UpdaterPinStore), not on the host. A
        // fork signing with its own key is what invalidates it.
        buildConfigField("String", "UPDATER_API_BASE",
            "\"${localProps.getProperty("UPDATER_API_BASE", "https://codeberg.org/api/v1")}\"")
        buildConfigField("String", "UPDATER_REPO_OWNER",
            "\"${localProps.getProperty("UPDATER_REPO_OWNER", "CruxCoach")}\"")
        buildConfigField("String", "UPDATER_REPO_NAME",
            "\"${localProps.getProperty("UPDATER_REPO_NAME", "CruxCoach")}\"")

        // FEAT-050 runtime source list. Fetched at most daily and cached; the
        // embedded defaults apply whenever it is unreachable or unusable.
        // This is the only lever that can retire a release host for installs
        // already in the field, so it must stay reachable independently of
        // the forge it points at.
        //
        // A LIST, tried in order, because "independently of the forge" was not
        // enough: on 2026-08-05 the apex went to 502 for a full day (Codeberg
        // moved custom-domain Pages to a new server and our DNS never followed)
        // and took the source list with it. Shipping a multi-source updater
        // whose source list hangs on one host is the same mistake one layer up.
        //
        // The mirror is deliberately NOT cruxcoach.github.io: once the apex is
        // a GitHub Pages custom domain, that origin 301s to the apex and stops
        // being independent. mirror.cruxcoach.org is our own machine, which is
        // the one we can always fix ourselves.
        buildConfigField("String", "UPDATE_SOURCES_URLS",
            "\"${localProps.getProperty("UPDATE_SOURCES_URLS",
                "https://cruxcoach.org/update-sources.json," +
                    "https://mirror.cruxcoach.org/update-sources.json")}\"")
        // Plain release pointer already published by the website
        // (tools/update-download-link.mjs writes it every night). Same list
        // and same reasoning as above — it is served from the same two hosts.
        buildConfigField("String", "UPDATER_MANIFEST_URLS",
            "\"${localProps.getProperty("UPDATER_MANIFEST_URLS",
                "https://cruxcoach.org/apk-target.json," +
                    "https://mirror.cruxcoach.org/apk-target.json")}\"")
        // Content-addressed, download-only last resort (BUD-01 GET /<sha256>).
        // These are the public Blossom servers cruxcoach-blossom-sync already
        // publishes board-DB chunks to. The project's own blossom-server is
        // deliberately absent: it binds 127.0.0.1:3000 with no publicDomain
        // and no reverse proxy, so devices cannot reach it.
        buildConfigField("String", "UPDATER_BLOSSOM_SERVERS",
            "\"${localProps.getProperty("UPDATER_BLOSSOM_SERVERS", "https://blossom.primal.net,https://nostr.download,https://cdn.hzrd149.com")}\"")
        // Where the cert-mismatch handoff (§5.4.3) sends the user when the
        // discovering source has no page of its own to show. Must NOT be a
        // forge URL: this is precisely the path a user needs when the forge
        // is the thing that changed.
        // The anchor is #install — verified against the live page, which has
        // ids {why, features, install, de-googled, privacy, faq, contribute}
        // and no #download. A wrong fragment does not 404, it just silently
        // fails to scroll, which is exactly the kind of thing nobody notices.
        buildConfigField("String", "UPDATER_RELEASE_PAGE_URL",
            "\"${localProps.getProperty("UPDATER_RELEASE_PAGE_URL", "https://cruxcoach.org/#install")}\"")
        // Where the in-app share QR sends a scanner.
        //
        // A QR encodes exactly one string and gets no second chance: the phone
        // that generated it is gone, and whatever host it names has to answer
        // months later. The website's Download button solves the same problem
        // with a click-time hook it has and a QR does not — it ships the
        // durable forge URL in `href` and is upgraded to our selector only
        // after a beacon proves the selector is up.
        //
        // So the QR names the most available host we have (the apex, a CDN)
        // and the fallback chain lives in the page: /get.html tries the
        // selector, then the forge, then the CDN blob, with every URL built
        // from the nightly apk-target.json rather than baked in. Pointing
        // straight at the selector was one host with no way back.
        //
        // Deliberately its own constant, not UPDATER_RELEASE_PAGE_URL: that
        // one is the cert-mismatch handoff, where landing on a page is right
        // because a human has to read something and decide.
        buildConfigField("String", "APP_SHARE_DOWNLOAD_URL",
            "\"${localProps.getProperty("APP_SHARE_DOWNLOAD_URL", "https://cruxcoach.org/get.html")}\"")

        // minSdk of the NEXT release, so this build can tell a device that it
        // is about to fall out of support and say so while it still can.
        //
        // 0.2.3 is the release that carries out the rise from 26 to 28 that
        // 0.2.2 warned about: v3 signing — and with it the certificate lineage
        // that makes a key rotation installable — does not exist before API 28,
        // and a rotation that leaves the old key valid on 26/27 would not
        // actually retire a compromised key. Android 8.0/8.1 stopped at 0.2.2,
        // which is where the warning shipped.
        //
        // It is equal to this build's own minSdk now, and that is correct
        // rather than an oversight: no device that can run 0.2.3 is being
        // dropped by 0.2.4, so `receivesFutureUpdates()` is true for every
        // device that can see this build and nobody is told a falsehood. Raise
        // it again one release BEFORE the next minSdk rise, never in the same
        // one — that is the invariant, not "never equal".
        buildConfigField("int", "MIN_SDK_NEXT_RELEASE",
            localProps.getProperty("MIN_SDK_NEXT_RELEASE", "28"))

        // One-way aggregate increment after a fully verified in-app update APK.
        // Empty by default; only the official CI build injects upstream's URL.
        buildConfigField("String", "ANONYMOUS_METRICS_ENDPOINT",
            "\"$anonymousMetricsEndpointBuildConfig\"")

        // Zapstore's signed release metadata and content-addressed CDN provide
        // a second direct-APK path. Forks override all three values together
        // to point at their own publisher namespace and infrastructure.
        buildConfigField("String", "ZAPSTORE_APP_URL",
            "\"${localProps.getProperty("ZAPSTORE_APP_URL", "https://zapstore.dev/apps/com.cruxcoach.android")}\"")
        buildConfigField("String", "ZAPSTORE_RELAY_URL",
            "\"${localProps.getProperty("ZAPSTORE_RELAY_URL", "wss://relay.zapstore.dev")}\"")
        buildConfigField("String", "ZAPSTORE_CDN_BASE_URL",
            "\"${localProps.getProperty("ZAPSTORE_CDN_BASE_URL", "https://cdn.zapstore.dev")}\"")

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
                // v1 (JAR) signing MUST stay on. The in-app updater's ROM
                // fallback signer check (IntegrityVerifier.extractSignerFromZip)
                // reads the META-INF/*.RSA v1 signature when
                // getPackageArchiveInfo() returns null — observed on HTC
                // Android 9 / API 28. Without a v1 signature that fallback is
                // dead and self-update is unrecoverable on those ROMs.
                // (`apksigner verify` reports "v1: false" on a minSdk-26 APK
                // because it validates via the strongest applicable scheme;
                // the v1 block is still present — check with
                // `--min-sdk-version 23`.)
                enableV1Signing = true
                enableV2Signing = true
                // v3 carries the SigningCertificateLineage, which is the only
                // mechanism that lets a signing key be rotated without every
                // installed app refusing the update. Turned on here, one
                // release BEFORE any rotation, deliberately: the lineage lives
                // in the *new* APK, but shipping the scheme early means the
                // v3 pipeline is exercised and verified while the key is still
                // the old one and a mistake is cheap.
                //
                // NOTE for the rotation itself: AGP has no DSL for a lineage.
                // The rotated release must be re-signed with
                // `apksigner sign --lineage …` as a post-build step — see
                // docs/KEY_ROTATION.md.
                enableV3Signing = true
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
        debug {
            if (featurePackage != null) {
                applicationIdSuffix = featurePackage.removePrefix("com.cruxcoach.android")
                resValue("string", "app_name", featureLabel!!)
                buildConfigField("String", "APKTRACK_FEATURE_TRACK", "\"${featureTrack}\"")
                buildConfigField("String", "APKTRACK_SOURCE_BRANCH", "\"${featureBranch}\"")
            } else {
                applicationIdSuffix = developmentAppIdSuffix
                versionNameSuffix = "-dev"
                resValue("string", "app_name", "CruxCoach Dev · $developmentLabelBranch")
                buildConfigField("String", "APKTRACK_FEATURE_TRACK", "\"\"")
                buildConfigField("String", "APKTRACK_SOURCE_BRANCH", "\"\"")
            }
        }
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
        // Backport newer java.* APIs to the supported old-API range (minSdk 28).
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

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/fipsJniLibs"))

    packaging {
        dex {
            // minSdk 28 makes AGP store DEX files uncompressed by default so
            // Android can mmap them directly from the APK. That turns the
            // debug APK served by the peer-to-peer offline-share flow from
            // roughly 60 MB into roughly 140 MB and causes real Wi-Fi Direct
            // transfers to time out. Prefer the smaller transport artifact;
            // Android extracts the DEX files during installation instead.
            useLegacyPackaging = true
        }
    }

}

val fipsCrateDir = rootProject.layout.projectDirectory.dir("native/fips-bridge")
val fipsSo = layout.buildDirectory.file("generated/fipsJniLibs/arm64-v8a/libcruxcoach_fips.so")
val buildFipsNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the pinned FIPS Rust bridge for arm64 Android"
    workingDir(fipsCrateDir)
    val ndkRoot = android.ndkDirectory.absolutePath
    val toolBin = "$ndkRoot/toolchains/llvm/prebuilt/linux-x86_64/bin"
    environment("PATH", "${System.getProperty("user.home")}/.cargo/bin:$toolBin:${System.getenv("PATH")}")
    environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", "$toolBin/aarch64-linux-android28-clang")
    environment("CC_aarch64_linux_android", "$toolBin/aarch64-linux-android28-clang")
    // The developer workstation may have an unrelated `cc` helper earlier in
    // PATH; build scripts themselves are host binaries and require the real C compiler.
    environment("CC", "/usr/bin/cc")
    environment("CXX", "/usr/bin/c++")
    environment("CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER", "/usr/bin/cc")
    commandLine("${System.getProperty("user.home")}/.cargo/bin/cargo", "+1.94.1", "build",
        "--locked", "--release", "--target", "aarch64-linux-android")
    inputs.files(fileTree(fipsCrateDir) { include("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "src/**") })
    outputs.file(fipsSo)
    doLast {
        copy {
            from(fipsCrateDir.file("target/aarch64-linux-android/release/libcruxcoach_fips.so"))
            into(fipsSo.get().asFile.parentFile)
        }
    }
}

tasks.matching {
    it.name == "mergeDebugNativeLibs" || it.name == "mergeReleaseNativeLibs" ||
        it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders"
}
    .configureEach { dependsOn(buildFipsNative) }

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
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

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
    // MockWebServer for KilterApiClient HTTP-error-mapping tests.
    // Pulls okhttp explicitly on the test classpath so okhttp3.internal.*
    // is resolvable at test runtime (mockwebserver depends on internals
    // that the production-only `implementation(okhttp)` doesn't expose
    // here through the ASM-transformed test runtime).
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
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
