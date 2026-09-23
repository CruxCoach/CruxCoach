package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two presets with monotone inheritance and the FEAT-062 precedence chain
 * object > person > preset baseline (deny before allow).
 */
class SharingPolicyResolverTest {

    private val alice = PeerId("npub1alice")

    private fun baselines(
        acquaintances: Set<SharingCategory> = emptySet(),
        friends: Set<SharingCategory> = emptySet(),
    ) = CircleBaselines(
        mapOf(
            SharingCircle.ACQUAINTANCES to acquaintances,
            SharingCircle.FRIENDS to friends,
        )
    )

    @Test
    fun circles_are_ordered_from_widest_to_narrowest() {
        assertEquals(
            listOf(SharingCircle.ACQUAINTANCES, SharingCircle.FRIENDS),
            SharingCircle.entries.sortedBy { it.rank },
        )
    }

    @Test
    fun there_are_exactly_three_categories() {
        assertEquals(
            listOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.TRAINING_HISTORY, SharingCategory.PRIVATE_NOTES),
            SharingCategory.entries,
        )
    }

    @Test
    fun a_narrower_circle_inherits_every_wider_baseline_category() {
        val b = baselines(
            acquaintances = setOf(SharingCategory.PROFILE_AND_GOALS),
            friends = setOf(SharingCategory.TRAINING_HISTORY),
        )

        assertEquals(setOf(SharingCategory.PROFILE_AND_GOALS), b.effectiveFor(SharingCircle.ACQUAINTANCES))
        assertEquals(
            setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.TRAINING_HISTORY),
            b.effectiveFor(SharingCircle.FRIENDS),
        )
    }

    @Test
    fun an_inherited_baseline_reports_the_circle_it_was_inherited_from() {
        val policy = SharingPolicy(
            baselines = baselines(acquaintances = setOf(SharingCategory.PROFILE_AND_GOALS)),
            peers = mapOf(alice to PeerPolicy(circle = SharingCircle.FRIENDS)),
        )

        val decision = SharingPolicyResolver.resolve(
            policy = policy,
            peer = alice,
            category = SharingCategory.PROFILE_AND_GOALS,
        )

        assertEquals(AccessEffect.ALLOW, decision.effect)
        assertEquals(DecisionSource.INHERITED_CIRCLE_BASELINE, decision.source)
        assertEquals(SharingCircle.ACQUAINTANCES, decision.inheritedFrom)
    }

    @Test
    fun a_category_nobody_granted_is_denied_by_default() {
        val policy = SharingPolicy(
            baselines = baselines(),
            peers = mapOf(alice to PeerPolicy(circle = SharingCircle.FRIENDS)),
        )

        val decision = SharingPolicyResolver.resolve(policy, alice, SharingCategory.PRIVATE_NOTES)

        assertEquals(AccessEffect.DENY, decision.effect)
        assertEquals(DecisionSource.NO_BASELINE_DEFAULT_DENY, decision.source)
    }

    @Test
    fun a_person_deny_beats_the_circle_baseline() {
        val policy = SharingPolicy(
            baselines = baselines(friends = setOf(SharingCategory.TRAINING_HISTORY)),
            peers = mapOf(
                alice to PeerPolicy(
                    circle = SharingCircle.FRIENDS,
                    categoryRules = mapOf(SharingCategory.TRAINING_HISTORY to AccessEffect.DENY),
                )
            ),
        )

        val decision = SharingPolicyResolver.resolve(policy, alice, SharingCategory.TRAINING_HISTORY)

        assertEquals(AccessEffect.DENY, decision.effect)
        assertEquals(DecisionSource.PERSON_DENY, decision.source)
    }

    @Test
    fun a_person_allow_beats_a_missing_baseline() {
        val policy = SharingPolicy(
            baselines = baselines(),
            peers = mapOf(
                alice to PeerPolicy(
                    circle = SharingCircle.ACQUAINTANCES,
                    categoryRules = mapOf(SharingCategory.PRIVATE_NOTES to AccessEffect.ALLOW),
                )
            ),
        )

        val decision = SharingPolicyResolver.resolve(policy, alice, SharingCategory.PRIVATE_NOTES)

        assertEquals(AccessEffect.ALLOW, decision.effect)
        assertEquals(DecisionSource.PERSON_ALLOW, decision.source)
    }

    @Test
    fun an_object_deny_beats_a_person_allow() {
        val ascent = ObjectId("ascent:1")
        val policy = SharingPolicy(
            baselines = baselines(friends = setOf(SharingCategory.TRAINING_HISTORY)),
            peers = mapOf(
                alice to PeerPolicy(
                    circle = SharingCircle.FRIENDS,
                    categoryRules = mapOf(SharingCategory.TRAINING_HISTORY to AccessEffect.ALLOW),
                    objectRules = mapOf(ObjectRuleKey(ascent, SharingCategory.TRAINING_HISTORY) to AccessEffect.DENY),
                )
            ),
        )

        val decision = SharingPolicyResolver.resolve(
            policy, alice, SharingCategory.TRAINING_HISTORY, objectId = ascent,
        )

        assertEquals(AccessEffect.DENY, decision.effect)
        assertEquals(DecisionSource.OBJECT_DENY, decision.source)
    }

    @Test
    fun an_object_allow_beats_a_person_deny() {
        val note = ObjectId("note-7")
        val policy = SharingPolicy(
            baselines = baselines(),
            peers = mapOf(
                alice to PeerPolicy(
                    circle = SharingCircle.FRIENDS,
                    categoryRules = mapOf(SharingCategory.PRIVATE_NOTES to AccessEffect.DENY),
                    objectRules = mapOf(ObjectRuleKey(note, SharingCategory.PRIVATE_NOTES) to AccessEffect.ALLOW),
                )
            ),
        )

        val decision = SharingPolicyResolver.resolve(
            policy, alice, SharingCategory.PRIVATE_NOTES, objectId = note,
        )

        assertEquals(AccessEffect.ALLOW, decision.effect)
        assertEquals(DecisionSource.OBJECT_ALLOW, decision.source)
    }

    @Test
    fun an_object_rule_only_applies_to_that_object() {
        val note = ObjectId("note-7")
        val other = ObjectId("note-8")
        val policy = SharingPolicy(
            baselines = baselines(friends = setOf(SharingCategory.PRIVATE_NOTES)),
            peers = mapOf(
                alice to PeerPolicy(
                    circle = SharingCircle.FRIENDS,
                    objectRules = mapOf(ObjectRuleKey(note, SharingCategory.PRIVATE_NOTES) to AccessEffect.DENY),
                )
            ),
        )

        assertEquals(
            AccessEffect.DENY,
            SharingPolicyResolver.resolve(policy, alice, SharingCategory.PRIVATE_NOTES, note).effect,
        )
        assertEquals(
            AccessEffect.ALLOW,
            SharingPolicyResolver.resolve(policy, alice, SharingCategory.PRIVATE_NOTES, other).effect,
        )
    }

    @Test
    fun an_unknown_peer_is_denied_fail_closed() {
        val policy = SharingPolicy(
            baselines = baselines(acquaintances = SharingCategory.entries.toSet()),
            peers = emptyMap(),
        )

        val decision = SharingPolicyResolver.resolve(policy, PeerId("npub1stranger"), SharingCategory.PROFILE_AND_GOALS)

        assertEquals(AccessEffect.DENY, decision.effect)
        assertEquals(DecisionSource.PEER_UNKNOWN_FAIL_CLOSED, decision.source)
    }
}
