package com.cruxcoach.app.ui

import com.cruxcoach.athlete.logic.DayLoad
import com.cruxcoach.athlete.logic.EnergyBalance
import com.cruxcoach.athlete.logic.EnergyReport
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MacroTotals
import com.cruxcoach.athlete.logic.PlannedTraining
import com.cruxcoach.athlete.logic.RedsSignal
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.logic.WeightPlan
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A text Swift renders: an Android string key (as generated into
 * `Localizable.strings`) with typed arguments, a plural, a literal, or a join
 * of parts. Kotlin decides WHICH sentence and with which numbers – the same
 * decisions Android's composables make – and Swift owns the wording.
 *
 * Rendering (Swift `NutritionText.render`): with [parts], render each and
 * join with [separator]; with an empty [key] and one arg, that arg alone;
 * with an empty [key] otherwise, [literal]; else the localized
 * format for [key] (`.one`/`.other` appended when [count] ≥ 0, table
 * `IOS` when [table] is "ios"), filled with [args]. A key without args is
 * returned untouched, as `L()` does.
 */
class NutriText internal constructor(
    val key: String,
    /** "" = the Android-derived strings, "ios" = IOS.strings. */
    val table: String,
    val args: List<NutriTextArg>,
    /** Plural quantity; -1 when [key] is not a plural. */
    val count: Int,
    val literal: String,
    val parts: List<NutriText>,
    val separator: String,
)

/**
 * One format argument. [kind]: "int" ([integer], for `%ld`), "number"
 * ([number] with at most [decimals] decimals, trailing zeros dropped, in the
 * locale's separator – Android's `formatNumber`; for `%@`), "text" ([text],
 * for `%@`) or "message" ([nested], rendered, for `%@`).
 */
class NutriTextArg internal constructor(
    val kind: String,
    val integer: Long,
    val number: Double,
    val decimals: Int,
    val text: String,
    val nested: NutriText?,
)

/** Builders and the ports of Android's text functions for nutrition. */
internal object NutritionTexts {
    fun t(key: String, vararg args: NutriTextArg) = NutriText(key, "", args.toList(), -1, "", emptyList(), "")
    fun ios(key: String, vararg args: NutriTextArg) = NutriText(key, "ios", args.toList(), -1, "", emptyList(), "")
    fun plural(key: String, count: Int, vararg args: NutriTextArg) = NutriText(key, "", args.toList(), count, "", emptyList(), "")
    fun literal(text: String) = NutriText("", "", emptyList(), -1, text, emptyList(), "")
    fun join(parts: List<NutriText>, separator: String = " · ") = NutriText("", "", emptyList(), -1, "", parts, separator)
    val empty: NutriText = literal("")

    fun int(value: Int) = NutriTextArg("int", value.toLong(), 0.0, 0, "", null)
    fun int(value: Long) = NutriTextArg("int", value, 0.0, 0, "", null)
    fun num(value: Double, decimals: Int = 1) = NutriTextArg("number", 0, value, decimals, "", null)
    fun str(value: String) = NutriTextArg("text", 0, 0.0, 0, value, null)
    fun msg(value: NutriText) = NutriTextArg("message", 0, 0.0, 0, "", value)

    // ── Units and amounts (FuelAmounts.kt, TrainingCommon.kt) ────────

    /** Plate steps like 1.25 kg need two decimals; body weights and loads read best with one. */
    private fun massDecimals(v: Double): Int {
        val quarter = abs(v * 4 - (v * 4).roundToInt()) < 1e-6
        val tenth = abs(v * 10 - (v * 10).roundToInt()) < 1e-6
        return if (quarter && !tenth) 2 else 1
    }

    /** "0.5 kg" / "1.1 lb". */
    fun mass(kg: Double, units: UnitSystem): NutriText {
        val v = Units.massToDisplay(kg, units)
        return t(if (units == UnitSystem.IMPERIAL) "tr_format_lb" else "tr_format_kg", num(v, massDecimals(v)))
    }

    /** Grams for display: one decimal below 10 g, whole grams above. */
    fun grams(value: Double) = num(value, if (value < 10) 1 else 0)

    fun unitLabel(unit: FuelUnits.Amount): NutriText = t(
        when (unit) {
            FuelUnits.Amount.G -> "fvp_grams"
            FuelUnits.Amount.ML -> "fvp_ml"
            FuelUnits.Amount.OZ -> "trf_unit_oz"
            FuelUnits.Amount.FL_OZ -> "trf_unit_floz"
            FuelUnits.Amount.CUP -> "trf_unit_cups"
        },
    )

