package com.cruxcoach.android.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaThumbnailUrlsTest {
    @Test fun knownHashGetsIndependentExternalMirrors() {
        val hash = "a".repeat(64)
        val urls = betaThumbnailUrls("https://nostr.download/$hash")
        assertEquals(3, urls.size)
        assertEquals("https://nostr.download/$hash", urls.first())
        assertTrue(urls.all { it.endsWith("/$hash") && !it.contains("cruxcoach.org") })
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
