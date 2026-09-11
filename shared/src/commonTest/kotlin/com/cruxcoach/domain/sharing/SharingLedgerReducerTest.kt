package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §5–6: the signed append-only permission ledger and its idempotent,
 * fail-closed reduction.
 */
class SharingLedgerReducerTest {

    private val alice = PeerId("npub1alice")
    private val phone = DeviceId("device-phone")
    private val tablet = DeviceId("device-tablet")

    private val owner = "npub1owner"
    private val reducer = SharingLedgerReducer(verifier = AcceptAllSignatures, ownerNpub = owner)

    private object AcceptAllSignatures : LedgerSignatureVerifier {
        override fun verify(entry: SharingLedgerEntry): Boolean = true
    }

    private object RejectAllSignatures : LedgerSignatureVerifier {
        override fun verify(entry: SharingLedgerEntry): Boolean = false
    }

    private fun entry(
        id: String,
        sequence: Long,
        body: SharingLedgerBody,
        authorityGeneration: Long = 1,
        resourceEpoch: Long = 1,
        deviceGeneration: Long = 1,
        peer: PeerId = alice,
        // Owner by default; recipient bodies are signed by the peer, because
        // only the peer may answer an offer on their own behalf.
        signer: String = "npub1owner",
    ) = SharingLedgerEntry(
        id = LedgerEntryId(id),
        peer = peer,
        policySequence = sequence,
        authorityGeneration = authorityGeneration,
        resourceEpoch = resourceEpoch,
        deviceGeneration = deviceGeneration,
        signerNpub = signer,
        signature = "sig-$id",
        body = body,
    )

    private val offered = entry(
        "e1", 1,
        SharingLedgerBody.RelationshipOffered(
            categories = setOf(SharingCategory.PROFILE_AND_GOALS),
        ),
    )
    private val accepted = entry(
        "e2", 2,
        SharingLedgerBody.RecipientAccepted(
            categories = setOf(SharingCategory.PROFILE_AND_GOALS),
            deviceId = phone,
        ),
        signer = alice.value,
    )

    private fun state(vararg entries: SharingLedgerEntry): RelationshipState? =
        reducer.reduce(entries.toList()).relationships[alice]

    // ---------------------------------------------------------------- basics

    @Test
    fun an_offer_alone_is_pending_and_releases_nothing() {
        val s = assertNotNull(state(offered))
        assertEquals(RelationshipStatus.PENDING, s.status)
        assertEquals(setOf(SharingCategory.PROFILE_AND_GOALS), s.offeredCategories)
        assertTrue(s.consentedCategories.isEmpty())
    }

    @Test
    fun acceptance_moves_the_relationship_to_accepted() {
        val s = assertNotNull(state(offered, accepted))
        assertEquals(RelationshipStatus.ACCEPTED, s.status)
        assertEquals(setOf(SharingCategory.PROFILE_AND_GOALS), s.consentedCategories)
    }

    @Test
    fun a_decline_is_terminal_and_consents_to_nothing() {
        val declined = entry("e2b", 2, SharingLedgerBody.RecipientDeclined, signer = alice.value)
        val s = assertNotNull(state(offered, declined))
        assertEquals(RelationshipStatus.DECLINED, s.status)
        assertTrue(s.consentedCategories.isEmpty())
    }

