package com.cruxcoach.domain.sharing

/**
 * FEAT-062 §10: the signing seam, for signers that live in another app.
 *
 * The synchronous [LedgerCrypto] could not express an external NIP-55 signer at
 * all. Approval happens in Amber, in its own process, when the person says so —
 * so a synchronous `sign` had exactly one honest answer for it, `null`, and
 * every permission change was simply impossible on an external identity.
 *
 * The seam is deliberately lopsided:
 *
 *  - **signing suspends.** It may show a prompt in another app, and it may be
 *    refused, time out, or be cancelled by a lifecycle restart.
 *  - **verification does not.** It is what the reducer does over stored
 *    history, and the reducer has to stay a pure synchronous fold — otherwise
 *    every access decision would become a coroutine.
 *
 * Which ledger a signature is for is bound into the implementation rather than
 * passed per call, so a signer instance can only ever produce signatures for
 * the one purpose it was built for.
 */
interface AsyncLedgerCrypto {

    /**
     * The signature over [hash], or `null` when this identity did not sign —
     * refused, unavailable, timed out, or the answer failed validation.
     *
     * Never a fake signature, and never a blocking wait.
     */
    suspend fun signCanonical(hash: ByteArray): ByteArray?

    fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String): Boolean

    /** Hash of the canonical bytes; the default keeps the protocol testable. */
    fun hash(canonical: ByteArray): ByteArray = canonical
}

/**
 * Uses a synchronous primitive through the async seam.
 *
 * The debug peer demo holds real keypairs in-process and the domain tests drive
 * deterministic stand-ins; neither needs to leave the process, and both must
 * keep producing exactly the signatures they did before.
 */
fun LedgerCrypto.asAsync(): AsyncLedgerCrypto {
    val delegate = this
    return object : AsyncLedgerCrypto {
        override suspend fun signCanonical(hash: ByteArray): ByteArray? = delegate.sign(hash)

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String): Boolean =
            delegate.verify(signature, hash, signerNpub)

        override fun hash(canonical: ByteArray): ByteArray = delegate.hash(canonical)
    }
}

/**
 * Signs relationship-ledger entries, possibly by asking another app.
 *
 * The canonical bytes are built before the request and are not consulted again
 * afterwards, so what comes back can only ever be accepted for the entry that
 * was actually put up for signing.
 */
class AsyncSharingLedgerSigner(private val crypto: AsyncLedgerCrypto) {

    /** `null` when the entry was not signed — never an unsigned entry. */
    suspend fun sign(entry: SharingLedgerEntry): SharingLedgerEntry? {
        val signature = crypto.signCanonical(crypto.hash(SharingLedgerCanonicalForm.bytes(entry)))
            ?: return null
        return entry.copy(signature = signature.toHex())
    }

    fun verifier(): LedgerSignatureVerifier = LedgerSignatureVerifier { entry ->
        val signature = entry.signature.fromHexOrNull() ?: return@LedgerSignatureVerifier false
        crypto.verify(
            signature,
            crypto.hash(SharingLedgerCanonicalForm.bytes(entry)),
            entry.signerNpub,
        )
    }
}

/** The owner-policy counterpart of [AsyncSharingLedgerSigner]. */
class AsyncOwnerPolicySigner(private val crypto: AsyncLedgerCrypto) {

    /** `null` when the entry was not signed — never an unsigned entry. */
    suspend fun sign(entry: OwnerPolicyEntry): OwnerPolicyEntry? {
        val signature = crypto.signCanonical(crypto.hash(OwnerPolicyCanonicalForm.bytes(entry)))
            ?: return null
        return entry.copy(signature = signature.toHex())
    }

    fun verifier(): (OwnerPolicyEntry) -> Boolean = { entry ->
        val signature = entry.signature.fromHexOrNull()
        signature != null && crypto.verify(
            signature,
            crypto.hash(OwnerPolicyCanonicalForm.bytes(entry)),
            entry.signerNpub,
        )
    }
}
