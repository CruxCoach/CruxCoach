package com.cruxcoach.android.nostr.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * RFC 5869 HKDF-SHA256 test vectors.
 *
 * Source: <https://datatracker.ietf.org/doc/html/rfc5869#appendix-A>. We keep
 * three of the Appendix-A vectors — the ones whose hash is SHA-256. The
 * RFC includes SHA-1 vectors too; those are not relevant here.
 */
class HkdfSha256Test {

    @Test
    fun `RFC 5869 test case 1 - basic SHA-256 vector`() {
        val ikm = "0b".repeat(22).hexToBytes()
        val salt = "000102030405060708090a0b0c".hexToBytes()
        val info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes()
        val okm = HkdfSha256.derive(ikm, salt, info, outputLen = 42)

        val expected = (
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
            ).hexToBytes()
        assertEqualsBytes(expected, okm)
    }

    @Test
    fun `RFC 5869 test case 2 - longer inputs and outputs SHA-256`() {
        val ikm = (
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f" +
                "303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f"
            ).hexToBytes()
        val salt = (
            "606162636465666768696a6b6c6d6e6f" +
                "707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
            ).hexToBytes()
        val info = (
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
            ).hexToBytes()
        val okm = HkdfSha256.derive(ikm, salt, info, outputLen = 82)

        val expected = (
            "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f" +
                "1d87"
            ).hexToBytes()
        assertEqualsBytes(expected, okm)
    }

    @Test
    fun `RFC 5869 test case 3 - zero-length salt and info SHA-256`() {
        val ikm = "0b".repeat(22).hexToBytes()
        val okm = HkdfSha256.derive(
            ikm = ikm,
            salt = null,
            info = ByteArray(0),
            outputLen = 42,
        )
        val expected = (
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8"
            ).hexToBytes()
        assertEqualsBytes(expected, okm)
    }

    @Test
    fun `output length respected for odd sizes`() {
        val ikm = ByteArray(32) { 0x01 }
        val okm = HkdfSha256.derive(ikm, outputLen = 17)
        assertEquals(17, okm.size)
    }

    @Test
    fun `domain separation via info field produces different output`() {
        val ikm = ByteArray(32) { 0x42 }
        val salt = "cruxcoach-dtag-v1".toByteArray()
        val a = HkdfSha256.derive(ikm, salt, "hmac-key".toByteArray(), outputLen = 32)
        val b = HkdfSha256.derive(ikm, salt, "other-purpose".toByteArray(), outputLen = 32)
        assertNotEquals(a.toHex(), b.toHex())
    }

    @Test
    fun `throws on zero output length`() {
        try {
            HkdfSha256.derive(ByteArray(32), outputLen = 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `throws on output exceeding 255 HLEN`() {
        try {
            HkdfSha256.derive(ByteArray(32), outputLen = 255 * 32 + 1)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    // --- helpers ---

    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun assertEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toHex(), actual.toHex())
    }
}
