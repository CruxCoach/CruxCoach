package com.cruxcoach.app.ui

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.coach.WeeklyReviewPresenter
import com.cruxcoach.app.coach.WeeklyReviewRunState
import com.cruxcoach.app.nutrition.FoodCatalog
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.logic.MicroWatch
import com.cruxcoach.athlete.logic.PersonalRecord
import com.cruxcoach.athlete.logic.RecordKind
import com.cruxcoach.athlete.model.AthleteProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A new personal best of the week: the exercise and what was beaten ("42.5 kg", "estimated 1RM 80 kg"). */
class WeeklyReviewRecordUi(val slug: String, val name: String, val text: TrText)

/** One row with a label and a value: sets by area ("Finger · 12"), check-in averages ("Sleep · 3.5"). */
class WeeklyReviewLineUi(val code: String, val label: TrText, val value: TrText)

class WeeklyReviewScreenState(
    val loading: Boolean,
    /** 0 = this week, 1 = last week …; "next week" is possible while above 0. */
    val weekOffset: Int,
    val canGoNext: Boolean,
    /** "Week of Oct 12, 2026". */
    val weekTitle: TrText,
    val goalReached: Boolean,
    /** "Weekly goal reached: 3 of 3 training days." / "2 of 3 training days – rest is training too." */
    val goalText: TrText,
    val hasPausedDays: Boolean,
    val pausedText: TrText,
    val climbDays: Int,
    /** Climbing minutes under the climbing-days tile. */
    val climbMinutes: TrText,
    val workoutDays: Int,
    val workoutMinutes: TrText,
    /** Work sets, warm-ups not counted. */
    val sets: Int,
    val records: List<WeeklyReviewRecordUi>,
    /** "Finger: 3 days" per load structure with any day. */
    val domainDays: List<TrText>,
    val categories: List<WeeklyReviewLineUi>,
    /** The body section: only with the body area on and a trend at both ends of the week. */
    val hasWeight: Boolean,
    val weightText: TrText,
    /** The energy guard of the current week (Android `EnergyCareCard` without the goal button). */
    val energyCard: NutritionEnergyCardUi,
    /** The nutrition section: only with food logged this week. */
    val hasFood: Boolean,
    val foodDaysText: TrText,
    val proteinText: TrText,
    val hasCarbs: Boolean,
    val carbsText: TrText,
    val hasFat: Boolean,
    val fatText: TrText,
    /** Only when calories are shown. */
    val hasEnergy: Boolean,
    val energyText: TrText,
    val micros: MicroWeekUi,
    val checkins: List<WeeklyReviewLineUi>,
)

/**
 * The weekly review (Android `WeeklyReviewScreen`): previous and next week, the weekly goal,
 * climbing and training days, work sets, new bests, load per structure, sets by area, the weight
 * trend, protein, carbohydrate, fat and energy against the week's targets, the energy guard,
 * micronutrients and check-in averages.
 */
