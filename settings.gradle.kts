pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CruxCoach"
include(":shared")
include(":androidApp")
// iOS application core. :androidApp does not depend on it, so the Android APK is unaffected.
include(":appcore")
