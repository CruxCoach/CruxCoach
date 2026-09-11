package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * FEAT-062 §11: an act is *ordered* by the role its author held when it signed,
 * not by the role that device holds now.
 *
 * ## The hole this closes
 *
 * The signature check moved to the manifest point an act names, so a device
 * that has since been demoted or revoked still verifies. The **resolver** did
 * not move with it: it looked the author's rank up in the manifest as it stands
 * and dropped anything whose device no longer carried authority. So revoking a
 * device silently deleted the denials it had made — the withdrawal disappears
 * and the grant underneath it stands again — and demoting one changed which of
 * two concurrent changes won, months after both were signed.
 *
 * Both are decisions the owner genuinely made, at a time the manifest vouched
 * for the device that made them. Ending a device's authority stops it saying
 * anything *new*; it cannot rewrite what it already said.
 *
 * ## And the other direction
 *
 * Promotion is not retroactive either. An act a device authored while it was
 * `READ_ONLY` does not become authoritative because the device is `PRIMARY`
 * now — it was never authorised, and no later change to the manifest makes it
 * so.
 */
class AuthorityHistoricalRoleTest {

    private companion object {
        val ALICE = PeerId("npub1alice")
        val LAPTOP = AuthorityDeviceId("aaaa-laptop")
        val PHONE = AuthorityDeviceId("bbbb-phone")
        val SCOPE = AuthorityScope.peer(ALICE)
        val WAS = LedgerEntryId("m-1")
        val NOW = LedgerEntryId("m-2")
    }

    private fun record(device: AuthorityDeviceId, role: DeviceRole) =
        DeviceRecord(device, "pk-${device.value}", role, 1)

    private fun manifestEntry(id: String, seq: Long, parent: String?, body: DeviceManifestBody) =
        DeviceManifestEntry(
            id = LedgerEntryId(id),
            manifestSequence = seq,
            authorityGeneration = 1,
            parent = parent?.let { LedgerEntryId(it) },
            signerNpub = "npub1owner",
            signature = "sig",
            body = body,
        )

    /**
     * A real two-point manifest: the estate at [WAS], and what [NOW] made of it.
     *
     * Acts bind [WAS]; the manifest has moved on since. Assembled as entries
     * rather than as a state, because a bound context is replayed by folding
     * the entries it reaches — there is nothing else for it to be replayed
     * against.
     */
    private fun manifest(
        thenRoles: Map<AuthorityDeviceId, DeviceRole>,
        since: List<DeviceManifestBody>,
        nowRoles: Map<AuthorityDeviceId, DeviceRole>,
    ): DeviceAuthorityState {
        val enrolments = thenRoles.entries.sortedBy { it.key.value }.mapIndexed { index, e ->
            manifestEntry(
                id = if (index == thenRoles.size - 1) WAS.value else "m-0-$index",
                seq = index + 1L,
                parent = if (index == 0) null else "m-0-${index - 1}",
                body = DeviceManifestBody.DeviceEnrolled(e.key, "pk-${e.key.value}", e.value),
            )
        }
        val later = since.mapIndexed { index, body ->
            manifestEntry(
                id = if (index == since.size - 1) NOW.value else "m-1-$index",
                seq = enrolments.size + index + 1L,
                parent = if (index == 0) WAS.value else "m-1-${index - 1}",
                body = body,
            )
        }
        return DeviceAuthorityState(
            authorityGeneration = 1,
            head = NOW,
            devices = nowRoles.mapValues { (device, role) -> record(device, role) },
            frontier = listOf(NOW),
            history = enrolments + later,
        )
    }

    private fun act(
        id: String,
        device: AuthorityDeviceId,
        effect: AuthorityEffect,
        parent: String? = null,
        capability: DeviceCapability = DeviceCapability.MUTATE_PERMISSIONS,
    ) = AuthorityAttestation(
        id = LedgerEntryId(id),
        scope = SCOPE,
        subject = LedgerEntryId("entry-$id"),
        device = device,
        parent = parent?.let { LedgerEntryId(it) },
        manifestContext = ManifestContext.of(listOf(WAS)),
        authorityGeneration = 1,
        capability = capability,
        effect = effect,
        signature = "pk-${device.value}:$id",
    )

    // ------------------------------------ a withdrawal outlives its author

    /**
     * Two concurrent changes: the phone denies, the laptop grants. The phone is
     * revoked afterwards. The denial must still stand — losing it is a
     * withdrawal of access disappearing because of an unrelated later event.
     */
    @Test
    fun a_denial_survives_the_revocation_of_the_device_that_made_it() {
        val state = manifest(
            thenRoles = mapOf(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED),
            since = listOf(DeviceManifestBody.DeviceRevoked(PHONE)),
            nowRoles = mapOf(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.REVOKED),
        )
        val grant = act("a-grant", LAPTOP, AuthorityEffect.PERMISSIVE)
        val deny = act("z-deny", PHONE, AuthorityEffect.RESTRICTIVE)

        listOf(listOf(grant, deny), listOf(deny, grant)).forEach { arrival ->
            val winners = AuthorityLedger.winners(trusted(arrival, state))

            assertEquals(deny, winners[SCOPE], "the denial is what stands, in either arrival order")
        }
    }

