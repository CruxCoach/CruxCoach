package com.cruxcoach.android.ui.plan

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.domain.model.*
import com.cruxcoach.domain.usecase.AdaptPlanUseCase
import com.cruxcoach.domain.usecase.GeneratePlanUseCase
import com.cruxcoach.util.DateTimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PlanUiState(
    val isLoading: Boolean = true,
    val plan: TrainingPlan? = null,
    val sessions: List<PlannedSession> = emptyList(),
    val adaptations: List<Adaptation> = emptyList(),
    val weekDays: List<WeekDayInfo> = emptyList(),
    val error: String? = null
)

data class WeekDayInfo(
    val dayOfWeek: Int,
    val dayName: String,
    val date: String,
    val session: PlannedSession? = null,
    val isToday: Boolean = false
)

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val userRepository: UserRepository,
    private val generatePlanUseCase: GeneratePlanUseCase,
    private val adaptPlanUseCase: AdaptPlanUseCase,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    init {
        loadPlan()
    }

    fun loadPlan() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    val profile = userRepository.getActiveProfile()
                    if (profile == null) {
                        _state.update { it.copy(isLoading = false, error = context.getString(R.string.error_no_profile)) }
                        return@withContext
                    }

                    val plan = planRepository.getActivePlan(profile.id)
                    if (plan != null) {
                        val sessions = planRepository.getSessionsForPlan(plan.id)
                        _state.update { it.copy(
                            isLoading = false,
                            plan = plan,
                            sessions = sessions,
                            weekDays = buildWeekDays(plan, sessions)
                        ) }
                    } else {
                        _state.update { it.copy(
                            isLoading = false,
                            error = context.getString(R.string.error_no_active_plan)
                        ) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PlanViewModel", "loadPlan failed (${e.javaClass.simpleName})")
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun generateNewPlan() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    val profile = userRepository.getActiveProfile()
                        ?: throw IllegalStateException(context.getString(R.string.error_no_profile))
                    val assessment = userRepository.getLatestAssessment(profile.id)
                        ?: Assessment(userId = profile.id, date = DateTimeUtil.nowIso())

                    generatePlanUseCase.execute(
                        userProfile = profile,
                        assessment = assessment
                    )

                    // Reload after generation
                }
                loadPlan()
            } catch (e: Exception) {
                android.util.Log.w("PlanViewModel", "generatePlan failed (${e.javaClass.simpleName})")
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun adaptPlan() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                withContext(Dispatchers.IO) {
                    val profile = userRepository.getActiveProfile()
                        ?: throw IllegalStateException(context.getString(R.string.error_no_profile))

                    val result = adaptPlanUseCase.execute(profile.id, profile)
                    if (result != null && result.adaptations.isNotEmpty()) {
                        _state.update { it.copy(adaptations = result.adaptations) }
                    }
                }
                loadPlan()
            } catch (e: Exception) {
                android.util.Log.w("PlanViewModel", "adaptPlan failed (${e.javaClass.simpleName})")
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun buildWeekDays(plan: TrainingPlan, sessions: List<PlannedSession>): List<WeekDayInfo> {
        val dayNames = listOf(
            context.getString(R.string.day_name_mon),
            context.getString(R.string.day_name_tue),
            context.getString(R.string.day_name_wed),
            context.getString(R.string.day_name_thu),
            context.getString(R.string.day_name_fri),
            context.getString(R.string.day_name_sat),
            context.getString(R.string.day_name_sun)
        )
        val todayDow = DateTimeUtil.dayOfWeek(DateTimeUtil.todayIso())

        return (1..7).map { dow ->
            val session = sessions.find { it.dayOfWeek == dow }
            val date = DateTimeUtil.addDays(
                plan.startDate,
                dow - DateTimeUtil.dayOfWeek(plan.startDate)
            )
            WeekDayInfo(
                dayOfWeek = dow,
                dayName = dayNames[dow - 1],
                date = date,
                session = session,
                isToday = dow == todayDow
            )
        }
    }
}
