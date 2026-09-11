package com.cruxcoach.domain.sharing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: causality comes from the *signed* act, and a malformed DAG
 * decides nothing.
 *
 * ## Why the act's parent and not the entry's
 *
 * The resolver was deriving "what superseded what" by walking the entry chain
 * and taking the nearest ancestor that happened to share a scope. That is a
 * guess assembled from unsigned structure: entries interleave scopes, so which
 * ancestor comes "nearest" depends on what unrelated changes were written in
 * between. The act carries a parent its device actually signed, naming the act
 * it was built on — that is the statement of causality, and it is the only one
 * anybody put their name to.
 *
 * ## Why a malformed DAG must decide nothing
 *
 * Every failure here is a history that cannot be read the same way twice: a
 * missing subject, an act pointing into another scope, a cycle, a sequence that
 * does not follow its parent. Resolving what is left would silently hand
 * somebody a *truncated* history — the same shape as a revocation that was
 * quietly dropped. So the whole projection fails closed, and nothing is
 * released on the strength of a history nobody can reconstruct.
 */
class AuthorityDagValidationTest {

    private companion object {
        val ALICE = PeerId("npub1alice")
        val LAPTOP = AuthorityDeviceId("aaaa-laptop")
        val PHONE = AuthorityDeviceId("bbbb-phone")
        val SCOPE_A = AuthorityScope.peer(ALICE)
        val SCOPE_B = AuthorityScope.peerDevice(ALICE, DeviceId("dev-1"))
    }

    private val manifest = DeviceAuthorityState(
        authorityGeneration = 1,
        head = LedgerEntryId("m-1"),
        devices = listOf(LAPTOP, PHONE).associateWith {
            DeviceRecord(it, "pk-${it.value}", DeviceRole.TRUSTED, 1)
        },
    )

    private fun entry(id: String, body: SharingLedgerBody, parent: String?, sequence: Long) =
        SharingLedgerEntry(
            id = LedgerEntryId(id),
            peer = ALICE,
            policySequence = sequence,
            parent = parent?.let { LedgerEntryId(it) },
            authorityGeneration = 1,
            resourceEpoch = 1,
            deviceGeneration = 1,
            signerNpub = "npub1owner",
            signature = "sig",
            body = body,
        )

    /** An act whose parent is the *act* it was built on, as its device signed it. */
    private fun act(
        subject: String,
        body: SharingLedgerBody,
        device: AuthorityDeviceId,
        actParent: String?,
    ): AuthorityAttestation {
        val required = AuthorityPairing.requiredFor(ALICE, body)!!
        return AuthorityAttestation(
            id = LedgerEntryId("act-$subject"),
            scope = required.scope,
            subject = LedgerEntryId(subject),
            device = device,
            parent = actParent?.let { LedgerEntryId("act-$it") },
            manifestContext = FIXTURE_MANIFEST_CONTEXT,
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "pk-${device.value}:act-$subject",
        )
    }

