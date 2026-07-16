package com.cruxcoach.android.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.WorkoutRepository
import com.cruxcoach.domain.model.BodyStatTimeRange
import com.cruxcoach.domain.model.StatRegistry
import com.cruxcoach.domain.model.TrendEntry
import com.cruxcoach.util.GradeConverter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import javax.inject.Inject

data class GradePyramidEntry(
    val grade: String,
    val count: Long,
    val numeric: Int
)

data class RpeTrendEntry(
    val date: String,
    val rpe: Double
)

data class StatsState(
    val isLoading: Boolean = true,

    // Grade pyramid
    val gradePyramid: List<GradePyramidEntry> = emptyList(),
    val totalClimbs: Long = 0,
    val totalSends: Long = 0,

    // RPE trend
    val rpeTrend: List<RpeTrendEntry> = emptyList(),

    // Style distribution
    val styleDistribution: Map<String, Long> = emptyMap(),

    // Body stat trends
    val bodyStatTimeRange: BodyStatTimeRange = BodyStatTimeRange.THREE_MONTHS,
    val bodyStatTrends: Map<String, List<TrendEntry>> = emptyMap(),
    val selectedBodyStat: String = "weight",
    val compareBodyStat: String? = null,
    val compareEnabled: Boolean = false,

    // Summary stats
    val highestGrade: String? = null,
    val avgRpe: Double? = null,
    val totalWorkouts: Long = 0,
    val flashRate: Map<String, Double> = emptyMap(),
    val gradeScale: GradeScale = GradeScale.V_SCALE,

    val error: String? = null
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val climbRepository: ClimbRepository,
    private val workoutRepository: WorkoutRepository,
    private val bodyStatRepository: BodyStatRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
            }
        }
        loadStats()
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    fun onBodyStatTimeRangeChanged(range: BodyStatTimeRange) {
        _state.update { it.copy(bodyStatTimeRange = range) }
        loadBodyStatTrends()
    }

    fun onBodyStatSelected(statName: String) {
        _state.update { it.copy(selectedBodyStat = statName) }
    }

    fun onCompareToggled(enabled: Boolean) {
        _state.update { it.copy(compareEnabled = enabled, compareBodyStat = if (!enabled) null else it.compareBodyStat) }
    }

    fun onCompareStatSelected(statName: String) {
        _state.update { it.copy(compareBodyStat = statName) }
    }

    fun loadStats() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                withContext(Dispatchers.IO) {
                    // Grade pyramid
                    val pyramid = climbRepository.getGradePyramid()
                    val pyramidEntries = pyramid.map { (grade, count) ->
                        GradePyramidEntry(
                            grade = grade,
                            count = count,
                            numeric = GradeConverter.vScaleToNumeric(grade)
                        )
                    }.sortedBy { it.numeric }

                    val totalSends = pyramid.values.sum()

                    // RPE trend
                    val recentWorkouts = workoutRepository.getRecentWorkouts(14)
                    val rpeTrend = recentWorkouts
                        .filter { it.perceivedRpe != null }
                        .map { RpeTrendEntry(date = it.date, rpe = it.perceivedRpe!!) }

                    // Style distribution
                    val styles = climbRepository.getStyleDistribution()

                    // Summary
                    val highest = climbRepository.getRecentHighestGrade()
                    val avgRpe = workoutRepository.getAvgRpeLastN(14)
                    val totalWorkouts = workoutRepository.countThisWeek()
                    val flashRate = climbRepository.getFlashRate()

                    // Body stat trends
                    val trends = loadBodyStatTrendsSync()

                    // Select first stat with data if current selection has none
                    val currentSelected = _state.value.selectedBodyStat
                    val selectedBodyStat = if (trends.containsKey(currentSelected)) {
                        currentSelected
                    } else {
                        trends.keys.firstOrNull() ?: "weight"
                    }

                    _state.update { it.copy(
                        isLoading = false,
                        gradePyramid = pyramidEntries,
                        totalClimbs = pyramidEntries.sumOf { entry -> entry.count },
                        totalSends = totalSends,
                        rpeTrend = rpeTrend,
                        styleDistribution = styles,
                        bodyStatTrends = trends,
                        selectedBodyStat = selectedBodyStat,
                        highestGrade = highest,
                        avgRpe = avgRpe,
                        totalWorkouts = totalWorkouts,
                        flashRate = flashRate
                    ) }
                }
            } catch (e: Exception) {
                android.util.Log.w("StatsViewModel", "loadStats failed (${e.javaClass.simpleName})")
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun loadBodyStatTrends() {
        viewModelScope.launch {
            try {
                val trends = withContext(Dispatchers.IO) { loadBodyStatTrendsSync() }
                _state.update { it.copy(bodyStatTrends = trends) }
            } catch (e: Exception) {
                android.util.Log.e("StatsViewModel", "Failed to load body stat trends", e)
            }
        }
    }

    private fun loadBodyStatTrendsSync(): Map<String, List<TrendEntry>> {
        val range = _state.value.bodyStatTimeRange
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val endDate = today.toString()

        val trends = mutableMapOf<String, List<TrendEntry>>()
        for (def in StatRegistry.ALL) {
            val months = range.months
            val entries = if (months != null) {
                val startDate = today.minus(DatePeriod(months = months)).toString()
                bodyStatRepository.getByStatNameForDateRange(def.key, startDate, endDate)
            } else {
                bodyStatRepository.getByStatName(def.key)
            }
            if (entries.isNotEmpty()) {
                trends[def.key] = entries.map { TrendEntry(date = it.date, value = it.value) }
            }
        }
        return trends
    }
}
