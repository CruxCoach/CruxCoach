package com.cruxcoach.domain.sharing

/**
 * Answers the only question that matters at read time: *may this device of this
 * peer see this thing right now?*
 *
 * It composes two independent layers, and the order is deliberate:
 *
 *  1. [SharingPolicyResolver] — what the owner decided. A **deny** here is
 *     returned immediately, so narrowing a grant or denying one object takes
 *     effect at once and locally, without waiting for anybody to acknowledge
 *     anything.
 *  2. the relationship gates below — recipient consent, device authorisation,
 *     resource epoch, delivery confirmation, revocation. These can only ever
 *     turn an allow into a deny, never the other way round.
 *
 * Every gate names itself in [AccessDecision.source] so the UI can explain the
 * outcome instead of just showing a padlock.
 */
object EffectiveAccessResolver {

    fun resolve(
        policy: SharingPolicy,
        relationship: RelationshipState?,
        peer: PeerId,
        category: SharingCategory,
        objectId: ObjectId? = null,
        device: DeviceId?,
        localResourceEpoch: Long,
    ): AccessDecision {
        val decision = SharingPolicyResolver.resolve(policy, peer, category, objectId)
        if (decision.effect == AccessEffect.DENY) return decision

        if (relationship == null) {
            return deny(DecisionSource.RELATIONSHIP_UNKNOWN_FAIL_CLOSED)
        }
        if (relationship.failClosedReason != null || relationship.status == RelationshipStatus.FAIL_CLOSED) {
            return deny(DecisionSource.LEDGER_FAIL_CLOSED)
        }

        when (relationship.status) {
            RelationshipStatus.REVOKED -> return deny(DecisionSource.RELATIONSHIP_REVOKED)
            RelationshipStatus.PURGE_PENDING,
            RelationshipStatus.PURGED,
            -> return deny(DecisionSource.RELATIONSHIP_PURGE_PENDING)
            RelationshipStatus.DECLINED -> return deny(DecisionSource.RELATIONSHIP_DECLINED)
            RelationshipStatus.PENDING -> return deny(DecisionSource.RELATIONSHIP_PENDING_CONSENT)
            RelationshipStatus.ACCEPTED, RelationshipStatus.DELIVERY_UNCLEAR -> Unit
            RelationshipStatus.FAIL_CLOSED -> return deny(DecisionSource.LEDGER_FAIL_CLOSED)
        }

        // A restored install has a fresh device generation and no proof yet that
        // it has seen every revocation issued while it was away.
        if (relationship.awaitingRevokeSync) return deny(DecisionSource.AWAITING_REVOKE_SYNC)

        if (relationship.status == RelationshipStatus.DELIVERY_UNCLEAR) {
            return deny(DecisionSource.DELIVERY_UNCLEAR)
        }

        if (device == null || device !in relationship.authorisedDevices) {
            return deny(DecisionSource.DEVICE_NOT_AUTHORISED)
        }

        // Offline, only data of the locally valid epoch may be read.
        if (localResourceEpoch < relationship.resourceEpoch) {
            return deny(DecisionSource.RESOURCE_EPOCH_STALE)
        }

        if (category !in relationship.consentedCategories) {
            return deny(DecisionSource.CONSENT_EXPANSION_PENDING)
        }

        return decision
    }

    private fun deny(source: DecisionSource) = AccessDecision(AccessEffect.DENY, source)
}
