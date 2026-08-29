package com.cruxcoach.android.ui.settings

import com.cruxcoach.android.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the share QR is allowed to encode.
 *
 * The QR content is a plain BuildConfig string, so nothing in the compiler
 * stops it from drifting back to a forge asset or to the release page — both
 * of which it has already been, and both of which were wrong for reasons that
 * only show up months later on somebody else's phone. This test states those
 * reasons as assertions instead.
 */
class AppShareTargetTest {

    private val url = BuildConfig.APP_SHARE_DOWNLOAD_URL

    @Test
    fun `share link resolves a release instead of naming one`() {
        // The whole point: the sharer's own version must not appear anywhere
        // in the link. A phone stuck on an old build would otherwise keep
        // handing that old build to everyone who scans it.
        assertFalse(
            "share link must not pin a version: $url",
            url.contains(BuildConfig.VERSION_NAME),
        )
        assertFalse(
            "share link must not point at a release asset: $url",
            url.endsWith(".apk"),
        )
    }

    @Test
    fun `share link names no forge`() {
        // A forge in this URL is what makes a migration break every QR code
        // ever shared. Discovery hosts belong in the runtime source list, not
        // compiled into a link that outlives the build.
        for (forge in listOf("codeberg.org", "github.com", "gitlab.com", "/releases/")) {
            assertFalse("share link must not name $forge: $url", url.contains(forge))
        }
    }

    @Test
    fun `share link resolves a download rather than landing on a section`() {
        // Scanning a share QR already IS the decision to install, so the
        // target is the resolver that starts a download — not the marketing
        // page's install section, which would make the scanner hunt for the
        // button a second time.
        assertTrue("share link must be the download resolver: $url", url.endsWith("/get.html"))
        assertFalse("share link must not carry a page anchor: $url", url.contains("#"))
    }

    @Test
    fun `share link points at the origin with the most reach, not at one host`() {
        // A QR gets no second chance: it is scanned by a different device, at
        // an unknown later time, and cannot change its mind the way the
        // website's Download button can at click time. So it names the apex —
        // a CDN — and the fallback chain (selector, then forge, then CDN blob)
        // lives in the page it opens.
        //
        // It deliberately does NOT name the selector host directly. That was
        // one host with no way back: if our own machine is down when someone
        // scans, nothing happens at all.
        assertFalse(
            "share link must not hardcode the selector host: $url",
            url.contains("stats.cruxcoach.org"),
        )
        assertFalse(
            "share link must not point at a single mirror: $url",
            url.contains("mirror.cruxcoach.org"),
        )
    }

    @Test
    fun `share link is separate from the cert-mismatch handoff`() {
        // These two look alike and must not be merged. The handoff sends a
        // user to a PAGE on purpose: a signing key changed, and a human has to
        // read that and decide. Handing them a download instead would skip the
        // one screen that explains why they are being asked to trust it.
        assertNotEquals(BuildConfig.UPDATER_RELEASE_PAGE_URL, url)
        assertTrue(BuildConfig.UPDATER_RELEASE_PAGE_URL.contains("#"))
    }

    @Test
    fun `share link is transport-secure`() {
        assertTrue("share link must be https: $url", url.startsWith("https://"))
    }
}
