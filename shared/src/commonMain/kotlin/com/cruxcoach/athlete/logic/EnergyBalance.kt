package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.EverydayActivity
import com.cruxcoach.athlete.model.Sex
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The day's estimated energy need, shown with its parts (owner 2026-10-09:
 * values in the open instead of a hidden budget).
 *
 * Resting energy by Mifflin-St Jeor (1990), everyday life as a physical
 * activity level on top of it (FAO/WHO/UNU 2004: 1.4 mostly seated, 1.6 on
 * the feet, 1.8 physical work) and the day's training as net MET energy
 * ((MET − 1) × kg × hours, Compendium of Physical Activities 2024). An
 * estimate; the screen says so.
 */
object EnergyBalance {

    /** Energy in one kg of body weight lost or gained (the common 7,700 kcal rule). */
    const val KCAL_PER_KG = 7700.0
    /** Age used without a birth year or an age band from the coach setup. */
    const val DEFAULT_AGE = 30

    data class Need(
        val restingKcal: Int,
        val everydayKcal: Int,
        val trainingKcal: Int,
        /** No birth year known: the age comes from the coach's age band or is [DEFAULT_AGE]. */
        val ageAssumed: Boolean,
        /** No sex given: the mean of the female and male formula. */
        val sexAssumed: Boolean,
        /**
         * Energy the day's protein and carbohydrate targets take with fat at
         * its 20 % floor ([FuelTargets.macroFloorKcal]): a calorie target
         * under it cannot hold the macro targets. 0 when unknown.
         */
        val macroFloorKcal: Int = 0,
        /** The day's training load, which sets its carbohydrate target. */
        val dayLoad: DayLoad = DayLoad.REST,
    ) {
        val totalKcal: Int get() = restingKcal + everydayKcal + trainingKcal
    }

    fun restingKcal(weightKg: Double, heightCm: Double, age: Int, sex: Sex?): Double =
        10 * weightKg + 6.25 * heightCm - 5 * age + when (sex) {
            Sex.MALE -> 5.0
            Sex.FEMALE -> -161.0
            Sex.OTHER, null -> -78.0
        }

    fun activityLevel(everyday: EverydayActivity): Double = when (everyday) {
        EverydayActivity.SEATED -> 1.4
        EverydayActivity.ON_FEET -> 1.6
        EverydayActivity.PHYSICAL -> 1.8
    }

    /** MET of a climbing session by its rated intensity, rests between attempts included. */
    fun climbMet(intensity: ClimbIntensity?): Double = when (intensity) {
        ClimbIntensity.LIGHT -> 4.5
        ClimbIntensity.VOLUME -> 5.5
        ClimbIntensity.HARD, null -> 6.0
        ClimbIntensity.LIMIT -> 6.5
    }

    /** MET of logged trainings from their mean session RPE (RPE 5 ≈ 4.3, RPE 8 ≈ 5.9). */
    fun workoutMet(meanRpe: Double): Double = (1.5 + 0.55 * meanRpe).coerceIn(3.0, 8.0)

    /** Energy the day's training adds on top of resting and everyday energy. */
    fun trainingKcal(activity: DayActivity?, weightKg: Double): Double {
        if (activity == null || !activity.trained) return 0.0
        val climbHours = FuelTargets.climbingMinutes(activity) / 60.0
        val climb = (climbMet(activity.climbIntensity) - 1) * weightKg * climbHours
        val rpe = if (activity.workoutMinutes > 0 && activity.workoutLoad > 0) activity.workoutLoad.toDouble() / activity.workoutMinutes else 6.0
        val workout = (workoutMet(rpe) - 1) * weightKg * activity.workoutMinutes / 60.0
        return climb + workout
    }

    fun age(profile: AthleteProfile, year: Int): Int = profile.birthYear?.let { year - it } ?: when (profile.coach.ageBand) {
        AgeBand.UNDER_16 -> 14
        AgeBand.Y16_17 -> 17
        AgeBand.Y18_39 -> DEFAULT_AGE
        AgeBand.Y40_54 -> 47
        AgeBand.Y55_PLUS -> 60
        null -> DEFAULT_AGE
    }

    /** Null without weight or height: resting energy needs both. */
    fun need(profile: AthleteProfile, weightKg: Double?, heightCm: Double?, activity: DayActivity?, year: Int): Need? {
        val w = weightKg?.takeIf { it > 0 } ?: return null
        val h = heightCm?.takeIf { it > 100 } ?: return null
        val resting = restingKcal(w, h, age(profile, year), profile.sex)
        // The macro targets the rings show that day: recommended, or the athlete's own.
        val macros = FuelTargets.withOwn(FuelTargets.compute(w, activity, profile.proteinPerKg), profile)
        return Need(
            restingKcal = resting.roundToInt(),
            everydayKcal = (resting * (activityLevel(profile.everydayActivity) - 1)).roundToInt(),
            trainingKcal = trainingKcal(activity, w).roundToInt(),
            ageAssumed = profile.birthYear == null,
            sexAssumed = profile.sex == null || profile.sex == Sex.OTHER,
            macroFloorKcal = FuelTargets.macroFloorKcal(macros.proteinG, macros.carbsG),
            dayLoad = macros.dayLoad,
        )
    }
}

/**
 * The calorie target while losing weight: the day's need minus the deficit of
 * the planned pace, so training days get more. Where that target could not
 * hold the day's protein, carbohydrate and 20 % fat, the day's deficit
 * shrinks until it does – at most to none, the target never rises above the
 * need: hard days are fuelled rather than their carbohydrates capped, and
 * the plan says how much slower that makes the week. Every pace can be set;
 * the plan names what argues against it instead of refusing it (owner
 * 2026-10-09). Above 1 % of body weight per week climbers lose strength and
 * muscle (Garthe et al. 2011, ISSN position stand); a target more than 25 %
 * under the need or under resting energy is a large deficit.
 */
