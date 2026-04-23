package com.cruxcoach.android.nostr.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for backup blob encryption.
 *
 * Output layout: `[IV:12 bytes] || [ciphertext] || [auth tag:16 bytes]` —
 * the GCM auth tag is appended to the ciphertext by `Cipher.doFinal`, so the
 * caller only needs to pay attention to the IV prefix.
 *
 * IV is drawn from [SecureRandom] on every encrypt and must never be reused
 * with the same key. Reuse would leak plaintext XORs — a well-known GCM
 * failure mode. A 96-bit random IV gives a ~2^-32 collision chance after
 * 2^32 encryptions per key, which is far beyond what one user will ever
 * generate. DataKey rotation is deliberately out of scope for v0.1.3
 * (FEAT-002 §16); it can be added later without ciphertext-format changes.
 *
 * No new dependencies — uses `javax.crypto.Cipher`, hardware-accelerated on
 * ARM devices via the AES extensions.
 */
internal object BackupCrypto {

    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /**
     * Encrypts [plaintext] with the 32-byte [key]. Returns the IV-prefixed
     * ciphertext ready to upload. Throws [IllegalArgumentException] if the
     * key size is wrong.
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "BackupCrypto requires a 32-byte key, got ${key.size}" }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv),
        )
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Decrypts IV-prefixed ciphertext produced by [encrypt] with the same
     * 32-byte [key]. Throws `javax.crypto.AEADBadTagException` on tamper or
     * wrong key — callers translate that to a user-visible "backup cannot be
     * read" error.
     */
    fun decrypt(ivAndCiphertext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "BackupCrypto requires a 32-byte key, got ${key.size}" }
        require(ivAndCiphertext.size > IV_LEN) {
            "Ciphertext too short: need more than $IV_LEN bytes, got ${ivAndCiphertext.size}"
        }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, ivAndCiphertext, 0, IV_LEN),
        )
        return cipher.doFinal(ivAndCiphertext, IV_LEN, ivAndCiphertext.size - IV_LEN)
    }

    /** Generates a fresh 32-byte random key via [SecureRandom]. */
    fun generateKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
}
