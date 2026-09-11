package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: getting back in, and what that costs the devices that were
 * already there.
 *
 * A restore is the one moment where somebody holding a backup and a recovery
 * code takes over an estate. Everything here follows from that: it needs both
 * credentials, it mints a new authority generation atomically, the returning
 * device becomes PRIMARY, and every device that was there before is fenced
 * until the owner says otherwise.
 *
 * ## Why a stale backup is read-only
 *
 * A backup is a snapshot. Restoring an old one re-establishes permissions that
 * were since taken away — the revocation simply is not in it. So an install
 * that cannot show its backup is current gets a preview it can read and nothing
 * it can write, until either it syncs or the owner deliberately declares a
 * sovereign reset. Guessing in the owner's favour here hands access back to
 * somebody they already removed.
 */
class AuthorityRecoveryTest {

    private companion object {
        val LAPTOP = AuthorityDeviceId("aaaa1111")
        val PHONE = AuthorityDeviceId("bbbb2222")
        val NEW = AuthorityDeviceId("cccc3333")
        val ALICE = PeerId("npub1alice")
        val BOB = PeerId("npub1bob")
    }

    private fun manifest(
        vararg devices: Pair<AuthorityDeviceId, DeviceRole>,
        generation: Long = 3,
    ) = DeviceAuthorityState(
        authorityGeneration = generation,
        head = LedgerEntryId("m-9"),
        devices = devices.associate { (device, role) ->
            device to DeviceRecord(device, "pk-${device.value}", role, generation)
        },
    )

    private fun relationship(peer: PeerId, epoch: Long = 4, deviceGeneration: Long = 2) =
        RelationshipState(
            peer = peer,
            circle = SharingCircle.FRIENDS,
            status = RelationshipStatus.ACCEPTED,
            authorityGeneration = 3,
            resourceEpoch = epoch,
            deviceGeneration = deviceGeneration,
        )

    private val estate = manifest(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED)
    private val relationships = mapOf(ALICE to relationship(ALICE), BOB to relationship(BOB, epoch = 7))

    // -------------------------------------------------------------- the gate

    @Test
    fun both_credentials_are_needed_before_anything_is_restored() {
        assertEquals(
            RecoveryDecision.REFUSED,
            RecoveryGate.decide(
                RecoveryCredentials(rootSigned = false, recoveryCodeMatches = true),
                BackupFreshness.CURRENT,
            ),
        )
        assertEquals(
            RecoveryDecision.REFUSED,
            RecoveryGate.decide(
                RecoveryCredentials(rootSigned = true, recoveryCodeMatches = false),
                BackupFreshness.CURRENT,
            ),
        )
        assertEquals(
            RecoveryDecision.RESTORE,
            RecoveryGate.decide(
                RecoveryCredentials(rootSigned = true, recoveryCodeMatches = true),
                BackupFreshness.CURRENT,
            ),
        )
    }

    @Test
    fun a_stale_backup_is_a_preview_and_not_a_restore() {
        assertEquals(
            RecoveryDecision.PREVIEW_ONLY,
            RecoveryGate.decide(
                RecoveryCredentials(rootSigned = true, recoveryCodeMatches = true),
                BackupFreshness.STALE,
            ),
        )
    }

    /** Not knowing is treated exactly like knowing it is stale. */
    @Test
    fun an_unknown_freshness_is_a_preview_too() {
        assertEquals(
            RecoveryDecision.PREVIEW_ONLY,
            RecoveryGate.decide(
                RecoveryCredentials(rootSigned = true, recoveryCodeMatches = true),
                BackupFreshness.UNKNOWN,
            ),
        )
    }

    /**
     * The one way past a stale backup: the owner says, with the root key, that
     * they are taking the estate back regardless. It costs everything — every
     * device, key and grant — which is what makes it an acceptable override.
     */
    @Test
    fun a_root_signed_sovereign_reset_overrides_a_stale_backup() {
        assertEquals(
            RecoveryDecision.SOVEREIGN_RESET,
            RecoveryGate.decide(
                RecoveryCredentials(rootSigned = true, recoveryCodeMatches = true),
                BackupFreshness.STALE,
                sovereignReset = true,
            ),
        )
    }

    @Test
    fun a_sovereign_reset_still_needs_both_credentials() {
        assertEquals(
            RecoveryDecision.REFUSED,
            RecoveryGate.decide(
                RecoveryCredentials(rootSigned = true, recoveryCodeMatches = false),
                BackupFreshness.CURRENT,
                sovereignReset = true,
            ),
        )
    }

    // ------------------------------------------------------------ the restore

    private fun restorePlan() = AuthorityRecoveryPlanner.plan(
        manifest = estate,
        relationships = relationships,
        newDevice = NEW,
        newDevicePublicKey = "pk-new",
        decision = RecoveryDecision.RESTORE,
    )

    @Test
    fun a_restore_mints_the_next_generation() {
        assertEquals(4L, restorePlan().newGeneration)
    }

