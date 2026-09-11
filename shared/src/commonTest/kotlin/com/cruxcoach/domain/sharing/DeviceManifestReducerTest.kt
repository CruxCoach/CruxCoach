package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: the device manifest, and what it refuses to believe.
 *
 * The manifest is the root of device authority: it is the only thing that says
 * a given device of the owner's exists, what role it holds, and under which
 * authority generation. Everything else — who may mutate a permission, whose
 * concurrent change wins — is derived from it, so a manifest that reduced
 * loosely would hand out authority the owner never granted.
 *
 * It is therefore owner-signed and chained: every entry names its parent, and a
 * gap, a fork, a bad signature or a body this build cannot understand fails the
 * whole manifest closed. Failing closed here means *no device has authority*,
 * which is drastic and correct: the alternative is guessing which half of a
 * broken chain to trust.
 */
class DeviceManifestReducerTest {

    private companion object {
        const val OWNER = "npub1owner"
        const val IMPOSTOR = "npub1someone-else"
        val LAPTOP = AuthorityDeviceId("aaaa1111")
        val PHONE = AuthorityDeviceId("bbbb2222")
        val TABLET = AuthorityDeviceId("cccc3333")
    }

    /** Accepts a signature spelled `<signer>:<entryId>`; nothing else. */
    private val verifier = DeviceManifestVerifier { entry ->
        entry.signature == "${entry.signerNpub}:${entry.id.value}"
    }

    private val reducer = DeviceManifestReducer(verifier, OWNER, FIXTURE_ATTESTATION_VERIFIER)

    /**
     * The act asking for [entry], as the device holding authority would sign
     * it: against the estate as it stood at that entry's **parent**.
     *
     * Every ordinary mutation needs one now. A genesis and a sovereign recovery
     * do not, because at both of those points there is no device that could
     * have signed.
     */
    private fun ask(
        entry: DeviceManifestEntry,
        device: AuthorityDeviceId = LAPTOP,
        generation: Long = 1,
        publicKey: String = "pk-laptop",
        follows: LedgerEntryId? = null,
    ) =
        AuthorityPairing.requiredFor(entry.body)!!.let { required ->
            AuthorityAttestation(
                id = LedgerEntryId("act-${entry.id.value}"),
                scope = required.scope,
                subject = entry.id,
                device = device,
                parent = follows,
                manifestContext = ManifestContext.of(listOfNotNull(entry.parent)),
                authorityGeneration = generation,
                capability = required.capability,
                effect = required.effect,
                signature = "$publicKey:act-${entry.id.value}",
            )
        }

    /** Reduces with an act for every entry that needs one, and none for the rest. */
    private fun reduceAsked(entries: List<DeviceManifestEntry>, device: AuthorityDeviceId = LAPTOP) =
        reducer.reduce(
            entries,
            entries.filter { needsAnAct(it, entries) }
                .map { ask(it, device, it.authorityGeneration, keyOf(device, entries), follows(it, entries)) },
        )

    /**
     * The act this one was built on: the nearest manifest ancestor in the same
     * scope. A scope's causality is its own, so an enrolment and a later role
     * change about one device chain, while changes to *different* devices do
     * not see each other at all.
     */
    private fun follows(entry: DeviceManifestEntry, all: List<DeviceManifestEntry>): LedgerEntryId? {
        val scope = AuthorityPairing.requiredFor(entry.body)!!.scope
        var cursor = entry.parent?.let { id -> all.firstOrNull { it.id == id } }
        while (cursor != null) {
            if (AuthorityPairing.requiredFor(cursor.body)!!.scope == scope && needsAnAct(cursor, all)) {
                return LedgerEntryId("act-${cursor.id.value}")
            }
            cursor = cursor.parent?.let { id -> all.firstOrNull { it.id == id } }
        }
        return null
    }

    private fun keyOf(device: AuthorityDeviceId, entries: List<DeviceManifestEntry>): String =
        entries.map { it.body }.filterIsInstance<DeviceManifestBody.DeviceEnrolled>()
            .firstOrNull { it.device == device }?.publicKey ?: "pk-${device.value}"

    private fun needsAnAct(entry: DeviceManifestEntry, all: List<DeviceManifestEntry>): Boolean {
        if (entry.parent == null) return false
        if (entry.body is DeviceManifestBody.AuthorityRotated) return false
        if (entry.body is DeviceManifestBody.SovereignReset) return false
        val above = all.firstOrNull { it.id == entry.parent }?.body
        return above !is DeviceManifestBody.AuthorityRotated && above !is DeviceManifestBody.SovereignReset
    }

