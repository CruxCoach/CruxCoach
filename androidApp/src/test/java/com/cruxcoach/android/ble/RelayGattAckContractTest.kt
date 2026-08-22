package com.cruxcoach.android.ble

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.domain.board.BoardPacketEncoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a guest's write is told, and when.
 *
 * The contract the relay can honestly keep, now that the response is deferred
 * rather than sent up front:
 *
 *  - a fragment is answered at once with "received", which is all it can mean;
 *  - a write that completes a climb is answered when the relay knows whether
 *    it will deliver it — everything that decides that is asynchronous;
 *  - a write that is refused, dropped or left unanswered is an ATT error.
 *
 * The previous version answered `GATT_SUCCESS` before any of it, including for
 * a write it had just dropped on a full buffer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RelayGattAckContractTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val encoder = BoardPacketEncoder(apiLevel = 3)

    /**
     * The server's own scope runs on Main, so the sweeper only advances if the
     * test drives it — and its monotonic clock is read from the same virtual
     * time, so a `delay` inside the sweeper and the deadline it is comparing
     * against cannot drift apart.
     */
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Exactly what the official app puts on the wire for one climb. */
    private fun climbStream(): ByteArray =
        encoder.encodeClimb((10 until 15).map { it to 0x1C }).flatMap { it.toList() }.toByteArray()

    private class Answers {
        val statuses = mutableListOf<Pair<Int, Boolean>>()
        val last get() = statuses.lastOrNull()
    }

    private fun server(answers: Answers, admit: Boolean = true): RelayGattServer =
        RelayGattServer(context).apply {
            admitWrite = { admit }
            attResponder = { _, requestId, accepted -> answers.statuses += requestId to accepted }
            monotonicMs = { 10_000L + dispatcher.scheduler.currentTime }
        }

    private fun RelayGattServer.write(
        value: ByteArray,
        requestId: Int = 1,
        responseNeeded: Boolean = true,
        address: String = "AA:01",
    ) = handleGuestWrite(
        device = null,
        address = address,
        requestId = requestId,
        responseNeeded = responseNeeded,
        isBoardCharacteristic = true,
        value = value,
    )

    // ── Nothing is claimed before it is known ─────────────────────────────

    @Test
    fun `a write that completes a climb is not answered yet`() {
        val answers = Answers()

        server(answers).write(climbStream())

        assertTrue("the verdict does not exist yet", answers.statuses.isEmpty())
    }

    @Test
    fun `the deferred write is answered with the verdict it is given`() {
        val answers = Answers()
        val server = server(answers)
        server.write(climbStream(), requestId = 7)

        server.settle(7, accepted = true)

        assertEquals(7 to true, answers.last)
    }

    @Test
    fun `a refused delivery is an ATT error`() {
        val answers = Answers()
        val server = server(answers)
        server.write(climbStream(), requestId = 9)

        server.settle(9, accepted = false)

        assertEquals(9 to false, answers.last)
    }

    /** One request, one answer, however many verdicts arrive. */
    @Test
    fun `a second verdict for the same write is ignored`() {
        val answers = Answers()
        val server = server(answers)
        server.write(climbStream(), requestId = 3)

        server.settle(3, accepted = true)
        server.settle(3, accepted = false)

        assertEquals(1, answers.statuses.size)
        assertEquals(3 to true, answers.last)
    }

    // ── What can be answered at once ──────────────────────────────────────

    @Test
    fun `a fragment is answered as received`() {
        val answers = Answers()
        val stream = climbStream()

        server(answers).write(stream.copyOfRange(0, stream.size / 2))

        assertEquals(1 to true, answers.last)
    }

    @Test
    fun `a write with no usable board path is refused immediately`() {
        val answers = Answers()

        server(answers, admit = false).write(climbStream(), requestId = 4)

        assertEquals(4 to false, answers.last)
    }

    /**
     * The plainest lie the old version told: a write it had just thrown away
     * for want of buffer space, reported as delivered.
     */
    @Test
    fun `a climb dropped on a full buffer is refused`() {
        val answers = Answers()
        val server = server(answers).apply { emitClimb = { false } }

        server.write(climbStream(), requestId = 5)

        assertEquals(5 to false, answers.last)
    }

    @Test
    fun `a raw write dropped on a full buffer is refused`() {
        val answers = Answers()
        val server = server(answers).apply { emitWrite = { false } }

        server.write(climbStream(), requestId = 6)

        assertEquals(6 to false, answers.last)
    }

    // ── Nothing waits forever ─────────────────────────────────────────────

    @Test
    fun `a write nobody settles fails closed when the server stops`() = runTest {
        val answers = Answers()
        val server = server(answers)
        server.write(climbStream(), requestId = 11)
        assertTrue(answers.statuses.isEmpty())

        server.stop()

        assertEquals(11 to false, answers.last)
    }

    /** A guest that asked for no answer is never given one. */
    @Test
    fun `a write-without-response is never answered`() {
        val answers = Answers()
        val server = server(answers)

        server.write(climbStream(), requestId = 12, responseNeeded = false)
        server.settle(12, accepted = true)

        assertTrue(answers.statuses.isEmpty())
    }

    /**
     * A write from a device this server never saw connect used to reassemble
     * into nothing at all, silently, for as long as the link lasted.
     */
    @Test
    fun `a write from an unseen device still reassembles`() {
        val answers = Answers()
        val server = server(answers)

        server.write(climbStream(), requestId = 13, address = "ZZ:99")

        assertTrue("deferred, so the climb was reassembled", answers.statuses.isEmpty())
        server.settle(13, accepted = true)
        assertEquals(13 to true, answers.last)
    }

    // ── The verdict may arrive before the emission returns ────────────────

    /**
     * The race this closes. The pending transaction used to be registered
     * *after* the emission, so a collector that settles synchronously found
     * nothing waiting, its verdict was dropped, and the request was then failed
     * by the deadline — having been processed perfectly well.
     */
    @Test
    fun `a verdict delivered inside the emission is not lost`() {
        val answers = Answers()
        val server = server(answers)
        // Exactly what a synchronous collector does: decide during emit.
        server.emitClimb = { inbound ->
            server.settle(inbound.pendingResponse, accepted = true)
            true
        }

        server.write(climbStream(), requestId = 61)

        assertEquals(61 to true, answers.last)
    }

    /** And a refusal decided that fast is still the answer that goes out. */
    @Test
    fun `a refusal delivered inside the emission is the answer`() {
        val answers = Answers()
        val server = server(answers)
        server.emitClimb = { inbound ->
            server.settle(inbound.pendingResponse, accepted = false)
            true
        }

        server.write(climbStream(), requestId = 62)

        assertEquals(1, answers.statuses.size)
        assertEquals(62 to false, answers.last)
    }

    /**
     * A verdict that raced a failing emission loses harmlessly: whoever claims
     * the transaction first answers it, and it is answered exactly once.
     */
    @Test
    fun `a dropped climb settled during the drop is answered once`() {
        val answers = Answers()
        val server = server(answers)
        server.emitClimb = { inbound ->
            server.settle(inbound.pendingResponse, accepted = true)
            false
        }

        server.write(climbStream(), requestId = 63)

        assertEquals(1, answers.statuses.size)
        assertEquals(63 to true, answers.last)
    }

    // ── Every request gets its own window ─────────────────────────────────

    /**
     * One shared timer gave a write that arrived five seconds into somebody
     * else's window one second to be decided. Each transaction now carries its
     * own monotonic deadline.
     */
    @Test
    fun `a later request gets its own full window`() = runTest(dispatcher) {
        val answers = Answers()
        val server = server(answers)

        val window = RelayGattServer.RELAY_OPERATION_DEADLINE_MS
        server.write(climbStream(), requestId = 71)
        advanceTimeBy(window - 1_000)
        server.write(climbStream(), requestId = 72, address = "BB:02")

        // The first one is out of time; the second still has most of its own.
        advanceTimeBy(1_100)
        assertEquals(listOf(71 to false), answers.statuses)

        // And it is answered on its own merits when somebody decides it.
        server.settle(72, accepted = true)
        assertEquals(listOf(71 to false, 72 to true), answers.statuses)
    }

    /** Which is to say: the second one is not swept away with the first. */
    @Test
    fun `an expired request is failed and a fresh one is left alone`() = runTest(dispatcher) {
        val answers = Answers()
        val server = server(answers)

        val window = RelayGattServer.RELAY_OPERATION_DEADLINE_MS
        server.write(climbStream(), requestId = 81)
        advanceTimeBy(window + 100)
        assertEquals(listOf(81 to false), answers.statuses)

        server.write(climbStream(), requestId = 82, address = "CC:03")
        advanceTimeBy(window - 1_000)
        assertEquals("still inside its own window", 1, answers.statuses.size)

        advanceTimeBy(1_100)
        assertEquals(listOf(81 to false, 82 to false), answers.statuses)
    }
}
