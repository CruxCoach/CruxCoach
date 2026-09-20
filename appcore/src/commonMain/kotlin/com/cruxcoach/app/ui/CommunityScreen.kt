package com.cruxcoach.app.ui

import com.cruxcoach.app.community.CommunitySubscriber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CommunityScreenState(
    val busy: Boolean,
    val finished: Boolean,
    val received: Int,
    val imported: Int,
    val tombstoned: Int,
    val skipped: Int,
    /** Seconds since the epoch of the newest event taken in, or 0. */
    val cursor: Long,
    /** none | failed */
    val failure: String,
)

/**
 * Fetching community problems others published.
 *
 * Android holds a long-lived subscription; iOS cannot keep one alive in the
 * background, so this is an explicit pull. Every event is verified before it is
 * used, and the cursor makes a second pull cheap.
 */
class CommunityScreenModel(
    private val subscriber: CommunitySubscriber,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private var job: Job? = null

    private val _state = MutableStateFlow(
        CommunityScreenState(false, false, 0, 0, 0, 0, subscriber.since() ?: 0L, "none")
    )
    val state = _state.asStateFlow()

    val currentState: CommunityScreenState get() = _state.value

    fun watch(onState: (CommunityScreenState) -> Unit): Subscription {
        val collector = scope.launch { _state.collect { onState(it) } }
        return Subscription { collector.cancel() }
    }

    fun fetch() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = _state.value.copy(busy = true, finished = false, failure = "none")
            val result = try {
                withContext(io) { subscriber.fetch() }
            } catch (e: Exception) {
                null
            }
            _state.value = if (result == null) {
                _state.value.copy(busy = false, finished = true, failure = "failed")
            } else {
                CommunityScreenState(
                    busy = false,
                    finished = true,
                    received = result.received,
                    imported = result.imported,
                    tombstoned = result.tombstoned,
                    skipped = result.skipped,
                    cursor = result.cursor,
                    failure = "none",
                )
            }
        }
    }

    fun close() = scope.cancel()
}
