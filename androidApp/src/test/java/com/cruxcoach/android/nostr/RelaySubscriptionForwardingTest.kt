package com.cruxcoach.android.nostr

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 0.2.4 crash: a relay event that arrived after the
 * last EOSE was sent into the already closed subscription channel, and the
 * ClosedSendChannelException killed the app.
 */
class RelaySubscriptionForwardingTest {

    private val relayA = "wss://a.example.com"
    private val relayB = "wss://b.example.com"

    @Test
    fun `event after the last EOSE is dropped instead of crashing`() = runTest {
        val out = Channel<String>(Channel.UNLIMITED)

        forwardRelayFrames(
            flowOf("""{"n":1}""", eoseFrame(relayA), eoseFrame(relayB), """{"n":2}"""),
            out,
            EoseTracker(listOf(relayA, relayB)),
        ) { true }

        assertEquals(listOf("""{"n":1}"""), out.toList())
    }

    @Test
    fun `second EOSE from a reconnected relay does not end the query early`() = runTest {
        val out = Channel<String>(Channel.UNLIMITED)

        forwardRelayFrames(
            flowOf(eoseFrame(relayA), eoseFrame(relayA), """{"from":"b"}""", eoseFrame(relayB)),
            out,
            EoseTracker(listOf(relayA, relayB)),
        ) { true }

        assertEquals(listOf("""{"from":"b"}"""), out.toList())
    }

    @Test
    fun `channel closed by the subscribe loop ends forwarding quietly`() = runTest {
        val out = Channel<String>(Channel.UNLIMITED)
        out.close()

        forwardRelayFrames(flowOf("""{"n":1}"""), out, eose = null) { true }

        assertEquals(emptyList<String>(), out.toList())
    }

    @Test
    fun `live subscription forwards accepted frames and ignores EOSE`() = runTest {
        val out = Channel<String>(Channel.UNLIMITED)

        forwardRelayFrames(
            flowOf("""{"ok":1}""", eoseFrame(relayA), """{"bad":1}""", """{"ok":2}"""),
            out,
            eose = null,
        ) { "ok" in it }
        out.close()

        assertEquals(listOf("""{"ok":1}""", """{"ok":2}"""), out.toList())
    }

    @Test
    fun `tracker reports done exactly once and ignores unknown relays`() {
        val tracker = EoseTracker(listOf(relayA, relayB))

        assertFalse(tracker.markDone("wss://unknown.example.com"))
        assertFalse(tracker.markDone(relayA))
        assertFalse(tracker.markDone(relayA))
        assertTrue(tracker.markDone(relayB))
        assertFalse(tracker.markDone(relayB))
    }

    @Test
    fun `EOSE frames carry the relay URL and event JSON is never read as EOSE`() {
        assertEquals(relayA, eoseRelayOf(eoseFrame(relayA)))
        assertNull(eoseRelayOf("""{"id":"__EOSE__ wss://a.example.com"}"""))
    }
}
