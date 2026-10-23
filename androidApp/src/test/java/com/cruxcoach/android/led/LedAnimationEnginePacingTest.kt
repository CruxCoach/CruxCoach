package com.cruxcoach.android.led

import com.cruxcoach.android.ble.LitFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * What the engine puts on the wall and how often: the lit climb frame pushed by
 * frame, the frame the climb actually shows, one frame never sent twice, and the
 * pacing rule — the effect's own tempo, stretched by the write groups the frame
 * needs, floored at three colour changes a second.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LedAnimationEnginePacingTest : LedEngineTestBase() {

    @Test
    fun pulse_pushesTheLitClimbFrameByFrame() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(500)
        assertTrue(h.sink.sent.size >= 3, "expected several frames, got ${h.sink.sent.size}")
        assertTrue(h.engine.isRunning.value)
        for (frame in h.sink.sent) {
            assertEquals(listOf(11, 22, 33, 44), frame.positions())
        }
        assertEquals(fourRoleFrame().staticFrame(), h.sink.sent.first(), "the first frame is the static look")
    }

    @Test
    fun anEditedClimbIsPickedUpWithoutRestartingTheEffect() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertEquals(listOf(11, 22, 33, 44), h.sink.sent.last().positions())

        h.sink.litFrame.value = litFrameOf(3)
        h.advance(300)
        assertEquals(listOf(1, 2, 3), h.sink.sent.last().positions())
        assertTrue(h.engine.isRunning.value, "the loop follows the board instead of restarting on it")
    }

    @Test
    fun aFrameIdenticalToThePreviousOneIsNeverSentTwice() = runEngineTest(
        // A climb whose holds are all set to black: every pulse frame is the same
        // all-zero frame, so the skip-identical rule — where the whole traffic
        // budget lives — is the only thing left saying anything.
        frame = litFrameOf(4, color = BLACK),
        selected = "pulse",
    ) { h ->
        h.advance(2_000)
        assertEquals(1, h.sink.attempts.size, "one frame says it; the rest are that frame")
        assertTrue(h.engine.isRunning.value, "the loop is idle on the link, not dead")
    }

    // ── Pacing ───────────────────────────────────────────────────

    @Test
    fun thePaceFollowsTheFrameSize() {
        // Nothing is launched here: this reads the pacing rule straight off the
        // engine, so no scheduler and no cleanup are involved.
        val engine = engineOn("pulse")
        assertEquals(180L, engine.frameIntervalMillis(PulseAnimation, 4), "one write group, the effect's own tempo")
        assertEquals(180L, engine.frameIntervalMillis(PulseAnimation, 40), "own tempo still beats three write groups")
        assertEquals(225L, engine.frameIntervalMillis(PulseAnimation, 80), "five write groups stretch it past the tempo")
        assertEquals(360L, engine.frameIntervalMillis(PulseAnimation, 120), "the LED cutoff is also the ceiling")
        assertEquals(45L, engine.frameIntervalMillis(EagerAnimation, 4), "never faster than one write group")
        assertEquals(0L, engine.frameIntervalMillis(PulseAnimation, 121), "too big to animate")
        assertEquals(0L, engine.frameIntervalMillis(PulseAnimation, 0), "nothing lit")
    }

    @Test
    fun aFlashingAnimationCannotPaceItselfIntoAHazard() {
        val engine = engineOn("pulse")
        assertEquals(
            PHOTOSENSITIVE_MIN_FRAME_MS,
            engine.frameIntervalMillis(FlashAnimation, 4),
            "at most three colour changes a second, whatever the effect asks for",
        )
        assertEquals(PHOTOSENSITIVE_MIN_FRAME_MS, engine.frameIntervalMillis(TwoPhaseStrobeAnimation, 4))
        assertEquals(
            PHOTOSENSITIVE_MIN_FRAME_MS,
            engine.frameIntervalMillis(ChaoticStrobeAnimation, 16),
            "pacing must not be used as an excuse to speed a strobe up",
        )
        assertEquals(360L, engine.frameIntervalMillis(TwoPhaseStrobeAnimation, 120))
    }

    @Test
    fun theLoopKeepsItsPromiseAboutTheFlashingFloor() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "two_phase_strobe",
    ) { h ->
        h.advance(PHOTOSENSITIVE_MIN_FRAME_MS - 3)
        assertEquals(1, h.sink.attempts.size, "the second frame must not come early")
        h.advance(6)
        assertEquals(2, h.sink.attempts.size)
    }

    private fun engineOn(selected: String): LedAnimationEngine =
        LedAnimationEngine(FakeLedFrameSink(), MutableStateFlow(selected), UnconfinedTestDispatcher())

    /** Not marked photosensitive, and asks for far less than one write group per frame. */
    private object EagerAnimation : LedAnimation {
        override val id = "eager"
        override val labelResId = 1
        override val hintResId = 2
        override val frameIntervalMillis = 5L
        override fun frameAt(base: LitFrame, tick: Int) =
            base.staticFrame().map { (position, _) -> position to if (tick % 2 == 0) RED else GREEN }
    }

    /** A strobe that wants to flash as fast as anything can be sent. */
    private object FlashAnimation : LedAnimation {
        override val id = "flash"
        override val labelResId = 3
        override val hintResId = 4
        override val frameIntervalMillis = 1L
        override val photosensitive = true
        override fun frameAt(base: LitFrame, tick: Int) =
            base.staticFrame().map { (position, _) -> position to if (tick % 2 == 0) RED else GREEN }
    }

    private companion object {
        const val BLACK = 0x00
    }
}
