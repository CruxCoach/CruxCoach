package com.cruxcoach.domain.engine

import com.cruxcoach.domain.model.*
import com.cruxcoach.util.DateTimeUtil
import com.cruxcoach.util.GradeConverter

class AdaptiveAdjuster {

    fun analyzeAndAdapt(
        currentPlan: WeekPlan,
        recentLogs: List<WorkoutLog>,
        recentClimbs: List<ClimbLog>,
        userProfile: UserProfile
    ): Pair<WeekPlan, List<Adaptation>> {
        val adaptations = mutableListOf<Adaptation>()
        var adjustedPlan = currentPlan

        // === RPE ANALYSIS ===
        adjustedPlan = checkRpe(adjustedPlan, recentLogs, adaptations)

        // === FINGER / INJURY CHECK ===
        adjustedPlan = checkInjuries(adjustedPlan, recentLogs, adaptations)

        // === SKIN STATUS ===
        adjustedPlan = checkSkinStatus(adjustedPlan, recentLogs, adaptations)

        // === GRADE PROGRESSION ===
        checkGradeProgression(recentClimbs, userProfile, adaptations)

        // === DELOAD CHECK ===
        checkDeloadNeeded(currentPlan, recentLogs, adaptations)

        // === MOTIVATION / MOOD CHECK ===
        checkMoodAndMotivation(recentLogs, adaptations)

        // === MISSED SESSIONS ===
        checkMissedSessions(currentPlan, recentLogs, adaptations)

        return Pair(adjustedPlan, adaptations)
    }

    internal fun checkRpe(
        plan: WeekPlan,
        recentLogs: List<WorkoutLog>,
        adaptations: MutableList<Adaptation>
    ): WeekPlan {
        val rpeValues = recentLogs
            .sortedWith(compareByDescending<WorkoutLog> { it.date }.thenByDescending { it.id })
            .filter { it.perceivedRpe != null }
            .take(4)
            .mapNotNull { it.perceivedRpe }

        if (rpeValues.size < 2) return plan

        val avgRpe = rpeValues.average()

        if (avgRpe > 8.5) {
            adaptations.add(
                Adaptation(
                    type = AdaptationType.VOLUME_DECREASE,
                    description = "Volumen um 20% reduziert – deine RPE war ${"%.1f".format(avgRpe)} in den letzten Sessions",
                    emoji = "\uD83D\uDCC9" // 📉
                )
            )
            return reduceVolume(plan, 0.8f)
        }

        if (avgRpe < 6.0) {
            adaptations.add(
                Adaptation(
                    type = AdaptationType.INTENSITY_INCREASE,
                    description = "Intensität erhöht – du hattest Luft nach oben (RPE ${"%.1f".format(avgRpe)})",
                    emoji = "\uD83D\uDCC8" // 📈
                )
            )
            return increaseIntensity(plan)
        }

        return plan
    }

    internal fun checkInjuries(
        plan: WeekPlan,
        recentLogs: List<WorkoutLog>,
        adaptations: MutableList<Adaptation>
    ): WeekPlan {
        val lastLog = recentLogs.maxWithOrNull(compareBy<WorkoutLog> { it.date }.thenBy { it.id })
            ?: return plan

        if (lastLog.painAreas.any { it.contains("finger", ignoreCase = true) }) {
            adaptations.add(
                Adaptation(
                    type = AdaptationType.INJURY_ALERT,
                    description = "Finger-Schmerz geloggt – alle Fingerbelastung gestrichen. Bitte Arzt konsultieren wenn es anhält!",
                    emoji = "\uD83D\uDEA8" // 🚨
                )
            )
            return removeFingerLoad(plan)
        }

        return plan
    }

    internal fun checkSkinStatus(
        plan: WeekPlan,
        recentLogs: List<WorkoutLog>,
        adaptations: MutableList<Adaptation>
    ): WeekPlan {
        val lastLog = recentLogs.maxWithOrNull(compareBy<WorkoutLog> { it.date }.thenBy { it.id })
            ?: return plan

        if (lastLog.fingerSkinStatus == "SPLIT") {
            adaptations.add(
                Adaptation(
                    type = AdaptationType.SKIN_RECOVERY,
                    description = "Hangboard + Board-Sessions reduziert – Haut braucht Erholung",
                    emoji = "\uD83E\uDE79" // 🩹
                )
            )
            return reduceBoardAndHangboard(plan)
        }

        return plan
    }

    internal fun checkGradeProgression(
        recentClimbs: List<ClimbLog>,
        userProfile: UserProfile,
        adaptations: MutableList<Adaptation>
    ) {
        val recentMaxGrade = recentClimbs
            .filter { it.sent }
            .maxByOrNull { GradeConverter.vScaleToNumeric(it.grade) }
            ?.grade ?: return

        val currentMax = GradeConverter.vScaleToNumeric(userProfile.maxBoulderGrade)
        val recentMax = GradeConverter.vScaleToNumeric(recentMaxGrade)

        if (recentMax > currentMax) {
            adaptations.add(
                Adaptation(
                    type = AdaptationType.GRADE_UPGRADE,
                    description = "Neuer Maximalgrad: $recentMaxGrade! Plan wird ans neue Level angepasst.",
                    emoji = "\uD83C\uDF89" // 🎉
                )
            )
        }
    }

