package com.cruxcoach.android.nostr

import com.cruxcoach.android.nostr.model.RelayConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A burst of stored events from several relays, faster than the subscription takes them: every event arrives, and the
 * query ends with the relays' EOSE instead of waiting for its timeout. The pool used to drop what did not fit into a
 * 256-frame buffer, EOSE included.
 */
class NostrRelayPoolBurstTest {
    private val servers = mutableListOf<MockWebServer>()
    private val serverSockets = java.util.Collections.synchronizedList(mutableListOf<WebSocket>())
    private val pool = NostrRelayPool(OkHttpClient()).apply {
        // Quartz needs JVM 21; the events here carry their id in plain JSON.
        verifyEvent = { json -> Json.parseToJsonElement(json).jsonObject["id"]?.jsonPrimitive?.content }
    }

    @After
    fun tearDown() {
        pool.closeAll()
        // MockWebServer waits for its open WebSockets when it shuts down (a server-side socket cannot be cancelled).
        serverSockets.toList().forEach { it.close(1000, null) }
        servers.forEach { server ->
            try {
                server.shutdown()
            } catch (e: java.io.IOException) {
                // A socket still closing; the events were checked already.
            }
        }
    }

    /** A relay that answers every REQ with [events] stored events of [chars] characters each, then EOSE. */
    private fun relay(name: String, events: Int, chars: Int = 0): String {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSockets += webSocket
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.parseToJsonElement(text).jsonArray
                if (frame[0].jsonPrimitive.content != "REQ") return
                val subId = frame[1].jsonPrimitive.content
                val content = "x".repeat(chars)
                repeat(events) { i -> webSocket.send("""["EVENT","$subId",{"id":"$name-$i","content":"$content"}]""") }
                webSocket.send("""["EOSE","$subId"]""")
            }
        }))
        server.start()
        servers += server
        return server.url("/").toString().replaceFirst("http", "ws")
    }

    private fun useRelays(vararg urls: String) = pool.onRelaysChanged(urls.map { RelayConfig(it, read = true, write = true) })

    private fun idsOf(events: List<String>) = events.map { Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `a burst from several relays loses no event and ends with their EOSE`() = runBlocking {
        useRelays(relay("a", 700), relay("b", 700), relay("c", 700))

        val events = withTimeout(30_000) {
            // Slower than the relays: far more than 256 frames wait at once.
            pool.fetchStored("""{"kinds":[1]}""").onEach { delay(1) }.toList()
        }

        val expected = listOf("a", "b", "c").flatMap { r -> (0 until 700).map { "$r-$it" } }
        assertEquals(expected.sorted(), idsOf(events).sorted())
        // Each relay's events come in the order it sent them.
        for (r in listOf("a", "b", "c")) {
            assertEquals((0 until 700).map { "$r-$it" }, idsOf(events).filter { it.startsWith("$r-") })
        }
    }

    @Test
    fun `relays held back by a full queue still deliver everything, EOSE last`() = runBlocking {
        // 3 x 150 events of 60 000 characters, 27 MB: three times what the queue and the flow's buffer hold, so the
        // relays have to wait for room. The old 256 + 64 slots would have dropped some too.
        useRelays(relay("a", 150, 60_000), relay("b", 150, 60_000), relay("c", 150, 60_000))
        val seen = mutableListOf<String>()

        withTimeout(60_000) {
            pool.fetchStored("""{"kinds":[30621]}""").map { event ->
                delay(2)
                Json.parseToJsonElement(event).jsonObject["id"]!!.jsonPrimitive.content
            }.toList(seen)
        }

        assertEquals(listOf("a", "b", "c").flatMap { r -> (0 until 150).map { "$r-$it" } }.sorted(), seen.sorted())
        for (r in listOf("a", "b", "c")) {
            assertEquals((0 until 150).map { "$r-$it" }, seen.filter { it.startsWith("$r-") })
        }
    }
}
