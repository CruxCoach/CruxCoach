package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §5 + §10: *who* signed an entry decides whether it means anything.
 *
 * A verified signature only proves the bytes were not altered. It says nothing
 * about authority. Without a role check, the owner could sign a recipient's
 * acceptance — consent forged by the very party it is supposed to constrain —
 * and any key at all could sign an owner action.
 */
class SharingLedgerAuthorityTest {

    private val owner = "npub1owner"
    private val alice = PeerId("npub1alice")
    private val stranger = "npub1mallory"
    private val phone = DeviceId("dev-phone")
    private val tablet = DeviceId("dev-tablet")

    private val reducer = SharingLedgerReducer({ true }, ownerNpub = owner)

    private fun entry(
        id: String,
        sequence: Long,
        body: SharingLedgerBody,
        signer: String = owner,
        authorityGeneration: Long = 1,
        deviceGeneration: Long = 1,
        resourceEpoch: Long = 1,
    ) = SharingLedgerEntry(
        id = LedgerEntryId(id),
        peer = alice,
        policySequence = sequence,
        authorityGeneration = authorityGeneration,
        resourceEpoch = resourceEpoch,
        deviceGeneration = deviceGeneration,
        signerNpub = signer,
        signature = "sig-$id",
        body = body,
    )

    private fun state(vararg e: SharingLedgerEntry) = reducer.reduce(e.toList()).relationships[alice]

