package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ClimbIntensity
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
    /** Hardest climbing of the day relative to the athlete's own level; null without graded climbing. */
    val climbIntensity: com.cruxcoach.athlete.model.ClimbIntensity? = null,
    /** Load units per structure from climbing and training (FEAT-071 load model, see ClimbingLoad). */
    val fingerLoad: Double = 0.0,
    val skinLoad: Double = 0.0,
    val shoulderLoad: Double = 0.0,
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
 * anyway. Fat fills the energy that protein and carbohydrate leave, kept
 * inside 20–35 % of the energy (owner 2026-10-10: a fat target with its own
 * ring). Energy (need, and the calorie target while losing weight) is
 * [EnergyBalance] and [WeightPlan].
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
        val fatG: Int,
        /** 20–35 % of the energy target, or 0.8–1.2 g/kg without one. */
        val fatRangeG: IntRange,
        /** The fat target comes from the day's energy target, not from body weight alone. */
        val fatFromEnergy: Boolean,
        /** Targets the athlete set themselves ([withOwn]); the others follow the recommendation. */
        val own: Set<TargetKind> = emptySet(),
        /** The recommendation behind own targets, to show next to them. */
        val recommended: Targets? = null,
    )

    enum class TargetKind { ENERGY, CARBS, PROTEIN, FAT, WATER }

    /** Plausible own targets: typos are refused, choices are not. */
    val OWN_KCAL = 800..6000
    val OWN_CARBS_G = 0..1000
    val OWN_PROTEIN_G = 20..400
    val OWN_FAT_G = 10..300
    val OWN_WATER_ML = 500..8000

    /** [t] with the athlete's own targets in place of the recommended ones. */
    fun withOwn(t: Targets, p: AthleteProfile): Targets = t.copy(
        proteinG = p.ownProteinG ?: t.proteinG,
        carbsG = p.ownCarbsG ?: t.carbsG,
        fatG = p.ownFatG ?: t.fatG,
        waterMl = p.ownWaterMl ?: t.waterMl,
        own = buildSet {
            if (p.ownKcal != null) add(TargetKind.ENERGY)
            if (p.ownCarbsG != null) add(TargetKind.CARBS)
            if (p.ownProteinG != null) add(TargetKind.PROTEIN)
            if (p.ownFatG != null) add(TargetKind.FAT)
            if (p.ownWaterMl != null) add(TargetKind.WATER)
        },
        recommended = t,
    )

    /**
     * Session RPE of the day's climbing: from the intensity the coach rated
     * for that day (board logbook against the athlete's level, or a climbing
     * day logged by hand or from Health Connect; FEAT-071), else a typical
     * board session.
     */
    fun climbRpe(intensity: ClimbIntensity?): Int = when (intensity) {
        null -> DEFAULT_CLIMB_RPE
        ClimbIntensity.LIGHT -> 4
        ClimbIntensity.VOLUME -> 6
        ClimbIntensity.HARD -> 8
        ClimbIntensity.LIMIT -> 9
    }

    fun dayLoad(activity: DayActivity?): DayLoad {
        if (activity == null || !activity.trained) return DayLoad.REST
        val climbMinutes = if (activity.climbingMinutes > 0) activity.climbingMinutes
            else activity.climbingEfforts * MINUTES_PER_EFFORT
        val load = climbMinutes * climbRpe(activity.climbIntensity) + activity.workoutLoad
        return when {
            load < 200 -> DayLoad.LIGHT
            load < 450 -> DayLoad.MODERATE
            load < 750 -> DayLoad.HARD
            else -> DayLoad.VERY_HARD
        }
    }

    fun trainingMinutes(activity: DayActivity?): Int {
        if (activity == null) return 0
        return climbingMinutes(activity) + activity.workoutMinutes
    }

    /** Climbing time of the day; logged efforts count about four minutes each without a recorded session. */
    fun climbingMinutes(activity: DayActivity?): Int {
        if (activity == null) return 0
        return if (activity.climbingMinutes > 0) activity.climbingMinutes else activity.climbingEfforts * MINUTES_PER_EFFORT
    }

    fun carbsPerKg(load: DayLoad): Double = when (load) {
        DayLoad.REST -> 3.0
        DayLoad.LIGHT -> 4.0
        DayLoad.MODERATE -> 5.0
        DayLoad.HARD -> 6.0
        DayLoad.VERY_HARD -> 7.0
    }

    /** Fat per kg body weight when no energy target is known (sports-nutrition rule of thumb). */
    const val FAT_PER_KG = 1.0

    /**
     * [energyKcal] is the day's energy target (the need, minus the deficit
     * while losing weight); without it fat follows body weight.
     */
    fun compute(weightKg: Double, activity: DayActivity?, proteinPerKg: Double, energyKcal: Int? = null): Targets {
        val load = dayLoad(activity)
        val ppk = proteinPerKg.coerceIn(1.2, 2.4)
        val cpk = carbsPerKg(load)
        val water = weightKg * 35 + trainingMinutes(activity) / 60.0 * 500
        val proteinG = (weightKg * ppk).roundToInt()
        val carbsG = (weightKg * cpk).roundToInt()
        val energy = energyKcal?.takeIf { it > 0 }
        val fatRange = if (energy != null) {
            (energy * MacroTotals.FAT_SHARE_MIN / 9).roundToInt()..(energy * MacroTotals.FAT_SHARE_MAX / 9).roundToInt()
        } else (weightKg * 0.8).roundToInt()..(weightKg * 1.2).roundToInt()
        val fatG = if (energy != null) ((energy - 4 * proteinG - 4 * carbsG) / 9.0).roundToInt().coerceIn(fatRange)
            else (weightKg * FAT_PER_KG).roundToInt()
        return Targets(
            dayLoad = load,
            proteinG = proteinG,
            proteinRangeG = (weightKg * 1.4).roundToInt()..(weightKg * 2.0).roundToInt(),
            carbsPerKg = cpk,
            carbsG = carbsG,
            waterMl = (water / 50).roundToInt() * 50,
            fatG = fatG,
            fatRangeG = fatRange,
            fatFromEnergy = energy != null,
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

/**
 * One day of logged fueling, as the energy guard sees it. [kcal] and
 * [needKcal] compare the day with its estimated need; only [complete] days
 * (food in at least two meals, and not today) are compared, so a day with one
 * logged snack does not read as a huge deficit.
 */
data class FuelDay(
    val day: String,
    val logged: Boolean,
    val carbsG: Double?,
    val dayLoad: DayLoad,
    val kcal: Double? = null,
    val needKcal: Int? = null,
    val complete: Boolean = false,
)

enum class RedsSignal {
    /** BMI under the IFSC 2024 screening threshold. */
    LOW_BMI,
    /** Trend weight falling fast. */
    RAPID_LOSS,
    /** Logged energy clearly below the estimated need over several days. */
    HIGH_DEFICIT,
    /** Logged carbohydrate repeatedly far below need on training days. */
    LOW_CARBS_ON_TRAINING_DAYS,
    /** The planned loss is faster than 1 % of body weight per week. */
    FAST_PACE,
    /** The calorie target is far under the need, or under resting energy. */
    LARGE_DEFICIT,
    /** The target weight lies under the BMI screening threshold. */
    LOW_TARGET_BMI,
}

/** An own calorie target with today's need and resting energy, so a warning can name them. */
data class OwnEnergyTarget(val kcal: Int, val needKcal: Int, val restingKcal: Int) {
    val deficitShare: Double get() = if (needKcal > 0) (needKcal - kcal).toDouble() / needKcal else 0.0
    val belowResting: Boolean get() = kcal < restingKcal
    val large: Boolean get() = belowResting || deficitShare > WeightPlan.LARGE_DEFICIT_SHARE
}

/** What the energy guard noticed, with the numbers behind it, so the app can show them. */
data class EnergyReport(
    val signals: List<RedsSignal> = emptyList(),
    val bmi: Double? = null,
    val bmiThreshold: Double = 18.5,
    /** Trend change per week in kg and as a share of the trend weight; negative while losing. */
    val weeklyRateKg: Double? = null,
    val weeklyRateShare: Double? = null,
    /** Trend change over four weeks as a share of the trend weight. */
    val fourWeekShare: Double? = null,
    /** Mean energy eaten and mean estimated need over [comparedDays] complete days. */
    val avgIntakeKcal: Int? = null,
    val avgNeedKcal: Int? = null,
    val comparedDays: Int = 0,
    val lowCarbDays: Int = 0,
    /** The weight-loss plan, while one is active. */
    val plan: WeightPlan.Plan? = null,
    /** The athlete's own calorie target against today's need, when one is set. */
    val ownTarget: OwnEnergyTarget? = null,
) {
    /** Mean deficit as a share of the need (0.3 = 30 % under it); null without compared days. */
    val deficitShare: Double?
        get() {
            val intake = avgIntakeKcal ?: return null
            val need = avgNeedKcal?.takeIf { it > 0 } ?: return null
            return (need - intake).toDouble() / need
        }
}

/**
 * Energy guard. It never diagnoses, never blocks a goal and never prescribes
 * a weight: it names, with numbers, what argues against the current course
 * and explains where to get help (owner 2026-10-09: transparent values and
 * warnings instead of withheld functions). Thresholds: BMI 18.5 men / 17.5
 * women (IFSC 2024 RED-S policy), loss faster than 1 % of body weight per week
 * or 3 % in four weeks, a mean deficit above 25 % of the need.
 */
object RedsGuard {

    /** A mean deficit above this share of the need is a high one. */
    const val HIGH_DEFICIT_SHARE = 0.25
    /** Complete days needed before logged intake is compared with the need. */
    const val MIN_COMPARED_DAYS = 3

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
        plan: WeightPlan.Plan? = null,
        ownTarget: OwnEnergyTarget? = null,
    ): EnergyReport {
        val signals = mutableListOf<RedsSignal>()
        val currentTrend = trend.lastOrNull()?.trend
        val bmi = bmi(currentTrend, heightCm)
        val threshold = bmiThreshold(profile.sex)
        if (bmi != null && bmi < threshold) signals += RedsSignal.LOW_BMI

        val weekly = TrendWeight.weeklyRate(trend, windowDays = 14)
        val weeklyShare = if (weekly != null && currentTrend != null && currentTrend > 0) weekly / currentTrend else null
        val fourWeeks = TrendWeight.relativeChange(trend, days = 28)
        val fastWeekly = weeklyShare != null && weeklyShare < -0.01
        val fastMonthly = fourWeeks != null && fourWeeks < -0.03
        if (fastWeekly || fastMonthly) signals += RedsSignal.RAPID_LOSS

        val week = fuelDays.takeLast(7)
        val compared = week.filter { it.complete && it.kcal != null && it.needKcal != null && it.needKcal > 0 }
        val avgIntake = compared.takeIf { it.size >= MIN_COMPARED_DAYS }?.map { it.kcal!! }?.average()
        val avgNeed = compared.takeIf { it.size >= MIN_COMPARED_DAYS }?.map { it.needKcal!!.toDouble() }?.average()
        if (avgIntake != null && avgNeed != null && avgIntake < avgNeed * (1 - HIGH_DEFICIT_SHARE)) signals += RedsSignal.HIGH_DEFICIT

        var lowDays = 0
        if (currentTrend != null) {
            lowDays = week.count { d -> d.logged && d.dayLoad >= DayLoad.MODERATE && (d.carbsG ?: 0.0) / currentTrend < 3.0 }
            if (lowDays >= 3) signals += RedsSignal.LOW_CARBS_ON_TRAINING_DAYS
        }

        // An own calorie target replaces the plan's: its deficit is weighed instead, with the same thresholds.
        plan?.let { p -> signals += if (ownTarget != null) p.warnings - RedsSignal.LARGE_DEFICIT else p.warnings }
        if (ownTarget?.large == true) signals += RedsSignal.LARGE_DEFICIT
        return EnergyReport(
            signals = signals, bmi = bmi, bmiThreshold = threshold,
            weeklyRateKg = weekly, weeklyRateShare = weeklyShare, fourWeekShare = fourWeeks,
            avgIntakeKcal = avgIntake?.roundToInt(), avgNeedKcal = avgNeed?.roundToInt(),
            comparedDays = if (avgIntake != null) compared.size else 0,
            lowCarbDays = lowDays, plan = plan, ownTarget = ownTarget,
        )
    }
}
