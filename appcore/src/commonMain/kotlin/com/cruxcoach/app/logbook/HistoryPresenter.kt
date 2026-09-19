package com.cruxcoach.app.logbook

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.app.settings.HistoryRetention
import com.cruxcoach.app.settings.SettingsKeys
import com.cruxcoach.app.settings.readGradeScale
import com.cruxcoach.app.settings.readHistoryRetention
import com.cruxcoach.app.settings.safePut
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.data.repository.PersonalBoardRepository
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Date [days] before [now], same time-of-day, in the `DateTimeUtil.nowIso()`
 * format history rows store, so the lexicographic prune cutoff stays valid.
 */
fun computeHistoryCutoffIso(now: LocalDateTime, days: Int): String =
    LocalDateTime(now.date.minus(days, DateTimeUnit.DAY), now.time).toString()

enum class HistoryError { NONE, LOAD_FAILED, DELETE_FAILED, CLEAR_FAILED, SETTING_NOT_SAVED }

data class HistoryState(
    val entries: List<ClimbHistoryEntry> = emptyList(),
    val retention: HistoryRetention = HistoryRetention.DAYS_30,
    val gradeScale: GradeScale = GradeScale.FRENCH,
    val selectedIds: Set<Long> = emptySet(),
    val error: HistoryError = HistoryError.NONE,
) {
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = entries.isNotEmpty() && selectedIds.size == entries.size
}

/** Projection history ("Verlauf"): sends only, local, pruned to the retention window. */
class HistoryPresenter(
    private val personalBoardRepo: PersonalBoardRepository,
    private val keyValues: KeyValueStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val now: () -> LocalDateTime = { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) },
) {
    private val _state = MutableStateFlow(
        HistoryState(retention = keyValues.readHistoryRetention(), gradeScale = keyValues.readGradeScale())
    )
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        scope.launch {
            try {
                personalBoardRepo.observeClimbHistory().collect { entries ->
                    // A pruned or cleared entry must not stay selected.
                    val liveIds = entries.mapTo(HashSet()) { it.id }
                    _state.update { it.copy(entries = entries, selectedIds = it.selectedIds.intersect(liveIds)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = HistoryError.LOAD_FAILED) }
            }
        }
        pruneToRetention(_state.value.retention)
    }

    fun watch(onState: (HistoryState) -> Unit): StateWatch = scope.watchState(state, onState)

    /** Re-read settings changed elsewhere (grade scale). */
    fun refreshSettings() = _state.update { it.copy(gradeScale = keyValues.readGradeScale()) }

    fun setRetention(retention: HistoryRetention) {
        if (!keyValues.safePut(SettingsKeys.CLIMB_HISTORY_RETENTION_DAYS, retention.days.toString())) {
            _state.update { it.copy(error = HistoryError.SETTING_NOT_SAVED) }
            return
        }
        _state.update { it.copy(retention = retention) }
        pruneToRetention(retention)
    }

    fun clearHistory() = run(HistoryError.CLEAR_FAILED) { personalBoardRepo.clearClimbHistory() }

    fun toggleSelection(id: Long) = _state.update { s ->
        s.copy(selectedIds = if (id in s.selectedIds) s.selectedIds - id else s.selectedIds + id)
    }

    /** Doubles as "deselect all" once everything is selected, like the logbook. */
    fun toggleSelectAll() = _state.update { s ->
        if (s.allSelected) s.copy(selectedIds = emptySet())
        else s.copy(selectedIds = s.entries.mapTo(HashSet()) { it.id })
    }

    fun clearSelection() = _state.update { it.copy(selectedIds = emptySet()) }

    fun deleteSelected() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        run(HistoryError.DELETE_FAILED) {
            personalBoardRepo.deleteClimbHistory(ids)
            _state.update { it.copy(selectedIds = emptySet()) }
        }
    }

    fun consumeError() = _state.update { it.copy(error = HistoryError.NONE) }

    fun close() = scope.cancel()

    private fun pruneToRetention(retention: HistoryRetention) {
        // OFF keeps everything.
        if (retention == HistoryRetention.OFF) return
        run(HistoryError.NONE) { personalBoardRepo.pruneClimbHistory(computeHistoryCutoffIso(now(), retention.days)) }
    }

    private fun run(onFailure: HistoryError, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (onFailure != HistoryError.NONE) _state.update { it.copy(error = onFailure) }
            }
        }
    }
}
