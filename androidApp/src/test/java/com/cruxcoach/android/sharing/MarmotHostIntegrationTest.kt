package com.cruxcoach.android.sharing

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The production Kotlin chokepoint over the real JNI library, MDK/OpenMLS,
 * SQLCipher, the in-process relay and the nostr-sdk pool, against a loopback
 * relay. Identities are synthetic and live only in Rust memory.
 */
class MarmotHostIntegrationTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> keep(value: T) = value.also { closeables += it }

    @AfterTest fun close() = closeables.asReversed().forEach { runCatching { it.close() } }

    private fun waitFor(vararg endpoints: SyntheticMarmotEndpoint, seconds: Int = 30, done: () -> Boolean): Boolean {
        repeat(seconds * 4) {
            endpoints.forEach { e -> e.blocking { sync() } }
            if (done()) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun received(endpoint: SyntheticMarmotEndpoint) = endpoint.blocking { next(0, 0, 0, 64).items }

    @Test fun invitation_acceptance_and_encrypted_messages_through_the_chokepoint() = runBlocking {
        val relay = keep(SyntheticRelay())
        val alice = keep(SyntheticMarmotEndpoint(listOf(relay.url)))
        val bob = keep(SyntheticMarmotEndpoint(listOf(relay.url)))
        for (endpoint in listOf(alice, bob)) endpoint.blocking { setOnline(true); setDiscovery(true) }
        assertTrue(waitFor(alice, bob) { alice.blocking { status() }.local.outboundPending == 0L && bob.blocking { status() }.local.outboundPending == 0L })

        alice.blocking { invite(bob.account) }
        assertTrue(waitFor(alice, bob) { bob.blocking { status() }.peers.any { it.account == alice.account && it.state == "invited" } })
        assertTrue(received(bob).isEmpty(), "an unaccepted invitation delivers nothing")
        bob.blocking { accept(alice.account); send(alice.account, "hello", "synthetic hello") }
        assertTrue(waitFor(alice, bob) { received(alice).any { it.content == "synthetic hello" } })
        assertEquals("active", alice.blocking { status() }.peers.single().state)

        val sent = alice.blocking { send(bob.account, "t1", "synthetic data") }
        assertEquals(sent, alice.blocking { send(bob.account, "t1", "synthetic data") }.copy(duplicate = false))
        assertEquals("token_conflict", runCatching { alice.blocking { send(bob.account, "t1", "other") } }
            .exceptionOrNull().let { (it as MarmotFailure).code })
        assertTrue(waitFor(alice, bob) { received(bob).any { it.content == "synthetic data" } })
        // Nothing in the relay-facing store matches the plaintext marker.
        assertEquals(0, bob.harness("private_storage_matches") { put("marker", "synthetic unrelated marker") }.jsonPrimitive.content.toInt())

        val batch = bob.blocking { next(0, 0, 0, 64) }
        bob.blocking { ack(batch.items.map { it.seq }) }
        assertTrue(received(bob).isEmpty())
        bob.blocking { end(alice.account) }
        assertEquals("ended", bob.blocking { status() }.peers.single().state)
    }
}
