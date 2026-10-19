// The typed-meal part of androidApp/.../fuel/FoodPhotoViewModel.kt (0e1bad0c6,
// 0.2.4 review corrections e9e3069ce, b76427fba, 89c7b902f): a description
// becomes a checkable list through the shared rule parser and FoodMatcher, then
// food log entries; the installed model may read it again (onTextWithModel).
// MealPhotoScreenModel ends its photos in the same list.
package com.cruxcoach.app.nutrition

import com.cruxcoach.app.foodvision.MealTextVision
import com.cruxcoach.app.foodvision.VisionOutcome

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.FoodMatcher
import com.cruxcoach.athlete.logic.FoodVisionParser
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MealTextParser
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.round

/** One line of the review list. */
data class ReviewItem(
    val key: Int,
    /** What the parser read; null for foods the user added by hand. */
    val detected: DetectedFood?,
    val candidates: List<FoodMatcher.Match>,
    /** Nutrient source; null logs the entry without nutrients. */
    val choice: BlsFood?,
    val uncertain: Boolean,
    val amountText: String,
    val included: Boolean = true,
    /** Water goes to the hydration log instead of the food log. */
    val water: Boolean = false,
    /** Unit of [amountText]: g or oz for food, ml or fl oz for water. */
    val unit: FuelUnits.Amount = if (water) FuelUnits.Amount.ML else FuelUnits.Amount.G,
)

/** Where the review list came from (Android ReviewSource). */
enum class ReviewSource { PHOTO, TEXT_RULES, TEXT_MODEL }

sealed interface ReviewPhase {
    data object Idle : ReviewPhase
    data object Reading : ReviewPhase
    /** The on-device model reads the typed meal (Android PhotoPhase.Analyzing); wall-clock start. */
    data class Analyzing(val startedMs: Long) : ReviewPhase
    data class Review(
        val items: List<ReviewItem>,
        /** Meal named in a typed description ("zum Frühstück"). */
        val mealHint: Meal? = null,
        /** The typed description, kept for a second pass with the model. */
        val text: String? = null,
        val source: ReviewSource = ReviewSource.PHOTO,
        /** Seconds the model took; 0 for the rules. */
        val seconds: Int = 0,
    ) : ReviewPhase
    data object Saved : ReviewPhase
    data object Failed : ReviewPhase
    /**
     * A model run failed with no rules list to go back to (Android failFor):
     * [error] memory | app_memory | engine, [code] the engine's code.
     */
    data class VisionFailed(val error: String, val code: String) : ReviewPhase
}

