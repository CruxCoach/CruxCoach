package com.cruxcoach.domain.sharing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: the owner's *policy* is a DAG too, decided by the same resolver.
 *
 * The relationship ledger was fixed and this one was left linear, which put the
 * whole model back where it started for baselines and rules: two of the owner's
 * devices offline both change the same baseline, the second to arrive collides
 * on the next sequence and is discarded, and whichever reached the database
 * first wins. A denial made on a phone loses to a grant made on a laptop
 * because of network timing.
 *
 * Baselines, per-peer rules and per-object rules each have their own scope, so
 * unrelated policy changes never compete — but two changes to the *same* one do,
 * and the resolver settles them from signed evidence.
 */
class OwnerPolicyBranchTest {

    private companion object {
        const val OWNER = "npub1owner"
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

    private fun entry(id: String, body: OwnerPolicyBody, parent: String?, sequence: Long) =
        OwnerPolicyEntry(
            id = LedgerEntryId(id),
            policySequence = sequence,
            parent = parent?.let { LedgerEntryId(it) },
            authorityGeneration = 1,
            signerNpub = OWNER,
            signature = "sig",
            body = body,
        )

    private fun actFor(entry: OwnerPolicyEntry, device: AuthorityDeviceId): AuthorityAttestation {
        val required = AuthorityPairing.requiredFor(entry.body)!!
        return AuthorityAttestation(
            id = LedgerEntryId("act-${entry.id.value}"),
            scope = required.scope,
            subject = entry.id,
            device = device,
            parent = null,
            manifestContext = FIXTURE_MANIFEST_CONTEXT,
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "pk-${device.value}:act-${entry.id.value}",
        )
    }

    private val root = entry(
        "p0",
        OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
        null,
        1,
    )

    private fun reduce(
        entries: List<OwnerPolicyEntry>,
        acts: List<AuthorityAttestation>,
        state: DeviceAuthorityState,
    ) = OwnerPolicyReducer({ true }, OWNER).reduce(entries, trusted(acts, state))

    // ------------------------------------------- baselines: allow versus deny

    private fun baselineFork(grantDevice: AuthorityDeviceId, denyDevice: AuthorityDeviceId) =
        listOf(
            root,
            entry(
                "p-grant",
                OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
                "p0",
                2,
            ),
            entry(
                "p-deny",
                OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
                "p0",
                2,
            ),
        ) to listOf(
            actFor(root, grantDevice),
            actFor(
                entry(
                    "p-grant",
                    OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
                    "p0",
                    2,
                ),
                grantDevice,
            ),
            actFor(
                entry(
                    "p-deny",
                    OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
                    "p0",
                    2,
                ),
                denyDevice,
            ),
        )

    @Test
    fun a_baseline_denial_beats_a_grant_in_either_arrival_order() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = baselineFork(grantDevice = LAPTOP, denyDevice = PHONE)

        listOf(entries to acts, entries.reversed() to acts.reversed()).forEach { (e, a) ->
            val reduced = reduce(e, a, state)
            assertTrue(
                SharingCategory.VIDEOS !in reduced.baselines.explicitFor(SharingCircle.FRIENDS),
                "the denial is what stands",
            )
        }
    }

    @Test
    fun a_baseline_denial_beats_a_grant_from_the_primary_device() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = baselineFork(grantDevice = LAPTOP, denyDevice = PHONE)

        val reduced = reduce(entries, acts, state)

