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

    @Test fun lan_invitation_acceptance_and_messages_without_any_relay() {
        val alice = endpoint()
        val bob = endpoint()
        for (e in listOf(alice, bob)) e.blocking { setOnline(true); setDiscovery(true) }
        alice.discovery.endpoints = listOf("http://127.0.0.1:${bob.lan.port}")
        bob.discovery.endpoints = listOf("http://127.0.0.1:${alice.lan.port}")

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

    /** Measurement for the spike report: QR size of the invitation hand-over. */
    @Test fun invitation_qr_size() {
        val alice = endpoint()
        val bob = endpoint()
        alice.blocking { invite(bob.account) }
        val payload = alice.harness("invitation") { put("peer", bob.account) }.jsonObject["payload"]!!.jsonPrimitive.content
        fun version(content: String, level: ErrorCorrectionLevel) =
            Encoder.encode(content, level, mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1")).version.versionNumber
        val full = payload.length
        // Two-stage variant: npub, identity fingerprint and a rendezvous secret (64 bytes).
        val twoStage = "cruxcoach:i2:" + "A".repeat(86)
        val lines = listOf(
            "single-stage chars=$full L=${version(payload, ErrorCorrectionLevel.L)} M=${version(payload, ErrorCorrectionLevel.M)} Q=${version(payload, ErrorCorrectionLevel.Q)}",
            "two-stage chars=${twoStage.length} L=${version(twoStage, ErrorCorrectionLevel.L)} M=${version(twoStage, ErrorCorrectionLevel.M)}",
        )
        System.getenv("CC_MEASURE_OUT")?.let { java.io.File(it).appendText(lines.joinToString("\n", postfix = "\n")) }
        println(lines.joinToString("\n"))
        assertEquals("cruxcoach:i1:".length + 519, full, "389-byte package, base64url")
        bob.blocking { setOnline(false) }
        bob.harness("import_invitation") { put("payload", payload) }
        assertEquals("invited", bob.blocking { status() }.peers.single().state)
    }
}