    /** An amount with its unit in the athlete's units: "120 g", "4.2 oz", "8 fl oz". */
    fun amount(base: Double, unit: FuelUnits.Amount): NutriText {
        val value = FuelUnits.fromBase(base, unit)
        val number = when (unit) {
            FuelUnits.Amount.G, FuelUnits.Amount.ML -> grams(value)
            else -> num(value, 1)
        }
        return t("trf_amount_with_unit", number, msg(unitLabel(unit)))
    }

    /** [base] grams or ml as the number to edit in [unit]: whole g/ml, tenths of an ounce, quarter cups. */
    fun inputNumber(base: Double, unit: FuelUnits.Amount): NutriTextArg {
        val value = FuelUnits.fromBase(base, unit)
        return when (unit) {
            FuelUnits.Amount.G, FuelUnits.Amount.ML -> num(value, 0)
            FuelUnits.Amount.OZ, FuelUnits.Amount.FL_OZ -> num(value, 1)
            FuelUnits.Amount.CUP -> num((value * 4).roundToInt() / 4.0, 2)
        }
    }

    /** "470 kcal", or "≈ 470 kcal" when (part of) it comes from the macros. */
    fun kcal(kcal: Double, estimated: Boolean) = t(if (estimated) "trf_kcal_estimated" else "trf_kcal_value", int(kcal.roundToInt()))

    /** "P 32 · C 85 · F 14 g", plus kcal when calories are shown. */
    fun macros(protein: Double?, carbs: Double?, fat: Double?, energy: MacroTotals.Energy?, showCalories: Boolean): NutriText {
        fun g(v: Double?) = v?.let { grams(it) } ?: str("–")
        val macros = t("trf_entry_macros_g", g(protein), g(carbs), g(fat))
        return if (showCalories && energy != null) join(listOf(macros, kcal(energy.kcal, energy.estimated))) else macros
    }

    /** "noch 61 g" until a target is met, then "Ziel erreicht" – never a red "over". */
    fun remaining(value: Double, target: Int): NutriText {
        val left = target - value.roundToInt()
        return if (left > 0) t("trf_to_go", int(left)) else t("trf_reached")
    }

    fun mealKey(m: Meal): String = when (m) {
        Meal.BREAKFAST -> "tr_meal_breakfast"
        Meal.LUNCH -> "tr_meal_lunch"
        Meal.DINNER -> "tr_meal_dinner"
        Meal.SNACK -> "tr_meal_snack"
        Meal.PRE_TRAINING -> "tr_meal_pre_training"
        Meal.POST_TRAINING -> "tr_meal_post_training"
    }

    fun dayLoad(d: DayLoad): NutriText = t(
        when (d) {
            DayLoad.REST -> "tr_dayload_rest"
            DayLoad.LIGHT -> "tr_dayload_light"
            DayLoad.MODERATE -> "tr_dayload_moderate"
            DayLoad.HARD -> "tr_dayload_hard"
            DayLoad.VERY_HARD -> "tr_dayload_very_hard"
        },
    )

    /** "board 60 min" / "climbing 90 min" / "training 45 min": today's planned training in the day's header and the carbohydrate info. */
    fun plannedTraining(p: PlannedTraining.Planned): NutriText = t(
        when (p.kind) {
            PlannedTraining.Kind.BOARD -> "trf_planned_board"
            PlannedTraining.Kind.CLIMB -> "trf_planned_climb"
            PlannedTraining.Kind.OFF_WALL -> "trf_planned_training"
        },
        int(p.minutes),
    )

    // ── Energy (EnergyCareCard.kt, EnergySheets.kt) ──────────────────

    /** The energy target of a day: the athlete's own, else the need minus the plan's deficit while losing weight. */
    fun energyTarget(need: EnergyBalance.Need?, plan: WeightPlan.Plan?, profile: AthleteProfile? = null): Int? =
        profile?.ownKcal ?: need?.let { it.totalKcal - (plan?.dailyDeficitKcal ?: 0) }

