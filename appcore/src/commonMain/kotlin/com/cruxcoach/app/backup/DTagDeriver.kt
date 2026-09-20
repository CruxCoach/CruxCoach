package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.Hkdf
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.util.toHex

/**
 * Deterministic, opaque `d` tags for the kind-30078 backup events, so relay
 * operators cannot enumerate CruxCoach users by querying `#d`.
 *
 * `HMAC-SHA256(key = HKDF-SHA256(nsec, salt "cruxcoach-dtag-v1", info
 * "hmac-key", 32), identifier)`, byte-identical to Android's LOCAL path.
 * Android's Amber path has no iOS counterpart (NIP-55 is Android IPC), so
 * LOCAL is the only derivation here.
 */
object DTagDeriver {
    const val IDENTIFIER_BACKUP = "cruxcoach/backup/v1"
    const val IDENTIFIER_KEY = "cruxcoach/key/v1"

    private val HKDF_SALT = "cruxcoach-dtag-v1".encodeToByteArray()
    private val HKDF_INFO = "hmac-key".encodeToByteArray()

    fun derive(hashing: Hashing, secretKey: ByteArray, identifier: String): String? {
        if (secretKey.size != 32) return null
        val hmacKey = Hkdf.extractExpand(hashing, secretKey, HKDF_SALT, HKDF_INFO, 32)
        try {
            return hashing.hmacSha256(hmacKey, identifier.encodeToByteArray()).toHex()
        } finally {
            hmacKey.fill(0)
        }
    }
}

/**
 * Per-identity SQLCipher key, matching Android's `SqlCipherKeyManager`:
 * `HKDF-SHA256(ikm = master DB key, salt = pubkey hex bytes, info
 * "cruxcoach-secure-db", 32)`, with the database file named after the first
 * 16 hex characters of the pubkey.
 */
object SecureDbKey {
    private val INFO = "cruxcoach-secure-db".encodeToByteArray()

    fun derive(hashing: Hashing, masterKey: ByteArray, pubkeyHex: String): ByteArray =
        Hkdf.extractExpand(hashing, masterKey, pubkeyHex.encodeToByteArray(), INFO, 32)

    fun databaseName(pubkeyHex: String): String = "cruxcoach_secure_${pubkeyHex.take(16)}.db"
}
