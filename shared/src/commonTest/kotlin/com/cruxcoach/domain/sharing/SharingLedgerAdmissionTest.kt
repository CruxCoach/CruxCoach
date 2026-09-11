package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FEAT-062 §5: what may be *written* to the ledger at all.
 *
 * The reducer fails closed when it reads something wrong, but that is not the
 * same as refusing to store it. An untrusted inbound entry that is persisted
 * and only then rejected poisons the relationship permanently: every later
 * reduction sees the same bad entry and stays `FAIL_CLOSED`, and the ledger is
 * append-only, so nothing can take it back out.
 *
 * Admission is therefore checked **before** anything is written, and it is
 * side-effect free by construction: it takes the stored entries and the
 * candidate and returns a verdict.
 */
class SharingLedgerAdmissionTest {

    private val owner = "npub1owner"
    private val alice = PeerId("npub1alice")
    private val phone = DeviceId("dev-phone")

    /** Signature = identity tag + hash, so one identity's signature is not another's. */
    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private val ownerSigner = SharingLedgerSigner(TaggedCrypto(owner))
    private val peerSigner = SharingLedgerSigner(TaggedCrypto(alice.value))
    private val verifier = ownerSigner.verifier()

    private fun unsigned(
        id: String,
        sequence: Long,
        body: SharingLedgerBody,
        signer: String,
        authorityGeneration: Long = 1,
        deviceGeneration: Long = 1,
        resourceEpoch: Long = 1,
        parent: String? = if (sequence > 1) "e${sequence - 1}" else null,
    ) = SharingLedgerEntry(
        id = LedgerEntryId(id),
        peer = alice,
        policySequence = sequence,
        parent = parent?.let { LedgerEntryId(it) },
        authorityGeneration = authorityGeneration,
        resourceEpoch = resourceEpoch,
        deviceGeneration = deviceGeneration,
        signerNpub = signer,
        signature = "",
        body = body,
    )

    private fun ownerEntry(
        id: String,
        seq: Long,
        body: SharingLedgerBody,
        gen: Long = 1,
        devGen: Long = 1,
        parent: String? = if (seq > 1) "e${seq - 1}" else null,
    ) = ownerSigner.sign(unsigned(id, seq, body, owner, gen, devGen, parent = parent))!!

    private fun peerEntry(
        id: String,
        seq: Long,
        body: SharingLedgerBody,
        gen: Long = 1,
        devGen: Long = 1,
        parent: String? = if (seq > 1) "e${seq - 1}" else null,
    ) = peerSigner.sign(unsigned(id, seq, body, alice.value, gen, devGen, parent = parent))!!

