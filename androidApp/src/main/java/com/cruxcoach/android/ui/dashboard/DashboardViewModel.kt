package com.cruxcoach.android.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.data.repository.WorkoutRepository
import com.cruxcoach.domain.model.*
import com.cruxcoach.util.DateTimeUtil
import com.cruxcoach.android.util.PerfLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    val hasProfile: Boolean = false,
    val userName: String = "",

    // Plan info
    val activePlan: TrainingPlan? = null,
    val currentPhase: TrainingPhase? = null,
    val sessionsThisWeek: Int = 0,
    val totalSessionsPlanned: Int = 0,

    // Streak
    val trainingStreak: Int = 0,

    // Next session
    val nextSession: PlannedSession? = null,
    val nextSessionDay: String = "",

    // Latest adaptation
    val latestAdaptation: Adaptation? = null,

    // Quick stats
    val totalClimbsToday: Int = 0,
    val sendsToday: Int = 0,
    val avgRpeLast7: Double? = null,
    val highestGrade: String? = null,
    val gradeScale: GradeScale = GradeScale.V_SCALE,

    val error: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val planRepository: PlanRepository,
    private val workoutRepository: WorkoutRepository,
    private val climbRepository: ClimbRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        PerfLogger.log("📊 DashboardViewModel.init START")
        viewModelScope.launch {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
            }
        }
        loadDashboard()
    }

    fun loadDashboard() {
        PerfLogger.log("📊 DashboardViewModel.loadDashboard() START")
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                withContext(Dispatchers.IO) {
                    val profile = userRepository.getActiveProfile()
                    if (profile == null) {
                        _state.update { it.copy(
                            isLoading = false,
                            hasProfile = false
                        ) }
                        return@withContext
                    }

                    val plan = planRepository.getActivePlan(profile.id)
                    val sessions = plan?.let { planRepository.getSessionsForPlan(it.id) } ?: emptyList()

                    // Week session count
                    val workoutsThisWeek = workoutRepository.countThisWeek().toInt()
                    val totalPlanned = sessions.size

                    // Training streak
                    val streak = calculateStreak()

                    // Next session
                    val todayDow = DateTimeUtil.dayOfWeek(DateTimeUtil.todayIso())
                    val nextSession = sessions
                        .filter { it.dayOfWeek > todayDow }
                        .minByOrNull { it.dayOfWeek }
                        ?: sessions.minByOrNull { it.dayOfWeek } // wrap to next week

                    val nextDay = nextSession?.let {
                        DayOfWeek.of(it.dayOfWeek).getDisplayName(TextStyle.FULL, Locale.getDefault())
                    } ?: ""

                    // Today's climbing
                    val today = DateTimeUtil.todayIso()
                    val todayClimbs = climbRepository.getSendsForDateRange(today, today)

                    // RPE average
                    val avgRpe = workoutRepository.getAvgRpeLastN(7)

                    // Highest grade
                    val highest = climbRepository.getRecentHighestGrade()

                    _state.update { it.copy(
                        isLoading = false,
                        hasProfile = true,
                        userName = profile.name,
                        activePlan = plan,
                        currentPhase = plan?.phase,
                        sessionsThisWeek = workoutsThisWeek,
                        totalSessionsPlanned = totalPlanned,
                        trainingStreak = streak,
                        nextSession = nextSession,
                        nextSessionDay = nextDay,
                        totalClimbsToday = todayClimbs.size,
                        sendsToday = todayClimbs.count { it.sent },
                        avgRpeLast7 = avgRpe,
                        highestGrade = highest
                    ) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun calculateStreak(): Int {
        val recentLogs = workoutRepository.getRecentWorkouts(30)
        if (recentLogs.isEmpty()) return 0

        val today = DateTimeUtil.todayIso()
        val logDates = recentLogs.map { it.date }.toSortedSet().reversed()

        var streak = 0
        var expectedDate = today

        for (date in logDates) {
            if (date == expectedDate) {
                streak++
                expectedDate = DateTimeUtil.addDays(expectedDate, -1)
            } else if (date < expectedDate) {
                // Allow gap of 1 day (rest days count)
                val nextExpected = DateTimeUtil.addDays(expectedDate, -1)
                if (date == nextExpected) {
                    streak++
                    expectedDate = DateTimeUtil.addDays(nextExpected, -1)
                } else {
                    break
                }
            }
        }
        return streak
    }
}
