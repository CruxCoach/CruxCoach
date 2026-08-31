package com.cruxcoach.android.ui.board

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.HistoryRetention
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.ProgressHistoryIssue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import javax.inject.Inject
import kotlin.time.Clock

data class BoardClimbHistoryState(
    val entries: List<ClimbHistoryEntry> = emptyList(),
    val retention: HistoryRetention = HistoryRetention.DAYS_30,
    /** The user's preferred grade scale, so the history renders grades the
     *  same way the rest of the app does (not hard-coded to V-scale). */
    val gradeScale: GradeScale = GradeScale.FRENCH,
    /** Ids of entries the user has ticked for single/multi-select delete.
     *  Empty = no selection (cards just navigate on tap). */
    val selectedIds: Set<Long> = emptySet(),
    val isLoading: Boolean = true,
    val issue: ProgressHistoryIssue? = null,
) {
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = entries.isNotEmpty() && selectedIds.size == entries.size
}

@HiltViewModel
class BoardClimbHistoryViewModel @Inject constructor(
    private val personalBoardRepo: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(BoardClimbHistoryState())
    val state: StateFlow<BoardClimbHistoryState> = _state.asStateFlow()

    companion object {
        private const val TAG = "BoardHistoryVM"
    }

    private var historyJob: Job? = null
    private sealed interface FailedAction {
        data class RetentionWrite(val retention: HistoryRetention) : FailedAction
        data class RetentionPrune(val retention: HistoryRetention) : FailedAction
        data class Delete(val ids: List<Long>) : FailedAction
        data object Clear : FailedAction
    }
    private var failedAction: FailedAction? = null

    init {
        // History entries (newest-recorded first; the repo flow re-emits on
        // every change, so this also reflects clears + prunes immediately).
        observeHistory()
        // Retention setting. On every emission (init + later changes) prune to
        // the cutoff so the on-disk log can't drift past the chosen window;
        // OFF disables retention entirely (keep everything).
        viewModelScope.safeLaunch(TAG) {
            userPreferences.historyRetention.collect { retention ->
                _state.update { it.copy(retention = retention) }
                pruneToRetention(retention)
            }
        }
        // Grade scale — mirror the user's app-wide preference for grade display.
        viewModelScope.safeLaunch(TAG) {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
            }
        }
    }

    private fun observeHistory() {
        historyJob?.cancel()
        _state.update { it.copy(isLoading = it.entries.isEmpty(), issue = null) }
        historyJob = viewModelScope.launch {
            try {
                personalBoardRepo.observeClimbHistory().collect { entries ->
                    // Drop any selected ids that no longer exist (pruned/cleared
                    // elsewhere) so the selection can't go stale.
                    val liveIds = entries.mapTo(HashSet()) { it.id }
                    _state.update {
                        it.copy(
                            entries = entries,
                            selectedIds = it.selectedIds.intersect(liveIds),
                            isLoading = false,
                            issue = it.issue.takeUnless { issue ->
                                issue == ProgressHistoryIssue.LOAD_FAILED
                            },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "observeClimbHistory failed", e)
                _state.update {
                    it.copy(isLoading = false, issue = ProgressHistoryIssue.LOAD_FAILED)
                }
            }
        }
    }

    fun retryCurrentIssue() {
        when (val action = failedAction) {
            is FailedAction.RetentionWrite -> persistRetention(action.retention)
            is FailedAction.RetentionPrune -> pruneToRetention(action.retention)
            is FailedAction.Delete -> deleteHistory(action.ids)
            FailedAction.Clear -> clearHistory()
            null -> observeHistory()
        }
    }

    /** Persist the chosen retention window; the historyRetention collector
     *  above then runs the prune. */
    fun setRetention(retention: HistoryRetention) = persistRetention(retention)

    private fun persistRetention(retention: HistoryRetention) {
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(issue = null) }
            try {
                userPreferences.setHistoryRetention(retention)
                failedAction = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "setRetention failed value=$retention", e)
                failedAction = FailedAction.RetentionWrite(retention)
                _state.update { it.copy(issue = ProgressHistoryIssue.RETENTION_UPDATE_FAILED) }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(issue = null) }
            try {
                personalBoardRepo.clearClimbHistory()
                failedAction = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "clearHistory failed", e)
                failedAction = FailedAction.Clear
                _state.update { it.copy(issue = ProgressHistoryIssue.DELETE_FAILED) }
            }
        }
    }

    /** Tick/untick a single entry for deletion. */
    fun toggleSelection(id: Long) {
        _state.update { s ->
            val next = if (id in s.selectedIds) s.selectedIds - id else s.selectedIds + id
            s.copy(selectedIds = next)
        }
    }

    /** Select all entries, or clear the selection if everything is already
     *  selected (the icon doubles as a toggle, like the logbook). */
    fun toggleSelectAll() {
        _state.update { s ->
            if (s.allSelected) s.copy(selectedIds = emptySet())
            else s.copy(selectedIds = s.entries.mapTo(HashSet()) { it.id })
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    /** Delete just the ticked entries (single tap = one id, multi-select =
     *  many), then clear the selection. */
    fun deleteSelected() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        deleteHistory(ids)
    }

    private fun deleteHistory(ids: List<Long>) {
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(issue = null) }
            try {
                personalBoardRepo.deleteClimbHistory(ids)
                failedAction = null
                _state.update { it.copy(selectedIds = emptySet()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "deleteSelected failed count=${ids.size}", e)
                failedAction = FailedAction.Delete(ids)
                _state.update { it.copy(issue = ProgressHistoryIssue.DELETE_FAILED) }
            }
        }
    }

    private fun pruneToRetention(retention: HistoryRetention) {
        if (retention == HistoryRetention.OFF) return
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(issue = null) }
            try {
                personalBoardRepo.pruneClimbHistory(cutoffIso(retention.days))
                failedAction = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pruneToRetention failed days=${retention.days}", e)
                failedAction = FailedAction.RetentionPrune(retention)
                _state.update { it.copy(issue = ProgressHistoryIssue.RETENTION_UPDATE_FAILED) }
            }
        }
    }

    /** "now minus [days] days" as an ISO LocalDateTime string in the exact
     *  same format the history rows store for recorded_at — i.e. the format
     *  produced by DateTimeUtil.nowIso()
     *  (Clock.System.now().toLocalDateTime(currentSystemDefault()).toString()).
     *  Same time-of-day, only the date shifted back, so pruneClimbHistory's
     *  lexicographic cutoff comparison stays valid. */
    private fun cutoffIso(days: Int): String =
        computeHistoryCutoffIso(
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
            days,
        )
}

/** Date [days] before [now], same time-of-day, as a LocalDateTime ISO string —
 *  the lexicographic cutoff pruneClimbHistory compares against. Extracted with
 *  `now` injected so the calendar arithmetic (leap days, month lengths) is
 *  unit-testable without the system clock. */
internal fun computeHistoryCutoffIso(now: LocalDateTime, days: Int): String =
    LocalDateTime(now.date.minus(days, DateTimeUnit.DAY), now.time).toString()
