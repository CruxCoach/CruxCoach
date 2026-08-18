package com.cruxcoach.android.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A GitHub forge source has to work without an app update.
 *
 * The point of FEAT-050 is that release hosts are runtime data: once Codeberg
 * is retired, existing installs learn about the replacement from
 * update-sources.json and keep updating. That only holds if a GitHub entry
 * actually resolves — and the shapes differ in three small places that would
 * each fail quietly rather than loudly.
 *
 * Checked against GitHub's real conventions on 2026-08-07:
 *   api root   https://api.github.com
 *   web host   https://github.com
 *   list       {root}/repos/{owner}/{repo}/releases
 *   asset      {web}/{owner}/{repo}/releases/download/{tag}/{file}
 */
class ForgeSourceGitHubCompatTest {

    private val github = UpdateSource(
        id = "github",
        kind = UpdateSource.Kind.FORGE,
        url = "https://api.github.com",
        owner = "CruxCoach",
        repo = "CruxCoach",
    )

    private val codeberg = UpdateSource(
        id = "forge",
        kind = UpdateSource.Kind.FORGE,
        url = "https://codeberg.org/api/v1",
        owner = "CruxCoach",
        repo = "CruxCoach",
    )

    @Test
    fun `the api host maps to the web host for downloads`() {
        // api.github.com serves no release assets; github.com does. Getting
        // this wrong yields a 404 per download attempt, which the updater
        // reports as "source had no usable URL" — true, and useless.
        assertEquals("https://github.com", github.webHost())
        assertEquals("https://codeberg.org", codeberg.webHost())
    }

    @Test
    fun `both forges produce the same asset URL shape`() {
        val sha = "a".repeat(64)
        assertEquals(
            "https://github.com/CruxCoach/CruxCoach/releases/download/v0.2.2/CruxCoach-v0.2.2.apk",
            github.downloadUrlFor("v0.2.2", sha),
        )
        assertEquals(
            "https://codeberg.org/CruxCoach/CruxCoach/releases/download/v0.2.2/CruxCoach-v0.2.2.apk",
            codeberg.downloadUrlFor("v0.2.2", sha),
        )
    }

    @Test
    fun `a github source passes the usability and transport rules`() {
        // A source the registry filters out is indistinguishable from one that
        // was never in the manifest — so the entry we intend to publish has to
        // survive those filters.
        assertTrue(github.isUsable())
        assertTrue(github.isTransportAcceptable())
        assertTrue(github.supportsDiscovery)
    }

    @Test
    fun `a download from either forge is attributed to the right source`() {
        val sources = listOf(codeberg, github)
        assertEquals(
            "github",
            resolveSourceId(
                "https://github.com/CruxCoach/CruxCoach/releases/download/v0.2.2/CruxCoach-v0.2.2.apk",
                sources,
            ),
        )
        assertEquals(
            "forge",
            resolveSourceId(
                "https://codeberg.org/CruxCoach/CruxCoach/releases/download/v0.2.2/CruxCoach-v0.2.2.apk",
                sources,
            ),
        )
    }

    @Test
    fun `both forges appear as separate download URLs for one release`() {
        // The sweep tries every source's URL in turn, so a release discovered
        // on one forge is still downloadable from the other. That redundancy
        // is the whole reason to list both while the migration is in flight.
        val urls = buildDownloadUrls(
            tagName = "v0.2.2",
            apkSha256 = "b".repeat(64),
            primaryUrl = null,
            sources = listOf(codeberg, github),
        )
        assertTrue("codeberg URL missing: $urls", urls.any { it.startsWith("https://codeberg.org/") })
        assertTrue("github URL missing: $urls", urls.any { it.startsWith("https://github.com/") })
    }
}
