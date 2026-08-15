package com.cruxcoach.android.competition

import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.model.RelayConfig
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runtime transport evidence; CompetitionRelayBackfillPolicyTest pins client call ordering. */
class CompetitionRelayHandoffIntegrationTest {
    @Test
    fun `silent connected definition relay is unreachable rather than not found`() = runBlocking {
        val requestSeen = CompletableDeferred<Unit>()
        val serverSocket = AtomicReference<WebSocket>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.set(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = Json.parseToJsonElement(text).jsonArray
                    if (request[0].jsonPrimitive.content == "REQ") requestSeen.complete(Unit)
                    // Deliberately remain connected without EVENT or EOSE.
                }
            }))
            server.start()
            val http = OkHttpClient()
            val pool = NostrRelayPool(http)
            val relayUrl = server.url("/").toString().replaceFirst("http", "ws")
            val competition = CompetitionRelayClient(pool, ::competitionRelayScope, queryTimeoutMs = 250)

            val loaded = competition.load(
                organizerPubkey = "aa".repeat(32),
                compId = "ad57d38ca87ecbc5",
                nowSeconds = 1_789_020_000,
                relayHints = listOf(relayUrl),
            )
            withTimeout(2_000) { requestSeen.await() }
            assertFalse(loaded)
            assertEquals(CompetitionRelayClient.Problem.UNREACHABLE, competition.snapshot.value.problem)
            assertTrue(relayUrl in pool.connectedRelayUrls.value)

            pool.closeAll()
            serverSocket.get()?.close(1000, "test complete")
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun `duplicate EOSE from one relay cannot complete a strict two relay walk`() = runBlocking {
        val firstArmed = CompletableDeferred<Unit>()
        val secondArmed = CompletableDeferred<Unit>()
        val firstSocket = AtomicReference<WebSocket>()
        val secondSocket = AtomicReference<WebSocket>()
        val secondSubId = AtomicReference<String>()

        MockWebServer().use { firstServer ->
            MockWebServer().use { secondServer ->
                firstServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        firstSocket.set(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = Json.parseToJsonElement(text).jsonArray
                        if (request[0].jsonPrimitive.content != "REQ") return
                        val subId = request[1].jsonPrimitive.content
                        webSocket.send("[\"EOSE\",\"$subId\"]")
                        webSocket.send("[\"EOSE\",\"$subId\"]")
                        firstArmed.complete(Unit)
                    }
                }))
                secondServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        secondSocket.set(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = Json.parseToJsonElement(text).jsonArray
                        if (request[0].jsonPrimitive.content != "REQ") return
                        secondSubId.set(request[1].jsonPrimitive.content)
                        secondArmed.complete(Unit)
                    }
                }))
                firstServer.start()
                secondServer.start()
                val client = OkHttpClient()
                val pool = NostrRelayPool(client)
                val relays = listOf(firstServer, secondServer).map { server ->
                    RelayConfig(server.url("/").toString().replaceFirst("http", "ws"), read = true, write = false)
                }

                val walk = async {
                    pool.fetchStoredComplete("{\"kinds\":[30078]}", relays).toList()
                }
                withTimeout(5_000) {
                    firstArmed.await()
                    secondArmed.await()
                }
                delay(100)
                assertTrue("the silent relay must still hold the barrier", !walk.isCompleted)
                secondSocket.get().send("[\"EOSE\",\"${secondSubId.get()}\"]")
                assertEquals(emptyList<String>(), withTimeout(5_000) { walk.await() })

                pool.closeAll()
                firstSocket.get()?.close(1000, "test complete")
                secondSocket.get()?.close(1000, "test complete")
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `live frame sent during exact EOSE handoff is retained`() = runBlocking {
        val liveSub = AtomicReference<String>()
        val serverSocket = AtomicReference<WebSocket>()
        val armed = CompletableDeferred<Unit>()
        val finalEvent = "{\"id\":\"${"30".padStart(64, '0')}\"}"
        val storedEvent = "{\"id\":\"${"01".padStart(64, '0')}\"}"

        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.set(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val req = Json.parseToJsonElement(text).jsonArray
                    if (req[0].jsonPrimitive.content != "REQ") return
                    val subId = req[1].jsonPrimitive.content
                    val filter = req[2].jsonObject
                    if (filter["#d"] == null) {
                        liveSub.set(subId)
                        armed.complete(Unit)
                        webSocket.send("[\"EOSE\",\"$subId\"]")
                    } else {
                        val live = liveSub.get()
                        if (live != null) webSocket.send("[\"EVENT\",\"$live\",$finalEvent]")
                        webSocket.send("[\"EVENT\",\"$subId\",$storedEvent]")
                        webSocket.send("[\"EOSE\",\"$subId\"]")
                    }
                }
            }))
            server.start()
            val client = OkHttpClient()
            val pool = NostrRelayPool(client)
            val localRelay = RelayConfig(
                server.url("/").toString().replaceFirst("http", "ws"),
                read = true,
                write = true,
            )
            assertTrue(pool.readRelays().none { it.url == localRelay.url })

            val live = async {
                pool.subscribe(
                    "{\"kinds\":[30078],\"#a\":[\"competition\"]}",
                    skipDedup = true,
                    relays = listOf(localRelay),
                )
                    .first()
            }
            withTimeout(5_000) { armed.await() }
            val exact = withTimeout(5_000) {
                pool.fetchStoredComplete(
                    "{\"kinds\":[30078],\"#d\":[\"log:000001\"]}",
                    listOf(localRelay),
                ).toList()
            }

            assertEquals(listOf(storedEvent), exact)
            assertEquals(finalEvent, withTimeout(5_000) { live.await() })
            assertTrue(liveSub.get() != null)

            pool.closeAll()
            serverSocket.get()?.close(1000, "test complete")
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
