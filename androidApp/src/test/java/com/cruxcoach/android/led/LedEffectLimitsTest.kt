package com.cruxcoach.android.led

import com.cruxcoach.android.ble.LitFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * How the contributed effects fit into the 0.2.5 line: a time limit per climb,
 * Stop from the notification, the contributed rhythm kept on slow writes, hold
 * cleaning, the status the settings show, and the keep-alive choice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LedEffectLimitsTest : LedEngineTestBase() {

    @Test
    fun anEffectEndsAfterTheTimeLimitOnTheClimbsOwnColoursUntilTheNextSend() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(LedAnimationEngine.MAX_EFFECT_MS - 1_000)
        assertTrue(h.engine.isRunning.value)
        h.advance(2_000)
        assertFalse(h.engine.isRunning.value, "15 minutes on one climb is the limit")
        assertEquals(fourRoleFrame().staticFrame(), h.sink.attempts.last(), "the climb's own colours come back")
        assertEquals(LedEffectStatus.ENDED, h.engine.status.value)
        assertFalse(h.engine.wantsToRun.value, "nothing left for the background to keep")

        h.sink.reset()
        h.advance(5_000)
        assertTrue(h.sink.attempts.isEmpty(), "it stays ended for this climb")

        h.sink.litFrame.value = LitFrame(fourRoleFrame().leds, sendId = 1)
        h.advance(300)
        assertTrue(h.engine.isRunning.value, "sending the climb again starts it afresh")
        assertTrue(h.sink.sent.isNotEmpty())
    }

    @Test
    fun stopForTheLitClimbEndsTheEffectButKeepsTheSetting() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "rainbow_wave",
    ) { h ->
        h.advance(500)
        assertTrue(h.engine.isRunning.value)
        h.engine.stopForCurrentClimb()
        h.advance(500)
        assertFalse(h.engine.isRunning.value)
        assertEquals(fourRoleFrame().staticFrame(), h.sink.attempts.last())
        assertEquals("rainbow_wave", h.selectedId.value)

        h.sink.litFrame.value = litFrameOf(3)
        h.advance(300)
        assertTrue(h.engine.isRunning.value, "the next climb moves again")
    }

    @Test
    fun aStrobeKeepsTheContributedRhythmWhenWritesTakeTime() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "two_phase_strobe",
    ) { h ->
        // The contributed rhythm, kept on purpose: frames start every 333 ms,
        // counted from the start of the previous frame (0, 333, 666, 999), also
        // when each write takes 200 ms.
        h.sink.writeMillis = 200
        h.advance(1_000)
        assertEquals(4, h.sink.attempts.size)
    }

    @Test
    fun aSlowLinkRunsTheEffectAsFastAsItsWritesAllowAsContributed() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        // Writes of 300 ms (a write-only box paced at 100 ms per chunk) leave the
        // shortest gap after each frame: starts at 0, 180, 500, 820.
        h.sink.writeMillis = 300
        h.advance(1_000)
        assertEquals(4, h.sink.attempts.size)
    }

    @Test
    fun holdCleaningPausesTheEffectWithoutPaintingOverIt() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        h.sink.reset()
        h.sink.availabilityState.value = LedBoardAvailability.CLEANING
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty(), "the holds to clean stay as cleaning lit them")
        assertFalse(h.engine.isRunning.value)
        assertEquals(LedEffectStatus.PAUSED, h.engine.status.value)

        h.sink.availabilityState.value = LedBoardAvailability.READY
        h.advance(300)
        assertTrue(h.engine.isRunning.value, "the effect returns once cleaning is done")
    }

    @Test
    fun theStatusSaysWhatTheEffectIsDoing() = runEngineTest(
        frame = null,
        selected = "none",
    ) { h ->
        assertEquals(LedEffectStatus.OFF, h.engine.status.value)
        h.selectedId.value = "pulse"
        h.advance(10)
        assertEquals(LedEffectStatus.WAITING_FOR_CLIMB, h.engine.status.value)
        h.sink.availabilityState.value = LedBoardAvailability.NO_BOARD
        h.advance(10)
        assertEquals(LedEffectStatus.NO_BOARD, h.engine.status.value)
        h.sink.availabilityState.value = LedBoardAvailability.READY
        h.sink.litFrame.value = fourRoleFrame()
        h.advance(10)
        assertEquals(LedEffectStatus.RUNNING, h.engine.status.value)
        h.engine.pause(AnimationPauseOwner.WALL_GAME)
        h.advance(10)
        assertEquals(LedEffectStatus.PAUSED, h.engine.status.value)
        h.engine.resume(AnimationPauseOwner.WALL_GAME)
        h.advance(10)
        assertEquals(LedEffectStatus.RUNNING, h.engine.status.value)
    }

    @Test
    fun theBackgroundIsKeptOnlyWhenTheUserChoseIt() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.engine.wantsToRun.value)
        assertFalse(h.engine.shouldKeepRunningInBackground(), "off by default")
        h.settings.value = LedEffectSettings(keepRunningInBackground = true)
        h.advance(10)
        assertTrue(h.engine.shouldKeepRunningInBackground())
    }
}
