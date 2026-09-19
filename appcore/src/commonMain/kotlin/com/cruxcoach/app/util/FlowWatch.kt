package com.cruxcoach.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Handle Swift keeps for as long as it wants updates; [cancel] is idempotent. */
interface Cancellable {
    fun cancel()
}

/**
 * How SwiftUI observes a presenter: Swift cannot collect a `Flow`, so it hands
 * over a closure and keeps the returned [Cancellable].
 *
 * Collection runs on `Dispatchers.Main.immediate`, so [onEach] is always
 * invoked on the main thread and may touch `@Published` state directly. The
 * current value is delivered first. An exception thrown by [onEach] would
 * terminate the process on Kotlin/Native, so it is swallowed per value.
 */
class Watcher<T>(private val flow: StateFlow<T>) {
    fun watch(onEach: (T) -> Unit): Cancellable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            flow.collect { value ->
                try {
                    onEach(value)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Keep observing: one failing render must not end the subscription.
                }
            }
        }
        return object : Cancellable {
            override fun cancel() {
                scope.cancel()
            }
        }
    }
}

fun <T> StateFlow<T>.watch(onEach: (T) -> Unit): Cancellable = Watcher(this).watch(onEach)
