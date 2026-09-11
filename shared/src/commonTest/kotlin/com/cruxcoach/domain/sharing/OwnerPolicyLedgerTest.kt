package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §2: circle baselines and per-person/per-object exceptions are
 * permissions, so they belong in a signed append-only ledger too.
 *
 * They used to be written straight into mutable SQL rows: unsigned, with no
 * history, no ordering guarantee and nothing to detect tampering. Anything that
 * could write the database could silently widen every grant, and there was no
 * way to tell afterwards.
 */
class OwnerPolicyLedgerTest {

    private val owner = "npub1owner"
    private val stranger = "npub1mallory"
    private val alice = PeerId("npub1alice")
    private val video = ObjectId("video-1")

    private val reducer = OwnerPolicyReducer({ true }, ownerNpub = owner)

    private fun entry(
        id: String,
        sequence: Long,
        body: OwnerPolicyBody,
        signer: String = owner,
        authorityGeneration: Long = 1,
    ) = OwnerPolicyEntry(
        id = LedgerEntryId(id),
        policySequence = sequence,
        authorityGeneration = authorityGeneration,
        signerNpub = signer,
        signature = "sig-$id",
        body = body,
    )

    private fun baseline(id: String, seq: Long, circle: SharingCircle, category: SharingCategory, granted: Boolean, gen: Long = 1) =
        entry(id, seq, OwnerPolicyBody.CircleBaselineSet(circle, category, granted), authorityGeneration = gen)

    private fun reduce(vararg e: OwnerPolicyEntry) = reducer.reduce(e.toList())

    // ------------------------------------------------------------- basics

    @Test
    fun an_empty_ledger_grants_nothing() {
        val s = reduce()
        assertNull(s.failClosedReason)
        SharingCircle.entries.forEach { assertTrue(s.baselines.effectiveFor(it).isEmpty()) }
    }

    @Test
    fun a_baseline_entry_grants_a_category_to_a_circle() {
        val s = reduce(baseline("e1", 1, SharingCircle.ACQUAINTANCES, SharingCategory.TRAINING_HISTORY, true))
        assertEquals(setOf(SharingCategory.TRAINING_HISTORY), s.baselines.explicitFor(SharingCircle.ACQUAINTANCES))
        assertTrue(SharingCategory.TRAINING_HISTORY in s.baselines.effectiveFor(SharingCircle.FRIENDS))
    }

    @Test
    fun a_later_entry_can_take_a_baseline_away_again() {
        val s = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            baseline("e2", 2, SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
        )
        assertTrue(s.baselines.explicitFor(SharingCircle.FRIENDS).isEmpty())
    }