class WeeklyReviewScreenModel internal constructor(
    private val presenter: WeeklyReviewPresenter,
    /** Exercise names in the catalogue language: "de" or "en". */
    private val language: String,
) {
    val currentState: WeeklyReviewScreenState get() = map(presenter.state.value)

    fun watch(onState: (WeeklyReviewScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun previous() = presenter.previous()

    fun next() = presenter.next()

    fun close() = presenter.close()

    internal companion object {
        /** AppCore's factory: the micronutrient week comes from the food tables when they opened ([foods] non-null). */
        fun create(service: AthleteService, foods: FoodCatalog?, language: String, scope: CoroutineScope, io: CoroutineDispatcher) =
            WeeklyReviewScreenModel(WeeklyReviewPresenter(service, scope, io, foods?.let { f -> f::microWeek }), language)
    }

    private var lastGood: WeeklyReviewScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: WeeklyReviewRunState): WeeklyReviewScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(WeeklyReviewRunState())
    }

    private fun mapUnsafe(s: WeeklyReviewRunState): WeeklyReviewScreenState {
        val p = s.profile
        val goal = p.weeklyGoal
        val reached = s.trainingDays >= goal
        val weight = s.weightChangeKg
        val protein = s.proteinAvg
        return WeeklyReviewScreenState(
            loading = s.loading,
            weekOffset = s.weekOffset,
            canGoNext = s.weekOffset > 0,
            weekTitle = s.weekStart?.let { Tx.t("tra_review_week_of", Tx.date(it.toString())) } ?: Tx.EMPTY,
            goalReached = reached,
            goalText = Tx.t(if (reached) "tra_review_goal_reached" else "tra_review_goal_open", Tx.i(s.trainingDays), Tx.i(goal)),
            hasPausedDays = s.pausedDays > 0,
            pausedText = if (s.pausedDays > 0) Tx.p("tra_review_paused", s.pausedDays, Tx.i(s.pausedDays)) else Tx.EMPTY,
            climbDays = s.climbDays,
            climbMinutes = Tx.minutes(s.boardMinutes),
            workoutDays = s.workoutDays,
            workoutMinutes = Tx.minutes(s.workoutMinutes),
            sets = s.sets,
            records = s.records.map { (def, pr) -> WeeklyReviewRecordUi(def.slug, def.name(language), recordText(pr, p)) },
            domainDays = s.domainDays.map { (d, n) -> Tx.p("tra_review_domain_days", n, Tx.k(Tx.domainKey(d)), Tx.i(n)) },
            categories = s.setsByCategory.map { (c, n) -> WeeklyReviewLineUi(c.name, Tx.t(Tx.categoryKey(c)), Tx.plain("$n")) },
            hasWeight = p.bodyEnabled && weight != null,
            weightText = when {
                !p.bodyEnabled || weight == null -> Tx.EMPTY
                p.hideBodyNumbers -> Tx.t(when {
                    weight > 0.15 -> "tra_review_weight_up"
                    weight < -0.15 -> "tra_review_weight_down"
                    else -> "tra_review_weight_stable"
                })
                else -> Tx.t("tra_review_weight_change", Tx.x(Tx.mass(weight, p.units, signed = true)))
            },
            energyCard = EnergyUi.card(s.energy, p, canOpenGoal = false),
            hasFood = protein != null,
            foodDaysText = Tx.p("tra_review_food_days", s.foodDays, Tx.i(s.foodDays)),
            // Without a body weight there is no target to name.
            proteinText = when {
                protein == null -> Tx.EMPTY
                s.proteinTarget != null -> Tx.t("tra_review_protein", Tx.i(protein.roundToInt()), Tx.i(s.proteinTarget))
                else -> Tx.t("tra_review_protein_no_target", Tx.i(protein.roundToInt()))
            },
            hasCarbs = s.carbsAvg != null && s.carbsTargetAvg != null,
            carbsText = if (s.carbsAvg != null && s.carbsTargetAvg != null) {
                Tx.t("tra_review_carbs", Tx.i(s.carbsAvg.roundToInt()), Tx.i(s.carbsTargetAvg))
            } else Tx.EMPTY,
            // Fat like the fourth ring in Nutrition: grams and the share of energy the 20–35 % guideline talks about.
            hasFat = s.fatAvg != null && s.fatShare != null,
            fatText = if (s.fatAvg != null && s.fatShare != null) {
                Tx.t("tra_review_fat", Tx.i(s.fatAvg.roundToInt()), Tx.i((s.fatShare * 100).roundToInt()))
            } else Tx.EMPTY,
            hasEnergy = protein != null && p.showCalories && s.kcalAvg != null,
            energyText = energyText(s),
            micros = micros(if (protein != null) s.micros else null),
            checkins = s.checkinAverages.map { (key, value) -> WeeklyReviewLineUi(key, Tx.t(key), number(value)) },
        )
    }

    /** Against the calorie target where it is not the need (losing weight, an own target), as in Nutrition. */
    private fun energyText(s: WeeklyReviewRunState): TrText {
        val avg = s.kcalAvg ?: return Tx.EMPTY
        if (!s.profile.showCalories) return Tx.EMPTY
        val eaten = avg.roundToInt()
        val need = s.kcalNeedAvg
        val target = s.kcalTargetAvg
        return when {
            target != null && need != null && target != need -> Tx.t("trn_review_energy_target", Tx.i(eaten), Tx.i(target), Tx.i(need))
            target != null && need == null -> Tx.t("trn_review_energy_target_only", Tx.i(eaten), Tx.i(target))
            need != null -> Tx.t("trn_review_energy", Tx.i(eaten), Tx.i(need))
            else -> Tx.t("trn_review_energy_no_need", Tx.i(eaten))
        }
    }

    private fun recordText(pr: PersonalRecord, profile: AthleteProfile): TrText = when (pr.kind) {
        RecordKind.LOAD -> Tx.mass(pr.value, profile.units)
        RecordKind.ESTIMATED_MAX -> Tx.t("tra_review_e1rm", Tx.x(Tx.mass(pr.value, profile.units)))
        RecordKind.REPS -> Tx.p("tra_reps", pr.value.roundToInt(), Tx.i(pr.value.roundToInt()))
        RecordKind.DURATION -> Tx.seconds(pr.value.roundToInt())
    }

    /** Android `formatNumber(value)`: one decimal at most, in the device's decimal separator (Swift formats it). */
    private fun number(value: Double) = TrText("", listOf(Tx.n(value, 1)), -1, "")

    /** The week's iron, calcium and vitamin D next to EFSA reference values (as the nutrition tab maps them). */
    private fun micros(summary: MicroWatch.Summary?): MicroWeekUi {
        if (summary == null) return MicroWeekUi(false, emptyList(), NutritionTexts.empty)
        return MicroWeekUi(
            visible = true,
            items = summary.items.map { item ->
                val (code, label, unit) = when (item.nutrient) {
                    MicroWatch.Nutrient.IRON -> Triple("iron", "trf_micro_iron", "mg")
                    MicroWatch.Nutrient.CALCIUM -> Triple("calcium", "trf_micro_calcium", "mg")
                    MicroWatch.Nutrient.VITAMIN_D -> Triple("vitaminD", "trf_micro_vitamin_d", "µg")
                }
                MicroItemUi(
                    code = code, label = NutritionTexts.t(label),
                    value = NutritionTexts.t("trf_micro_value", NutritionTexts.num(item.perDay, if (item.reference < 100) 1 else 0),
                        NutritionTexts.num(item.reference, 0), NutritionTexts.str(unit)),
                    progress = if (item.reference > 0) (item.perDay / item.reference).coerceIn(0.0, 1.0) else 0.0,
                )
            },
            coverage = NutritionTexts.plural("trf_micro_coverage", summary.loggedDays,
                NutritionTexts.int((summary.coverage * 100).roundToInt()), NutritionTexts.int(summary.loggedDays)),
        )
    }
}
