package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FEAT-073 §2: levels, ceilings, templates, expiry, never-share and the age gate. */
class AudienceResolverTest {
    private val anna = PeerId("a".repeat(64))
    private val ben = PeerId("b".repeat(64))
    private val now = 1_800_000_000_000L
    private val send = ObjectId("ascent:1")
    private val audiences = listOf(Audience.Person(anna), Audience.Group("crew"), Audience.Public)

    private fun policy(
        person: PersonPolicy? = PersonPolicy(ShareTemplates.TRAINING_PARTNER.id),
        neverShare: Set<ObjectId> = emptySet(),
        available: Set<ShareCategory> = ShareCategory.AVAILABLE,
    ) = SharePolicy(
        persons = person?.let { mapOf(anna to it) }.orEmpty(),
        neverShare = neverShare,
        ageConfirmed = true,
        available = available,
    )

    private fun resolve(p: SharePolicy, category: ShareCategory, record: ObjectId? = null, audience: Audience = Audience.Person(anna), at: Long = now) =
        AudienceResolver.resolve(p, audience, category, record, at)

    @Test fun a_fresh_install_shares_nothing_with_anyone() {
        val fresh = SharePolicy()
        for (audience in audiences) for (category in ShareCategory.entries) {
            assertFalse(AudienceResolver.resolve(fresh, audience, category, send, now).isAllowed, "$audience $category")
        }
        // Even with the age confirmed, nobody is known yet.
        assertEquals(ShareDecisionSource.PERSON_UNKNOWN, resolve(SharePolicy(ageConfirmed = true), ShareCategory.CLIMBING_PROFILE).source)
    }

    @Test fun nothing_above_only_me_without_the_age_confirmation() {
        val p = policy().copy(ageConfirmed = false)
        assertEquals(ShareDecisionSource.AGE_GATE, resolve(p, ShareCategory.CLIMBING_PROFILE).source)
    }

    @Test fun no_combination_of_rules_exceeds_a_ceiling() {
        val effects = listOf(null, ShareRule(AccessEffect.ALLOW), ShareRule(AccessEffect.ALLOW, now + 1), ShareRule(AccessEffect.DENY))
        for (category in ShareCategory.entries) for (person in effects) for (record in effects) for (inTemplate in listOf(false, true)) {
            val template = ShareTemplate(TemplateId("T"), if (inTemplate) setOf(category) else emptySet())
            val rules = PersonPolicy(
                template.id,
                categoryRules = person?.let { mapOf(category to it) }.orEmpty(),
                recordRules = record?.let { mapOf(RecordRuleKey(send, category) to it) }.orEmpty(),
            )
            val p = SharePolicy(mapOf(template.id to template), mapOf(anna to rules), ageConfirmed = true, available = ShareCategory.entries.toSet())
            for (audience in audiences) {
                val decision = AudienceResolver.resolve(p, audience, category, send, now)
                if (audience.level.rank > category.ceiling.rank) {
                    assertEquals(ShareDecisionSource.CEILING, decision.source, "$category $audience")
                }
                if (audience !is Audience.Person) assertFalse(decision.isAllowed, "no groups or public yet")
            }
        }
    }

    @Test fun health_data_reaches_one_person_only_with_an_expiring_grant() {
        val all = ShareCategory.entries.toSet()
        for (category in ShareCategory.entries.filter { it.health == HealthClass.HEALTH }) {
            assertEquals(AudienceLevel.PERSON, category.ceiling)
            val inTemplate = ShareTemplate(TemplateId("T"), setOf(category))
            fun grantedBy(rule: ShareRule?) = SharePolicy(
                mapOf(inTemplate.id to inTemplate),
                mapOf(anna to PersonPolicy(inTemplate.id, categoryRules = rule?.let { mapOf(category to it) }.orEmpty())),
                ageConfirmed = true, available = all,
            )
            assertEquals(ShareDecisionSource.HEALTH_NEEDS_EXPIRY, resolve(grantedBy(null), category).source, "template never grants $category")
            assertEquals(ShareDecisionSource.HEALTH_NEEDS_EXPIRY, resolve(grantedBy(ShareRule(AccessEffect.ALLOW)), category).source)
            val granted = resolve(grantedBy(ShareRule(AccessEffect.ALLOW, now + 1_000)), category)
            assertTrue(granted.isAllowed)
            assertEquals(now + 1_000, granted.expiresAt)
            assertFalse(resolve(grantedBy(ShareRule(AccessEffect.ALLOW, now + 1_000)), category, at = now + 1_000).isAllowed, "expired")
        }
        assertTrue(ShareTemplates.SHIPPED.none { t -> t.categories.any { it.health == HealthClass.HEALTH } })
    }

