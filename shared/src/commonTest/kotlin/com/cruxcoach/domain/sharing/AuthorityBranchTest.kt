package com.cruxcoach.domain.sharing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: the resolver deciding what a person's permissions actually are.
 *
 * Until now it decided which *act* stood, and the permission projection was
 * reduced along a single linear chain that never consulted it. That made the
 * whole model decorative: two devices offline could not even both record their
 * decision, because the second one to arrive collided on the next sequence and
 * was thrown away. Whichever branch reached the database first won, which is
 * arrival order — the exact thing the resolver exists to eliminate.
 *
 * So the permission ledger is a DAG per peer, exactly like the acts that
 * authorise it, and the reduction folds the branch the resolver picks.
 */
class AuthorityBranchTest {

    private companion object {
        val ALICE = PeerId("npub1alice")
        val LAPTOP = AuthorityDeviceId("aaaa-laptop")
        val PHONE = AuthorityDeviceId("bbbb-phone")
        val TABLET = AuthorityDeviceId("zzzz-tablet")
    }

    private fun manifest(vararg devices: Pair<AuthorityDeviceId, DeviceRole>) = DeviceAuthorityState(
        authorityGeneration = 1,
        head = LedgerEntryId("m-1"),
        devices = devices.associate { (device, role) ->
            device to DeviceRecord(device, "pk-${device.value}", role, 1)
        },
    )

    private fun entry(
        id: String,
        body: SharingLedgerBody,
        parent: String?,
        sequence: Long,
    ) = SharingLedgerEntry(
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

    /**
     * The act that authorises [entry], as the owning device would sign it.
     *
     * [follows] is the act it was built on, and must be in the same scope: a
     * scope's causality is its own, so the first act in one carries no parent.
     */
    private fun actFor(
        entry: SharingLedgerEntry,
        device: AuthorityDeviceId,
        follows: String? = entry.parent?.value,
    ): AuthorityAttestation {
        val required = AuthorityPairing.requiredFor(entry.peer, entry.body)!!
        return AuthorityAttestation(
            id = LedgerEntryId("act-${entry.id.value}"),
            scope = required.scope,
            subject = entry.id,
            device = device,
            parent = follows?.let { LedgerEntryId("act-$it") },
            manifestContext = FIXTURE_MANIFEST_CONTEXT,
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "pk-${device.value}:act-${entry.id.value}",
        )
    }

    // ---------------------------------------------------------- the branches

    private val root = entry("e0", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1)

    /** Two devices, offline, both building on the same entry. */
    private fun forked(
        allowDevice: AuthorityDeviceId,
        denyDevice: AuthorityDeviceId,
    ): Pair<List<SharingLedgerEntry>, List<AuthorityAttestation>> {
        val allow = entry("e-allow", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val deny = entry("e-deny", SharingLedgerBody.RelationshipRevoked, "e0", 2)
        val entries = listOf(root, allow, deny)
        val acts = listOf(
            actFor(root, allowDevice),
            actFor(allow, allowDevice),
            actFor(deny, denyDevice),
        )
        return entries to acts
    }

    /**
     * The winner of the peer's own scope, which is where these fixtures put
     * their conflicts. Scopes are resolved separately now, so the head is a
     * question about one of them.
     */
    private fun head(
        entries: List<SharingLedgerEntry>,
        acts: List<AuthorityAttestation>,
        manifest: DeviceAuthorityState,
    ) = AuthorityBranchResolver.head(entries, trusted(acts, manifest), AuthorityScope.peer(ALICE))

    /** Mandatory matrix: same parent, one allows and one denies. */
    @Test
    fun a_denial_beats_an_allow_on_the_same_parent_in_either_arrival_order() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = forked(allowDevice = LAPTOP, denyDevice = PHONE)

        assertEquals(LedgerEntryId("e-deny"), head(entries, acts, state)?.id)
        assertEquals(LedgerEntryId("e-deny"), head(entries.reversed(), acts.reversed(), state)?.id)
    }

    /** Mandatory matrix: revoke versus grant. */
    @Test
    fun a_revocation_beats_a_grant_whichever_arrives_first() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = forked(allowDevice = LAPTOP, denyDevice = PHONE)

        // Even though the grant came from the *primary* device: restrictive
        // outranks role, because losing privacy costs more than a repeated tap.
        assertEquals(LedgerEntryId("e-deny"), head(entries, acts, state)?.id)
        assertEquals(LedgerEntryId("e-deny"), head(entries.reversed(), acts.reversed(), state)?.id)
    }

