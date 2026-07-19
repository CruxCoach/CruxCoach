package com.cruxcoach.android.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZapstoreReleaseClientTest {
    private val sha = "a".repeat(64)
    private val cert = "b".repeat(64)

    @Test
    fun `parser accepts matching signed-event payload and joins release notes`() {
        val asset = assetEvent()
        val notes = releaseEvent(assetId = asset.id)

        val releases = ZapstoreEventParser.parse(
            events = listOf(notes, asset),
            packageId = PACKAGE_ID,
            pinnedCertSha256 = cert,
            cdnBaseUrl = CDN_BASE,
        )

        assertEquals(1, releases.size)
        assertEquals("0.2.2", releases.single().versionName)
        assertEquals(8, releases.single().versionCode)
        assertEquals("$CDN_BASE/$sha", releases.single().apkUrl)
        assertEquals("Release notes", releases.single().releaseNotesMarkdown)
    }

    @Test
    fun `parser rejects asset whose certificate does not match installed app`() {
        val releases = ZapstoreEventParser.parse(
            events = listOf(assetEvent(certSha256 = "c".repeat(64))),
            packageId = PACKAGE_ID,
            pinnedCertSha256 = cert,
            cdnBaseUrl = CDN_BASE,
        )

        assertTrue(releases.isEmpty())
    }

    @Test
    fun `parser rejects non-content-addressed CDN URL`() {
        val releases = ZapstoreEventParser.parse(
            events = listOf(assetEvent(url = "$CDN_BASE/latest.apk")),
            packageId = PACKAGE_ID,
            pinnedCertSha256 = cert,
            cdnBaseUrl = CDN_BASE,
        )

        assertTrue(releases.isEmpty())
    }

    @Test
    fun `parser rejects event for another package`() {
        val releases = ZapstoreEventParser.parse(
            events = listOf(assetEvent(packageId = "attacker.example")),
            packageId = PACKAGE_ID,
            pinnedCertSha256 = cert,
            cdnBaseUrl = CDN_BASE,
        )

        assertTrue(releases.isEmpty())
    }

    private fun assetEvent(
        packageId: String = PACKAGE_ID,
        certSha256: String = cert,
        url: String = "$CDN_BASE/$sha",
    ) = VerifiedZapstoreEvent(
        id = "asset-event",
        kind = 3063,
        pubkey = "d".repeat(64),
        createdAt = 200,
        tags = listOf(
            listOf("i", packageId),
            listOf("x", sha),
            listOf("version", "0.2.2"),
            listOf("url", url),
            listOf("m", "application/vnd.android.package-archive"),
            listOf("size", "34558390"),
            listOf("f", "android-arm64-v8a"),
            listOf("version_code", "8"),
            listOf("apk_certificate_hash", certSha256),
        ),
        content = "",
    )

    private fun releaseEvent(assetId: String) = VerifiedZapstoreEvent(
        id = "release-event",
        kind = 30063,
        pubkey = "d".repeat(64),
        createdAt = 201,
        tags = listOf(
            listOf("i", PACKAGE_ID),
            listOf("version", "0.2.2"),
            listOf("e", assetId, "wss://relay.zapstore.dev"),
        ),
        content = "Release notes",
    )

    companion object {
        private const val PACKAGE_ID = "com.cruxcoach.android"
        private const val CDN_BASE = "https://cdn.zapstore.dev"
    }
}
