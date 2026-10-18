package com.cruxcoach.app.ui

import com.cruxcoach.app.nutrition.FuelPresenter
import com.cruxcoach.app.nutrition.FuelState
import com.cruxcoach.app.ui.NutritionTexts.int
import com.cruxcoach.app.ui.NutritionTexts.literal
import com.cruxcoach.app.ui.NutritionTexts.msg
import com.cruxcoach.app.ui.NutritionTexts.num
import com.cruxcoach.app.ui.NutritionTexts.str
import com.cruxcoach.app.ui.NutritionTexts.t
import com.cruxcoach.athlete.logic.EnergyBalance
import com.cruxcoach.athlete.logic.EnergyReport
import com.cruxcoach.athlete.logic.FuelTargets
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MacroTotals
import com.cruxcoach.athlete.logic.OwnEnergyTarget
import com.cruxcoach.athlete.logic.RedsSignal
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.logic.WeightPlan
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.EverydayActivity
import com.cruxcoach.athlete.model.Sex
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt

class NutritionEnergyWarningUi(
    /** lowBmi | rapidLoss | highDeficit | lowCarbsOnTrainingDays | fastPace | largeDeficit | lowTargetBmi | youth */
    val code: String,
    val text: NutriText,
)

/** The energy card (RED-S guard): the same reasons wherever it appears; nothing is locked. */
class NutritionEnergyCardUi(
    val visible: Boolean,
    val warnings: List<NutritionEnergyWarningUi>,
    /** "Adjust weight-loss goal": only for what the goal causes, or a running goal under 18. */
    val canAdjustGoal: Boolean,
)

/** One nutrition target: the recommendation or the athlete's own value. */
class OwnTargetUi(
    /** energy | carbs | protein | fat | water */
    val kindCode: String,
    val label: NutriText,
    val unit: NutriText,
    /** "Recommended (208 g)", or plain "Recommended" without a number. */
    val recommendedChip: NutriText,
    val isOwn: Boolean,
    /** The own value in display units (fl oz for US water). */
    val ownText: String,
    val fieldLabel: NutriText,
    /** Why a value outside the plausible range is not kept, with the range. */
    val invalidHint: NutriText,
    /** Under an own carbohydrate or calorie value: it does not follow the training day. */
    val ownNote: NutriText,
    val hasOwnNote: Boolean,
)

class EnergyRowUi(val code: String, val label: NutriText, val value: NutriText, val bold: Boolean)

class NutritionChoiceUi(val code: String, val label: NutriText, val selected: Boolean)

/** "Energy today" (Android `EnergySheet`): the need with its parts, in the open. */
class EnergySheetUi(
    val needsWeight: Boolean,
    val massUnit: String,
    val needsHeight: Boolean,
    val lengthUnit: String,
    val heightLabel: NutriText,
    val hasNeed: Boolean,
    val resting: EnergyRowUi,
    val everyday: EnergyRowUi,
    val everydayChoices: List<NutritionChoiceUi>,
    val training: EnergyRowUi,
    val plannedTraining: NutriText,
    val hasPlannedTraining: Boolean,
    val need: EnergyRowUi,
    val hasPlan: Boolean,
    val deficit: EnergyRowUi,
    val target: EnergyRowUi,
    /** Eased deficit and the week's real pace, when they apply. */
    val planNotes: List<NutriText>,
    val ownEnergy: OwnTargetUi,
    val ownWarning: NutriText,
    val hasOwnWarning: Boolean,
    val hasEaten: Boolean,
    val eaten: EnergyRowUi,
    val ageAssumed: Boolean,
    val ageText: NutriText,
    val birthYearMin: Int,
    val birthYearMax: Int,
    val sexAssumed: Boolean,
    val sexChoices: List<NutritionChoiceUi>,
    /** trn_edit_goal | trn_set_goal */
    val goalButtonKey: String,
)

internal object EnergyUi {
    /** Signals that come from the weight-loss plan; the card offers to adjust it. */
    private val PLAN_SIGNALS = setOf(RedsSignal.FAST_PACE, RedsSignal.LARGE_DEFICIT, RedsSignal.LOW_TARGET_BMI)

