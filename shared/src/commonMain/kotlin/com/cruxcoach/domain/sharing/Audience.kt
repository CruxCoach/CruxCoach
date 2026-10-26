package com.cruxcoach.domain.sharing

/**
 * "Who sees this?" (FEAT-073 §2): the audience model of personal-data sharing.
 *
 * Pure Kotlin like the rest of this package, so every rule can be tested
 * without a device, a database or a transport. The policy stays local and
 * unsigned; the transport decides who can read a message, this decides what
 * goes into it.
 */

/** How far a record reaches, narrowest first. Research is a separate axis, not a rung. */
enum class AudienceLevel(val rank: Int) {
    ONLY_ME(0),
    PERSON(1),
    GROUP(2),
    PUBLIC(3),
}

/** Someone a record can reach. Groups and public publication come with their own features (0.2.7+). */
sealed interface Audience {
    val level: AudienceLevel

    data class Person(val peer: PeerId) : Audience {
        override val level: AudienceLevel get() = AudienceLevel.PERSON
    }

    data class Group(val id: String) : Audience {
        init { require(id.isNotBlank()) { "group id must not be blank" } }
        override val level: AudienceLevel get() = AudienceLevel.GROUP
    }

    data object Public : Audience {
        override val level: AudienceLevel get() = AudienceLevel.PUBLIC
    }
}

/** GDPR Art. 9 data, data close to it, or neither (FEAT-073 §2.2, §8.3). */
enum class HealthClass { NONE, ADJACENT, HEALTH }

/**
 * The closed category catalogue. Each category has a fixed [ceiling] that no
 * rule can exceed; a new category needs a code change with its own field list.
 * [dated] categories follow a period (days back); the others are current state.
 */
enum class ShareCategory(val ceiling: AudienceLevel, val health: HealthClass, val dated: Boolean) {
    CLIMBING_PROFILE(AudienceLevel.PUBLIC, HealthClass.NONE, dated = false),
    SENDS(AudienceLevel.PUBLIC, HealthClass.NONE, dated = true),
    ATTEMPTS(AudienceLevel.GROUP, HealthClass.NONE, dated = true),
    CLIMB_NOTES(AudienceLevel.GROUP, HealthClass.NONE, dated = false),
    WEEKLY_VOLUME(AudienceLevel.GROUP, HealthClass.NONE, dated = true),
    LEADERBOARD_VALUES(AudienceLevel.PUBLIC, HealthClass.NONE, dated = false),
    TRAINING_SESSIONS(AudienceLevel.GROUP, HealthClass.NONE, dated = true),
    BENCHMARKS(AudienceLevel.PUBLIC, HealthClass.ADJACENT, dated = true),
    BODY(AudienceLevel.PERSON, HealthClass.HEALTH, dated = true),
    WELLBEING(AudienceLevel.PERSON, HealthClass.HEALTH, dated = true),
    INJURY_COMEBACK(AudienceLevel.PERSON, HealthClass.HEALTH, dated = true),
    NUTRITION(AudienceLevel.PERSON, HealthClass.ADJACENT, dated = true),
    PRESENCE(AudienceLevel.GROUP, HealthClass.NONE, dated = false),
    ;

    companion object {
        /** Categories with a source in this version; everything else resolves to deny. */
        val AVAILABLE: Set<ShareCategory> = setOf(CLIMBING_PROFILE, SENDS, ATTEMPTS, CLIMB_NOTES, WEEKLY_VOLUME)
    }
}

/** How far back a dated category reaches, in days; [ALL] means everything. */
object SharePeriod {
    const val ALL = -1
    const val DEFAULT = 30
    val CHOICES = listOf(30, 90, 365, ALL)

    fun isValid(days: Int): Boolean = days == ALL || days > 0
}

data class TemplateId(val value: String) {
    init { require(value.isNotBlank()) { "template id must not be blank" } }
}

/** A named starting point for one person ("training partner"); replaces the two circles. */
data class ShareTemplate(
    val id: TemplateId,
    val categories: Set<ShareCategory>,
    val periodDays: Int = SharePeriod.DEFAULT,
    /** Not offered yet (its categories come later). */
    val hidden: Boolean = false,
) {
    init { require(SharePeriod.isValid(periodDays)) { "invalid period" } }
}

/** The shipped templates (owner decision 2026-10-25). Health data is never part of a template. */
object ShareTemplates {
    val TRAINING_PARTNER = ShareTemplate(
        TemplateId("TRAINING_PARTNER"),
        setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.SENDS, ShareCategory.ATTEMPTS, ShareCategory.CLIMB_NOTES),
        periodDays = 30,
    )
    /** Profile & goals and how much one climbs each week, never single climbs. */
    val FAMILY = ShareTemplate(
        TemplateId("FAMILY"),
        setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.WEEKLY_VOLUME),
        periodDays = 30,
    )
    /** 0.2.8: later also training sessions and benchmarks; health only ticked one by one, with an expiry. */
    val COACH = ShareTemplate(
        TemplateId("COACH"),
        setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.SENDS, ShareCategory.ATTEMPTS, ShareCategory.CLIMB_NOTES),
        periodDays = 365,
        hidden = true,
    )
    val SHIPPED: List<ShareTemplate> = listOf(TRAINING_PARTNER, FAMILY, COACH)
}

/** One explicit decision, optionally until [expiresAt] (epoch ms, exclusive). */
data class ShareRule(val effect: AccessEffect, val expiresAt: Long? = null) {
    fun activeAt(now: Long): Boolean = expiresAt == null || now < expiresAt
}

data class RecordRuleKey(val record: ObjectId, val category: ShareCategory)

/** What the owner decided about one person. */
data class PersonPolicy(
    val template: TemplateId,
    /** Overrides the template's period for this person; null follows the template. */
    val periodDays: Int? = null,
    val categoryRules: Map<ShareCategory, ShareRule> = emptyMap(),
    val recordRules: Map<RecordRuleKey, ShareRule> = emptyMap(),
)

/** The owner's complete local policy. */
data class SharePolicy(
    val templates: Map<TemplateId, ShareTemplate> = ShareTemplates.SHIPPED.associateBy { it.id },
    val persons: Map<PeerId, PersonPolicy> = emptyMap(),
    /** "Never share this record": applies to every audience. */
    val neverShare: Set<ObjectId> = emptySet(),
    /** Self-declared age of 16 or more (FEAT-073 §2.7); nothing above "only me" without it. */
    val ageConfirmed: Boolean = false,
    val available: Set<ShareCategory> = ShareCategory.AVAILABLE,
)

/** Why the resolver decided the way it did; the UI renders it. */
enum class ShareDecisionSource {
    AGE_GATE,
    NOT_AVAILABLE,
    CEILING,
    /** Groups and public publication do not exist yet. */
    AUDIENCE_NOT_SUPPORTED,
    NEVER_SHARE,
    RECORD_DENY,
    RECORD_ALLOW,
    PERSON_DENY,
    PERSON_ALLOW,
    /** Health data reaches a person only through an explicit, expiring grant. */
    HEALTH_NEEDS_EXPIRY,
    TEMPLATE,
    NO_GRANT,
    /** The person is not in the policy: fail closed rather than guess. */
    PERSON_UNKNOWN,
}

data class ShareDecision(
    val effect: AccessEffect,
    val source: ShareDecisionSource,
    /** Set when the deciding rule expires; the recipient learns it with the data. */
    val expiresAt: Long? = null,
) {
    val isAllowed: Boolean get() = effect == AccessEffect.ALLOW
}