    internal fun checkDeloadNeeded(
        currentPlan: WeekPlan,
        recentLogs: List<WorkoutLog>,
        adaptations: MutableList<Adaptation>
    ) {
        if (currentPlan.phase == TrainingPhase.DELOAD) return

        val weeksWithoutDeload = countWeeksWithoutDeload(recentLogs)
        if (weeksWithoutDeload >= 4) {
            adaptations.add(
                Adaptation(
                    type = AdaptationType.SUGGEST_DELOAD,
                    description = "$weeksWithoutDeload Wochen ohne Deload – nächste Woche leichter?",
                    emoji = "\uD83D\uDE34" // 😴
                )
            )
        }
    }

    internal fun checkMoodAndMotivation(
        recentLogs: List<WorkoutLog>,
        adaptations: MutableList<Adaptation>
    ) {
        val moodValues = recentLogs
            .sortedWith(compareByDescending<WorkoutLog> { it.date }.thenByDescending { it.id })
            .filter { it.moodPre != null }
            .take(3)
            .mapNotNull { it.moodPre }

        if (moodValues.isEmpty()) return

        val avgMood = moodValues.average()
        if (avgMood < 2.5) {
            adaptations.add(
                Adaptation(
                    type = AdaptationType.MOTIVATION_BOOST,
                    description = "Energie war zuletzt niedrig – eine Fun-Session statt hartem Training?",
                    emoji = "\uD83C\uDF1F" // 🌟
                )
            )
        }
    }

    internal fun checkMissedSessions(
        currentPlan: WeekPlan,
        recentLogs: List<WorkoutLog>,
        adaptations: MutableList<Adaptation>
    ) {
        val logsThisWeek = recentLogs.count { isThisWeek(it.date) }
        // An empty observation window is not evidence that sessions were
        // missed. Require at least one log from this week before inferring a
        // completion shortfall from the remaining plan.
        if (logsThisWeek == 0) return
        val plannedSessions = currentPlan.sessions.size
        val missed = plannedSessions - logsThisWeek

        if (missed >= 2 && plannedSessions > 2) {
            adaptations.add(
                Adaptation(
                    type = AdaptationType.SESSION_REDUCE,
                    description = "2+ Sessions verpasst – Plan auf ${plannedSessions - 1}x/Woche anpassen?",
                    emoji = "\uD83D\uDCC5" // 📅
                )
            )
        }
    }

    internal fun reduceVolume(plan: WeekPlan, factor: Float): WeekPlan {
        val adjusted = plan.sessions.map { session ->
            val reducedExercises = session.exercises.map { ex ->
                ex.copy(sets = (ex.sets * factor).toInt().coerceAtLeast(1))
            }
            session.copy(exercises = reducedExercises)
        }
        return plan.copy(sessions = adjusted)
    }

    internal fun increaseIntensity(plan: WeekPlan): WeekPlan {
        val adjusted = plan.sessions.map { session ->
            session.copy(targetRpe = (session.targetRpe + 0.5f).coerceAtMost(10.0f))
        }
        return plan.copy(sessions = adjusted)
    }

    internal fun removeFingerLoad(plan: WeekPlan): WeekPlan {
        val fingerCategories = setOf("HANGBOARD", "CAMPUS", "POWER", "BOARD_CLIMBING")
        val adjusted = plan.sessions.map { session ->
            val filtered = session.exercises.filter { ex ->
                ex.category.uppercase() !in fingerCategories
            }
            session.copy(exercises = filtered)
        }
        return plan.copy(sessions = adjusted)
    }

    internal fun reduceBoardAndHangboard(plan: WeekPlan): WeekPlan {
        val boardCategories = setOf("HANGBOARD", "BOARD_CLIMBING")
        val adjusted = plan.sessions.map { session ->
            val reduced = session.exercises.map { ex ->
                if (ex.category.uppercase() in boardCategories) {
                    ex.copy(sets = (ex.sets / 2).coerceAtLeast(1))
                } else {
                    ex
                }
            }
            session.copy(exercises = reduced)
        }
        return plan.copy(sessions = adjusted)
    }

    internal fun countWeeksWithoutDeload(logs: List<WorkoutLog>): Int {
        if (logs.isEmpty()) return 0
        val byWeek = logs.mapNotNull { log ->
            val weekStart = try {
                DateTimeUtil.startOfWeek(log.date)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            weekStart to log
        }.groupBy({ it.first }, { it.second })

        var weeks = 0
        var expectedWeek: String? = null
        for ((weekStart, weekLogs) in byWeek.toSortedMap(reverseOrder())) {
            if (expectedWeek != null && weekStart != expectedWeek) break
            val rpeValues = weekLogs.mapNotNull { it.perceivedRpe }
            if (rpeValues.isEmpty() || rpeValues.average() < 6.0) break
            weeks++
            expectedWeek = DateTimeUtil.addWeeks(weekStart, -1)
        }
        return weeks
    }

    private fun isThisWeek(date: String): Boolean {
        return try {
            DateTimeUtil.isThisWeek(date)
        } catch (_: Exception) {
            false
        }
    }
}