    private val revoke = SharingLedgerBody.RelationshipRevoked
    private val grant = SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS))
    private val circle = SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS)
    private val deviceRevoke = SharingLedgerBody.DeviceRevoked(DeviceId("dev-1"))

    // ------------------------------------- causality comes from the signed act

    /**
     * The regression the blocker names.
     *
     * Scope A: a restrictive change, then a permissive one that its device
     * signed as following it. Scope B is interleaved between them in the entry
     * chain, which is exactly the case where "nearest same-scope entry
     * ancestor" and "the act's own parent" come apart. The permissive successor
     * has to win, because its device said it was built on the restrictive one.
     */
    private fun mergedScopesThenSuccessor(): Pair<List<SharingLedgerEntry>, List<AuthorityAttestation>> {
        val root = entry("e0", circle, null, 1)
        val aRestrictive = entry("e-a1", revoke, "e0", 2)
        val bIndependent = entry("e-b1", deviceRevoke, "e0", 2)
        // Its *entry* parent is the B entry — that is just where it was
        // written — while its *act* names the A act it follows.
        val aPermissive = entry("e-a2", grant, "e-b1", 3)
        return listOf(root, aRestrictive, bIndependent, aPermissive) to listOf(
            act("e0", circle, LAPTOP, null),
            act("e-a1", revoke, PHONE, "e0"),
            act("e-b1", deviceRevoke, PHONE, null),
            act("e-a2", grant, LAPTOP, "e-a1"),
        )
    }

    @Test
    fun a_permissive_successor_beats_the_restrictive_act_it_was_built_on() {
        val (entries, acts) = mergedScopesThenSuccessor()

        listOf(entries to acts, entries.reversed() to acts.reversed()).forEach { (e, a) ->
            val ids = AuthorityBranchResolver.history(e, trusted(a, manifest)).map { it.id }
            assertTrue(LedgerEntryId("e-a2") in ids, "the successor stands")
            assertTrue(
                LedgerEntryId("e-a1") in ids,
                "and the act it was built on stays in its history",
            )
            assertTrue(LedgerEntryId("e-b1") in ids, "the independent scope is untouched")
        }
    }

    @Test
    fun the_same_holds_for_the_current_authority() {
        val (_, acts) = mergedScopesThenSuccessor()
        val subjects = setOf("e0", "e-a1", "e-b1", "e-a2").map { LedgerEntryId(it) }.toSet()

        val winners = AuthorityLedger.winners(trusted(acts, manifest), subjects)

        assertEquals(LedgerEntryId("act-e-a2"), winners[SCOPE_A]?.id)
        assertEquals(LedgerEntryId("act-e-b1"), winners[SCOPE_B]?.id)
    }

    @Test
    fun the_winner_does_not_depend_on_order() {
        val (entries, acts) = mergedScopesThenSuccessor()
        val expected = AuthorityBranchResolver.history(entries, trusted(acts, manifest)).map { it.id }

        val random = Random(20620819)
        repeat(100) {
            assertEquals(
                expected,
                AuthorityBranchResolver.history(entries.shuffled(random), trusted(acts.shuffled(random), manifest))
                    .map { it.id },
            )
        }
    }

    // ------------------------------------------------- the validation itself

    private fun validate(entries: List<SharingLedgerEntry>, acts: List<AuthorityAttestation>) =
        AuthorityDagValidation.check(
            acts = acts,
            knownSubjects = entries.map { it.id }.toSet(),
            requirements = entries.mapNotNull { e ->
                AuthorityPairing.requiredFor(e.peer, e.body)?.let { e.id to it }
            }.toMap(),
        ) { entries.associate { e -> e.id to (e.parent to e.policySequence) } }

    private val wellFormed = mergedScopesThenSuccessor()

    @Test
    fun a_well_formed_dag_passes() {
        assertNull(validate(wellFormed.first, wellFormed.second))
    }

    @Test
    fun an_act_whose_subject_is_missing_fails_closed() {
        val (entries, acts) = wellFormed
        val orphan = act("nowhere", grant, LAPTOP, null)

        val reason = assertNotNull(validate(entries, acts + orphan))
        assertTrue(reason.contains("subject", ignoreCase = true), reason)
    }

    @Test
    fun two_acts_for_one_subject_fail_closed() {
        val (entries, acts) = wellFormed
        val second = act("e-a1", revoke, LAPTOP, null).copy(id = LedgerEntryId("act-dup"))

        val reason = assertNotNull(validate(entries, acts + second))
        assertTrue(reason.contains("one", ignoreCase = true), reason)
    }

    /** The act must say exactly what the body requires — see AuthorityPairing. */
    @Test
    fun an_act_that_does_not_match_its_body_fails_closed() {
        val (entries, acts) = wellFormed
        val mislabelled = acts.map {
            if (it.subject == LedgerEntryId("e-a2")) it.copy(effect = AuthorityEffect.RESTRICTIVE) else it
        }

        assertNotNull(validate(entries, mislabelled))
    }

    @Test
    fun an_act_from_the_superseded_scope_encoding_fails_closed() {
        val (entries, acts) = wellFormed
        val legacy = acts.map {
            if (it.subject == LedgerEntryId("e-b1")) it.copy(scope = AuthorityScope("peer|npub1alice")) else it
        }

        val reason = assertNotNull(validate(entries, legacy))
        assertTrue(reason.contains("scope", ignoreCase = true), reason)
    }

    @Test
    fun an_act_parent_in_another_scope_fails_closed() {
        val (entries, acts) = wellFormed
        val crossed = acts.map {
            if (it.subject == LedgerEntryId("e-a2")) it.copy(parent = LedgerEntryId("act-e-b1")) else it
        }

        val reason = assertNotNull(validate(entries, crossed))
        assertTrue(reason.contains("scope", ignoreCase = true), reason)
    }

    @Test
    fun an_act_parent_that_is_not_stored_fails_closed() {
        val (entries, acts) = wellFormed
        val dangling = acts.map {
            if (it.subject == LedgerEntryId("e-a2")) it.copy(parent = LedgerEntryId("act-nowhere")) else it
        }

        assertNotNull(validate(entries, dangling))
    }

    @Test
    fun a_cycle_among_acts_fails_closed() {
        val (entries, acts) = wellFormed
        val looped = acts.map {
            when (it.subject) {
                LedgerEntryId("e-a1") -> it.copy(parent = LedgerEntryId("act-e-a2"))
                else -> it
            }
        }

        val reason = assertNotNull(validate(entries, looped))
        assertTrue(reason.contains("cycle", ignoreCase = true), reason)
    }

    @Test
    fun an_entry_parent_that_is_not_stored_fails_closed() {
        val (entries, acts) = wellFormed
        val broken = entries.map {
            if (it.id == LedgerEntryId("e-a2")) it.copy(parent = LedgerEntryId("nowhere")) else it
        }

        assertNotNull(validate(broken, acts))
    }

    @Test
    fun a_sequence_that_does_not_follow_its_parent_fails_closed() {
        val (entries, acts) = wellFormed
        val jumped = entries.map {
            if (it.id == LedgerEntryId("e-a2")) it.copy(policySequence = 9) else it
        }

        val reason = assertNotNull(validate(jumped, acts))
        assertTrue(reason.contains("sequence", ignoreCase = true), reason)
    }

    /** A fork is the point: two entries on one parent share a number. */
    @Test
    fun two_entries_forking_on_one_parent_are_well_formed() {
        val root = entry("e0", circle, null, 1)
        val a = entry("e-a", revoke, "e0", 2)
        val b = entry("e-b", grant, "e0", 2)
        val entries = listOf(root, a, b)
        val acts = listOf(
            act("e0", circle, LAPTOP, null),
            act("e-a", revoke, PHONE, "e0"),
            act("e-b", grant, LAPTOP, "e0"),
        )

        assertNull(validate(entries, acts))
    }

    // ------------------------------------------ what a violation costs

    @Test
    fun a_malformed_dag_projects_nothing_rather_than_part_of_itself() {
        val (entries, acts) = wellFormed
        val looped = acts.map {
            if (it.subject == LedgerEntryId("e-a1")) it.copy(parent = LedgerEntryId("act-e-a2")) else it
        }

        assertEquals(
            emptyList(),
            AuthorityBranchResolver.history(entries, trusted(looped, manifest)),
            "a truncated history is indistinguishable from a dropped revocation",
        )
        assertEquals(
            emptyMap(),
            AuthorityLedger.winners(trusted(looped, manifest), entries.map { it.id }.toSet()),
        )
    }

    /**
     * An orphan is caught by the *global* check, not the per-ledger one.
     *
     * A branch projection is handed every act the install holds, including the
     * other ledger's, and cannot tell "belongs elsewhere" from "belongs
     * nowhere" — only a caller whose known subjects span both can. So the
     * projection scopes itself to its own subjects and
     * [AuthorityLedger.winners] refuses the estate outright.
     */
    @Test
    fun a_missing_subject_leaves_no_current_authority() {
        val (entries, acts) = wellFormed
        val orphan = act("nowhere", grant, LAPTOP, null)

        assertEquals(
            emptyMap(),
            AuthorityLedger.winners(trusted(acts + orphan, manifest), entries.map { it.id }.toSet()),
        )
    }

    @Test
    fun an_act_about_another_ledger_does_not_disturb_this_one() {
        val (entries, acts) = wellFormed
        val elsewhere = act("a-policy-entry", grant, LAPTOP, null)

        assertEquals(
            AuthorityBranchResolver.history(entries, trusted(acts, manifest)),
            AuthorityBranchResolver.history(entries, trusted(acts + elsewhere, manifest)),
        )
    }

    // --------------------------- an administrative change nobody attested

    /**
     * The other half of "exactly one act per subject": the *missing* half.
     *
     * The check only ever looked at acts and asked whether each named a real
     * subject. An administrative entry with no act at all was invisible to it —
     * so a revocation whose act was dropped in transit, or deleted from the
     * database, simply stopped counting, and the grant it withdrew went on
     * standing. Losing a denial has to cost the whole estate, because a history
     * missing its last restrictive step is indistinguishable from one that
     * never had it.
     */
    @Test
    fun an_administrative_entry_with_no_act_fails_the_estate_closed() {
        val root = entry("e0", circle, null, 1)
        val granted = entry("e1", grant, "e0", 2)
        val withdrawn = entry("e2", revoke, "e1", 3)
        val entries = listOf(root, granted, withdrawn)
        // Every act but the revocation's.
        val acts = listOf(
            act("e0", circle, LAPTOP, null),
            act("e1", grant, LAPTOP, "e0"),
        )

        val reason = assertNotNull(validate(entries, acts))
        assertTrue(reason.contains("e2"), reason)
        assertEquals(
            emptyList(),
            AuthorityBranchResolver.history(entries, trusted(acts, manifest)),
            "an older grant must not stand in for a revocation nobody can find",
        )
    }

    /**
     * The peer's own bodies still need none.
     *
     * Their acceptance is carried by their own signature; demanding an owner
     * act for it would mean the owner authorising somebody else's consent.
     */
    @Test
    fun a_peer_authored_entry_needs_no_act() {
        val root = entry("e0", circle, null, 1)
        val accepted = entry(
            "e1",
            SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), DeviceId("dev-1")),
            "e0",
            2,
        )

        assertNull(validate(listOf(root, accepted), listOf(act("e0", circle, LAPTOP, null))))
    }
}