    /** Plausible heights in cm, as in the settings. */
    val PLAUSIBLE_HEIGHT_CM = 100.0..250.0
    /** Body weights the app accepts, in kg. */
    val PLAUSIBLE_WEIGHT_KG = 20.0..300.0

    fun signalCode(s: RedsSignal): String = when (s) {
        RedsSignal.LOW_BMI -> "lowBmi"
        RedsSignal.RAPID_LOSS -> "rapidLoss"
        RedsSignal.HIGH_DEFICIT -> "highDeficit"
        RedsSignal.LOW_CARBS_ON_TRAINING_DAYS -> "lowCarbsOnTrainingDays"
        RedsSignal.FAST_PACE -> "fastPace"
        RedsSignal.LARGE_DEFICIT -> "largeDeficit"
        RedsSignal.LOW_TARGET_BMI -> "lowTargetBmi"
        RedsSignal.YOUTH -> "youth"
    }

    fun card(report: EnergyReport, profile: AthleteProfile, canOpenGoal: Boolean): NutritionEnergyCardUi {
        // "Adjust weight-loss goal" only for what the goal causes; a low own calorie target is changed at the energy ring.
        // Under 18 the running goal itself is the reason to look at it again.
        val adjust = canOpenGoal && report.signals.any { s ->
            (s in PLAN_SIGNALS && !(s == RedsSignal.LARGE_DEFICIT && report.ownTarget != null)) || (s == RedsSignal.YOUTH && report.plan != null)
        }
        return NutritionEnergyCardUi(
            visible = report.signals.isNotEmpty(),
            warnings = report.signals.map { NutritionEnergyWarningUi(signalCode(it), NutritionTexts.energyWarning(it, report, profile)) },
            canAdjustGoal = adjust,
        )
    }

    fun kindCode(kind: FuelTargets.TargetKind): String = when (kind) {
        FuelTargets.TargetKind.ENERGY -> "energy"
        FuelTargets.TargetKind.CARBS -> "carbs"
        FuelTargets.TargetKind.PROTEIN -> "protein"
        FuelTargets.TargetKind.FAT -> "fat"
        FuelTargets.TargetKind.WATER -> "water"
    }

    fun kind(code: String): FuelTargets.TargetKind? = FuelTargets.TargetKind.entries.firstOrNull { kindCode(it) == code }

    /** The own target of [kind] in a profile. */
    fun ownTarget(p: AthleteProfile, kind: FuelTargets.TargetKind): Int? = when (kind) {
        FuelTargets.TargetKind.ENERGY -> p.ownKcal
        FuelTargets.TargetKind.CARBS -> p.ownCarbsG
        FuelTargets.TargetKind.PROTEIN -> p.ownProteinG
        FuelTargets.TargetKind.FAT -> p.ownFatG
        FuelTargets.TargetKind.WATER -> p.ownWaterMl
    }

    /** [p] with the own target of [kind] set, or back to the recommendation with null. */
    fun withOwnTarget(p: AthleteProfile, kind: FuelTargets.TargetKind, value: Int?): AthleteProfile = when (kind) {
        FuelTargets.TargetKind.ENERGY -> p.copy(ownKcal = value)
        FuelTargets.TargetKind.CARBS -> p.copy(ownCarbsG = value)
        FuelTargets.TargetKind.PROTEIN -> p.copy(ownProteinG = value)
        FuelTargets.TargetKind.FAT -> p.copy(ownFatG = value)
        FuelTargets.TargetKind.WATER -> p.copy(ownWaterMl = value)
    }

    fun range(kind: FuelTargets.TargetKind): IntRange = when (kind) {
        FuelTargets.TargetKind.ENERGY -> FuelTargets.OWN_KCAL
        FuelTargets.TargetKind.CARBS -> FuelTargets.OWN_CARBS_G
        FuelTargets.TargetKind.PROTEIN -> FuelTargets.OWN_PROTEIN_G
        FuelTargets.TargetKind.FAT -> FuelTargets.OWN_FAT_G
        FuelTargets.TargetKind.WATER -> FuelTargets.OWN_WATER_ML
    }

