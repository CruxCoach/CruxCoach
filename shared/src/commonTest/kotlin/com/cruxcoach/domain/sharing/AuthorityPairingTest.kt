package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: what an act is allowed to claim about the change it authorises.
 *
 * The hole this closes: the attestation used to carry its own `capability`, and
 * admission checked *that* against the device's role. A `READ_ONLY` device could
 * therefore write `capability = READ`, pass the role check, and attach the act
 * to a `GrantChanged` — authorising a permission change with an act that only
 * ever claimed to be a read.
 *
 * So the capability, the scope and the effect are all **derived from the signed
 * body of the entry being authorised**, and the act has to match what the body
 * requires. A device cannot influence the requirement: it is a function of
 * content the owner's key already signed.
 */
class AuthorityPairingTest {

    private companion object {
        val ALICE = PeerId("npub1alice")
        val PHONE = AuthorityDeviceId("bbbb2222")
        val SUBJECT = LedgerEntryId("entry-1")
    }

    private fun act(
        scope: AuthorityScope,
        effect: AuthorityEffect,
        capability: DeviceCapability,
        subject: LedgerEntryId = SUBJECT,
    ) = AuthorityAttestation(
        id = LedgerEntryId("act-1"),
        scope = scope,
        subject = subject,
        device = PHONE,
        parent = null,
        manifestContext = FIXTURE_MANIFEST_CONTEXT,
        authorityGeneration = 1,
        capability = capability,
        effect = effect,
        signature = "sig",
    )

    // ------------------------------------------- what a body actually requires

