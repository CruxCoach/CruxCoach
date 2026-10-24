package com.cruxcoach.android.ble

import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** What "Cleaned" confirmed on one controller on [date]: of the entries logged
 * up to [until] (local time, ISO), the holds in [cleaned] are clean. Entries
 * logged later count in full. LED positions only, no logbook data. */
@Serializable
internal data class CleaningMark(val date: String, val until: String, val cleaned: Set<Int>)

/** The board positions of one of today's logbook entries, as a send of its
 * climb to the connected board would light them. */
data class LoggedHolds(val loggedAt: LocalDateTime, val positions: Set<Int>)

data class BoardCleaningState(
    val available: Boolean = false,
    val holdCount: Int = 0,
    val active: Boolean = false,
    val busy: Boolean = false,
    val holdLimit: Int = Int.MAX_VALUE,
    val displayedHoldCount: Int = 0,
    /** Today's logbook has not been read for this connection yet. */
    val loading: Boolean = false,
    /** Holds were logged today, and all of them are marked cleaned. */
    val allCleaned: Boolean = false,
)

/** Holds of the climbs logged today on this phone (sends and attempts) that
 * fit the connected board, minus what was marked cleaned since. Callers
 * serialize writes; a connection generation fences asynchronous completions,
 * including logbook reads started for a previous connection. */
internal class BoardCleaningSession(
    initial: Map<String, CleaningMark> = emptyMap(),
    private val save: (Map<String, CleaningMark>) -> Unit = {},
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    /** One cleaning round: the collection when it started, the holds of every
     * entry known then, the sections confirmed clean so far and the section
     * on the board. */
    private class Round(
        val date: String,
        val until: LocalDateTime,
        val holds: Set<Int>,
        val known: Set<Int>,
    ) {
        var confirmed = emptySet<Int>()
        var displayed = emptySet<Int>()
        val remaining get() = holds - confirmed
    }

    private val marks = initial.toMutableMap()
    private var target: String? = null
    private var generation = 0L
    private var previous: List<ByteArray>? = null
    private var logDate: String? = null
    private var logged = emptyList<LoggedHolds>()
    private var round: Round? = null
    private var encodeCleaning: ((Set<Int>) -> List<ByteArray>)? = null
    private val mutableState = MutableStateFlow(BoardCleaningState())
    val state = mutableState.asStateFlow()

    private fun today() = now().toLocalDate().toString()

    @Synchronized fun attach(key: String?, holdLimit: Int = Int.MAX_VALUE) {
        generation++
        target = key
        previous = null
        logDate = null
        logged = emptyList()
        round = null
        encodeCleaning = null
        mutableState.value = BoardCleaningState(
            available = key != null, holdLimit = holdLimit.coerceAtLeast(1), loading = key != null,
        )
    }

    @Synchronized fun ticket(): Long = generation

    /** Today's entries for the board attached at [ticket]; a read that
     * finishes after a reconnect or board switch is dropped. */
    @Synchronized fun logged(ticket: Long, date: String, entries: List<LoggedHolds>) {
        if (ticket != generation || target == null) return
        logDate = date
        logged = entries
        mutableState.value = state.value.copy(loading = false)
        refresh()
    }

    @Synchronized fun refresh() {
        if (state.value.active) return // Keep an in-progress cleaning across midnight.
        val holds = collection()
        mutableState.value = state.value.copy(
            holdCount = holds.size,
            allCleaned = holds.isEmpty() && logDate == today() && logged.isNotEmpty(),
        )
    }

    private fun collection(): Set<Int> {
        val date = today()
        if (logDate != date) return emptySet()
        val mark = target?.let(marks::get)?.takeIf { it.date == date }
        // An unreadable mark hides nothing: better a hold too many than one missed.
        val until = mark?.let { runCatching { LocalDateTime.parse(it.until) }.getOrNull() }
        val holds = mutableSetOf<Int>()
        for (entry in logged) {
            holds += if (mark != null && until != null && !entry.loggedAt.isAfter(until)) {
                entry.positions - mark.cleaned
            } else entry.positions
        }
        return holds
    }

    /** Remembers the last acknowledged display so finishing can restore it. */
    @Synchronized fun projected(ticket: Long, chunks: List<ByteArray>) {
        if (ticket != generation || state.value.active) return
        previous = chunks.map { it.copyOf() }
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
            val current = state.value
            if (!current.available || current.busy || target == null) return false
            val active = round ?: run {
                val holds = collection()
                if (holds.isEmpty()) return false
                // A clock that ran ahead must not leave a shown entry "later".
                val until = maxOf(now(), logged.maxOf { it.loggedAt })
                Round(today(), until, holds, logged.flatMapTo(mutableSetOf()) { it.positions })
            }
            val remaining = active.remaining
            if (remaining.isEmpty()) return false
            val batch = remaining.sorted().take(current.holdLimit).toSet()
            val chunks = encode(batch)
            encodeCleaning = encode
            round = active
            active.displayed = batch
            // Fence other producers before the first write, including a partial
            // failed send. Stop/retry stays available until restoration succeeds.
            mutableState.value = current.copy(
                active = true, busy = true, holdCount = remaining.size, displayedHoldCount = batch.size,
            )
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
            val active = round
            if (active == null || !state.value.active || state.value.busy) return false
            val confirmed = if (markCleaned) active.confirmed + active.displayed else active.confirmed
            val nextBatch = if (markCleaned) {
                (active.holds - confirmed).sorted().take(state.value.holdLimit).toSet()
            } else emptySet()
            val chunks = if (nextBatch.isNotEmpty()) {
                encodeCleaning?.invoke(nextBatch) ?: return false
            } else previous ?: clear()
            mutableState.value = state.value.copy(busy = true)
            Finish(generation, chunks, active, confirmed, nextBatch)
        }
        try {
            val success = write(operation.chunks)
            return synchronized(this) {
                if (!success || operation.ticket != generation) return@synchronized false
                val active = operation.round
                if (markCleaned) {
                    active.confirmed = operation.confirmed
                    saveMark(active)
                }
                active.displayed = operation.nextBatch
                if (operation.nextBatch.isNotEmpty()) {
                    mutableState.value = state.value.copy(
                        holdCount = active.remaining.size, displayedHoldCount = operation.nextBatch.size,
                    )
                } else {
                    round = null
                    mutableState.value = state.value.copy(active = false, displayedHoldCount = 0)
                    refresh()
                }
                true
            }
        } finally {
            synchronized(this) {
                if (operation.ticket == generation) mutableState.value = state.value.copy(busy = false)
            }
        }
    }

    private class Finish(
        val ticket: Long,
        val chunks: List<ByteArray>,
        val round: Round,
        val confirmed: Set<Int>,
        val nextBatch: Set<Int>,
    )

    /** Holds of the entries known when the round started count as clean unless
     * still pending, and so does every confirmed section. Entries that arrive
     * later (an import) keep their holds. A round begun before midnight leaves
     * today's collection alone. */
    private fun saveMark(active: Round) {
        val key = target ?: return
        val date = today()
        if (active.date != date) return
        val cleaned = (active.known - active.remaining) + active.confirmed
        marks.entries.removeAll { it.value.date != date }
        marks[key] = CleaningMark(date, active.until.toString(), cleaned)
        save(marks.toMap())
    }
}
