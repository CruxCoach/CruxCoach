package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FEAT-062 §5: ledger bodies must survive a round trip through storage, and an
 * entry kind this build does not know must decode to something the reducer
 * fails closed on — never be silently dropped.
 */
class SharingLedgerCodecTest {

    private fun roundTrip(body: SharingLedgerBody): SharingLedgerBody {
        val (kind, json) = SharingLedgerCodec.encodeBody(body)
        return SharingLedgerCodec.decodeBody(kind, json)
    }

    @Test
    fun an_offer_round_trips_with_its_categories() {
        val body = SharingLedgerBody.RelationshipOffered(
            categories = setOf(SharingCategory.VIDEOS, SharingCategory.TRAINING_HISTORY),
        )
        assertEquals(body, roundTrip(body))
    }

    @Test
    fun a_circle_assignment_round_trips_and_is_marked_local_only() {
        val body = SharingLedgerBody.PeerCircleAssigned(SharingCircle.ACQUAINTANCES)
        assertEquals(body, roundTrip(body))
        assertTrue(body.isLocalOnly)
    }

    @Test
    fun an_offer_never_carries_the_social_circle() {
        val (_, payload) = SharingLedgerCodec.encodeBody(
            SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
        )
        assertFalse(payload.contains("circle"))
        SharingCircle.entries.forEach { assertFalse(payload.contains(it.name)) }
    }

    @Test
    fun an_offer_round_trips_its_optional_expiry() {
        val body = SharingLedgerBody.RelationshipOffered(
            categories = setOf(SharingCategory.VIDEOS),
            expiresAt = 1_900_000_000_000L,
        )
        assertEquals(body, roundTrip(body))
    }

    @Test
    fun an_acceptance_round_trips_with_its_device() {
        val body = SharingLedgerBody.RecipientAccepted(
            categories = setOf(SharingCategory.PROFILE_AND_GOALS),
            deviceId = DeviceId("dev-1"),
        )
        assertEquals(body, roundTrip(body))
    }

    @Test
    fun every_object_body_round_trips() {
        listOf(
            SharingLedgerBody.RecipientDeclined,
            SharingLedgerBody.RelationshipRevoked,
            SharingLedgerBody.PurgeRequested,
            SharingLedgerBody.PurgeCompleted,
        ).forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun every_device_body_round_trips() {
        listOf(
            SharingLedgerBody.DeviceAuthorized(DeviceId("d1")),
            SharingLedgerBody.DeviceRevoked(DeviceId("d2")),
            SharingLedgerBody.KeyDeliveryUnclear(DeviceId("d3")),
            SharingLedgerBody.KeyDeliveryConfirmed(DeviceId("d4")),
        ).forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun grant_epoch_and_restore_bodies_round_trip() {
        assertEquals(
            SharingLedgerBody.GrantChanged(setOf(SharingCategory.HEALTH_INFORMATION)),
            roundTrip(SharingLedgerBody.GrantChanged(setOf(SharingCategory.HEALTH_INFORMATION))),
        )
        assertEquals(
            SharingLedgerBody.ResourceEpochAdvanced(7),
            roundTrip(SharingLedgerBody.ResourceEpochAdvanced(7)),
        )
        assertEquals(
            SharingLedgerBody.RestoreCompleted(3),
            roundTrip(SharingLedgerBody.RestoreCompleted(3)),
        )
    }

    @Test
    fun an_entry_kind_from_a_newer_build_decodes_to_unknown() {
        val decoded = SharingLedgerCodec.decodeBody("cc.sharing.SomethingNewer", "{}")
        assertIs<SharingLedgerBody.Unknown>(decoded)
        assertEquals("cc.sharing.SomethingNewer", decoded.kind)
    }

    @Test
    fun a_corrupt_body_decodes_to_unknown_rather_than_throwing() {
        val decoded = SharingLedgerCodec.decodeBody("RelationshipOffered", "not json at all")
        assertIs<SharingLedgerBody.Unknown>(decoded)
    }

    @Test
    fun an_unknown_category_name_in_stored_json_decodes_to_unknown() {
        val decoded = SharingLedgerCodec.decodeBody(
            "RelationshipOffered",
            """{"categories":["TELEPATHY"]}""",
        )
        assertIs<SharingLedgerBody.Unknown>(decoded)
    }

    @Test
    fun an_unknown_body_that_reaches_the_reducer_fails_the_relationship_closed() {
        val reducer = SharingLedgerReducer({ true }, ownerNpub = "npub1owner")
        val entry = SharingLedgerEntry(
            id = LedgerEntryId("e1"),
            peer = PeerId("npub1alice"),
            policySequence = 1,
            authorityGeneration = 1,
            resourceEpoch = 1,
            deviceGeneration = 1,
            signerNpub = "npub1owner",
            signature = "sig",
            body = SharingLedgerCodec.decodeBody("cc.sharing.FromTheFuture", "{}"),
        )
        val state = reducer.reduce(listOf(entry)).relationships[PeerId("npub1alice")]
        assertEquals(RelationshipStatus.FAIL_CLOSED, state?.status)
    }
}
