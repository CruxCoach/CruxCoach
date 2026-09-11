package com.cruxcoach.android.sharing

import android.util.Log
import com.cruxcoach.domain.sharing.AsyncLedgerCrypto
import com.cruxcoach.domain.sharing.Nip01SignedEvent
import com.cruxcoach.domain.sharing.Nip01SigningEnvelope
import com.cruxcoach.domain.sharing.SigningDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/**
 * Asks a signer to sign one NIP-01 event.
 *
 * The single call that has to reach the Nostr library, kept behind an interface
 * so everything around it — what we ask for, and what we accept back — is
 * ordinary testable code. `null` means "did not sign": refused, timed out, no
 * signer installed, or read-only.
 */
fun interface Nip01EventSigner {
    suspend fun sign(
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): Nip01SignedEvent?
}

/** BIP-340 verification. Native, hence injected. */
fun interface Bip340Verifier {
    fun verify(signature: ByteArray, message32: ByteArray, pubKey: ByteArray): Boolean
}

/**
 * FEAT-062 §10: signs permission-ledger entries with the account's Nostr
 * identity, local or external, without ever seeing a private key.
 *
 * The previous implementation reached for `signer.keyPair.privKey` and returned
 * `null` whenever there wasn't one — which is to say, an external NIP-55/Amber
 * identity could not change a single permission. This signs through the
 * signer abstraction instead, so a local key and Amber take the same path and
 * produce the same bytes.
 *
 * What is stored is still just the 64-byte signature. The event it belongs to
 * is reconstructed from the canonical hash and the signer's public key, both of
 * which the entry already carries, so nothing extra has to be persisted and
 * nothing about the event can drift between signing and verifying.
 *
 * Everything about the answer is checked before it is believed:
 *
 *  - every field of the returned event is the field we asked for, including the
 *    id — which is what proves the signer serialised the event the same way we
 *    did, since the id is what the signature actually covers;
 *  - the signature verifies under BIP-340 against exactly the event id fixed
 *    *before* the request went out.
 *
 * A failure of any of these is `null`, never an exception and never a
 * signature. Cancellation is the one thing that propagates: a screen rotation
 * that kills the coroutine must not be mistaken for a refusal, and must not let
 * any caller carry on to write something.
 */
class Nip55LedgerCrypto(
    private val domain: SigningDomain,
    private val pubKeyHex: () -> String?,
    private val eventSigner: Nip01EventSigner,
    private val bip340: Bip340Verifier,
    /**
     * How long to wait for an approval before giving up.
     *
     * Configurable only so a test can drive it; production uses
     * [DEFAULT_APPROVAL_TIMEOUT_MILLIS].
     */
    private val approvalTimeoutMillis: Long = DEFAULT_APPROVAL_TIMEOUT_MILLIS,
) : AsyncLedgerCrypto {

    override fun hash(canonical: ByteArray): ByteArray =
        MessageDigest.getInstance(SHA_256).digest(canonical)

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun signCanonical(hash: ByteArray): ByteArray? {
        // Fixed before anything leaves the process. Nothing below recomputes
        // them from the answer, so what comes back can only be accepted for
        // this exact request.
        val pubKey = pubKeyHex()?.lowercase()?.takeIf { it.isPublicKey() } ?: return null
        val expectedId = Nip01SigningEnvelope.eventId(pubKey, domain, hash, ::sha256)

        val event = try {
            // Bounded, because nothing about the IPC to another app guarantees
            // an answer: the signer can be killed, suspended, or left behind a
            // lock screen with its prompt unanswered. Waiting for ever is not
            // fail-closed but fail-stuck — this call holds the controller's
            // mutation lock and the screen's signing state, so one wedged
            // request would block every later permission change too.
            //
            // `withTimeoutOrNull` is the right shape here rather than a plain
            // `withTimeout`: our own expiry is a refusal to keep waiting, which
            // is `null` like every other way of not getting a signature. An
            // *outer* cancellation is a different thing — the caller going away
            // — and that still propagates, because only the timeout's own
            // cancellation is swallowed.
            withTimeoutOrNull(approvalTimeoutMillis) {
                eventSigner.sign(
                    createdAt = Nip01SigningEnvelope.CREATED_AT,
                    kind = Nip01SigningEnvelope.KIND,
                    tags = Nip01SigningEnvelope.tags(domain),
                    content = Nip01SigningEnvelope.content(hash),
                )
            }
        } catch (cancelled: CancellationException) {
            // Not a refusal. Let it unwind so no caller writes anything.
            throw cancelled
        } catch (e: Exception) {
            // Refused, not installed, read-only: all the same to us.
            Log.w(TAG, "external signer did not sign a $domain entry")
            return null
        } ?: return null

        val mismatch = Nip01SigningEnvelope.check(event, pubKey, domain, hash, ::sha256)
        if (mismatch != null) {
            // The field name only — never the event, the hash or the key.
            Log.w(TAG, "discarding a signed event: $mismatch")
            return null
        }

        val signature = event.signatureBytes() ?: return null
        if (!verifyBytes(signature, expectedId, pubKey)) {
            Log.w(TAG, "discarding a signed event: signature does not verify")
            return null
        }
        return signature
    }

    override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String): Boolean {
        val pubKey = signerNpub.lowercase().takeIf { it.isPublicKey() } ?: return false
        if (signature.size != SIGNATURE_BYTES) return false
        return verifyBytes(signature, Nip01SigningEnvelope.eventId(pubKey, domain, hash, ::sha256), pubKey)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun verifyBytes(signature: ByteArray, message32: ByteArray, pubKeyHex: String): Boolean = try {
        val pubKey = pubKeyHex.hexToBytesOrNull() ?: return false
        bip340.verify(signature, message32, pubKey)
    } catch (e: Exception) {
        // A signature that cannot be checked is not a signature.
        Log.w(TAG, "signature check failed")
        false
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance(SHA_256).digest(bytes)

    private fun String.isPublicKey() = length == PUBLIC_KEY_HEX && all { it in HEX }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    companion object {
        /**
         * Two minutes.
         *
         * The wait has to cover a person noticing a notification, unlocking the
         * phone, reading what they are approving and pressing the button — a
         * biometric prompt on a cold device eats a surprising amount of that,
         * so seconds would turn ordinary approvals into spurious failures. It
         * also has to be short enough that a signer which is never coming back
         * does not leave the screen unusable, and a permission change is the
         * kind of thing a person is doing deliberately and watching, not a
         * background sync they walked away from. Two minutes is the compromise;
         * an expiry is reported as SIGNER_UNAVAILABLE and the change can simply
         * be made again.
         */
        const val DEFAULT_APPROVAL_TIMEOUT_MILLIS: Long = 2 * 60 * 1000L

        private const val TAG = "Nip55LedgerCrypto"
        private const val SHA_256 = "SHA-256"
        private const val PUBLIC_KEY_HEX = 64
        private const val SIGNATURE_BYTES = 64
        private const val HEX = "0123456789abcdef"
    }
}
