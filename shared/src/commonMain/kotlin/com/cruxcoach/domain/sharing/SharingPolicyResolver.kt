package com.cruxcoach.domain.sharing

/**
 * Resolves the owner's *policy* for one (peer, category, object) triple.
 *
 * Precedence, highest first:
 *
 *  1. an object exception — deny before allow;
 *  2. an explicit per-person rule — deny before allow;
 *  3. the circle baseline, inherited monotonically from wider circles;
 *  4. otherwise deny.
 *
 * This layer answers "what did the owner decide". It deliberately does **not**
 * answer "may this peer read the data right now" — recipient consent, device
 * authorisation, resource epoch and revocation live in
 * [EffectiveAccessResolver], which composes this decision with the relationship
 * state. Keeping them apart is what lets the UI say *both* "you shared this"
 * and "it is not released yet, because consent is missing".
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