    /**
     * Rank is settled the same way. The laptop was PRIMARY when it signed and
     * has been demoted since; that must not hand the tie to the phone.
     */
    @Test
    fun rank_comes_from_the_role_the_author_held_when_it_signed() {
        val state = manifest(
            thenRoles = mapOf(LAPTOP to DeviceRole.PRIMARY, PHONE to DeviceRole.TRUSTED),
            since = listOf(DeviceManifestBody.DeviceRoleChanged(LAPTOP, DeviceRole.READ_ONLY)),
            nowRoles = mapOf(LAPTOP to DeviceRole.READ_ONLY, PHONE to DeviceRole.TRUSTED),
        )
        // Same effect, and the phone's id sorts first, so only rank can decide.
        val fromLaptop = act("z-laptop", LAPTOP, AuthorityEffect.PERMISSIVE)
        val fromPhone = act("a-phone", PHONE, AuthorityEffect.PERMISSIVE)

        listOf(listOf(fromLaptop, fromPhone), listOf(fromPhone, fromLaptop)).forEach { arrival ->
            assertEquals(
                fromLaptop,
                AuthorityLedger.winners(trusted(arrival, state))[SCOPE],
                "the primary device's change stands, in either arrival order",
            )
        }
    }

    /** The relationship projection reads the same answer. */
    @Test
    fun the_branch_projection_keeps_the_denial_too() {
        val state = manifest(
            thenRoles = mapOf(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.TRUSTED),
            since = listOf(DeviceManifestBody.DeviceRevoked(PHONE)),
            nowRoles = mapOf(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.REVOKED),
        )
        fun entry(id: String, body: SharingLedgerBody, parent: String?, sequence: Long) =
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

        val root = entry("entry-root", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1)
        val granted = entry(
            "entry-a-grant",
            SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)),
            "entry-root",
            2,
        )
        val revoked = entry("entry-z-deny", SharingLedgerBody.RelationshipRevoked, "entry-root", 2)
        val acts = listOf(
            act("root", LAPTOP, AuthorityEffect.PERMISSIVE).copy(subject = LedgerEntryId("entry-root")),
            act("a-grant", LAPTOP, AuthorityEffect.PERMISSIVE, parent = "root"),
            act("z-deny", PHONE, AuthorityEffect.RESTRICTIVE, parent = "root"),
        )

        val history = AuthorityBranchResolver.history(listOf(root, granted, revoked), trusted(acts, state))

        assertEquals(
            listOf(LedgerEntryId("entry-root"), LedgerEntryId("entry-z-deny")),
            history.map { it.id },
            "the revocation authored by the since-revoked device is the branch that stands",
        )
    }

    // ------------------------------------------ promotion is not retroactive

    /**
     * An act the device was never allowed to make does not become allowed
     * because the manifest promoted it afterwards.
     */
    @Test
    fun an_act_authored_without_the_capability_stays_refused_after_a_promotion() {
        val state = manifest(
            thenRoles = mapOf(LAPTOP to DeviceRole.READ_ONLY),
            since = listOf(DeviceManifestBody.DeviceRoleChanged(LAPTOP, DeviceRole.PRIMARY)),
            nowRoles = mapOf(LAPTOP to DeviceRole.PRIMARY),
        )

        val checked = trusted(listOf(act("a", LAPTOP, AuthorityEffect.PERMISSIVE)), state)

        assertNotNull(checked.failClosedReason, "it was never authorised, and promotion does not backdate")
        assertEquals(emptyMap(), AuthorityLedger.winners(checked))
    }

    @Test
    fun an_act_from_a_device_that_held_nothing_then_stays_refused_now() {
        val state = manifest(
            thenRoles = mapOf(LAPTOP to DeviceRole.TRUSTED),
            since = listOf(DeviceManifestBody.DeviceEnrolled(PHONE, "pk-${PHONE.value}", DeviceRole.PRIMARY)),
            nowRoles = mapOf(LAPTOP to DeviceRole.TRUSTED, PHONE to DeviceRole.PRIMARY),
        )

        assertNotNull(trusted(listOf(act("a", PHONE, AuthorityEffect.PERMISSIVE)), state).failClosedReason)
        assertNull(AuthorityLedger.winners(trusted(listOf(act("a", PHONE, AuthorityEffect.PERMISSIVE)), state))[SCOPE])
    }
}
