package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FEAT-062 §2: the owner-policy ledger gets the same door as the relationship
 * ledger.
 *
 * It previously had none: a bad entry was refused when the policy was *read*,
 * which is fail-closed but permanent — an append-only ledger cannot drop it
 * again, so one unsigned or out-of-sequence row left the whole policy granting
 * nothing for good. Admission decides before the write, side-effect free.
 */
class OwnerPolicyAdmissionTest {

    private val owner = "npub1owner"
    private val alice = PeerId("npub1alice")

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private val signer = OwnerPolicySigner(TaggedCrypto(owner))
    private val verifier = signer.verifier()

    private fun entry(
        id: String,
        seq: Long,
        body: OwnerPolicyBody,
        gen: Long = 1,
        signerNpub: String = owner,
        // The policy is a DAG: every entry after the first names its parent.
        parent: String? = if (seq > 1) "e${seq - 1}" else null,
    ) =
        OwnerPolicyEntry(
            id = LedgerEntryId(id),
            policySequence = seq,
            parent = parent?.let { LedgerEntryId(it) },
            authorityGeneration = gen,
            signerNpub = signerNpub,
            signature = "",
            body = body,
        )

    private fun signed(
        id: String,
        seq: Long,
        body: OwnerPolicyBody,
        gen: Long = 1,
        parent: String? = if (seq > 1) "e${seq - 1}" else null,
    ) = signer.sign(entry(id, seq, body, gen, parent = parent))!!

    private val baseline = OwnerPolicyBody.CircleBaselineSet(
        SharingCircle.FRIENDS, SharingCategory.VIDEOS, true,
    )
    private val otherBody = OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY)

    private fun admit(existing: List<OwnerPolicyEntry>, candidate: OwnerPolicyEntry) =
        OwnerPolicyAdmission.check(existing, candidate, verifier, owner)

    // ------------------------------------------------------------- accepted

    @Test
    fun the_first_entry_is_admitted() {
        assertIs<LedgerAdmission.Accept>(admit(emptyList(), signed("e1", 1, baseline)))
    }

    @Test
    fun the_exact_next_sequence_is_admitted() {
        val first = signed("e1", 1, baseline)
        assertIs<LedgerAdmission.Accept>(admit(listOf(first), signed("e2", 2, otherBody)))
    }

    @Test
    fun a_byte_identical_duplicate_is_idempotent() {
        val first = signed("e1", 1, baseline)
        assertIs<LedgerAdmission.AlreadyPresent>(admit(listOf(first), first))
    }

    @Test
    fun a_higher_authority_generation_is_admitted() {
        val first = signed("e1", 1, baseline)
        assertIs<LedgerAdmission.Accept>(admit(listOf(first), signed("e2", 2, otherBody, gen = 2)))
    }

    // -------------------------------------------------------------- refused

    @Test
    fun an_entry_whose_signature_does_not_verify_is_refused() {
        val tampered = signed("e1", 1, baseline).copy(signature = "ff")
        val verdict = assertIs<LedgerAdmission.Reject>(admit(emptyList(), tampered))
        assertTrue(verdict.reason.contains("signature", ignoreCase = true))
    }

    @Test
    fun an_entry_signed_by_anybody_but_the_owner_is_refused() {
        val stranger = OwnerPolicySigner(TaggedCrypto("npub1mallory"))
            .sign(entry("e1", 1, baseline, signerNpub = "npub1mallory"))!!
        val verdict = assertIs<LedgerAdmission.Reject>(admit(emptyList(), stranger))
        assertTrue(verdict.reason.contains("owner", ignoreCase = true))
    }

    @Test
    fun a_gap_in_the_sequence_is_refused_before_it_is_stored() {
        val first = signed("e1", 1, baseline)
        // Follows the stored entry, but claims a number that does not follow it.
        val jumped = signed("e9", 9, otherBody, parent = "e1")
        val verdict = assertIs<LedgerAdmission.Reject>(admit(listOf(first), jumped))
        assertTrue(verdict.reason.contains("sequence", ignoreCase = true))
    }

    @Test
    fun a_history_that_does_not_start_at_one_is_refused() {
        assertIs<LedgerAdmission.Reject>(admit(emptyList(), signed("e2", 2, baseline)))
    }

    @Test
    fun an_id_reused_with_different_content_is_refused_as_divergent() {
        val first = signed("e1", 1, baseline)
        val divergent = signed("e1", 2, otherBody)
        val verdict = assertIs<LedgerAdmission.Reject>(admit(listOf(first), divergent))
        assertTrue(verdict.reason.contains("diverg", ignoreCase = true))
    }

    @Test
    fun a_sequence_that_is_already_used_is_refused() {
        val first = signed("e1", 1, baseline)
        assertIs<LedgerAdmission.Reject>(admit(listOf(first), signed("e1x", 1, otherBody)))
    }

    @Test
    fun a_stale_authority_generation_is_refused() {
        val first = signed("e1", 1, baseline, gen = 2)
        assertIs<LedgerAdmission.Reject>(admit(listOf(first), signed("e2", 2, otherBody, gen = 1)))
    }

    @Test
    fun an_entry_kind_the_build_cannot_understand_is_refused() {
        val unknown = signed("e1", 1, OwnerPolicyBody.Unknown("cc.owner.FromTheFuture"))
        assertIs<LedgerAdmission.Reject>(admit(emptyList(), unknown))
    }

    // ------------------------------------------------------- no side effects

    @Test
    fun a_refused_candidate_leaves_the_reduced_policy_exactly_as_it_was() {
        val reducer = OwnerPolicyReducer(verifier, owner)
        val existing = listOf(signed("e1", 1, baseline))
        val before = reducer.reduce(existing)

        assertIs<LedgerAdmission.Reject>(admit(existing, signed("e9", 9, otherBody)))

        assertTrue(reducer.reduce(existing).baselines == before.baselines)
        assertTrue(before.failClosedReason == null)
    }
}
