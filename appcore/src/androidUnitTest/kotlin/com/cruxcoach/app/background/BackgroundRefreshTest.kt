package com.cruxcoach.app.background

import com.cruxcoach.app.platform.KeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MemoryKeyValues : KeyValueStore {
    val map = LinkedHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun keys(): Set<String> = map.keys.toSet()
}

/** A step with a fixed interval and back-off that records each run into [trace]. */
private class RecordingStep(
    override val code: String,
    private val trace: MutableList<String>,
    private val interval: Long = 1_000,
    private val backoff: Long = 100,
    var gate: Boolean = true,
    var ok: Boolean = true,
    var notified: Int = 0,
    /** When set, the run waits for it (a request that takes long). */
    var hold: CompletableDeferred<Unit>? = null,
) : BackgroundStep {
    override fun intervalMs(): Long = interval
    override fun retryAfterMs(): Long = backoff
    override suspend fun enabled(trigger: String): Boolean = gate
    override suspend fun run(): BackgroundStepResult {
        trace.add(code)
        hold?.await()
        return BackgroundStepResult(ok, notified)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundRefreshTest {
    private val keyValues = MemoryKeyValues()
    private var now = 1_000_000L

    @Test
    fun `steps run in their order and a due step runs once per interval`() = runTest {
        val trace = mutableListOf<String>()
        val a = RecordingStep("a", trace)
        val b = RecordingStep("b", trace, interval = 5_000)
        val refresh = BackgroundRefresh(listOf(a, b), keyValues, { now }, this, StandardTestDispatcher(testScheduler))

        val first = refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(listOf("a", "b"), trace)
        assertEquals(listOf("a", "b"), first.ran)
        assertTrue(first.success)

        // Too early for both: nothing runs, both are reported as skipped.
        now += 500
        val early = refresh.runSteps(BackgroundRefresh.TRIGGER_FOREGROUND)
        assertEquals(listOf("a", "b"), trace)
        assertEquals(listOf("a", "b"), early.skipped)

        // A's interval has passed, B's has not.
        now += 600
        refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(listOf("a", "b", "a"), trace)
    }

    @Test
    fun `a failed step waits for its back-off and not for the full interval`() = runTest {
        val trace = mutableListOf<String>()
        val step = RecordingStep("s", trace, interval = 10_000, backoff = 300, ok = false)
        val refresh = BackgroundRefresh(listOf(step), keyValues, { now }, this, StandardTestDispatcher(testScheduler))

        val failed = refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(listOf("s"), failed.failed)
        assertFalse(failed.success)

        now += 200
        refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(1, trace.size, "inside the back-off")

        now += 200
        step.ok = true
        val retried = refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(2, trace.size)
        assertEquals(listOf("s"), retried.ran)

        // Now a good run: the interval applies again, not the back-off.
        now += 400
        refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(2, trace.size)
    }

    @Test
    fun `a closed gate skips the step without recording a run`() = runTest {
        val trace = mutableListOf<String>()
        val step = RecordingStep("s", trace, gate = false)
        val refresh = BackgroundRefresh(listOf(step), keyValues, { now }, this, StandardTestDispatcher(testScheduler))

        val summary = refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(listOf("s"), summary.skipped)
        assertTrue(trace.isEmpty())
        assertNull(keyValues.getString(BackgroundRefresh.tryKey("s")))
        assertNull(keyValues.getString(BackgroundRefresh.okKey("s")))

        // Opened, it runs at once: a skipped refresh does not start an interval.
        step.gate = true
        refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(listOf("s"), trace)
    }

    @Test
    fun `a clock set back counts as due`() = runTest {
        val trace = mutableListOf<String>()
        val step = RecordingStep("s", trace, interval = 10_000)
        val refresh = BackgroundRefresh(listOf(step), keyValues, { now }, this, StandardTestDispatcher(testScheduler))
        refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        now -= 60_000
        refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(2, trace.size)
    }

    @Test
    fun `notified counts are summed over the steps`() = runTest {
        val trace = mutableListOf<String>()
        val refresh = BackgroundRefresh(
            listOf(RecordingStep("a", trace, notified = 2), RecordingStep("b", trace, notified = 1)),
            keyValues, { now }, this, StandardTestDispatcher(testScheduler),
        )
        assertEquals(3, refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND).notified)
    }

    @Test
    fun `cancel answers the caller at once and stops before the next step`() = runTest {
        val trace = mutableListOf<String>()
        val slow = RecordingStep("slow", trace, hold = CompletableDeferred())
        val after = RecordingStep("after", trace)
        val refresh = BackgroundRefresh(listOf(slow, after), keyValues, { now }, this, StandardTestDispatcher(testScheduler))
        val answers = mutableListOf<BackgroundRefreshSummary>()

        refresh.run(BackgroundRefresh.TRIGGER_BACKGROUND) { answers.add(it) }
        advanceUntilIdle()
        assertEquals(listOf("slow"), trace)
        assertTrue(refresh.isRunning)
        assertTrue(answers.isEmpty())

        refresh.cancel()
        assertEquals(1, answers.size, "answered inside cancel, as an expiration handler needs")
        assertTrue(answers[0].cancelled)
        assertFalse(answers[0].success)
        assertEquals(BackgroundRefresh.TRIGGER_BACKGROUND, answers[0].trigger)

        advanceUntilIdle()
        assertEquals(listOf("slow"), trace, "the next step never starts")
        assertEquals(1, answers.size, "answered exactly once")
        assertFalse(refresh.isRunning)
        // The cut-off step has a try but no good run: it is retried after its back-off.
        assertNull(keyValues.getString(BackgroundRefresh.okKey("slow")))
    }

    @Test
    fun `a second caller joins the run under way`() = runTest {
        val trace = mutableListOf<String>()
        val hold = CompletableDeferred<Unit>()
        val step = RecordingStep("s", trace, hold = hold)
        val refresh = BackgroundRefresh(listOf(step), keyValues, { now }, this, StandardTestDispatcher(testScheduler))
        val answers = mutableListOf<String>()

        refresh.run(BackgroundRefresh.TRIGGER_FOREGROUND) { answers.add("first:${it.ran}") }
        advanceUntilIdle()
        refresh.run(BackgroundRefresh.TRIGGER_BACKGROUND) { answers.add("second:${it.ran}") }
        hold.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("s"), trace, "one run for both")
        assertEquals(listOf("first:[s]", "second:[s]"), answers)
        assertFalse(refresh.isRunning)

        // A later call starts a new run (nothing is due, so it only skips).
        refresh.run(BackgroundRefresh.TRIGGER_BACKGROUND) { answers.add("third:${it.skipped}") }
        advanceUntilIdle()
        assertEquals("third:[s]", answers.last())
    }

    @Test
    fun `a step that throws is a failed step and the rest still run`() = runTest {
        val trace = mutableListOf<String>()
        val broken = object : BackgroundStep {
            override val code = "broken"
            override fun intervalMs() = 0L
            override fun retryAfterMs() = 0L
            override suspend fun enabled(trigger: String) = true
            override suspend fun run(): BackgroundStepResult = error("boom")
        }
        val refresh = BackgroundRefresh(
            listOf(broken, RecordingStep("next", trace)), keyValues, { now }, this, StandardTestDispatcher(testScheduler),
        )
        val summary = refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(listOf("broken"), summary.failed)
        assertEquals(listOf("next"), summary.ran)
    }
}
