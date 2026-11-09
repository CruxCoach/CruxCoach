package com.cruxcoach.android.nostr

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 0.2.4 crash: a relay event that arrived after the
 * last EOSE was sent into the already closed subscription channel, and the
 * ClosedSendChannelException killed the app. And for the frame queue between
 * the relays and the subscription, which used to drop frames when full.
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

    /** Room for [frames] frames of four characters. */
    private fun roomFor(frames: Int) = frames * (4 + RelayFrameQueue.FRAME_OVERHEAD)

    @Test
    fun `frames that come before the subscription takes them wait for it, in order`() = runBlocking {
        val queue = RelayFrameQueue(maxWaitMs = 5_000)
        assertTrue(queue.offer("""{"n":1}"""))
        assertTrue(queue.offer(eoseFrame(relayA)))

        assertEquals(listOf("""{"n":1}""", eoseFrame(relayA)), withTimeout(10_000) { queue.frames.take(2).toList() })
    }

    @Test
    fun `a full queue makes the relay wait instead of dropping, and every frame arrives`() = runBlocking {
        val queue = RelayFrameQueue(maxWaitMs = 60_000, maxChars = roomFor(2))
        val taken = Collections.synchronizedList(mutableListOf<Boolean>())
        val relay = thread {
            repeat(100) { i -> taken += queue.offer("f%03d".format(i)) }
            taken += queue.offer(eoseFrame(relayA))
        }
        Thread.sleep(300)
        assertEquals("the relay waits while nothing is taken", 2, taken.size)

        val frames = withTimeout(10_000) { queue.frames.take(101).toList() }
        relay.join(10_000)

        assertFalse(relay.isAlive)
        assertEquals(List(101) { true }, taken.toList())
        assertEquals((0 until 100).map { "f%03d".format(it) } + eoseFrame(relayA), frames)
    }

    @Test
    fun `a subscription that does not keep up holds a relay up once, and its EOSE still gets through`() = runBlocking {
        val queue = RelayFrameQueue(maxWaitMs = 2_000, maxChars = roomFor(2))
        assertTrue(queue.offer("f001"))
        assertTrue(queue.offer("f002"))

        // Full, and nothing is taken: the relay waits, then the frame goes.
        var started = System.nanoTime()
        assertFalse(queue.offer("f003"))
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(1_900))
        // From then on at once, so the relay's other subscriptions, OKs and pings are not held up.
        started = System.nanoTime()
        assertFalse(queue.offer("f004"))
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000))
        // One EOSE per relay takes no room.
        assertTrue(queue.offer(eoseFrame(relayA)))
        assertFalse(queue.offer(eoseFrame(relayA)))
        assertTrue(queue.offer(eoseFrame(relayB)))

        val frames = withTimeout(10_000) { queue.frames.take(4).toList() }
        assertEquals(listOf("f001", "f002", eoseFrame(relayA), eoseFrame(relayB)), frames)
        // Caught up: frames are taken again.
        assertTrue(queue.offer("f005"))
        assertEquals(listOf("f005"), withTimeout(10_000) { queue.frames.take(1).toList() })
    }

    @Test
    fun `the wait counts from when the queue filled up, not per frame`() = runBlocking {
        // A subscription that takes a frame now and then still holds relays up for at most maxWaitMs in all.
        val queue = RelayFrameQueue(maxWaitMs = 4_000, maxChars = roomFor(4))
        repeat(4) { assertTrue(queue.offer("f00$it")) }
        val start = System.nanoTime()
        var lateTaken = true
        var lateWaitedMs = 0L
        var lateEndedMs = 0L
        val relay = thread {
            assertTrue(queue.offer("f004"))
            val asked = System.nanoTime()
            lateTaken = queue.offer("f005")
            lateWaitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - asked)
            lateEndedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }
        Thread.sleep(2_000)
        // One frame taken: room for f004, and the queue is full again.
        withTimeout(10_000) { queue.frames.take(1).toList() }
        relay.join(10_000)

        assertFalse(relay.isAlive)
        assertFalse(lateTaken)
        // Per frame it would have waited the full 4 s; the rest of the budget is about 2 s.
        assertTrue("f005 waited only the rest of the budget, $lateWaitedMs ms", lateWaitedMs < 3_500)
        assertTrue("relays waited the budget in all, $lateEndedMs ms", lateEndedMs >= 3_800)
    }

    @Test
    fun `closing frees a waiting relay and drops what comes after`() {
        val queue = RelayFrameQueue(maxWaitMs = 60_000, maxChars = roomFor(1))
        assertTrue(queue.offer("full"))
        val waiting = CountDownLatch(1)
        var handedOver = true
        val relay = thread { handedOver = queue.offer("next"); waiting.countDown() }
        assertFalse("still waiting for room", waiting.await(300, TimeUnit.MILLISECONDS))

        queue.close()

        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        relay.join(5_000)
        assertFalse(handedOver)
        assertFalse(queue.offer("late"))
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
