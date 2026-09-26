package com.cruxcoach.android

import com.cruxcoach.android.ui.whatsnew.WhatsNewItems
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The build has to say which release it is.
 *
 * This branch prepares 0.2.3. An earlier attempt shipped an APK identifying as the
 * wrong release, which nothing caught because nothing looked. A wrong
 * versionName is not cosmetic: the in-app updater compares it to decide whether
 * an update is available, so a build calling itself the wrong version would
 * offer itself an update forever.
 */
class ReleaseMetadataTest {

    private val gradle: String by lazy {
        listOf(File("build.gradle.kts"), File("androidApp/build.gradle.kts"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("androidApp/build.gradle.kts not found (cwd=${File(".").absolutePath})")
    }

    private fun intField(name: String): Int =
        Regex("""\b$name\s*=\s*(?:featureVersionCode\s*\?:\s*)?(\d+)""")
            .find(gradle)?.groupValues?.get(1)?.toInt()
            ?: error("$name not found in the build file")

    private fun stringField(name: String): String =
        Regex("""\b$name\s*=\s*"([^"]+)"""").find(gradle)?.groupValues?.get(1)
            ?: error("$name not found in the build file")

    @Test
    fun `the build identifies as this release`() {
        assertEquals("0.2.3", stringField("versionName"))
        assertEquals(9, intField("versionCode"))
        assertEquals(
            "0.2.3",
            BuildConfig.VERSION_NAME.removeSuffix("-dev"),
            "debug builds may carry the configured -dev suffix",
        )
        if (BuildConfig.APKTRACK_FEATURE_TRACK.isEmpty()) {
            assertEquals(9, BuildConfig.VERSION_CODE)
        }
    }

    @Test
    fun `0_2_3 implements the previously announced Android 9 minimum`() {
        assertEquals(28, intField("minSdk"))
        assertEquals(28, BuildConfig.MIN_SDK_NEXT_RELEASE)
    }

    @Test
    fun `the end-of-support warning never claims a device is being dropped when it is not`() {
        // No further minimum-SDK increase is announced for this candidate.
        assertTrue(
            BuildConfig.MIN_SDK_NEXT_RELEASE >= intField("minSdk"),
            "the next release cannot require less than this one",
        )
    }

    @Test
    fun `this release announces itself to upgrading users`() {
        val item = WhatsNewItems.registry.singleOrNull { it.sinceVersionCode == intField("versionCode") }
        assertTrue(
            item != null,
            "a release with no What's New entry is invisible to everyone upgrading into it",
        )
        assertEquals("release-0.2.3", item.id)
    }

    @Test
    fun `the What's New registry stays in ascending order and announces nothing from the future`() {
        val codes = WhatsNewItems.registry.map { it.sinceVersionCode }
        assertEquals(codes.sorted(), codes, "the registry is documented as ascending")
        assertTrue(
            codes.all { it <= intField("versionCode") },
            "an entry newer than the build would never fire",
        )
    }

    @Test
    fun `the changelog has a section for this release`() {
        val changelog = listOf(File("CHANGELOG.md"), File("../CHANGELOG.md"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("CHANGELOG.md not found")
        val releaseVersion = BuildConfig.VERSION_NAME.removeSuffix("-dev")
        assertTrue(
            changelog.contains("## [$releaseVersion]"),
            "the changelog has no section for $releaseVersion",
        )
    }
}
