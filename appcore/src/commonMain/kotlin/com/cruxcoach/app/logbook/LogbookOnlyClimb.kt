package com.cruxcoach.app.logbook

import com.cruxcoach.app.ui.Subscription
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.PersonalBoardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One logged row of a climb the catalogue does not have (Android `LogbookOnlyAscentRow`). */
class LogbookOnlyEntryUi(
    val uuid: String,
    val isSend: Boolean,
    val angle: Int,
    /** yyyy-MM-dd; Swift formats it for the locale, as Android's `formatDate`. */
    val date: String,
    val isMirror: Boolean,
    /** The row's own attempt count, as Android shows it (`error_climb_not_in_db_log_attempts`). */
    val attempts: Int,
    /** "" without a comment. */
    val comment: String,
)

class LogbookOnlyClimbState(
    /** loading | ready | empty | failed */
    val status: String,
    /** "" when no row carries a name: Swift shows `error_climb_not_in_db_unnamed`. */
    val climbName: String,
    /** Newest first. */
    val entries: List<LogbookOnlyEntryUi>,
)

/**
 * Android `LogbookOnlyClimbContent`: a climb the user logged (e.g. a Kilter
 * ascent of a climb the curated catalogue never mirrored) opens on what the
 * logbook rows themselves carry instead of dead-ending on "not found". The
 * uuid/angle diagnostic stays out of the UI, as on Android.
 */
class LogbookOnlyClimbModel(
    private val repository: PersonalBoardRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val state = MutableStateFlow(LogbookOnlyClimbState("loading", "", emptyList()))
    private var loadJob: Job? = null

    val currentState: LogbookOnlyClimbState get() = state.value

    fun watch(onState: (LogbookOnlyClimbState) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    fun open(climbUuid: String) {
        loadJob?.cancel()
        state.value = LogbookOnlyClimbState("loading", "", emptyList())
        if (climbUuid.isBlank()) {
            state.value = LogbookOnlyClimbState("empty", "", emptyList())
            return
        }
        loadJob = scope.launch {
            state.value = try {
                map(withContext(io) { repository.getUserHistoryForClimb(climbUuid) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogbookOnlyClimbState("failed", "", emptyList())
            }
        }
    }

    fun close() = scope.cancel()

    companion object {
        /** Pure mapping, shared with the tests. */
        fun map(rows: List<AscentWithClimb>): LogbookOnlyClimbState {
            if (rows.isEmpty()) return LogbookOnlyClimbState("empty", "", emptyList())
            // Logbook imports for unknown climbs often leave the name blank;
            // any row that carries one names the climb.
            val name = rows.firstNotNullOfOrNull { row -> row.climbName.takeIf { it.isNotBlank() } } ?: ""
            val entries = rows.sortedByDescending { it.climbedAt }.map { row ->
                LogbookOnlyEntryUi(
                    uuid = row.uuid,
                    isSend = row.isSend,
                    angle = row.angle.toInt(),
                    date = row.climbedAt.take(10),
                    isMirror = row.isMirror,
                    attempts = row.bidCount.toInt(),
                    comment = row.comment?.takeIf { it.isNotBlank() } ?: "",
                )
            }
            return LogbookOnlyClimbState("ready", name, entries)
        }
    }
}