    @Test
    fun a_grant_requires_permission_to_mutate_permissions() {
        val required = AuthorityPairing.requiredFor(
            ALICE,
            SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)),
        )

        assertEquals(AuthorityScope.peer(ALICE), required?.scope)
        assertEquals(AuthorityEffect.PERMISSIVE, required?.effect)
        assertEquals(DeviceCapability.MUTATE_PERMISSIONS, required?.capability)
    }

    @Test
    fun a_revocation_requires_a_restrictive_act() {
        val required = AuthorityPairing.requiredFor(ALICE, SharingLedgerBody.RelationshipRevoked)

        assertEquals(AuthorityEffect.RESTRICTIVE, required?.effect)
    }

    @Test
    fun a_peers_device_gets_its_own_scope() {
        val required = AuthorityPairing.requiredFor(
            ALICE,
            SharingLedgerBody.DeviceRevoked(DeviceId("dev-1")),
        )

        assertEquals(AuthorityScope.peerDevice(ALICE, DeviceId("dev-1")), required?.scope)
        assertEquals(AuthorityEffect.RESTRICTIVE, required?.effect)
    }

    /**
     * Consent is the peer's, and is carried by the *peer's* signature. Demanding
     * an owner-device act for it would mean the owner authorising somebody
     * else's decision, which is the opposite of what consent is.
     */
    @Test
    fun a_peer_authored_body_needs_no_owner_act_at_all() {
        listOf(
            SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), DeviceId("d")),
            SharingLedgerBody.RecipientDeclined,
            SharingLedgerBody.DeviceAuthorized(DeviceId("d")),
        ).forEach { body ->
            assertNull(AuthorityPairing.requiredFor(ALICE, body), "should be peer-authored: $body")
        }
    }

    @Test
    fun a_body_this_build_cannot_read_requires_the_strongest_thing_there_is() {
        val required = AuthorityPairing.requiredFor(ALICE, SharingLedgerBody.Unknown("Newer"))

        // Fail closed: an unreadable body may be anything, so it may only be
        // authorised by a device that could have authorised anything.
        assertEquals(DeviceCapability.SOVEREIGN_RESET, required?.capability)
        assertEquals(AuthorityEffect.RESTRICTIVE, required?.effect)
    }

    @Test
    fun a_policy_body_is_scoped_to_what_it_changes() {
        assertEquals(
            AuthorityScope.baseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS),
            AuthorityPairing.requiredFor(
                OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            )?.scope,
        )
        assertEquals(
            AuthorityScope.category(ALICE, SharingCategory.VIDEOS),
            AuthorityPairing.requiredFor(
                OwnerPolicyBody.PeerRuleSet(ALICE, SharingCategory.VIDEOS, AccessEffect.ALLOW),
            )?.scope,
        )
        assertEquals(
            AuthorityScope.objectRule(ALICE, ObjectId("vid-1")),
            AuthorityPairing.requiredFor(
                OwnerPolicyBody.ObjectRuleSet(ALICE, ObjectId("vid-1"), SharingCategory.VIDEOS, AccessEffect.DENY),
            )?.scope,
        )
    }

    @Test
    fun a_denying_policy_body_requires_a_restrictive_act() {
        assertEquals(
            AuthorityEffect.RESTRICTIVE,
            AuthorityPairing.requiredFor(
                OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
            )?.effect,
        )
        assertEquals(
            AuthorityEffect.RESTRICTIVE,
            AuthorityPairing.requiredFor(
                OwnerPolicyBody.PeerRuleSet(ALICE, SharingCategory.VIDEOS, AccessEffect.DENY),
            )?.effect,
        )
    }

    @Test
    fun a_manifest_body_requires_the_right_to_administer_devices() {
        val enrol = AuthorityPairing.requiredFor(
            DeviceManifestBody.DeviceEnrolled(PHONE, "pk", DeviceRole.TRUSTED),
        )

        // Its own device's scope, not the estate's: enrolling a phone and
        // revoking a tablet are independent facts and must not compete.
        assertEquals(AuthorityScope.ownerDevice(PHONE), enrol?.scope)
        assertEquals(DeviceCapability.ADMINISTER_DEVICES, enrol?.capability)
        assertEquals(AuthorityEffect.PERMISSIVE, enrol?.effect)
    }

    @Test
    fun a_sovereign_reset_body_requires_the_sovereign_reset_capability() {
        val required = AuthorityPairing.requiredFor(DeviceManifestBody.SovereignReset)

        assertEquals(DeviceCapability.SOVEREIGN_RESET, required?.capability)
        assertEquals(AuthorityEffect.RESTRICTIVE, required?.effect)
    }

    // ------------------------------------------------------------- the pairing

    private fun required() = AuthorityPairing.requiredFor(
        ALICE,
        SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)),
    )!!

    @Test
    fun a_matching_act_pairs() {
        val ok = act(required().scope, required().effect, required().capability)

        assertIs<AuthorityPairing.Verdict.Paired>(AuthorityPairing.pair(required(), SUBJECT, listOf(ok)))
    }

    /**
     * The hole this whole file exists for. A READ_ONLY device claims `READ`,
     * which its role really does allow, and attaches it to a grant.
     */
    @Test
    fun an_act_claiming_a_weaker_capability_than_the_body_needs_is_refused() {
        val forged = act(required().scope, required().effect, DeviceCapability.READ)

        val verdict = AuthorityPairing.pair(required(), SUBJECT, listOf(forged))

        assertIs<AuthorityPairing.Verdict.Refused>(verdict)
        assertTrue(verdict.reason.contains("capability"), verdict.reason)
    }

    @Test
    fun an_act_naming_the_wrong_scope_is_refused() {
        val wrong = act(
            AuthorityScope.peer(PeerId("npub1someone-else")),
            required().effect,
            required().capability,
        )

        assertIs<AuthorityPairing.Verdict.Refused>(AuthorityPairing.pair(required(), SUBJECT, listOf(wrong)))
    }

    /**
     * A grant dressed up as a withdrawal would win every concurrent conflict,
     * because restrictive beats permissive.
     */
    @Test
    fun an_act_claiming_the_wrong_effect_is_refused() {
        val wrong = act(required().scope, AuthorityEffect.RESTRICTIVE, required().capability)

        assertIs<AuthorityPairing.Verdict.Refused>(AuthorityPairing.pair(required(), SUBJECT, listOf(wrong)))
    }

    @Test
    fun an_act_pointing_at_a_different_entry_is_refused() {
        val elsewhere = act(
            required().scope,
            required().effect,
            required().capability,
            subject = LedgerEntryId("entry-somewhere-else"),
        )

        assertIs<AuthorityPairing.Verdict.Refused>(AuthorityPairing.pair(required(), SUBJECT, listOf(elsewhere)))
    }

    @Test
    fun an_entry_with_no_act_at_all_is_refused() {
        val verdict = AuthorityPairing.pair(required(), SUBJECT, emptyList())

        assertIs<AuthorityPairing.Verdict.Refused>(verdict)
        assertTrue(verdict.reason.contains("no attestation"), verdict.reason)
    }

    /**
     * Exactly one. Two acts for one entry would let a second, weaker act ride
     * along on a valid one and land in the ledger as evidence of authority.
     */
    @Test
    fun two_acts_for_one_entry_are_refused() {
        val one = act(required().scope, required().effect, required().capability)
        val two = one.copy(id = LedgerEntryId("act-2"))

        val verdict = AuthorityPairing.pair(required(), SUBJECT, listOf(one, two))

        assertIs<AuthorityPairing.Verdict.Refused>(verdict)
        assertTrue(verdict.reason.contains("exactly one"), verdict.reason)
    }

    @Test
    fun an_act_in_a_legacy_scope_can_never_pair() {
        val legacy = act(AuthorityScope("peer|npub1alice"), required().effect, required().capability)

        assertIs<AuthorityPairing.Verdict.Refused>(AuthorityPairing.pair(required(), SUBJECT, listOf(legacy)))
    }
}