class MealReviewPresenter(
    private val service: AthleteService,
    private val catalog: FoodCatalog,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val compute: CoroutineDispatcher,
    /** Formats an editable amount as Android's `inputText` does, in the app's decimal separator. */
    private val inputText: (Double, FuelUnits.Amount) -> String,
    private val clock: () -> Long = { nowMs() },
) {
    private val _phase = MutableStateFlow<ReviewPhase>(ReviewPhase.Idle)
    val phase: StateFlow<ReviewPhase> = _phase.asStateFlow()
    private var work: Job? = null
    private var saving: Job? = null
    private var nextKey = 0

    /** The athlete's unit system; the nutrition screen keeps it current. */
    var units: UnitSystem = UnitSystem.METRIC

    /**
     * Turns a typed description into the review list at once, without the
     * model: on typical sentences the rules beat the 2B model (which also
     * invents foods for vague input) and need no waiting. The model stays
     * available as a second opinion, see [onTextWithModel].
     */
    fun onText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        work?.cancel()
        work = scope.launch {
            _phase.value = ReviewPhase.Reading
            try {
                val matcher = catalog.bls.matcher()
                // The food table tells which words are foods, so a list typed without separators is split (2026-10-19).
                val parsed = withContext(compute) {
                    MealTextParser.parse(trimmed) { name -> matcher.match(DetectedFood(name, name, 100.0), 1).firstOrNull()?.score ?: 0.0 }
                }
                showReview(parsed.foods, parsed.meal, trimmed, ReviewSource.TEXT_RULES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _phase.value = ReviewPhase.Failed
            }
        }
    }

    /**
     * Asks the on-device model to read the description; keeps the rules'
     * list if it fails or finds nothing (Android onTextWithModel). The caller
     * offers this only while [MealTextVision.available]; [vision] checks again.
     */
    fun onTextWithModel(text: String, vision: MealTextVision) {
        val fallback = _phase.value as? ReviewPhase.Review
        work?.cancel()
        work = scope.launch {
            val started = clock()
            _phase.value = ReviewPhase.Analyzing(started)
            try {
                val hint = withContext(compute) { MealTextParser.parse(text).meal }
                val outcome = vision.read(text)
                val foods = if (outcome.ok) withContext(compute) { FoodVisionParser.parse(outcome.text) } else emptyList()
                if (foods.isEmpty() && fallback != null) {
                    _phase.value = fallback
                    return@launch
                }
                if (!outcome.ok) {
                    _phase.value = visionFailure(outcome)
                    return@launch
                }
                val seconds = ((clock() - started) / 1000).toInt()
                showReview(foods, hint, text, ReviewSource.TEXT_MODEL, seconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _phase.value = fallback ?: ReviewPhase.Failed
            }
        }
    }

    /** A model run that ended the app last time (MealTextVision.takeInterruptedRun). */
    fun reportVisionFailure(error: String) {
        _phase.value = ReviewPhase.VisionFailed(error, "")
    }

    private fun visionFailure(outcome: VisionOutcome): ReviewPhase.VisionFailed =
        if (outcome.error == MealTextVision.APP_MEMORY) {
            ReviewPhase.VisionFailed(MealTextVision.APP_MEMORY, "")
        } else {
            ReviewPhase.VisionFailed("engine", outcome.error ?: "unknown")
        }

    /** Matches the detected foods against BLS and opens the review list. */
    internal suspend fun showReview(
        detections: List<DetectedFood>,
        mealHint: Meal?,
        text: String?,
        source: ReviewSource = ReviewSource.PHOTO,
        seconds: Int = 0,
    ) {
        val matcher = catalog.bls.matcher()
        // Scoring 7,140 foods per detection is too much for the main thread.
        val items = withContext(compute) { detections.map { reviewItem(it, matcher) } }
        _phase.value = ReviewPhase.Review(items, mealHint, text, source, seconds)
    }

    fun cancel() {
        work?.cancel()
        work = null
        _phase.value = ReviewPhase.Idle
    }

    fun dismissError() { _phase.value = ReviewPhase.Idle }

    private fun reviewItem(food: DetectedFood, matcher: FoodMatcher): ReviewItem {
        if (FoodItems.isWater(food)) {
            val unit = FuelUnits.unitFor(units, drink = true)
            return ReviewItem(nextKey++, food, emptyList(), null, uncertain = false, amountText = inputText(food.grams, unit),
                water = true, unit = unit)
        }
        val candidates = matcher.match(food, limit = 6)
        val best = candidates.firstOrNull()
        val unit = FuelUnits.unitFor(units, drink = best?.food?.let { FuelUnits.isDrink("bls:${it.code}", it.nameEn) } ?: false)
        return ReviewItem(
            key = nextKey++, detected = food, candidates = candidates, choice = best?.food,
            uncertain = best == null || best.score < FoodMatcher.CONFIDENT_SCORE,
            amountText = inputText(food.grams, unit), unit = unit,
        )
    }

    private fun updateItem(key: Int, change: (ReviewItem) -> ReviewItem) {
        _phase.update { phase ->
            val review = phase as? ReviewPhase.Review ?: return@update phase
            review.copy(items = review.items.map { if (it.key == key) change(it) else it })
        }
    }

    fun setAmount(key: Int, text: String) = updateItem(key) { it.copy(amountText = text.take(6)) }

    /** "½", "¾", "1¼", "1½": the estimate is often a quarter off, so correcting it takes one tap (owner 2026-10-19). */
    fun scaleAmount(key: Int, factor: Double) = updateItem(key) { item ->
        val grams = amountOf(item) ?: return@updateItem item
        item.copy(amountText = inputText((grams * factor).coerceIn(1.0, FoodVisionParser.MAX_GRAMS), item.unit))
    }

    /** One piece more or less for a food people count: whole pieces, never fewer than one. */
    fun stepPieces(key: Int, delta: Int) = updateItem(key) { item ->
        val piece = pieceOf(item) ?: return@updateItem item
        val grams = amountOf(item) ?: piece
        // round(): ties to even, as on Android (2.5 pieces shows and steps from 2).
        val pieces = (round(grams / piece).toInt() + delta).coerceIn(1, MAX_PIECES)
        item.copy(amountText = inputText(pieces * piece, item.unit))
    }

    fun setIncluded(key: Int, included: Boolean) = updateItem(key) { it.copy(included = included) }

    /** Another food for a line; a drink switches the amount to ml/fl oz (and back), keeping the quantity. */
    fun choose(key: Int, food: BlsFood?) = updateItem(key) { item ->
        val unit = food?.let { FuelUnits.unitFor(units, FoodItems.isDrinkChoice(it)) } ?: item.unit
        val text = if (unit != item.unit) amountOf(item)?.let { inputText(it, unit) } ?: item.amountText else item.amountText
        item.copy(choice = food, uncertain = false, water = false, unit = unit, amountText = text)
    }

    /** Adds a food the parser missed. */
    fun addFood(food: BlsFood) {
        _phase.update { phase ->
            val review = phase as? ReviewPhase.Review ?: return@update phase
            val unit = FuelUnits.unitFor(units, FuelUnits.isDrink("bls:${food.code}", food.nameEn))
            val item = ReviewItem(nextKey++, null, emptyList(), food, uncertain = false, amountText = inputText(100.0, unit), unit = unit)
            review.copy(items = review.items + item)
        }
    }

    /** Writes every included line to [day]: food log entries, and water to hydration. */
    fun save(day: String, meal: Meal) {
        val review = _phase.value as? ReviewPhase.Review ?: return
        // A second tap while the first save is still writing would log the meal twice.
        if (saving?.isActive == true) return
        val lines = review.items.filter { it.included && amountOf(it) != null }
        val german = catalog.german()
        saving = scope.launch {
            try {
                withContext(io) {
                    service.ensureReady()
                    val repo = service.repo
                    val now = clock()
                    repo.transaction {
                        lines.forEachIndexed { i, line ->
                            val grams = amountOf(line) ?: return@forEachIndexed
                            if (line.water) {
                                repo.addHydration(day, grams.toInt())
                                return@forEachIndexed
                            }
                            val food = line.choice
                            val item = food?.let { blsItem(it, now, german) }?.also { repo.saveFoodItem(it) }
                            val f = grams / 100.0
                            repo.saveFoodLog(
                                FoodLogEntry(
                                    id = repo.newId(), day = day, loggedAt = now + i, meal = meal, foodItemId = item?.id,
                                    name = item?.name ?: line.detected?.let { FoodItems.displayName(it, german) }.orEmpty(),
                                    amountG = grams,
                                    kcal = food?.kcal?.times(f), proteinG = food?.protein?.times(f),
                                    carbsG = food?.carbs?.times(f), fatG = food?.fat?.times(f),
                                ),
                            )
                            item?.let { repo.markFoodItemUsed(it.id) }
                        }
                    }
                }
                _phase.value = ReviewPhase.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _phase.value = ReviewPhase.Failed
            }
        }
    }

    /**
     * The food as a reusable "my foods" entry (per 100 g), keeping favourite and use count; a product chosen in the
     * review is saved as that product ("off:" item).
     */
    private fun blsItem(food: BlsFood, now: Long, german: Boolean): FoodItem =
        if (FoodItems.isProduct(food)) (service.repo.foodItem(food.code) ?: FoodItems.productFoodItem(food, german)).copy(updatedAt = now)
        else (service.repo.foodItem("bls:${food.code}") ?: FoodItems.blsFoodItem(food, german)).copy(
            kcalPer100 = food.kcal, proteinPer100 = food.protein, carbsPer100 = food.carbs, fatPer100 = food.fat,
            updatedAt = now,
        )

    fun close() {
        work?.cancel()
        scope.cancel()
    }

    companion object {
        /** Most whole pieces one tap may reach (Android stepPieces). */
        const val MAX_PIECES = 20

        /** Grams (or ml for water) of a review line, whatever unit it is shown in. */
        fun amountOf(item: ReviewItem): Double? = FoodItems.amountOf(item.amountText, item.unit)

        /** Grams of one piece when the line's food is one people count ("Banane roh"); products are weighed. */
        fun pieceOf(item: ReviewItem): Double? =
            item.choice?.takeUnless { FoodItems.isProduct(it) || item.water }?.let { MealTextParser.pieceGramsOf(it.nameDe) }
    }
}
