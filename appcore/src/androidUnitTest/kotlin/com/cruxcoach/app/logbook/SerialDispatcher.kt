package com.cruxcoach.app.logbook

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * One thread for a presenter's scope and its IO.
 *
 * On the shared Default pool a state update and the database write behind it
 * run concurrently, so a test that awaits state and then reads the repository
 * fails at random. Serialising keeps the code under test genuinely
 * asynchronous while making the order observable.
 */
class SerialDispatcher {
    private val scopeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** For a presenter's own scope. */
    val dispatcher: ExecutorCoroutineDispatcher = scopeExecutor.asCoroutineDispatcher()

    /**
     * For a presenter's IO. Deliberately a second thread: one thread for both
     * deadlocks as soon as a coroutine on it waits for another that also needs it.
     */
    val io: ExecutorCoroutineDispatcher = ioExecutor.asCoroutineDispatcher()

    fun close() {
        dispatcher.close()
        io.close()
        scopeExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }
}

fun serialDispatcher(): CoroutineDispatcher = SerialDispatcher().dispatcher
