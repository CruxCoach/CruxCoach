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

    @Test
    fun aSlowLinkOnlyGetsTheNewestStateNext() = runTest {
        val sent = mutableListOf<List<BoardHold>>()
        val gate = CompletableDeferred<Unit>()
        var first = true
        val sender = WallGameSender(backgroundScope, transmit = { h ->
            sent += h
            if (first) {
                first = false
                gate.await()
            }
            true
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
    fun theSameStateIsNotSentTwiceUnlessForgottenOrFailed() = runTest {
        val sent = mutableListOf<List<BoardHold>>()
        var ok = true
        val results = mutableListOf<Boolean>()
        val sender = WallGameSender(backgroundScope, transmit = { h -> sent += h; ok }, onResult = { results += it })
        sender.show(holds(1))
        runCurrent()
        sender.show(holds(1))
        runCurrent()
        assertEquals(1, sent.size)
        sender.forget()
        sender.show(holds(1))
        runCurrent()
        assertEquals(2, sent.size)

        ok = false
        sender.show(holds(2))
        runCurrent()
        sender.show(holds(2))
        runCurrent()
        assertEquals(4, sent.size)
        assertEquals(listOf(true, true, false, false), results)
    }

    @Test
    fun aThrowingSendCountsAsFailedAndTheSenderKeepsGoing() = runTest {
        val results = mutableListOf<Boolean>()
        var calls = 0
        val sender = WallGameSender(backgroundScope, transmit = {
            calls++
            if (calls == 1) error("gatt gone") else true
        }, onResult = { results += it })
        sender.show(holds(1))
        runCurrent()
        sender.show(holds(2))
        runCurrent()
        assertEquals(listOf(false, true), results)
    }
}
