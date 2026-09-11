package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FEAT-062 §3–6: what a peer may actually read *now*, once recipient consent,
 * device authorisation, resource epoch and revocation are composed on top of
 * the owner's policy.
 */
class EffectiveAccessResolverTest {

    private val alice = PeerId("npub1alice")
    private val phone = DeviceId("device-phone")
    private val tablet = DeviceId("device-tablet")

    private val policy = SharingPolicy(
        baselines = CircleBaselines(
            mapOf(SharingCircle.FRIENDS to setOf(SharingCategory.TRAINING_HISTORY, SharingCategory.VIDEOS)),
        ),
        peers = mapOf(alice to PeerPolicy(circle = SharingCircle.FRIENDS)),
    )

    private fun relationship(
        status: RelationshipStatus = RelationshipStatus.ACCEPTED,
        consented: Set<SharingCategory> = setOf(SharingCategory.TRAINING_HISTORY, SharingCategory.VIDEOS),
        pending: Set<SharingCategory> = emptySet(),
        devices: Set<DeviceId> = setOf(phone),
        resourceEpoch: Long = 1,
        failClosedReason: String? = null,
        awaitingRevokeSync: Boolean = false,
    ) = RelationshipState(
        peer = alice,
        status = status,
        circle = SharingCircle.FRIENDS,
        offeredCategories = consented + pending,
        consentedCategories = consented,
        pendingConsentCategories = pending,
        authorisedDevices = devices,
        resourceEpoch = resourceEpoch,
        failClosedReason = failClosedReason,
        awaitingRevokeSync = awaitingRevokeSync,
    )

    private fun resolve(
        state: RelationshipState?,
        category: SharingCategory = SharingCategory.TRAINING_HISTORY,
        device: DeviceId? = phone,
        localEpoch: Long = 1,
        objectId: ObjectId? = null,
    ) = EffectiveAccessResolver.resolve(
        policy = policy,
        relationship = state,
        peer = alice,
        category = category,
        objectId = objectId,
        device = device,
        localResourceEpoch = localEpoch,
    )

    @Test
    fun an_accepted_consented_category_on_an_authorised_device_is_released() {
        val d = resolve(relationship())
        assertEquals(AccessEffect.ALLOW, d.effect)
        assertEquals(DecisionSource.CIRCLE_BASELINE, d.source)
    }

    @Test
    fun nothing_is_released_before_the_recipient_accepts() {
        val d = resolve(relationship(status = RelationshipStatus.PENDING, consented = emptySet()))
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.RELATIONSHIP_PENDING_CONSENT, d.source)
    }

    @Test
    fun a_widened_category_stays_closed_until_the_new_consent_arrives() {
        val state = relationship(
            consented = setOf(SharingCategory.TRAINING_HISTORY),
            pending = setOf(SharingCategory.VIDEOS),
        )
        val d = resolve(state, category = SharingCategory.VIDEOS)
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.CONSENT_EXPANSION_PENDING, d.source)
    }

    @Test
    fun revocation_closes_every_category_immediately() {
        val d = resolve(relationship(status = RelationshipStatus.REVOKED, consented = emptySet()))
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.RELATIONSHIP_REVOKED, d.source)
    }

    @Test
    fun a_pending_purge_releases_nothing() {
        val d = resolve(relationship(status = RelationshipStatus.PURGE_PENDING, consented = emptySet()))
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.RELATIONSHIP_PURGE_PENDING, d.source)
    }

    @Test
    fun an_unauthorised_device_reads_nothing_even_for_a_consented_category() {
        val d = resolve(relationship(), device = tablet)
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.DEVICE_NOT_AUTHORISED, d.source)
    }

    @Test
    fun a_missing_device_context_fails_closed() {
        val d = resolve(relationship(), device = null)
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.DEVICE_NOT_AUTHORISED, d.source)
    }

    @Test
    fun offline_reads_are_limited_to_the_locally_valid_resource_epoch() {
        val d = resolve(relationship(resourceEpoch = 4), localEpoch = 3)
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.RESOURCE_EPOCH_STALE, d.source)
    }

    @Test
    fun an_unclear_delivery_is_shown_and_releases_nothing_new() {
        val d = resolve(relationship(status = RelationshipStatus.DELIVERY_UNCLEAR))
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.DELIVERY_UNCLEAR, d.source)
    }

    @Test
    fun a_fail_closed_ledger_overrides_every_grant() {
        val d = resolve(relationship(failClosedReason = "sequence gap"))
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.LEDGER_FAIL_CLOSED, d.source)
    }

    @Test
    fun a_restored_install_stays_closed_until_the_revoke_sync_completes() {
        val d = resolve(relationship(awaitingRevokeSync = true))
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.AWAITING_REVOKE_SYNC, d.source)
    }

    @Test
    fun a_relationship_the_projection_does_not_know_fails_closed() {
        val d = resolve(null)
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.RELATIONSHIP_UNKNOWN_FAIL_CLOSED, d.source)
    }

    @Test
    fun an_owner_side_restriction_beats_an_existing_consent() {
        // Consent still lists PRIVATE_NOTES, but no baseline or person rule
        // grants it any more: the local restriction wins immediately.
        val state = relationship(consented = setOf(SharingCategory.PRIVATE_NOTES))
        val d = resolve(state, category = SharingCategory.PRIVATE_NOTES)
        assertEquals(AccessEffect.DENY, d.effect)
        assertEquals(DecisionSource.NO_BASELINE_DEFAULT_DENY, d.source)
    }

    @Test
    fun an_object_deny_still_wins_after_every_gate_has_passed() {
        val d = resolve(relationship(), category = SharingCategory.VIDEOS, objectId = ObjectId("v1"))
        assertEquals(AccessEffect.ALLOW, d.effect)

        val denied = EffectiveAccessResolver.resolve(
            policy = policy.copy(
                peers = mapOf(
                    alice to PeerPolicy(
                        circle = SharingCircle.FRIENDS,
                        objectRules = mapOf(ObjectId("v1") to AccessEffect.DENY),
                    )
                )
            ),
            relationship = relationship(),
            peer = alice,
            category = SharingCategory.VIDEOS,
            objectId = ObjectId("v1"),
            device = phone,
            localResourceEpoch = 1,
        )
        assertEquals(AccessEffect.DENY, denied.effect)
        assertEquals(DecisionSource.OBJECT_DENY, denied.source)
    }
}
