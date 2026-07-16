import org.gradle.api.artifacts.dsl.LockMode

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Exercises the supported debug, unit-test and release graphs for lock generation."
    dependsOn(
        ":shared:testDebugUnitTest",
        ":androidApp:testDebugUnitTest",
        ":androidApp:assembleDebug",
        ":androidApp:assembleRelease",
    )
    doFirst {
        check(gradle.startParameter.isWriteDependencyLocks) {
            "resolveAndLockAll must be run with --write-locks"
        }
    }
}
