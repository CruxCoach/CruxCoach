package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FEAT-062 §1–2: exclusive circles, monotone baseline inheritance and the
 * precedence chain object > person > circle baseline (deny before allow).
 */
class SharingPolicyResolverTest {

    private val alice = PeerId("npub1alice")

    private fun baselines(
        allOthers: Set<SharingCategory> = emptySet(),
        acquaintances: Set<SharingCategory> = emptySet(),
        friends: Set<SharingCategory> = emptySet(),
    ) = CircleBaselines(
        mapOf(
            SharingCircle.ALL_OTHER_USERS to allOthers,
            SharingCircle.ACQUAINTANCES to acquaintances,
            SharingCircle.FRIENDS to friends,
        )
    )

    @Test
    fun circles_are_ordered_from_widest_to_narrowest() {
        assertEquals(
            listOf(
                SharingCircle.ALL_OTHER_USERS,
                SharingCircle.ACQUAINTANCES,
                SharingCircle.FRIENDS,
            ),
            SharingCircle.entries.sortedBy { it.rank },
        )
    }

    @Test
    fun there_are_exactly_five_categories() {
        assertEquals(5, SharingCategory.entries.size)
    }

    @Test
    fun a_narrower_circle_inherits_every_wider_baseline_category() {
        val b = baselines(
            allOthers = setOf(SharingCategory.PROFILE_AND_GOALS),
            acquaintances = setOf(SharingCategory.TRAINING_HISTORY),
            friends = setOf(SharingCategory.VIDEOS),
        )

        assertEquals(setOf(SharingCategory.PROFILE_AND_GOALS), b.effectiveFor(SharingCircle.ALL_OTHER_USERS))
        assertEquals(
            setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.TRAINING_HISTORY),
            b.effectiveFor(SharingCircle.ACQUAINTANCES),
        )
        assertEquals(
            setOf(
                SharingCategory.PROFILE_AND_GOALS,
                SharingCategory.TRAINING_HISTORY,
                SharingCategory.VIDEOS,
            ),
            b.effectiveFor(SharingCircle.FRIENDS),
        )
    }

    @Test
    fun an_inherited_baseline_reports_the_circle_it_was_inherited_from() {
        val policy = SharingPolicy(
            baselines = baselines(allOthers = setOf(SharingCategory.PROFILE_AND_GOALS)),
            peers = mapOf(alice to PeerPolicy(circle = SharingCircle.FRIENDS)),
        )

        val decision = SharingPolicyResolver.resolve(
            policy = policy,
            peer = alice,
            category = SharingCategory.PROFILE_AND_GOALS,
        )

        assertEquals(AccessEffect.ALLOW, decision.effect)
        assertEquals(DecisionSource.INHERITED_CIRCLE_BASELINE, decision.source)
        assertEquals(SharingCircle.ALL_OTHER_USERS, decision.inheritedFrom)
    }

    @Test
    fun a_category_nobody_granted_is_denied_by_default() {
        val policy = SharingPolicy(
            baselines = baselines(),
            peers = mapOf(alice to PeerPolicy(circle = SharingCircle.FRIENDS)),
        )

        val decision = SharingPolicyResolver.resolve(policy, alice, SharingCategory.HEALTH_INFORMATION)

        assertEquals(AccessEffect.DENY, decision.effect)
        assertEquals(DecisionSource.NO_BASELINE_DEFAULT_DENY, decision.source)
    }

    @Test
    fun a_person_deny_beats_the_circle_baseline() {
        val policy = SharingPolicy(
            baselines = baselines(friends = setOf(SharingCategory.VIDEOS)),
            peers = mapOf(
                alice to PeerPolicy(
                    circle = SharingCircle.FRIENDS,
                    categoryRules = mapOf(SharingCategory.VIDEOS to AccessEffect.DENY),
                )
            ),
        )

        val decision = SharingPolicyResolver.resolve(policy, alice, SharingCategory.VIDEOS)

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
        val video = ObjectId("video-1")
        val policy = SharingPolicy(
            baselines = baselines(friends = setOf(SharingCategory.VIDEOS)),
            peers = mapOf(
                alice to PeerPolicy(
                    circle = SharingCircle.FRIENDS,
                    categoryRules = mapOf(SharingCategory.VIDEOS to AccessEffect.ALLOW),
                    objectRules = mapOf(ObjectRuleKey(video, SharingCategory.VIDEOS) to AccessEffect.DENY),
                )
            ),
        )

        val decision = SharingPolicyResolver.resolve(
            policy, alice, SharingCategory.VIDEOS, objectId = video,
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
            baselines = baselines(allOthers = SharingCategory.entries.toSet()),
            peers = emptyMap(),
        )

        val decision = SharingPolicyResolver.resolve(policy, PeerId("npub1stranger"), SharingCategory.VIDEOS)

        assertEquals(AccessEffect.DENY, decision.effect)
        assertEquals(DecisionSource.PEER_UNKNOWN_FAIL_CLOSED, decision.source)
    }
}
