package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: what may be *written* to the manifest, decided before writing.
 *
 * The reducer already fails a broken manifest closed, but refusing to read
 * something is not the same as refusing to store it. The manifest is
 * append-only: one bad entry that reaches the table fails the manifest closed
 * for ever, and with it every device's authority — the app would be bricked out
 * of its own permissions with no way to take the entry back out.
 *
 * So admission runs first and is side-effect free by construction: given the
 * stored entries and a candidate, it returns a verdict and writes nothing.
 */
class DeviceManifestAdmissionTest {

    private companion object {
        const val OWNER = "npub1owner"
        const val IMPOSTOR = "npub1impostor"
        val LAPTOP = AuthorityDeviceId("aaaa1111")
        val PHONE = AuthorityDeviceId("bbbb2222")
    }

    private val verifier = DeviceManifestVerifier { entry ->
        entry.signature == "${entry.signerNpub}:${entry.id.value}"
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

    private val genesis = entry(1, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk", DeviceRole.PRIMARY), null)

    private fun check(existing: List<DeviceManifestEntry>, candidate: DeviceManifestEntry) =
        DeviceManifestAdmission.check(
            existing,
            candidate,
            verifier,
            OWNER,
            (existing + candidate).filter { it.parent != null }.map { ask(it) },
            FIXTURE_ATTESTATION_VERIFIER,
        )

    /** The act a PRIMARY device signs against the estate at [entry]'s parent. */
    private fun ask(entry: DeviceManifestEntry) =
        AuthorityPairing.requiredFor(entry.body)!!.let { required ->
            AuthorityAttestation(
                id = LedgerEntryId("act-${entry.id.value}"),
                scope = required.scope,
                subject = entry.id,
                device = LAPTOP,
                parent = null,
                manifestContext = ManifestContext.of(listOfNotNull(entry.parent)),
                authorityGeneration = 1,
                capability = required.capability,
                effect = required.effect,
                signature = "pk:act-${entry.id.value}",
            )
        }

    // ------------------------------------------------------------- accepted

    @Test
    fun a_well_formed_genesis_is_accepted() {
        assertIs<LedgerAdmission.Accept>(check(emptyList(), genesis))
    }

    @Test
    fun a_well_formed_successor_is_accepted() {
        val next = entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1")

        assertIs<LedgerAdmission.Accept>(check(listOf(genesis), next))
    }

    @Test
    fun a_byte_identical_duplicate_is_idempotent_rather_than_a_conflict() {
        assertIs<LedgerAdmission.AlreadyPresent>(check(listOf(genesis), genesis))
    }

    // ------------------------------------------------------------- refused

    @Test
    fun an_entry_that_does_not_verify_never_reaches_storage() {
        val forged = entry(
            2,
            DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.PRIMARY),
            "m-1",
            signature = "not-a-signature",
        )

        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), forged))
    }

    @Test
    fun an_entry_signed_by_anybody_but_the_owner_is_refused() {
        val notOurs = entry(
            2,
            DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.PRIMARY),
            "m-1",
            signer = IMPOSTOR,
        )

        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), notOurs))
    }

    @Test
    fun a_sequence_that_is_not_the_next_one_is_refused() {
        val gap = entry(3, DeviceManifestBody.DeviceRevoked(LAPTOP), "m-1")
        val replay = entry(1, DeviceManifestBody.DeviceRevoked(LAPTOP), null, id = "m-1b")

        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), gap))
        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), replay))
    }

    /**
     * The parent link is the half that catches a fork. Two devices that have
     * not seen each other both produce "sequence 2, parent m-1"; the second one
     * to arrive must be refused rather than merged, because merging is how a
     * revoked device quietly keeps its role.
     */
    @Test
    fun an_entry_naming_the_wrong_parent_is_refused() {
        val wrongParent = entry(2, DeviceManifestBody.DeviceRevoked(LAPTOP), "m-99")

        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), wrongParent))
    }

    /**
     * A second entry on one parent is admitted now, and the resolver settles
     * it.
     *
     * Refusing it made whichever device reached the database first the one
     * that decided: the root key is reachable from all of them, so two offline
     * devices both legitimately produce "parent head, next sequence". What
     * must not happen is the two being *merged* — and they are not, because
     * only one branch of the device's own scope stands.
     */
    @Test
    fun a_second_entry_claiming_the_same_place_in_history_is_a_fork() {
        val first = entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1")
        val fork = entry(
            2,
            DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.PRIMARY),
            "m-1",
            id = "m-2b",
        )

        assertIs<LedgerAdmission.Accept>(check(listOf(genesis, first), fork))
    }

    /** A sequence that does not follow its parent's still is refused. */
    @Test
    fun a_sequence_that_does_not_follow_its_parent_is_refused() {
        val jumped = entry(9, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1")

        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), jumped))
    }

    /** And a second genesis is not a fork; it is a second estate. */
    @Test
    fun a_second_root_is_refused() {
        val other = entry(1, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.PRIMARY), null, id = "m-1b")

        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), other))
    }

    @Test
    fun one_id_may_not_be_reused_for_different_content() {
        val divergent = genesis.copy(
            body = DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.PRIMARY),
        )

        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), divergent))
    }

    @Test
    fun a_body_this_build_cannot_read_is_refused_rather_than_stored() {
        val unreadable = entry(2, DeviceManifestBody.Unknown("SomethingNewer"), "m-1")

        // Storing it would fail the manifest closed for ever, taking every
        // device's authority with it.
        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), unreadable))
    }

    @Test
    fun an_entry_that_would_leave_the_manifest_unreadable_is_refused() {
        val backwards = entry(2, DeviceManifestBody.AuthorityRotated(0, fenceExisting = true), "m-1")

        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), backwards))
    }

    @Test
    fun revoking_a_device_that_was_never_enrolled_is_refused() {
        val phantom = entry(2, DeviceManifestBody.DeviceRevoked(PHONE), "m-1")

        assertIs<LedgerAdmission.Reject>(check(listOf(genesis), phantom))
    }

    // ------------------------------------------------- capability enforcement

    /**
     * Admission is about the *manifest*, which only the root owner key signs.
     * Which of the owner's devices may author a *permission* change is a
     * different question, answered by the manifest this admission protects —
     * see [DeviceAuthorityState.can].
     */
    @Test
    fun the_reduced_manifest_is_what_decides_who_may_administer_devices() {
        val second = entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1")
        val reduced = DeviceManifestReducer(verifier, OWNER, FIXTURE_ATTESTATION_VERIFIER)
            .reduce(listOf(genesis, second), listOf(ask(second)))

        assertTrue(reduced.can(LAPTOP, DeviceCapability.ADMINISTER_DEVICES))
        assertTrue(!reduced.can(PHONE, DeviceCapability.ADMINISTER_DEVICES))
        assertTrue(reduced.can(PHONE, DeviceCapability.MUTATE_PERMISSIONS))
    }

    @Test
    fun admission_writes_nothing_and_says_why_it_refused() {
        val before = listOf(genesis)
        val snapshot = before.toList()

        val verdict = check(before, entry(9, DeviceManifestBody.DeviceRevoked(LAPTOP), "m-1"))

        assertIs<LedgerAdmission.Reject>(verdict)
        assertTrue(verdict.reason.isNotBlank(), "a refusal has to be explainable to a person")
        assertEquals(snapshot, before, "admission must not touch what it was given")
    }
}
