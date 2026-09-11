package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: every act is checked against the manifest on every read.
 *
 * ## Why once was not enough
 *
 * [AuthorityAttestationAdmission] verifies a signature on the way in, and the
 * row was then believed for ever after. But that check happened in our process,
 * against a table anything with write access can reach — a restored backup, a
 * rooted device, a bug of our own, a hand-edited file. Put a row in by any of
 * those routes and every read afterwards took it as evidence that one of the
 * owner's devices had authorised a permission change. "It was checked when it
 * was written" is a claim about a past the file does not record.
 *
 * ## What a failure costs
 *
 * Everything. Skipping the act would leave a history missing a step, and a
 * history missing a restrictive step is indistinguishable from one that never
 * had it — the older grant underneath would quietly stand. So nothing resolves
 * and nothing is released.
 *
 * ## What it deliberately does not do
 *
 * Void history. A device that is revoked or fenced stops speaking; it does not
 * un-say what it already said. Every act on disk was authored by a device that
 * a recovery has since fenced, so the other reading would leave a restored
 * install permanently unreadable — including the entries the returning device
 * is writing right now. An act stands for the generation its author held, and
 * for no later one.
 */
class AuthorityTrustTest {

    private companion object {
        val ALICE = PeerId("npub1alice")
        val LAPTOP = AuthorityDeviceId("aaaa-laptop")
        val PHONE = AuthorityDeviceId("bbbb-phone")
        val STRANGER = AuthorityDeviceId("zzzz-stranger")
        val SCOPE = AuthorityScope.peer(ALICE)
    }

    private fun manifest(
        generation: Long = 2,
        vararg devices: DeviceRecord,
        failClosed: String? = null,
    ) = DeviceAuthorityState(
        authorityGeneration = generation,
        head = LedgerEntryId("m-1"),
        devices = devices.associateBy { it.device },
        failClosedReason = failClosed,
    )

    private fun record(
        device: AuthorityDeviceId,
        role: DeviceRole = DeviceRole.TRUSTED,
        enrolled: Long = 1,
        fenced: Boolean = false,
        revokedIn: Long? = null,
    ) = DeviceRecord(device, "pk-${device.value}", role, enrolled, fenced, revokedIn)

    private fun act(
        id: String,
        device: AuthorityDeviceId,
        generation: Long = 2,
        capability: DeviceCapability = DeviceCapability.MUTATE_PERMISSIONS,
        signature: String? = null,
    ) = AuthorityAttestation(
        id = LedgerEntryId(id),
        scope = SCOPE,
        subject = LedgerEntryId("entry-$id"),
        device = device,
        parent = null,
        manifestContext = FIXTURE_MANIFEST_CONTEXT,
        authorityGeneration = generation,
        capability = capability,
        effect = AuthorityEffect.PERMISSIVE,
        signature = signature ?: "pk-${device.value}:$id",
    )

    private fun manifestEntry(id: String, seq: Long, parent: String?, body: DeviceManifestBody) =
        DeviceManifestEntry(
            id = LedgerEntryId(id),
            manifestSequence = seq,
            authorityGeneration = 2,
            parent = parent?.let { LedgerEntryId(it) },
            signerNpub = "npub1owner",
            signature = "sig",
            body = body,
        )

    /**
     * A two-point manifest: the estate at `m-0`, and what `m-1` made of it.
     *
     * Real entries, because a bound context is replayed by folding the entries
     * it reaches.
     */
    private fun past(before: DeviceRecord, since: DeviceManifestBody, now: DeviceRecord) =
        DeviceAuthorityState(
            authorityGeneration = 2,
            head = LedgerEntryId("m-1"),
            devices = mapOf(now.device to now),
            frontier = listOf(LedgerEntryId("m-1")),
            history = listOf(
                manifestEntry(
                    "m-0", 1, null,
                    DeviceManifestBody.DeviceEnrolled(before.device, before.publicKey, before.role),
                ),
                manifestEntry("m-1", 2, "m-0", since),
            ),
        )

    private fun reasonFor(acts: List<AuthorityAttestation>, manifest: DeviceAuthorityState) =
        trusted(acts, manifest).failClosedReason

    // ------------------------------------------------------------- the check

