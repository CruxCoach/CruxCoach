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

// Owner review: expose test failures in public check annotations for the
// credential-free feature test jobs, including the publisher's build job.
// This only adds diagnostics; it does not change tests or publication gates.
if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true" &&
    providers.gradleProperty("featureBranch").orNull?.startsWith("feat/") == true &&
    gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "testDebugUnitTest" }) {
    println("::add-matcher::${file("scripts/ci-test-problems.json").absolutePath}")
}