    @Test
    fun the_returning_device_becomes_primary() {
        val enrolment = restorePlan().manifestBodies
            .filterIsInstance<DeviceManifestBody.DeviceEnrolled>()
            .single()

        assertEquals(NEW, enrolment.device)
        assertEquals(DeviceRole.PRIMARY, enrolment.role)
        assertEquals("pk-new", enrolment.publicKey)
    }

    @Test
    fun the_rotation_comes_before_the_enrolment() {
        val bodies = restorePlan().manifestBodies

        assertIs<DeviceManifestBody.AuthorityRotated>(bodies.first())
        assertIs<DeviceManifestBody.DeviceEnrolled>(bodies.last())
    }

    /**
     * Fencing is the default, not an option. A restore happens because control
     * was lost; the devices that were there are exactly the ones that might be
     * in somebody else's hands.
     */
    @Test
    fun every_device_that_was_there_before_is_fenced() {
        val plan = restorePlan()

        assertEquals(setOf(LAPTOP, PHONE), plan.fenced)
        assertTrue((plan.manifestBodies.first() as DeviceManifestBody.AuthorityRotated).fenceExisting)
    }

    @Test
    fun the_new_device_is_not_fenced_by_its_own_restore() {
        assertTrue(NEW !in restorePlan().fenced)
    }

    @Test
    fun every_relationships_resource_epoch_moves_on() {
        val plan = restorePlan()

        assertEquals(5L, plan.resourceEpochs[ALICE])
        assertEquals(8L, plan.resourceEpochs[BOB])
    }

    @Test
    fun every_relationships_device_generation_moves_on() {
        assertEquals(mapOf(ALICE to 3L, BOB to 3L), restorePlan().deviceGenerations)
    }

    @Test
    fun a_restore_does_not_revoke_the_relationships_themselves() {
        assertEquals(emptySet(), restorePlan().revokedPeers)
    }

    @Test
    fun administrative_writes_are_open_after_a_restore() {
        assertTrue(!restorePlan().administrativeWritesLocked)
    }

    /**
     * The native transport is still gated shut, so the rotation there is
     * *planned* and not performed. Saying it happened would be a lie the next
     * reader cannot check.
     */
    @Test
    fun the_native_rotation_is_planned_and_not_claimed_to_have_happened() {
        val plan = restorePlan()

        assertTrue(plan.nativeRotation.planned)
        assertTrue(!plan.nativeRotation.performed)
        assertTrue(plan.nativeRotation.reason.isNotBlank())
    }

    // ---------------------------------------------------------- the preview

    private fun previewPlan() = AuthorityRecoveryPlanner.plan(
        manifest = estate,
        relationships = relationships,
        newDevice = NEW,
        newDevicePublicKey = "pk-new",
        decision = RecoveryDecision.PREVIEW_ONLY,
    )

    @Test
    fun a_preview_writes_nothing_at_all() {
        val plan = previewPlan()

        assertEquals(emptyList(), plan.manifestBodies)
        assertEquals(emptyMap(), plan.resourceEpochs)
        assertEquals(emptyMap(), plan.deviceGenerations)
        assertEquals(emptySet(), plan.fenced)
        assertEquals(estate.authorityGeneration, plan.newGeneration)
    }

    @Test
    fun a_preview_locks_administrative_writes() {
        assertTrue(previewPlan().administrativeWritesLocked)
    }

    @Test
    fun a_preview_plans_no_native_rotation() {
        assertTrue(!previewPlan().nativeRotation.planned)
    }

    @Test
    fun a_refusal_plans_nothing_and_stays_locked() {
        val plan = AuthorityRecoveryPlanner.plan(
            manifest = estate,
            relationships = relationships,
            newDevice = NEW,
            newDevicePublicKey = "pk-new",
            decision = RecoveryDecision.REFUSED,
        )

        assertEquals(emptyList(), plan.manifestBodies)
        assertTrue(plan.administrativeWritesLocked)
    }

    // --------------------------------------------------- the sovereign reset

    private fun resetPlan() = AuthorityRecoveryPlanner.plan(
        manifest = estate,
        relationships = relationships,
        newDevice = NEW,
        newDevicePublicKey = "pk-new",
        decision = RecoveryDecision.SOVEREIGN_RESET,
    )

    @Test
    fun a_sovereign_reset_starts_with_the_reset_itself() {
        assertIs<DeviceManifestBody.SovereignReset>(resetPlan().manifestBodies.first())
    }

    @Test
    fun a_sovereign_reset_enrols_the_new_device_as_primary() {
        val enrolment = resetPlan().manifestBodies
            .filterIsInstance<DeviceManifestBody.DeviceEnrolled>()
            .single()

        assertEquals(NEW, enrolment.device)
        assertEquals(DeviceRole.PRIMARY, enrolment.role)
    }

    @Test
    fun a_sovereign_reset_fences_every_earlier_device() {
        assertEquals(setOf(LAPTOP, PHONE), resetPlan().fenced)
    }

    /** The difference from a restore: the grants go too. */
    @Test
    fun a_sovereign_reset_revokes_every_relationship() {
        assertEquals(setOf(ALICE, BOB), resetPlan().revokedPeers)
    }

