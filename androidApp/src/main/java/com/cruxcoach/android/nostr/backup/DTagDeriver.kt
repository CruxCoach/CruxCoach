package com.cruxcoach.android.nostr.backup

import android.util.Log
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.SignerMode
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Derives deterministic-but-opaque d-tags for Kind 30078 events so relay
 * operators cannot enumerate CruxCoach users by querying `#d`.
 *
 * Two code paths:
 *
 * - **Local key (HMAC).** Derives a 32-byte HKDF key from the user's nsec
 *   (domain-separated via `info = "hmac-key"`), then HMACs each identifier.
 *   Output is deterministic across devices holding the same key and can be
 *   re-derived at any time — restore uses a gezielt `#d` filter, no
 *   query-all needed.
 *
 * - **Amber.** Amber never exposes nsec, so HMAC is unavailable. Instead,
 *   we ask Amber to sign a fixed template event (`kind=0, created_at=0,
 *   content=identifier, tags=[]`) and take `SHA-256(sig)` as the d-tag.
 *   Amber implementations vary on `aux_rand` handling, so determinism
 *   across fresh-install restores is NOT assumed — the Amber restore path
 *   (FEAT-002 §3.1) queries all Kind 30078 by pubkey and matches by
 *   decrypted content. We therefore only need the d-tag to be stable on
 *   *this* device, and cache the first derivation forever.
 *
 * All results are cached in [BackupPreferences] so neither an HMAC call nor
 * an Amber round-trip happens more than once per identifier + install.
 */
@Singleton
class DTagDeriver @Inject constructor(
    private val nostrSigner: NostrSigner,
    private val keyStore: NostrKeyStore,
    private val preferences: BackupPreferences,
) {

    /** Returns the hex d-tag for [identifier], caching the first derivation. */
    suspend fun derive(identifier: String): String {
        preferences.getDTag(identifier)?.let { return it }

        val dTag = when (nostrSigner.getStoredSignerMode()) {
            SignerMode.LOCAL -> deriveLocal(identifier)
            SignerMode.AMBER -> deriveAmber(identifier)
        }
        preferences.setDTag(identifier, dTag)
        return dTag
    }

    /**
     * HMAC-SHA256 over HKDF-derived 32-byte key. Salt is the constant
     * `"cruxcoach-dtag-v1"` (bytes); info is `"hmac-key"` for domain
     * separation from signing and ECDH.
     */
    private fun deriveLocal(identifier: String): String {
        val privKey = keyStore.getPrivateKeyHex()?.hexToByteArray()
            ?: error("deriveLocal invoked without a local private key")
        val hmacKey = HkdfSha256.derive(
            ikm = privKey,
            salt = HKDF_SALT.toByteArray(),
            info = HKDF_INFO.toByteArray(),
            outputLen = 32,
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val digest = mac.doFinal(identifier.toByteArray(Charsets.UTF_8))
        return digest.toHexString()
    }

    /**
     * Ask Amber (via the shared [NostrSigner]) to sign a fixed template and
     * take SHA-256 of the signature. One sign call per identifier + install.
     */
    private suspend fun deriveAmber(identifier: String): String {
        val event: Event = try {
            nostrSigner.signer.sign<Event>(
                createdAt = 0L,
                kind = 0,
                tags = emptyArray(),
                content = identifier,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Amber sign failed during d-tag derivation", e)
            throw e
        }
        val sigBytes = event.sig.hexToByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(sigBytes)
        return digest.toHexString()
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    companion object {
        private const val TAG = "DTagDeriver"
        private const val HKDF_SALT = "cruxcoach-dtag-v1"
        private const val HKDF_INFO = "hmac-key"
    }
}
