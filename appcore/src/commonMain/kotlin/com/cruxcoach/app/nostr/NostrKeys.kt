package com.cruxcoach.app.nostr

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import fr.acinq.secp256k1.Secp256k1

/**
 * secp256k1 key handling for the LOCAL signer. Amber / NIP-55 is an Android
 * IPC mechanism with no iOS counterpart, so this is the only signer mode.
 * Secret keys are `ByteArray` throughout and are never logged or stringified.
 */
object NostrKeys {
    /** True for a 32-byte scalar in `1 until n` (libsecp256k1's own check). */
    fun isValidSecretKey(secretKey: ByteArray): Boolean =
        secretKey.size == 32 && try {
            Secp256k1.secKeyVerify(secretKey)
        } catch (e: Exception) {
            false
        }

    /** Fresh key from the platform CSPRNG; invalid scalars are rejected and redrawn. Null if the RNG is broken. */
    fun generateSecretKey(hashing: Hashing): ByteArray? {
        repeat(16) {
            val candidate = hashing.randomBytes(32)
            if (candidate.size == 32 && candidate.any { it != 0.toByte() } && isValidSecretKey(candidate)) {
                return candidate
            }
        }
        return null
    }

    /** BIP-340 x-only public key (32 bytes), or null for an invalid secret key. */
    fun xOnlyPublicKey(secretKey: ByteArray): ByteArray? {
        if (!isValidSecretKey(secretKey)) return null
        return try {
            // 65-byte uncompressed point: 0x04 || X || Y.
            Secp256k1.pubkeyCreate(secretKey).copyOfRange(1, 33)
        } catch (e: Exception) {
            null
        }
    }

    fun publicKeyHex(secretKey: ByteArray): String? = xOnlyPublicKey(secretKey)?.toHex()

    /**
     * Builds and signs a NIP-01 event. Fresh 32-byte auxiliary randomness per
     * signature (BIP-340 recommendation). The result is self-verified before it
     * is returned, so a faulty signer can never emit an event we would reject.
     */
    fun sign(
        hashing: Hashing,
        secretKey: ByteArray,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): NostrEvent? {
        val pubkey = publicKeyHex(secretKey) ?: return null
        val id = NostrEvents.computeId(hashing, pubkey, createdAt, kind, tags, content)
        val idBytes = id.hexToBytesOrNull() ?: return null
        val aux = hashing.randomBytes(32)
        if (aux.size != 32) return null
        val sig = try {
            Secp256k1.signSchnorr(idBytes, secretKey, aux)
        } catch (e: Exception) {
            return null
        }
        val event = NostrEvent(id, pubkey, createdAt, kind, tags, content, sig.toHex())
        return event.takeIf { NostrEvents.verify(hashing, it) }
    }
}

/** HKDF-SHA256 (RFC 5869) on top of the platform HMAC. */
object Hkdf {
    private const val HASH_LEN = 32

    fun extract(hashing: Hashing, salt: ByteArray?, ikm: ByteArray): ByteArray =
        hashing.hmacSha256(if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(hashing: Hashing, prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "invalid HKDF length" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            t = hashing.hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val take = minOf(HASH_LEN, length - offset)
            t.copyInto(out, offset, 0, take)
            offset += take
            counter++
        }
        return out
    }

    fun extractExpand(hashing: Hashing, ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = extract(hashing, salt, ikm)
        try {
            return expand(hashing, prk, info, length)
        } finally {
            prk.fill(0)
        }
    }
}

/** Compares all bytes regardless of where the first difference is. */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
