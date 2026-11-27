package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.UserPreferences
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The board ownership route playback lends to the LED animation: claimed once
 * per run, handed back exactly once, and never claimed by a run that could
 * never start. A claim whose `finally` can never run would leave the animation
 * paused for the whole process.
 *
 * Only claims (`true`) are asserted directly. Releases also come from the
 * unconditional hand-back inside [RoutePlaybackController.stopPlayback] on a
 * cold stop — harmless, the engine clamps releases it has no claim for — so
 * these tests count claims and watch the tail of the sequence instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlaybackControllerTest {

    @Test
    fun aPlaybackThatCanNeverStartNeverClaimsTheBoard() = runTest {
        val deadScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        deadScope.cancel()
        val claims = mutableListOf<Boolean>()
        val (controller, _) = routeController(deadScope, claims)

        controller.startPlayback()
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()

        assertFalse(claims.any { it }, "a launch into a cancelled scope runs no block, so it may own nothing")
    }

    @Test
    fun aRouteEndsByItselfAndHandsTheBoardBack() = runTest {
        val claims = mutableListOf<Boolean>()
        val (controller, _) = routeController(backgroundScope, claims)

        controller.startPlayback()
        testScheduler.runCurrent()
        assertEquals(1, claims.count { it }, "the run claims the board once, before its first tick")
        assertTrue(claims.last(), "and still holds it while frames are running")

        testScheduler.advanceTimeBy(3_500L)
        testScheduler.runCurrent()
        assertEquals(
            1,
            claims.count { it },
            "the non-looping route ran once and claimed nothing more",
        )
        assertFalse(claims.last(), "the last frame ends the run and its claim together")
    }

    @Test
    fun restartingForANewSpeedClaimsAgainWithoutTheOldRunReleasingEarly() = runTest {
        val claims = mutableListOf<Boolean>()
        val (controller, _) = routeController(backgroundScope, claims)

        controller.startPlayback()
        testScheduler.runCurrent()
        assertEquals(1, claims.count { it })

        // [updateSpeed] restarts: the cancelled run's finally is dispatched, and
        // must find its claim already superseded by the new run's.
        controller.updateSpeed(2f)
        testScheduler.runCurrent()
        assertEquals(2, claims.count { it }, "the restart claims for the new run")
        assertTrue(
            claims.last(),
            "the board is still owned — the old run's finally must not release through the new claim",
        )

        testScheduler.advanceTimeBy(6_500L)
        testScheduler.runCurrent()
        assertEquals(2, claims.count { it })
        assertFalse(claims.last(), "and exactly one release closes the surviving run")
    }

    private fun routeController(
        scope: CoroutineScope,
        claims: MutableList<Boolean>,
        speedSec: Float = 1f,
    ): Pair<RoutePlaybackController, MutableStateFlow<ClimbDetailState>> {
        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.routeCountdown } returns flowOf(false)
        val state = MutableStateFlow(
            ClimbDetailState(
                playback = PlaybackState(
                    allFrames = List(3) { emptyList() },
                    totalFrames = 3,
                    isRoute = true,
                    speedSec = speedSec,
                ),
            ),
        )
        return RoutePlaybackController(
            scope = scope,
            state = state,
            userPreferences = prefs,
            onFrameChanged = {},
            onBoardOwnershipChanged = { claims += it },
        ) to state
    }
}
