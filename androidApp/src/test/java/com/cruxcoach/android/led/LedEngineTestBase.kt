package com.cruxcoach.android.led

import com.cruxcoach.android.ble.FrameSendResult
import com.cruxcoach.android.ble.LitFrame
import kotlin.test.AfterTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Shared scaffolding for the engine suites: an engine wired to a fake sink on a
 * virtual clock, with the test's conditions in place before wiring so an
 * unconfined dispatcher cannot race a pause, and a guaranteed shutdown so a
 * failing assertion reports itself instead of timing the scheduler out.
 *
 * The clock is the test scheduler's virtual time, so pacing and the time limit
 * are assertable to the millisecond; a send takes no time unless the fake sink
 * is told to ([FakeLedFrameSink.writeMillis]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class LedEngineTestBase {

    private var running: LedAnimationEngine? = null

    @AfterTest
    fun tearDown() {
        running?.shutdown()
        running = null
    }

    protected inner class Harness(
        private val scope: TestScope,
        val sink: FakeLedFrameSink,
        val selectedId: MutableStateFlow<String>,
        /** Everything but the effect id, which [selectedId] overrides. */
        val settings: MutableStateFlow<LedEffectSettings>,
        val engine: LedAnimationEngine,
    ) {
        /** Move virtual time on and let everything it scheduled run. */
        fun advance(millis: Long) {
            scope.advanceTimeBy(millis)
            scope.runCurrent()
        }
    }

    protected fun runEngineTest(
        frame: LitFrame?,
        selected: String,
        foreground: Boolean = true,
        canAnimate: Boolean = true,
        sendResult: FrameSendResult = FrameSendResult.SENT,
        ownedBy: Set<AnimationPauseOwner> = emptySet(),
        settings: LedEffectSettings = LedEffectSettings(),
        block: suspend TestScope.(Harness) -> Unit,
    ) = runTest {
        // The conditions a test wants already in place are set before the engine
        // is wired up, because an unconfined dispatcher runs the first frame the
        // moment the inputs line up — a pause applied afterwards would race it.
        val sink = FakeLedFrameSink(frame, canAnimate)
        sink.result = sendResult
        val selectedId = MutableStateFlow(selected)
        val extra = MutableStateFlow(settings)
        val engine = LedAnimationEngine(
            sink,
            combine(selectedId, extra) { id, rest -> rest.copy(effectId = id) },
            UnconfinedTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )
        running = engine
        try {
            ownedBy.forEach { engine.pause(it) }
            // `start()` is deliberately not used: it registers a process lifecycle
            // observer, which a JVM test has no way to drive.
            engine.observeSelection()
            engine.setForeground(foreground)
            block(Harness(this, sink, selectedId, extra, engine))
        } finally {
            // Without this the loop delays forever and the scheduler never goes
            // idle, so a failing assertion would be reported as a timeout.
            engine.shutdown()
        }
    }
}
