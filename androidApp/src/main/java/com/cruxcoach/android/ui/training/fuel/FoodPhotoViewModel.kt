package com.cruxcoach.android.ui.training.fuel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.FoodVisionClient
import com.cruxcoach.android.foodvision.PhotoInput
import com.cruxcoach.android.foodvision.PreparedImage
import com.cruxcoach.android.foodvision.VisionModel
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.foodvision.VisionModels
import com.cruxcoach.android.foodvision.VisionOutcome
import com.cruxcoach.android.foodvision.VisionPrompt
import com.cruxcoach.android.foodvision.VisionRequest
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.DeviceFacts
import com.cruxcoach.athlete.logic.FoodMatcher
import com.cruxcoach.athlete.logic.FoodVisionParser
import com.cruxcoach.athlete.logic.MealTextParser
import com.cruxcoach.athlete.logic.VisionCapability
import com.cruxcoach.athlete.logic.VisionSupport
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

/** One line of the review list after a photo. */
data class ReviewItem(
    val key: Int,
    /** What the model said; null for foods the user added by hand. */
    val detected: DetectedFood?,
    val candidates: List<FoodMatcher.Match>,
    /** Nutrient source; null logs the entry without nutrients. */
    val choice: BlsFood?,
    val uncertain: Boolean,
    val amountText: String,
    val included: Boolean = true,
    /** Water goes to the hydration log instead of the food log. */
    val water: Boolean = false,
)

sealed interface PhotoPhase {
    data object Idle : PhotoPhase
    data class Probing(val startedAt: Long) : PhotoPhase
    data class Analyzing(val startedAt: Long, val preview: Bitmap?, val estimateMs: Long?) : PhotoPhase
    data class Review(
        val preview: Bitmap?,
        val items: List<ReviewItem>,
        val seconds: Int,
        val source: ReviewSource = ReviewSource.PHOTO,
        /** Meal named in a typed description ("zum Frühstück"). */
        val mealHint: Meal? = null,
        /** The typed description, kept for a second pass with the model. */
        val text: String? = null,
    ) : PhotoPhase
    data class Failed(val error: PhotoError) : PhotoPhase
    data object Saved : PhotoPhase
}

/** Where the review list came from. */
enum class ReviewSource { PHOTO, TEXT_RULES, TEXT_MODEL }

sealed interface PhotoError {
    data object OutOfMemory : PhotoError
    data object Image : PhotoError
    data object Download : PhotoError
    data object Corrupt : PhotoError
    data class Space(val neededBytes: Long, val freeBytes: Long) : PhotoError
    data object NoStorage : PhotoError
    data class Engine(val code: String) : PhotoError
}

data class FoodPhotoState(
    val facts: DeviceFacts,
    val support: VisionSupport,
    val model: VisionModelStore.State = VisionModelStore.State.Missing,
    val phase: PhotoPhase = PhotoPhase.Idle,
) {
    /** The model this device would download. */
    val target: VisionModel? get() = (support as? VisionSupport.Supported)?.let { VisionModels.forTier(it.tier) }

    val tooSlow: Boolean
        get() = (model as? VisionModelStore.State.Ready)?.probe?.verdict == VisionCapability.ProbeVerdict.TOO_SLOW

    val canTakePhotos: Boolean
        get() = support is VisionSupport.Supported && model is VisionModelStore.State.Ready &&
            model.probe != null && !tooSlow
}

/**
 * Photo → foods → BLS nutrients → review → food log (FEAT-069). Everything
 * runs on the device; the only network traffic is the one-time model
 * download the user starts.
 */