    private fun fallback(kind: FuelTargets.TargetKind): Int = when (kind) {
        FuelTargets.TargetKind.ENERGY -> 2200
        FuelTargets.TargetKind.CARBS -> 250
        FuelTargets.TargetKind.PROTEIN -> 110
        FuelTargets.TargetKind.FAT -> 70
        FuelTargets.TargetKind.WATER -> 2500
    }

    private fun imperialWater(kind: FuelTargets.TargetKind, units: UnitSystem) =
        kind == FuelTargets.TargetKind.WATER && units == UnitSystem.IMPERIAL

    fun toDisplay(kind: FuelTargets.TargetKind, units: UnitSystem, v: Int): Int =
        if (imperialWater(kind, units)) (v / FuelUnits.ML_PER_FL_OZ).roundToInt() else v

    fun fromDisplay(kind: FuelTargets.TargetKind, units: UnitSystem, v: Int): Int =
        if (imperialWater(kind, units)) (v * FuelUnits.ML_PER_FL_OZ).roundToInt() else v

    /** Choosing "own" starts from the recommendation, so one tap keeps today's value as the own one. */
    fun startValue(kind: FuelTargets.TargetKind, recommended: Int?): Int = (recommended ?: fallback(kind)).coerceIn(range(kind))

    /** A typed own value in display units, stored when plausible; null (not kept) otherwise. */
    fun parseOwn(kind: FuelTargets.TargetKind, units: UnitSystem, text: String): Int? =
        text.trim().toIntOrNull()?.let { fromDisplay(kind, units, it) }?.takeIf { it in range(kind) }

    /** Targets whose recommendation changes with the day's training load, unlike an own value. */
    private val DAY_FOLLOWING = setOf(FuelTargets.TargetKind.CARBS, FuelTargets.TargetKind.ENERGY)

    private fun label(kind: FuelTargets.TargetKind): NutriText = t(
        when (kind) {
            FuelTargets.TargetKind.ENERGY -> "trn_energy"
            FuelTargets.TargetKind.CARBS -> "tru_carbs_short"
            FuelTargets.TargetKind.PROTEIN -> "trf_protein"
            FuelTargets.TargetKind.FAT -> "trf_fat"
            FuelTargets.TargetKind.WATER -> "trf_water"
        },
    )

    private fun unit(kind: FuelTargets.TargetKind, units: UnitSystem): NutriText = when (kind) {
        FuelTargets.TargetKind.ENERGY -> literal("kcal")
        FuelTargets.TargetKind.WATER -> literal(if (units == UnitSystem.IMPERIAL) "fl oz" else "ml")
        else -> t("trn_unit_g")
    }

    /**
     * Port of `OwnTargetChoice`: [recommended] is the day's recommended value in
     * the stored unit; [heading] replaces the nutrient's name where it is already the title.
     */
    fun ownTargetUi(kind: FuelTargets.TargetKind, profile: AthleteProfile, recommended: Int?, heading: NutriText?): OwnTargetUi {
        val units = profile.units
        val unit = unit(kind, units)
        val range = range(kind)
        val own = ownTarget(profile, kind)
        val label = label(kind)
        return OwnTargetUi(
            kindCode = kindCode(kind),
            label = heading ?: label,
            unit = unit,
            recommendedChip = recommended?.let { t("trn_target_recommended", int(toDisplay(kind, units, it)), msg(unit)) }
                ?: t("trn_target_recommended_plain"),
            isOwn = own != null,
            ownText = own?.let { toDisplay(kind, units, it).toString() }.orEmpty(),
            fieldLabel = t("trn_target_own_label", msg(label)),
            // A typo guard, not a limit on choices: the field names the range and why.
            invalidHint = t(if (kind == FuelTargets.TargetKind.ENERGY) "trn_target_range_energy" else "trn_target_range",
                int(toDisplay(kind, units, range.first)), int(toDisplay(kind, units, range.last)), msg(unit)),
            // The recommendation follows the day's training; an own carbohydrate or calorie target does not.
            ownNote = if (kind in DAY_FOLLOWING) t("trn_target_own_fixed") else NutritionTexts.empty,
            hasOwnNote = kind in DAY_FOLLOWING,
        )
    }

