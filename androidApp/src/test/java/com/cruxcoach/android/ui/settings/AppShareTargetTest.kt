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
    fun `share link downloads directly rather than landing on a page`() {
        // Scanning a share QR already IS the decision to install. Landing on
        // the install section and having to find the download button again is
        // a step that buys nothing.
        assertTrue("share link must be a download route: $url", url.contains("/download/apk/"))
        assertFalse("share link must not carry a page anchor: $url", url.contains("#"))
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
