package com.cruxcoach.android.sharing

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Invitation links: the code only in the fragment, only the own host, only well-formed codes. */
class ShareLinksTest {
    private val host = "cruxcoach.org"
    private val peer = "ab".repeat(32)
    private val until = 1_793_600_000L

    private fun code(magic: String = "ccl1", size: Int = 72): String {
        val bytes = ByteArray(size)
        magic.toByteArray().copyInto(bytes)
        if (size >= 72) {
            ByteArray(32) { 0xab.toByte() }.copyInto(bytes, 4)
            ByteArray(32) { 7 }.copyInto(bytes, 36)
            byteArrayOf((until shr 24).toByte(), (until shr 16).toByte(), (until shr 8).toByte(), until.toByte()).copyInto(bytes, 68)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    @Test fun a_link_carries_the_code_only_in_the_fragment_and_comes_back_unchanged() {
        val payload = ShareLinks.LINK_PREFIX + code()
        val url = ShareLinks.url(payload, host)
        assertEquals("https://cruxcoach.org/f/#${code()}", url)
        assertEquals(payload, ShareLinks.payload(url, host))
        assertEquals(LinkIssuer(peer, until * 1000), ShareLinks.issuer(payload))
    }

    @Test fun a_scanned_contact_code_names_its_issuer_for_an_hour_and_a_link_as_qr_counts_too() {
        val bytes = ByteArray(68).also { "ccc1".toByteArray().copyInto(it); ByteArray(32) { 0xab.toByte() }.copyInto(it, 4) }
        val contact = ShareLinks.CONTACT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertEquals(LinkIssuer(peer, 1_000 + 3_600_000), ShareLinks.issuer(contact, now = 1_000))
        assertEquals(contact, ShareLinks.scanned(" $contact\n", host))
        val link = "https://cruxcoach.org/f/#${code()}"
        assertEquals(ShareLinks.LINK_PREFIX + code(), ShareLinks.scanned(link, host))
        for (text in listOf("", "hello", "cruxcoach:c1:", ShareLinks.CONTACT_PREFIX + "A".repeat(91), "WIFI:S:x;;", "x".repeat(600))) {
            assertNull(ShareLinks.scanned(text, host), text)
        }
    }

    @Test fun anything_else_is_no_invitation() {
        val good = code()
        val refused = listOf(
            "http://cruxcoach.org/f/#$good", // not https
            "https://example.org/f/#$good", // another host
            "https://cruxcoach.org.example.org/f/#$good",
            "https://cruxcoach.org:8443/f/#$good",
            "https://user@cruxcoach.org/f/#$good",
            "https://cruxcoach.org/f/$good", // in the path: a web server would see it
            "https://cruxcoach.org/f/?c=$good#$good", // in the query
            "https://cruxcoach.org/l/#$good", // another path
            "https://cruxcoach.org/f/#${good.dropLast(1)}", // too short
            "https://cruxcoach.org/f/#$good=",
            "https://cruxcoach.org/f/#${code(magic = "ccc1")}", // a contact code is no link
            "https://cruxcoach.org/f/#${good.replaceRange(10, 11, "+")}",
            "https://cruxcoach.org/f/",
            "not a link",
        )
        for (text in refused) assertNull(ShareLinks.payload(text, host), text)
        assertNull(ShareLinks.issuer("cruxcoach:c1:$good"))
        assertNull(ShareLinks.issuer(ShareLinks.LINK_PREFIX + "x".repeat(96)))
    }
}
