package com.cruxcoach.android.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaThumbnailUrlsTest {
    @Test fun knownHashGetsOwnedAndExternalMirrors() {
        val hash = "a".repeat(64)
        val urls = betaThumbnailUrls("https://nostr.download/$hash")
        assertEquals(4, urls.size)
        assertEquals("https://nostr.download/$hash", urls.first())
        assertTrue(urls.all { it.endsWith("/$hash") })
    }
    @Test fun ownedMirrorIsAcceptedAndFallsBackToExternalMirrors() {
        val hash = "b".repeat(64)
        val original = "https://blossom.cruxcoach.org/$hash"
        val urls = betaThumbnailUrls(original)
        assertEquals(listOf(
            original,
            "https://nostr.download/$hash",
            "https://blossom.primal.net/$hash",
            "https://cdn.hzrd149.com/$hash",
        ), urls)
        urls.forEach { assertEquals(hash, betaThumbnailHash(it)) }
        assertTrue(betaThumbnailUrls("https://nostr.download/$hash").contains(original))
    }

    @Test fun ownedMirrorStillRequiresExactHostAndUnadornedHttpsHashUrl() {
        val hash = "b".repeat(64)
        listOf(
            "http://blossom.cruxcoach.org/$hash",
            "https://blossom.cruxcoach.org.attacker.example/$hash",
            "https://attacker.blossom.cruxcoach.org/$hash",
            "https://user:secret@blossom.cruxcoach.org/$hash",
            "https://blossom.cruxcoach.org:8443/$hash",
            "https://blossom.cruxcoach.org/$hash?x=1",
            "https://blossom.cruxcoach.org/$hash#fragment",
            "https://blossom.cruxcoach.org/preview.jpg",
        ).forEach { assertEquals(it, null, betaThumbnailHash(it)) }
        assertEquals(hash, betaThumbnailHash("https://blossom.cruxcoach.org:443/$hash"))
    }

    @Test fun providerImagesAreNotRewrittenAndCredentialsAreRejected() {
        val original = "https://image.example/preview.jpg"
        assertEquals(listOf(original), betaThumbnailUrls(original))
        assertTrue(betaThumbnailUrls("https://user:secret@nostr.download/" + "a".repeat(64)).isEmpty())
        assertTrue(betaThumbnailUrls("file:///image.jpg").isEmpty())
        val signed = "https://nostr.download/" + "a".repeat(64) + "?signature=example"
        assertEquals(listOf(signed), betaThumbnailUrls(signed))
    }
    @Test fun imageBytesMustMatchSignedHashBeforeCachingOrDisplay() {
        val bytes = "known bytes".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertTrue(validBetaThumbnailBytes(bytes, hash))
        org.junit.Assert.assertFalse(validBetaThumbnailBytes("tampered".toByteArray(), hash))
        org.junit.Assert.assertFalse(validBetaThumbnailBytes(ByteArray(512 * 1024 + 1), hash))
        assertEquals(hash, betaThumbnailHash("https://nostr.download/$hash"))
        assertEquals(null, betaThumbnailHash("https://attacker.example/$hash"))
        assertEquals(null, betaThumbnailHash("https://nostr.download/$hash?x=1"))
    }
}
