package com.cruxcoach.android.ui.board.games

import com.cruxcoach.domain.board.games.KnotCall
import com.cruxcoach.domain.board.games.KnotColour
import com.cruxcoach.domain.board.games.KnotField
import com.cruxcoach.domain.board.games.KnotStripe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KnotRunnerTest {

    private val field = KnotField(
        climbers = 2,
        stripes = listOf(
            KnotStripe(KnotColour.BLUE, hands = listOf(1, 2, 3), feet = listOf(4, 5)),
            KnotStripe(KnotColour.YELLOW, hands = listOf(11, 12, 13), feet = listOf(14, 15)),
            KnotStripe(KnotColour.MAGENTA, hands = listOf(21, 22, 23), feet = listOf(24, 25)),
            KnotStripe(KnotColour.WHITE, hands = listOf(31, 32, 33), feet = listOf(34, 35)),
        ),
    )

    /** The same field with parts for three climbers. */
    private val field3 = field.copy(climbers = 3)

    private class Log {
        val walls = mutableListOf<Map<Int, KnotColour>>()
        /** What goes out once more after a blink, with its time. */
        val again = mutableListOf<Pair<Long, Map<Int, KnotColour>>>()
        val calls = mutableListOf<KnotCall>()
        val beeps = mutableListOf<GameBeep>()
        val stepIns = mutableListOf<Int>()
    }

    private fun TestScope.runner(log: Log) = KnotRunner(
        scope = backgroundScope,
        now = { testScheduler.currentTime },
        onWall = { log.walls += it },
        onWallAgain = { log.again += testScheduler.currentTime to it },
        onCall = { log.calls += it },
        onBeep = { log.beeps += it },
        onStepIn = { log.stepIns += it },
    )

    private companion object {
        const val LOST_FAILED = "failed"
        const val LOST_DROPPED = "dropped"
    }

    @Test
    fun theFieldStaysLitThroughStartAndCountdownThenCallsComeOnTime() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 2)
        assertEquals(KnotPhase.POSITIONS, runner.run.value.phase)
        assertEquals(listOf(field.spots), log.walls)

        runner.start(callMs = 4_000, seed = 1)
        runCurrent()
        assertEquals(KnotPhase.COUNTDOWN, runner.run.value.phase)
        advanceTimeBy(4_999)
        runCurrent()
        // Nothing changes on the wall during the countdown: the climbers are already on it.
        assertEquals(1, log.walls.size)
        assertTrue(log.calls.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(KnotPhase.RUNNING, runner.run.value.phase)
        assertEquals(List(5) { GameBeep.COUNTDOWN } + GameBeep.GO, log.beeps)
        assertEquals(1, log.calls.size)
        assertEquals(0, log.calls[0].climber)

        // The next call comes when the time for this one is up, to the next climber.
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(1, log.calls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, log.calls.size)
        assertEquals(1, log.calls[1].climber)
        assertEquals(1, runner.run.value.climbers[0].held)
    }

    @Test
    fun theCalledColourBlinksThreeTimesThenStaysLit() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 1)
        runner.start(callMs = 3_000, seed = 2)
        advanceTimeBy(5_000)
        runCurrent()
        val call = log.calls.single()
        advanceTimeBy(2_000)
        runCurrent()
        // Field, then dark/lit three times for the called colour's hand or foot spots only.
        val blink = log.walls.drop(1)
        assertEquals(6, blink.size)
        val dark = field.without(call.colour, hands = call.limb.isHand)
        blink.forEachIndexed { i, wall ->
            if (i % 2 == 0) assertEquals(dark, wall) else assertEquals(field.spots, wall)
        }
        val stripe = field.stripes.single { it.colour == call.colour }
        assertEquals((if (call.limb.isHand) stripe.hands else stripe.feet).toSet(), field.spots.keys - dark.keys)
    }

    @Test
    fun halfASecondAfterEveryBlinkTheLitFieldGoesOutOnceMore() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 2)
        runner.start(callMs = 4_000, seed = 1)
        // The first call after the countdown at 5 s blinks from 5.5 to 7 s, the second from 9.25 to 10.75 s.
        advanceTimeBy(7_499)
        runCurrent()
        assertTrue(log.again.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(7_500L to field.spots), log.again)
        advanceTimeBy(11_250 - 7_500)
        runCurrent()
        assertEquals(listOf(7_500L to field.spots, 11_250L to field.spots), log.again)
        // Nothing else in between: the wall stays as the blink left it.
        assertEquals(field.spots, log.walls.last())
    }

    @Test
    fun aRoundThatEndsRightAfterABlinkSendsNoLitFieldAfterIt() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 2)
        runner.start(callMs = 4_000, seed = 1)
        // Stopped 100 ms after the first call's blink: the field's second send, due at 7.5 s, never comes; the dark wall
        // goes out once more half a second after the end blink instead.
        advanceTimeBy(7_100)
        runCurrent()
        runner.stop()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(7_100L + 1_500 + 500 to emptyMap<Int, KnotColour>()), log.again)

        // Back to the settings right after a blink: nothing more goes out.
        val after = Log()
        val again = runner(after)
        again.light(field, players = 2)
        again.start(callMs = 4_000, seed = 1)
        advanceTimeBy(7_100)
        runCurrent()
        again.reset()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(after.again.isEmpty())
        assertEquals(emptyMap<Int, KnotColour>(), after.walls.last())
    }

    @Test
    fun byTapTheNextBlinkIsNotCutByTheLastOnesSecondSend() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 2)
        runner.start(callMs = 0, seed = 8)
        // The first call blinks from 5.5 to 7 s; while the wall waits for a tap, the field goes out again at 7.5 s.
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(listOf(7_500L to field.spots), log.again)
        // A tap right after the next blink: the blink after it starts before that blink's second send was due.
        runner.next()
        advanceTimeBy(250 + 1_500 + 100)
        runCurrent()
        runner.next()
        runCurrent()
        val tapped = testScheduler.currentTime
        advanceTimeBy(250 + 1_500 - 1)
        runCurrent()
        // Only the first call's second send so far: the second call's was cut by the third call's blink.
        assertEquals(1, log.again.size)
        advanceTimeBy(1 + 500)
        runCurrent()
        assertEquals(listOf(7_500L to field.spots, tapped + 250 + 1_500 + 500 to field.spots), log.again)
    }

    /**
     * A board behind the app's [WallGameSender]: [lose] says, by write number (from 1), which writes fail ([LOST_FAILED])
     * or go through without the wall taking them ([LOST_DROPPED]).
     */
    private class FakeBoard(val lose: (Int) -> String?) {
        var shows: Map<Int, KnotColour> = emptyMap()
        var writes = 0

        fun transmit(spots: Map<Int, KnotColour>): WallWrite {
            writes++
            return when (lose(writes)) {
                LOST_FAILED -> WallWrite.FAILED
                LOST_DROPPED -> WallWrite.SENT
                else -> {
                    shows = spots
                    WallWrite.SENT
                }
            }
        }
    }

    private fun TestScope.runnerOn(board: FakeBoard): KnotRunner {
        val sender = WallGameSender<Map<Int, KnotColour>>(backgroundScope, transmit = { board.transmit(it) })
        return KnotRunner(
            scope = backgroundScope,
            now = { testScheduler.currentTime },
            onWall = { sender.show(it) },
            onWallAgain = { sender.show(it, again = true) },
        )
    }

    @Test
    fun theWallEndsLitWhenTheBoardDropsTheLastFrameOfABlink() = runTest {
        // Write 1 lights the field, writes 2 to 7 are the first call's blink from 5.5 s: 7 is the lit field at 6.75 s.
        val board = FakeBoard { if (it == 7) LOST_DROPPED else null }
        val runner = runnerOn(board)
        runner.light(field, players = 1)
        runner.start(callMs = 4_000, seed = 2)
        advanceTimeBy(7_100)
        runCurrent()
        // The write went out, but the wall missed it: the called spots are dark.
        assertEquals(7, board.writes)
        assertTrue(board.shows.size < field.spots.size)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(field.spots, board.shows)
    }

    @Test
    fun theWallEndsLitWhenTheLastWriteOfABlinkFails() = runTest {
        val board = FakeBoard { if (it == 7) LOST_FAILED else null }
        val runner = runnerOn(board)
        runner.light(field, players = 1)
        runner.start(callMs = 4_000, seed = 2)
        advanceTimeBy(6_800)
        runCurrent()
        assertTrue(board.shows.size < field.spots.size)
        // Tried again after a short pause, before the blink's second send.
        advanceTimeBy(WallGameSender.RETRY_MS)
        runCurrent()
        assertEquals(8, board.writes)
        assertEquals(field.spots, board.shows)
    }

    @Test
    fun theWallEndsDarkWhenTheBoardDropsTheClearAfterTheEndBlink() = runTest {
        val board = FakeBoard { null }
        lateinit var runner: KnotRunner
        var clears = 0
        val sender = WallGameSender<Map<Int, KnotColour>>(backgroundScope, transmit = { spots ->
            // After the round the wall goes dark four times: three in the blink, then for good. The board misses that last one.
            val lost = spots.isEmpty() && runner.run.value.phase == KnotPhase.FINISHED && ++clears == 4
            if (lost) WallWrite.SENT else board.transmit(spots)
        })
        runner = KnotRunner(
            scope = backgroundScope,
            now = { testScheduler.currentTime },
            onWall = { sender.show(it) },
            onWallAgain = { sender.show(it, again = true) },
        )
        runner.light(field, players = 1)
        runner.start(callMs = 3_000, seed = 4)
        advanceTimeBy(5_000 + 3_000)
        runCurrent()
        runner.out(0)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(field.spots, board.shows)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(emptyMap<Int, KnotColour>(), board.shows)
    }

    @Test
    fun theLastClimberOnTheWallWinsAndTheFieldBlinksBeforeTheWallClears() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field3, players = 3)
        runner.start(callMs = 3_000, seed = 3)
        advanceTimeBy(5_000 + 3_000 * 4)
        runCurrent()
        runner.out(1)
        assertEquals(KnotPhase.RUNNING, runner.run.value.phase)
        assertEquals(setOf(0, 2), runner.run.value.stillIn)
        // A double tap is one tap.
        advanceTimeBy(300)
        runner.out(1)
        assertEquals(setOf(0, 2), runner.run.value.stillIn)
        // A later tap undoes a wrong one, and a tap after that puts the climber out again.
        advanceTimeBy(2_000)
        runner.out(1)
        assertEquals(setOf(0, 1, 2), runner.run.value.stillIn)
        runner.out(1)
        assertEquals(setOf(0, 2), runner.run.value.stillIn)
        val before = log.walls.size
        runner.out(0)
        assertEquals(KnotPhase.FINISHED, runner.run.value.phase)
        assertEquals(2, runner.run.value.winner)
        advanceTimeBy(2_000)
        runCurrent()
        val end = log.walls.drop(before)
        assertEquals(listOf(emptyMap<Int, KnotColour>(), field.spots, emptyMap(), field.spots, emptyMap(), field.spots, emptyMap()), end)
        // Half a second after the end blink the dark wall goes out once more.
        assertEquals(emptyMap<Int, KnotColour>(), log.again.last().second)
        // No more calls after the end.
        val calls = log.calls.size
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(calls, log.calls.size)
    }

    @Test
    fun soloEndsWhenTheClimberIsOutAndCountsTheCallsHeld() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 1)
        runner.start(callMs = 3_000, seed = 4)
        advanceTimeBy(5_000 + 3_000 * 3 + 1_000)
        runCurrent()
        // Off during the fourth call: three held.
        runner.out(0)
        val run = runner.run.value
        assertEquals(KnotPhase.FINISHED, run.phase)
        assertNull(run.winner)
        assertEquals(3, run.climbers[0].held)
        assertEquals(4, run.call!!.number)
    }

    @Test
    fun aClimberOutDuringTheirCallDoesNotHoldItAndIsSkipped() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field3, players = 3)
        runner.start(callMs = 3_000, seed = 5)
        advanceTimeBy(5_000 + 3_000 + 1_000)
        runCurrent()
        assertEquals(1, runner.run.value.call!!.climber)
        runner.out(1)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, runner.run.value.climbers[1].held)
        assertEquals(2, runner.run.value.call!!.climber)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(0, runner.run.value.call!!.climber)
    }

    @Test
    fun stopBeforeTheCountdownGoesBackAndClearsAndStopAfterEndsWithoutAWinner() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 2)
        runner.stop()
        assertEquals(KnotPhase.IDLE, runner.run.value.phase)
        assertEquals(emptyMap<Int, KnotColour>(), log.walls.last())
        assertFalse(runner.run.value.active)

        runner.light(field, players = 2)
        runner.start(callMs = 3_000, seed = 6)
        advanceTimeBy(6_000)
        runCurrent()
        runner.stop()
        assertEquals(KnotPhase.FINISHED, runner.run.value.phase)
        assertNull(runner.run.value.winner)
        assertTrue(runner.run.value.stopped)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(emptyMap<Int, KnotColour>(), log.walls.last())

        runner.reset()
        assertEquals(KnotRun(), runner.run.value)
    }

    @Test
    fun byTapTheNextCallComesOnlyWhenSomeoneTaps() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 2)
        runner.start(callMs = 0, seed = 8)
        // A tap during the countdown is not a call.
        runner.next()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(runner.run.value.byTap)
        assertEquals(1, log.calls.size)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, log.calls.size)
        assertEquals(0, runner.run.value.climbers[0].held)

        runner.next()
        runCurrent()
        assertEquals(2, log.calls.size)
        assertEquals(1, runner.run.value.climbers[0].held)
        // Taps during the next call's blink don't count: a double tap doesn't skip a call.
        assertFalse(runner.run.value.tapReady)
        runner.next()
        runner.next()
        advanceTimeBy(1_750)
        runCurrent()
        assertEquals(2, log.calls.size)
        assertTrue(runner.run.value.tapReady)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, log.calls.size)
        // Once it has blinked, a tap calls the next one, and a second tap of a double tap is ignored.
        runner.next()
        runner.next()
        runCurrent()
        assertEquals(3, log.calls.size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, log.calls.size)
    }

    @Test
    fun outCountsDuringTheCountdownToo() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 2)
        runner.start(callMs = 3_000, seed = 7)
        advanceTimeBy(2_000)
        runCurrent()
        runner.out(0)
        assertEquals(KnotPhase.FINISHED, runner.run.value.phase)
        assertEquals(1, runner.run.value.winner)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(log.calls.isEmpty())
    }

    // ── The line ────────────────────────────────────────────────────

    @Test
    fun withMorePlayersThanPartsTheOthersWaitAndTheNextGetsTimeToReachTheFreedPart() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 4)
        val lit = runner.run.value
        assertTrue(lit.withLine)
        assertEquals(listOf(2, 3), lit.line)
        assertEquals(listOf(0, 1), lit.onParts)

        runner.start(callMs = 3_000, seed = 9)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(0, log.calls.single().climber)
        // Climber 0 comes off: 2 is called to the left part.
        runner.out(0)
        assertEquals(listOf(2), log.stepIns)
        assertEquals(2, runner.run.value.stepIn)
        assertEquals(listOf(2, 1), runner.run.value.onParts)
        assertEquals(listOf(3), runner.run.value.line)
        // The calls stay with 1 until 2 has had 8 s to get there.
        advanceTimeBy(2_000 + 3_000 + 2_999)
        runCurrent()
        assertEquals(listOf(0, 1, 1), log.calls.map { it.climber })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0, 1, 1, 2), log.calls.map { it.climber })
        assertNull(runner.run.value.stepIn)
        assertEquals(KnotPhase.RUNNING, runner.run.value.phase)
    }

    @Test
    fun aDoubleTapDoesNotPutTheNextInLineOutAndAWrongOutIsUndoneUntilTheNextCall() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 3)
        runner.start(callMs = 3_000, seed = 10)
        advanceTimeBy(6_000)
        runCurrent()
        runner.out(1)
        // The second tap of a double tap lands on the row of 2, who just took the part.
        advanceTimeBy(300)
        runner.out(2)
        assertTrue(runner.run.value.climbers[2].atWall)
        // A deliberate second tap on 1 undoes the out: 1 is back, 2 waits again, first in line.
        advanceTimeBy(1_000)
        runner.out(1)
        assertEquals(listOf(0, 1), runner.run.value.onParts)
        assertEquals(listOf(2), runner.run.value.line)
        assertNull(runner.run.value.stepIn)
        // Out again, and after the next call the out stands.
        runner.out(1)
        advanceTimeBy(3_000)
        runCurrent()
        runner.out(1)
        assertTrue(runner.run.value.climbers[1].out)
        assertEquals(listOf(0, 2), runner.run.value.onParts)
    }

    @Test
    fun theLineEndsWhenEveryoneHasClimbedAndTheMostCallsHeldWin() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 3)
        runner.start(callMs = 3_000, seed = 11)
        // Calls to 0 and 1 in turn: after four calls each has held two.
        advanceTimeBy(5_000 + 4 * 3_000 + 1_000)
        runCurrent()
        assertEquals(listOf(2, 2), runner.run.value.climbers.take(2).map { it.held })
        runner.out(0)
        // 1 holds on; 2 steps in. Nobody is the last one standing: the round goes on until all are off.
        advanceTimeBy(30_000)
        runCurrent()
        runner.out(1)
        assertEquals(KnotPhase.RUNNING, runner.run.value.phase)
        advanceTimeBy(1_750)
        runner.out(2)
        val run = runner.run.value
        assertEquals(KnotPhase.FINISHED, run.phase)
        assertFalse(run.stopped)
        val held = run.climbers.map { it.held }
        assertEquals(held.indexOf(held.max()), run.winner)
        assertTrue(held[1] > held[0])
    }

    @Test
    fun aTieInTheLineHasNoWinnerAndByTapTheNextInLineIsCalledAtOnce() = runTest {
        val log = Log()
        val runner = runner(log)
        runner.light(field, players = 3)
        runner.start(callMs = 0, seed = 12)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(0), log.calls.map { it.climber })
        runner.out(0)
        // By tap there is no wait: whoever taps "Next call" waits until everyone is ready.
        advanceTimeBy(2_000)
        runCurrent()
        runner.next()
        runCurrent()
        advanceTimeBy(1_750)
        runCurrent()
        runner.next()
        runCurrent()
        assertEquals(listOf(0, 1, 2), log.calls.map { it.climber })
        // 2 holds their call; 1 comes off during the next one, then 2: one call each.
        advanceTimeBy(1_750)
        runCurrent()
        runner.next()
        runCurrent()
        assertEquals(1, log.calls.last().climber)
        runner.out(1)
        advanceTimeBy(1_750)
        runner.out(2)
        val run = runner.run.value
        assertEquals(KnotPhase.FINISHED, run.phase)
        assertEquals(listOf(0, 1, 1), run.climbers.map { it.held })
        assertNull(run.winner)
        assertFalse(run.stopped)
    }
}
