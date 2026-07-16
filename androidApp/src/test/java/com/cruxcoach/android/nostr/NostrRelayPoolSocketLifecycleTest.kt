package com.cruxcoach.android.nostr

import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NostrRelayPoolSocketLifecycleTest {

    private class FakeSocket(private val requestValue: Request) : WebSocket {
        val cancels = AtomicInteger(0)
        override fun request(): Request = requestValue
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() { cancels.incrementAndGet() }
    }

    private data class OpenAttempt(
        val socket: FakeSocket,
        val listener: WebSocketListener,
    )

    private class FakeFactory : WebSocket.Factory {
        val attempts = mutableListOf<OpenAttempt>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request).also { attempts += OpenAttempt(it, listener) }
    }

    @Test
    fun `timeout cancels socket and its late failure cannot tear down replacement`() = runTest {
        val factory = FakeFactory()
        val pool = NostrRelayPool(okhttp3.OkHttpClient()).also { it.webSocketFactory = factory }
        val connection = pool.connectionForTesting("wss://relay.example")

        supervisorScope {
            val firstConnect = async { connection.ensureConnected() }
            runCurrent()
            assertEquals(1, factory.attempts.size)

            advanceTimeBy(NostrConfig.RELAY_TIMEOUT_MS + 1L)
            runCurrent()
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                firstConnect.await()
            }
        }
        val first = factory.attempts.single()
        assertTrue(first.socket.cancels.get() > 0, "timed-out socket must be cancelled")

        supervisorScope {
            val replacementConnect = async { connection.ensureConnected() }
            runCurrent()
            assertEquals(2, factory.attempts.size)
            val replacement = factory.attempts[1]
            replacement.listener.onOpen(replacement.socket, mockk<Response>(relaxed = true))
            replacementConnect.await()

            assertTrue(connection.connectedForTesting)
            assertSame(replacement.socket, connection.socketForTesting)

            first.listener.onFailure(first.socket, IllegalStateException("late failure"), null)
            assertTrue(connection.connectedForTesting, "stale callback must not clear live state")
            assertSame(replacement.socket, connection.socketForTesting)
        }

        connection.close()
    }
}