    @Test fun never_share_wins_over_every_other_rule() {
        val allowed = PersonPolicy(
            ShareTemplates.TRAINING_PARTNER.id,
            categoryRules = mapOf(ShareCategory.SENDS to ShareRule(AccessEffect.ALLOW)),
            recordRules = mapOf(RecordRuleKey(send, ShareCategory.SENDS) to ShareRule(AccessEffect.ALLOW)),
        )
        val p = policy(allowed, neverShare = setOf(send))
        assertEquals(ShareDecisionSource.NEVER_SHARE, resolve(p, ShareCategory.SENDS, send).source)
        assertTrue(resolve(p, ShareCategory.SENDS, ObjectId("ascent:2")).isAllowed)
    }

    @Test fun precedence_record_then_person_then_template() {
        val p = policy(
            PersonPolicy(
                ShareTemplates.TRAINING_PARTNER.id,
                categoryRules = mapOf(ShareCategory.SENDS to ShareRule(AccessEffect.DENY), ShareCategory.ATTEMPTS to ShareRule(AccessEffect.ALLOW)),
                recordRules = mapOf(
                    RecordRuleKey(send, ShareCategory.SENDS) to ShareRule(AccessEffect.ALLOW),
                    RecordRuleKey(send, ShareCategory.ATTEMPTS) to ShareRule(AccessEffect.DENY),
                ),
            ),
        )
        assertEquals(ShareDecisionSource.RECORD_ALLOW, resolve(p, ShareCategory.SENDS, send).source)
        assertEquals(ShareDecisionSource.RECORD_DENY, resolve(p, ShareCategory.ATTEMPTS, send).source)
        assertEquals(ShareDecisionSource.PERSON_DENY, resolve(p, ShareCategory.SENDS, ObjectId("ascent:2")).source)
        assertEquals(ShareDecisionSource.PERSON_ALLOW, resolve(p, ShareCategory.ATTEMPTS, ObjectId("bid:2")).source)
        assertEquals(ShareDecisionSource.TEMPLATE, resolve(p, ShareCategory.CLIMB_NOTES).source)
    }

    @Test fun an_expired_rule_resolves_as_if_it_were_absent() {
        val p = policy(
            PersonPolicy(
                ShareTemplates.TRAINING_PARTNER.id,
                categoryRules = mapOf(ShareCategory.CLIMB_NOTES to ShareRule(AccessEffect.DENY, expiresAt = now)),
                recordRules = mapOf(RecordRuleKey(send, ShareCategory.SENDS) to ShareRule(AccessEffect.DENY, expiresAt = now + 10)),
            ),
        )
        assertEquals(ShareDecisionSource.TEMPLATE, resolve(p, ShareCategory.CLIMB_NOTES).source, "expired at now")
        assertEquals(ShareDecisionSource.RECORD_DENY, resolve(p, ShareCategory.SENDS, send).source)
        assertEquals(ShareDecisionSource.TEMPLATE, resolve(p, ShareCategory.SENDS, send, at = now + 10).source)
        assertEquals(now + 10, AudienceResolver.nextExpiry(p, now))
        assertNull(AudienceResolver.nextExpiry(p, now + 10))
    }

