package com.cruxcoach.android.spraywall

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The editor's model session: never freed under a running photo, never left open after the screen is gone. */
class HoldModelSessionTest {
    private class FakeRunner : HoldModelSession.Runner {
        val stopped = AtomicBoolean(false)
        val closed = AtomicInteger(0)
        override fun stop() = stopped.set(true)
        override fun close() {
            closed.incrementAndGet()
        }
    }

    /** A photo that runs until it is stopped, like a tile in the runtime; records whether the session was freed under it. */
    private fun runUntilStopped(runner: FakeRunner, started: CompletableDeferred<Unit>, closedDuringRun: AtomicInteger, isCancelled: () -> Boolean): Int {
        started.complete(Unit)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!runner.stopped.get() && System.nanoTime() < deadline) Thread.sleep(5)
        closedDuringRun.set(runner.closed.get())
        if (isCancelled()) throw CancellationException("stopped")
        return 1
    }

    @Test
    fun `leaving mid-run stops the photo and frees the session only after it stopped`() = runBlocking {
        val runner = FakeRunner()
        val session = HoldModelSession { runner }
        val started = CompletableDeferred<Unit>()
        val closedDuringRun = AtomicInteger(-1)
        val job = async(Dispatchers.Default) {
            session.run { r, isCancelled -> runUntilStopped(r, started, closedDuringRun, isCancelled) }
        }
        withTimeout(5_000) { started.await() }

        // What the view model does when the screen goes: close, then its scope is cancelled.
        session.close()
        assertEquals(0, runner.closed.get(), "not freed while the photo runs")
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }

        assertTrue(runner.stopped.get(), "the running inference was told to stop")
        assertEquals(0, closedDuringRun.get(), "the session was still open while the photo ran")
        assertEquals(1, runner.closed.get(), "freed once the run had stopped")
    }

    @Test
    fun `a session that finishes loading after the screen is gone is freed straight away`() = runBlocking {
        val runner = FakeRunner()
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ran = AtomicBoolean(false)
        val session = HoldModelSession {
            loading.countDown()
            release.await(10, TimeUnit.SECONDS)
            runner
        }
        val job = async(Dispatchers.Default) { session.run { _, _ -> ran.set(true) } }
        assertTrue(loading.await(5, TimeUnit.SECONDS))

        session.close()
        job.cancel()
        release.countDown()
        assertFailsWith<CancellationException> { job.await() }

        assertEquals(false, ran.get(), "no photo runs on a session loaded for a screen that is gone")
        assertEquals(1, runner.closed.get())
    }

    @Test
    fun `the session is kept between photos and freed on close`() = runBlocking {
        val loads = AtomicInteger(0)
        val runner = FakeRunner()
        val session = HoldModelSession { loads.incrementAndGet(); runner }

        assertEquals(1, session.run { _, _ -> 1 })
        assertEquals(2, session.run { _, _ -> 2 })
        assertEquals(1, loads.get())
        assertEquals(0, runner.closed.get())

        session.close()
        assertEquals(1, runner.closed.get())
        assertFailsWith<CancellationException> { session.run { _, _ -> 3 } }
        assertEquals(1, runner.closed.get())
    }

    @Test
    fun `a model error is reported as such and the session stays usable`() = runBlocking {
        val runner = FakeRunner()
        val session = HoldModelSession { runner }

        val error = assertFailsWith<IllegalStateException> { session.run<Int> { _, _ -> error("bad output") } }
        assertEquals("bad output", error.message)
        assertEquals(4, session.run { _, _ -> 4 })

        session.close()
        assertEquals(1, runner.closed.get())
    }

    @Test
    fun `a model that cannot load fails the run and leaves nothing to free`() = runBlocking {
        val session = HoldModelSession<FakeRunner> { throw IllegalStateException("no model") }

        assertFailsWith<IllegalStateException> { session.run { _, _ -> 1 } }
        session.close()
    }
}
