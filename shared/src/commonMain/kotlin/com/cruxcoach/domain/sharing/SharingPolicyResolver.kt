package com.cruxcoach.domain.sharing

/**
 * Resolves the owner's local policy for one (person, category, record) triple.
 *
 * Precedence, highest first:
 *
 *  1. an object exception — deny before allow;
 *  2. an explicit per-person rule — deny before allow;
 *  3. the preset baseline, inherited monotonically from the wider preset;
 *  4. otherwise deny.
 *
 * It answers "what did the owner decide". Whether a friendship is active is the
 * transport's answer; whether a record is inside the chosen training period is
 * the projection's.
 */
object SharingPolicyResolver {

    fun resolve(
        policy: SharingPolicy,
        peer: PeerId,
        category: SharingCategory,
        objectId: ObjectId? = null,
    ): AccessDecision {
        val peerPolicy = policy.peers[peer]
            ?: return AccessDecision(AccessEffect.DENY, DecisionSource.PEER_UNKNOWN_FAIL_CLOSED)

        objectId?.let { id ->
            when (peerPolicy.objectRules[ObjectRuleKey(id, category)]) {
                AccessEffect.DENY -> return AccessDecision(AccessEffect.DENY, DecisionSource.OBJECT_DENY)
                AccessEffect.ALLOW -> return AccessDecision(AccessEffect.ALLOW, DecisionSource.OBJECT_ALLOW)
                null -> Unit
            }
        }

        when (peerPolicy.categoryRules[category]) {
            AccessEffect.DENY -> return AccessDecision(AccessEffect.DENY, DecisionSource.PERSON_DENY)
            AccessEffect.ALLOW -> return AccessDecision(AccessEffect.ALLOW, DecisionSource.PERSON_ALLOW)
            null -> Unit
        }

        val granting = policy.baselines.grantingCircle(peerPolicy.circle, category)
            ?: return AccessDecision(AccessEffect.DENY, DecisionSource.NO_BASELINE_DEFAULT_DENY)

        return if (granting == peerPolicy.circle) {
            AccessDecision(AccessEffect.ALLOW, DecisionSource.CIRCLE_BASELINE)
        } else {
            AccessDecision(AccessEffect.ALLOW, DecisionSource.INHERITED_CIRCLE_BASELINE, inheritedFrom = granting)
        }
    }
}
