package com.cruxcoach.app.ble

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The physical LED positions sent to one board on one local day. */
@Serializable
data class CleaningDay(val date: String, val positions: Set<Int>)

data class BoardCleaningState(
    val available: Boolean = false,
    val holdCount: Int = 0,
    val active: Boolean = false,
    val busy: Boolean = false,
    val holdLimit: Int = Int.MAX_VALUE,
    val displayedHoldCount: Int = 0,
)

/**
 * "Clean holds": daily physical LED positions, independent of catalogue,
 * outcome, angle and account. Only acknowledged climb projections enter the
 * collection. Port of Android's `BoardCleaningSession` (0.2.4).
 *
 * Android serializes with `synchronized`; here every call comes from the
 * connection controller's confined dispatcher (Main on iOS), so the blocks
 * between suspension points cannot interleave. A connection generation fences
 * asynchronous completions, as on Android.
 */
class BoardCleaningSession(
    initial: Map<String, CleaningDay> = emptyMap(),
    private val save: (Map<String, CleaningDay>) -> Unit = {},
    /** The local date, `YYYY-MM-DD`. */
    private val today: () -> String,
) {
    private val days = initial.toMutableMap()
    private var target: String? = null
    private var generation = 0L
    private var previous: List<ByteArray>? = null
    private var cleaningDate: String? = null
    private var displayedPositions = emptySet<Int>()
    private var encodeCleaning: ((Set<Int>) -> List<ByteArray>)? = null
    private val mutableState = MutableStateFlow(BoardCleaningState())
    val state: StateFlow<BoardCleaningState> = mutableState.asStateFlow()

    fun attach(key: String?, holdLimit: Int = Int.MAX_VALUE) {
        generation++
        target = key
        previous = null
        cleaningDate = null
        displayedPositions = emptySet()
        encodeCleaning = null
        mutableState.value = BoardCleaningState(available = key != null, holdLimit = holdLimit.coerceAtLeast(1))
        refresh()
    }

    fun ticket(): Long = generation

    fun refresh() {
        if (state.value.active) return // Keep an in-progress cleaning across midnight.
        val date = today()
        mutableState.value = state.value.copy(
            holdCount = target?.let { days[it] }?.takeIf { it.date == date }?.positions?.size ?: 0,
        )
    }

    fun projected(ticket: Long, positions: Set<Int>, chunks: List<ByteArray>) {
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
    fun forgetProjection(ticket: Long) {
        if (ticket == generation) previous = null
    }

    suspend fun start(
        encode: (Set<Int>) -> List<ByteArray>,
        write: suspend (List<ByteArray>) -> Boolean,
    ): Boolean {
        refresh()
        val day = target?.let { days[it] } ?: return false
        val current = state.value
        if (!current.available || current.busy || day.positions.isEmpty() ||
            (!current.active && day.date != today())
        ) return false
        val batch = day.positions.sorted().take(current.holdLimit).toSet()
        val chunks = encode(batch)
        encodeCleaning = encode
        displayedPositions = batch
        cleaningDate = day.date
        // Fence other producers before the first write, including a partial
        // failed send. Stop/retry stays available until restoration succeeds.
        mutableState.value = current.copy(active = true, busy = true, displayedHoldCount = batch.size)
        val operation = generation
        try {
            val success = write(chunks)
            return success && operation == generation
        } finally {
            if (operation == generation) mutableState.value = state.value.copy(busy = false)
        }
    }

    suspend fun finish(
        markCleaned: Boolean,
        clear: () -> List<ByteArray>,
        write: suspend (List<ByteArray>) -> Boolean,
    ): Boolean {
        var remaining = emptySet<Int>()
        var nextBatch = emptySet<Int>()
        if (!state.value.active || state.value.busy) return false
        if (markCleaned) {
            remaining = target?.let { days[it] }?.positions.orEmpty() - displayedPositions
            nextBatch = remaining.sorted().take(state.value.holdLimit).toSet()
        }
        val chunks = if (nextBatch.isNotEmpty()) {
            encodeCleaning?.invoke(nextBatch) ?: return false
        } else previous ?: clear()
        mutableState.value = state.value.copy(busy = true)
        val operation = generation
        try {
            val success = write(chunks)
            if (!success || operation != generation) return false
            val date = cleaningDate
            if (markCleaned && date != null && target?.let { days[it] }?.date == date) {
                val key = target ?: return false
                if (remaining.isEmpty()) days.remove(key) else days[key] = CleaningDay(date, remaining)
                save(days.toMap())
            }
            displayedPositions = nextBatch
            if (nextBatch.isNotEmpty()) {
                mutableState.value = state.value.copy(holdCount = remaining.size, displayedHoldCount = nextBatch.size)
            } else {
                cleaningDate = null
                mutableState.value = state.value.copy(active = false, displayedHoldCount = 0)
                refresh()
            }
            return true
        } finally {
            if (operation == generation) mutableState.value = state.value.copy(busy = false)
        }
    }
}

/**
 * Small installation-local day cache; no climb ids, logbook or account data.
 * Port of Android's `BoardCleaningStorage` over the platform preferences.
 */
class BoardCleaningStorage(private val keyValues: KeyValueStore) {
    private val serializer = MapSerializer(String.serializer(), CleaningDay.serializer())

    fun load(): Map<String, CleaningDay> = try {
        keyValues.getString(KEY)?.let { Json.decodeFromString(serializer, it) }.orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }

    fun save(days: Map<String, CleaningDay>) = keyValues.putString(KEY, Json.encodeToString(serializer, days))

    companion object {
        const val KEY = "board_cleaning.days"

        /**
         * One collection per physical board. Android keys it by the hashed
         * controller MAC; iOS never sees a MAC, so the CoreBluetooth peripheral
         * identifier stands in. That identifier is a per-installation UUID
         * (already kept in plain by [RememberedBoardStore]), so it is not
         * hashed here; it also means a reinstall or another iPhone starts a
         * new collection for the same wall.
         */
        fun key(board: DiscoveredBoard): String? {
            // A relay address names a phone, which may switch physical boards.
            if (board.isCruxRelay) return null
            if (!board.boardBrand.usesAuroraProtocol && board.boardBrand != BoardBrand.MOONBOARD) return null
            if (board.identifier.isBlank()) return null
            return "${board.boardBrand.wireValue}|${board.identifier.uppercase()}|${board.apiLevel}"
        }
    }
}
