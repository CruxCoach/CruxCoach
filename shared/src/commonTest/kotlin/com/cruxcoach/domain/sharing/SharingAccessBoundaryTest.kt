package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

class SharingAccessBoundaryTest {
    private val peer = PeerId("11".repeat(32))
    private val device = DeviceId("22".repeat(32))
    private val category = SharingCategory.PRIVATE_NOTES
    private val policy = SharingPolicy(peers = mapOf(peer to PeerPolicy(
        SharingCircle.FRIENDS, categoryRules = mapOf(category to AccessEffect.ALLOW),
    )))
    private val state = RelationshipState(peer, RelationshipStatus.ACCEPTED,
        offeredCategories = setOf(category), consentedCategories = setOf(category),
        authorisedDevices = setOf(device), expiresAt = 2_000L)

    private fun resolve(s: RelationshipState = state, epoch: Long = 1, now: Long? = 1_000) =
        EffectiveAccessResolver.resolve(policy, s, peer, category, device = device,
            localResourceEpoch = epoch, nowEpochMillis = now)

    @Test fun expiry_is_checked_at_the_boundary_and_requires_a_clock() {
        assertTrue(resolve(now = 1_999).isAllowed)
        assertEquals(DecisionSource.RELATIONSHIP_EXPIRED, resolve(now = 2_000).source)
        assertFalse(resolve(now = Long.MAX_VALUE).isAllowed)
        assertEquals(DecisionSource.CLOCK_UNAVAILABLE, resolve(now = null).source)
        assertFalse(resolve(now = -1).isAllowed)
        assertFalse(resolve(state.copy(expiresAt = 0)).isAllowed)
        assertTrue(resolve(state.copy(expiresAt = null), now = null).isAllowed)
    }

    @Test fun identity_epoch_and_device_are_exact_bindings() {
        assertFalse(resolve(state.copy(peer = PeerId("33".repeat(32)))).isAllowed)
        for (epoch in listOf(-1L, 0L, 2L, Long.MAX_VALUE)) assertFalse(resolve(epoch = epoch).isAllowed)
        assertFalse(resolve(state.copy(revokedDevices = setOf(device))).isAllowed)
        assertFalse(resolve(state.copy(offeredCategories = emptySet())).isAllowed)
        assertFalse(resolve(state.copy(awaitingRevokeSync = true)).isAllowed)
    }

    @Test fun object_allow_cannot_cross_the_signed_category() {
        val id = ObjectId("same-id")
        val owner = OwnerPolicyState(objectRules = mapOf(peer to mapOf(
            ObjectRuleKey(id, SharingCategory.VIDEOS) to AccessEffect.ALLOW,
        )))
        val p = owner.toPolicy(mapOf(peer to SharingCircle.FRIENDS))
        assertTrue(SharingPolicyResolver.resolve(p, peer, SharingCategory.VIDEOS, id).isAllowed)
        assertFalse(SharingPolicyResolver.resolve(p, peer, SharingCategory.HEALTH_INFORMATION, id).isAllowed)
        assertFalse(SharingPolicyResolver.resolve(p, peer, SharingCategory.PRIVATE_NOTES, id).isAllowed)
    }

    @Test fun codecs_refuse_ununderstood_restrictions_duplicates_and_lossy_types() {
        val relationship = listOf(
            "{\"categories\":[\"VIDEOS\"],\"futureRestriction\":\"deny\"}",
            "{\"categories\":[],\"categories\":[\"VIDEOS\"]}",
            "{\"categories\":[\"VIDEOS\",\"VIDEOS\"]}",
            "{\"categories\":[\"VIDEOS\"],\"expiresAt\":\"2000\"}",
        )
        relationship.forEach { assertIs<SharingLedgerBody.Unknown>(SharingLedgerCodec.decodeBody("RelationshipOffered", it)) }
        assertIs<OwnerPolicyBody.Unknown>(OwnerPolicyCodec.decodeBody("PeerRuleSet",
            "{\"peer\":\"p\",\"category\":\"VIDEOS\",\"effect\":\"ALLOW\",\"expiresAt\":1}"))
        assertIs<DeviceManifestBody.Unknown>(DeviceManifestCodec.decodeBody("DeviceEnrolled",
            "{\"device\":\"d\",\"publicKey\":\"p\",\"role\":\"PRIMARY\",\"restrictions\":[\"READ\"]}"))
        assertIs<SharingLedgerBody.Unknown>(SharingLedgerCodec.decodeBody("RecipientDeclined", "{\"v\":999}"))
    }
}
