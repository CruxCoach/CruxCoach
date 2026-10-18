package com.cruxcoach.app.ui

import com.cruxcoach.app.foodvision.MealTextVision
import com.cruxcoach.app.foodvision.VisionModelStore
import com.cruxcoach.app.nutrition.FoodCatalog
import com.cruxcoach.app.nutrition.FoodItems
import com.cruxcoach.app.nutrition.MealReviewPresenter
import com.cruxcoach.app.nutrition.ReviewItem
import com.cruxcoach.app.nutrition.ReviewPhase
import com.cruxcoach.app.nutrition.ReviewSource
import com.cruxcoach.app.ui.NutritionTexts.msg
import com.cruxcoach.app.ui.NutritionTexts.num
import com.cruxcoach.app.ui.NutritionTexts.t
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MealReviewRowUi(
    val key: Int,
    val title: String,
    val isWater: Boolean,
    /** A match below the confident score: "check". */
    val uncertain: Boolean,
    val hasChoice: Boolean,
    /** "P 3 · C 47 · F 6" for the amount; empty for water and for lines without a match. */
    val macros: NutriText,
    /** "Recognised as: …" when the matched food reads differently. */
    val detectedLine: NutriText,
    val hasDetectedLine: Boolean,
    val amountText: String,
    val amountInvalid: Boolean,
    val unitLabel: NutriText,
    val included: Boolean,
)

class FoodChoiceUi(val code: String, val title: String, val subtitle: NutriText)

class MealTextUi(
    /** input | reading | analyzing | review | saved | failed */
    val phase: String,
    val text: String,
    /** Where the review list came from: rules | model | photo; empty outside the review. */
    val source: String,
    /** Seconds the model took, for "Read by the AI model in %d s". */
    val reviewSeconds: Int,
    /**
     * Android's "Analyse with the AI model" under a rules list: only with the
     * installed, probed model on a phone that passed the device check.
     */
    val canAskModel: Boolean,
    /** Wall-clock start of "analyzing", for the elapsed seconds; 0 otherwise. */
    val phaseStartedMs: Long,
    /** For phase "failed": empty (the description or the save failed), or memory | app_memory | engine. */
    val error: String,
    /** The engine's code for error "engine". */
    val errorCode: String,
    val rows: List<MealReviewRowUi>,
    val mealCode: String,
    val saveCount: Int,
    /** "Log 2 entries" */
    val saveLabel: NutriText,
    /** The line whose food is being chosen, -1 none; -2 adds a food the parser missed. */
    val choosingKey: Int,
    val choiceQuery: String,
    val choices: List<FoodChoiceUi>,
    /** "No food" is offered when changing a line, not when adding one. */
    val allowNone: Boolean,
)

/**
 * "Describe a meal" (Android `FoodTextSheet` + the review list): a sentence
 * becomes a checkable list through the shared rule parser and the BLS
 * matcher – on every phone, offline, without a model. Where photos would be
 * recognised, the installed model may read the sentence again ([readWithModel]).
 */
