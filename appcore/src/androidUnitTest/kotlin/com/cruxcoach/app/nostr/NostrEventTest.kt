package com.cruxcoach.app.nostr

import com.cruxcoach.app.testing.Fixtures
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NostrEventTest {
    // Real kind-30078 catalogue manifests fetched from public relays on 2026-09-18.
    // They were produced and signed by the publisher's own tooling, so they check
    // our NIP-01 canonicalisation against an independent implementation.
    private val manifests: List<NostrEvent> =
        Json.parseToJsonElement(Fixtures.text("manifest-events.json")).jsonArray
            .map { assertNotNull(NostrEvent.fromJson(it)) }

    @Test
    fun `real publisher events verify`() {
        assertEquals(5, manifests.size)
        for (event in manifests) {
            assertEquals("70b2740bff77cf65743a7d6ffa5465b3a27105ae26123458cf5450eafb1bd68d", event.pubkey)
            assertTrue(NostrEvents.verify(JvmHashing, event), "d=${event.firstTagValue("d")}")
        }
    }

    @Test
    fun `any altered field breaks verification`() {
        val event = manifests.first()
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(content = event.content + " ")))
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(createdAt = event.createdAt + 1)))
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(kind = 30079)))
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(tags = event.tags + listOf(listOf("d", "x")))))
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(sig = manifests[1].sig)))
    }

    @Test
    fun `a valid signature from another key is rejected for the claimed pubkey`() {
        val event = manifests.first()
        val attackerKey = ByteArray(32) { 7 }
        val forgedSig = Secp256k1.signSchnorr(event.id.hexToBytesOrNull()!!, attackerKey, null)
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(sig = forgedSig.joinToString("") { "%02x".format(it) })))
    }

    @Test
    fun `malformed ids and signatures are rejected without throwing`() {
        val event = manifests.first()
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(sig = "zz")))
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(sig = "")))
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(id = event.id.uppercase())))
        assertFalse(NostrEvents.verify(JvmHashing, event.copy(pubkey = "00")))
    }
}
