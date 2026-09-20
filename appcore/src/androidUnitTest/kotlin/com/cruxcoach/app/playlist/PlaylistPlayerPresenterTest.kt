package com.cruxcoach.app.playlist

import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPlayerPresenterTest {

    /**
     * Virtual time: the rest countdown must never depend on the wall clock.
     * The clock advances WITH the scheduler — a clock that stands still while
     * virtual time runs would make the countdown loop forever.
     */
    private class TestClock : PlaybackClock {
        var seconds = 1_000L
        override fun epochSeconds(): Long = seconds
        override suspend fun awaitTick() {
            delay(1_000)
            seconds += 1
        }
    }

    private class RecordingTransport : PlaybackTransport {
        val sent = mutableListOf<Pair<String, Int>>()
        override var isBoardConnected: Boolean = true
        override fun sendClimb(climbUuid: String, angle: Int) {
            sent += climbUuid to angle
        }
    }

    private fun items(vararg spec: Pair<String, Int>) = spec.map { (uuid, rest) -> PlaybackItem(uuid, 40, rest) }

    private fun presenter(
        scope: CoroutineScope,
        clock: PlaybackClock,
        transport: PlaybackTransport = RecordingTransport(),
    ) = PlaylistPlayerPresenter(
        transport = transport,
        clock = clock,
        climbInfo = { uuid -> PlayerClimbInfo("Name of $uuid", 18.0) },
        gradeLabel = { if (it == null) "" else "6b" },
        scope = scope,
        random = Random(7),
    )

    @Test
    fun `play sends the first climb and reports queue position`() = runTest {
        val transport = RecordingTransport()
        val p = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock(), transport)
        p.play("Session", items("a" to 0, "b" to 0))

        val s = p.state.value
        assertTrue(s.isActive)
        assertEquals("Session", s.listName)
        assertEquals(0, s.currentIndex)
        assertEquals("Name of a", s.currentClimbName)
        assertEquals("6b", s.currentClimbGrade)
        assertTrue(s.boardConnected)
        assertTrue(s.hasNext)
        assertFalse(s.hasPrevious)
        assertEquals(PlaybackItem("b", 40, 0), s.upNext)
        assertEquals(listOf("a" to 40), transport.sent)

        p.play("Empty", emptyList())
        assertEquals("Session", p.state.value.listName)
        p.close()
    }

    @Test
    fun `advancing arms the rest of the climb left behind and next skips it`() = runTest {
        val clock = TestClock()
        val transport = RecordingTransport()
        val p = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), clock, transport)
        p.play("s", items("a" to 30, "b" to 0))

        p.next()
        // The queue already sits on the upcoming climb while the pause runs.
        assertEquals(1, p.state.value.currentIndex)
        val resting = p.state.value.phase as PlaybackPhase.Resting
        assertEquals(30, resting.secondsRemaining)
        assertEquals(30, resting.totalSeconds)
        // The absolute end time is what iOS schedules its notification for.
        assertEquals(1_030L, resting.endsAtEpochSeconds)
        assertTrue(p.state.value.hasNext)
        assertTrue(p.state.value.hasPrevious)

        testScheduler.advanceTimeBy(10_001)
        assertEquals(20, (p.state.value.phase as PlaybackPhase.Resting).secondsRemaining)

        // next() during a rest ends the pause; it must not advance again.
        p.next()
        assertEquals(PlaybackPhase.Climbing, p.state.value.phase)
        assertEquals(1, p.state.value.currentIndex)
        assertFalse(p.state.value.restFinished)
        assertEquals(listOf("a" to 40, "b" to 40), transport.sent)
        p.close()
    }

    @Test
    fun `a rest that runs out returns to climbing and raises the banner once`() = runTest {
        val clock = TestClock()
        val p = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), clock)
        p.play("s", items("a" to 5, "b" to 0))
        p.next()

        testScheduler.advanceTimeBy(5_001)
        assertEquals(PlaybackPhase.Climbing, p.state.value.phase)
        assertTrue(p.state.value.restFinished)
        p.acknowledgeRestFinished()
        assertFalse(p.state.value.restFinished)
        p.close()

        // A suspended app resumes with the time that really passed: one tick
        // after a ten-minute jump ends a one-minute rest.
        val other = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), clock)
        other.play("s", items("a" to 60, "b" to 0))
        other.next()
        clock.seconds += 600
        testScheduler.advanceTimeBy(1_001)
        assertEquals(PlaybackPhase.Climbing, other.state.value.phase)
        other.close()
    }

    @Test
    fun `previous during a rest cancels the pause and steps back`() = runTest {
        val p = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock())
        p.play("s", items("a" to 30, "b" to 0))
        p.next()
        assertTrue(p.state.value.isResting)

        p.previous()
        assertEquals(0, p.state.value.currentIndex)
        assertEquals(PlaybackPhase.Climbing, p.state.value.phase)

        p.previous()
        assertEquals(0, p.state.value.currentIndex)
        p.close()
    }

    @Test
    fun `setCurrent is a user override and arms no rest`() = runTest {
        val transport = RecordingTransport()
        val p = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock(), transport)
        p.play("s", items("a" to 30, "b" to 30, "c" to 0))

        p.setCurrent(2)
        assertEquals(2, p.state.value.currentIndex)
        assertEquals(PlaybackPhase.Climbing, p.state.value.phase)
        p.setCurrent(99)
        assertEquals(2, p.state.value.currentIndex)

        p.resendCurrentClimb()
        assertEquals(listOf("a" to 40, "c" to 40, "c" to 40), transport.sent)
        p.close()
    }

    @Test
    fun `try n of m counts a run of identical climbs`() = runTest {
        val p = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock())
        p.play("s", items("a" to 10, "a" to 10, "a" to 60, "b" to 0))
        assertEquals(1 to 3, p.state.value.attemptInfo)
        p.setCurrent(1)
        assertEquals(2 to 3, p.state.value.attemptInfo)
        p.setCurrent(3)
        assertNull(p.state.value.attemptInfo)
        p.close()
    }

    @Test
    fun `a send drops the queued repeats and carries the trailing rest over`() = runTest {
        val p = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock())
        p.play("s", items("a" to 10, "a" to 10, "a" to 180, "b" to 0))

        p.onClimbLogged(isSend = true)
        val s = p.state.value
        assertEquals(listOf("a", "b"), s.queue.map { it.climbUuid })
        // The 10s attempt rest becomes the 180s gap before a different problem.
        assertEquals(180, s.queue[0].restAfterSeconds)
        assertEquals(0, s.currentIndex)
        assertEquals(PlaybackPhase.Climbing, s.phase)
        assertNull(s.attemptInfo)

        // An attempt changes nothing at all.
        p.onClimbLogged(isSend = false)
        assertEquals(listOf("a", "b"), p.state.value.queue.map { it.climbUuid })
        assertEquals(0, p.state.value.currentIndex)
        p.close()
    }

    @Test
    fun `advance mode decides whether a log moves the playlist`() = runTest {
        val manual = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock())
        manual.play("s", items("a" to 0, "b" to 0), advance = ListPlaybackAdvance.MANUAL)
        manual.onClimbLogged(isSend = true)
        assertEquals(0, manual.state.value.currentIndex)

        val afterSend = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock())
        afterSend.play("s", items("a" to 0, "b" to 0), advance = ListPlaybackAdvance.AFTER_SEND)
        afterSend.onClimbLogged(isSend = false)
        assertEquals(0, afterSend.state.value.currentIndex)
        afterSend.onClimbLogged(isSend = true)
        assertEquals(1, afterSend.state.value.currentIndex)

        val afterLog = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock())
        afterLog.play("s", items("a" to 0, "b" to 0), advance = ListPlaybackAdvance.AFTER_LOG)
        afterLog.onClimbLogged(isSend = false)
        assertEquals(1, afterLog.state.value.currentIndex)
        manual.close()
        afterSend.close()
        afterLog.close()
    }

    @Test
    fun `shuffle reorders the queue but keeps every entry`() = runTest {
        val p = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock())
        val queue = items("a" to 0, "b" to 0, "c" to 0, "d" to 0, "e" to 0)
        p.play("s", queue, order = ListPlaybackOrder.SHUFFLE)
        val shuffled = p.state.value.queue
        assertEquals(queue.toSet(), shuffled.toSet())
        assertEquals(ListPlaybackOrder.SHUFFLE, p.state.value.order)
        assertEquals(shuffled[0], p.state.value.currentClimb)
        p.close()
    }

    @Test
    fun `stop clears the player and its rest`() = runTest {
        val p = presenter(TestScope(UnconfinedTestDispatcher(testScheduler)), TestClock())
        p.play("s", items("a" to 30, "b" to 0))
        p.next()
        assertTrue(p.state.value.isResting)

        p.stop()
        assertFalse(p.state.value.isActive)
        assertEquals(PlaybackPhase.Climbing, p.state.value.phase)
        assertTrue(p.state.value.queue.isEmpty())
        p.onClimbLogged(isSend = true)
        assertEquals(-1, p.state.value.currentIndex)
        p.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerFacadeTest {

    private class Clock : PlaybackClock {
        override fun epochSeconds(): Long = 2_000L
        override suspend fun awaitTick() = delay(1_000)
    }

    @Test
    fun `facade exposes plain codes, the queue and the rest end time`() = runTest {
        val presenter = PlaylistPlayerPresenter(
            clock = Clock(),
            climbInfo = { PlayerClimbInfo("Alpha", 18.0) },
            gradeLabel = { "6b" },
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )
        val model = com.cruxcoach.app.ui.PlayerScreenModel(presenter)
        assertFalse(model.currentState.isActive)

        model.play(
            listName = "Session",
            climbUuids = listOf("a", "a", "b"),
            angles = listOf(40, 40, 45),
            restsAfter = listOf(20, 120, 0),
            orderCode = "list",
            advanceCode = "afterSend",
            defaultRestSeconds = 60,
        )
        val s = model.currentState
        assertTrue(s.isActive)
        assertEquals("climbing", s.phaseCode)
        assertEquals("afterSend", s.advanceCode)
        assertEquals("list", s.orderCode)
        assertEquals(3, s.queue.size)
        assertEquals(45, s.queue[2].angle)
        assertEquals("Alpha", s.currentClimbName)
        assertEquals("6b", s.currentClimbGrade)
        assertEquals(1, s.attemptNumber)
        assertEquals(2, s.attemptTotal)
        assertEquals(0L, s.restEndsAtEpochSeconds)

        model.next()
        val resting = model.currentState
        assertEquals("resting", resting.phaseCode)
        assertEquals(20, resting.restTotalSeconds)
        assertEquals(2_020L, resting.restEndsAtEpochSeconds)

        model.skipRest()
        assertEquals("climbing", model.currentState.phaseCode)
        assertEquals(1, model.currentState.currentIndex)

        // On the SECOND try of the run: nothing is queued behind it to drop,
        // and afterSend advances to the next problem.
        model.climbLogged(isSend = true)
        assertEquals(listOf("a", "a", "b"), model.currentState.queue.map { it.climbUuid })
        assertEquals(2, model.currentState.currentIndex)
        assertEquals(0, model.currentState.attemptTotal)

        // Mismatched parallel arrays are refused rather than half-applied.
        model.play("bad", listOf("x"), emptyList(), listOf(0), "list", "manual", 0)
        assertEquals("Session", model.currentState.listName)

        model.stop()
        assertFalse(model.currentState.isActive)
        model.close()
    }
}