    private val offer = ownerEntry("e1", 1, SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)))
    private val accept = peerEntry("e2", 2, SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone))

    private fun admit(existing: List<SharingLedgerEntry>, candidate: SharingLedgerEntry) =
        SharingLedgerAdmission.check(existing, candidate, verifier, owner)

    // ------------------------------------------------------------- accepted

    @Test
    fun the_first_entry_of_a_relationship_is_admitted() {
        assertIs<LedgerAdmission.Accept>(admit(emptyList(), offer))
    }

    @Test
    fun the_exact_next_sequence_is_admitted() {
        assertIs<LedgerAdmission.Accept>(admit(listOf(offer), accept))
    }

    @Test
    fun a_byte_identical_duplicate_is_idempotent_rather_than_an_error() {
        assertIs<LedgerAdmission.AlreadyPresent>(admit(listOf(offer, accept), accept))
    }

    // ------------------------------------------------------------- refused

    @Test
    fun an_entry_whose_signature_does_not_verify_is_refused() {
        val tampered = accept.copy(signature = "ff")
        val verdict = admit(listOf(offer), tampered)
        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("signature", ignoreCase = true))
    }

    @Test
    fun an_entry_whose_body_was_altered_after_signing_is_refused() {
        val altered = accept.copy(
            body = SharingLedgerBody.RecipientAccepted(
                setOf(SharingCategory.VIDEOS, SharingCategory.HEALTH_INFORMATION), phone,
            ),
        )
        assertIs<LedgerAdmission.Reject>(admit(listOf(offer), altered))
    }

    @Test
    fun an_entry_signed_by_the_wrong_role_is_refused() {
        val ownerSignedAcceptance = ownerEntry(
            "e2", 2, SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone),
        )
        val verdict = admit(listOf(offer), ownerSignedAcceptance)
        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("authorised", ignoreCase = true))
    }

    @Test
    fun an_owner_signed_device_authorisation_is_refused() {
        val ownerAuthorised = ownerEntry("e3", 3, SharingLedgerBody.DeviceAuthorized(DeviceId("dev-tablet")))
        assertIs<LedgerAdmission.Reject>(admit(listOf(offer, accept), ownerAuthorised))
    }

    @Test
    fun a_gap_in_the_sequence_is_refused_before_it_can_be_stored() {
        // Follows the stored head, but claims a number that does not follow it.
        val far = ownerEntry("e9", 9, SharingLedgerBody.RelationshipRevoked, parent = "e2")
        val verdict = admit(listOf(offer, accept), far)
        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("sequence", ignoreCase = true))
    }

    /** A branch is fine; a branch hanging off nothing is not. */
    @Test
    fun an_entry_whose_parent_was_never_stored_is_refused() {
        val orphan = ownerEntry("e9", 9, SharingLedgerBody.RelationshipRevoked, parent = "e8")
        val verdict = admit(listOf(offer, accept), orphan)
        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("not stored", ignoreCase = true))
    }

    /**
     * A sibling on the same parent is a *branch*, not a collision.
     *
     * This used to be refused as "an already-used sequence is a fork", which is
     * exactly what made the authority model decorative: two of the owner's
     * devices offline both build on the same entry, and refusing the second to
     * arrive let arrival order decide who can see what. Both are stored;
     * AuthorityBranchResolver picks the one that stands.
     */
    @Test
    fun a_sibling_branch_on_the_same_parent_is_admitted() {
        val rival = ownerEntry("other", 2, SharingLedgerBody.RelationshipRevoked, parent = "e1")

        assertIs<LedgerAdmission.Accept>(admit(listOf(offer, accept), rival))
    }

    /** What is still a collision: one id claiming two different histories. */
    @Test
    fun one_id_may_not_carry_two_different_entries() {
        val divergent = accept.copy(body = SharingLedgerBody.RecipientDeclined)

        assertIs<LedgerAdmission.Reject>(admit(listOf(offer, accept), divergent))
    }

    @Test
    fun one_id_arriving_with_different_content_is_refused_as_divergent() {
        // Correctly signed, so it gets past authenticity — but it claims an id
        // the ledger already knows, with different content behind it.
        val divergent = peerEntry("e2", 3, SharingLedgerBody.RecipientDeclined)
        val verdict = admit(listOf(offer, accept), divergent)
        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("diverg", ignoreCase = true), "was: ${verdict.reason}")
    }

    @Test
    fun re_signing_a_stored_entry_with_altered_fields_is_refused_by_the_signature() {
        // The cheaper forgery — edit a field and hope — dies at authenticity,
        // before the divergence check ever matters.
        val verdict = admit(listOf(offer, accept), accept.copy(policySequence = 3))
        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("signature", ignoreCase = true))
    }

    @Test
    fun a_stale_device_generation_is_refused() {
        val restore = ownerEntry("e3", 3, SharingLedgerBody.RestoreCompleted(2), devGen = 2)
        val stale = ownerEntry("e4", 4, SharingLedgerBody.RelationshipRevoked, devGen = 1)
        assertIs<LedgerAdmission.Reject>(admit(listOf(offer, accept, restore), stale))
    }

    @Test
    fun a_device_generation_that_jumps_without_a_restore_is_refused() {
        val jump = ownerEntry("e3", 3, SharingLedgerBody.RelationshipRevoked, devGen = 7)
        assertIs<LedgerAdmission.Reject>(admit(listOf(offer, accept), jump))
    }

    @Test
    fun an_entry_the_build_cannot_understand_is_refused() {
        val unknown = ownerEntry("e3", 3, SharingLedgerBody.Unknown("cc.sharing.FromTheFuture"))
        assertIs<LedgerAdmission.Reject>(admit(listOf(offer, accept), unknown))
    }

    @Test
    fun an_entry_for_a_different_peer_is_refused() {
        val other = ownerSigner.sign(
            unsigned("e3", 3, SharingLedgerBody.RelationshipRevoked, owner).copy(peer = PeerId("npub1bob"))
        )!!
        assertIs<LedgerAdmission.Reject>(admit(listOf(offer, accept), other))
    }

    // ------------------------------------------------------- no side effects

    @Test
    fun checking_admission_does_not_change_the_entries_it_was_given() {
        val existing = listOf(offer, accept)
        val before = existing.toList()

        admit(existing, ownerEntry("e9", 9, SharingLedgerBody.RelationshipRevoked))

        assertEquals(before, existing)
    }

    @Test
    fun a_refused_entry_leaves_the_reduced_state_exactly_as_it_was() {
        val reducer = SharingLedgerReducer(verifier, owner)
        val existing = listOf(offer, accept)
        val before = reducer.reduce(existing).relationships[alice]

        val bad = ownerEntry("e9", 9, SharingLedgerBody.RelationshipRevoked)
        assertIs<LedgerAdmission.Reject>(admit(existing, bad))

        assertEquals(before, reducer.reduce(existing).relationships[alice])
    }
}
