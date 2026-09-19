package com.cruxcoach.app.logbook

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Handle Swift keeps to stop a `watch { }` subscription. */
class StateWatch internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

internal fun <T> CoroutineScope.watchState(flow: StateFlow<T>, onState: (T) -> Unit): StateWatch =
    StateWatch(launch { flow.collect { onState(it) } })