    /**
     * One reason with its numbers. Body numbers stay hidden in "hide numbers"
     * mode and calories when they are switched off; the reason itself is always named.
     */
    fun energyWarning(signal: RedsSignal, report: EnergyReport, profile: AthleteProfile): NutriText {
        val hide = profile.hideBodyNumbers
        val plan = report.plan
        return when (signal) {
            RedsSignal.LOW_BMI -> {
                val bmi = report.bmi
                if (hide || bmi == null) t("trt_reds_low_bmi")
                else t("trn_w_low_bmi", num(bmi), num(report.bmiThreshold))
            }
            RedsSignal.RAPID_LOSS -> {
                val share = report.weeklyRateShare
                val rate = report.weeklyRateKg
                val month = report.fourWeekShare
                when {
                    hide -> t("trt_reds_rapid_loss")
                    share != null && rate != null && share < -0.01 ->
                        t("trn_w_rapid_week", msg(mass(-rate, profile.units)), num(-share * 100))
                    month != null -> t("trn_w_rapid_month", num(-month * 100))
                    else -> t("trt_reds_rapid_loss")
                }
            }
            RedsSignal.HIGH_DEFICIT -> {
                val share = ((report.deficitShare ?: 0.0) * 100).roundToInt()
                val intake = report.avgIntakeKcal
                val need = report.avgNeedKcal
                if (profile.showCalories && intake != null && need != null)
                    t("trn_w_high_deficit", int(report.comparedDays), int(intake), int(share), int(need))
                else t("trn_w_high_deficit_plain", int(report.comparedDays), int(share))
            }
            RedsSignal.LOW_CARBS_ON_TRAINING_DAYS -> {
                val perKg = report.lowCarbPerKg
                val target = report.lowCarbTargetPerKg
                if (perKg == null || target == null) t("trt_reds_low_carbs")
                else t("trn_w_low_carbs", int(report.lowCarbDays), num(perKg),
                    if (target.start == target.endInclusive) num(target.start)
                    else msg(join(listOf(literalNumber(target.start), literalNumber(target.endInclusive)), "–")))
            }
            RedsSignal.FAST_PACE ->
                if (hide || plan == null) t("trn_w_fast_pace_plain")
                else t("trn_w_fast_pace", msg(mass(plan.paceKgPerWeek, profile.units)), num(plan.paceShare * 100))
            RedsSignal.LARGE_DEFICIT -> report.ownTarget?.let { own ->
                // The athlete's own calorie target is weighed with the same thresholds as the plan's.
                if (own.belowResting && profile.showCalories) t("trn_w_below_resting", int(own.kcal), int(own.restingKcal))
                else t("trn_w_large_deficit", int((own.deficitShare * 100).roundToInt()))
            } ?: run {
                val target = plan?.targetKcal
                val resting = plan?.restingKcal
                if (plan != null && plan.belowResting && profile.showCalories && target != null && resting != null)
                    t("trn_w_below_resting", int(target), int(resting))
                else t("trn_w_large_deficit", int(((plan?.deficitShare ?: WeightPlan.LARGE_DEFICIT_SHARE) * 100).roundToInt()))
            }
            RedsSignal.YOUTH -> t("trn_w_youth")
            RedsSignal.LOW_TARGET_BMI -> {
                val bmi = plan?.targetBmi
                if (hide || bmi == null) t("trn_w_low_target_bmi_plain")
                else t("trn_w_low_target_bmi", num(bmi), num(plan.bmiThreshold))
            }
        }
    }

    /** A bare number as a text part, for "5–7" ranges. */
    private fun literalNumber(value: Double) = value(num(value))

    /** One argument on its own (a number in a join). */
    fun value(arg: NutriTextArg) = NutriText("", "", listOf(arg), -1, "", emptyList(), "")

    /**
     * Why the day's deficit is smaller than the pace's, with both numbers; null
     * when it is not smaller.
     */
    fun easedDeficit(plan: WeightPlan.Plan?, dayLoad: DayLoad, profile: AthleteProfile): NutriText? {
        if (plan == null || plan.easedKcal <= 0) return null
        val day = msg(dayLoad(dayLoad))
        return when {
            !profile.showCalories -> t("trn_deficit_eased_plain", day)
            dayLoad == DayLoad.REST -> t("trn_deficit_eased_rest", day, int(plan.dailyDeficitKcal), int(plan.paceDeficitKcal))
            else -> t("trn_deficit_eased", day, int(plan.dailyDeficitKcal), int(plan.paceDeficitKcal))
        }
    }

    /**
     * The pace the last seven days' deficits really add up to, when it is
     * slower than the chosen one (to the 0.1 kg shown); null otherwise.
     */
    fun weekPace(plan: WeightPlan.Plan?, profile: AthleteProfile): NutriText? {
        val week = plan?.weekPaceKg ?: return null
        val shown = (week * 10).roundToInt() / 10.0
        if (shown >= plan.paceKgPerWeek - 1e-9) return null
        return t("trn_week_pace", msg(mass(shown, profile.units)), msg(mass(plan.paceKgPerWeek, profile.units)))
    }

    /** Short summary of the goal for settings rows: "0,5 kg pro Woche · heute etwa 1950 kcal · Ziel 65 kg". */
    fun weightGoalSummary(profile: AthleteProfile, plan: WeightPlan.Plan?): NutriText {
        val pace = msg(mass(WeightPlan.clampPace(profile.weeklyLossKg), profile.units))
        val target = plan?.targetKcal
        val head = if (profile.showCalories && target != null) t("trn_goal_summary", pace, int(target))
            else t("trn_goal_summary_plain", pace)
        val goal = profile.targetWeightKg?.takeIf { !profile.hideBodyNumbers }?.let { t("trn_goal_summary_target", msg(mass(it, profile.units))) }
        return join(listOfNotNull(head, goal))
    }
}
