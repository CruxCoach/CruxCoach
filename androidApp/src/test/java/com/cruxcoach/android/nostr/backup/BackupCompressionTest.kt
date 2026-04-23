package com.cruxcoach.android.nostr.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCompressionTest {

    @Test
    fun `round-trip short string`() {
        val bytes = "cruxcoach".toByteArray()
        val compressed = BackupCompression.compress(bytes)
        val decompressed = BackupCompression.decompress(compressed)
        assertArrayEquals(bytes, decompressed)
    }

    @Test
    fun `round-trip JSON-like payload`() {
        val json = """{"foo":"bar","items":[1,2,3,4,5],"text":"${"a".repeat(1000)}"}"""
        val compressed = BackupCompression.compress(json.toByteArray())
        val decompressed = BackupCompression.decompress(compressed)
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
        val decompressed = BackupCompression.decompress(compressed)
        assertArrayEquals(payload, decompressed)
    }

    @Test
    fun `decompress rejects non-gzip bytes`() {
        try {
            BackupCompression.decompress("not gzip data".toByteArray())
            throw AssertionError("expected IOException / ZipException")
        } catch (_: Exception) {
            // java.util.zip.ZipException / IOException — either is fine
        }
    }

    @Test
    fun `empty input round-trips`() {
        val compressed = BackupCompression.compress(ByteArray(0))
        val decompressed = BackupCompression.decompress(compressed)
        assertTrue(decompressed.isEmpty())
    }
}