        assertTrue(SharingCategory.VIDEOS !in reduced.baselines.explicitFor(SharingCircle.FRIENDS))
    }

    /**
     * Two changes to the *same* baseline, so they genuinely compete. Asserted
     * on the history, because both set the category the same way and the
     * folded state cannot tell them apart.
     */
    private fun sameBaselineTie(a: AuthorityDeviceId, b: AuthorityDeviceId): List<LedgerEntryId> {
        val state = manifest(TABLET to DeviceRole.PRIMARY, LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val one = entry(
            "p-a",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "p0",
            2,
        )
        val two = entry(
            "p-b",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "p0",
            2,
        )
        val entries = listOf(root, one, two)
        val acts = listOf(actFor(root, a), actFor(one, a), actFor(two, b))
        val forward = AuthorityBranchResolver.policyHistory(entries, trusted(acts, state)).map { it.id }
        val reverse = AuthorityBranchResolver
            .policyHistory(entries.reversed(), trusted(acts.reversed(), state)).map { it.id }
        assertEquals(forward, reverse, "the winner cannot depend on arrival order")
        return forward
    }

    /** Mandatory matrix: PRIMARY outranks TRUSTED when the effect is the same. */
    @Test
    fun a_primary_device_wins_a_baseline_tie() {
        // TABLET sorts last by id, so rank has to be what decides.
        val ids = sameBaselineTie(a = LAPTOP, b = TABLET)

        assertTrue(LedgerEntryId("p-b") in ids, "the primary device's change stands")
        assertTrue(LedgerEntryId("p-a") !in ids)
    }

    /** Mandatory matrix: two trusted devices, separated by canonical id. */
    @Test
    fun two_trusted_devices_are_separated_by_canonical_id() {
        // LAPTOP's device id sorts first and nothing above it separates them.
        val ids = sameBaselineTie(a = PHONE, b = LAPTOP)

        assertTrue(LedgerEntryId("p-b") in ids)
        assertTrue(LedgerEntryId("p-a") !in ids)
    }

    /**
     * Two *different* baselines are different subjects, so neither displaces
     * the other. Playing every act in one pool made an unrelated change to one
     * category delete a change to another.
     */
    @Test
    fun two_different_baselines_both_stand() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val videos = entry(
            "p-videos",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "p0",
            2,
        )
        val history = entry(
            "p-history",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.TRAINING_HISTORY, true),
            "p0",
            2,
        )
        val entries = listOf(root, videos, history)
        val acts = listOf(actFor(root, LAPTOP), actFor(videos, LAPTOP), actFor(history, PHONE))

        listOf(entries to acts, entries.reversed() to acts.reversed()).forEach { (e, a) ->
            val baselines = reduce(e, a, state).baselines.explicitFor(SharingCircle.FRIENDS)
            assertTrue(SharingCategory.VIDEOS in baselines)
            assertTrue(SharingCategory.TRAINING_HISTORY in baselines)
        }
    }

    /** A per-peer rule and a per-object rule are different subjects too. */
    @Test
    fun a_peer_rule_and_an_object_rule_both_stand() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val video = ObjectId("vid-1")
        val peerRule = entry(
            "p-peer",
            OwnerPolicyBody.PeerRuleSet(ALICE, SharingCategory.VIDEOS, AccessEffect.DENY),
            "p0",
            2,
        )
        val objectRule = entry(
            "p-object",
            OwnerPolicyBody.ObjectRuleSet(ALICE, video, SharingCategory.VIDEOS, AccessEffect.ALLOW),
            "p0",
            2,
        )
        val entries = listOf(root, peerRule, objectRule)
        val acts = listOf(actFor(root, LAPTOP), actFor(peerRule, PHONE), actFor(objectRule, LAPTOP))

        listOf(entries to acts, entries.reversed() to acts.reversed()).forEach { (e, a) ->
            val reduced = reduce(e, a, state)
            assertEquals(AccessEffect.DENY, reduced.peerRules[ALICE]?.get(SharingCategory.VIDEOS))
            assertEquals(AccessEffect.ALLOW, reduced.objectRules[ALICE]?.get(video)?.second)
        }
    }

    // ------------------------------------------------ peer and object rules

    @Test
    fun a_peer_rule_denial_beats_an_allow_in_either_order() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val allow = entry(
            "p-allow",
            OwnerPolicyBody.PeerRuleSet(ALICE, SharingCategory.VIDEOS, AccessEffect.ALLOW),
            "p0",
            2,
        )
        val deny = entry(
            "p-deny",
            OwnerPolicyBody.PeerRuleSet(ALICE, SharingCategory.VIDEOS, AccessEffect.DENY),
            "p0",
            2,
        )
        val entries = listOf(root, allow, deny)
        val acts = listOf(actFor(root, LAPTOP), actFor(allow, LAPTOP), actFor(deny, PHONE))

        listOf(entries to acts, entries.reversed() to acts.reversed()).forEach { (e, a) ->
            assertEquals(
                AccessEffect.DENY,
                reduce(e, a, state).peerRules[ALICE]?.get(SharingCategory.VIDEOS),
            )
        }
    }

    @Test
    fun an_object_rule_denial_beats_an_allow_in_either_order() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val video = ObjectId("vid-1")
        val allow = entry(
            "p-allow",
            OwnerPolicyBody.ObjectRuleSet(ALICE, video, SharingCategory.VIDEOS, AccessEffect.ALLOW),
            "p0",
            2,
        )
        val deny = entry(
            "p-deny",
            OwnerPolicyBody.ObjectRuleSet(ALICE, video, SharingCategory.VIDEOS, AccessEffect.DENY),
            "p0",
            2,
        )
        val entries = listOf(root, allow, deny)
        val acts = listOf(actFor(root, LAPTOP), actFor(allow, LAPTOP), actFor(deny, PHONE))

        listOf(entries to acts, entries.reversed() to acts.reversed()).forEach { (e, a) ->
            assertEquals(AccessEffect.DENY, reduce(e, a, state).objectRules[ALICE]?.get(video)?.second)
        }
    }

    @Test
    fun the_reduced_policy_is_the_same_under_any_shuffle() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)
        val (entries, acts) = baselineFork(grantDevice = LAPTOP, denyDevice = PHONE)
        val expected = reduce(entries, acts, state)

        val random = Random(20620817)
        repeat(100) {
            assertEquals(expected, reduce(entries.shuffled(random), acts.shuffled(random), state))
        }
    }

    // ------------------------------------------------------------ fail closed

    @Test
    fun a_policy_no_act_vouches_for_grants_nothing() {
        val reduced = reduce(listOf(root), emptyList(), manifest(LAPTOP to DeviceRole.TRUSTED))

        assertTrue(reduced.failClosedReason != null)
        assertEquals(emptySet(), reduced.baselines.explicitFor(SharingCircle.ALL_OTHER_USERS))
    }

    @Test
    fun an_empty_policy_is_simply_empty() {
        val reduced = reduce(emptyList(), emptyList(), manifest(LAPTOP to DeviceRole.PRIMARY))

        assertNull(reduced.failClosedReason)
    }

    // ------------------------------------------------------------- admission

    private fun admit(existing: List<OwnerPolicyEntry>, candidate: OwnerPolicyEntry) =
        OwnerPolicyAdmission.check(existing, candidate, { true }, OWNER)

    /** The rule that put arrival order back in charge of baselines. */
    @Test
    fun both_sides_of_a_same_parent_fork_are_admitted() {
        val (entries, _) = baselineFork(LAPTOP, PHONE)
        val grant = entries[1]
        val deny = entries[2]

        assertIs<LedgerAdmission.Accept>(admit(listOf(root), grant))
        assertIs<LedgerAdmission.Accept>(admit(listOf(root, grant), deny))
        assertIs<LedgerAdmission.Accept>(admit(listOf(root, deny), grant))
    }

    @Test
    fun a_parent_that_is_not_stored_is_refused() {
        val orphan = entry(
            "p9",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "nowhere",
            2,
        )

        assertIs<LedgerAdmission.Reject>(admit(listOf(root), orphan))
    }

    @Test
    fun a_second_beginning_is_refused() {
        val other = entry(
            "p-other",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            null,
            1,
        )

        assertIs<LedgerAdmission.Reject>(admit(listOf(root), other))
    }

    @Test
    fun a_sequence_that_does_not_follow_its_parent_is_refused() {
        val jumped = entry(
            "p9",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "p0",
            9,
        )

        assertIs<LedgerAdmission.Reject>(admit(listOf(root), jumped))
    }

    @Test
    fun a_byte_identical_duplicate_is_idempotent() {
        assertIs<LedgerAdmission.AlreadyPresent>(admit(listOf(root), root))
    }
}