    private fun kcalRow(code: String, label: NutriText, value: String, bold: Boolean = false, unit: Boolean = true) =
        EnergyRowUi(code, label, if (unit) t("trn_kcal_signed", str(value)) else literal(value), bold)

    private fun everydayKey(e: EverydayActivity) = when (e) {
        EverydayActivity.SEATED -> "trn_everyday_seated"
        EverydayActivity.ON_FEET -> "trn_everyday_on_feet"
        EverydayActivity.PHYSICAL -> "trn_everyday_physical"
    }

    fun everydayCode(e: EverydayActivity) = when (e) {
        EverydayActivity.SEATED -> "seated"
        EverydayActivity.ON_FEET -> "onFeet"
        EverydayActivity.PHYSICAL -> "physical"
    }

    fun everyday(code: String): EverydayActivity? = EverydayActivity.entries.firstOrNull { everydayCode(it) == code }

    fun everydayChoices(p: AthleteProfile) = EverydayActivity.entries.map { NutritionChoiceUi(everydayCode(it), t(everydayKey(it)), p.everydayActivity == it) }

    /** The energy sheet for the shown day; [year] is the current year. */
    fun sheet(s: FuelState, year: Int): EnergySheetUi {
        val profile = s.profile
        val need = s.need
        val plan = s.plan.takeIf { s.isToday }
        val eaten = MacroTotals.of(s.entries).takeIf { it.entries > 0 }?.kcal
        val none = EnergyRowUi("", NutritionTexts.empty, NutritionTexts.empty, false)
        val ownWarning = profile.ownKcal?.let { kcal ->
            need?.let { n ->
                val own = OwnEnergyTarget(kcal, n.totalKcal, n.restingKcal)
                if (own.large) NutritionTexts.energyWarning(RedsSignal.LARGE_DEFICIT,
                    EnergyReport(signals = listOf(RedsSignal.LARGE_DEFICIT), ownTarget = own), profile) else null
            }
        }
        val planned = s.planned?.takeIf { s.isToday }?.let { NutritionTexts.plannedTraining(it) }
        val hasPlan = need != null && plan != null && plan.targetKcal != null
        return EnergySheetUi(
            needsWeight = s.bodyweightKg == null,
            massUnit = Units.massUnit(profile.units),
            needsHeight = s.heightCm == null,
            lengthUnit = Units.lengthUnit(profile.units),
            heightLabel = t("trn_height_label", str(Units.lengthUnit(profile.units))),
            hasNeed = need != null,
            resting = need?.let { kcalRow("resting", t("trn_resting"), it.restingKcal.toString()) } ?: none,
            everyday = need?.let { kcalRow("everyday", t("trn_everyday"), "+" + it.everydayKcal) } ?: none,
            everydayChoices = everydayChoices(profile),
            training = need?.let {
                if (it.trainingKcal > 0) kcalRow("training", t("trn_training"), "+" + it.trainingKcal)
                else EnergyRowUi("training", t("trn_training"), t("trn_no_training"), false)
            } ?: none,
            plannedTraining = planned?.let { t("trn_training_planned", msg(it)) } ?: NutritionTexts.empty,
            hasPlannedTraining = need != null && planned != null,
            need = need?.let { kcalRow("need", t("trn_need"), it.totalKcal.toString(), bold = true) } ?: none,
            hasPlan = hasPlan,
            deficit = if (hasPlan) kcalRow("deficit", t("trn_deficit_line", msg(NutritionTexts.mass(plan.paceKgPerWeek, profile.units))),
                "−" + plan.dailyDeficitKcal) else none,
            target = if (hasPlan) kcalRow("target", t("trn_target"), plan.targetKcal.toString(), bold = true) else none,
            planNotes = if (hasPlan) listOfNotNull(NutritionTexts.easedDeficit(plan, need.dayLoad, profile),
                NutritionTexts.weekPace(plan, profile)) else emptyList(),
            ownEnergy = ownTargetUi(FuelTargets.TargetKind.ENERGY, profile, NutritionTexts.energyTarget(need, plan), t("trn_target_heading")),
            ownWarning = ownWarning ?: NutritionTexts.empty,
            hasOwnWarning = ownWarning != null,
            hasEaten = need != null && eaten != null,
            eaten = eaten?.let { kcalRow("eaten", t("trn_eaten"), it.roundToInt().toString()) } ?: none,
            ageAssumed = need?.ageAssumed == true,
            ageText = t("trn_age_assumed", int(EnergyBalance.age(profile, year))),
            birthYearMin = year - 100,
            birthYearMax = year - 8,
            sexAssumed = need?.sexAssumed == true,
            sexChoices = listOf(Sex.FEMALE to "tra_sex_female", Sex.MALE to "tra_sex_male").map { (sex, key) ->
                NutritionChoiceUi(if (sex == Sex.FEMALE) "female" else "male", t(key), profile.sex == sex)
            },
            goalButtonKey = if (profile.goal == AthleteGoal.LOSE_WEIGHT) "trn_edit_goal" else "trn_set_goal",
        )
    }
}

