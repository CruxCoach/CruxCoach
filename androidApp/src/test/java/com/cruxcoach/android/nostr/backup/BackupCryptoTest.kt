package com.cruxcoach.android.nostr.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class BackupCryptoTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `round-trip with ASCII payload recovers original bytes`() {
        val plaintext = "Hello CruxCoach backup".toByteArray()
        val ct = BackupCrypto.encrypt(plaintext, key)
        val pt = BackupCrypto.decrypt(ct, key)
        assertArrayEquals(plaintext, pt)
    }

    @Test
    fun `round-trip with 1 MB random payload`() {
        val random = java.security.SecureRandom()
        val payload = ByteArray(1_000_000).also { random.nextBytes(it) }
        val ct = BackupCrypto.encrypt(payload, key)
        assertTrue("ciphertext must contain IV + tag", ct.size > payload.size)
        val pt = BackupCrypto.decrypt(ct, key)
        assertArrayEquals(payload, pt)
    }

    @Test
    fun `each encrypt draws a fresh IV`() {
        val pt = "same plaintext".toByteArray()
        val ct1 = BackupCrypto.encrypt(pt, key)
        val ct2 = BackupCrypto.encrypt(pt, key)
        // IV is the first 12 bytes — must differ across calls
        val iv1 = ct1.copyOfRange(0, 12)
        val iv2 = ct2.copyOfRange(0, 12)
        assertNotEquals(iv1.toList(), iv2.toList())
    }

    @Test
    fun `tampered ciphertext byte fails auth`() {
        val ct = BackupCrypto.encrypt("payload".toByteArray(), key)
        ct[ct.size - 5] = (ct[ct.size - 5].toInt() xor 0x01).toByte()
        try {
            BackupCrypto.decrypt(ct, key)
            throw AssertionError("expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
            // ok
        }
    }

    @Test
    fun `tampered IV fails auth`() {
        val ct = BackupCrypto.encrypt("payload".toByteArray(), key)
        ct[2] = (ct[2].toInt() xor 0x01).toByte()
        try {
            BackupCrypto.decrypt(ct, key)
            throw AssertionError("expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
            // ok
        }
    }

    @Test
    fun `wrong key fails auth`() {
        val ct = BackupCrypto.encrypt("payload".toByteArray(), key)
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        try {
            BackupCrypto.decrypt(ct, wrongKey)
            throw AssertionError("expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
            // ok
        }
    }

    @Test
    fun `wrong key size rejected at encrypt`() {
        try {
            BackupCrypto.encrypt("x".toByteArray(), ByteArray(16))
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `empty plaintext round-trips`() {
        val ct = BackupCrypto.encrypt(ByteArray(0), key)
        val pt = BackupCrypto.decrypt(ct, key)
        assertTrue(pt.isEmpty())
    }

    @Test
    fun `ciphertext shorter than IV rejected`() {
        try {
            BackupCrypto.decrypt(ByteArray(8), key)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `generateKey produces 32 bytes with non-zero content`() {
        val k = BackupCrypto.generateKey()
        assertTrue(k.size == 32)
        assertTrue(k.any { it != 0.toByte() })
    }
}
