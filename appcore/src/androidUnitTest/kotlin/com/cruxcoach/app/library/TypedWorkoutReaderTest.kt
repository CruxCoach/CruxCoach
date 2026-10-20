package com.cruxcoach.app.library

import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Android's `WorkoutTextSheet` reads the typed text off the main thread, the first text at once and later ones
 * `READ_DELAY_MS` (120 ms) after the last keystroke, a newer text cancelling the read of an older one, and "Add"
 * takes exactly what is in the field. Virtual time on a test dispatcher; the real catalogue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypedWorkoutReaderTest {

    private val catalog = ExerciseCatalog.parse(AthleteTestKit.catalogJson)

    /** The reader's dispatcher, counting the parses that run on it (and only those). */
    private class ReaderDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var running = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            inner.dispatch(context, Runnable {
                running = true
                try { block.run() } finally { running = false }
            })
        }
    }

    private class Setup(test: TestScope, catalog: ExerciseCatalog) {
        val main = StandardTestDispatcher(test.testScheduler, "main")
        val readerDispatcher = ReaderDispatcher(StandardTestDispatcher(test.testScheduler, "reader"))
        val scope = CoroutineScope(SupervisorJob() + main)
        val reads = mutableListOf<String?>()
        var parsesOffReader = 0
        var parses = 0
        val reader = TypedWorkoutReader(scope, readerDispatcher, {
            parses++
            if (!readerDispatcher.running) parsesOffReader++
            catalog
        }) { reads += it.text }
    }

    @Test
    fun `the first text is read at once and later ones a moment after the last keystroke`() = runTest {
        val t = Setup(this, catalog)
        t.reader.read("Klimmzüge 5x5")
        runCurrent()
        assertEquals("Klimmzüge 5x5", t.reader.state.value.text)
        assertEquals(listOf("pull.pull_up"), t.reader.state.value.result.lines.map { it.item.slug })
        // Read on the reader's dispatcher, never on the scope's (the main thread in the app).
        assertEquals(1, t.parses)
        assertEquals(0, t.parsesOffReader)

        t.reader.read("Klimmzüge 5x5, Liegestütze 3x15")
        advanceTimeBy(TypedWorkoutReader.READ_DELAY_MS - 1)
        runCurrent()
        assertEquals("Klimmzüge 5x5", t.reader.state.value.text, "not read before the delay")
        advanceTimeBy(1)
        runCurrent()
        assertEquals("Klimmzüge 5x5, Liegestütze 3x15", t.reader.state.value.text)
        assertEquals(listOf("pull.pull_up", "push.push_up"), t.reader.state.value.result.lines.map { it.item.slug })
        assertEquals(0, t.parsesOffReader)
        t.scope.cancel()
    }

    @Test
    fun `a newer text cancels the read of an older one`() = runTest {
        val t = Setup(this, catalog)
        t.reader.read("Klimmzüge 5x5")
        runCurrent()
        t.reader.read("Klimmzüge 5x5, Dips")
        advanceTimeBy(100)
        t.reader.read("Klimmzüge 5x5, Dips 3x8")
        // 200 ms after the second text: its read would have run at 120 ms; the third text cancelled it.
        advanceTimeBy(100)
        runCurrent()
        assertEquals("Klimmzüge 5x5", t.reader.state.value.text)
        advanceTimeBy(20)
        runCurrent()
        assertEquals(listOf<String?>("Klimmzüge 5x5", "Klimmzüge 5x5, Dips 3x8"), t.reads)
        assertEquals(2, t.parses, "the cancelled text was never parsed")
        assertEquals(listOf("pull.pull_up", "push.dip"), t.reader.state.value.result.lines.map { it.item.slug })
        assertEquals(listOf(5, 3), t.reader.state.value.result.lines.map { it.item.sets })
        t.scope.cancel()
    }

    @Test
    fun `an emptied field is read at once`() = runTest {
        val t = Setup(this, catalog)
        t.reader.read("Plank 3x60s")
        runCurrent()
        t.reader.read("")
        runCurrent()
        assertEquals("", t.reader.state.value.text)
        assertTrue(t.reader.state.value.result.lines.isEmpty())
        t.scope.cancel()
    }

    @Test
    fun `Add takes what is in the field even before it is read`() = runTest {
        val t = Setup(this, catalog)
        t.reader.read("Klimmzüge 5x5")
        runCurrent()
        val read = t.reader.state.value.result
        // The read of the field's text is taken as it is, not read again.
        assertSame(read, t.reader.resultFor("Klimmzüge 5x5"))
        assertEquals(1, t.parses)
        // Typed a moment ago: the field holds more than the last read, so "Add" reads it now.
        t.reader.read("Klimmzüge 5x5\nDips 3x8")
        val now = t.reader.resultFor("Klimmzüge 5x5\nDips 3x8")
        assertEquals(listOf("pull.pull_up", "push.dip"), now.lines.map { it.item.slug })
        assertEquals("Klimmzüge 5x5", t.reader.state.value.text, "the debounced read is still due")
        t.scope.cancel()
    }

    @Test
    fun `a closed sheet drops a due read and reads the next text at once`() = runTest {
        val t = Setup(this, catalog)
        t.reader.read("Klimmzüge 5x5")
        runCurrent()
        t.reader.read("Klimmzüge 5x5, Dips 3x8")
        t.reader.reset()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(t.reader.state.value.text)
        assertTrue(t.reader.state.value.result.lines.isEmpty())
        t.reader.read("Plank 3x60s")
        runCurrent()
        assertEquals("Plank 3x60s", t.reader.state.value.text)
        assertEquals(listOf<String?>("Klimmzüge 5x5", "Plank 3x60s"), t.reads)
        t.scope.cancel()
    }
}