    /** Mandatory matrix: primary versus trusted, same effect. */
    @Test
    fun a_primary_device_outranks_a_trusted_one_on_the_same_effect() {
        // TABLET sorts last by id, so rank has to be what decides.
        val state = manifest(TABLET to DeviceRole.PRIMARY, LAPTOP to DeviceRole.TRUSTED)
        val a = entry("e-a", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val b = entry("e-b", SharingLedgerBody.GrantChanged(setOf(SharingCategory.PRIVATE_NOTES)), "e0", 2)
        val entries = listOf(root, a, b)
        val acts = listOf(actFor(root, TABLET), actFor(a, LAPTOP), actFor(b, TABLET))

        assertEquals(LedgerEntryId("e-b"), head(entries, acts, state)?.id)
        assertEquals(LedgerEntryId("e-b"), head(entries.reversed(), acts.reversed(), state)?.id)
    }

    /** Mandatory matrix: two trusted devices, same effect. */
    @Test
    fun two_trusted_devices_are_separated_by_canonical_id_not_by_order() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val a = entry("e-a", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val b = entry("e-b", SharingLedgerBody.GrantChanged(setOf(SharingCategory.PRIVATE_NOTES)), "e0", 2)
        val entries = listOf(root, a, b)
        val acts = listOf(actFor(root, LAPTOP), actFor(a, PHONE), actFor(b, LAPTOP))

        // LAPTOP's device id sorts first and nothing above it separates them.
        assertEquals(LedgerEntryId("e-b"), head(entries, acts, state)?.id)
        assertEquals(LedgerEntryId("e-b"), head(entries.reversed(), acts.reversed(), state)?.id)
    }

    @Test
    fun the_winner_is_the_same_under_any_shuffle() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = forked(allowDevice = LAPTOP, denyDevice = PHONE)
        val expected = head(entries, acts, state)

        val random = Random(20620816)
        repeat(100) {
            assertEquals(expected, head(entries.shuffled(random), acts.shuffled(random), state))
        }
    }

    // ---------------------------------------------------------- the history

    @Test
    fun the_history_is_the_winning_branch_and_nothing_from_the_loser() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = forked(allowDevice = LAPTOP, denyDevice = PHONE)

        val history = AuthorityBranchResolver.history(entries, trusted(acts, state))