object WeightPlan {

    const val FAST_PACE_SHARE = 0.01
    const val LARGE_DEFICIT_SHARE = 0.25
    const val MIN_PACE_KG = 0.1
    const val MAX_PACE_KG = 2.0
    /** Step of the pace buttons; [clampPace] keeps the 0.1 kg grid. */
    const val PACE_STEP_KG = 0.1

    data class Plan(
        val paceKgPerWeek: Double,
        /** Pace as a share of the current weight per week (0.007 = 0.7 %). */
        val paceShare: Double,
        /**
         * The day's deficit: [paceDeficitKcal], made smaller where the full
         * one would leave no room for the day's macro targets
         * ([dayDeficit]). Zero once the target weight is reached: the target
         * is then the need.
         */
        val dailyDeficitKcal: Int,
        val needKcal: Int?,
        val restingKcal: Int?,
        val targetKcal: Int?,
        val targetWeightKg: Double?,
        val targetBmi: Double?,
        val bmiThreshold: Double,
        /**
         * Weeks until the target weight: at [weekPaceKg] when the week's days
         * are known, else at the pace. 0 once it is reached; null without a
         * target or when the days leave no deficit at all.
         */
        val weeksToTarget: Int?,
        val warnings: List<RedsSignal>,
        /** The deficit the pace asks for every day (7,700 kcal per kg, spread over the week). */
        val paceDeficitKcal: Int = dailyDeficitKcal,
        /** Kilograms per week that the deficits of the given days add up to; null without them. */
        val weekPaceKg: Double? = null,
    ) {
        /** How much smaller the day's deficit is than the pace's, so its macro targets fit. */
        val easedKcal: Int get() = paceDeficitKcal - dailyDeficitKcal
        val deficitShare: Double? get() = needKcal?.takeIf { it > 0 }?.let { dailyDeficitKcal.toDouble() / it }
        val belowResting: Boolean get() = targetKcal != null && restingKcal != null && targetKcal < restingKcal
        val targetReached: Boolean get() = weeksToTarget == 0
    }

    /** The pace on the 0.1 kg grid, inside [MIN_PACE_KG]..[MAX_PACE_KG]. */
    fun clampPace(kg: Double): Double = ((kg * 10).roundToInt() / 10.0).coerceIn(MIN_PACE_KG, MAX_PACE_KG)

    /**
     * The deficit a day can hold: the pace's, but no more than lets the
     * target stay at [EnergyBalance.Need.macroFloorKcal] – and never below
     * zero, so the target never exceeds the need.
     */
    fun dayDeficit(paceDeficitKcal: Int, need: EnergyBalance.Need?): Int {
        if (need == null || need.macroFloorKcal <= 0) return paceDeficitKcal
        return paceDeficitKcal.coerceAtMost((need.totalKcal - need.macroFloorKcal).coerceAtLeast(0))
    }

    /**
     * [weekNeeds] are the needs of the last days (today included); with them
     * the plan says what pace their deficits really add up to.
     */
    fun plan(
        paceKg: Double,
        targetWeightKg: Double?,
        weightKg: Double,
        heightCm: Double?,
        sex: Sex?,
        need: EnergyBalance.Need?,
        weekNeeds: List<EnergyBalance.Need> = emptyList(),
    ): Plan {
        val pace = clampPace(paceKg)
        val reached = targetWeightKg != null && weightKg <= targetWeightKg
        val paceDeficit = if (reached) 0 else (pace * EnergyBalance.KCAL_PER_KG / 7).roundToInt()
        val deficit = dayDeficit(paceDeficit, need)
        val weekPace = weekNeeds.takeIf { !reached && it.isNotEmpty() }
            ?.let { days -> days.sumOf { dayDeficit(paceDeficit, it) } * 7.0 / days.size / EnergyBalance.KCAL_PER_KG }
        val effectivePace = weekPace ?: pace
        val needKcal = need?.totalKcal
        val target = needKcal?.let { it - deficit }
        val threshold = RedsGuard.bmiThreshold(sex)
        val targetBmi = RedsGuard.bmi(targetWeightKg, heightCm)
        val share = pace / weightKg
        val warnings = buildList {
            if (!reached && share > FAST_PACE_SHARE) add(RedsSignal.FAST_PACE)
            if (needKcal != null && target != null && need.restingKcal > 0 &&
                (deficit > needKcal * LARGE_DEFICIT_SHARE || target < need.restingKcal)) add(RedsSignal.LARGE_DEFICIT)
            if (targetBmi != null && targetBmi < threshold) add(RedsSignal.LOW_TARGET_BMI)
        }
        return Plan(
            paceKgPerWeek = pace, paceShare = share, dailyDeficitKcal = deficit,
            needKcal = needKcal, restingKcal = need?.restingKcal, targetKcal = target,
            targetWeightKg = targetWeightKg, targetBmi = targetBmi, bmiThreshold = threshold,
            // A hair below whole weeks (3 kg at 0.3 kg) stays that many weeks.
            weeksToTarget = targetWeightKg?.let {
                if (reached) 0 else if (effectivePace < MIN_PACE_KG / 10) null else ceil((weightKg - it) / effectivePace - 1e-6).toInt()
            },
            warnings = warnings,
            paceDeficitKcal = paceDeficit,
            weekPaceKg = weekPace,
        )
    }
}