    @Test fun categories_without_a_source_are_never_shared() {
        val family = policy(PersonPolicy(ShareTemplates.FAMILY.id))
        assertTrue(resolve(family, ShareCategory.WEEKLY_VOLUME).isAllowed)
        assertTrue(resolve(family, ShareCategory.CLIMBING_PROFILE).isAllowed)
        val asked = policy(PersonPolicy(ShareTemplates.FAMILY.id, categoryRules = mapOf(ShareCategory.TRAINING_SESSIONS to ShareRule(AccessEffect.ALLOW))))
        assertEquals(ShareDecisionSource.NOT_AVAILABLE, resolve(asked, ShareCategory.TRAINING_SESSIONS).source)
        assertEquals(setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.SENDS, ShareCategory.ATTEMPTS, ShareCategory.CLIMB_NOTES,
            ShareCategory.WEEKLY_VOLUME), ShareCategory.AVAILABLE)
    }

    @Test fun the_shipped_templates_are_the_owner_decision() {
        assertEquals(
            setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.SENDS, ShareCategory.ATTEMPTS, ShareCategory.CLIMB_NOTES),
            ShareTemplates.TRAINING_PARTNER.categories,
        )
        assertEquals(30, ShareTemplates.TRAINING_PARTNER.periodDays)
        assertEquals(setOf(ShareCategory.CLIMBING_PROFILE, ShareCategory.WEEKLY_VOLUME), ShareTemplates.FAMILY.categories)
        assertTrue(!ShareTemplates.FAMILY.hidden, "weekly volume has a source")
        assertTrue(ShareTemplates.COACH.hidden)
        assertEquals(365, ShareTemplates.COACH.periodDays)
        assertEquals(listOf("TRAINING_PARTNER", "FAMILY", "COACH"), ShareTemplates.SHIPPED.map { it.id.value })
    }

    @Test fun period_follows_the_person_then_the_template() {
        assertEquals(30, AudienceResolver.periodDays(policy(), anna))
        assertEquals(SharePeriod.ALL, AudienceResolver.periodDays(policy(PersonPolicy(ShareTemplates.TRAINING_PARTNER.id, periodDays = SharePeriod.ALL)), anna))
        assertEquals(365, AudienceResolver.periodDays(policy(PersonPolicy(ShareTemplates.COACH.id)), anna))
        assertNull(AudienceResolver.periodDays(policy(), ben))
    }

    @Test fun who_sees_a_record_lists_exactly_the_people_it_reaches() {
        val p = SharePolicy(
            persons = mapOf(
                anna to PersonPolicy(ShareTemplates.TRAINING_PARTNER.id),
                ben to PersonPolicy(ShareTemplates.TRAINING_PARTNER.id, recordRules = mapOf(RecordRuleKey(send, ShareCategory.SENDS) to ShareRule(AccessEffect.DENY))),
            ),
            ageConfirmed = true,
        )
        assertEquals(listOf(anna), AudienceResolver.whoSees(p, ShareCategory.SENDS, send, listOf(anna, ben), now))
        assertEquals(listOf(anna, ben), AudienceResolver.whoSees(p, ShareCategory.SENDS, ObjectId("ascent:2"), listOf(anna, ben), now))
        assertEquals(emptyList(), AudienceResolver.whoSees(p.copy(neverShare = setOf(send)), ShareCategory.SENDS, send, listOf(anna, ben), now))
    }

    @Test fun ceilings_match_the_catalogue() {
        val expected = mapOf(
            ShareCategory.CLIMBING_PROFILE to AudienceLevel.PUBLIC, ShareCategory.SENDS to AudienceLevel.PUBLIC,
            ShareCategory.ATTEMPTS to AudienceLevel.GROUP, ShareCategory.CLIMB_NOTES to AudienceLevel.GROUP,
            ShareCategory.WEEKLY_VOLUME to AudienceLevel.GROUP, ShareCategory.LEADERBOARD_VALUES to AudienceLevel.PUBLIC,
            ShareCategory.TRAINING_SESSIONS to AudienceLevel.GROUP, ShareCategory.BENCHMARKS to AudienceLevel.PUBLIC,
            ShareCategory.BODY to AudienceLevel.PERSON, ShareCategory.WELLBEING to AudienceLevel.PERSON,
            ShareCategory.INJURY_COMEBACK to AudienceLevel.PERSON, ShareCategory.NUTRITION to AudienceLevel.PERSON,
            ShareCategory.PRESENCE to AudienceLevel.GROUP,
        )
        assertEquals(expected, ShareCategory.entries.associateWith { it.ceiling })
    }
}
