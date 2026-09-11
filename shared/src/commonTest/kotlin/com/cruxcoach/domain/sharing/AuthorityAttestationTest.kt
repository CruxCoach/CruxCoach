package com.cruxcoach.domain.sharing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: the device-signed, parent-linked record of *who changed what*.
 *
 * The manifest says which devices exist and what they may do. This ledger is
 * every administrative act those devices performed: one attestation per act,
 * signed by the authoring device's key and linked to the act it was built on.
 *
 * ## Why this is a DAG where the manifest is a line
 *
 * The manifest has exactly one writer — the owner's root identity — so it can
 * demand a strictly linear chain and refuse a fork outright. This ledger has as
 * many writers as the owner has devices, and two of them go offline and both
 * decide something. Refusing the second to arrive would make the outcome depend
 * on relay order, which is precisely what the resolver exists to eliminate. So
 * concurrent siblings are *admitted* and the resolver picks the winner from
 * signed evidence.
 */
class AuthorityAttestationTest {

    private companion object {
        val LAPTOP = AuthorityDeviceId("aaaa1111")
        val PHONE = AuthorityDeviceId("bbbb2222")
        val TABLET = AuthorityDeviceId("cccc3333")
        val STRANGER = AuthorityDeviceId("dddd4444")
        val SCOPE = AuthorityScope.category(PeerId("npub1friend"), SharingCategory.VIDEOS)
        val OTHER_SCOPE = AuthorityScope.category(PeerId("npub1other"), SharingCategory.VIDEOS)
    }

    /** Public keys are "pk-<device>", and a signature is the key plus the id. */
    private val verifier = AuthorityAttestationVerifier { attestation, publicKey ->
        attestation.signature == "$publicKey:${attestation.id.value}"
    }

    private fun manifest(
        vararg devices: Pair<AuthorityDeviceId, DeviceRole>,
        generation: Long = 1,
        fenced: Set<AuthorityDeviceId> = emptySet(),
        failClosed: String? = null,
    ) = DeviceAuthorityState(
        authorityGeneration = generation,
        head = LedgerEntryId("m-1"),
        devices = devices.associate { (device, role) ->
            device to DeviceRecord(
                device = device,
                publicKey = "pk-${device.value}",
                role = role,
                enrolledInGeneration = 1,
                fenced = device in fenced,
            )
        },
        failClosedReason = failClosed,
    ).readableAtItsOwnHead()

    private fun attest(
        id: String,
        device: AuthorityDeviceId,
        effect: AuthorityEffect,
        parent: String? = null,
        generation: Long = 1,
        scope: AuthorityScope = SCOPE,
        capability: DeviceCapability = DeviceCapability.MUTATE_PERMISSIONS,
        signature: String? = null,
    ) = AuthorityAttestation(
        id = LedgerEntryId(id),
        scope = scope,
        subject = LedgerEntryId("entry-$id"),
        device = device,
        parent = parent?.let { LedgerEntryId(it) },
        manifestContext = FIXTURE_MANIFEST_CONTEXT,
        authorityGeneration = generation,
        capability = capability,
        effect = effect,
        signature = signature ?: "pk-${device.value}:$id",
    )

    private fun check(
        existing: List<AuthorityAttestation>,
        candidate: AuthorityAttestation,
        manifest: DeviceAuthorityState,
    ) = AuthorityAttestationAdmission.check(existing, candidate, manifest, verifier)

    // ------------------------------------------------------------- admission

