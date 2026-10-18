package com.cruxcoach.app.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Port of Android `RoutePlaybackController` behaviour, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlaybackPresenterTest {

    private class Harness(scope: TestScope, var prefs: RoutePlaybackPrefs = RoutePlaybackPrefs(countdown = false)) {
        val frames = mutableListOf<Int>()
        val savedLoops = mutableListOf<Boolean>()
        val presenter = RoutePlaybackPresenter(
            scope = scope.backgroundScope,
            prefs = { prefs },
            saveAutoLoop = { savedLoops += it },
            onFrameChanged = { frames += it },
            pause = { delay(it) },
        )
    }

    @Test
    fun `a single frame climb is no route and ignores every control`() = runTest {
        val h = Harness(this)
        h.presenter.load("k1|40", 1, 0)
        assertFalse(h.presenter.state.value.isRoute)
        h.presenter.nextFrame(); h.presenter.startPlayback(); h.presenter.goToFrame(0)
        assertFalse(h.presenter.state.value.isPlaying)
        assertTrue(h.frames.isEmpty())
    }

    @Test
    fun `manual stepping stops at the ends unless looping`() = runTest {
        val h = Harness(this)
        h.presenter.load("r|40", 3, 0)
        h.presenter.previousFrame() // at the first frame, not looping: nothing
        h.presenter.nextFrame(); h.presenter.nextFrame(); h.presenter.nextFrame()
        assertEquals(listOf(1, 2), h.frames)
        assertEquals(2, h.presenter.state.value.currentFrameIndex)
        h.presenter.toggleLoop()
        assertEquals(listOf(true), h.savedLoops, "the loop choice is Android's persisted route_auto_loop")
        h.presenter.nextFrame()
        assertEquals(0, h.presenter.state.value.currentFrameIndex)
        h.presenter.previousFrame()
        assertEquals(2, h.presenter.state.value.currentFrameIndex)
        h.presenter.goToFrame(7) // out of range: ignored
        assertEquals(listOf(1, 2, 0, 2), h.frames)
    }

    @Test
    fun `playback counts down then advances one frame per speed and ends on the last`() = runTest {
        val h = Harness(this, RoutePlaybackPrefs(frameSpeedSec = 2f, countdown = true, countdownSeconds = 3))
        h.presenter.load("r|40", 3, 0)
        h.presenter.startPlayback()
        runCurrent()
        assertEquals(3, h.presenter.state.value.countdownSeconds)
        assertTrue(h.presenter.state.value.auto)
        advanceTimeBy(2_001)
        assertEquals(1, h.presenter.state.value.countdownSeconds)
        advanceTimeBy(1_000)
        assertEquals(0, h.presenter.state.value.countdownSeconds)
        assertTrue(h.frames.isEmpty(), "no frame changes during the countdown")
        advanceTimeBy(2_000)
        assertEquals(listOf(1), h.frames)
        advanceTimeBy(2_000)
        assertEquals(listOf(1, 2), h.frames)
        advanceTimeBy(2_000)
        assertFalse(h.presenter.state.value.isPlaying, "without loop the last frame ends playback")
        assertFalse(h.presenter.state.value.auto)
        assertEquals(listOf(1, 2), h.frames)
    }

    @Test
    fun `looping playback wraps to the first frame until stopped`() = runTest {
        val h = Harness(this, RoutePlaybackPrefs(frameSpeedSec = 1f, countdown = false, autoLoop = true))
        h.presenter.load("r|40", 2, 0)
        assertTrue(h.presenter.state.value.isLooping, "auto loop comes from the settings")
        h.presenter.startPlayback()
        advanceTimeBy(3_500)
        assertEquals(listOf(1, 0, 1), h.frames)
        h.presenter.stopPlayback()
        advanceTimeBy(5_000)
        assertEquals(listOf(1, 0, 1), h.frames)
        assertFalse(h.presenter.state.value.isPlaying)
    }

    @Test
    fun `the setter pace wins when asked for and a new climb stops playback on its first frame`() = runTest {
        val h = Harness(this, RoutePlaybackPrefs(frameSpeedSec = 5f, useSetterSpeed = true, countdown = false))
        h.presenter.load("r1|40", 3, 1_500)
        assertEquals(1.5f, h.presenter.state.value.speedSec)
        h.presenter.startPlayback()
        advanceTimeBy(1_600)
        assertEquals(listOf(1), h.frames)
        assertEquals(1, h.presenter.state.value.currentFrameIndex)

        assertTrue(h.presenter.load("r1|25", 4, 0), "another angle is another variant")
        assertFalse(h.presenter.state.value.isPlaying)
        assertEquals(0, h.presenter.state.value.currentFrameIndex)
        assertEquals(4, h.presenter.state.value.totalFrames)
        assertEquals(1.5f, h.presenter.state.value.speedSec, "without a pace the current speed carries over")
        assertFalse(h.presenter.load("r1|25", 4, 0), "the same variant again changes nothing")

        h.prefs = h.prefs.copy(useSetterSpeed = false)
        h.presenter.load("r2|40", 3, 900)
        assertEquals(1.5f, h.presenter.state.value.speedSec, "the setter pace is ignored when switched off")
        advanceUntilIdle()
    }

    @Test
    fun `a speed change while playing restarts with the new speed`() = runTest {
        val h = Harness(this, RoutePlaybackPrefs(frameSpeedSec = 5f, countdown = false))
        h.presenter.load("r|40", 5, 0)
        h.presenter.startPlayback()
        advanceTimeBy(4_000)
        h.presenter.updateSpeed(1f)
        assertTrue(h.presenter.state.value.isPlaying)
        advanceTimeBy(1_100)
        assertEquals(listOf(1), h.frames, "the new speed applies from the restart, not the old 5 s")
        h.presenter.stopPlayback()
    }

    @Test
    fun `the facade gates the transport as Android's controls do`() = runTest {
        val h = Harness(this)
        val model = RoutePlaybackModel(h.presenter, backgroundScope)
        h.presenter.load("r|40", 3, 0)
        var ui = model.currentState
        assertTrue(ui.isRoute)
        assertEquals(1, ui.frameNumber)
        assertFalse(ui.canFirst); assertFalse(ui.canPrevious); assertTrue(ui.canNext); assertTrue(ui.canLast)
        assertTrue(ui.showDots)

        h.presenter.setCanReachBoard(false)
        ui = model.currentState
        assertFalse(ui.canTogglePlay, "play needs a board no playlist owns")
        h.presenter.setCanReachBoard(true)
        model.togglePlay()
        ui = model.currentState
        assertTrue(ui.isPlaying)
        assertFalse(ui.canNext, "manual stepping is disabled while playing")
        h.presenter.setCanReachBoard(false)
        assertTrue(model.currentState.canTogglePlay, "stop is always allowed")
        model.togglePlay()
        assertFalse(model.currentState.isPlaying)

        model.setSpeed(40.0)
        assertEquals(15.0, model.currentState.speedSeconds, "bounded to Android's 1–15 s slider")
        model.setSpeed(0.2)
        assertEquals(1.0, model.currentState.speedSeconds)
        model.lastFrame()
        assertEquals(3, model.currentState.frameNumber)
    }
}
