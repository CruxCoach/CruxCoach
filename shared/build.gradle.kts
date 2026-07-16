import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iosTarget – uncomment when ready for iOS
    // listOf(
    //     iosX64(),
    //     iosArm64(),
    //     iosSimulatorArm64()
    // ).forEach {
    //     it.binaries.framework {
    //         baseName = "shared"
    //         isStatic = true
    //     }
    // }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }
    }
}

android {
    namespace = "com.cruxcoach.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    // schemaOutputDirectory enables the executable snapshot-diff tasks wired
    // into release CI. The separate compile-time verifyMigrations flag cannot
    // reconstruct this repository's historical v1 starting schemas; the real
    // v1 chains are instead executed by the checked-in JDBC migration tests.
    databases {
        create("BoardDatabase") {
            packageName.set("com.cruxcoach.db.board")
            srcDirs.setFrom("src/commonMain/sqldelight/board")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/board"))
        }
        create("SecureDatabase") {
            packageName.set("com.cruxcoach.db.secure")
            srcDirs.setFrom("src/commonMain/sqldelight/secure")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/secure"))
        }
    }
}
