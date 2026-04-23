package com.cruxcoach.android.nostr.backup

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (HMAC-based Extract-and-Expand Key Derivation Function) over SHA-256,
 * per [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869).
 *
 * Used by FEAT-002 to derive a 32-byte HMAC key from the user's Nostr
 * private key (see [DTagDeriver]). Domain separation via [info] lets us
 * derive independent secrets from the same IKM without cross-contamination.
 *
 * No new dependencies — uses `javax.crypto.Mac` which is built into Android.
 */
internal object HkdfSha256 {

    private const val HASH_LEN = 32   // SHA-256 output length
    private const val MAX_OUT_LEN = 255 * HASH_LEN

    /**
     * Full HKDF: `extract` followed by `expand`. For a single-step caller,
     * prefer this over the two helpers — it matches the RFC test vectors
     * directly.
     *
     * @param ikm input keying material (e.g. the 32-byte nsec)
     * @param salt optional salt; empty or `null` → zero-salt per RFC §2.2
     * @param info optional info string for domain separation
     * @param outputLen desired output length in bytes (1..255*32)
     */
    fun derive(
        ikm: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray = ByteArray(0),
        outputLen: Int = HASH_LEN,
    ): ByteArray {
        require(outputLen in 1..MAX_OUT_LEN) {
            "outputLen must be 1..${MAX_OUT_LEN}, was $outputLen"
        }
        val prk = extract(salt, ikm)
        return expand(prk, info, outputLen)
    }

    /** HKDF-Extract: derives a pseudorandom key (PRK) from [ikm] using [salt] as HMAC key. */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val saltBytes = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(saltBytes, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand: derives [outputLen] bytes of output keying material from [prk] + [info]. */
    fun expand(prk: ByteArray, info: ByteArray, outputLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(outputLen)
        val n = (outputLen + HASH_LEN - 1) / HASH_LEN
        var t = ByteArray(0)
        var offset = 0
        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(byteArrayOf(i.toByte()))
            t = mac.doFinal()
            val remaining = outputLen - offset
            val take = if (remaining < HASH_LEN) remaining else HASH_LEN
            System.arraycopy(t, 0, out, offset, take)
            offset += take
        }
        return out
    }
}
