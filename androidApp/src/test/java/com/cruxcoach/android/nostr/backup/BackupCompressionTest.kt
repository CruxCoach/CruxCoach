package com.cruxcoach.android.nostr.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCompressionTest {

    private val generousMax = 16 * 1024 * 1024 // 16 MB — covers every test payload

    @Test
    fun `round-trip short string`() {
        val bytes = "cruxcoach".toByteArray()
        val compressed = BackupCompression.compress(bytes)
        val decompressed = BackupCompression.decompress(compressed, generousMax)
        assertArrayEquals(bytes, decompressed)
    }

    @Test
    fun `round-trip JSON-like payload`() {
        val json = """{"foo":"bar","items":[1,2,3,4,5],"text":"${"a".repeat(1000)}"}"""
        val compressed = BackupCompression.compress(json.toByteArray())
        val decompressed = BackupCompression.decompress(compressed, generousMax)
        assertArrayEquals(json.toByteArray(), decompressed)
    }

    @Test
    fun `compression shrinks repetitive payloads significantly`() {
        // 10 000 identical characters — should compress to very few bytes.
        val payload = "a".repeat(10_000).toByteArray()
        val compressed = BackupCompression.compress(payload)
        assertTrue(
            "expected at least 10x ratio on fully-repetitive data, got ${payload.size} → ${compressed.size}",
            compressed.size < payload.size / 10,
        )
    }

    @Test
    fun `compression round-trip on binary payload`() {
        val random = java.security.SecureRandom()
        val payload = ByteArray(5_000).also { random.nextBytes(it) }
        val compressed = BackupCompression.compress(payload)
        val decompressed = BackupCompression.decompress(compressed, generousMax)
        assertArrayEquals(payload, decompressed)
    }

    @Test
    fun `decompress rejects non-gzip bytes`() {
        try {
            BackupCompression.decompress("not gzip data".toByteArray(), generousMax)
            throw AssertionError("expected IOException / ZipException")
        } catch (_: Exception) {
            // java.util.zip.ZipException / IOException — either is fine
        }
    }

    @Test
    fun `empty input round-trips`() {
        val compressed = BackupCompression.compress(ByteArray(0))
        val decompressed = BackupCompression.decompress(compressed, generousMax)
        assertTrue(decompressed.isEmpty())
    }

    @Test
    fun `decompress refuses output exceeding maxBytes (bomb guard)`() {
        // 1 MB of repetitive data compresses down to a few KB, then
        // decompresses back to 1 MB — feed it a 100-byte cap so the
        // guard has to trip mid-stream before the output fills.
        val payload = "a".repeat(1_000_000).toByteArray()
        val compressed = BackupCompression.compress(payload)
        try {
            BackupCompression.decompress(compressed, maxBytes = 100)
            throw AssertionError("expected BackupException — bomb guard did not trip")
        } catch (e: BackupException) {
            assertTrue(
                "exception message should name the cap: was '${e.message}'",
                e.message?.contains("100") == true,
            )
        }
    }

    @Test
    fun `decompress accepts output exactly at maxBytes`() {
        val payload = "a".repeat(5_000).toByteArray()
        val compressed = BackupCompression.compress(payload)
        val decompressed = BackupCompression.decompress(compressed, maxBytes = payload.size)
        assertArrayEquals(payload, decompressed)
    }
}