    @Test
    fun the_owner_side_circle_comes_from_its_own_local_only_entry() {
        val circled = entry("e0", 1, SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS))
        val offer = entry("e1", 2, SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)))
        val s = assertNotNull(state(circled, offer))
        assertEquals(SharingCircle.FRIENDS, s.circle)
        assertTrue(circled.body.isLocalOnly)
        assertFalse(offer.body.isLocalOnly)
    }

    // ------------------------------------------------- idempotence / ordering

    @Test
    fun applying_the_same_entry_twice_changes_nothing() {
        val once = reducer.reduce(listOf(offered, accepted))
        val twice = reducer.reduce(listOf(offered, accepted, accepted))
        assertEquals(once.relationships, twice.relationships)
    }

    @Test
    fun a_duplicate_entry_id_with_different_content_fails_closed() {
        val impostor = entry("e2", 2, SharingLedgerBody.RelationshipRevoked)
        val s = assertNotNull(state(offered, accepted, impostor))
        assertNotNull(s.failClosedReason)
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun out_of_order_delivery_reduces_to_the_same_state_as_in_order() {
        val inOrder = reducer.reduce(listOf(offered, accepted))
        val shuffled = reducer.reduce(listOf(accepted, offered))
        assertEquals(inOrder.relationships, shuffled.relationships)
    }

    @Test
    fun a_replayed_older_entry_never_resurrects_an_older_state() {
        val revoked = entry("e3", 3, SharingLedgerBody.RelationshipRevoked)
        val s = assertNotNull(state(offered, accepted, revoked, accepted))
        assertEquals(RelationshipStatus.REVOKED, s.status)
    }

    /**
     * Two entries at one number are a *branch*, which is the whole point of the
     * DAG. The fold no longer refuses them; which one stands is decided by the
     * resolver, per scope, and one id carrying two histories is still refused.
     */
    @Test
    fun two_entries_claiming_one_sequence_are_a_branch_rather_than_a_failure() {
        val conflicting = entry("e2x", 2, SharingLedgerBody.RelationshipRevoked)

        val s = assertNotNull(state(offered, accepted, conflicting))

        assertNull(s.failClosedReason)
    }

    /**
     * A gap is caught at admission, where the parent is visible.
     *
     * The fold used to demand strict, gap-free numbering — a linear chain's
     * invariant. In a DAG a scope whose branch lost leaves a gap behind, so the
     * fold cannot tell one from the other.
     */
    @Test
    fun a_gap_in_the_sequence_is_refused_at_admission() {
        val far = entry("e9", 9, SharingLedgerBody.RelationshipRevoked)
            .copy(parent = LedgerEntryId("e2"))

        val verdict = SharingLedgerAdmission.check(listOf(offered, accepted), far, { true }, owner)

        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("sequence", ignoreCase = true))
    }

    @Test
    fun an_unverifiable_signature_fails_closed() {
        val strict = SharingLedgerReducer(verifier = RejectAllSignatures, ownerNpub = owner)
        val s = strict.reduce(listOf(offered)).relationships[alice]
        assertNotNull(s)
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    // ------------------------------------------------------ consent expansion

    @Test
    fun widening_the_grant_needs_fresh_consent_before_it_releases() {
        val widened = entry(
            "e3", 3,
            SharingLedgerBody.GrantChanged(
                setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.HEALTH_INFORMATION),
            ),
        )
        val s = assertNotNull(state(offered, accepted, widened))

        assertEquals(RelationshipStatus.ACCEPTED, s.status)
        assertEquals(setOf(SharingCategory.PROFILE_AND_GOALS), s.consentedCategories)
        assertEquals(setOf(SharingCategory.HEALTH_INFORMATION), s.pendingConsentCategories)
    }

    @Test
    fun a_second_acceptance_confirms_the_widened_grant() {
        val widened = entry(
            "e3", 3,
            SharingLedgerBody.GrantChanged(
                setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.HEALTH_INFORMATION),
            ),
        )
        val reAccepted = entry(
            "e4", 4,
            SharingLedgerBody.RecipientAccepted(
                categories = setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.HEALTH_INFORMATION),
                deviceId = phone,
            ),
            signer = alice.value,
        )
        val s = assertNotNull(state(offered, accepted, widened, reAccepted))

        assertEquals(
            setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.HEALTH_INFORMATION),
            s.consentedCategories,
        )
        assertTrue(s.pendingConsentCategories.isEmpty())
    }

    @Test
    fun narrowing_the_grant_takes_effect_at_once_without_asking_anybody() {
        val narrowed = entry("e3", 3, SharingLedgerBody.GrantChanged(emptySet()))
        val s = assertNotNull(state(offered, accepted, narrowed))

        assertTrue(s.consentedCategories.isEmpty())
        assertTrue(s.pendingConsentCategories.isEmpty())
        assertEquals(RelationshipStatus.ACCEPTED, s.status)
    }

    @Test
    fun a_recipient_can_never_consent_to_more_than_was_offered() {
        val overreaching = entry(
            "e2y", 2,
            SharingLedgerBody.RecipientAccepted(
                categories = setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.PRIVATE_NOTES),
                deviceId = phone,
            ),
            signer = alice.value,
        )
        val s = assertNotNull(state(offered, overreaching))
        assertEquals(setOf(SharingCategory.PROFILE_AND_GOALS), s.consentedCategories)
    }

    // -------------------------------------------------------------- devices

    @Test
    fun the_accepting_device_is_the_only_authorised_one() {
        val s = assertNotNull(state(offered, accepted))
        assertEquals(setOf(phone), s.authorisedDevices)
    }

    @Test
    fun a_new_device_gets_no_access_until_it_is_authorised_explicitly() {
        val s = assertNotNull(state(offered, accepted))
        assertFalse(tablet in s.authorisedDevices)
    }

    @Test
    fun each_device_is_revocable_on_its_own() {
        val addTablet = entry("e3", 3, SharingLedgerBody.DeviceAuthorized(tablet), signer = alice.value)
        val dropPhone = entry("e4", 4, SharingLedgerBody.DeviceRevoked(phone))
        val s = assertNotNull(state(offered, accepted, addTablet, dropPhone))

        assertEquals(setOf(tablet), s.authorisedDevices)
        assertEquals(RelationshipStatus.ACCEPTED, s.status)
    }

    @Test
    fun a_revoked_device_tombstone_is_sticky_and_cannot_be_re_authorised() {
        val dropPhone = entry("e3", 3, SharingLedgerBody.DeviceRevoked(phone))
        val reAdd = entry("e4", 4, SharingLedgerBody.DeviceAuthorized(phone), signer = alice.value)
        val s = assertNotNull(state(offered, accepted, dropPhone, reAdd))

        assertFalse(phone in s.authorisedDevices)
        assertTrue(phone in s.revokedDevices)
    }

    // --------------------------------------------------- revoke / purge flow

    @Test
    fun revocation_is_visible_and_stops_every_category() {
        val revoked = entry("e3", 3, SharingLedgerBody.RelationshipRevoked)
        val s = assertNotNull(state(offered, accepted, revoked))

        assertEquals(RelationshipStatus.REVOKED, s.status)
        assertTrue(s.consentedCategories.isEmpty())
    }

    @Test
    fun a_purge_request_is_visible_as_its_own_state() {
        val revoked = entry("e3", 3, SharingLedgerBody.RelationshipRevoked)
        val purge = entry("e4", 4, SharingLedgerBody.PurgeRequested)
        val s = assertNotNull(state(offered, accepted, revoked, purge))
        assertEquals(RelationshipStatus.PURGE_PENDING, s.status)
    }

    @Test
    fun a_revoked_relationship_never_returns_to_accepted() {
        val revoked = entry("e3", 3, SharingLedgerBody.RelationshipRevoked)
        val reAccept = entry(
            "e4", 4,
            SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.PROFILE_AND_GOALS), phone),
            signer = alice.value,
        )
        val s = assertNotNull(state(offered, accepted, revoked, reAccept))
        assertEquals(RelationshipStatus.REVOKED, s.status)
    }

    // ----------------------------------------- delivery / epochs / generations

    @Test
    fun an_unconfirmed_key_delivery_shows_as_delivery_unclear() {
        val widened = entry(
            "e3", 3,
            SharingLedgerBody.GrantChanged(
                setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.VIDEOS),
            ),
        )
        val reAccept = entry(
            "e4", 4,
            SharingLedgerBody.RecipientAccepted(
                setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.VIDEOS), phone,
            ),
            signer = alice.value,
        )
        val unclear = entry("e5", 5, SharingLedgerBody.KeyDeliveryUnclear(phone))
        val s = assertNotNull(state(offered, accepted, widened, reAccept, unclear))
        assertEquals(RelationshipStatus.DELIVERY_UNCLEAR, s.status)
    }

    @Test
    fun a_confirmed_delivery_clears_the_unclear_state() {
        val unclear = entry("e3", 3, SharingLedgerBody.KeyDeliveryUnclear(phone))
        val confirmed = entry("e4", 4, SharingLedgerBody.KeyDeliveryConfirmed(phone))
        val s = assertNotNull(state(offered, accepted, unclear, confirmed))
        assertEquals(RelationshipStatus.ACCEPTED, s.status)
    }

    @Test
    fun the_resource_epoch_only_ever_moves_forward() {
        val advance = entry("e3", 3, SharingLedgerBody.ResourceEpochAdvanced(5), resourceEpoch = 5)
        val backwards = entry("e4", 4, SharingLedgerBody.ResourceEpochAdvanced(2), resourceEpoch = 2)
        val s = assertNotNull(state(offered, accepted, advance, backwards))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun an_entry_from_a_superseded_authority_generation_is_ignored() {
        val newAuthority = entry("e3", 3, SharingLedgerBody.RelationshipRevoked, authorityGeneration = 2)
        val stale = entry(
            "e4", 4,
            SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.PROFILE_AND_GOALS), phone),
            authorityGeneration = 1,
            signer = alice.value,
        )
        val s = assertNotNull(state(offered, accepted, newAuthority, stale))
        assertEquals(RelationshipStatus.REVOKED, s.status)
    }

    @Test
    fun a_restore_mints_a_new_device_generation_and_stays_fail_closed_until_resync() {
        val restored = entry(
            "e3", 3,
            SharingLedgerBody.RestoreCompleted(newDeviceGeneration = 2),
            deviceGeneration = 2,
        )
        val s = assertNotNull(state(offered, accepted, restored))

        assertEquals(2, s.deviceGeneration)
        assertTrue(s.awaitingRevokeSync)
        assertTrue(s.authorisedDevices.isEmpty())
    }

    @Test
    fun an_unknown_entry_kind_fails_closed_instead_of_being_skipped() {
        val unknown = entry("e3", 3, SharingLedgerBody.Unknown("cc.future.v2"))
        val s = assertNotNull(state(offered, accepted, unknown))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun expiry_is_carried_in_the_model_without_being_enforced_in_v1() {
        val withExpiry = entry("e1x", 1, SharingLedgerBody.RelationshipOffered(
            categories = setOf(SharingCategory.VIDEOS),
            expiresAt = 1_900_000_000_000L,
        ))
        val s = assertNotNull(state(withExpiry))
        assertEquals(1_900_000_000_000L, s.expiresAt)
        assertEquals(RelationshipStatus.PENDING, s.status)
    }

    @Test
    fun an_empty_ledger_yields_no_relationships_at_all() {
        assertNull(reducer.reduce(emptyList()).relationships[alice])
    }
}
