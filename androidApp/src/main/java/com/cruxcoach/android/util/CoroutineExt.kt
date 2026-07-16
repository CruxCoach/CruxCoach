package com.cruxcoach.android.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Like [CoroutineScope.launch], but a non-cancellation failure in [block] is
 * logged and swallowed instead of propagating to the scope's default handler.
 *
 * `viewModelScope` (and the app's SupervisorJob scopes) have no
 * [kotlinx.coroutines.CoroutineExceptionHandler], so an uncaught throw in an
 * `init`/background coroutine reaches the global handler and crashes the
 * process. For startup work that should degrade gracefully — e.g. a corrupt
 * DataStore/SQLite read on ViewModel init should leave sensible defaults, not
 * kill the app — use [safeLaunch].
 *
 * Start long-lived child collectors with their own [safeLaunch]. A nested
 * plain `launch` can fail only after this wrapper's block has returned, when
 * this `try` is no longer on the child's exception path.
 *
 * [CancellationException] is re-thrown so structured cancellation still works;
 * `Error`s (e.g. OOM) are intentionally NOT caught and continue to propagate.
 */
fun CoroutineScope.safeLaunch(
    tag: String,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "safeLaunch: coroutine failed — degraded gracefully", e)
    }
}