    private fun entry(
        seq: Long,
        body: DeviceManifestBody,
        parent: String?,
        generation: Long = 1,
        signer: String = OWNER,
        id: String = "m-$seq",
        signature: String? = null,
    ) = DeviceManifestEntry(
        id = LedgerEntryId(id),
        manifestSequence = seq,
        authorityGeneration = generation,
        parent = parent?.let { LedgerEntryId(it) },
        signerNpub = signer,
        signature = signature ?: "$signer:$id",
        body = body,
    )

    /** The genesis: a primary device enrolling itself under generation 1. */
    private val genesis = entry(1, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk-laptop", DeviceRole.PRIMARY), null)

    // ------------------------------------------------------------ enrolment

    @Test
    fun the_genesis_entry_enrols_the_first_device() {
        val state = reduceAsked(listOf(genesis))

        assertNull(state.failClosedReason)
        assertEquals(1L, state.authorityGeneration)
        assertEquals(LedgerEntryId("m-1"), state.head)
        assertEquals(DeviceRole.PRIMARY, state.roleOf(LAPTOP))
        assertTrue(state.can(LAPTOP, DeviceCapability.ADMINISTER_DEVICES))
    }

    @Test
    fun a_second_device_is_enrolled_with_the_role_it_was_given() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-1"),
                entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk-tablet", DeviceRole.READ_ONLY), "m-2"),
            )
        )

        assertNull(state.failClosedReason)
        assertEquals(DeviceRole.TRUSTED, state.roleOf(PHONE))
        assertEquals(DeviceRole.READ_ONLY, state.roleOf(TABLET))
        // A trusted device may change permissions but not the manifest.
        assertTrue(state.can(PHONE, DeviceCapability.MUTATE_PERMISSIONS))
        assertTrue(!state.can(PHONE, DeviceCapability.ADMINISTER_DEVICES))
        assertTrue(!state.can(TABLET, DeviceCapability.MUTATE_PERMISSIONS))
    }

    @Test
    fun a_role_change_takes_effect() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.READ_ONLY), "m-1"),
                entry(3, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.TRUSTED), "m-2"),
            )
        )

        assertEquals(DeviceRole.TRUSTED, state.roleOf(PHONE))
    }

    // ----------------------------------------------------------- revocation

    @Test
    fun a_revoked_device_holds_no_role() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-1"),
                entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2"),
            )
        )

        assertNull(state.roleOf(PHONE), "a revoked device carries no authority")
        assertEquals(DeviceRole.REVOKED, assertNotNull(state.devices[PHONE]).role)
    }

    /**
     * Revocation is sticky, exactly as a revoked *peer* device is. Withdrawing
     * trust from a device that may be lost or seized, and then having a later
     * entry hand it back, would make revocation advisory.
     */
    @Test
    fun a_revoked_device_is_not_resurrected_by_a_later_enrolment() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-1"),
                entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2"),
                entry(4, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.PRIMARY), "m-3"),
                entry(5, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.TRUSTED), "m-4"),
            )
        )

        assertNull(state.roleOf(PHONE), "revocation must not be reversible")
        assertEquals(DeviceRole.REVOKED, assertNotNull(state.devices[PHONE]).role)
    }

    // ------------------------------------------------- generations, fencing

    @Test
    fun rotating_authority_fences_every_device_that_was_not_re_enrolled() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-1"),
                entry(3, DeviceManifestBody.AuthorityRotated(2, fenceExisting = true), "m-2"),
            )
        )

        assertEquals(2L, state.authorityGeneration)
        assertNull(state.roleOf(LAPTOP), "a fenced device carries no authority")
        assertNull(state.roleOf(PHONE))
        // The rows survive so the screen can say *why* they stopped working.
        assertTrue(assertNotNull(state.devices[PHONE]).fenced)
        assertEquals(DeviceRole.TRUSTED, assertNotNull(state.devices[PHONE]).role)
    }

    @Test
    fun a_device_re_enrolled_after_a_rotation_works_again() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.AuthorityRotated(2, fenceExisting = true), "m-1"),
                entry(
                    3,
                    DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk-laptop", DeviceRole.PRIMARY),
                    "m-2",
                    generation = 2,
                ),
            )
        )

        assertEquals(DeviceRole.PRIMARY, state.roleOf(LAPTOP))
        assertTrue(!assertNotNull(state.devices[LAPTOP]).fenced)
        assertEquals(2L, assertNotNull(state.devices[LAPTOP]).enrolledInGeneration)
    }

    @Test
    fun an_entry_authored_under_a_superseded_generation_fails_the_manifest_closed() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.AuthorityRotated(2, fenceExisting = true), "m-1"),
                // Authored under generation 1, after authority moved to 2.
                entry(3, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.PRIMARY), "m-2"),
            )
        )

        assertNotNull(state.failClosedReason)
        assertNull(state.roleOf(LAPTOP), "a manifest that did not reduce grants nothing at all")
    }

    @Test
    fun authority_generations_only_ever_move_forward() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.AuthorityRotated(3, fenceExisting = true), "m-1"),
                entry(3, DeviceManifestBody.AuthorityRotated(2, fenceExisting = true), "m-2", generation = 3),
            )
        )

        assertNotNull(state.failClosedReason, "a generation that went backwards is a replay or a fork")
    }

    // ------------------------------------------------------ sovereign reset

    @Test
    fun a_sovereign_reset_revokes_and_fences_the_whole_estate() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-1"),
                entry(3, DeviceManifestBody.SovereignReset, "m-2"),
            )
        )

        assertNull(state.failClosedReason)
        assertEquals(2L, state.authorityGeneration, "a reset mints a fresh authority")
        listOf(LAPTOP, PHONE).forEach { device ->
            assertNull(state.roleOf(device), "$device must hold nothing after a reset")
            val record = assertNotNull(state.devices[device])
            assertEquals(DeviceRole.REVOKED, record.role)
            assertTrue(record.fenced)
        }
    }

    @Test
    fun a_device_enrolled_after_a_sovereign_reset_holds_authority_again() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.SovereignReset, "m-1"),
                entry(
                    3,
                    DeviceManifestBody.DeviceEnrolled(TABLET, "pk-tablet", DeviceRole.PRIMARY),
                    "m-2",
                    generation = 2,
                ),
            )
        )

        assertEquals(DeviceRole.PRIMARY, state.roleOf(TABLET))
        assertNull(state.roleOf(LAPTOP), "the reset estate stays fenced")
    }

    // ------------------------------------------------------- failing closed

    @Test
    fun an_entry_signed_by_somebody_other_than_the_owner_fails_closed() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(
                    2,
                    DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.PRIMARY),
                    "m-1",
                    signer = IMPOSTOR,
                ),
            )
        )

        assertNotNull(state.failClosedReason)
        assertNull(state.roleOf(LAPTOP))
    }

    @Test
    fun an_entry_whose_signature_does_not_verify_fails_closed() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(
                    2,
                    DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED),
                    "m-1",
                    signature = "not-the-signature",
                ),
            )
        )

        assertNotNull(state.failClosedReason)
    }

    @Test
    fun a_gap_in_the_manifest_fails_closed() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(3, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-1"),
            )
        )

        assertNotNull(state.failClosedReason, "a missing entry must be detectable, not invisible")
    }

    @Test
    fun an_entry_naming_the_wrong_parent_fails_closed() {
        val state = reduceAsked(
            listOf(
                genesis,
                entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-99"),
            )
        )

        assertNotNull(state.failClosedReason, "a broken chain is a fork")
    }

    @Test
    fun a_body_this_build_cannot_understand_fails_closed() {
        val state = reduceAsked(
            listOf(genesis, entry(2, DeviceManifestBody.Unknown("SomethingNewer"), "m-1")),
        )

        assertNotNull(state.failClosedReason, "an unreadable authority change must never be ignored")
    }

    @Test
    fun an_empty_manifest_grants_nothing_without_being_an_error() {
        val state = reduceAsked(emptyList())

        assertNull(state.failClosedReason)
        assertEquals(0L, state.authorityGeneration)
        assertNull(state.roleOf(LAPTOP))
    }

    // --------------------------------------------- order and duplicates

    @Test
    fun entries_arriving_out_of_order_reduce_to_the_same_state() {
        val entries = listOf(
            genesis,
            entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-1"),
            entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2"),
        )

        assertEquals(reduceAsked(entries), reduceAsked(entries.reversed()))
        assertEquals(reduceAsked(entries), reduceAsked(listOf(entries[2], entries[0], entries[1])))
    }

    @Test
    fun a_byte_identical_duplicate_changes_nothing() {
        val entries = listOf(
            genesis,
            entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-1"),
        )

        assertEquals(reduceAsked(entries), reduceAsked(entries + entries))
    }

    /**
     * A fork is normal now, and both sides stand when they are about different
     * devices.
     *
     * This used to fail the manifest closed on the grounds that one writer
     * cannot legitimately claim a sequence twice. Only the *signature* has one
     * writer: the root key is reachable from every device the owner holds, so
     * two of them offline both ask it to sign and both produce "parent head,
     * next sequence". Refusing the second put relay order in charge of who
     * administers the estate. Enrolling a phone and enrolling a tablet are
     * independent facts and neither displaces the other.
     */
    @Test
    fun two_entries_sharing_one_sequence_are_a_fork_rather_than_a_failure() {
        val entries = listOf(
            genesis,
            entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.TRUSTED), "m-1"),
            entry(
                2,
                DeviceManifestBody.DeviceEnrolled(TABLET, "pk-tablet", DeviceRole.PRIMARY),
                "m-1",
                id = "m-2b",
            ),
        )

        listOf(entries, entries.reversed()).forEach { arrival ->
            val state = reduceAsked(arrival)
            assertNull(state.failClosedReason, "a fork is resolved, not refused")
            assertEquals(DeviceRole.TRUSTED, state.roleOf(PHONE), "both sides stand")
            assertEquals(DeviceRole.PRIMARY, state.roleOf(TABLET))
        }
    }

    /**
     * Two changes to the *same* device do compete, and the restrictive one
     * wins whichever arrived first.
     */
    @Test
    fun a_revocation_beats_a_concurrent_role_change_to_the_same_device_either_way_round() {
        val entries = listOf(
            genesis,
            entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.READ_ONLY), "m-1"),
            entry(3, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.PRIMARY), "m-2"),
            entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2", id = "m-3b"),
        )

        listOf(entries, entries.reversed()).forEach { arrival ->
            val state = reduceAsked(arrival)
            assertNull(state.failClosedReason)
            assertNull(state.roleOf(PHONE), "a concurrent revocation is never lost to a promotion")
        }
    }

    /**
     * A sovereign reset wipes the estate, and the enrolment that follows it is
     * the new estate's first device.
     *
     * Revocation is sticky *within* a generation — that is what stops a revoked
     * device being quietly reinstated. But a reset revokes everything including
     * the device performing it, so under a plain sticky rule that device could
     * never enrol itself and the reset left an estate nobody could act in. The
     * new generation is what makes the difference: it is a different estate.
     */
    @Test
    fun a_device_may_be_enrolled_again_under_the_generation_a_reset_minted() {
        val entries = listOf(
            entry(1, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk", DeviceRole.PRIMARY), null),
            entry(2, DeviceManifestBody.SovereignReset, "m-1"),
            entry(3, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk", DeviceRole.PRIMARY), "m-2", generation = 2),
        )

        val reduced = reduceAsked(entries)

        assertEquals(null, reduced.failClosedReason)
        assertEquals(DeviceRole.PRIMARY, reduced.roleOf(LAPTOP))
        assertEquals(2L, reduced.authorityGeneration)
    }

    /** Within one generation it stays sticky, which is the point of it. */
    @Test
    fun a_revoked_device_still_cannot_be_re_enrolled_in_the_same_generation() {
        val entries = listOf(
            entry(1, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk", DeviceRole.PRIMARY), null),
            entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1"),
            entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2"),
            entry(4, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.PRIMARY), "m-3"),
        )

        assertEquals(null, reduceAsked(entries).roleOf(PHONE), "revocation does not wear off")
    }

    @Test
    fun a_rotation_alone_does_not_reinstate_a_revoked_device() {
        val entries = listOf(
            entry(1, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk", DeviceRole.PRIMARY), null),
            entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1"),
            entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2"),
            entry(4, DeviceManifestBody.AuthorityRotated(2, fenceExisting = false), "m-3"),
        )

        // Only an explicit enrolment under the new generation does that.
        assertEquals(null, reduceAsked(entries).roleOf(PHONE))
    }

    // ------------------------------------- ranking, and the act DAG itself

    /**
     * The rank a fork is settled by must come from the role each asking device
     * held **at the head its own act names**.
     *
     * It was assembled into one synthetic manifest instead — a map built by
     * folding every act's device record into a single state, where a device
     * that authored twice contributed twice and `toMap` kept whichever came
     * last. The per-event lookup is pinned directly in
     * DeviceAuthorityResolverTest; what this one holds is the property that
     * matters here: the same evidence, in either order, with roles that differ
     * between heads and a revocation in a scope of its own, reduces the same
     * way.
     */
    @Test
    fun the_same_evidence_ranks_the_same_fork_the_same_way_in_either_order() {
        val entries = listOf(
            genesis,
            entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.PRIMARY), "m-1"),
            // Two changes about the tablet, forked, each asked for by a device
            // that was PRIMARY at the head it names.
            entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk-tablet", DeviceRole.TRUSTED), "m-2", id = "m-3a"),
            entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk-tablet", DeviceRole.READ_ONLY), "m-2", id = "m-3b"),
            // And the phone loses its say afterwards, in its own scope, which
            // must not reach back into the tablet's.
            entry(4, DeviceManifestBody.DeviceRevoked(PHONE), "m-3a", id = "m-4"),
        )
        val acts = listOf(
            ask(entries[1], LAPTOP, 1, "pk-laptop"),
            ask(entries[2], LAPTOP, 1, "pk-laptop"),
            ask(entries[3], PHONE, 1, "pk-phone"),
            ask(entries[4], LAPTOP, 1, "pk-laptop", LedgerEntryId("act-m-2")),
        )

        val forward = reducer.reduce(entries, acts)
        val reverse = reducer.reduce(entries.reversed(), acts.reversed())

        assertNull(forward.failClosedReason, "the estate is well formed")
        assertEquals(forward, reverse, "the same evidence must reduce the same way")
        assertNull(forward.roleOf(PHONE), "the revocation stands in the phone's own scope")
        assertNotNull(forward.devices[TABLET], "and the tablet's scope is decided on its own evidence")
    }

    // ------------------------------------------------ the act DAG must hold

    private fun wellFormed(): Pair<List<DeviceManifestEntry>, List<AuthorityAttestation>> {
        val entries = listOf(
            genesis,
            entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.READ_ONLY), "m-1"),
            entry(3, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.TRUSTED), "m-2"),
        )
        return entries to listOf(
            ask(entries[1], LAPTOP, 1, "pk-laptop"),
            ask(entries[2], LAPTOP, 1, "pk-laptop", LedgerEntryId("act-m-2")),
        )
    }

    @Test
    fun a_well_formed_manifest_act_dag_reduces() {
        val (entries, acts) = wellFormed()

        assertNull(reducer.reduce(entries, acts).failClosedReason)
    }

    @Test
    fun two_acts_for_one_manifest_entry_fail_closed() {
        val (entries, acts) = wellFormed()
        val second = acts[0].copy(id = LedgerEntryId("act-duplicate"))

        assertNotNull(reducer.reduce(entries, acts + second).failClosedReason)
    }

    @Test
    fun an_act_that_does_not_match_the_entrys_body_fails_closed() {
        val (entries, acts) = wellFormed()
        val mislabelled = acts.map { if (it.subject.value == "m-3") it.copy(effect = AuthorityEffect.PERMISSIVE) else it }

        assertNotNull(reducer.reduce(entries, mislabelled).failClosedReason)
    }

    @Test
    fun an_act_parent_that_is_not_stored_fails_closed() {
        val (entries, acts) = wellFormed()
        val dangling = acts.map {
            if (it.subject.value == "m-3") it.copy(parent = LedgerEntryId("act-nowhere")) else it
        }

        assertNotNull(reducer.reduce(entries, dangling).failClosedReason)
    }

    @Test
    fun an_act_parent_in_another_scope_fails_closed() {
        val entries = listOf(
            genesis,
            entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk-phone", DeviceRole.READ_ONLY), "m-1"),
            entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk-tablet", DeviceRole.READ_ONLY), "m-2"),
        )
        val acts = listOf(
            ask(entries[1], LAPTOP, 1, "pk-laptop"),
            // The tablet's act claims to follow the phone's.
            ask(entries[2], LAPTOP, 1, "pk-laptop", LedgerEntryId("act-m-2")),
        )

        assertNotNull(reducer.reduce(entries, acts).failClosedReason)
    }

    @Test
    fun a_cycle_among_manifest_acts_fails_closed() {
        val (entries, acts) = wellFormed()
        val looped = acts.map {
            when (it.subject.value) {
                "m-2" -> it.copy(parent = LedgerEntryId("act-m-3"))
                else -> it
            }
        }

        assertNotNull(reducer.reduce(entries, looped).failClosedReason)
    }

    @Test
    fun a_second_manifest_root_fails_closed() {
        val (entries, acts) = wellFormed()
        val second = entry(1, DeviceManifestBody.DeviceEnrolled(TABLET, "pk-tablet", DeviceRole.PRIMARY), null, id = "m-1b")

        val reason = assertNotNull(reducer.reduce(entries + second, acts).failClosedReason)
        assertTrue(reason.contains("root") || reason.contains("history"), reason)
    }
}
