package com.cruxcoach.android.sharing

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike M0: the production chokepoint over the real JNI library with the Olm
 * engine and the direct LAN transport on loopback. No relay exists anywhere
 * in this test; the only path between the two endpoints is their HTTP servers.
 */
class OlmP2pIntegrationTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> keep(value: T) = value.also { closeables += it }

    @AfterTest fun close() = closeables.asReversed().forEach { runCatching { it.close() } }

    private fun endpoint() = keep(SyntheticMarmotEndpoint(listOf("ws://127.0.0.1:1"), p2p = true))

    private fun waitFor(vararg endpoints: SyntheticMarmotEndpoint, seconds: Int = 30, done: () -> Boolean): Boolean {
        repeat(seconds * 5) {
            endpoints.forEach { e -> e.blocking { sync() } }
            if (done()) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun received(endpoint: SyntheticMarmotEndpoint) = endpoint.blocking { next(0, 0, 0, 64).items }

    private fun contactCode(endpoint: SyntheticMarmotEndpoint) =
        endpoint.harness("contact_code").jsonObject["payload"]!!.jsonPrimitive.content

    private fun scan(endpoint: SyntheticMarmotEndpoint, code: String) =
        endpoint.harness("import_contact") { put("payload", code) }.jsonObject["peer"]!!.jsonPrimitive.content

    @Test fun lan_invitation_acceptance_and_messages_without_any_relay() {
        val alice = endpoint()
        val bob = endpoint()
        for (e in listOf(alice, bob)) e.blocking { setOnline(true) }
        alice.discovery.endpoints = listOf("http://127.0.0.1:${bob.lan.port}")
        bob.discovery.endpoints = listOf("http://127.0.0.1:${alice.lan.port}")

        // Bob shows his contact code, Alice scans it.
        assertEquals(bob.account, scan(alice, contactCode(bob)))
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

        // The v2 relay projection now carries the transports; nothing pending in reach.
        val status = alice.blocking { status() }
        assertEquals(listOf("p2p:lan"), status.relays.map { it.url })
        assertEquals("connected", status.relays.single().connection)
        assertTrue(waitFor(alice, bob) { alice.blocking { status() }.local.outboundPending == 0L })
        assertEquals(0, bob.harness("private_storage_matches") { put("marker", "synthetic unrelated marker") }.jsonPrimitive.content.toInt())

        val batch = bob.blocking { next(0, 0, 0, 64) }
        bob.blocking { ack(batch.items.map { it.seq }) }
        assertTrue(received(bob).isEmpty())
        bob.blocking { end(alice.account) }
        assertEquals("ended", bob.blocking { status() }.peers.single().state)
        // One binding signature per installation, nothing per relationship or message.
        assertEquals(1, alice.harness("signer_ops").jsonPrimitive.content.toInt())
        assertEquals(1, bob.harness("signer_ops").jsonPrimitive.content.toInt())
    }

    /** The contact code: its QR size, and a stranger in reach gets nothing. */
    @Test fun contact_code_reaches_only_the_device_that_showed_it() {
        val alice = endpoint()
        val bob = endpoint()
        val carol = endpoint()
        val code = contactCode(bob)
        fun version(content: String, level: ErrorCorrectionLevel) =
            Encoder.encode(content, level, mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1")).version.versionNumber
        println("contact-code chars=${code.length} L=${version(code, ErrorCorrectionLevel.L)} M=${version(code, ErrorCorrectionLevel.M)} Q=${version(code, ErrorCorrectionLevel.Q)}")
        assertEquals("cruxcoach:c1:".length + 91, code.length, "68-byte code, base64url")
        assertTrue(version(code, ErrorCorrectionLevel.M) <= 6)

        for (e in listOf(alice, bob, carol)) e.blocking { setOnline(true) }
        // Alice sees both devices; Bob and Carol see Alice.
        alice.discovery.endpoints = listOf("http://127.0.0.1:${carol.lan.port}", "http://127.0.0.1:${bob.lan.port}")
        bob.discovery.endpoints = listOf("http://127.0.0.1:${alice.lan.port}")
        carol.discovery.endpoints = listOf("http://127.0.0.1:${alice.lan.port}")
        scan(alice, code)
        assertTrue(waitFor(alice, bob, carol) { bob.blocking { status() }.peers.any { it.account == alice.account && it.state == "invited" } })
        assertTrue(carol.blocking { status() }.peers.isEmpty(), "a stranger in reach learns of no invitation")
        bob.blocking { accept(alice.account) }
        assertTrue(waitFor(alice, bob) { alice.blocking { status() }.peers.single().state == "active" })
    }
}
