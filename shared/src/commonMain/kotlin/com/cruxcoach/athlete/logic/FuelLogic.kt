package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.Sex
import kotlin.math.roundToInt

/** Everything that counts as training on one day, from the board and the logger. */
data class DayActivity(
    val day: String,
    /** Board/wall time from board sessions. */
    val climbingMinutes: Int = 0,
    /** Logged sends + attempts; used to estimate time when no session was recorded. */
    val climbingEfforts: Int = 0,
    val workoutMinutes: Int = 0,
    /** Session RPE × minutes of logged trainings (Foster's session load). */
    val workoutLoad: Int = 0,
) {
    val trained: Boolean get() = climbingMinutes > 0 || climbingEfforts > 0 || workoutMinutes > 0
}

enum class DayLoad { REST, LIGHT, MODERATE, HARD, VERY_HARD }

/**
 * Training-day aware fueling targets (FEAT-068).
 *
 * Protein follows body weight (ISSN range 1.4–2.0 g/kg). Carbohydrate follows
 * the day's training load inside the 3–7 g/kg range reported for climbers —
 * which CruxCoach can set automatically because board sessions are logged
 * anyway. There is deliberately no calorie budget here: the goal is enough
 * fuel for training, not a deficit.
 */
object FuelTargets {

    private const val DEFAULT_CLIMB_RPE = 7
    private const val MINUTES_PER_EFFORT = 4

    data class Targets(
        val dayLoad: DayLoad,
        val proteinG: Int,
        val proteinRangeG: IntRange,
        val carbsPerKg: Double,
        val carbsG: Int,
        val waterMl: Int,
    )

    fun dayLoad(activity: DayActivity?): DayLoad {
        if (activity == null || !activity.trained) return DayLoad.REST
        val climbMinutes = if (activity.climbingMinutes > 0) activity.climbingMinutes
            else activity.climbingEfforts * MINUTES_PER_EFFORT
        val load = climbMinutes * DEFAULT_CLIMB_RPE + activity.workoutLoad
        return when {
            load < 200 -> DayLoad.LIGHT
            load < 450 -> DayLoad.MODERATE
            load < 750 -> DayLoad.HARD
            else -> DayLoad.VERY_HARD
        }
    }

    fun trainingMinutes(activity: DayActivity?): Int {
        if (activity == null) return 0
        val climb = if (activity.climbingMinutes > 0) activity.climbingMinutes else activity.climbingEfforts * MINUTES_PER_EFFORT
        return climb + activity.workoutMinutes
    }

    fun carbsPerKg(load: DayLoad): Double = when (load) {
        DayLoad.REST -> 3.0
        DayLoad.LIGHT -> 4.0
        DayLoad.MODERATE -> 5.0
        DayLoad.HARD -> 6.0
        DayLoad.VERY_HARD -> 7.0
    }

    fun compute(weightKg: Double, activity: DayActivity?, proteinPerKg: Double): Targets {
        val load = dayLoad(activity)
        val ppk = proteinPerKg.coerceIn(1.2, 2.4)
        val cpk = carbsPerKg(load)
        val water = weightKg * 35 + trainingMinutes(activity) / 60.0 * 500
        return Targets(
            dayLoad = load,
            proteinG = (weightKg * ppk).roundToInt(),
            proteinRangeG = (weightKg * 1.4).roundToInt()..(weightKg * 2.0).roundToInt(),
            carbsPerKg = cpk,
            carbsG = (weightKg * cpk).roundToInt(),
            waterMl = (water / 50).roundToInt() * 50,
        )
    }
}

/**
 * Sums of logged nutrients for a day or a meal. Missing values count as zero:
 * a quick entry may carry only protein. An entry without kcal contributes the
 * energy of its macros (Atwater 4/4/9 kcal per g) and marks the sum as an
 * estimate.
 */
