package com.cruxcoach.android.nostr

import com.cruxcoach.android.nostr.model.RelayConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrRelayPoolOverflowTest {
    @Test
    fun `a final live event cannot disappear silently when buffers fill`() = runBlocking {
        val blasted = AtomicBoolean(false)
        val serverSocket = AtomicReference<WebSocket>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = Json.parseToJsonElement(text).jsonArray
                    if (request[0].jsonPrimitive.content != "REQ" || !blasted.compareAndSet(false, true)) return
                    val subId = request[1].jsonPrimitive.content
                    repeat(2_000) { index ->
                        val id = index.toString(16).padStart(64, '0')
                        webSocket.send("[\"EVENT\",\"$subId\",{\"id\":\"$id\"}]")
                    }
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.set(webSocket)
                }
            }))
            server.start()
            val client = OkHttpClient()
            val pool = NostrRelayPool(client)
            pool.onRelaysChanged(listOf(RelayConfig(
                server.url("/").toString().replaceFirst("http", "ws"),
                read = true,
                write = false,
            )))

            val failure = runCatching {
                withTimeout(10_000) {
                    pool.subscribe("{\"kinds\":[30078]}", skipDedup = true, failOnRelayError = true)
                        .collect { delay(100) }
                }
            }.exceptionOrNull()

            assertTrue(blasted.get())
            assertTrue(
                "overflow must close a strict stream with an observable integrity failure, was $failure",
                generateSequence(failure) { it.cause }
                    .any { it is RelaySubscriptionIntegrityException },
            )
            pool.closeAll()
            serverSocket.get()?.close(1000, "test complete")
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
