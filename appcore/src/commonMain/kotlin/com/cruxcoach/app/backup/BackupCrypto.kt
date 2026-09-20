package com.cruxcoach.app.backup

import com.cruxcoach.app.platform.AeadCipher
import com.cruxcoach.app.platform.Hashing

/**
 * AES-256-GCM for the backup blob, in Android's exact wire layout:
 * `IV(12) || ciphertext || tag(16)`, 128-bit tag, no AAD.
 *
 * The IV is fresh random on every encrypt and must never be reused with the
 * same key. The 12-byte prefix is the only framing — everything after it is
 * what the platform AEAD returns, which is `ciphertext || tag` on both
 * CryptoKit and javax.crypto.
 */
object BackupCrypto {
    const val IV_LENGTH = 12
    const val TAG_LENGTH = 16
    const val KEY_LENGTH = 32

    /** Null on a wrong key size or a platform cipher failure. */
    fun encrypt(aead: AeadCipher, hashing: Hashing, plaintext: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != KEY_LENGTH) return null
        val iv = hashing.randomBytes(IV_LENGTH)
        if (iv.size != IV_LENGTH) return null
        val sealed = aead.aesGcmSeal(key, iv, plaintext) ?: return null
        return iv + sealed
    }

    /** Null on a wrong key, a truncated blob, or a failed authentication tag. */
    fun decrypt(aead: AeadCipher, ivAndCiphertext: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != KEY_LENGTH) return null
        if (ivAndCiphertext.size <= IV_LENGTH + TAG_LENGTH) return null
        val iv = ivAndCiphertext.copyOfRange(0, IV_LENGTH)
        val body = ivAndCiphertext.copyOfRange(IV_LENGTH, ivAndCiphertext.size)
        return aead.aesGcmOpen(key, iv, body)
    }

    /** Fresh 32-byte data key. Null when the platform RNG misbehaves. */
    fun generateKey(hashing: Hashing): ByteArray? =
        hashing.randomBytes(KEY_LENGTH).takeIf { it.size == KEY_LENGTH && it.any { b -> b != 0.toByte() } }
}
