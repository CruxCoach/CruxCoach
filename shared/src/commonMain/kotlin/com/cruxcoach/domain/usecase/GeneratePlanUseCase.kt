package com.cruxcoach.domain.usecase

import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.WorkoutRepository
import com.cruxcoach.domain.engine.ProfileClassifier
import com.cruxcoach.domain.engine.TrainingEngine
import com.cruxcoach.domain.model.*
import com.cruxcoach.util.DateTimeUtil

class GeneratePlanUseCase(
    private val profileClassifier: ProfileClassifier,
    private val trainingEngine: TrainingEngine,
    private val planRepository: PlanRepository,
    private val workoutRepository: WorkoutRepository
) {

    fun execute(
        userProfile: UserProfile,
        assessment: Assessment,
        boardAnalysis: BoardAnalysisResult? = null,
        weeksSinceStart: Int = 0
    ): TrainingPlan {
        // 1. Classify the climber profile
        val climberProfile = profileClassifier.classify(assessment, userProfile, boardAnalysis)

        // 2. Get recent workout logs for phase selection
        val recentLogs = workoutRepository.getRecentWorkouts(limit = 14)

        // 3. Generate the week plan via TrainingEngine
        val weekPlan = trainingEngine.generateWeekPlan(
            profile = climberProfile,
            userProfile = userProfile,
            currentAssessment = assessment,
            recentLogs = recentLogs,
            weeksSinceStart = weeksSinceStart
        )

        // 4. Create and persist TrainingPlan
        val today = DateTimeUtil.todayIso()
        val endDate = DateTimeUtil.addDays(today, 6)

        val trainingPlan = TrainingPlan(
            userId = userProfile.id,
            startDate = today,
            endDate = endDate,
            phase = weekPlan.phase,
            focusAreas = weekPlan.focusAreas,
            sessionsPerWeek = userProfile.sessionsPerWeek,
            planVersion = 1,
            generatedBy = PlanGeneratedBy.INITIAL
        )

        val planId = planRepository.savePlan(trainingPlan, weekPlan.sessions)

        // 5. Return the persisted plan with ID
        return trainingPlan.copy(id = planId)
    }
}
