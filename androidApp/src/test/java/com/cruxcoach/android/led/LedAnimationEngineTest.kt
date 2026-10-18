package com.cruxcoach.android.led

import com.cruxcoach.android.ble.FrameSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * What the engine owes the board: frames only when it is allowed to animate, the
 * static colours back when the effect is switched off, and a prompt stop when
 * the link or the climb goes away. What it puts on the wall, and how often, is
 * the pacing suite's subject.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LedAnimationEngineTest : LedEngineTestBase() {

    // ── When it stays quiet ──────────────────────────────────────

    @Test
    fun staticSelectionSendsNothing() = runEngineTest(frame = fourRoleFrame(), selected = "none") { h ->
        h.advance(2_000)
        assertTrue(h.sink.sent.isEmpty(), "the static look is what the climb send already did")
        assertFalse(h.engine.isRunning.value)
    }

    @Test
    fun nothingIsSentWhileTheAppIsInBackground() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        foreground = false,
    ) { h ->
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty(), "a background board must not be written to")
        assertFalse(h.engine.isRunning.value)

        h.engine.setForeground(true)
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty(), "coming back to the foreground resumes the effect")
    }

    @Test
    fun nothingIsSentWhileAnotherFeatureOwnsTheBoard() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        ownedBy = setOf(AnimationPauseOwner.ROUTE_PLAYBACK),
    ) { h ->
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty())
        assertFalse(h.engine.isRunning.value)

        h.engine.resume(AnimationPauseOwner.ROUTE_PLAYBACK)
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty(), "handing the board back restarts the effect")
    }

    @Test
    fun pausingDoesNotRepaintTheStaticColours() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty())
        h.sink.reset()

        h.engine.pause(AnimationPauseOwner.EASTER_ANIMATION)
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty(), "the feature that just took the board must not be overwritten")
        assertFalse(h.engine.isRunning.value)
    }

    @Test
    fun aSecondClaimFromTheSameOwnerHoldsTheBoardUntilBothLetGo() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        ownedBy = setOf(AnimationPauseOwner.EASTER_ANIMATION),
    ) { h ->
        // A re-triggered effect overlaps its predecessor's cancellation: the old
        // run's release lands while the new run's claim still stands, and two
        // screens can hold one owner at once. Counts, not sets.
        h.engine.pause(AnimationPauseOwner.EASTER_ANIMATION)
        h.engine.resume(AnimationPauseOwner.EASTER_ANIMATION)
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty(), "one holder left; the other is mid-run")
        assertFalse(h.engine.isRunning.value)

        h.engine.resume(AnimationPauseOwner.EASTER_ANIMATION)
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty(), "the last holder releasing hands the board back")
    }

    @Test
    fun aResumeWithoutAPauseIsFeltByNobody() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.engine.resume(AnimationPauseOwner.ROUTE_PLAYBACK)
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty(), "a stray release must not pause the effect")

        h.engine.pause(AnimationPauseOwner.ROUTE_PLAYBACK)
        h.sink.reset()
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty(), "and it must not swallow the next real claim either")
    }

    @Test
    fun nothingIsSentWithoutALitClimb() = runEngineTest(frame = null, selected = "pulse") { h ->
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty())
        assertFalse(h.engine.isRunning.value)

        h.sink.litFrame.value = fourRoleFrame()
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty(), "sending a climb must be enough to start the effect")
    }

    @Test
    fun nothingIsSentWhenTheBoardRefusesFrames() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        canAnimate = false,
    ) { h ->
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty())
        // The loop parks rather than ends: the refusal is not observable to the
        // watcher, and a loop that walked out on a transient one could end the
        // user's effect on a state change the watcher never saw.
        assertTrue(h.engine.isRunning.value, "the loop waits for the gate to reopen")
    }

    @Test
    fun aBoardThatStopsTakingFramesParksTheLoopUntilItReopens() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty())
        h.sink.reset()

        // A paired board of another brand: the loop stops writing on its own,
        // without waiting for the animation setting to change. A real disconnect
        // nulls the lit frame, and the watcher ends the loop for that.
        h.sink.canAnimate = false
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty())
        assertTrue(h.engine.isRunning.value, "a refused board parks the loop, it does not kill it")

        // The tick is held while parked, so the first frame after the gate
        // reopens equals the last sent one and is skipped; the next is new.
        h.sink.canAnimate = true
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty(), "the parked loop resumes on its own")
    }

    @Test
    fun aRelitClimbTheWatcherOnlySeesWholeKeepsTheEffectOnTheNewFrame() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty())
        h.sink.reset()

        // A send that clears the lit frame and refills it inside one scheduler
        // tick reaches the watcher as a single change — the null is conflated
        // away, so a loop that ended on the gap would have ended an effect the
        // watcher never saw leave. The loop polls instead: it rides the gap.
        h.sink.litFrame.value = null
        h.sink.litFrame.value = litFrameOf(2)
        h.advance(300)
        assertTrue(h.engine.isRunning.value, "the effect survives the unobserved gap")
        assertEquals(2, h.sink.sent.last().size, "and moves onto the new climb's LEDs")
    }

    @Test
    fun aBoardTooBigToAnimateIsLeftStatic() = runEngineTest(
        frame = litFrameOf(MAX_ANIMATED_LEDS + 1),
        selected = "pulse",
    ) { h ->
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty(), "over the LED cutoff the wall keeps its static colours")
        assertFalse(h.engine.isRunning.value)
    }

    @Test
    fun aDisconnectedBoardEndsTheLoopWithoutRepainting() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty())
        h.sink.reset()

        h.sink.litFrame.value = null
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty(), "there is nothing to repaint after a disconnect")
        assertFalse(h.engine.isRunning.value)
    }

    @Test
    fun repeatedWriteFailuresEndTheLoop() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        sendResult = FrameSendResult.FAILED,
    ) { h ->
        h.advance(5_000)
        // Three failures and nothing else: no frame ever got through, so the static
        // colours are still on the wall and there is nothing to repaint.
        assertEquals(3, h.sink.attempts.size, "the loop must give up, not retry forever")
        assertTrue(h.sink.sent.isEmpty())
        assertFalse(h.engine.isRunning.value)
    }

    // ── When the link is busy or going bad ───────────────────────

    @Test
    fun aBusyLinkCostsFramesNotTheAnimation() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        sendResult = FrameSendResult.DEFERRED,
    ) { h ->
        // Everything the user sends by hand parks the link for a moment. Those beats
        // are dropped, not counted against the board.
        h.advance(1_000)
        assertTrue(h.sink.attempts.isNotEmpty(), "the loop keeps offering frames")
        assertTrue(h.sink.sent.isEmpty())
        assertTrue(h.engine.isRunning.value, "a busy link is not a broken link")

        h.sink.result = FrameSendResult.SENT
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty(), "the effect picks itself back up")
    }

    @Test
    fun aTurnedDownFrameIsRecomputedFromTheClimbOnTheWall() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        sendResult = FrameSendResult.DEFERRED,
    ) { h ->
        h.advance(300)

        h.sink.litFrame.value = litFrameOf(3)
        h.sink.result = FrameSendResult.SENT
        h.advance(300)
        assertEquals(
            listOf(1, 2, 3),
            h.sink.sent.last().positions(),
            "the beat that lands is built from the current climb, never a stale one",
        )
    }

    @Test
    fun givingUpOnADyingLinkPaintsTheStaticColoursBack() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty())
        h.sink.reset()

        h.sink.result = FrameSendResult.FAILED
        h.advance(1_000)
        assertFalse(h.engine.isRunning.value)
        assertEquals(
            fourRoleFrame().staticFrame(),
            h.sink.attempts.last(),
            "the wall must not freeze mid-breath just because the link went bad",
        )
    }

    @Test
    fun aClimbThatGrowsTooBigToAnimateIsPaintedBackToStatic() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty())
        h.sink.reset()

        h.sink.litFrame.value = litFrameOf(MAX_ANIMATED_LEDS + 1)
        h.advance(300)
        assertFalse(h.engine.isRunning.value)
        assertEquals(
            litFrameOf(MAX_ANIMATED_LEDS + 1).staticFrame(),
            h.sink.attempts.single(),
            "past the cutoff the new climb keeps the colours its send gave it",
        )
    }

    @Test
    fun leavingTheForegroundDoesNotRepaintTheStaticColours() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty())
        h.sink.reset()

        h.engine.setForeground(false)
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty(), "going quiet is not the same as putting colours back")
        assertFalse(h.engine.isRunning.value)
    }

    // ── When the keep-alive service claims the background ────────

    @Test
    fun aClaimedBackgroundAnimatesAsThoughTheAppWereUp() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        foreground = false,
    ) { h ->
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty(), "unclaimed, the background stays quiet")

        h.engine.claimBackground()
        h.advance(300)
        assertTrue(h.engine.isRunning.value)
        assertTrue(h.sink.sent.isNotEmpty(), "the service's claim is the permission to write")
    }

    @Test
    fun releasingTheClaimSilencesTheBackgroundWithoutRepainting() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        foreground = false,
    ) { h ->
        h.engine.claimBackground()
        h.advance(300)
        assertTrue(h.engine.isRunning.value)
        h.sink.reset()

        h.engine.releaseBackground()
        h.advance(2_000)
        assertFalse(h.engine.isRunning.value)
        assertTrue(
            h.sink.attempts.isEmpty(),
            "a keep-alive that died must not write — not even the static colours back",
        )
    }

    @Test
    fun theClaimDoesNotOutrankAFeatureThatOwnsTheBoard() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
        foreground = false,
        ownedBy = setOf(AnimationPauseOwner.ROUTE_PLAYBACK),
    ) { h ->
        h.engine.claimBackground()
        h.advance(2_000)
        assertTrue(h.sink.attempts.isEmpty())
        assertFalse(h.engine.isRunning.value)
    }

    @Test
    fun wantsToRunOutlivesTheForegroundItWasMadeFor() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.engine.wantsToRun.value)

        h.engine.setForeground(false)
        h.advance(300)
        assertFalse(h.engine.isRunning.value, "the app switch closed the gate")
        assertTrue(
            h.engine.wantsToRun.value,
            "the work is still there — this is what the keep-alive hook asks on the way out",
        )

        h.engine.pause(AnimationPauseOwner.ROUTE_PLAYBACK)
        h.advance(50)
        assertFalse(h.engine.wantsToRun.value, "nothing is being interrupted while an owner has the board")

        h.engine.resume(AnimationPauseOwner.ROUTE_PLAYBACK)
        h.selectedId.value = "none"
        h.advance(50)
        assertFalse(h.engine.wantsToRun.value, "no effect selected, nothing to keep alive")
    }

    // ── When the effect is switched off ──────────────────────────

    @Test
    fun switchingToNonePaintsTheStaticColoursBack() = runEngineTest(
        frame = fourRoleFrame(),
        selected = "pulse",
    ) { h ->
        h.advance(300)
        assertTrue(h.sink.sent.isNotEmpty())
        h.sink.reset()

        h.selectedId.value = "none"
        h.advance(50)
        assertEquals(
            listOf(fourRoleFrame().staticFrame()),
            h.sink.sent,
            "the wall must not freeze mid-breath",
        )
        assertFalse(h.engine.isRunning.value)
    }

    private companion object {
        /** Mirrors the engine's private cutoff; keep in step with `MAX_ANIMATED_LEDS`. */
        const val MAX_ANIMATED_LEDS = 120
    }
}