    @Test
    fun a_trusted_device_may_change_permissions() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)

        assertIs<LedgerAdmission.Accept>(
            check(emptyList(), attest("a", PHONE, AuthorityEffect.PERMISSIVE), state),
        )
    }

    /**
     * The capability is checked *before* anything is stored, not read back
     * afterwards. An attestation in an append-only ledger cannot be taken out
     * again, so "we will ignore it at read time" is not the same guarantee.
     */
    @Test
    fun a_read_only_device_may_not_change_permissions() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.READ_ONLY)

        val verdict = check(emptyList(), attest("a", PHONE, AuthorityEffect.PERMISSIVE), state)

        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("MUTATE_PERMISSIONS"))
    }

    @Test
    fun a_trusted_device_may_not_administer_devices() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)

        assertIs<LedgerAdmission.Reject>(
            check(
                emptyList(),
                attest("a", PHONE, AuthorityEffect.PERMISSIVE, capability = DeviceCapability.ADMINISTER_DEVICES),
                state,
            ),
        )
        assertIs<LedgerAdmission.Accept>(
            check(
                emptyList(),
                attest("b", LAPTOP, AuthorityEffect.PERMISSIVE, capability = DeviceCapability.ADMINISTER_DEVICES),
                state,
            ),
        )
    }

    @Test
    fun only_a_primary_device_may_claim_a_sovereign_reset() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)

        assertIs<LedgerAdmission.Reject>(
            check(
                emptyList(),
                attest("a", PHONE, AuthorityEffect.RESTRICTIVE, capability = DeviceCapability.SOVEREIGN_RESET),
                state,
            ),
        )
        assertIs<LedgerAdmission.Accept>(
            check(
                emptyList(),
                attest("b", LAPTOP, AuthorityEffect.RESTRICTIVE, capability = DeviceCapability.SOVEREIGN_RESET),
                state,
            ),
        )
    }

    @Test
    fun a_device_the_manifest_never_named_is_refused() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY)

        assertIs<LedgerAdmission.Reject>(
            check(emptyList(), attest("a", STRANGER, AuthorityEffect.RESTRICTIVE), state),
        )
    }

    @Test
    fun a_revoked_device_is_refused() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.REVOKED)

        assertIs<LedgerAdmission.Reject>(
            check(emptyList(), attest("a", PHONE, AuthorityEffect.PERMISSIVE), state),
        )
    }

    @Test
    fun a_fenced_device_is_refused_even_though_its_role_still_reads_trusted() {
        val state = manifest(
            LAPTOP to DeviceRole.PRIMARY,
            PHONE to DeviceRole.TRUSTED,
            fenced = setOf(PHONE),
        )

        assertIs<LedgerAdmission.Reject>(
            check(emptyList(), attest("a", PHONE, AuthorityEffect.PERMISSIVE), state),
        )
    }

    /**
     * A signature is checked against the key the *manifest* holds for that
     * device. Taking the key from the attestation would let anything sign for
     * anyone: the attacker supplies both halves of the check.
     */
    @Test
    fun a_signature_is_checked_against_the_manifests_key_not_the_attestations() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)

        val forged = attest("a", PHONE, AuthorityEffect.PERMISSIVE, signature = "pk-${LAPTOP.value}:a")

        assertIs<LedgerAdmission.Reject>(check(emptyList(), forged, state))
    }

    @Test
    fun an_unsigned_attestation_never_reaches_storage() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)

        assertIs<LedgerAdmission.Reject>(
            check(emptyList(), attest("a", PHONE, AuthorityEffect.PERMISSIVE, signature = ""), state),
        )
    }

    @Test
    fun a_manifest_that_failed_closed_admits_nothing() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, failClosed = "unreadable entry")

        assertIs<LedgerAdmission.Reject>(
            check(emptyList(), attest("a", LAPTOP, AuthorityEffect.RESTRICTIVE), state),
        )
    }

    // ---------------------------------------------------------- generations

    @Test
    fun a_stale_generation_is_refused() {
        val state = manifest(PHONE to DeviceRole.TRUSTED, generation = 3)

        val verdict = check(emptyList(), attest("a", PHONE, AuthorityEffect.PERMISSIVE, generation = 2), state)

        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("generation"))
    }

    /** A generation the manifest has not established yet cannot be claimed. */
    @Test
    fun a_generation_from_the_future_is_refused() {
        val state = manifest(PHONE to DeviceRole.TRUSTED, generation = 1)

        assertIs<LedgerAdmission.Reject>(
            check(emptyList(), attest("a", PHONE, AuthorityEffect.PERMISSIVE, generation = 9), state),
        )
    }

    // ------------------------------------------------------------- linking

    @Test
    fun a_parent_that_is_not_stored_is_refused() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)

        assertIs<LedgerAdmission.Reject>(
            check(emptyList(), attest("a", PHONE, AuthorityEffect.PERMISSIVE, parent = "nowhere"), state),
        )
    }

    @Test
    fun an_attestation_that_is_its_own_parent_is_refused() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)

        assertIs<LedgerAdmission.Reject>(
            check(emptyList(), attest("a", PHONE, AuthorityEffect.PERMISSIVE, parent = "a"), state),
        )
    }

    /**
     * The DAG rule. Two devices that never saw each other both build on `a`;
     * both are stored, and the resolver decides. Refusing the second would put
     * relay order in charge of the answer.
     */
    @Test
    fun two_concurrent_siblings_on_one_parent_are_both_admitted() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)
        val root = attest("a", LAPTOP, AuthorityEffect.PERMISSIVE)

        val fromLaptop = attest("b", LAPTOP, AuthorityEffect.PERMISSIVE, parent = "a")
        val fromPhone = attest("c", PHONE, AuthorityEffect.RESTRICTIVE, parent = "a")

        assertIs<LedgerAdmission.Accept>(check(listOf(root), fromLaptop, state))
        assertIs<LedgerAdmission.Accept>(check(listOf(root, fromLaptop), fromPhone, state))
    }

    @Test
    fun a_byte_identical_duplicate_is_idempotent() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)
        val one = attest("a", PHONE, AuthorityEffect.PERMISSIVE)

        assertIs<LedgerAdmission.AlreadyPresent>(check(listOf(one), one, state))
    }

    @Test
    fun one_id_may_not_be_reused_for_a_different_decision() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)
        val one = attest("a", PHONE, AuthorityEffect.PERMISSIVE)
        val other = one.copy(effect = AuthorityEffect.RESTRICTIVE)

        assertIs<LedgerAdmission.Reject>(check(listOf(one), other, state))
    }

    @Test
    fun admission_writes_nothing_and_says_why() {
        val state = manifest(PHONE to DeviceRole.READ_ONLY)
        val existing = listOf(attest("a", PHONE, AuthorityEffect.PERMISSIVE))
        val snapshot = existing.toList()

        val verdict = check(existing, attest("b", PHONE, AuthorityEffect.PERMISSIVE), state)

        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.isNotBlank())
        assertEquals(snapshot, existing)
    }

    // -------------------------------------------------------------- winners

    private fun winners(vararg attestations: AuthorityAttestation, manifest: DeviceAuthorityState) =
        AuthorityLedger.winners(trusted(attestations.toList(), manifest))

    @Test
    fun the_only_act_in_a_scope_wins_it() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)
        val one = attest("a", PHONE, AuthorityEffect.PERMISSIVE)

        assertEquals(one, winners(one, manifest = state)[SCOPE])
    }

    /** Mandatory matrix: same parent, one allows and one denies. */
    @Test
    fun a_concurrent_denial_beats_a_concurrent_allow_on_the_same_parent() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val root = attest("a", LAPTOP, AuthorityEffect.PERMISSIVE)
        val allow = attest("zzz-allow", LAPTOP, AuthorityEffect.PERMISSIVE, parent = "a")
        val deny = attest("aaa-deny", PHONE, AuthorityEffect.RESTRICTIVE, parent = "a")

        assertEquals(deny, winners(root, allow, deny, manifest = state)[SCOPE])
        // ...and the id order it would have lost on is not what decided it.
        assertEquals(deny, winners(root, deny, allow, manifest = state)[SCOPE])
    }

    /** Mandatory matrix: revoke versus grant. */
    @Test
    fun a_concurrent_revoke_beats_a_concurrent_grant() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)
        val grant = attest("a-grant", LAPTOP, AuthorityEffect.PERMISSIVE)
        val revoke = attest("b-revoke", PHONE, AuthorityEffect.RESTRICTIVE)

        assertEquals(revoke, winners(grant, revoke, manifest = state)[SCOPE])
    }

    /** Mandatory matrix: two trusted devices, same effect. */
    @Test
    fun two_trusted_devices_with_the_same_effect_are_decided_by_canonical_id() {
        val state = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED)
        val fromLaptop = attest("z", LAPTOP, AuthorityEffect.PERMISSIVE)
        val fromPhone = attest("y", PHONE, AuthorityEffect.PERMISSIVE)

        // LAPTOP's id sorts first, and nothing above device id separates them.
        assertEquals(fromLaptop, winners(fromLaptop, fromPhone, manifest = state)[SCOPE])
        assertEquals(fromLaptop, winners(fromPhone, fromLaptop, manifest = state)[SCOPE])
    }

    /** Mandatory matrix: primary versus trusted. */
    @Test
    fun a_primary_device_outranks_a_trusted_one_on_the_same_effect() {
        val state = manifest(TABLET to DeviceRole.PRIMARY, LAPTOP to DeviceRole.TRUSTED)
        // TABLET's device id sorts *after* LAPTOP's, so rank has to be what wins.
        val fromPrimary = attest("z", TABLET, AuthorityEffect.PERMISSIVE)
        val fromTrusted = attest("a", LAPTOP, AuthorityEffect.PERMISSIVE)

        assertEquals(fromPrimary, winners(fromPrimary, fromTrusted, manifest = state)[SCOPE])
    }

    @Test
    fun a_later_act_supersedes_the_one_it_was_built_on() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)
        val first = attest("a", PHONE, AuthorityEffect.RESTRICTIVE)
        val second = attest("b", PHONE, AuthorityEffect.PERMISSIVE, parent = "a")

        // Causal successor, even though restrictive would win a tie.
        assertEquals(second, winners(first, second, manifest = state)[SCOPE])
    }

    @Test
    fun scopes_are_decided_independently() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)
        val here = attest("a", PHONE, AuthorityEffect.RESTRICTIVE)
        val there = attest("b", PHONE, AuthorityEffect.PERMISSIVE, scope = OTHER_SCOPE)

        val decided = winners(here, there, manifest = state)

        assertEquals(here, decided[SCOPE])
        assertEquals(there, decided[OTHER_SCOPE])
    }

    /**
     * It used to be dropped, and the rest resolved — which reads as the
     * laptop's grant standing unopposed, while the phone's denial is sitting
     * right there on disk simply not being counted. A history missing a
     * restrictive step cannot be told apart from one that never had it, so
     * nothing is decided at all.
     */
    @Test
    fun an_act_from_a_device_that_lost_its_authority_decides_nothing_at_all() {
        val revoked = manifest(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.REVOKED)
        val fromRevoked = attest("a", PHONE, AuthorityEffect.RESTRICTIVE)
        val fromLaptop = attest("b", LAPTOP, AuthorityEffect.PERMISSIVE)

        assertEquals(emptyMap(), winners(fromRevoked, fromLaptop, manifest = revoked))
        assertEquals(
            fromLaptop,
            winners(fromLaptop, manifest = revoked)[SCOPE],
            "the laptop alone still decides, so it is the revoked act that closes this",
        )
    }

    @Test
    fun a_scope_where_nothing_carries_authority_has_no_winner() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY)

        assertNull(winners(attest("a", STRANGER, AuthorityEffect.RESTRICTIVE), manifest = state)[SCOPE])
    }

    // ------------------------------------------------------- forged priority

    /**
     * Mandatory matrix: a freely-set priority must not change the outcome. It
     * cannot, because there is nowhere to put one — the attestation carries no
     * weight, rank, timestamp or sequence a device chooses. Rank is looked up
     * in the manifest, and this is the test that says so out loud.
     */
    @Test
    fun there_is_no_field_a_device_can_set_to_make_itself_win() {
        val fields = AuthorityAttestation::class.simpleName
        assertNotNull(fields)

        val state = manifest(TABLET to DeviceRole.READ_ONLY, LAPTOP to DeviceRole.TRUSTED)
        // TABLET would love to outrank LAPTOP. Its role is the only thing that
        // decides, and it is not in its gift.
        val fromTablet = attest("a", TABLET, AuthorityEffect.RESTRICTIVE, capability = DeviceCapability.READ)
        val fromLaptop = attest("b", LAPTOP, AuthorityEffect.PERMISSIVE)

        // READ_ONLY cannot even get its restrictive act admitted...
        assertIs<LedgerAdmission.Reject>(
            check(emptyList(), attest("c", TABLET, AuthorityEffect.RESTRICTIVE), state),
        )
        // ...and were one already stored, the resolver still ranks it last.
        assertEquals(
            DeviceRole.READ_ONLY.rank,
            state.roleOf(TABLET)?.rank,
        )
        assertTrue(state.roleOf(LAPTOP)!!.rank < state.roleOf(TABLET)!!.rank)
        assertEquals(fromTablet, winners(fromTablet, fromLaptop, manifest = state)[SCOPE])
    }

    // ----------------------------------------------- order and duplicates

    @Test
    fun the_winner_does_not_depend_on_the_order_they_arrived_in() {
        val state = manifest(
            LAPTOP to DeviceRole.PRIMARY,
            PHONE to DeviceRole.TRUSTED,
            TABLET to DeviceRole.TRUSTED,
        )
        val all = listOf(
            attest("a", LAPTOP, AuthorityEffect.PERMISSIVE),
            attest("b", PHONE, AuthorityEffect.RESTRICTIVE, parent = "a"),
            attest("c", TABLET, AuthorityEffect.PERMISSIVE, parent = "a"),
            attest("d", LAPTOP, AuthorityEffect.PERMISSIVE, parent = "b"),
            attest("e", PHONE, AuthorityEffect.RESTRICTIVE, scope = OTHER_SCOPE),
        )
        val expected = AuthorityLedger.winners(trusted(all, state))

        val random = Random(20620811)
        repeat(200) {
            assertEquals(expected, AuthorityLedger.winners(trusted(all.shuffled(random), state)))
        }
    }

    @Test
    fun a_duplicate_arriving_twice_changes_nothing() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)
        val one = attest("a", PHONE, AuthorityEffect.PERMISSIVE)

        assertEquals(
            AuthorityLedger.winners(trusted(listOf(one), state)),
            AuthorityLedger.winners(trusted(listOf(one, one, one), state)),
        )
    }

    // -------------------------------------------------------- legacy scopes

    /**
     * A scope from the pre-versioned, ambiguous encoding decides nothing and
     * accepts nothing.
     *
     * It cannot be repaired — the old string does not say which subject it
     * meant — so guessing would be guessing about who can see what.
     */
    @Test
    fun an_act_in_a_legacy_scope_is_refused() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)
        val legacy = attest("a", PHONE, AuthorityEffect.PERMISSIVE, scope = AuthorityScope("peer|npub1x"))

        val verdict = check(emptyList(), legacy, state)

        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("scope"), verdict.reason)
    }

    @Test
    fun a_stored_act_in_a_legacy_scope_wins_nothing() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)
        val legacyScope = AuthorityScope("peer|npub1x")
        val legacy = attest("a", PHONE, AuthorityEffect.RESTRICTIVE, scope = legacyScope)

        assertNull(AuthorityLedger.winners(trusted(listOf(legacy), state))[legacyScope])
    }

    @Test
    fun a_legacy_act_cannot_drag_a_current_scope_down_with_it() {
        val state = manifest(PHONE to DeviceRole.TRUSTED)
        val legacy = attest("a", PHONE, AuthorityEffect.RESTRICTIVE, scope = AuthorityScope("peer|npub1x"))
        val current = attest("b", PHONE, AuthorityEffect.PERMISSIVE)

        val decided = AuthorityLedger.winners(trusted(listOf(legacy, current), state))

        assertEquals(current, decided[SCOPE])
        assertEquals(1, decided.size, "the legacy scope must not appear at all")
    }

    // ------------------------------------------------------- canonical bytes

    @Test
    fun the_canonical_bytes_separate_fields_unambiguously() {
        val a = attest("a", PHONE, AuthorityEffect.PERMISSIVE, scope = AuthorityScope("x|y"))
        val b = attest("a", PHONE, AuthorityEffect.PERMISSIVE, scope = AuthorityScope("x"))
            .copy(subject = LedgerEntryId("|yentry-a"))

        assertTrue(
            !AuthorityAttestationCanonicalForm.bytes(a).contentEquals(
                AuthorityAttestationCanonicalForm.bytes(b),
            ),
            "two different attestations must not share one canonical form",
        )
    }

    @Test
    fun a_genesis_is_distinguishable_from_one_with_an_empty_parent() {
        val genesis = attest("a", PHONE, AuthorityEffect.PERMISSIVE, parent = null)
        val bytes = AuthorityAttestationCanonicalForm.bytes(genesis).decodeToString()

        assertTrue(bytes.contains("none"), "an absent parent needs its own value")
        assertTrue(!bytes.contains(":,"), "no field may encode as empty and collide")
    }

    @Test
    fun the_signature_is_not_part_of_what_is_signed() {
        val one = attest("a", PHONE, AuthorityEffect.PERMISSIVE)

        assertTrue(
            AuthorityAttestationCanonicalForm.bytes(one).contentEquals(
                AuthorityAttestationCanonicalForm.bytes(one.copy(signature = "different")),
            ),
        )
    }

    // -------------------------------------------------- one act per subject

    /**
     * Globally one, not one per batch.
     *
     * A second act for a subject that is already attested is how a weaker claim
     * gets into the ledger as evidence of authority: the entry is already
     * there and paired, so nothing downstream re-checks it, and the extra row
     * reads afterwards as a device having authorised something it never did.
     */
    @Test
    fun a_second_act_for_an_already_attested_subject_is_refused() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)
        val first = attest("a", LAPTOP, AuthorityEffect.PERMISSIVE)
        val second = attest("b", PHONE, AuthorityEffect.RESTRICTIVE)
            .copy(subject = first.subject)

        val verdict = check(listOf(first), second, state)

        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.contains("already"), verdict.reason)
    }

    @Test
    fun re_submitting_the_identical_act_is_still_idempotent() {
        val state = manifest(LAPTOP to DeviceRole.PRIMARY)
        val one = attest("a", LAPTOP, AuthorityEffect.PERMISSIVE)

        assertIs<LedgerAdmission.AlreadyPresent>(check(listOf(one), one, state))
    }
}
