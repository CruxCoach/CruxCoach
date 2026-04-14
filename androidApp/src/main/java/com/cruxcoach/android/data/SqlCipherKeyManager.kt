package com.cruxcoach.android.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SqlCipherKeyManager(private val prefs: SharedPreferences) {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) createMasterKey()
        if (!prefs.contains(PREF_ENCRYPTED_KEY)) generateAndStoreDbKey()
    }

    private fun createMasterKey() {
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    private fun generateAndStoreDbKey() {
        val dbKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val encrypted = cipher.doFinal(dbKey)
        dbKey.fill(0) // Wipe plaintext from memory

        prefs.edit()
            .putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun getDecryptedDbKey(): ByteArray {
        val encStr = prefs.getString(PREF_ENCRYPTED_KEY, null)
        val ivStr = prefs.getString(PREF_IV, null)
        if (encStr == null || ivStr == null) {
            generateAndStoreDbKey()
            return getDecryptedDbKey()
        }
        val encrypted = Base64.decode(encStr, Base64.NO_WRAP)
        val iv = Base64.decode(ivStr, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encrypted)
    }

    /**
     * Derives a per-pubkey encryption key using HKDF-SHA256.
     * Each Nostr key gets its own SecureDB with a unique encryption key,
     * all derived from the single Android Keystore-backed master DB key.
     */
    fun getDerivedKeyForPubkey(pubkeyHex: String): ByteArray {
        val masterKey = getDecryptedDbKey()
        try {
            return hkdfSha256(masterKey, pubkeyHex.toByteArray(), INFO_SECURE_DB, 32)
        } finally {
            masterKey.fill(0)
        }
    }

    private fun getMasterKey() = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey

    companion object {
        private const val KEYSTORE_ALIAS = "cruxcoach_db_master"
        private const val PREF_ENCRYPTED_KEY = "enc_db_key"
        private const val PREF_IV = "enc_db_key_iv"
        private const val GCM_TAG_LENGTH = 128
        private val INFO_SECURE_DB = "cruxcoach-secure-db".toByteArray()

        init { System.loadLibrary("sqlcipher") }

        /** HKDF-SHA256 extract-then-expand (RFC 5869). */
        private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            // Extract
            val prk = hmacSha256(salt, ikm)
            // Expand
            val n = (length + 31) / 32
            val okm = ByteArray(length)
            var t = ByteArray(0)
            var offset = 0
            for (i in 1..n) {
                t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
                val copyLen = minOf(32, length - offset)
                System.arraycopy(t, 0, okm, offset, copyLen)
                offset += copyLen
            }
            prk.fill(0)
            return okm
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
    }
}
