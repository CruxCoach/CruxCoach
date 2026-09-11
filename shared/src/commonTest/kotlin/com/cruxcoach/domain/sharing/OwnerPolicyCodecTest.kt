package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FEAT-062 §2: owner-policy entries must survive storage and be signable. */
class OwnerPolicyCodecTest {

    private val alice = PeerId("npub1alice")
    private val video = ObjectId("video-1")

    private fun roundTrip(body: OwnerPolicyBody): OwnerPolicyBody {
        val (kind, json) = OwnerPolicyCodec.encodeBody(body)
        return OwnerPolicyCodec.decodeBody(kind, json)
    }

    @Test
    fun a_baseline_grant_round_trips() {
        val body = OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        assertEquals(body, roundTrip(body))
    }

    @Test
    fun a_baseline_removal_round_trips() {
        val body = OwnerPolicyBody.CircleBaselineSet(SharingCircle.ACQUAINTANCES, SharingCategory.PRIVATE_NOTES, false)
        assertEquals(body, roundTrip(body))
    }

    @Test
    fun a_person_rule_round_trips_including_its_cleared_form() {
        listOf(
            OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY),
            OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, null),
        ).forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun an_object_rule_round_trips_including_its_cleared_form() {
        listOf(
            OwnerPolicyBody.ObjectRuleSet(alice, video, SharingCategory.VIDEOS, AccessEffect.ALLOW),
            OwnerPolicyBody.ObjectRuleSet(alice, video, SharingCategory.VIDEOS, null),
        ).forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun an_unknown_kind_decodes_to_unknown_rather_than_being_dropped() {
        val decoded = OwnerPolicyCodec.decodeBody("cc.owner.FromTheFuture", "{}")
        assertIs<OwnerPolicyBody.Unknown>(decoded)
    }

    @Test
    fun a_corrupt_body_decodes_to_unknown_rather_than_throwing() {
        assertIs<OwnerPolicyBody.Unknown>(OwnerPolicyCodec.decodeBody("CircleBaselineSet", "not json"))
    }

    @Test
    fun an_unrecognised_circle_or_category_decodes_to_unknown() {
        assertIs<OwnerPolicyBody.Unknown>(
            OwnerPolicyCodec.decodeBody("CircleBaselineSet", """{"circle":"EVERYONE","category":"VIDEOS","granted":true}""")
        )
        assertIs<OwnerPolicyBody.Unknown>(
            OwnerPolicyCodec.decodeBody("CircleBaselineSet", """{"circle":"FRIENDS","category":"TELEPATHY","granted":true}""")
        )
    }

    @Test
    fun an_unknown_body_reaching_the_reducer_fails_the_policy_closed() {
        val reducer = OwnerPolicyReducer({ true }, ownerNpub = "npub1owner")
        val entry = OwnerPolicyEntry(
            id = LedgerEntryId("e1"),
            policySequence = 1,
            authorityGeneration = 1,
            signerNpub = "npub1owner",
            signature = "sig",
            body = OwnerPolicyCodec.decodeBody("cc.owner.FromTheFuture", "{}"),
        )
        assertTrue(reducer.reduce(listOf(entry)).failClosedReason != null)
    }

    // ------------------------------------------------------------ signing

    private object FakeCrypto : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = hash.copyOf()
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals(hash)
    }

    private object UnavailableCrypto : LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray? = null
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) = false
    }

    private fun unsigned(seq: Long = 1, body: OwnerPolicyBody = OwnerPolicyBody.CircleBaselineSet(
        SharingCircle.FRIENDS, SharingCategory.VIDEOS, true,
    )) = OwnerPolicyEntry(
        id = LedgerEntryId("e1"),
        policySequence = seq,
        authorityGeneration = 1,
        signerNpub = "npub1owner",
        signature = "",
        body = body,
    )

    @Test
    fun a_signed_owner_entry_verifies() {
        val signer = OwnerPolicySigner(FakeCrypto)
        val signed = assertNotNull(signer.sign(unsigned()))
        assertTrue(signed.signature.isNotEmpty())
        assertTrue(signer.verifier()(signed))
    }

    @Test
    fun a_tampered_owner_entry_stops_verifying() {
        val signer = OwnerPolicySigner(FakeCrypto)
        val signed = signer.sign(unsigned())!!
        val flipped = signed.copy(
            body = OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
        )
        assertFalse(signer.verifier()(flipped))
    }

    @Test
    fun a_resequenced_owner_entry_stops_verifying() {
        val signer = OwnerPolicySigner(FakeCrypto)
        val signed = signer.sign(unsigned())!!
        assertFalse(signer.verifier()(signed.copy(policySequence = 99)))
    }

    @Test
    fun owner_entry_fields_cannot_be_confused_across_their_boundaries() {
        val a = unsigned().copy(id = LedgerEntryId("e1"), signerNpub = "npub1owner")
        val b = unsigned().copy(id = LedgerEntryId("e1npub1"), signerNpub = "owner")
        assertNotEquals(
            OwnerPolicyCanonicalForm.bytes(a).decodeToString(),
            OwnerPolicyCanonicalForm.bytes(b).decodeToString(),
        )
    }

    @Test
    fun an_unavailable_signer_produces_no_owner_entry() {
        assertNull(OwnerPolicySigner(UnavailableCrypto).sign(unsigned()))
    }
}