    private val offer = entry(
        "e1", 1,
        SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
    )
    private fun accept(id: String, seq: Long, signer: String = alice.value, gen: Long = 1) = entry(
        id, seq,
        SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone),
        signer = signer,
        authorityGeneration = gen,
    )

    // ------------------------------------------------------------- roles

    @Test
    fun an_owner_action_signed_by_a_stranger_fails_closed() {
        val s = assertNotNull(state(entry("e1", 1, SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)), signer = stranger)))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun an_owner_action_signed_by_the_peer_fails_closed() {
        val s = assertNotNull(state(offer, entry("e2", 2, SharingLedgerBody.RelationshipRevoked, signer = alice.value)))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun an_acceptance_signed_by_the_owner_fails_closed() {
        // The whole point of consent is that the owner cannot give it.
        val s = assertNotNull(state(offer, accept("e2", 2, signer = owner)))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
        assertTrue(s.consentedCategories.isEmpty())
    }

    @Test
    fun a_decline_signed_by_the_owner_fails_closed() {
        val s = assertNotNull(state(offer, entry("e2", 2, SharingLedgerBody.RecipientDeclined, signer = owner)))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun an_acceptance_signed_by_a_stranger_fails_closed() {
        val s = assertNotNull(state(offer, accept("e2", 2, signer = stranger)))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun an_acceptance_signed_by_the_peer_is_accepted() {
        val s = assertNotNull(state(offer, accept("e2", 2)))
        assertEquals(RelationshipStatus.ACCEPTED, s.status)
        assertEquals(setOf(SharingCategory.VIDEOS), s.consentedCategories)
    }

    @Test
    fun a_decline_signed_by_the_peer_is_a_decline() {
        val s = assertNotNull(state(offer, entry("e2", 2, SharingLedgerBody.RecipientDeclined, signer = alice.value)))
        assertEquals(RelationshipStatus.DECLINED, s.status)
    }

    @Test
    fun the_local_only_circle_assignment_is_an_owner_action() {
        val s = assertNotNull(
            state(entry("e1", 1, SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), signer = alice.value))
        )
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    // ------------------------------------------------- device generation

    @Test
    fun an_entry_from_an_older_device_generation_fails_closed() {
        val restore = entry("e3", 3, SharingLedgerBody.RestoreCompleted(2), deviceGeneration = 2)
        val stale = entry("e4", 4, SharingLedgerBody.RelationshipRevoked, deviceGeneration = 1)
        val s = assertNotNull(state(offer, accept("e2", 2), restore, stale))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun a_device_generation_that_jumps_without_a_restore_fails_closed() {
        val jump = entry("e3", 3, SharingLedgerBody.RelationshipRevoked, deviceGeneration = 7)
        val s = assertNotNull(state(offer, accept("e2", 2), jump))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun a_restore_whose_envelope_disagrees_with_its_body_fails_closed() {
        val mismatched = entry("e3", 3, SharingLedgerBody.RestoreCompleted(5), deviceGeneration = 2)
        val s = assertNotNull(state(offer, accept("e2", 2), mismatched))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun a_restore_that_does_not_advance_the_device_generation_fails_closed() {
        val notAdvancing = entry("e3", 3, SharingLedgerBody.RestoreCompleted(1), deviceGeneration = 1)
        val s = assertNotNull(state(offer, accept("e2", 2), notAdvancing))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun a_restore_advancing_by_one_is_accepted_and_drops_the_devices() {
        val restore = entry("e3", 3, SharingLedgerBody.RestoreCompleted(2), deviceGeneration = 2)
        val s = assertNotNull(state(offer, accept("e2", 2), restore))
        assertEquals(2L, s.deviceGeneration)
        assertTrue(s.authorisedDevices.isEmpty())
        assertTrue(s.awaitingRevokeSync)
    }

    // ---------------------------------------------- authority generation

    @Test
    fun a_higher_authority_generation_does_not_inherit_consent() {
        val reOffer = entry(
            "e3", 3,
            SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
            authorityGeneration = 2,
        )
        val s = assertNotNull(state(offer, accept("e2", 2), reOffer))

        assertEquals(2L, s.authorityGeneration)
        assertTrue(s.consentedCategories.isEmpty(), "a new authority must not inherit an old consent")
        assertEquals(RelationshipStatus.PENDING, s.status)
    }

    @Test
    fun a_higher_authority_generation_does_not_inherit_devices() {
        val reOffer = entry(
            "e3", 3,
            SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
            authorityGeneration = 2,
        )
        val s = assertNotNull(state(offer, accept("e2", 2), reOffer))
        assertTrue(s.authorisedDevices.isEmpty(), "a new authority must not inherit an authorised device")
    }

    @Test
    fun a_higher_authority_generation_keeps_device_tombstones_sticky() {
        val revokeDevice = entry("e3", 3, SharingLedgerBody.DeviceRevoked(tablet))
        val reOffer = entry(
            "e4", 4,
            SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
            authorityGeneration = 2,
        )
        val reAuthorise = entry(
            "e5", 5, SharingLedgerBody.DeviceAuthorized(tablet),
            signer = alice.value, authorityGeneration = 2,
        )
        val s = assertNotNull(state(offer, accept("e2", 2), revokeDevice, reOffer, reAuthorise))

        assertTrue(tablet in s.revokedDevices)
        assertTrue(tablet !in s.authorisedDevices, "a revoked device stays revoked across a new authority")
    }

    @Test
    fun a_new_authority_can_be_re_established_explicitly() {
        val reOffer = entry(
            "e3", 3,
            SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
            authorityGeneration = 2,
        )
        val reAccept = accept("e4", 4, gen = 2)
        val s = assertNotNull(state(offer, accept("e2", 2), reOffer, reAccept))

        assertEquals(RelationshipStatus.ACCEPTED, s.status)
        assertEquals(setOf(SharingCategory.VIDEOS), s.consentedCategories)
        assertEquals(setOf(phone), s.authorisedDevices)
    }

    @Test
    fun a_higher_authority_generation_never_un_revokes_a_relationship() {
        val revoked = entry("e3", 3, SharingLedgerBody.RelationshipRevoked)
        val reOffer = entry(
            "e4", 4,
            SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
            authorityGeneration = 2,
        )
        val s = assertNotNull(state(offer, accept("e2", 2), revoked, reOffer))
        assertEquals(RelationshipStatus.REVOKED, s.status)
    }

    // ------------------------------------------------- device authorisation

    @Test
    fun a_device_authorisation_signed_by_the_owner_fails_closed() {
        // Authorising a device is the peer proving that *this* device is theirs.
        // If the owner could sign it, "every device is authorised separately"
        // would mean nothing: the owner would simply name any device.
        val ownerAuthorised = entry("e3", 3, SharingLedgerBody.DeviceAuthorized(tablet), signer = owner)
        val s = assertNotNull(state(offer, accept("e2", 2), ownerAuthorised))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun a_device_authorisation_signed_by_a_stranger_fails_closed() {
        val forged = entry("e3", 3, SharingLedgerBody.DeviceAuthorized(tablet), signer = stranger)
        val s = assertNotNull(state(offer, accept("e2", 2), forged))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun a_device_authorisation_signed_by_the_peer_is_accepted() {
        val peerAuthorised = entry("e3", 3, SharingLedgerBody.DeviceAuthorized(tablet), signer = alice.value)
        val s = assertNotNull(state(offer, accept("e2", 2), peerAuthorised))
        assertEquals(setOf(phone, tablet), s.authorisedDevices)
    }

    @Test
    fun revoking_a_device_stays_an_owner_action() {
        val ownerRevoked = entry("e3", 3, SharingLedgerBody.DeviceRevoked(phone), signer = owner)
        val s = assertNotNull(state(offer, accept("e2", 2), ownerRevoked))
        assertTrue(phone in s.revokedDevices)
        assertTrue(phone !in s.authorisedDevices)
    }

    @Test
    fun a_device_revocation_signed_by_the_peer_fails_closed() {
        val peerRevoked = entry("e3", 3, SharingLedgerBody.DeviceRevoked(phone), signer = alice.value)
        val s = assertNotNull(state(offer, accept("e2", 2), peerRevoked))
        assertEquals(RelationshipStatus.FAIL_CLOSED, s.status)
    }

    @Test
    fun a_revoked_device_stays_revoked_even_when_the_peer_re_authorises_it() {
        val revoked = entry("e3", 3, SharingLedgerBody.DeviceRevoked(tablet), signer = owner)
        val peerRetry = entry("e4", 4, SharingLedgerBody.DeviceAuthorized(tablet), signer = alice.value)
        val s = assertNotNull(state(offer, accept("e2", 2), revoked, peerRetry))

        assertTrue(tablet in s.revokedDevices)
        assertTrue(tablet !in s.authorisedDevices, "the owner's revocation outranks the peer's retry")
    }
}
