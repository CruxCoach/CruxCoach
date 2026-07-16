package com.cruxcoach.domain.usecase

import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.WorkoutRepository
import com.cruxcoach.domain.engine.AdaptiveAdjuster
import com.cruxcoach.domain.model.*
import com.cruxcoach.util.DateTimeUtil

class AdaptPlanUseCase(
    private val adaptiveAdjuster: AdaptiveAdjuster,
    private val planRepository: PlanRepository,
    private val workoutRepository: WorkoutRepository,
    private val climbRepository: ClimbRepository,
    private val today: () -> String = DateTimeUtil::todayIso,
) {

    data class AdaptResult(
        val updatedPlan: TrainingPlan,
        val adaptations: List<Adaptation>
    )

    fun execute(userId: Long, userProfile: UserProfile): AdaptResult? {
        // 1. Get the active plan
        val activePlan = planRepository.getActivePlan(userId) ?: return null
        val sessions = planRepository.getSessionsForPlan(activePlan.id)

        // 2. Build current WeekPlan from persisted data
        val currentWeekPlan = WeekPlan(
            phase = activePlan.phase,
            sessions = sessions,
            focusAreas = activePlan.focusAreas,
            weekNumber = 1
        )

        // 3. Gather recent data
        val todayIso = today()
        val weekStart = DateTimeUtil.startOfWeek(todayIso)
        val weekEnd = DateTimeUtil.endOfWeek(todayIso)
        // Deload detection reasons about four adjacent calendar weeks. A row
        // limit cannot represent that window for users training frequently.
        val historyStart = DateTimeUtil.addWeeks(weekStart, -3)
        val recentLogs = workoutRepository.getWorkoutsForDateRange(historyStart, weekEnd)
        val recentClimbs = climbRepository.getSendsForDateRange(weekStart, weekEnd)

        // 4. Run adaptive adjuster
        val (adjustedWeekPlan, adaptations) = adaptiveAdjuster.analyzeAndAdapt(
            currentPlan = currentWeekPlan,
            recentLogs = recentLogs,
            recentClimbs = recentClimbs,
            userProfile = userProfile
        )

        if (adaptations.isEmpty()) {
            return AdaptResult(updatedPlan = activePlan, adaptations = emptyList())
        }

        // 5. Update persisted sessions (atomic delete + re-insert)
        planRepository.replaceSessionsForPlan(activePlan.id, adjustedWeekPlan.sessions)

        // 6. Bump plan version
        val updatedPlan = activePlan.copy(
            planVersion = activePlan.planVersion + 1,
            generatedBy = PlanGeneratedBy.ADAPTIVE
        )

        return AdaptResult(
            updatedPlan = updatedPlan,
            adaptations = adaptations
        )
    }
}
