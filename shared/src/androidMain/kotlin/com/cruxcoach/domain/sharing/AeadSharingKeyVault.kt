package com.cruxcoach.domain.sharing

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM data keys, wrapped under a per-handle wrapping key.
 *
 * Same primitive and layout as the existing backup crypto
 * (`[IV:12] || ciphertext || tag:16`), so there is one AEAD shape in the app
 * rather than two. Two things are deliberate:
 *
 *  - every data key is freshly random, per category and per object, so
 *    destroying one never affects another;
 *  - the [KeyHandle] is fed in as associated data on both the wrap and the
 *    seal, so a ciphertext cannot be replayed into a different category or
 *    object even by someone who holds the right key.
 *
 * A crypto erase destroys the wrapping key first ([destroy]); every wrapped
 * data key under it becomes unopenable at that instant, with no need to reach
 * the bytes on disk.
 */
class AeadSharingKeyVault(
    private val store: WrappingKeyStore,
    private val random: SecureRandom = SecureRandom(),
) : SharingKeyVault {

    override fun createDataKey(handle: KeyHandle): WrappedKey {
        val dataKey = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        try {
            val wrappingKey = store.getOrCreate(handle)
            return WrappedKey(handle, encrypt(wrappingKey, dataKey, handle.aad()))
        } finally {
            dataKey.fill(0)
        }
    }

    override fun seal(key: WrappedKey, plaintext: ByteArray, aad: ByteArray): SealedPayload {
        val dataKey = unwrap(key)
        try {
            return SealedPayload(encrypt(dataKey, plaintext, aad))
        } finally {
            dataKey.fill(0)
        }
    }

    override fun open(key: WrappedKey, sealed: SealedPayload, aad: ByteArray): ByteArray {
        val dataKey = unwrap(key)
        try {
            return decrypt(dataKey, sealed.bytes, aad)
        } finally {
            dataKey.fill(0)
        }
    }

    override fun destroy(handle: KeyHandle) = store.destroy(handle)

    @Suppress("TooGenericExceptionCaught")
    override fun exportForBackup(key: WrappedKey): ByteArray? = try {
        unwrap(key)
    } catch (e: SharingCryptoException) {
        null
    }

    override fun importFromBackup(handle: KeyHandle, rawDataKey: ByteArray): WrappedKey {
        if (rawDataKey.size != KEY_BYTES) {
            rawDataKey.fill(0)
            throw SharingCryptoException("a data key must be $KEY_BYTES bytes, got ${rawDataKey.size}")
        }
        try {
            // getOrCreate, so a restore onto a device that already has a
            // wrapping key for this handle reuses it rather than orphaning the
            // keys already wrapped under it.
            val wrappingKey = store.getOrCreate(handle)
            return WrappedKey(handle, encrypt(wrappingKey, rawDataKey, handle.aad()))
        } finally {
            rawDataKey.fill(0)
        }
    }

    private fun unwrap(key: WrappedKey): ByteArray {
        val wrappingKey = store.get(key.handle)
            ?: throw SharingCryptoException("no wrapping key for ${key.handle} — destroyed or never created")
        return decrypt(wrappingKey, key.wrappedBytes, key.handle.aad())
    }

    private fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            return iv + cipher.doFinal(plaintext)
        } catch (e: GeneralSecurityException) {
            throw SharingCryptoException("sealing failed", e)
        }
    }

    private fun decrypt(key: ByteArray, ivAndCiphertext: ByteArray, aad: ByteArray): ByteArray {
        if (ivAndCiphertext.size <= IV_BYTES) {
            throw SharingCryptoException("ciphertext too short to contain an IV and a tag")
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, ivAndCiphertext, 0, IV_BYTES),
            )
            cipher.updateAAD(aad)
            return cipher.doFinal(ivAndCiphertext, IV_BYTES, ivAndCiphertext.size - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            // Deliberately vague: never echo key, handle material or plaintext.
            throw SharingCryptoException("payload could not be authenticated", e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * A wrapping-key store held only in memory.
 *
 * This is the store the unit tests and the debug preview use. It is **not** a
 * production key store: the keys live in the process heap and are gone on
 * restart. The Android-Keystore-backed store used by the app is
 * `com.cruxcoach.android.sharing.KeystoreWrappingKeyStore`.
 */
class InMemoryWrappingKeyStore(
    private val random: SecureRandom = SecureRandom(),
) : WrappingKeyStore {

    private val keys = mutableMapOf<KeyHandle, ByteArray>()

    override fun getOrCreate(handle: KeyHandle): ByteArray =
        keys.getOrPut(handle) { ByteArray(32).also { random.nextBytes(it) } }

    override fun get(handle: KeyHandle): ByteArray? = keys[handle]

    override fun destroy(handle: KeyHandle) {
        keys.remove(handle)?.fill(0)
    }
}
