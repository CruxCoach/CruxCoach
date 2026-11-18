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
        val calls = mutableListOf<KnotCall>()
        val beeps = mutableListOf<GameBeep>()
        val stepIns = mutableListOf<Int>()
    }

    private fun TestScope.runner(log: Log) = KnotRunner(
        scope = backgroundScope,
        now = { testScheduler.currentTime },
        onWall = { log.walls += it },
        onCall = { log.calls += it },
        onBeep = { log.beeps += it },
        onStepIn = { log.stepIns += it },
    )

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