class MealTextScreenModel internal constructor(
    private val presenter: MealReviewPresenter,
    private val catalog: FoodCatalog,
    private val scope: CoroutineScope,
    private val day: String,
    initialMeal: Meal,
    units: UnitSystem,
    /** The model's second pass; null for the photo's review list and where only the rules read. */
    private val vision: MealTextVision? = null,
) {
    private data class Local(
        val text: String = "",
        val meal: Meal,
        /** Whether the meal was chosen by hand; else the description's hint wins. */
        val mealChosen: Boolean = false,
        val choosingKey: Int = -1,
        val choiceQuery: String = "",
        val searchResults: List<BlsFood>? = null,
    )

    private val local = MutableStateFlow(Local(meal = initialMeal))
    private var searchJob: Job? = null

    init {
        presenter.units = units
        if (vision?.takeInterruptedRun() == true) presenter.reportVisionFailure("memory")
    }

    private val modelState: Flow<VisionModelStore.State> = vision?.state ?: flowOf(VisionModelStore.State.Missing)

    val currentState: MealTextUi
        get() = map(presenter.phase.value, local.value, vision?.state?.value ?: VisionModelStore.State.Missing)

    fun watch(onState: (MealTextUi) -> Unit): Subscription {
        val job = scope.launch {
            combine(presenter.phase, local, modelState) { p, l, s ->
                // A mapping bug keeps the last state instead of terminating the app.
                try { map(p, l, s) } catch (t: Exception) { println("MealTextScreenModel: ${t.message}"); null }
            }.collect { ui -> if (ui != null) onState(ui) }
        }
        return Subscription { job.cancel() }
    }

    /** Also frees the model's memory, like Android's onClose. */
    fun close() {
        presenter.close()
        vision?.release()
        scope.cancel()
    }

    fun setText(text: String) = local.update { it.copy(text = text.take(MAX_MEAL_CHARS)) }

    fun read() = presenter.onText(local.value.text)

    /**
     * Android "Analyse with the AI model" under the rules' list. Does nothing
     * unless [MealTextUi.canAskModel]: no model runs where it is not offered.
     */
    fun readWithModel() {
        val v = vision ?: return
        val review = presenter.phase.value as? ReviewPhase.Review ?: return
        val text = review.text ?: return
        if (!canAskModel(review, v.state.value)) return
        presenter.onTextWithModel(text, v)
    }

    /** Back to the text field (after an error, to change the description, or to stop the model). */
    fun edit() = presenter.cancel()

    fun setMeal(code: String) {
        NutritionFormat.meal(code)?.let { m -> local.update { it.copy(meal = m, mealChosen = true) } }
    }

    fun setAmount(key: Int, text: String) = presenter.setAmount(key, text)
    fun setIncluded(key: Int, included: Boolean) = presenter.setIncluded(key, included)

    /** Opens the food choice for a line ([key]) or, with -2, for a food to add. */
    fun beginChoice(key: Int) {
        searchJob?.cancel()
        local.update { it.copy(choosingKey = key, choiceQuery = "", searchResults = null) }
    }

    fun endChoice() {
        searchJob?.cancel()
        local.update { it.copy(choosingKey = -1, choiceQuery = "", searchResults = null) }
    }

    fun setChoiceQuery(text: String) {
        val query = text.take(60)
        local.update { it.copy(choiceQuery = query, searchResults = if (query.isBlank()) null else it.searchResults) }
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = scope.launch {
            delay(250)
            val found = try {
                catalog.search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            local.update { it.copy(searchResults = found) }
        }
    }

    /** [code] "" picks "no food" (the line is logged without nutrients). */
    fun pick(code: String) {
        val l = local.value
        val food = choices(presenter.phase.value, l).firstOrNull { it.code == code }
        when {
            l.choosingKey == -2 && food != null -> presenter.addFood(food)
            l.choosingKey >= 0 -> presenter.choose(l.choosingKey, food)
        }
        endChoice()
    }

    fun save() {
        val phase = presenter.phase.value as? ReviewPhase.Review ?: return
        presenter.save(day, meal(phase, local.value))
    }

    private fun meal(phase: ReviewPhase?, l: Local): Meal =
        if (l.mealChosen) l.meal else (phase as? ReviewPhase.Review)?.mealHint ?: l.meal

    private fun choices(phase: ReviewPhase, l: Local): List<BlsFood> {
        l.searchResults?.let { return it }
        if (l.choosingKey < 0) return emptyList()
        val item = (phase as? ReviewPhase.Review)?.items?.firstOrNull { it.key == l.choosingKey } ?: return emptyList()
        return item.candidates.map { it.food }
    }

    private fun row(item: ReviewItem, german: Boolean): MealReviewRowUi {
        val choice = item.choice
        val title = when {
            item.water -> null
            choice != null -> FoodItems.blsName(choice, german)
            else -> item.detected?.let { FoodItems.displayName(it, german) }.orEmpty()
        }
        val grams = MealReviewPresenter.amountOf(item)
        val macros = if (choice != null && grams != null && !item.water) {
            val f = grams / 100.0
            t("trf_entry_macros", num(choice.protein * f), num(choice.carbs * f), num(choice.fat * f))
        } else null
        val detected = item.detected?.let { d ->
            val shown = FoodItems.displayName(d, german)
            if (choice != null && title != null && !title.equals(shown, ignoreCase = true)) t("fvp_detected_as", NutritionTexts.str(shown)) else null
        }
        return MealReviewRowUi(
            key = item.key,
            title = title.orEmpty(),
            isWater = item.water,
            uncertain = item.uncertain && choice != null,
            hasChoice = choice != null,
            macros = macros ?: NutritionTexts.empty,
            detectedLine = detected ?: NutritionTexts.empty,
            hasDetectedLine = detected != null,
            amountText = item.amountText,
            amountInvalid = grams == null,
            unitLabel = NutritionTexts.unitLabel(item.unit),
            included = item.included,
        )
    }

    private fun canAskModel(review: ReviewPhase.Review?, s: VisionModelStore.State): Boolean =
        review != null && review.source == ReviewSource.TEXT_RULES && review.text != null && vision?.available(s) == true

    private fun map(phase: ReviewPhase, l: Local, s: VisionModelStore.State): MealTextUi {
        val german = catalog.german()
        val review = phase as? ReviewPhase.Review
        val failed = phase as? ReviewPhase.VisionFailed
        val count = review?.items?.count { it.included && MealReviewPresenter.amountOf(it) != null } ?: 0
        return MealTextUi(
            phase = when (phase) {
                ReviewPhase.Idle -> "input"
                ReviewPhase.Reading -> "reading"
                is ReviewPhase.Analyzing -> "analyzing"
                is ReviewPhase.Review -> "review"
                ReviewPhase.Saved -> "saved"
                ReviewPhase.Failed, is ReviewPhase.VisionFailed -> "failed"
            },
            text = l.text,
            source = when (review?.source) {
                ReviewSource.TEXT_RULES -> "rules"
                ReviewSource.TEXT_MODEL -> "model"
                ReviewSource.PHOTO -> "photo"
                null -> ""
            },
            reviewSeconds = review?.seconds ?: 0,
            canAskModel = canAskModel(review, s),
            phaseStartedMs = (phase as? ReviewPhase.Analyzing)?.startedMs ?: 0L,
            error = failed?.error.orEmpty(),
            errorCode = failed?.code.orEmpty(),
            rows = review?.items.orEmpty().map { row(it, german) },
            mealCode = NutritionFormat.mealCode(meal(phase, l)),
            saveCount = count,
            saveLabel = NutritionTexts.plural("fvp_save", count, NutritionTexts.int(count)),
            choosingKey = l.choosingKey,
            choiceQuery = l.choiceQuery,
            choices = choices(phase, l).map { food ->
                FoodChoiceUi(food.code, FoodItems.blsName(food, german),
                    t("fvp_per_100", num(food.kcal), num(food.protein), num(food.carbs), num(food.fat)))
            },
            allowNone = l.choosingKey >= 0,
        )
    }

    private companion object {
        const val MAX_MEAL_CHARS = MealTextVision.MAX_MEAL_CHARS
    }
}
