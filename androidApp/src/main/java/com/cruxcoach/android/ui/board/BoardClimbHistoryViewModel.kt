package com.cruxcoach.android.ui.board

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.HistoryRetention
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.data.repository.PersonalBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

data class BoardClimbHistoryState(
    val entries: List<ClimbHistoryEntry> = emptyList(),
    val retention: HistoryRetention = HistoryRetention.DAYS_30,
)

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

    init {
        // History entries (newest-recorded first; the repo flow re-emits on
        // every change, so this also reflects clears + prunes immediately).
        viewModelScope.launch {
            personalBoardRepo.observeClimbHistory().collect { entries ->
                _state.update { it.copy(entries = entries) }
            }
        }
        // Retention setting. On every emission (init + later changes) prune to
        // the cutoff so the on-disk log can't drift past the chosen window;
        // OFF disables retention entirely (keep everything).
        viewModelScope.launch {
            userPreferences.historyRetention.collect { retention ->
                _state.update { it.copy(retention = retention) }
                pruneToRetention(retention)
            }
        }
    }

    /** Persist the chosen retention window; the historyRetention collector
     *  above then runs the prune. */
    fun setRetention(retention: HistoryRetention) {
        viewModelScope.launch {
            try {
                userPreferences.setHistoryRetention(retention)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "setRetention failed value=$retention", e)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                personalBoardRepo.clearClimbHistory()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "clearHistory failed", e)
            }
        }
    }

    private fun pruneToRetention(retention: HistoryRetention) {
        if (retention == HistoryRetention.OFF) return
        viewModelScope.launch {
            try {
                personalBoardRepo.pruneClimbHistory(cutoffIso(retention.days))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pruneToRetention failed days=${retention.days}", e)
            }
        }
    }

    /** "now minus [days] days" as an ISO LocalDateTime string in the exact
     *  same format the history rows store for recorded_at — i.e. the format
     *  produced by DateTimeUtil.nowIso()
     *  (Clock.System.now().toLocalDateTime(currentSystemDefault()).toString()).
     *  Same time-of-day, only the date shifted back, so pruneClimbHistory's
     *  lexicographic cutoff comparison stays valid. */
    private fun cutoffIso(days: Int): String {
        val tz = TimeZone.currentSystemDefault()
        val now = Clock.System.now().toLocalDateTime(tz)
        val cutoffDate = now.date.minus(days, DateTimeUnit.DAY)
        return LocalDateTime(cutoffDate, now.time).toString()
    }
}
