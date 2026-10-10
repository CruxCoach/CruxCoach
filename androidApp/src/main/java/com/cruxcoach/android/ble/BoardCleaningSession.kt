package com.cruxcoach.android.ble

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
internal data class CleaningDay(val date: String, val positions: Set<Int>)

data class BoardCleaningState(
    val available: Boolean = false,
    val holdCount: Int = 0,
    val active: Boolean = false,
    val busy: Boolean = false,
)

/** Daily physical LED positions, independent of catalogue, outcome, angle and
 * account. Only acknowledged climb projections enter the collection. Callers
 * serialize writes; a connection generation fences asynchronous completions. */
internal class BoardCleaningSession(
    initial: Map<String, CleaningDay> = emptyMap(),
    private val save: (Map<String, CleaningDay>) -> Unit = {},
    private val today: () -> String = { LocalDate.now().toString() },
) {
    private val days = initial.toMutableMap()
    private var target: String? = null
    private var generation = 0L
    private var previous: List<ByteArray>? = null
    private var cleaningDate: String? = null
    private val mutableState = MutableStateFlow(BoardCleaningState())
    val state = mutableState.asStateFlow()

    @Synchronized fun attach(key: String?) {
        generation++
        target = key
        previous = null
        cleaningDate = null
        mutableState.value = BoardCleaningState(available = key != null)
        refresh()
    }

    @Synchronized fun ticket(): Long = generation

    @Synchronized fun refresh() {
        if (state.value.active) return // Keep an in-progress cleaning across midnight.
        val date = today()
        mutableState.value = state.value.copy(
            holdCount = days[target]?.takeIf { it.date == date }?.positions?.size ?: 0,
        )
    }

    @Synchronized fun projected(ticket: Long, positions: Set<Int>, chunks: List<ByteArray>) {
        if (ticket != generation || state.value.active) return
        val key = target ?: return
        previous = chunks.map { it.copyOf() }
        if (positions.isNotEmpty()) {
            val date = today()
            days.entries.removeAll { it.value.date != date }
            days[key] = CleaningDay(date, days[key]?.positions.orEmpty() + positions)
            save(days.toMap())
        }
        refresh()
    }

    /** Raw relay fragments are not necessarily a complete, restorable climb. */
    @Synchronized fun forgetProjection(ticket: Long) {
        if (ticket == generation) previous = null
    }

    suspend fun start(
        encode: (Set<Int>) -> List<ByteArray>,
        write: suspend (List<ByteArray>) -> Boolean,
    ): Boolean {
        val operation = synchronized(this) {
            refresh()
            val day = days[target] ?: return false
            if (!state.value.available || state.value.busy || day.positions.isEmpty() ||
                (!state.value.active && day.date != today())) return false
            val chunks = encode(day.positions)
            cleaningDate = day.date
            // Fence other producers before the first write, including a partial
            // failed send. Stop/retry stays available until restoration succeeds.
            mutableState.value = state.value.copy(active = true, busy = true)
            generation to chunks
        }
        try {
            val success = write(operation.second)
            return synchronized(this) { success && operation.first == generation }
        } finally {
            synchronized(this) {
                if (operation.first == generation) mutableState.value = state.value.copy(busy = false)
            }
        }
    }

    suspend fun finish(
        markCleaned: Boolean,
        clear: () -> List<ByteArray>,
        write: suspend (List<ByteArray>) -> Boolean,
    ): Boolean {
        val operation = synchronized(this) {
            if (!state.value.active || state.value.busy) return false
            mutableState.value = state.value.copy(busy = true)
            generation to (previous ?: clear())
        }
        try {
            val success = write(operation.second)
            return synchronized(this) {
                if (!success || operation.first != generation) return@synchronized false
                if (markCleaned && days[target]?.date == cleaningDate) {
                    days.remove(target)
                    save(days.toMap())
                }
                cleaningDate = null
                mutableState.value = state.value.copy(active = false)
                refresh()
                true
            }
        } finally {
            synchronized(this) {
                if (operation.first == generation) mutableState.value = state.value.copy(busy = false)
            }
        }
    }
}
