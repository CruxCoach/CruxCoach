package com.cruxcoach.android.ui.board.games

import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.games.FloorIsLava
import com.cruxcoach.domain.board.games.GameFrame
import com.cruxcoach.domain.board.games.GameTimeline
import com.cruxcoach.domain.board.games.WallHold
import com.cruxcoach.domain.board.games.WallRows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WallGameRunnerTest {

    private fun hold(id: Int) = BoardHold(id, HoldRole.HAND)

    private fun timeline(vararg atMs: Long) = GameTimeline(
        atMs.mapIndexed { i, t -> GameFrame(t, (0..i).map { hold(it) }, i) },
        steps = atMs.size - 1,
    )

    private fun TestScope.runner(frames: MutableList<List<BoardHold>>, beeps: MutableList<GameBeep>) = WallGameRunner(
        scope = backgroundScope,
        now = { testScheduler.currentTime },
        onFrame = { frames += it },
        onBeep = { beeps += it },
    )

    @Test
    fun countsDownThenShowsEveryFrameOnTime() = runTest {
        val frames = mutableListOf<List<BoardHold>>()
        val beeps = mutableListOf<GameBeep>()
        val runner = runner(frames, beeps)
        runner.start(timeline(0, 1_000, 2_000), countdownSeconds = 3, finishWithTimeline = false)
        runCurrent()
        assertEquals(GamePhase.COUNTDOWN, runner.run.value.phase)
        assertEquals(3, runner.run.value.countdown)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, runner.run.value.countdown)
        assertTrue("nothing lit during the countdown", frames.isEmpty())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(GameBeep.COUNTDOWN, GameBeep.COUNTDOWN, GameBeep.COUNTDOWN, GameBeep.GO), beeps)
        assertEquals(GamePhase.RUNNING, runner.run.value.phase)
        assertEquals(1, frames.size)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, frames.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, frames.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, frames.size)
        assertTrue(runner.run.value.timelineDone)
        assertEquals(GamePhase.RUNNING, runner.run.value.phase)

        // The stopwatch keeps running until the climber stops.
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(runner.run.value.elapsedMs >= 6_900)
        runner.stop()
        assertEquals(GamePhase.FINISHED, runner.run.value.phase)
        assertEquals(7_000, runner.run.value.elapsedMs)
        assertEquals(3, frames.size)
    }

    @Test
    fun lavaEndsWhenItReachesTheTop() = runTest {
        val frames = mutableListOf<List<BoardHold>>()
        val runner = runner(frames, mutableListOf())
        val rows = WallRows.of((0 until 4).map { WallHold(it, 0.0, it * 4.0) })
        runner.start(FloorIsLava.timeline(rows, level = 4), countdownSeconds = 0, finishWithTimeline = true)
        // backgroundScope work does not count for advanceUntilIdle; move the clock instead.
        advanceTimeBy(10_000)
        runCurrent()
        val run = runner.run.value
        assertEquals(GamePhase.FINISHED, run.phase)
        assertEquals(2_000, run.elapsedMs)
        assertEquals(4, run.step)
        assertEquals(4, run.lit.size)
        assertEquals(listOf(0, 1, 2, 3, 4), frames.map { it.size })
    }

    @Test
    fun aRunnerThatFellBehindJumpsToTheNewestFrame() = runTest {
        val frames = mutableListOf<List<BoardHold>>()
        var clock = 0L
        val runner = WallGameRunner(
            scope = backgroundScope,
            now = { clock },
            onFrame = { frames += it },
        )
        runner.start(timeline(0, 100, 200, 300), countdownSeconds = 0, finishWithTimeline = true)
        runCurrent()
        assertEquals(1, frames.size)
        // The phone stalled for half a second: the next look sees frame 3 due.
        clock = 500
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(1, 4), frames.map { it.size })
        assertEquals(GamePhase.FINISHED, runner.run.value.phase)
    }

    @Test
    fun stoppingDuringTheCountdownGoesBackToTheStart() = runTest {
        val frames = mutableListOf<List<BoardHold>>()
        val runner = runner(frames, mutableListOf())
        runner.start(timeline(0, 1_000), countdownSeconds = 5, finishWithTimeline = false)
        advanceTimeBy(2_000)
        runner.stop()
        assertEquals(GamePhase.IDLE, runner.run.value.phase)
        advanceTimeBy(10_000)
        assertTrue(frames.isEmpty())
        assertTrue(!runner.isActive)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WallGameSenderTest {

    private fun holds(vararg ids: Int) = ids.map { BoardHold(it, HoldRole.START) }

    private fun write(ok: Boolean) = if (ok) WallWrite.SENT else WallWrite.FAILED

    @Test
    fun aSlowLinkOnlyGetsTheNewestStateNext() = runTest {
        val sent = mutableListOf<List<BoardHold>>()
        val gate = CompletableDeferred<Unit>()
        var first = true
        val sender = WallGameSender<List<BoardHold>>(backgroundScope, transmit = { h ->
            sent += h
            if (first) {
                first = false
                gate.await()
            }
            WallWrite.SENT
        })
        sender.show(holds(1))
        runCurrent()
        sender.show(holds(1, 2))
        sender.show(holds(1, 2, 3))
        sender.show(holds(1, 2, 3, 4))
        runCurrent()
        assertEquals(1, sent.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(holds(1), holds(1, 2, 3, 4)), sent)
    }

    @Test
    fun theSameStateIsNotSentTwiceUnlessForgottenAskedForOrFailed() = runTest {
        val sent = mutableListOf<List<BoardHold>>()
        var ok = true
        val results = mutableListOf<Boolean>()
        val sender = WallGameSender<List<BoardHold>>(backgroundScope, transmit = { h -> sent += h; write(ok) }, onResult = { results += it })
        sender.show(holds(1))
        runCurrent()
        sender.show(holds(1))
        runCurrent()
        assertEquals(1, sent.size)
        sender.forget()
        sender.show(holds(1))
        runCurrent()
        assertEquals(2, sent.size)
        // Once more on purpose: the board may have dropped it.
        sender.show(holds(1), again = true)
        runCurrent()
        assertEquals(3, sent.size)
        sender.show(holds(1))
        runCurrent()
        assertEquals(3, sent.size)

        ok = false
        sender.show(holds(2))
        runCurrent()
        // It gave up after its retries; the same state shown again goes out again.
        advanceTimeBy(WallGameSender.RETRY_MS * WallGameSender.RETRIES)
        runCurrent()
        assertEquals(4 + WallGameSender.RETRIES, sent.size)
        sender.show(holds(2))
        runCurrent()
        assertEquals(5 + WallGameSender.RETRIES, sent.size)
        assertEquals(List(3) { true } + List(2 + WallGameSender.RETRIES) { false }, results)
    }

    @Test
    fun aThrowingSendCountsAsFailedAndTheSenderKeepsGoing() = runTest {
        val results = mutableListOf<Boolean>()
        var calls = 0
        val sender = WallGameSender<List<BoardHold>>(backgroundScope, transmit = {
            calls++
            if (calls == 1) error("gatt gone") else WallWrite.SENT
        }, onResult = { results += it })
        sender.show(holds(1))
        runCurrent()
        sender.show(holds(2))
        advanceTimeBy(WallGameSender.RETRY_MS)
        runCurrent()
        assertEquals(listOf(false, true), results)
    }

    @Test
    fun aFailedWriteGoesOutAgainAfterAPauseAFewTimes() = runTest {
        val sent = mutableListOf<Long>()
        var failures = 2
        val sender = WallGameSender<List<BoardHold>>(backgroundScope, transmit = {
            sent += testScheduler.currentTime
            write(failures-- <= 0)
        })
        sender.show(holds(1))
        runCurrent()
        advanceTimeBy(WallGameSender.RETRY_MS - 1)
        runCurrent()
        assertEquals(listOf(0L), sent)
        advanceTimeBy(1 + WallGameSender.RETRY_MS)
        runCurrent()
        // The third write went through: no more.
        assertEquals(listOf(0L, WallGameSender.RETRY_MS, 2 * WallGameSender.RETRY_MS), sent)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, sent.size)

        // A write that never goes through is tried a few times, then left.
        val always = mutableListOf<List<BoardHold>>()
        val failing = WallGameSender<List<BoardHold>>(backgroundScope, transmit = { always += it; WallWrite.FAILED })
        failing.show(holds(1))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(List(1 + WallGameSender.RETRIES) { holds(1) }, always)
    }

    @Test
    fun aNewerStateGoesOutInsteadOfTheFailedOne() = runTest {
        val sent = mutableListOf<Pair<Long, List<BoardHold>>>()
        val gate = CompletableDeferred<Unit>()
        val sender = WallGameSender<List<BoardHold>>(backgroundScope, transmit = { h ->
            sent += testScheduler.currentTime to h
            if (h == holds(1)) gate.await()
            write(h != holds(1))
        })
        // Shown while the failing write is on the way: it goes out right after, without the pause.
        sender.show(holds(1))
        runCurrent()
        sender.show(holds(1, 2))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(0L to holds(1), 0L to holds(1, 2)), sent)
        // Shown during the pause after a failure: it goes out instead of the retry.
        sender.show(holds(1))
        runCurrent()
        advanceTimeBy(100)
        sender.show(holds(1, 3))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(holds(1), holds(1, 2), holds(1), holds(1, 3)), sent.map { it.second })
        assertEquals(WallGameSender.RETRY_MS, sent.last().first)
    }

    @Test
    fun aRefusedWriteIsNotTriedAgain() = runTest {
        val sent = mutableListOf<List<BoardHold>>()
        val sender = WallGameSender<List<BoardHold>>(backgroundScope, transmit = { sent += it; WallWrite.REFUSED })
        sender.show(holds(1))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(holds(1)), sent)
        // Not on the wall: shown again, it goes out again.
        sender.show(holds(1))
        runCurrent()
        assertEquals(2, sent.size)
    }

    @Test
    fun askedForAgainWhileAWriteIsOnTheWayTheStateGoesOutOnceMore() = runTest {
        val sent = mutableListOf<List<BoardHold>>()
        val gate = CompletableDeferred<Unit>()
        val sender = WallGameSender<List<BoardHold>>(backgroundScope, transmit = { h ->
            sent += h
            if (sent.size == 1) gate.await()
            WallWrite.SENT
        })
        sender.show(holds(1))
        runCurrent()
        sender.show(holds(1), again = true)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(holds(1), holds(1)), sent)
    }
}
