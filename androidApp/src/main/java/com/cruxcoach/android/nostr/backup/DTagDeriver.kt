package com.cruxcoach.android.nostr.backup

import android.util.Log
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.SignerMode
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *   we ask Amber to sign a fixed template event under kind
 *   [AMBER_AUX_SIGN_KIND] — an app-specific ephemeral kind the spec
 *   never uses, with an explicit `["purpose", "cruxcoach-dtag-v1"]`
 *   tag for domain separation + self-documentation in Amber's approval
 *   dialog. We take `SHA-256(sig)` as the d-tag.
 *
 *   Previously we reused kind 0 (profile metadata), which worked
 *   mechanically but surfaced as "sign profile metadata event" in
 *   Amber's approval UI — misleading and semantically wrong because
 *   the event is neither a real profile nor published anywhere.
 *
 *   Amber implementations vary on `aux_rand` handling, so determinism
 *   across fresh-install restores is NOT assumed — the Amber restore path
 *   (FEAT-002 §3.1) queries all Kind 30078 by pubkey and matches by
 *   decrypted content. We therefore only need the d-tag to be stable on
 *   *this* device, and cache the first derivation forever.
 *
 * All results are cached in [BackupPreferences] so neither an HMAC call nor
 * an Amber round-trip happens more than once per identifier + install.
 *
 * **Migration note**: because [derive] short-circuits on cache hit
 * (`preferences.getDTag(…)?.let { return it }`), the Amber kind switch
 * is forward-only. Installs that already cached a kind-0-derived d-tag
 * keep using it — no orphaned events. Fresh installs and any path that
 * clears identity state (key import, signer switch, remote-backup
 * deletion → see `A2` / `clearAllIdentityState`) get the new kind.
 */
@Singleton
class DTagDeriver @Inject constructor(
    private val nostrSigner: NostrSigner,
    private val keyStore: NostrKeyStore,
    private val preferences: BackupPreferences,
) {

    /**
     * Serializes the cache check-then-act so two concurrent callers for
     * the same identifier collapse to a single HMAC / Amber round-trip.
     * Without it, two backup pipeline branches firing in close succession
     * (manual + periodic) both miss the cache, both kick off Amber's
     * approval dialog, and the user sees a double prompt for a derivation
     * that's documented to happen "at most once per identifier + install".
     * The lock is per-singleton; cross-identifier callers are serialized
     * too, but each derive is sub-second on local + bound by Amber's UI
     * for the AMBER path, so the small added latency is negligible.
     */
    private val deriveMutex = Mutex()

    /** Returns the hex d-tag for [identifier], caching the first derivation. */
    suspend fun derive(identifier: String): String = deriveMutex.withLock {
        preferences.getDTag(identifier)?.let { return@withLock it }

        val dTag = when (nostrSigner.getStoredSignerMode()) {
            SignerMode.LOCAL -> deriveLocal(identifier)
            SignerMode.AMBER -> deriveAmber(identifier)
        }
        preferences.setDTag(identifier, dTag)
        dTag
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
        // Zero the master-key-derived buffers as soon as the MAC key
        // has been consumed. The JVM hex String we loaded from
        // SharedPreferences is inherently immutable + GC-governed (no
        // good way to zero that here), but at least the mutable
        // ByteArray copies don't linger on the heap for the next
        // memory scrape.
        try {
            val mac = Mac.getInstance("HmacSHA256")
            val keySpec = SecretKeySpec(hmacKey, "HmacSHA256")
            mac.init(keySpec)
            val digest = mac.doFinal(identifier.toByteArray(Charsets.UTF_8))
            return digest.toHexString()
        } finally {
            privKey.fill(0)
            hmacKey.fill(0)
        }
    }

    /**
     * Ask Amber (via the shared [NostrSigner]) to sign a fixed template
     * and take SHA-256 of the signature. One sign call per identifier +
     * install. Amber's approval dialog surfaces [AMBER_AUX_SIGN_KIND]
     * + the `purpose` tag so the user sees "CruxCoach auxiliary sign"
     * rather than "sign profile metadata event" (the pre-B5a misuse).
     */
    private suspend fun deriveAmber(identifier: String): String {
        val event: Event = try {
            nostrSigner.signer.sign<Event>(
                createdAt = 0L,
                kind = AMBER_AUX_SIGN_KIND,
                tags = arrayOf(
                    arrayOf("purpose", AMBER_AUX_PURPOSE),
                    arrayOf("identifier", identifier),
                ),
                content = "",
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
        /**
         * App-specific ephemeral kind reserved for d-tag derivation
         * sign calls. Ephemeral range (20000-29999) is correct
         * because the signed event is never published — only its
         * signature bytes are consumed locally to derive the d-tag.
         * 27777 is outside every NIP-claimed slot the spec defines
         * today.
         */
        const val AMBER_AUX_SIGN_KIND = 27777
        private const val AMBER_AUX_PURPOSE = "cruxcoach-dtag-v1"
    }
}
