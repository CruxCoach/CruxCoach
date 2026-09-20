package com.cruxcoach.app.nostr

import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Scripted relays: each URL maps to a behaviour that runs when a frame is sent. */
private class FakeRelays(private val script: (url: String, sent: String) -> List<String>) : WebSocketConnector {
    val dialed = ArrayList<String>()
    val sent = ArrayList<String>()
    val closed = ArrayList<String>()
    var refuse: Set<String> = emptySet()
    var frameCeiling: Int? = null

    override fun connect(url: String, maxFrameBytes: Int, listener: WebSocketListener): WebSocketHandle? {
        dialed += url
        frameCeiling = maxFrameBytes
        if (url in refuse) return null
        val handle = object : WebSocketHandle {
            override fun send(text: String) {
                sent += text
                script(url, text).forEach(listener::onText)
            }
            override fun close() { closed += url }
        }
        // Worst case for the caller: the socket opens before connect() returns.
        listener.onOpen()
        return handle
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RelayClientTest {
    private val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa".hexToBytesOrNull()!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val relays = listOf("wss://a.example", "wss://b.example", "wss://c.example")
    private val filter = """{"kinds":[30078],"authors":["$pubkey"]}"""

    private fun event(content: String, createdAt: Long = 1_780_000_000L, dTag: String = "d1"): NostrEvent =
        NostrKeys.sign(JvmHashing, secret, createdAt, 30078, listOf(listOf("d", dTag)), content)!!

    private fun subIdOf(request: String): String =
        Json.parseToJsonElement(request).let { (it as kotlinx.serialization.json.JsonArray)[1] }
            .toString().trim('"')

    private fun eventFrame(subId: String, event: NostrEvent) =
        """["EVENT","$subId",${Json.encodeToString(JsonObject.serializer(), event.toJson())}]"""

    private fun client(fake: FakeRelays) = RelayClient(fake, JvmHashing, relays)

    @Test
    fun `verified events come back once, with a REQ and a CLOSE per relay`() = runTest {
        val wanted = event("pointer")
        val fake = FakeRelays { url, frame ->
            if (!frame.startsWith("[\"REQ\"")) return@FakeRelays emptyList()
            val sub = subIdOf(frame)
            // Two relays hold the same event; the third has nothing.
            if (url == "wss://c.example") listOf("""["EOSE","$sub"]""")
            else listOf(eventFrame(sub, wanted), """["EOSE","$sub"]""")
        }
        val events = client(fake).query(filter)

        assertEquals(1, events.size, "the same event from two relays is one event")
        assertEquals(wanted.id, events.first().id)
        assertEquals(relays.toSet(), fake.dialed.toSet())
        assertEquals(relays.toSet(), fake.closed.toSet())
        assertEquals(1024 * 1024, fake.frameCeiling, "the socket is capped at the Android frame size")
        val requests = fake.sent.filter { it.startsWith("[\"REQ\"") }
        assertEquals(3, requests.size)
        for (request in requests) {
            assertTrue(request.contains(filter), "the filter is sent verbatim: $request")
            val sub = subIdOf(request)
            assertTrue(fake.sent.contains("""["CLOSE","$sub"]"""), "the subscription is closed again")
        }
        // Distinct subscription ids per relay, so a stray frame cannot cross over.
        assertEquals(3, requests.map { subIdOf(it) }.toSet().size)
    }

    @Test
    fun `events that do not verify are dropped, the rest survive`() = runTest {
        val good = event("good")
        val other = event("other", createdAt = 1_780_000_001L)
        val forged = good.copy(sig = other.sig)
        val tampered = good.copy(content = "tampered")
        val impostor = NostrKeys.sign(JvmHashing, ByteArray(32) { 3 }, 1_780_000_000L, 30078, listOf(listOf("d", "d1")), "evil")!!
        val fake = FakeRelays { _, frame ->
            if (!frame.startsWith("[\"REQ\"")) return@FakeRelays emptyList()
            val sub = subIdOf(frame)
            listOf(
                eventFrame(sub, forged),
                eventFrame(sub, tampered),
                eventFrame(sub, impostor),
                """["EVENT","$sub",{"id":"nope"}]""",
                """["EVENT","other-sub",${Json.encodeToString(JsonObject.serializer(), other.toJson())}]""",
                eventFrame(sub, good),
                """["EOSE","$sub"]""",
            )
        }
        val events = client(fake).query(filter)
        // The impostor is correctly signed but by another key: it is returned and
        // the caller's author check rejects it; the forged/tampered ones never get that far.
        assertEquals(setOf(good.id, impostor.id), events.map { it.id }.toSet())
        assertTrue(events.none { it.id == other.id }, "a frame for another subscription is ignored")
    }

    @Test
    fun `a silent relay times out without taking the others down`() = runTest {
        val wanted = event("pointer")
        val fake = FakeRelays { url, frame ->
            val sub = subIdOf(frame)
            when (url) {
                "wss://a.example" -> listOf(eventFrame(sub, wanted), """["EOSE","$sub"]""")
                "wss://b.example" -> emptyList() // accepts the REQ and says nothing
                else -> listOf("""["CLOSED","$sub","rate-limited"]""")
            }
        }
        fake.refuse = setOf("wss://c.example")
        val started = currentTime
        val events = client(fake).query(filter, timeoutMs = 4_000)
        assertEquals(listOf(wanted.id), events.map { it.id })
        assertEquals(4_000, currentTime - started, "only the silent relay waits out the timeout")
    }

    @Test
    fun `partial publish is reported honestly`() = runTest {
        val toPublish = event("publish me")
        val fake = FakeRelays { url, frame ->
            if (!frame.startsWith("[\"EVENT\"")) return@FakeRelays emptyList()
            when (url) {
                "wss://a.example" -> listOf("""["OK","${toPublish.id}",true,""]""")
                "wss://b.example" -> listOf("""["OK","${toPublish.id}",false,"blocked: pow required"]""")
                // Answers about a different event: never counted.
                else -> listOf("""["OK","${"f".repeat(64)}",true,""]""")
            }
        }
        val (attempted, accepted) = client(fake).publish(toPublish)
        assertEquals(3, attempted)
        assertEquals(1, accepted)
        assertEquals(relays.toSet(), fake.closed.toSet())
        val payload = fake.sent.first { it.startsWith("[\"EVENT\"") }
        assertTrue(payload.contains("\"id\":\"${toPublish.id}\""))
        assertTrue(payload.contains("\"sig\":\"${toPublish.sig}\""))
    }

    @Test
    fun `a relay that never acknowledges counts as a refusal`() = runTest {
        val toPublish = event("publish me")
        val fake = FakeRelays { _, _ -> emptyList() }
        val started = currentTime
        assertEquals(3 to 0, client(fake).publish(toPublish))
        assertEquals(NostrRelays.RELAY_TIMEOUT_MS, currentTime - started)
    }

    @Test
    fun `non-wss relays are never dialed`() = runTest {
        val fake = FakeRelays { _, _ -> emptyList() }
        val bad = listOf("https://a.example", "ws://b.example", "wss://has space", "")
        assertEquals(emptyList(), client(fake).query(filter, relays = bad, timeoutMs = 1_000))
        assertEquals(0 to 0, client(fake).publish(event("x"), relays = bad))
        assertEquals(emptyList(), fake.dialed)
    }

    @Test
    fun `hostile frames are refused before the parser sees them`() = runTest {
        val wanted = event("pointer")
        val deep = "[".repeat(40) + "]".repeat(40)
        val huge = "[\"NOTICE\",\"" + "x".repeat(1024 * 1024) + "\"]"
        val fake = FakeRelays { url, frame ->
            val sub = subIdOf(frame)
            when (url) {
                "wss://a.example" -> listOf(deep, eventFrame(sub, wanted), """["EOSE","$sub"]""")
                "wss://b.example" -> listOf(huge, eventFrame(sub, wanted), """["EOSE","$sub"]""")
                else -> listOf(eventFrame(sub, wanted), """["EOSE","$sub"]""")
            }
        }
        val events = client(fake).query(filter, timeoutMs = 1_000)
        // The two hostile relays are abandoned at the guard; the honest one still delivers.
        assertEquals(listOf(wanted.id), events.map { it.id })
    }

    @Test
    fun `one relay cannot flood the caller`() = runTest {
        val many = (0 until 300).map { event("event $it", createdAt = 1_780_000_000L + it) }
        val fake = FakeRelays { _, frame ->
            val sub = subIdOf(frame)
            many.map { eventFrame(sub, it) } + """["EOSE","$sub"]"""
        }
        val events = client(fake).query(filter, relays = listOf("wss://a.example"), timeoutMs = 1_000)
        assertEquals(256, events.size, "a relay's contribution to one query is bounded")
    }

    @Test
    fun `both versions of a replaceable event are returned so the caller can pick`() = runTest {
        val older = event("old pointer", createdAt = 1_780_000_000L)
        val newer = event("new pointer", createdAt = 1_780_000_900L)
        val fake = FakeRelays { url, frame ->
            val sub = subIdOf(frame)
            val served = if (url == "wss://a.example") older else newer
            listOf(eventFrame(sub, served), """["EOSE","$sub"]""")
        }
        val events = client(fake).query(filter)
        assertEquals(2, events.size)
        assertEquals(newer.id, assertNotNull(events.maxByOrNull { it.createdAt }).id)
    }
}
