package com.cruxcoach.android

import com.cruxcoach.android.ui.whatsnew.WhatsNewItems
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The build has to say which release it is.
 *
 * This branch *is* 0.2.3, and the first two attempts at it shipped an APK that
 * identified as 0.2.2 — which nothing caught, because nothing looked. A wrong
 * versionName is not cosmetic: the in-app updater compares it to decide whether
 * an update is available, so a 0.2.3 build calling itself 0.2.2 would offer
 * itself an update forever.
 */
class ReleaseMetadataTest {

    private fun requireProductionReleaseMetadata() {
        assumeTrue(
            "exact release metadata applies only to production builds",
            BuildConfig.APKTRACK_FEATURE_TRACK.isEmpty(),
        )
    }

    private val gradle: String by lazy {
        listOf(File("build.gradle.kts"), File("androidApp/build.gradle.kts"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("androidApp/build.gradle.kts not found (cwd=${File(".").absolutePath})")
    }

    private fun intField(name: String): Int =
        Regex("""\b$name\s*=\s*(\d+)""").find(gradle)?.groupValues?.get(1)?.toInt()
            ?: error("$name not found in the build file")

    private fun stringField(name: String): String =
        Regex("""\b$name\s*=\s*"([^"]+)"""").find(gradle)?.groupValues?.get(1)
            ?: error("$name not found in the build file")

    @Test
    fun `the build identifies as this release`() {
        requireProductionReleaseMetadata()
        assertEquals("0.2.3", stringField("versionName"))
        assertEquals(1000008, intField("versionCode"))
        assertEquals(
            "0.2.3",
            BuildConfig.VERSION_NAME.removeSuffix("-dev"),
            "debug builds may carry the configured -dev suffix",
        )
        assertEquals(1000008, BuildConfig.VERSION_CODE)
    }

    @Test
    fun `0_2_3 carries out the minSdk rise that 0_2_2 warned about`() {
        // 0.2.2 shipped MIN_SDK_NEXT_RELEASE = 28 to tell Android 8.0/8.1 that
        // its update path ended. This is the release that acts on it: v3
        // signing, and with it the certificate lineage a key rotation needs,
        // does not exist before API 28.
        assertEquals(28, intField("minSdk"))
        assertEquals(28, BuildConfig.MIN_SDK_NEXT_RELEASE)
    }

    @Test
    fun `the end-of-support warning never claims a device is being dropped when it is not`() {
        // The invariant is "raise it one release BEFORE the minSdk rise", not
        // "never equal". Equal means nothing is being dropped next, which is
        // exactly the situation now, and every device that can run this build
        // must be told it still receives updates.
        assertTrue(
            BuildConfig.MIN_SDK_NEXT_RELEASE >= intField("minSdk"),
            "the next release cannot require less than this one",
        )
    }

    @Test
    fun `this release announces itself to upgrading users`() {
        requireProductionReleaseMetadata()
        val item = WhatsNewItems.registry.singleOrNull { it.sinceVersionCode == BuildConfig.VERSION_CODE }
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
            codes.all { it <= BuildConfig.VERSION_CODE },
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
