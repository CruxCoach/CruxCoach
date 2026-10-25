package com.cruxcoach.android.led

import com.cruxcoach.android.ble.LitFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The limits put on the contributed effects for the 0.2.5 line: a time limit per
 * climb, Stop from the notification, a strobe that keeps its distance from the
 * write before, a slow link that slows the effect instead of filling up, the
 * tempo, Android's "Remove animations", hold cleaning, and the status the
 * settings show.
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
    fun aStrobeWaitsItsWholeIntervalAfterTheWriteHasFinished() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "two_phase_strobe",
    ) { h ->
        // The first frame went out instantly at 0; from then on writes take
        // 200 ms. Starts at 0, 333, 866, 1399 — each 333 ms after the previous
        // write ended, so two colour changes on the wall are never closer than
        // that, however long a write takes (counted from the start: 0, 333, 666, 999).
        h.sink.writeMillis = 200
        h.advance(1_000)
        assertEquals(3, h.sink.attempts.size)
        h.advance(300)
        assertEquals(3, h.sink.attempts.size)
        h.advance(100)
        assertEquals(4, h.sink.attempts.size)
    }

    @Test
    fun aSlowLinkGetsASlowerEffectInsteadOfAFullOne() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        // A write-only box paced at 100 ms per chunk: 300 ms a frame (the first one
        // at 0 went out instantly). The loop then waits as long again, so the link
        // stays free half the time for the user's own sends — frames at 0, 180,
        // 780, 1380 instead of 0, 180, 500, 800, 1100.
        h.sink.writeMillis = 300
        h.advance(1_000)
        assertEquals(3, h.sink.attempts.size)
        h.advance(300)
        assertEquals(3, h.sink.attempts.size)
        h.advance(100)
        assertEquals(4, h.sink.attempts.size)
    }

    @Test
    fun theTempoScalesTheEffectButNeverAStrobeBelowItsFloor() {
        val engine = LedAnimationEngine(FakeLedFrameSink(), MutableStateFlow(LedEffectSettings()), UnconfinedTestDispatcher())
        assertEquals(288L, engine.frameIntervalMillis(PulseAnimation, 4, LedEffectTempo.CALM))
        assertEquals(126L, engine.frameIntervalMillis(PulseAnimation, 4, LedEffectTempo.FAST))
        assertEquals(533L, engine.frameIntervalMillis(TwoPhaseStrobeAnimation, 4, LedEffectTempo.CALM))
        assertEquals(
            PHOTOSENSITIVE_MIN_FRAME_MS,
            engine.frameIntervalMillis(TwoPhaseStrobeAnimation, 4, LedEffectTempo.FAST),
            "fast never means more than three colour changes a second",
        )
        assertEquals(LedEffectTempo.NORMAL, LedEffectTempo.byId("unknown"))
    }

    @Test
    fun androidsRemoveAnimationsKeepsTheStrobesOff() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "chaotic_strobe",
        settings = LedEffectSettings(flashingAllowed = false),
    ) { h ->
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty())
        assertEquals(LedEffectStatus.FLASHING_BLOCKED, h.engine.status.value)

        h.selectedId.value = "pulse"
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty(), "gradual effects stay available")
        assertEquals(LedEffectStatus.RUNNING, h.engine.status.value)
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
