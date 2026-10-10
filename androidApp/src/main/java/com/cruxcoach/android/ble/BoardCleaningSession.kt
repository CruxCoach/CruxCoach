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
    val holdLimit: Int = Int.MAX_VALUE,
    val displayedHoldCount: Int = 0,
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
    private var displayedPositions = emptySet<Int>()
    private var encodeCleaning: ((Set<Int>) -> List<ByteArray>)? = null
    private val mutableState = MutableStateFlow(BoardCleaningState())
    val state = mutableState.asStateFlow()

    @Synchronized fun attach(key: String?, holdLimit: Int = Int.MAX_VALUE) {
        generation++
        target = key
        previous = null
        cleaningDate = null
        displayedPositions = emptySet()
        encodeCleaning = null
        mutableState.value = BoardCleaningState(available = key != null, holdLimit = holdLimit.coerceAtLeast(1))
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
            val batch = day.positions.sorted().take(state.value.holdLimit).toSet()
            val chunks = encode(batch)
            encodeCleaning = encode
            displayedPositions = batch
            cleaningDate = day.date
            // Fence other producers before the first write, including a partial
            // failed send. Stop/retry stays available until restoration succeeds.
            mutableState.value = state.value.copy(active = true, busy = true, displayedHoldCount = batch.size)
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
        var remaining = emptySet<Int>()
        var nextBatch = emptySet<Int>()
        val operation = synchronized(this) {
            if (!state.value.active || state.value.busy) return false
            if (markCleaned) {
                remaining = days[target]?.positions.orEmpty() - displayedPositions
                nextBatch = remaining.sorted().take(state.value.holdLimit).toSet()
            }
            val chunks = if (nextBatch.isNotEmpty()) {
                encodeCleaning?.invoke(nextBatch) ?: return false
            } else previous ?: clear()
            mutableState.value = state.value.copy(busy = true)
            generation to chunks
        }
        try {
            val success = write(operation.second)
            return synchronized(this) {
                if (!success || operation.first != generation) return@synchronized false
                if (markCleaned && days[target]?.date == cleaningDate) {
                    val key = target ?: return@synchronized false
                    if (remaining.isEmpty()) days.remove(key)
                    else days[key] = CleaningDay(cleaningDate!!, remaining)
                    save(days.toMap())
                }
                displayedPositions = nextBatch
                if (nextBatch.isNotEmpty()) {
                    mutableState.value = state.value.copy(
                        holdCount = remaining.size, displayedHoldCount = nextBatch.size,
                    )
                } else {
                    cleaningDate = null
                    mutableState.value = state.value.copy(active = false, displayedHoldCount = 0)
                    refresh()
                }
                true
            }
        } finally {
            synchronized(this) {
                if (operation.first == generation) mutableState.value = state.value.copy(busy = false)
            }
        }
    }
}