        assertEquals(listOf(LedgerEntryId("e0"), LedgerEntryId("e-deny")), history.map { it.id })
    }

    @Test
    fun the_history_runs_root_first() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED)
        val next = entry("e1", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val entries = listOf(next, root)
        val acts = listOf(actFor(root, LAPTOP), actFor(next, LAPTOP))

        val history = AuthorityBranchResolver.history(entries, trusted(acts, state))

        assertEquals(listOf(LedgerEntryId("e0"), LedgerEntryId("e1")), history.map { it.id })
    }

    /**
     * Consent is the peer's and carries no owner act, so it cannot be ranked —
     * but it is still part of the history it answers. It rides on the branch
     * its parent is on.
     */
    @Test
    fun a_peer_signed_reply_stays_on_the_branch_it_answers() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED)
        val offer = entry("e1", SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val accept = entry(
            "e2",
            SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), DeviceId("d1")),
            "e1",
            3,
        )
        val entries = listOf(root, offer, accept)
        val acts = listOf(actFor(root, LAPTOP), actFor(offer, LAPTOP))

        val history = AuthorityBranchResolver.history(entries, trusted(acts, state))

        assertTrue(history.map { it.id }.contains(LedgerEntryId("e2")), "the acceptance has to survive")
    }

    @Test
    fun an_entry_on_the_losing_branch_decides_nothing() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = forked(allowDevice = LAPTOP, denyDevice = PHONE)

        val history = AuthorityBranchResolver.history(entries, trusted(acts, state))

        assertTrue(history.none { it.id == LedgerEntryId("e-allow") })
    }

    // ------------------------------------------------------------ fail closed

    @Test
    fun an_entry_no_act_vouches_for_heads_nothing() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED)

        assertNull(head(listOf(root), emptyList(), state))
        assertEquals(emptyList(), AuthorityBranchResolver.history(listOf(root), trusted(emptyList(), state)))
    }

    /**
     * PHONE's denial would win outright, and PHONE holds no authority. Dropping
     * it and letting LAPTOP's allow stand is the truncation this whole model
     * exists to prevent: a withdrawal that disappears in silence. So the branch
     * closes rather than resolving to what is left.
     */
    @Test
    fun a_branch_authored_by_a_revoked_device_closes_the_history() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.REVOKED)
        val (entries, acts) = forked(allowDevice = LAPTOP, denyDevice = PHONE)

        assertNull(head(entries, acts, state))
        assertEquals(emptyList(), AuthorityBranchResolver.history(entries, trusted(acts, state)))
        assertEquals(
            LedgerEntryId("e-allow"),
            head(
                entries.filterNot { it.id == LedgerEntryId("e-deny") },
                acts.filterNot { it.device == PHONE },
                state,
            )?.id,
            "take the revoked device's change away entirely and the same fixture resolves",
        )
    }

    @Test
    fun an_empty_ledger_has_no_head() {
        assertNull(head(emptyList(), emptyList(), manifest(LAPTOP to DeviceRole.PRIMARY)))
    }

    // ------------------------------------------------------ reduced state

    @Test
    fun the_projection_follows_the_winning_branch() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = forked(allowDevice = LAPTOP, denyDevice = PHONE)
        val reducer = SharingLedgerReducer({ true }, "npub1owner")

        val reduced = reducer.reduce(ALICE, entries, trusted(acts, state))

        assertEquals(RelationshipStatus.REVOKED, reduced.status, "the denial is what stands")
        assertEquals(emptySet(), reduced.consentedCategories)
    }

    @Test
    fun the_projection_is_the_same_in_either_arrival_order() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = forked(allowDevice = LAPTOP, denyDevice = PHONE)
        val reducer = SharingLedgerReducer({ true }, "npub1owner")

        assertEquals(
            reducer.reduce(ALICE, entries, trusted(acts, state)),
            reducer.reduce(ALICE, entries.reversed(), trusted(acts.reversed(), state)),
        )
    }

    @Test
    fun a_projection_with_no_authority_at_all_fails_closed() {
        val reducer = SharingLedgerReducer({ true }, "npub1owner")

        val reduced = reducer.reduce(ALICE, listOf(root), trusted(emptyList(), manifest(LAPTOP to DeviceRole.TRUSTED)))

        assertNotNull(reduced.failClosedReason, "no authorised history means no released data")
        assertEquals(emptySet(), reduced.consentedCategories)
    }

    // ---------------------------------------------------------- admission

    private fun admit(existing: List<SharingLedgerEntry>, candidate: SharingLedgerEntry) =
        SharingLedgerAdmission.check(existing, candidate, { true }, "npub1owner")

    /**
     * The rule that made the model decorative. Two devices offline both build
     * on `e0` and both claim sequence 2; the second used to be refused as "not
     * the expected next sequence", so the branch that arrived first won.
     */
    @Test
    fun both_sides_of_a_same_parent_fork_are_admitted() {
        val allow = entry("e-allow", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val deny = entry("e-deny", SharingLedgerBody.RelationshipRevoked, "e0", 2)

        assertTrue(admit(listOf(root), allow) is LedgerAdmission.Accept)
        assertTrue(admit(listOf(root, allow), deny) is LedgerAdmission.Accept)
    }

    @Test
    fun a_fork_is_admitted_in_either_arrival_order() {
        val allow = entry("e-allow", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val deny = entry("e-deny", SharingLedgerBody.RelationshipRevoked, "e0", 2)

        assertTrue(admit(listOf(root), deny) is LedgerAdmission.Accept)
        assertTrue(admit(listOf(root, deny), allow) is LedgerAdmission.Accept)
    }

    @Test
    fun a_parent_that_is_not_stored_is_refused() {
        val orphan = entry("e1", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "nowhere", 2)

        assertTrue(admit(listOf(root), orphan) is LedgerAdmission.Reject)
    }

    @Test
    fun a_second_first_entry_is_refused() {
        val secondRoot = entry("e-other", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1)

        assertTrue(
            admit(listOf(root), secondRoot) is LedgerAdmission.Reject,
            "a relationship has one beginning",
        )
    }

    @Test
    fun an_entry_that_is_its_own_parent_is_refused() {
        val loop = entry("e1", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e1", 2)

        assertTrue(admit(listOf(root), loop) is LedgerAdmission.Reject)
    }

    /** The sequence still has to follow its own parent, so it stays meaningful. */
    @Test
    fun a_sequence_that_does_not_follow_its_parent_is_refused() {
        val jumped = entry("e1", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e0", 9)

        assertTrue(admit(listOf(root), jumped) is LedgerAdmission.Reject)
    }

    @Test
    fun a_byte_identical_duplicate_is_still_idempotent() {
        assertTrue(admit(listOf(root), root) is LedgerAdmission.AlreadyPresent)
    }

    // ------------------------------------- independent scopes both survive

    /**
     * Scopes that were never in conflict must not be played against each other.
     *
     * Resolving every act in one pool made a revocation about *one* of a peer's
     * devices delete an unrelated grant, because restrictive-beats-permissive
     * was applied across subjects that never competed. The rule is only meant
     * to settle two changes to the *same* thing.
     */
    @Test
    fun a_grant_and_a_device_revocation_both_stand() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val grant = entry("e-grant", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val revoke = entry("e-revoke", SharingLedgerBody.DeviceRevoked(DeviceId("dev-1")), "e0", 2)
        val entries = listOf(root, grant, revoke)
        // The revocation opens the peerDevice scope, so it has no act parent.
        val acts = listOf(actFor(root, LAPTOP), actFor(grant, LAPTOP), actFor(revoke, PHONE, follows = null))

        listOf(entries to acts, entries.reversed() to acts.reversed()).forEach { (e, a) ->
            val ids = AuthorityBranchResolver.history(e, trusted(a, state)).map { it.id }
            assertTrue(LedgerEntryId("e-grant") in ids, "the grant is about the peer, not the device")
            assertTrue(LedgerEntryId("e-revoke") in ids, "the revocation is about one device")
        }
    }

    @Test
    fun two_revocations_of_different_devices_both_stand() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val one = entry("e-one", SharingLedgerBody.DeviceRevoked(DeviceId("dev-1")), "e0", 2)
        val two = entry("e-two", SharingLedgerBody.DeviceRevoked(DeviceId("dev-2")), "e0", 2)
        val entries = listOf(root, one, two)
        // Each device's scope begins with its own act.
        val acts = listOf(
            actFor(root, LAPTOP),
            actFor(one, LAPTOP, follows = null),
            actFor(two, PHONE, follows = null),
        )

        listOf(entries to acts, entries.reversed() to acts.reversed()).forEach { (e, a) ->
            val ids = AuthorityBranchResolver.history(e, trusted(a, state)).map { it.id }
            assertTrue(LedgerEntryId("e-one") in ids)
            assertTrue(LedgerEntryId("e-two") in ids)
        }
    }

    /** Same scope still means exactly one winner. */
    @Test
    fun two_changes_to_one_device_still_leave_one_standing() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val device = DeviceId("dev-1")
        val confirm = entry("e-confirm", SharingLedgerBody.KeyDeliveryConfirmed(device), "e0", 2)
        val unclear = entry("e-unclear", SharingLedgerBody.KeyDeliveryUnclear(device), "e0", 2)
        val entries = listOf(root, confirm, unclear)
        // Two acts about one device: both open that device's scope, which is
        // exactly the offline fork the resolver settles.
        val acts = listOf(
            actFor(root, LAPTOP),
            actFor(confirm, LAPTOP, follows = null),
            actFor(unclear, PHONE, follows = null),
        )

        listOf(entries to acts, entries.reversed() to acts.reversed()).forEach { (e, a) ->
            val ids = AuthorityBranchResolver.history(e, trusted(a, state)).map { it.id }
            assertTrue(LedgerEntryId("e-unclear") in ids, "the restrictive one stands")
            assertTrue(LedgerEntryId("e-confirm") !in ids, "and the other does not")
        }
    }

    @Test
    fun the_history_is_ordered_the_same_way_every_time() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val grant = entry("e-grant", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val revoke = entry("e-revoke", SharingLedgerBody.DeviceRevoked(DeviceId("dev-1")), "e0", 2)
        val entries = listOf(root, grant, revoke)
        val acts = listOf(actFor(root, LAPTOP), actFor(grant, LAPTOP), actFor(revoke, PHONE, follows = null))
        val expected = AuthorityBranchResolver.history(entries, trusted(acts, state)).map { it.id }

        val random = Random(20620818)
        repeat(100) {
            assertEquals(
                expected,
                AuthorityBranchResolver.history(entries.shuffled(random), trusted(acts.shuffled(random), state))
                    .map { it.id },
            )
        }
    }

    /** A peer's reply chain still attaches, across scopes. */
    @Test
    fun a_peer_reply_chain_attaches_to_the_branch_it_answers() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED)
        val offer = entry("e1", SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)), "e0", 2)
        val accept = entry(
            "e2",
            SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), DeviceId("d1")),
            "e1",
            3,
        )
        val second = entry("e3", SharingLedgerBody.DeviceAuthorized(DeviceId("d2")), "e2", 4)
        val entries = listOf(root, offer, accept, second)
        val acts = listOf(actFor(root, LAPTOP), actFor(offer, LAPTOP))

        val ids = AuthorityBranchResolver.history(entries, trusted(acts, state)).map { it.id }

        assertEquals(
            listOf(LedgerEntryId("e0"), LedgerEntryId("e1"), LedgerEntryId("e2"), LedgerEntryId("e3")),
            ids,
        )
    }
}
