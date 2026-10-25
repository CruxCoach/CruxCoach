package com.cruxcoach.domain.sharing

/**
 * Resolves "may this (audience, category, record) be shared?" (FEAT-073 §2.3).
 *
 * Precedence, highest first:
 *
 *  1. fixed rules: age gate, category available, ceiling, supported audience,
 *     "never share this record" — always deny;
 *  2. a record rule for this person — deny before allow;
 *  3. a category rule for this person — deny before allow;
 *  4. the person's template;
 *  5. otherwise deny.
 *
 * An expired rule resolves as if it were absent. Health data ([HealthClass.HEALTH])
 * reaches a person only through an explicit allow that expires; a template
 * never grants it.
 *
 * It answers "what did the owner decide". Whether the person accepted and the
 * outgoing direction is on is the store's answer; whether a dated record is
 * inside the period is the projection's ([periodDays]).
 */
object AudienceResolver {

    fun resolve(
        policy: SharePolicy,
        audience: Audience,
        category: ShareCategory,
        record: ObjectId? = null,
        now: Long,
    ): ShareDecision {
        if (!policy.ageConfirmed) return deny(ShareDecisionSource.AGE_GATE)
        if (category !in policy.available) return deny(ShareDecisionSource.NOT_AVAILABLE)
        if (audience.level.rank > category.ceiling.rank) return deny(ShareDecisionSource.CEILING)
        val peer = (audience as? Audience.Person)?.peer ?: return deny(ShareDecisionSource.AUDIENCE_NOT_SUPPORTED)
        if (record != null && record in policy.neverShare) return deny(ShareDecisionSource.NEVER_SHARE)
        val person = policy.persons[peer] ?: return deny(ShareDecisionSource.PERSON_UNKNOWN)

        record?.let { id ->
            person.recordRules[RecordRuleKey(id, category)]?.takeIf { it.activeAt(now) }?.let { rule ->
                return explicit(rule, category, ShareDecisionSource.RECORD_DENY, ShareDecisionSource.RECORD_ALLOW)
            }
        }
        person.categoryRules[category]?.takeIf { it.activeAt(now) }?.let { rule ->
            return explicit(rule, category, ShareDecisionSource.PERSON_DENY, ShareDecisionSource.PERSON_ALLOW)
        }
        val template = policy.templates[person.template] ?: return deny(ShareDecisionSource.NO_GRANT)
        if (category !in template.categories) return deny(ShareDecisionSource.NO_GRANT)
        if (category.health == HealthClass.HEALTH) return deny(ShareDecisionSource.HEALTH_NEEDS_EXPIRY)
        return ShareDecision(AccessEffect.ALLOW, ShareDecisionSource.TEMPLATE)
    }

    /** The period a dated category reaches back for [peer], or null if the person is unknown. */
    fun periodDays(policy: SharePolicy, peer: PeerId): Int? {
        val person = policy.persons[peer] ?: return null
        return person.periodDays ?: policy.templates[person.template]?.periodDays ?: SharePeriod.DEFAULT
    }

    /**
     * The reverse question for one record: which of [persons] it reaches
     * ("who sees this?"). Only people the caller considers connected with their
     * outgoing direction on should be passed in.
     */
    fun whoSees(
        policy: SharePolicy,
        category: ShareCategory,
        record: ObjectId?,
        persons: Collection<PeerId>,
        now: Long,
    ): List<PeerId> = persons.filter { resolve(policy, Audience.Person(it), category, record, now).isAllowed }

    /** The earliest moment any active rule in the policy expires after [now], for scheduling. */
    fun nextExpiry(policy: SharePolicy, now: Long): Long? =
        policy.persons.values
            .flatMap { it.categoryRules.values + it.recordRules.values }
            .mapNotNull { it.expiresAt }
            .filter { it > now }
            .minOrNull()

    private fun explicit(
        rule: ShareRule,
        category: ShareCategory,
        denySource: ShareDecisionSource,
        allowSource: ShareDecisionSource,
    ): ShareDecision = when (rule.effect) {
        AccessEffect.DENY -> deny(denySource)
        AccessEffect.ALLOW ->
            if (category.health == HealthClass.HEALTH && rule.expiresAt == null) deny(ShareDecisionSource.HEALTH_NEEDS_EXPIRY)
            else ShareDecision(AccessEffect.ALLOW, allowSource, rule.expiresAt)
    }

    private fun deny(source: ShareDecisionSource) = ShareDecision(AccessEffect.DENY, source)
}