    @Test
    fun a_person_rule_and_an_object_rule_are_recorded() {
        val s = reduce(
            entry("e1", 1, OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY)),
            entry("e2", 2, OwnerPolicyBody.ObjectRuleSet(alice, video, SharingCategory.VIDEOS, AccessEffect.ALLOW)),
        )
        assertEquals(AccessEffect.DENY, s.peerRules[alice]?.get(SharingCategory.VIDEOS))
        assertEquals(AccessEffect.ALLOW, s.objectRules[alice]?.get(ObjectRuleKey(video, SharingCategory.VIDEOS)))
    }

    @Test
    fun category_collisions_and_removal_cannot_erase_another_categories_deny() {
        val noteRule = entry("n", 2, OwnerPolicyBody.ObjectRuleSet(alice, video, SharingCategory.PRIVATE_NOTES, AccessEffect.DENY))
        val videoRule = entry("v", 3, OwnerPolicyBody.ObjectRuleSet(alice, video, SharingCategory.VIDEOS, AccessEffect.ALLOW))
        val base = baseline("base", 1, SharingCircle.FRIENDS, SharingCategory.PRIVATE_NOTES, true)
        val s = reduce(base, noteRule, videoRule)
        assertNull(s.failClosedReason)
        val policy = s.toPolicy(mapOf(alice to SharingCircle.FRIENDS))
        assertEquals(AccessEffect.DENY, SharingPolicyResolver.resolve(policy, alice, SharingCategory.PRIVATE_NOTES, video).effect)
        assertEquals(AccessEffect.ALLOW, SharingPolicyResolver.resolve(policy, alice, SharingCategory.VIDEOS, video).effect)
        val removed = reduce(base, noteRule, videoRule,
            entry("remove", 4, OwnerPolicyBody.ObjectRuleSet(alice, video, SharingCategory.VIDEOS, null)))
        assertEquals(AccessEffect.DENY, SharingPolicyResolver.resolve(removed.toPolicy(mapOf(alice to SharingCircle.FRIENDS)),
            alice, SharingCategory.PRIVATE_NOTES, video).effect)
        assertEquals(1, removed.objectRules[alice]?.size)
    }

    @Test
    fun a_rule_can_be_cleared_back_to_the_circle_default() {
        val s = reduce(
            entry("e1", 1, OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY)),
            entry("e2", 2, OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, null)),
        )
        assertNull(s.peerRules[alice]?.get(SharingCategory.VIDEOS))
    }

    @Test
    fun the_reduced_state_becomes_a_policy_the_resolver_can_use() {
        val s = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            entry("e2", 2, OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY)),
        )
        val policy = s.toPolicy(circles = mapOf(alice to SharingCircle.FRIENDS))

        val decision = SharingPolicyResolver.resolve(policy, alice, SharingCategory.VIDEOS)
        assertEquals(AccessEffect.DENY, decision.effect)
        assertEquals(DecisionSource.PERSON_DENY, decision.source)
    }

    // ------------------------------------------------- ordering, idempotence

    @Test
    fun the_reduction_is_order_independent() {
        val a = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            baseline("e2", 2, SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
        )
        val b = reducer.reduce(
            listOf(
                baseline("e2", 2, SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
                baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            )
        )
        assertEquals(a.baselines, b.baselines)
    }

    @Test
    fun a_replayed_entry_changes_nothing() {
        val once = reduce(baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true))
        val twice = reducer.reduce(
            listOf(
                baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
                baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            )
        )
        assertEquals(once.baselines, twice.baselines)
    }

    // ------------------------------------------------------- fail-closed

    @Test
    fun an_unsigned_or_unverifiable_entry_fails_closed_and_grants_nothing() {
        val strict = OwnerPolicyReducer({ false }, ownerNpub = owner)
        val s = strict.reduce(listOf(baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)))
        assertNotNull(s.failClosedReason)
        assertTrue(s.baselines.effectiveFor(SharingCircle.FRIENDS).isEmpty(), "a fail-closed policy grants nothing")
    }

    @Test
    fun an_entry_signed_by_anybody_but_the_owner_fails_closed() {
        val s = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            entry("e2", 2, OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.ALLOW), signer = stranger),
        )
        assertNotNull(s.failClosedReason)
        assertTrue(s.baselines.effectiveFor(SharingCircle.FRIENDS).isEmpty())
    }

    /**
     * A gap is caught where it is now decidable: admission.
     *
     * The fold used to demand a strict, gap-free sequence, which is a linear
     * chain's invariant. In a DAG a gap is ordinary — a scope whose branch lost
     * leaves one behind — so the fold cannot tell a gap from a resolved
     * conflict. Admission can, because it sees the parent.
     */
    @Test
    fun a_gap_in_the_sequence_is_refused_at_admission() {
        val first = baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
        val jumped = baseline("e9", 9, SharingCircle.FRIENDS, SharingCategory.PRIVATE_NOTES, true)
            .copy(parent = LedgerEntryId("e1"))

        val verdict = OwnerPolicyAdmission.check(listOf(first), jumped, { true }, owner)

        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("sequence", ignoreCase = true))
    }

    @Test
    fun a_history_that_does_not_start_at_one_is_refused_at_admission() {
        val second = baseline("e2", 2, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)
            .copy(parent = null)

        assertIs<LedgerAdmission.Reject>(OwnerPolicyAdmission.check(emptyList(), second, { true }, owner))
    }

    /**
     * Two entries at one number are a *branch*, and branches are the point.
     * What is still impossible is one id carrying two histories — see below.
     */
    @Test
    fun two_entries_at_one_sequence_are_a_branch_rather_than_a_failure() {
        val s = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            baseline("e1x", 1, SharingCircle.FRIENDS, SharingCategory.PRIVATE_NOTES, true),
        )

        assertNull(s.failClosedReason)
    }

    @Test
    fun one_id_carrying_two_different_bodies_fails_closed() {
        val s = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
        )
        assertNotNull(s.failClosedReason)
    }

    @Test
    fun an_unknown_entry_kind_fails_closed_rather_than_being_skipped() {
        val s = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            entry("e2", 2, OwnerPolicyBody.Unknown("cc.owner.FromTheFuture")),
        )
        assertNotNull(s.failClosedReason)
        assertTrue(s.baselines.effectiveFor(SharingCircle.FRIENDS).isEmpty())
    }

    // ------------------------------------------------- authority generation

    @Test
    fun an_entry_from_a_superseded_authority_generation_is_ignored() {
        val s = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            baseline("e2", 2, SharingCircle.FRIENDS, SharingCategory.PRIVATE_NOTES, true, gen = 2),
            baseline("e3", 3, SharingCircle.FRIENDS, SharingCategory.HEALTH_INFORMATION, true, gen = 1),
        )
        assertTrue(SharingCategory.HEALTH_INFORMATION !in s.baselines.effectiveFor(SharingCircle.FRIENDS))
    }

    @Test
    fun a_higher_authority_generation_does_not_inherit_the_old_grants() {
        val s = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            entry("e2", 2, OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.PRIVATE_NOTES, AccessEffect.ALLOW)),
            baseline("e3", 3, SharingCircle.FRIENDS, SharingCategory.TRAINING_HISTORY, true, gen = 2),
        )
        assertTrue(
            SharingCategory.VIDEOS !in s.baselines.effectiveFor(SharingCircle.FRIENDS),
            "a new authority must re-establish its baselines explicitly",
        )
        assertNull(s.peerRules[alice]?.get(SharingCategory.PRIVATE_NOTES))
        assertTrue(SharingCategory.TRAINING_HISTORY in s.baselines.effectiveFor(SharingCircle.FRIENDS))
    }

    // --------------------------------------------------------------- audit

    @Test
    fun the_history_is_kept_so_a_change_can_be_accounted_for() {
        val s = reduce(
            baseline("e1", 1, SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            baseline("e2", 2, SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
        )
        assertEquals(2, s.appliedCount)
        assertEquals(2L, s.lastSequence)
    }
}