class WeightGoalUi(
    val losing: Boolean,
    /** Under 18 first of all: adult thresholds do not fit a growing body. Said, never locked. */
    val youthWarning: Boolean,
    val needsWeight: Boolean,
    val massUnit: String,
    val targetText: String,
    val targetInvalid: Boolean,
    val pace: NutriText,
    val canDecrease: Boolean,
    val canIncrease: Boolean,
    val hasPlan: Boolean,
    val paceShare: NutriText,
    val targetReached: Boolean,
    val hasTargetKcal: Boolean,
    val targetToday: NutriText,
    val targetMath: NutriText,
    val planNotes: List<NutriText>,
    val hasWeeks: Boolean,
    val weeks: NutriText,
    val needsHeight: Boolean,
    val heightLabel: NutriText,
    val lengthUnit: String,
    val warnings: List<NutritionEnergyWarningUi>,
    val canStart: Boolean,
)

/**
 * "Lose weight" (Android `WeightGoalSheet`): optional target weight and the
 * pace in 0.1 kg steps, with the resulting calorie target and every warning
 * that applies – live while choosing, never blocking the choice. While the
 * goal runs every valid change is kept at once.
 */
class WeightGoalForm internal constructor(
    private val presenter: FuelPresenter,
    private val year: Int,
    private val comma: Boolean,
) {
    private val s: FuelState get() = presenter.state.value
    private var targetText: String
    private var pace: Double
    private var stopped = false
    private var handedOver: Triple<Boolean, Double?, Double>? = null

    init {
        val p = s.profile
        targetText = p.targetWeightKg?.let { NutritionFormat.number(Units.massToDisplay(it, p.units), 1, comma) } ?: ""
        pace = WeightPlan.clampPace(p.weeklyLossKg)
    }

    private val targetKg: Double? get() = NutritionFormat.parseDecimal(targetText)?.let { Units.massFromDisplay(it, s.profile.units) }
    private val targetValid: Boolean get() = targetText.isBlank() || (targetKg?.let { it in EnergyUi.PLAUSIBLE_WEIGHT_KG } == true)
    private val losing: Boolean get() = s.profile.goal == AthleteGoal.LOSE_WEIGHT

    val current: WeightGoalUi get() = ui()

    fun setTarget(text: String): WeightGoalUi { targetText = text; keep(); return ui() }
    fun decreasePace(): WeightGoalUi { pace = WeightPlan.clampPace(pace - WeightPlan.PACE_STEP_KG); keep(); return ui() }
    fun increasePace(): WeightGoalUi { pace = WeightPlan.clampPace(pace + WeightPlan.PACE_STEP_KG); keep(); return ui() }

    /** "Start": turns the goal on with the chosen target and pace. */
    fun start(): Boolean {
        if (losing || !targetValid || s.bodyweightKg == null) return false
        presenter.saveWeightGoal(true, targetKg?.takeIf { targetValid }, pace)
        return true
    }

    /** "Stop": the goal ends; target and pace stay for next time. */
    fun stop() {
        stopped = true
        val p = s.profile
        presenter.saveWeightGoal(false, p.targetWeightKg, p.weeklyLossKg)
    }

    /** While the goal runs, a valid change is kept; stopping ends that, so closing cannot restart it. */
    fun keep() {
        if (stopped || !losing || !targetValid || s.bodyweightKg == null) return
        val p = s.profile
        val choice = Triple(true, targetKg?.takeIf { targetValid }, pace)
        val changed = pace != WeightPlan.clampPace(p.weeklyLossKg) || !sameKg(choice.second, p.targetWeightKg)
        if (!changed || handedOver == choice) return
        handedOver = choice
        presenter.saveWeightGoal(choice.first, choice.second, choice.third)
    }

    /** Two optional weights are the same to the gram that matters (lb round trips). */
    private fun sameKg(a: Double?, b: Double?): Boolean = if (a == null || b == null) a == b else abs(a - b) < 0.05

    private fun ui(): WeightGoalUi {
        val state = s
        val profile = state.profile
        val units = profile.units
        // The pace's calorie target is today's: the sheet always plans from today.
        val need = state.need.takeIf { state.isToday }
        val plan = state.bodyweightKg?.let {
            WeightPlan.plan(pace, targetKg?.takeIf { targetValid }, it, state.heightCm, profile.sex, need, state.weekNeeds)
        }
        val share = plan?.let { num(it.paceShare * 100) }
        val preview = plan?.let { EnergyReport(signals = it.warnings, plan = it) }
        val targetKcal = plan?.targetKcal
        val needKcal = plan?.needKcal
        val showKcal = plan != null && !plan.targetReached && targetKcal != null && needKcal != null && profile.showCalories
        return WeightGoalUi(
            losing = losing,
            youthWarning = EnergyBalance.isMinor(profile, year),
            needsWeight = state.bodyweightKg == null,
            massUnit = Units.massUnit(units),
            targetText = targetText,
            targetInvalid = !targetValid,
            pace = t("trn_goal_pace_value", msg(NutritionTexts.mass(pace, units))),
            canDecrease = pace > WeightPlan.MIN_PACE_KG + 1e-9,
            canIncrease = pace < WeightPlan.MAX_PACE_KG - 1e-9,
            hasPlan = plan != null,
            paceShare = when {
                share == null -> NutritionTexts.empty
                profile.showCalories -> t("trn_goal_pace_share", share, int((pace * EnergyBalance.KCAL_PER_KG / 7).roundToInt()))
                else -> t("trn_goal_pace_share_plain", share)
            },
            targetReached = plan?.targetReached == true,
            hasTargetKcal = showKcal,
            targetToday = if (showKcal) t("trn_goal_target_today", int(targetKcal)) else NutritionTexts.empty,
            targetMath = if (showKcal) t("trn_goal_target_math", int(needKcal), int(plan.dailyDeficitKcal)) else NutritionTexts.empty,
            planNotes = if (plan != null && !plan.targetReached) listOfNotNull(
                need?.let { NutritionTexts.easedDeficit(plan, it.dayLoad, profile) }, NutritionTexts.weekPace(plan, profile),
            ) else emptyList(),
            hasWeeks = (plan?.weeksToTarget ?: 0) > 0,
            weeks = plan?.weeksToTarget?.takeIf { it > 0 }?.let { t("trn_goal_weeks", int(it)) } ?: NutritionTexts.empty,
            needsHeight = plan != null && state.heightCm == null,
            heightLabel = t("trn_height_label", str(Units.lengthUnit(units))),
            lengthUnit = Units.lengthUnit(units),
            warnings = plan?.warnings.orEmpty().map { NutritionEnergyWarningUi(EnergyUi.signalCode(it), NutritionTexts.energyWarning(it, preview!!, profile)) },
            canStart = !losing && targetValid && state.bodyweightKg != null,
        )
    }
}
