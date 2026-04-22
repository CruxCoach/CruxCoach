package com.cruxcoach.android.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCheckerTest {

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
    ): CodebergRelease = CodebergRelease(
        id = 1,
        tagName = tag,
        prerelease = prerelease,
        draft = draft,
        htmlUrl = "https://codeberg.org/CruxCoach/CruxCoach/releases/tag/$tag",
    )

    @Test
    fun `stable strict tag passes isStableRelease`() {
        assertTrue(VersionChecker.isStableRelease(release("v0.1.2")))
    }

    @Test
    fun `prerelease flag rejects even if tag looks strict`() {
        assertFalse(VersionChecker.isStableRelease(release("v0.1.2", prerelease = true)))
    }

    @Test
    fun `draft flag rejects even if tag looks strict`() {
        assertFalse(VersionChecker.isStableRelease(release("v0.1.2", draft = true)))
    }

    @Test
    fun `strict-shape filter rejects rc tag marked stable`() {
        // Belt-and-braces against human error: publisher forgot to tick
        // "pre-release" on a -rc tag. The tag-shape filter catches it.
        assertFalse(VersionChecker.isStableRelease(release("v0.1.2-rc.1")))
    }

    @Test
    fun `strict-shape filter rejects dev tag marked stable`() {
        assertFalse(VersionChecker.isStableRelease(release("v0.1.2-dev.abc1234")))
    }

    @Test
    fun `pickNewerStable returns null when installed is current`() {
        val result = VersionChecker.pickNewerStable(
            candidates = listOf(release("v0.1.2"), release("v0.1.1")),
            installed = SemVer(0, 1, 2),
        )
        assertNull(result)
    }

    @Test
    fun `pickNewerStable picks the highest stable newer than installed`() {
        val result = VersionChecker.pickNewerStable(
            candidates = listOf(
                release("v0.1.2"),
                release("v0.2.0"),
                release("v0.1.5"),
            ),
            installed = SemVer(0, 1, 2),
        )
        assertEquals("v0.2.0", result?.tagName)
    }

    @Test
    fun `pickNewerStable tolerates out-of-order publishing`() {
        // Codeberg normally returns newest-first, but a manual backport
        // could land a low tag at the top of the list.
        val result = VersionChecker.pickNewerStable(
            candidates = listOf(
                release("v0.1.2"),
                release("v0.3.0"),
                release("v0.1.5"),
            ),
            installed = SemVer(0, 1, 0),
        )
        assertEquals("v0.3.0", result?.tagName)
    }

    @Test
    fun `pickNewerStable ignores prerelease even if newer`() {
        val result = VersionChecker.pickNewerStable(
            candidates = listOf(
                release("v0.5.0", prerelease = true),
                release("v0.1.3"),
            ),
            installed = SemVer(0, 1, 2),
        )
        assertEquals("v0.1.3", result?.tagName)
    }

    @Test
    fun `pickNewerStable returns null when only prereleases exist`() {
        val result = VersionChecker.pickNewerStable(
            candidates = listOf(
                release("v0.5.0", prerelease = true),
                release("v0.6.0-rc.1"),
            ),
            installed = SemVer(0, 1, 2),
        )
        assertNull(result)
    }

    @Test
    fun `empty candidate list yields null`() {
        assertNull(
            VersionChecker.pickNewerStable(
                candidates = emptyList(),
                installed = SemVer(0, 1, 2),
            ),
        )
    }
}