@HiltViewModel
class FoodPhotoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: AthleteService,
    private val store: VisionModelStore,
    private val bls: BlsRepository,
    factsReader: DeviceFactsReader,
) : ViewModel() {

    private val facts = factsReader.read()
    private val _state = MutableStateFlow(FoodPhotoState(facts, VisionCapability.assess(facts), store.state.value))
    val state: StateFlow<FoodPhotoState> = _state.asStateFlow()

    private val client = FoodVisionClient(context)
    private var prompt: VisionPrompt? = null
    private var work: Job? = null
    private var poller: Job? = null
    private var nextKey = 0

    init {
        viewModelScope.launch { store.state.collect { s -> _state.update { it.copy(model = s) } } }
        if (store.state.value is VisionModelStore.State.Downloading) pollDownload()
    }

    // ── Setup ────────────────────────────────────────────────────────

    fun startDownload(allowMobile: Boolean) {
        val model = _state.value.target ?: return
        viewModelScope.launch {
            when (val r = store.start(model, allowMobile)) {
                VisionModelStore.StartResult.Started -> pollDownload()
                is VisionModelStore.StartResult.NotEnoughSpace -> fail(PhotoError.Space(r.neededBytes, r.freeBytes))
                VisionModelStore.StartResult.NoStorage -> fail(PhotoError.NoStorage)
            }
        }
    }

    private fun pollDownload() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (true) {
                when (val s = store.refresh()) {
                    is VisionModelStore.State.Downloading -> delay(1000)
                    is VisionModelStore.State.Ready -> { if (s.probe == null) probe(); break }
                    is VisionModelStore.State.Failed -> {
                        fail(if (s.reason == VisionModelStore.Reason.HASH_MISMATCH) PhotoError.Corrupt else PhotoError.Download)
                        break
                    }
                    else -> break
                }
            }
        }
    }

    /** Called when the sheet opens: resumes polling and runs a missing probe. */
    fun onOpen() {
        when (val s = store.state.value) {
            is VisionModelStore.State.Downloading -> pollDownload()
            is VisionModelStore.State.Ready -> if (s.probe == null && _state.value.phase is PhotoPhase.Idle) probe()
            else -> Unit
        }
    }

    /**
     * Times one run on a drawn plate and extrapolates a real photo. Devices
     * that pass the static checks but throttle hard end up TOO_SLOW here.
     */
    fun probe() {
        val ready = store.state.value as? VisionModelStore.State.Ready ?: return
        work?.cancel()
        work = viewModelScope.launch {
            _state.update { it.copy(phase = PhotoPhase.Probing(SystemClock.elapsedRealtime())) }
            val image = withContext(Dispatchers.IO) { PhotoInput.probeImage(context) }
            val outcome = run(ready.model, image, maxTokens = PROBE_TOKENS)
            image.rgbFile.delete()
            if (!outcome.ok) return@launch failFor(outcome)
            val estimate = VisionCapability.estimateFullRunMs(outcome.imageMs, outcome.generateMs, outcome.outputTokens)
            store.saveProbe(ready.model, VisionModelStore.Probe(estimate, VisionCapability.verdict(estimate)))
            _state.update { it.copy(phase = PhotoPhase.Idle) }
        }
    }

    fun removeModel() {
        work?.cancel()
        poller?.cancel()
        client.release()
        viewModelScope.launch {
            store.remove()
            _state.update { it.copy(phase = PhotoPhase.Idle) }
        }
    }

    // ── Photo ────────────────────────────────────────────────────────

    fun onPhoto(uri: Uri, deleteAfter: File? = null) {
        val ready = store.state.value as? VisionModelStore.State.Ready ?: return
        work?.cancel()
        work = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            _state.update { it.copy(phase = PhotoPhase.Analyzing(started, null, ready.probe?.estimatedMs)) }
            val image = withContext(Dispatchers.IO) {
                runCatching { PhotoInput.prepare(context, uri) }.getOrNull().also { deleteAfter?.delete() }
            } ?: return@launch fail(PhotoError.Image)
            _state.update { it.copy(phase = PhotoPhase.Analyzing(started, image.preview, ready.probe?.estimatedMs)) }
            val outcome = run(ready.model, image, maxTokens = null)
            withContext(Dispatchers.IO) { image.rgbFile.delete() }
            if (!outcome.ok) return@launch failFor(outcome)
            val seconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
            showReview(image.preview, FoodVisionParser.parse(outcome.text), seconds)
        }
    }

    /** Matches the detected foods against BLS and opens the review list. */
    internal suspend fun showReview(
        preview: Bitmap?,
        detections: List<DetectedFood>,
        seconds: Int,
        source: ReviewSource = ReviewSource.PHOTO,
        mealHint: Meal? = null,
        text: String? = null,
    ) {
        val matcher = bls.matcher()
        // Scoring 7,140 foods per detection is too much for the main thread.
        val items = withContext(Dispatchers.Default) { detections.map { reviewItem(it, matcher) } }
        _state.update { it.copy(phase = PhotoPhase.Review(preview, items, seconds, source, mealHint, text)) }
    }

    fun cancel() {
        work?.cancel()
        work = null
        _state.update { it.copy(phase = PhotoPhase.Idle) }
    }

    // ── Typed meal ───────────────────────────────────────────────────

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
        work = viewModelScope.launch {
            val parsed = withContext(Dispatchers.Default) { MealTextParser.parse(trimmed) }
            showReview(null, parsed.foods, seconds = 0, source = ReviewSource.TEXT_RULES, mealHint = parsed.meal, text = trimmed)
        }
    }

    /** True when the installed model may be asked to read a typed meal. */
    val modelAvailable: Boolean get() = _state.value.canTakePhotos

    /** Asks the on-device model to read the description; keeps the rules' list if it fails or finds nothing. */
    fun onTextWithModel(text: String) {
        val ready = store.state.value as? VisionModelStore.State.Ready ?: return
        val fallback = _state.value.phase as? PhotoPhase.Review
        val hint = MealTextParser.parse(text).meal
        work?.cancel()
        work = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            _state.update { it.copy(phase = PhotoPhase.Analyzing(started, null, null)) }
            val p = prompt()
            val outcome = run(ready.model, null, p.textSystem, p.forMeal(text), p.grammar, maxTokens = null)
            val foods = if (outcome.ok) FoodVisionParser.parse(outcome.text) else emptyList()
            if (foods.isEmpty() && fallback != null) {
                _state.update { it.copy(phase = fallback) }
                return@launch
            }
            if (!outcome.ok) return@launch failFor(outcome)
            val seconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
            showReview(null, foods, seconds, source = ReviewSource.TEXT_MODEL, mealHint = hint, text = text)
        }
    }

    private suspend fun prompt(): VisionPrompt =
        prompt ?: withContext(Dispatchers.IO) { VisionPrompt.load(context) }.also { prompt = it }

    /** A photo run; cancelling the calling coroutine also stops the native run. */
    private suspend fun run(model: VisionModel, image: PreparedImage, maxTokens: Int?): VisionOutcome {
        val p = prompt()
        return run(model, image, p.system, p.user, p.grammar, maxTokens)
    }

    private suspend fun run(
        model: VisionModel, image: PreparedImage?, system: String, user: String, grammar: String, maxTokens: Int?,
    ): VisionOutcome {
        val weights = store.fileFor(model.weights)?.absolutePath ?: return VisionOutcome(false, "no_storage", "")
        val projector = store.fileFor(model.projector)?.absolutePath ?: return VisionOutcome(false, "no_storage", "")
        val request = VisionRequest(model, weights, projector, image, system, user, grammar)
        return client.analyze(if (maxTokens != null) request.copy(maxTokens = maxTokens) else request)
    }

    private fun failFor(outcome: VisionOutcome) {
        fail(if (outcome.error == VisionOutcome.PROCESS_DIED) PhotoError.OutOfMemory else PhotoError.Engine(outcome.error ?: "unknown"))
    }

    private fun fail(error: PhotoError) {
        _state.update { it.copy(phase = PhotoPhase.Failed(error)) }
    }

    fun dismissError() {
        _state.update { it.copy(phase = PhotoPhase.Idle) }
    }

    // ── Review ───────────────────────────────────────────────────────

    private fun reviewItem(food: DetectedFood, matcher: FoodMatcher): ReviewItem {
        if (isWater(food)) {
            return ReviewItem(nextKey++, food, emptyList(), null, uncertain = false, amountText = amount(food.grams), water = true)
        }
        val candidates = matcher.match(food, limit = 6)
        val best = candidates.firstOrNull()
        return ReviewItem(
            key = nextKey++, detected = food, candidates = candidates, choice = best?.food,
            uncertain = best == null || best.score < FoodMatcher.CONFIDENT_SCORE,
            amountText = amount(food.grams),
        )
    }

    private fun updateItem(key: Int, change: (ReviewItem) -> ReviewItem) {
        _state.update { s ->
            val review = s.phase as? PhotoPhase.Review ?: return@update s
            s.copy(phase = review.copy(items = review.items.map { if (it.key == key) change(it) else it }))
        }
    }

    fun setAmount(key: Int, text: String) = updateItem(key) { it.copy(amountText = text.take(6)) }
    fun setIncluded(key: Int, included: Boolean) = updateItem(key) { it.copy(included = included) }
    fun choose(key: Int, food: BlsFood?) = updateItem(key) { it.copy(choice = food, uncertain = false, water = false) }

    /** Adds a food the model missed. */
    fun addFood(food: BlsFood) {
        _state.update { s ->
            val review = s.phase as? PhotoPhase.Review ?: return@update s
            val item = ReviewItem(nextKey++, null, emptyList(), food, uncertain = false, amountText = "100")
            s.copy(phase = review.copy(items = review.items + item))
        }
    }

    suspend fun search(query: String): List<BlsFood> {
        val matcher = bls.matcher()
        return withContext(Dispatchers.Default) { matcher.search(query, limit = 25).map { it.food } }
    }

    /** Writes every included line to [day]: food log entries, and water to hydration. */
    fun save(day: String, meal: Meal) {
        val review = _state.value.phase as? PhotoPhase.Review ?: return
        val lines = review.items.filter { it.included && amountOf(it) != null }
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val now = System.currentTimeMillis()
            repo.transaction {
                lines.forEachIndexed { i, line ->
                    val grams = amountOf(line) ?: return@forEachIndexed
                    if (line.water) {
                        repo.addHydration(day, grams.toInt())
                        return@forEachIndexed
                    }
                    val food = line.choice
                    val item = food?.let { blsItem(it, now) }?.also { repo.saveFoodItem(it) }
                    val f = grams / 100.0
                    repo.saveFoodLog(
                        FoodLogEntry(
                            id = repo.newId(), day = day, loggedAt = now + i, meal = meal, foodItemId = item?.id,
                            name = item?.name ?: line.detected?.let { displayName(it) }.orEmpty(),
                            amountG = grams,
                            kcal = food?.kcal?.times(f), proteinG = food?.protein?.times(f),
                            carbsG = food?.carbs?.times(f), fatG = food?.fat?.times(f),
                        ),
                    )
                    item?.let { repo.markFoodItemUsed(it.id) }
                }
            }
            _state.update { it.copy(phase = PhotoPhase.Saved) }
        }
    }

    /** The food as a reusable "my foods" entry (per 100 g), keeping favourite and use count. */
    private fun blsItem(food: BlsFood, now: Long): FoodItem =
        (service.repo.foodItem("bls:${food.code}") ?: blsFoodItem(food)).copy(
            kcalPer100 = food.kcal, proteinPer100 = food.protein, carbsPer100 = food.carbs, fatPer100 = food.fat,
            updatedAt = now,
        )

    /** Called when the sheet closes: frees the ":vision" process and its memory. */
    fun onClose() {
        work?.cancel()
        work = null
        client.release()
        _state.update { it.copy(phase = PhotoPhase.Idle) }
    }

    override fun onCleared() {
        client.release()
    }

    companion object {
        /** Enough answer tokens to time generation without waiting for a full list. */
        const val PROBE_TOKENS = 32

        private val WATER = setOf("wasser", "water", "mineralwasser", "leitungswasser", "sprudelwasser", "mineral water",
            "sparkling water", "tap water", "trinkwasser", "drinking water")

        fun isWater(food: DetectedFood) =
            food.nameDe.trim().lowercase(Locale.ROOT) in WATER || food.nameEn.trim().lowercase(Locale.ROOT) in WATER

        private fun german() = Locale.getDefault().language == "de"

        fun blsName(food: BlsFood): String = if (german()) food.nameDe else food.nameEn

        /** A BLS food as an (unsaved) per-100 g food item; saved on first use. */
        fun blsFoodItem(food: BlsFood) = FoodItem(
            id = "bls:${food.code}", name = blsName(food), source = BlsRepository.SOURCE,
            kcalPer100 = food.kcal, proteinPer100 = food.protein, carbsPer100 = food.carbs, fatPer100 = food.fat,
        )

        fun displayName(food: DetectedFood): String = if (german()) food.nameDe else food.nameEn

        fun amount(grams: Double): String = grams.toInt().toString()

        fun amountOf(item: ReviewItem): Double? =
            item.amountText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= FoodVisionParser.MAX_GRAMS }
    }
}
