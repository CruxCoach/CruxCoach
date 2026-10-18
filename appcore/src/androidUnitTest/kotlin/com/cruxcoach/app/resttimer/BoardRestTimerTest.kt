package com.cruxcoach.app.resttimer

import com.cruxcoach.app.athlete.RestTimerState
import com.cruxcoach.app.athlete.TrainingRestTimer
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.LogAttemptPresenter
import com.cruxcoach.app.logbook.LogTarget
import com.cruxcoach.app.logbook.NoSessionRecorder
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.logbook.newPersonalRepo
import com.cruxcoach.app.ui.RestTimerModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android's detail rest timer: start dialog bounds, banner rules, auto-start after a log. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardRestTimerTest {
    private var now = 5_000_000L
    // The ticker is never advanced past one step: tests drive tick() themselves.
    private val timerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val timer = TrainingRestTimer(timerScope, { null }, { now })

    @AfterTest
    fun tearDown() = timerScope.cancel()

    @Test
    fun `countdown prints minutes from sixty seconds and plain seconds below as on Android`() {
        assertEquals("0s", RestTimerRules.countdown(0))
        assertEquals("45s", RestTimerRules.countdown(45))
        assertEquals("59s", RestTimerRules.countdown(59))
        assertEquals("1:00", RestTimerRules.countdown(60))
        assertEquals("1:05", RestTimerRules.countdown(65))
        assertEquals("3:00", RestTimerRules.countdown(180))
        assertEquals("60:00", RestTimerRules.countdown(3600))
        assertEquals("0s", RestTimerRules.countdown(-3), "a negative remainder never prints a minus")
    }

    @Test
    fun `banner tone follows the remaining seconds and turns green when finished`() {
        fun tone(seconds: Int, finished: Boolean = false) =
            RestTimerRules.tone(RestTimerState(isRunning = !finished, isFinished = finished, secondsRemaining = seconds, totalSeconds = 180))
        assertEquals("normal", tone(31))
        assertEquals("warning", tone(30))
        assertEquals("warning", tone(11))
        assertEquals("critical", tone(10))
        assertEquals("critical", tone(1))
        assertEquals("finished", tone(0, finished = true))
        assertEquals(0.5f, RestTimerRules.progress(RestTimerState(true, false, 90, 180)))
        assertEquals(0f, RestTimerRules.progress(RestTimerState(totalSeconds = 0)))
    }

    @Test
    fun `start dialog stays within five seconds and one hour and disables steps that would leave it`() {
        assertEquals(5, RestTimerRules.clamp(0))
        assertEquals(3600, RestTimerRules.clamp(5000))
        assertEquals(180, RestTimerRules.clamp(180))
        // 0:30 − 1 min would be below 5 s: disabled, as Android's DurationStepper.
        assertFalse(RestTimerRules.canStep(30, -60))
        assertTrue(RestTimerRules.canStep(65, -60))
        assertEquals(5, RestTimerRules.step(65, -60))
        assertFalse(RestTimerRules.canStep(5, -1))
        assertTrue(RestTimerRules.canStep(3599, 1))
        assertEquals(3600, RestTimerRules.step(3599, 1))
        assertFalse(RestTimerRules.canStep(3600, 1))
        assertFalse(RestTimerRules.canStep(3550, 60))
    }

    @Test
    fun `model maps the shared timer and its close button cancels or dismisses`() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        var defaultSeconds = 2
        val model = RestTimerModel(timer, { defaultSeconds }, main)
        assertEquals("idle", model.currentState.phase)
        assertEquals(5, model.currentState.defaultSeconds, "the settings length is clamped like the dialog")
        defaultSeconds = 240
        assertEquals(240, model.currentState.defaultSeconds)

        val seen = mutableListOf<String>()
        val watch = model.watch { seen += it.phase }
        runCurrent()
        model.start(2) // below the dialog's minimum
        runCurrent()
        var s = model.currentState
        assertEquals("running", s.phase)
        assertEquals(5, s.totalSeconds)
        assertEquals("5s", s.countdown)
        assertEquals("critical", s.tone)
        assertEquals(1f, s.progress)

        model.closeBanner(finished = false)
        runCurrent()
        assertEquals("idle", model.currentState.phase)
        assertEquals(5, model.currentState.totalSeconds, "cancel keeps the last total like Android")

        model.start(90)
        runCurrent()
        now += 90_000
        model.refresh()
        runCurrent()
        s = model.currentState
        assertEquals("finished", s.phase)
        assertEquals("finished", s.tone)
        assertEquals(0, s.secondsRemaining)
        model.closeBanner(finished = true)
        runCurrent()
        assertEquals("idle", model.currentState.phase)
        assertEquals(listOf("idle", "running", "idle", "running", "finished", "idle"), seen)
        watch.cancel()
        model.detach()
    }

    @Test
    fun `auto start runs only when the setting is on and uses the settings length`() {
        var enabled = false
        val auto = RestAutoStart(timer, { enabled }, { 150 })
        auto.onQuickLogSaved(isSend = false)
        auto.onAscentSaved(isSend = true)
        assertFalse(timer.restTimer.value.isRunning)

        enabled = true
        auto.onAscentSaved(isSend = true)
        assertEquals(RestTimerState(isRunning = true, isFinished = false, secondsRemaining = 150, totalSeconds = 150), timer.restTimer.value)
        now += 100_000
        timer.tick()
        assertEquals(50, timer.restTimer.value.secondsRemaining)
        // The undo window of a quick log closing restarts the rest, as on Android.
        auto.onQuickLogSaved(isSend = false)
        assertEquals(150, timer.restTimer.value.secondsRemaining)
        auto.onClimbStatusChanged("c1")
        assertTrue(timer.restTimer.value.isRunning)
    }

    @Test
    fun `a quick log through the detail logger starts the rest`() = runBlocking {
        val serial = SerialDispatcher().dispatcher
        val loggerScope = CoroutineScope(SupervisorJob() + serial)
        val logger = LogAttemptPresenter(
            newPersonalRepo(), NoSessionRecorder, RestAutoStart(timer, { true }, { 120 }), loggerScope, serial,
        )
        try {
            withContext(serial) {
                logger.setTarget(
                    LogTarget(
                        climbUuid = "climb-rest", climbName = "Rest", frames = "p1100r12", framesCount = 1L,
                        difficultyAverage = 17.0, boardBrand = "kilter", layoutId = 1L, angle = 40, isMirrored = false,
                    )
                )
            }
            withContext(serial) { logger.quickLog(isSend = true) }
            withTimeout(CI_WAIT_MS) {
                while (!withContext(serial) { timer.restTimer.value.isRunning }) delay(10)
            }
            assertEquals(120, withContext(serial) { timer.restTimer.value.totalSeconds })
        } finally {
            loggerScope.cancel()
        }
    }
}