    @Test
    fun an_act_its_device_signed_is_trusted() {
        val state = manifest(devices = arrayOf(record(LAPTOP)))

        assertNull(reasonFor(listOf(act("a", LAPTOP)), state))
    }

    /** An install holding nothing at all is not a broken install. */
    @Test
    fun no_acts_at_all_is_not_a_failure() {
        assertNull(reasonFor(emptyList(), manifest(devices = arrayOf(record(LAPTOP)))))
    }

    @Test
    fun a_signature_that_does_not_verify_closes_the_estate() {
        val state = manifest(devices = arrayOf(record(LAPTOP)))

        val reason = assertNotNull(reasonFor(listOf(act("a", LAPTOP, signature = "forged")), state))
        assertTrue(reason.contains("verify"), reason)
    }

    /**
     * The key comes from the manifest, never from the act. Moving a valid act
     * onto another enrolled device has to fail, or an attacker supplies both
     * halves of the check.
     */
    @Test
    fun an_act_attributed_to_a_device_that_did_not_sign_it_closes_the_estate() {
        val state = manifest(devices = arrayOf(record(LAPTOP), record(PHONE)))
        val moved = act("a", LAPTOP).copy(device = PHONE)

        assertNotNull(reasonFor(listOf(moved), state))
    }

    @Test
    fun an_act_from_a_device_the_manifest_never_named_closes_the_estate() {
        val state = manifest(devices = arrayOf(record(LAPTOP)))

        val reason = assertNotNull(reasonFor(listOf(act("a", STRANGER)), state))
        assertTrue(reason.contains("did not name"), reason)
    }

    @Test
    fun an_act_claiming_a_generation_nobody_established_closes_the_estate() {
        val state = manifest(devices = arrayOf(record(LAPTOP)))

        val reason = assertNotNull(reasonFor(listOf(act("a", LAPTOP, generation = 9)), state))
        assertTrue(reason.contains("generation"), reason)
    }

    @Test
    fun an_act_needing_more_than_its_devices_role_allows_closes_the_estate() {
        val state = manifest(devices = arrayOf(record(LAPTOP, role = DeviceRole.READ_ONLY)))

        val reason = assertNotNull(
            reasonFor(listOf(act("a", LAPTOP, capability = DeviceCapability.SOVEREIGN_RESET)), state),
        )
        assertTrue(reason.contains("SOVEREIGN_RESET"), reason)
    }

    @Test
    fun a_manifest_that_did_not_reduce_binds_no_keys_at_all() {
        val state = manifest(devices = arrayOf(record(LAPTOP)), failClosed = "the manifest forks")

        assertNotNull(reasonFor(listOf(act("a", LAPTOP)), state))
    }

    // ------------------------------------------- revoked, fenced, and history

    /**
     * The blocker's case. LAPTOP authorised an enrolment while it was PRIMARY
     * and has since been demoted; checking the act against the role it holds
     * *now* refuses it, which closes an estate whose history is perfectly
     * sound. What it held at the point it signed against is the question.
     */
    @Test
    fun an_act_is_judged_at_the_head_it_names_not_by_todays_roles() {
        val now = past(
            before = record(LAPTOP, DeviceRole.PRIMARY),
            since = DeviceManifestBody.DeviceRoleChanged(LAPTOP, DeviceRole.READ_ONLY),
            now = record(LAPTOP, DeviceRole.READ_ONLY),
        )

        assertNull(
            reasonFor(
                listOf(
                    act("a", LAPTOP, capability = DeviceCapability.ADMINISTER_DEVICES)
                        .copy(manifestContext = ManifestContext.of(listOf(LedgerEntryId("m-0")))),
                ),
                now,
            ),
        )
    }

    /**
     * Standing is read at the head too. An act naming a point where its author
     * was already revoked is refused there — nothing has to be inferred from a
     * generation number.
     */
    @Test
    fun an_act_naming_a_head_where_its_author_was_already_revoked_closes_the_estate() {
        val state = past(
            before = record(LAPTOP, DeviceRole.TRUSTED),
            since = DeviceManifestBody.DeviceRevoked(LAPTOP),
            now = record(LAPTOP, DeviceRole.REVOKED, fenced = true, revokedIn = 2),
        )

        assertNotNull(reasonFor(listOf(act("a", LAPTOP)), state))
    }