    @Test
    fun a_sovereign_reset_rotates_the_epochs_as_well() {
        val plan = resetPlan()

        assertEquals(5L, plan.resourceEpochs[ALICE])
        assertEquals(8L, plan.resourceEpochs[BOB])
        assertEquals(mapOf(ALICE to 3L, BOB to 3L), plan.deviceGenerations)
    }

    @Test
    fun a_sovereign_reset_unlocks_administrative_writes() {
        assertTrue(!resetPlan().administrativeWritesLocked)
    }

    @Test
    fun a_sovereign_reset_mints_a_generation_too() {
        assertEquals(4L, resetPlan().newGeneration)
    }

    // ------------------------------------------------------------ freshness

    @Test
    fun a_backup_is_current_when_it_carries_what_this_install_already_knows() {
        assertEquals(
            BackupFreshness.CURRENT,
            RecoveryGate.freshness(backupGeneration = 3, knownGeneration = 3),
        )
        assertEquals(
            BackupFreshness.CURRENT,
            RecoveryGate.freshness(backupGeneration = 4, knownGeneration = 3),
        )
    }

    @Test
    fun a_backup_from_an_earlier_generation_is_stale() {
        assertEquals(
            BackupFreshness.STALE,
            RecoveryGate.freshness(backupGeneration = 2, knownGeneration = 3),
        )
    }

    @Test
    fun nothing_to_compare_against_is_unknown_rather_than_current() {
        assertEquals(
            BackupFreshness.UNKNOWN,
            RecoveryGate.freshness(backupGeneration = 2, knownGeneration = null),
        )
    }

    // ------------------------------------------------- the manifest it makes

    /**
     * The plan has to reduce to what it promised. Building a rotation the
     * reducer then refuses would leave the install with a manifest that grants
     * nothing and no device able to fix it.
     */
    @Test
    fun a_restore_plan_reduces_to_a_manifest_the_new_device_can_use() {
        val verifier = DeviceManifestVerifier { true }
        val plan = restorePlan()

        val entries = plan.manifestBodies.mapIndexed { index, body ->
            DeviceManifestEntry(
                id = LedgerEntryId("r-$index"),
                manifestSequence = index + 1L,
                authorityGeneration = if (body is DeviceManifestBody.AuthorityRotated) {
                    estate.authorityGeneration
                } else {
                    plan.newGeneration
                },
                parent = if (index == 0) null else LedgerEntryId("r-${index - 1}"),
                signerNpub = "npub1owner",
                signature = "sig",
                body = body,
            )
        }

        val reduced = DeviceManifestReducer(verifier, "npub1owner").reduce(entries, emptyList())

        assertEquals(null, reduced.failClosedReason)
        assertEquals(plan.newGeneration, reduced.authorityGeneration)
        assertEquals(DeviceRole.PRIMARY, reduced.roleOf(NEW))
    }

    @Test
    fun a_sovereign_reset_plan_reduces_to_one_usable_device() {
        val verifier = DeviceManifestVerifier { true }
        val plan = resetPlan()

        // The estate first, so the reset has something to revoke.
        val existing = listOf(
            DeviceManifestEntry(
                LedgerEntryId("e-1"), 1, 1, null, "npub1owner", "sig",
                DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk-a", DeviceRole.PRIMARY),
            ),
            DeviceManifestEntry(
                LedgerEntryId("e-2"), 2, 1, LedgerEntryId("e-1"), "npub1owner", "sig",
                DeviceManifestBody.DeviceEnrolled(PHONE, "pk-b", DeviceRole.TRUSTED),
            ),
        )
        val added = plan.manifestBodies.mapIndexed { index, body ->
            DeviceManifestEntry(
                id = LedgerEntryId("r-$index"),
                manifestSequence = existing.size + index + 1L,
                authorityGeneration = if (body is DeviceManifestBody.SovereignReset) 1L else 2L,
                parent = if (index == 0) LedgerEntryId("e-2") else LedgerEntryId("r-${index - 1}"),
                signerNpub = "npub1owner",
                signature = "sig",
                body = body,
            )
        }

        // The estate's own second enrolment needed a device to ask for it. The
        // reset and the enrolment completing it did not: by then the root key
        // has revoked every device, including whichever one is performing the
        // recovery, so no device act could exist.
        val asked = AuthorityPairing.requiredFor(existing[1].body)!!.let { required ->
            AuthorityAttestation(
                id = LedgerEntryId("act-e-2"),
                scope = required.scope,
                subject = existing[1].id,
                device = LAPTOP,
                parent = null,
                manifestContext = ManifestContext.of(listOfNotNull(existing[1].parent)),
                authorityGeneration = 1,
                capability = required.capability,
                effect = required.effect,
                signature = "pk-a:act-e-2",
            )
        }
        val reduced = DeviceManifestReducer(verifier, "npub1owner", FIXTURE_ATTESTATION_VERIFIER)
            .reduce(existing + added, listOf(asked))

        assertEquals(null, reduced.failClosedReason)
        assertEquals(DeviceRole.PRIMARY, reduced.roleOf(NEW))
        assertEquals(null, reduced.roleOf(LAPTOP), "the old primary keeps nothing")
        assertEquals(null, reduced.roleOf(PHONE))
    }
}