data class MacroTotals(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val entries: Int,
    val kcalEstimated: Boolean = false,
) {
    /** Energy of one entry; [estimated] when it comes from the macros instead of logged kcal. */
    data class Energy(val kcal: Double, val estimated: Boolean)

    /** Share of energy from fat, from the macros (4/4/9 kcal per g); null without any macros. */
    val fatEnergyShare: Double?
        get() {
            val energy = protein * 4 + carbs * 4 + fat * 9
            return if (energy > 0) fat * 9 / energy else null
        }

    companion object {
        /** Fat guideline for athletes: 20–35 % of energy (ACSM/AND/DC position stand 2016). */
        const val FAT_SHARE_MIN = 0.20
        const val FAT_SHARE_MAX = 0.35

        fun of(entries: List<FoodLogEntry>) = MacroTotals(
            kcal = entries.sumOf { energy(it)?.kcal ?: 0.0 },
            protein = entries.sumOf { it.proteinG ?: 0.0 },
            carbs = entries.sumOf { it.carbsG ?: 0.0 },
            fat = entries.sumOf { it.fatG ?: 0.0 },
            entries = entries.size,
            kcalEstimated = entries.any { energy(it)?.estimated == true },
        )

        /** Logged kcal, else the energy of the logged macros; null when the entry has neither. */
        fun energy(entry: FoodLogEntry): Energy? {
            entry.kcal?.let { return Energy(it, estimated = false) }
            if (entry.proteinG == null && entry.carbsG == null && entry.fatG == null) return null
            val kcal = 4 * (entry.proteinG ?: 0.0) + 4 * (entry.carbsG ?: 0.0) + 9 * (entry.fatG ?: 0.0)
            return Energy(kcal, estimated = true)
        }

        /** Per meal, in the order of [Meal]; meals without entries are left out. */
        fun byMeal(entries: List<FoodLogEntry>): List<Pair<Meal, MacroTotals>> =
            Meal.entries.mapNotNull { meal -> entries.filter { it.meal == meal }.takeIf { it.isNotEmpty() }?.let { meal to of(it) } }
    }
}

/** One day of logged fueling, as the energy-availability guard sees it. */
data class FuelDay(val day: String, val logged: Boolean, val carbsG: Double?, val dayLoad: DayLoad)

enum class RedsSignal {
    /** BMI under the IFSC 2024 screening threshold. */
    LOW_BMI,
    /** Trend weight falling fast. */
    RAPID_LOSS,
    /** Logged carbohydrate repeatedly far below need on training days. */
    LOW_CARBS_ON_TRAINING_DAYS,
    /** A weight-loss goal was set but one of the signals above is present. */
    LOSS_GOAL_PAUSED,
}

/**
 * Low-energy-availability guard. It never diagnoses and never prescribes a
 * weight; it only decides when the app should stop supporting weight loss and
 * show the supportive information screen instead. Thresholds follow the IFSC
 * 2024 RED-S policy (BMI 18.5 men / 17.5 women triggers evaluation).
 */
object RedsGuard {

    fun bmi(weightKg: Double?, heightCm: Double?): Double? {
        val w = weightKg?.takeIf { it > 0 } ?: return null
        val h = heightCm?.takeIf { it > 100 }?.div(100.0) ?: return null
        return w / (h * h)
    }

    fun bmiThreshold(sex: Sex?): Double = if (sex == Sex.FEMALE) 17.5 else 18.5

    fun evaluate(
        profile: AthleteProfile,
        heightCm: Double?,
        trend: List<TrendWeight.Point>,
        fuelDays: List<FuelDay>,
    ): List<RedsSignal> {
        val signals = mutableListOf<RedsSignal>()
        val currentTrend = trend.lastOrNull()?.trend
        val bmi = bmi(currentTrend, heightCm)
        if (bmi != null && bmi < bmiThreshold(profile.sex)) signals += RedsSignal.LOW_BMI

        val weekly = TrendWeight.weeklyRate(trend, windowDays = 14)
        val fourWeeks = TrendWeight.relativeChange(trend, days = 28)
        val fastWeekly = weekly != null && currentTrend != null && weekly < -0.01 * currentTrend
        val fastMonthly = fourWeeks != null && fourWeeks < -0.03
        if (fastWeekly || fastMonthly) signals += RedsSignal.RAPID_LOSS

        val weight = currentTrend
        if (weight != null) {
            val lowDays = fuelDays.takeLast(7).count { d ->
                d.logged && d.dayLoad >= DayLoad.MODERATE && (d.carbsG ?: 0.0) / weight < 3.0
            }
            if (lowDays >= 3) signals += RedsSignal.LOW_CARBS_ON_TRAINING_DAYS
        }

        if (profile.goal == AthleteGoal.LOSE_WEIGHT && signals.isNotEmpty()) signals += RedsSignal.LOSS_GOAL_PAUSED
        return signals
    }

    /** A loss goal is only offered when nothing above argues against it. */
    fun lossGoalAllowed(profile: AthleteProfile, heightCm: Double?, trend: List<TrendWeight.Point>): Boolean =
        evaluate(profile.copy(goal = AthleteGoal.PERFORM), heightCm, trend, emptyList()).isEmpty()
}
