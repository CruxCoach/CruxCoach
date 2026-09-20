package com.cruxcoach.app.nostr

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.isLowerHex64
import fr.acinq.secp256k1.Secp256k1

/**
 * NIP-44 v2: secp256k1 ECDH → HKDF → ChaCha20 → HMAC-SHA256, base64 on the wire.
 *
 * Used for everything CruxCoach encrypts to itself or to the developer key:
 * the backup pointer, the wrapped data key, and the NIP-59 seal/wrap layers.
 * Payloads produced here are byte-compatible with the Android app's Quartz
 * implementation, so a backup written on one platform decrypts on the other.
 *
 * All failures are `null`. Nothing here throws, and no branch before the MAC
 * check depends on secret data.
 */
object Nip44 {
    private const val VERSION: Byte = 2
    private const val NONCE_LEN = 32
    private const val MAC_LEN = 32
    private const val MIN_PLAINTEXT = 1
    private const val MAX_PLAINTEXT = 65535
    private val SALT = "nip44-v2".encodeToByteArray()

    /**
     * The long-lived symmetric key for a (private key, public key) pair:
     * `hkdf_extract(ikm = ecdh_x, salt = "nip44-v2")`.
     *
     * NIP-44 uses the UNHASHED x coordinate of the shared point, which is why
     * this multiplies the lifted-x point by the scalar directly instead of
     * calling a standard ECDH (those hash the result).
     */
    fun conversationKey(hashing: Hashing, secretKey: ByteArray, publicKeyHex: String): ByteArray? {
        if (!NostrKeys.isValidSecretKey(secretKey)) return null
        if (!publicKeyHex.isLowerHex64()) return null
        val pubkey = publicKeyHex.hexToBytesOrNull() ?: return null
        val shared = try {
            // 0x02 || x lifts the x-only key to the even-y point, per BIP-340.
            Secp256k1.pubKeyTweakMul(byteArrayOf(2) + pubkey, secretKey)
        } catch (e: Exception) {
            return null
        }
        if (shared.size < 33) return null
        val x = shared.copyOfRange(1, 33)
        try {
            return Hkdf.extract(hashing, SALT, x)
        } finally {
            x.fill(0)
        }
    }

    class MessageKeys(val chachaKey: ByteArray, val chachaNonce: ByteArray, val hmacKey: ByteArray) {
        fun clear() {
            chachaKey.fill(0)
            chachaNonce.fill(0)
            hmacKey.fill(0)
        }
    }

    /** Per-message keys: `hkdf_expand(conversation_key, info = nonce, 76)`. */
    fun messageKeys(hashing: Hashing, conversationKey: ByteArray, nonce: ByteArray): MessageKeys? {
        if (conversationKey.size != 32 || nonce.size != NONCE_LEN) return null
        val expanded = Hkdf.expand(hashing, conversationKey, nonce, 76)
        try {
            return MessageKeys(
                chachaKey = expanded.copyOfRange(0, 32),
                chachaNonce = expanded.copyOfRange(32, 44),
                hmacKey = expanded.copyOfRange(44, 76),
            )
        } finally {
            expanded.fill(0)
        }
    }

    /** NIP-44 padding scheme: power-of-two buckets, minimum 32 bytes. */
    fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 0) return 0
        if (unpaddedLen <= 32) return 32
        val nextPower = 1 shl (32 - (unpaddedLen - 1).countLeadingZeroBits())
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpaddedLen - 1) / chunk + 1)
    }

    /** Encrypts with a fresh random nonce. */
    fun encrypt(hashing: Hashing, conversationKey: ByteArray, plaintext: String): String? =
        encryptWithNonce(hashing, conversationKey, plaintext, hashing.randomBytes(NONCE_LEN))

    /** Nonce-injecting variant — only for test vectors; production must use [encrypt]. */
    internal fun encryptWithNonce(
        hashing: Hashing,
        conversationKey: ByteArray,
        plaintext: String,
        nonce: ByteArray,
    ): String? {
        if (nonce.size != NONCE_LEN) return null
        val utf8 = plaintext.encodeToByteArray()
        if (utf8.size < MIN_PLAINTEXT || utf8.size > MAX_PLAINTEXT) return null
        val keys = messageKeys(hashing, conversationKey, nonce) ?: return null
        try {
            val padded = ByteArray(2 + calcPaddedLen(utf8.size))
            padded[0] = (utf8.size ushr 8).toByte()
            padded[1] = utf8.size.toByte()
            utf8.copyInto(padded, 2)
            val ciphertext = ChaCha20.xor(keys.chachaKey, keys.chachaNonce, 0, padded) ?: return null
            padded.fill(0)
            val mac = hashing.hmacSha256(keys.hmacKey, nonce + ciphertext)
            return Base64.encode(byteArrayOf(VERSION) + nonce + ciphertext + mac)
        } finally {
            keys.clear()
            utf8.fill(0)
        }
    }

    /** Null for a wrong version, bad base64, wrong length, bad MAC or bad padding. */
    fun decrypt(hashing: Hashing, conversationKey: ByteArray, payload: String): String? {
        if (payload.isEmpty() || payload[0] == '#') return null
        // 132 = shortest possible payload (1-byte plaintext), 87472 = longest.
        if (payload.length < 132 || payload.length > 87472) return null
        val raw = Base64.decode(payload) ?: return null
        if (raw.size < 99 || raw.size > 65603) return null
        if (raw[0] != VERSION) return null
        val nonce = raw.copyOfRange(1, 1 + NONCE_LEN)
        val ciphertext = raw.copyOfRange(1 + NONCE_LEN, raw.size - MAC_LEN)
        val mac = raw.copyOfRange(raw.size - MAC_LEN, raw.size)
        val keys = messageKeys(hashing, conversationKey, nonce) ?: return null
        try {
            val expected = hashing.hmacSha256(keys.hmacKey, nonce + ciphertext)
            if (!constantTimeEquals(expected, mac)) return null
            val padded = ChaCha20.xor(keys.chachaKey, keys.chachaNonce, 0, ciphertext) ?: return null
            try {
                if (padded.size < 2) return null
                val length = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
                if (length < MIN_PLAINTEXT || length > MAX_PLAINTEXT) return null
                // The padding must be exactly the one the encoder would have produced:
                // a shorter or longer buffer means a forged or truncated payload.
                if (padded.size != 2 + calcPaddedLen(length)) return null
                for (i in 2 + length until padded.size) if (padded[i] != 0.toByte()) return null
                return padded.decodeToString(2, 2 + length)
            } finally {
                padded.fill(0)
            }
        } finally {
            keys.clear()
        }
    }

    /** Convenience for the self-encryption the backup pipeline does everywhere. */
    fun encryptToSelf(hashing: Hashing, secretKey: ByteArray, plaintext: String): String? {
        val pubkey = NostrKeys.publicKeyHex(secretKey) ?: return null
        val conversationKey = conversationKey(hashing, secretKey, pubkey) ?: return null
        try {
            return encrypt(hashing, conversationKey, plaintext)
        } finally {
            conversationKey.fill(0)
        }
    }

    fun decryptFromSelf(hashing: Hashing, secretKey: ByteArray, payload: String): String? {
        val pubkey = NostrKeys.publicKeyHex(secretKey) ?: return null
        val conversationKey = conversationKey(hashing, secretKey, pubkey) ?: return null
        try {
            return decrypt(hashing, conversationKey, payload)
        } finally {
            conversationKey.fill(0)
        }
    }
}
