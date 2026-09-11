package com.cruxcoach.domain.sharing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §10: signing that may have to leave the process.
 *
 * An external NIP-55 signer runs in another app and answers when the user says
 * so, which a synchronous `sign(hash): ByteArray?` cannot express — it could
 * only ever return `null`, which is exactly the limitation this replaces.
 *
 * The seam is deliberately lopsided: signing suspends, verification does not.
 * Verification is what the reducer does over stored history, and the reducer
 * has to stay a pure synchronous fold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncLedgerSigningTest {

    private companion object {
        const val OWNER = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
        const val PEER = "1111111111111111111111111111111111111111111111111111111111111111"
    }

    /**
     * Stands in for the curve, but not for the envelope: it signs the *event
     * id* the real signer would sign, so a signature really is bound to one
     * ledger and one identity.
     */
    private class EnvelopeCrypto(
        private val identity: String,
        private val domain: SigningDomain,
        private val approval: CompletableDeferred<Boolean>? = null,
    ) : AsyncLedgerCrypto {

        /**
         * Not a real digest, but it depends on *every* input byte, which is the
         * only property these tests lean on. Truncating the preimage instead
         * would silently pass: the purpose tag and the entry's sequence both
         * live well past the first 32 bytes.
         */
        private val sha: (ByteArray) -> ByteArray = { bytes ->
            var h = -0x340d631b7bdddcdbL
            bytes.forEach { b -> h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L }
            ByteArray(32) { i ->
                h = (h xor (i.toLong() + 1)) * 0x100000001b3L
                (h ushr 24).toByte()
            }
        }

        override suspend fun signCanonical(hash: ByteArray): ByteArray? {
            if (approval != null && !approval.await()) return null
            return tag(identity, Nip01SigningEnvelope.eventId(identity, domain, hash, sha))
        }

        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String): Boolean =
            signature.contentEquals(
                tag(signerNpub, Nip01SigningEnvelope.eventId(signerNpub, domain, hash, sha))
            )

        private fun tag(who: String, id: ByteArray) = (who + ":").encodeToByteArray() + id
    }

    /** An identity that cannot sign at all. */
    private object Unavailable : AsyncLedgerCrypto {
        override suspend fun signCanonical(hash: ByteArray): ByteArray? = null
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) = false
    }

    private fun entry(seq: Long = 1L) = SharingLedgerEntry(
        id = LedgerEntryId("e$seq"),
        peer = PeerId(PEER),
        policySequence = seq,
        authorityGeneration = 1,
        resourceEpoch = 1,
        deviceGeneration = 1,
        signerNpub = OWNER,
        signature = "",
        body = SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
    )

    private fun policyEntry(seq: Long = 1L) = OwnerPolicyEntry(
        id = LedgerEntryId("p$seq"),
        policySequence = seq,
        authorityGeneration = 1,
        signerNpub = OWNER,
        signature = "",
        body = OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
    )

    // ------------------------------------------------------- round trips

    @Test
    fun a_relationship_entry_signed_asynchronously_verifies_synchronously() = runTest {
        val crypto = EnvelopeCrypto(OWNER, SigningDomain.RELATIONSHIP_LEDGER)
        val signer = AsyncSharingLedgerSigner(crypto)

        val signed = assertNotNull(signer.sign(entry()))

        assertTrue(signed.signature.isNotEmpty())
        assertTrue(signer.verifier().verify(signed))
    }

    @Test
    fun an_owner_policy_entry_signed_asynchronously_verifies_synchronously() = runTest {
        val crypto = EnvelopeCrypto(OWNER, SigningDomain.OWNER_POLICY_LEDGER)
        val signer = AsyncOwnerPolicySigner(crypto)

        val signed = assertNotNull(signer.sign(policyEntry()))

        assertTrue(signer.verifier()(signed))
    }

    @Test
    fun the_body_is_untouched_and_only_the_signature_is_filled_in() = runTest {
        val signer = AsyncSharingLedgerSigner(EnvelopeCrypto(OWNER, SigningDomain.RELATIONSHIP_LEDGER))
        val unsigned = entry()

        val signed = assertNotNull(signer.sign(unsigned))

        assertEquals(unsigned.copy(signature = signed.signature), signed)
    }

    // ------------------------------------------------------- fail closed

    @Test
    fun an_identity_that_cannot_sign_produces_no_entry_rather_than_an_unsigned_one() = runTest {
        assertNull(AsyncSharingLedgerSigner(Unavailable).sign(entry()))
        assertNull(AsyncOwnerPolicySigner(Unavailable).sign(policyEntry()))
    }

    @Test
    fun a_refused_approval_produces_no_entry() = runTest {
        val approval = CompletableDeferred<Boolean>()
        val signer = AsyncSharingLedgerSigner(
            EnvelopeCrypto(OWNER, SigningDomain.RELATIONSHIP_LEDGER, approval)
        )

        val signing = async { signer.sign(entry()) }
        approval.complete(false)

        assertNull(signing.await())
    }

    @Test
    fun nothing_is_signed_until_the_approval_comes_back() = runTest {
        val approval = CompletableDeferred<Boolean>()
        val signer = AsyncSharingLedgerSigner(
            EnvelopeCrypto(OWNER, SigningDomain.RELATIONSHIP_LEDGER, approval)
        )

        val signing = async { signer.sign(entry()) }
        runCurrent()
        assertFalse(signing.isCompleted, "signing must wait for the external signer, not guess")

        approval.complete(true)
        assertNotNull(signing.await())
    }

    @Test
    fun a_cancelled_signing_never_yields_an_entry() = runTest {
        val approval = CompletableDeferred<Boolean>()
        val signer = AsyncSharingLedgerSigner(
            EnvelopeCrypto(OWNER, SigningDomain.RELATIONSHIP_LEDGER, approval)
        )

        val signing = async { signer.sign(entry()) }
        runCurrent()
        signing.cancel()

        assertTrue(signing.isCancelled)
        // The approval arriving afterwards must not resurrect the write.
        approval.complete(true)
        assertTrue(signing.isCancelled)
    }

    // ------------------------------------------------- domain separation

    @Test
    fun a_relationship_signature_is_not_valid_on_the_owner_policy_ledger() = runTest {
        // Same identity, same stand-in curve, same canonical bytes — only the
        // purpose differs, and that alone must break verification.
        val relationship = EnvelopeCrypto(OWNER, SigningDomain.RELATIONSHIP_LEDGER)
        val policy = EnvelopeCrypto(OWNER, SigningDomain.OWNER_POLICY_LEDGER)

        val signed = assertNotNull(AsyncSharingLedgerSigner(relationship).sign(entry()))

        assertTrue(AsyncSharingLedgerSigner(relationship).verifier().verify(signed))
        assertFalse(AsyncSharingLedgerSigner(policy).verifier().verify(signed))
    }

    @Test
    fun a_signature_from_another_identity_is_refused() = runTest {
        val signed = assertNotNull(
            AsyncSharingLedgerSigner(EnvelopeCrypto(PEER, SigningDomain.RELATIONSHIP_LEDGER)).sign(entry())
        )
        // Signed by the peer's key but claiming to be the owner's entry.
        assertFalse(
            AsyncSharingLedgerSigner(EnvelopeCrypto(OWNER, SigningDomain.RELATIONSHIP_LEDGER))
                .verifier().verify(signed)
        )
    }

    @Test
    fun a_tampered_entry_no_longer_verifies() = runTest {
        val crypto = EnvelopeCrypto(OWNER, SigningDomain.RELATIONSHIP_LEDGER)
        val signed = assertNotNull(AsyncSharingLedgerSigner(crypto).sign(entry()))

        val moved = signed.copy(policySequence = 9)

        assertFalse(AsyncSharingLedgerSigner(crypto).verifier().verify(moved))
    }

    // --------------------------------------------------- the sync bridge

    /**
     * The debug peer demo and the domain tests drive a synchronous stand-in.
     * Wrapping it must produce the same signatures the synchronous signer did,
     * so those paths keep working unchanged.
     */
    @Test
    fun a_synchronous_primitive_can_still_be_used_through_the_async_seam() = runTest {
        val sync = object : LedgerCrypto {
            override fun sign(hash: ByteArray) = "s:".encodeToByteArray() + hash
            override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
                signature.contentEquals("s:".encodeToByteArray() + hash)
        }

        val viaAsync = assertNotNull(AsyncSharingLedgerSigner(sync.asAsync()).sign(entry()))
        val viaSync = assertNotNull(SharingLedgerSigner(sync).sign(entry()))

        assertEquals(viaSync.signature, viaAsync.signature)
        assertTrue(SharingLedgerSigner(sync).verifier().verify(viaAsync))
    }
}
