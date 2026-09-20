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
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    fun close() {
        dispatcher.close()
        executor.shutdownNow()
    }
}

fun serialDispatcher(): CoroutineDispatcher = SerialDispatcher().dispatcher
