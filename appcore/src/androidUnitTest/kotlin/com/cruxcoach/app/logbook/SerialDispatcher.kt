package com.cruxcoach.app.logbook

import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * One thread for a presenter's scope and its IO.
 *
 * Two threads on one SQLDelight JDBC driver corrupt its single connection and
 * single current transaction, which is what made these tests flaky.
 *
 * The thread is a daemon and is never shut down on purpose: closing it while a
 * presenter still has work queued throws `RejectedExecutionException` into that
 * presenter's scope, and the uncaught exception then fails whichever *other*
 * test happens to run next. The JVM reaps the thread at exit.
 */
class SerialDispatcher {
    val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "appcore-test-serial").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /** Kept for call sites; deliberately does nothing (see the class comment). */
    fun close() = Unit
}
