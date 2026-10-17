package com.cruxcoach.app.athlete

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The countdown follows the absolute end instant, as Android's BoardSessionManager does. */
class TrainingRestTimerTest {
    private var now = 1_000_000L
    // The ticker is never advanced: the test drives tick() itself, so no second thread reads the clock.
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val timer = TrainingRestTimer(scope, { null }, { now })

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `remaining time is computed from the end instant and rounds up`() {
        timer.startRestTimer(90)
        assertEquals(RestTimerState(isRunning = true, isFinished = false, secondsRemaining = 90, totalSeconds = 90), timer.restTimer.value)
        now += 30_500 // a suspended app comes back 30.5 s later
        timer.tick()
        assertEquals(60, timer.restTimer.value.secondsRemaining)
        now += 59_000 // 0.5 s left
        timer.tick()
        assertEquals(1, timer.restTimer.value.secondsRemaining)
    }

    @Test
    fun `the rest finishes once and can be dismissed`() {
        timer.startRestTimer(10)
        now += 10_000
        timer.tick()
        assertTrue(timer.restTimer.value.isFinished)
        assertFalse(timer.restTimer.value.isRunning)
        assertEquals(0, timer.restTimer.value.secondsRemaining)
        timer.dismissRestTimerFinished()
        assertFalse(timer.restTimer.value.isFinished)
    }

    @Test
    fun `cancel resets but keeps the last total like Android`() {
        timer.startRestTimer(120)
        timer.cancelRestTimer()
        assertEquals(RestTimerState(totalSeconds = 120), timer.restTimer.value)
    }
}