    /**
     * But what it signed before the revocation still stands, because that act
     * names the point where it was still trusted. Revoking a device that may be
     * lost stops it authoring anything further; it does not un-say the
     * decisions the owner genuinely made from it.
     */
    @Test
    fun what_a_revoked_device_signed_while_it_was_trusted_still_stands() {
        val state = past(
            before = record(LAPTOP, DeviceRole.TRUSTED),
            since = DeviceManifestBody.DeviceRevoked(LAPTOP),
            now = record(LAPTOP, DeviceRole.REVOKED, fenced = true, revokedIn = 2),
        )

        assertNull(
            reasonFor(
                listOf(act("a", LAPTOP).copy(manifestContext = ManifestContext.of(listOf(LedgerEntryId("m-0"))))),
                state,
            ),
        )
    }

    /**
     * A recovery fences every device present, and every act on disk was signed
     * by one of them. Judged against the estate as it stands they would all
     * fail, leaving a restored install permanently unreadable — including the
     * entries the returning device is writing right now.
     */
    @Test
    fun what_a_fenced_device_signed_before_the_rotation_still_stands() {
        val state = past(
            before = record(LAPTOP, DeviceRole.TRUSTED),
            since = DeviceManifestBody.DeviceRevoked(LAPTOP),
            now = record(LAPTOP, fenced = true),
        )

        assertNull(
            reasonFor(
                listOf(act("a", LAPTOP).copy(manifestContext = ManifestContext.of(listOf(LedgerEntryId("m-0"))))),
                state,
            ),
        )
    }

    @Test
    fun an_act_naming_a_head_this_install_does_not_hold_closes_the_estate() {
        val state = manifest(devices = arrayOf(record(LAPTOP)))

        val reason = assertNotNull(
            reasonFor(
                listOf(act("a", LAPTOP).copy(manifestContext = ManifestContext.of(listOf(LedgerEntryId("m-99"))))),
                state,
            ),
        )
        assertTrue(reason.contains("m-99"), reason)
    }

    /** An id from another ledger entirely is not a point in this manifest. */
    @Test
    fun an_act_naming_a_head_that_is_not_a_manifest_entry_closes_the_estate() {
        val state = manifest(devices = arrayOf(record(LAPTOP)))

        assertNotNull(
            reasonFor(
                listOf(act("a", LAPTOP).copy(manifestContext = ManifestContext.of(listOf(LedgerEntryId("entry-a"))))),
                state,
            ),
        )
    }

    /** An inconsistent record — no authority, no generation recorded — is refused. */
    @Test
    fun a_role_that_carries_nothing_held_nothing() {
        val state = manifest(devices = arrayOf(record(LAPTOP, DeviceRole.REVOKED)))

        assertNotNull(reasonFor(listOf(act("a", LAPTOP)), state))
    }

    // ---------------------------------------------- what everything else sees

    @Test
    fun nothing_resolves_and_nothing_projects_when_an_act_is_untrustworthy() {
        val state = manifest(devices = arrayOf(record(LAPTOP)))
        val entry = SharingLedgerEntry(
            id = LedgerEntryId("entry-a"),
            peer = ALICE,
            policySequence = 1,
            parent = null,
            authorityGeneration = 2,
            resourceEpoch = 1,
            deviceGeneration = 1,
            signerNpub = "npub1owner",
            signature = "sig",
            body = SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS),
        )
        val forged = act("a", LAPTOP, signature = "forged").copy(effect = AuthorityEffect.PERMISSIVE)

        val checked = trusted(listOf(forged), state)

        assertEquals(emptyList(), checked.acts, "an unreadable set carries no acts forward")
        assertEquals(emptyMap(), AuthorityLedger.winners(checked))
        assertEquals(emptyList(), AuthorityBranchResolver.history(listOf(entry), checked))
        assertNotNull(
            SharingLedgerReducer({ true }, "npub1owner").reduce(ALICE, listOf(entry), checked).failClosedReason,
            "and the relationship says why rather than releasing anything",
        )
    }
}
