package com.cruxcoach.app.ui

import com.cruxcoach.app.nutrition.FoodCatalog
import com.cruxcoach.app.nutrition.FoodItems
import com.cruxcoach.app.nutrition.FoodSearchPresenter
import com.cruxcoach.app.nutrition.FuelPresenter
import com.cruxcoach.app.nutrition.FuelRules
import com.cruxcoach.app.nutrition.FuelState
import com.cruxcoach.app.nutrition.OffProductStore
import com.cruxcoach.app.ui.NutritionTexts.int
import com.cruxcoach.app.ui.NutritionTexts.join
import com.cruxcoach.app.ui.NutritionTexts.literal
import com.cruxcoach.app.ui.NutritionTexts.msg
import com.cruxcoach.app.ui.NutritionTexts.num
import com.cruxcoach.app.ui.NutritionTexts.plural
import com.cruxcoach.app.ui.NutritionTexts.t
import com.cruxcoach.athlete.logic.FuelTargets
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MacroTotals
import com.cruxcoach.athlete.logic.MicroWatch
import com.cruxcoach.athlete.logic.OffTable
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.Sex
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One ring of the day: energy, carbohydrate, protein or fat against the day's target. */
class NutritionRingUi(
    /** energy | carbs | protein | fat */
    val code: String,
    val label: NutriText,
    /** The big number: "112", or "≈470" when energy is estimated from macros. */
    val value: NutriText,
    /** 0…n: eaten over target (the ring stays full above 1); 0 without a target. */
    val progress: Double,
    val hasTarget: Boolean,
    /** A reached target gets its tick – a small reward, never a "too much". */
    val reached: Boolean,
    /** "of 112 g" on a large ring … */
    val targetText: NutriText,
    /** … "of 112" on a small one. */
    val compactTargetText: NutriText,
    /** Under the ring: "97 g to go", "Target reached", "1758 kcal to go"…; empty without a target (macros). */
    val belowText: NutriText,
    val hasBelowText: Boolean,
    /** How the target comes about (macros; energy opens its sheet instead). */
    val explanation: List<NutriText>,
    val ownTarget: OwnTargetUi,
)

class NutritionWaterUi(
    /** One glass in ml: 250, or a US cup (237 ml). */
    val glassMl: Int,
    val glasses: Int,
    val full: Int,
    val reached: Boolean,
    val hint: NutriText,
    val total: NutriText,
    val addLabel: NutriText,
    /** The newest water entry, which tapping the last full glass removes. */
    val lastEntryId: String,
)

class FoodEntryUi(
    val id: String,
    val name: String,
    /** "120 g · P 32 · C 85 · F 14 g · 470 kcal" */
    val subtitle: NutriText,
    /** Edited by amount (its food is known) rather than as a quick entry. */
    val editsByAmount: Boolean,
)

class NutritionMealUi(
    /** breakfast | lunch | dinner | snack | preTraining | postTraining */
    val code: String,
    val title: NutriText,
    val totals: NutriText,
    val hasTotals: Boolean,
    val addLabel: NutriText,
    val entries: List<FoodEntryUi>,
)

class MicroItemUi(val code: String, val label: NutriText, val value: NutriText, val progress: Double)

class MicroWeekUi(val visible: Boolean, val items: List<MicroItemUi>, val coverage: NutriText)

/** A row of the add sheet: a food to log, or a recent entry to log again. */
class FoodRowUi(
    /** What to hand back: a food id ("bls:…", "off:…", "usda:…", an own id) or, for recent rows, the entry id. */
    val ref: String,
    val title: String,
    val subtitle: NutriText,
    val isFavorite: Boolean,
    val isRecipe: Boolean,
    val canDelete: Boolean,
)

class FoodSearchUi(
    /** log | ingredient */
    val purpose: String,
    val query: String,
    val mealCode: String,
    val showMeal: Boolean,
    val recent: List<FoodRowUi>,
    val own: List<FoodRowUi>,
    val showOwnHeader: Boolean,
    val ownEmpty: Boolean,
    val blsHint: NutriText,
    val bls: List<FoodRowUi>,
    val usda: List<FoodRowUi>,
    val showProducts: Boolean,
    val productsHint: NutriText,
    val products: List<FoodRowUi>,
)

class BarcodeScanUi(
    /** idle | looking | found | missing */
    val phase: String,
    val code: String,
    /** The food to open the amount for, when found. */
    val ref: String,
)

/** A transient "Deleted – Undo" line. */
class NutritionUndoUi(val visible: Boolean, val message: NutriText)

class NutritionSettingsUi(
    /** metric | us */
    val unitsCode: String,
    val showCalories: Boolean,
    val everydayChoices: List<NutritionChoiceUi>,
    /** Own targets: energy (only while calories are shown), carbohydrate, protein, fat, water. */
    val targets: List<OwnTargetUi>,
    /** Calories hidden but an own calorie target active: it still counts and can be taken back. */
    val ownKcalHidden: Boolean,
    val showProteinPerKg: Boolean,
    val proteinPerKg: Double,
    val proteinPerKgText: NutriText,
    val proteinMin: Double,
    val proteinMax: Double,
    val proteinLosingHint: NutriText,
    val hasProteinLosingHint: Boolean,
    /** Bytes of the unpacked product database; 0 when it is not on the phone. */
    val productBytes: Long,
)

class NutritionDayUi(
    val isLoading: Boolean,
    /** The athlete database could not be read. */
    val failed: Boolean,
    val dayIso: String,
    val isToday: Boolean,
    val isYesterday: Boolean,
    val dayLoad: NutriText,
    val showIntro: Boolean,
    /** The intro's guide behind the app bar's info button once the card is gone. */
    val showGuideButton: Boolean,
    val energyCard: NutritionEnergyCardUi,
    val needsWeight: Boolean,
    val rings: List<NutritionRingUi>,
    val ownNotes: List<NutriText>,
    val water: NutritionWaterUi,
    val previousDayCount: Int,
    val showRepeatCard: Boolean,
    val showRepeatLink: Boolean,
    val repeatCardText: NutriText,
    val repeatConfirmText: NutriText,
    val meals: List<NutritionMealUi>,
    val micros: MicroWeekUi,
    val showCalories: Boolean,
    /** metric | us */
    val unitsCode: String,
    val massUnit: String,
    val energySheet: EnergySheetUi,
    val search: FoodSearchUi,
    val scan: BarcodeScanUi,
    val undo: NutritionUndoUi,
    val settings: NutritionSettingsUi,
    /** Ready | preparing | missing | failed */
    val productsState: String,
)

/**
 * The nutrition tab (Android `FuelScreen`): the day's rings against targets
 * that follow the training day, the meals, water, the energy card and every
 * way of adding food. One model per screen; [close] when it goes.
 */
class NutritionScreenModel internal constructor(
    /** Internal for tests, which assert the day's numbers directly as Android's do. */
    internal val presenter: FuelPresenter,
    private val catalog: FoodCatalog,
    private val scope: CoroutineScope,
    private val decimalComma: Boolean,
    private val currentYear: () -> Int,
) {
    private val search = FoodSearchPresenter(catalog, scope)
    private val forms = FoodFormFactory(presenter, decimalComma)

    private data class Extra(
        val purpose: String = "log",
        val meal: Meal = FuelRules.mealForHour(FuelRules.hourNow()),
        val excludeId: String = "",
        val micros: MicroWatch.Summary? = null,
        val scan: BarcodeScanUi = BarcodeScanUi("idle", "", ""),
        val undo: Undo? = null,
        val productBytes: Long = 0,
    )

    /** What the undo line takes back. */
    private sealed interface Undo {
        val message: NutriText
        class Deleted(val entry: FoodLogEntry, override val message: NutriText) : Undo
        class Relogged(val entry: FoodLogEntry, override val message: NutriText) : Undo
        class Water(val ml: Int, override val message: NutriText) : Undo
    }

    private val extra = MutableStateFlow(Extra())
    /** Foods from the search results, so a picked row can be logged before it is one of "my foods". */
    private val pickable = mutableMapOf<String, FoodItem>()
    private var microJob: Job? = null

    init {
        catalog.prepare()
        // The settings show the space the product database takes once it is built or removed.
        scope.launch { catalog.products.state.collect { refreshProductBytes() } }
        // Iron, calcium and vitamin D over the week up to the shown day.
        microJob = scope.launch {
            presenter.state
                .map { s -> MicroKey(s.day ?: s.today, s.entries, s.profile.sex, s.profile.birthYear, s.loading) }
                .distinctUntilChanged()
                .collect { key ->
                    val day = key.day ?: return@collect
                    if (key.loading) return@collect
                    val summary = try {
                        catalog.microWeek(day, key.sex, key.birthYear)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    extra.update { it.copy(micros = summary) }
                }
        }
    }

    private data class MicroKey(val day: kotlinx.datetime.LocalDate?, val entries: List<FoodLogEntry>, val sex: Sex?, val birthYear: Int?, val loading: Boolean)

    val currentState: NutritionDayUi get() = map(presenter.state.value, search.state.value, extra.value)

    fun watch(onState: (NutritionDayUi) -> Unit): Subscription {
        val job = scope.launch {
            // The product state only changes the hints; it is read in [map].
            combine(presenter.state, search.state, extra, catalog.products.state) { s, q, e, _ -> map(s, q, e) }.collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    fun close() {
        microJob?.cancel()
        presenter.close()
        scope.cancel()
    }

    // ── Day ──────────────────────────────────────────────────────────

    fun previousDay() = presenter.previousDay()
    fun nextDay() = presenter.nextDay()
    fun goToday() = presenter.goToday()
    fun acceptIntro() = presenter.acceptIntro()

    /** "Same as yesterday": the previous day's entries into the shown day. */
    fun repeatPreviousDay() = presenter.copyPreviousDay()

    // ── Water ────────────────────────────────────────────────────────

    /** The next empty glass. */
    fun addGlass() = presenter.addWater(water(presenter.state.value).glassMl)

    fun addWater(ml: Int) { if (ml in 1..5000) presenter.addWater(ml) }

    /** The last full glass: removes the newest water entry, with undo. */
    fun removeLastGlass() {
        val entry = presenter.state.value.water.maxByOrNull { it.loggedAt } ?: return
        presenter.deleteWater(entry)
        extra.update { it.copy(undo = Undo.Water(entry.ml, t("trf_water_removed"))) }
    }

    // ── Entries ──────────────────────────────────────────────────────

    fun deleteEntry(id: String) {
        val entry = entry(id) ?: return
        presenter.deleteEntry(entry)
        extra.update { it.copy(undo = Undo.Deleted(entry, t("trf_deleted"))) }
    }

    fun undo() {
        when (val u = extra.value.undo) {
            is Undo.Deleted -> presenter.restoreEntry(u.entry)
            is Undo.Relogged -> presenter.deleteEntry(u.entry)
            is Undo.Water -> presenter.addWater(u.ml)
            null -> Unit
        }
        dismissUndo()
    }

    fun dismissUndo() = extra.update { it.copy(undo = null) }

    /** Change an entry by amount; null when its food is unknown (then [quickEditForm]). */
    fun editForm(entryId: String): FoodAmountForm? {
        val s = presenter.state.value
        val entry = entry(entryId) ?: return null
        val item = entry.foodItemId?.let { id -> s.foods.firstOrNull { it.id == id } } ?: return null
        return forms.edit(entry, item, s.profile.units, s.profile.showCalories)
    }

    fun quickEditForm(entryId: String): QuickEntryForm? = entry(entryId)?.let { forms.quickEdit(it) }

    private fun entry(id: String) = presenter.state.value.entries.firstOrNull { it.id == id }

    // ── Adding ───────────────────────────────────────────────────────

    /** The meal "add" starts with now: by the time of day, or "after training" right after one. */
    fun mealNowCode(): String = NutritionFormat.mealCode(presenter.mealNow())

    /** Opens the add sheet for [mealCode] (purpose "log") or the ingredient picker of [recipe] (purpose "ingredient"). */
    fun beginSearch(purpose: String, mealCode: String, recipe: RecipeForm?) {
        val meal = NutritionFormat.meal(mealCode) ?: extra.value.meal
        extra.update { it.copy(purpose = if (purpose == "ingredient") "ingredient" else "log", meal = meal, excludeId = recipe?.foodId.orEmpty()) }
        search.clear()
    }

    fun setSearchMeal(mealCode: String) {
        NutritionFormat.meal(mealCode)?.let { m -> extra.update { it.copy(meal = m) } }
    }

    fun setQuery(text: String) = search.setQuery(text, presenter.state.value.foods.map { it.id }.toSet())

    /** "Log again" with one tap: the same food and amount into the add sheet's meal; undo-able. */
    fun relog(entryId: String) {
        val s = presenter.state.value
        val meal = extra.value.meal
        val entry = s.recentByMeal[meal]?.firstOrNull { it.id == entryId } ?: return
        val added = presenter.relog(entry, meal)
        extra.update { it.copy(undo = Undo.Relogged(added, t("trf_recent_logged", NutritionTexts.str(entry.name)))) }
    }

    fun toggleFavorite(foodId: String) {
        presenter.state.value.foods.firstOrNull { it.id == foodId }?.let { presenter.toggleFavorite(it) }
    }

    fun deleteFood(foodId: String) {
        presenter.state.value.foods.firstOrNull { it.id == foodId }?.let { presenter.deleteFood(it) }
    }

    /** The amount for a picked row ("add" in the add sheet's meal); null when the row is gone. */
    fun amountForm(ref: String): FoodAmountForm? {
        val s = presenter.state.value
        val item = food(ref) ?: return null
        return forms.amount(item, extra.value.meal, s.profile.units, s.profile.showCalories, s.lastByFood[item.id])
    }

    /** The amount of an ingredient for [recipe]; null for per-portion foods, which a recipe cannot weigh. */
    fun ingredientForm(ref: String, recipe: RecipeForm): FoodAmountForm? {
        val s = presenter.state.value
        val item = food(ref)?.takeIf { !FuelRules.isPerPortion(it) } ?: return null
        return forms.ingredient(item, recipe, s.profile.units, s.profile.showCalories)
    }

    private fun food(ref: String): FoodItem? = presenter.state.value.foods.firstOrNull { it.id == ref } ?: pickable[ref]

    fun quickForm(mealCode: String): QuickEntryForm = forms.quick(NutritionFormat.meal(mealCode) ?: extra.value.meal)

    /** [barcode]: "" when the food is not created for a scanned code. */
    fun createFoodForm(barcode: String): CreateFoodForm =
        forms.create(barcode.ifBlank { null }, presenter.state.value.profile.units)

    fun makeRecipeForm(): RecipeForm {
        val p = presenter.state.value.profile
        return forms.recipe(null, null, p.units, p.showCalories)
    }

    /** Loads a saved recipe to change it; [onReady] gets null when it cannot be read. */
    fun editRecipe(foodId: String, onReady: (RecipeForm?) -> Unit) {
        presenter.loadRecipe(foodId) { recipe ->
            val p = presenter.state.value.profile
            onReady(recipe?.let { forms.recipe(foodId, it, p.units, p.showCalories) })
        }
    }

    // ── Barcode ──────────────────────────────────────────────────────

    /** Own foods first (they may carry a barcode), then the product database – both on the device. */
    fun lookupBarcode(code: String) {
        val variants = OffTable.barcodeVariants(code)
        if (variants.isEmpty()) {
            extra.update { it.copy(scan = BarcodeScanUi("missing", code.take(40), "")) }
            return
        }
        extra.update { it.copy(scan = BarcodeScanUi("looking", code, "")) }
        scope.launch {
            val own = presenter.state.value.foods.firstOrNull { it.barcode != null && it.barcode in variants }
            val item = own ?: try {
                catalog.productByBarcode(code)?.let { FoodItems.offFoodItem(it, catalog.german()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (item != null) pickable[item.id] = item
            extra.update { it.copy(scan = if (item != null) BarcodeScanUi("found", code, item.id) else BarcodeScanUi("missing", code, "")) }
        }
    }

    fun consumeScan() = extra.update { it.copy(scan = BarcodeScanUi("idle", "", "")) }

    // ── Body data and energy ─────────────────────────────────────────

    private fun weightKg(text: String): Double? =
        NutritionFormat.parseDecimal(text)?.let { Units.massFromDisplay(it, presenter.state.value.profile.units) }
            ?.takeIf { it in EnergyUi.PLAUSIBLE_WEIGHT_KG }

    private fun heightCm(text: String): Double? =
        NutritionFormat.parseDecimal(text)?.let { Units.lengthFromDisplay(it, presenter.state.value.profile.units) }
            ?.takeIf { it in EnergyUi.PLAUSIBLE_HEIGHT_CM }

    private fun birthYear(text: String): Int? {
        val year = currentYear()
        return text.trim().toIntOrNull()?.takeIf { it in (year - 100)..(year - 8) }
    }

    /** Whether a typed weight (athlete's unit) would be kept: 20–300 kg. Fields save themselves once it is. */
    fun isWeightValid(text: String): Boolean = weightKg(text) != null
    fun isHeightValid(text: String): Boolean = heightCm(text) != null
    fun isBirthYearValid(text: String): Boolean = birthYear(text) != null

    /** A weight typed in the athlete's unit; false (not kept) outside 20–300 kg. */
    fun enterWeight(text: String): Boolean {
        val kg = weightKg(text) ?: return false
        presenter.logWeight(kg)
        return true
    }

    /** A height typed in the athlete's unit; false (not kept) outside 100–250 cm. */
    fun enterHeight(text: String): Boolean {
        val cm = heightCm(text) ?: return false
        presenter.logHeight(cm)
        return true
    }

    fun enterBirthYear(text: String): Boolean {
        val y = birthYear(text) ?: return false
        presenter.updateProfile { it.copy(birthYear = y) }
        return true
    }

    fun setEverydayActivity(code: String) {
        EnergyUi.everyday(code)?.let { e -> presenter.updateProfile { it.copy(everydayActivity = e) } }
    }

    fun setSex(code: String) {
        val sex = when (code) { "female" -> Sex.FEMALE; "male" -> Sex.MALE; else -> return }
        presenter.updateProfile { it.copy(sex = sex) }
    }

    fun weightGoalForm(): WeightGoalForm = WeightGoalForm(presenter, currentYear(), decimalComma)

    // ── Targets and settings ─────────────────────────────────────────

    /** The target follows the recommendation again. */
    fun followRecommendation(kindCode: String) {
        val kind = EnergyUi.kind(kindCode) ?: return
        presenter.updateProfile { EnergyUi.withOwnTarget(it, kind, null) }
    }

    /** "Own": starts from today's recommendation. */
    fun chooseOwnTarget(kindCode: String) {
        val kind = EnergyUi.kind(kindCode) ?: return
        val s = presenter.state.value
        if (EnergyUi.ownTarget(s.profile, kind) != null) return
        val value = EnergyUi.startValue(kind, recommended(s, kind))
        presenter.updateProfile { EnergyUi.withOwnTarget(it, kind, value) }
    }

    /** Whether a typed own value would be kept (a typo guard, not a limit on choices). */
    fun isOwnTargetValid(kindCode: String, text: String): Boolean {
        val kind = EnergyUi.kind(kindCode) ?: return false
        return EnergyUi.parseOwn(kind, presenter.state.value.profile.units, text) != null
    }

    /** A typed own value; false (not kept) outside the plausible range – the field then shows its hint. */
    fun setOwnTarget(kindCode: String, text: String): Boolean {
        val kind = EnergyUi.kind(kindCode) ?: return false
        val value = EnergyUi.parseOwn(kind, presenter.state.value.profile.units, text) ?: return false
        presenter.updateProfile { EnergyUi.withOwnTarget(it, kind, value) }
        return true
    }

    fun setShowCalories(show: Boolean) = presenter.updateProfile { it.copy(showCalories = show) }

    /** metric | us — the same setting as in the training settings; storage stays metric. */
    fun setUnits(code: String) {
        val system = when (code) { "metric" -> UnitSystem.METRIC; "us" -> UnitSystem.IMPERIAL; else -> return }
        presenter.updateProfile { Units.withUnits(it, system) }
    }

    /** Protein per kg in 0.1 steps within the choice (1.4–2.4). */
    fun setProteinPerKg(value: Double) {
        val choice = FuelTargets.PROTEIN_PER_KG_CHOICE
        val v = ((value.coerceIn(choice.start, choice.endInclusive)) * 10).roundToInt() / 10.0
        presenter.updateProfile { it.copy(proteinPerKg = v) }
    }

    /** Deletes the unpacked product database; the next product search builds it again. */
    fun removeProducts() {
        scope.launch {
            try {
                catalog.products.remove()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("NutritionScreenModel: could not remove the product database: ${e.message}")
            }
            refreshProductBytes()
        }
    }

    fun refreshProductBytes() {
        extra.update { it.copy(productBytes = catalog.products.bytesOnDisk()) }
    }

    // ── Mapping ──────────────────────────────────────────────────────

    private fun recommended(s: FuelState, kind: FuelTargets.TargetKind): Int? {
        val rec = s.targets?.let { it.recommended ?: it }
        return when (kind) {
            FuelTargets.TargetKind.ENERGY -> NutritionTexts.energyTarget(s.need, s.plan)
            FuelTargets.TargetKind.CARBS -> rec?.carbsG
            FuelTargets.TargetKind.PROTEIN -> rec?.proteinG
            FuelTargets.TargetKind.FAT -> rec?.fatG
            FuelTargets.TargetKind.WATER -> rec?.waterMl
        }
    }

    private fun map(s: FuelState, q: FoodSearchPresenter.State, e: Extra): NutritionDayUi {
        val profile = s.profile
        val units = profile.units
        val day = s.day ?: s.today
        val load = NutritionTexts.dayLoad(s.dayLoad)
        val foodsById = s.foods.associateBy { it.id }
        val byMeal = MacroTotals.byMeal(s.entries).toMap()
        val meals = MAIN_MEALS + Meal.entries.filter { it !in MAIN_MEALS && byMeal.containsKey(it) }
        val productState = when (catalog.products.state.value) {
            is OffProductStore.State.Ready -> "ready"
            is OffProductStore.State.Preparing -> "preparing"
            OffProductStore.State.Missing -> "missing"
            OffProductStore.State.Failed -> "failed"
        }
        return NutritionDayUi(
            isLoading = s.loading,
            failed = s.failed,
            dayIso = day?.toString().orEmpty(),
            isToday = s.isToday,
            isYesterday = s.day != null && s.today != null && s.day.toEpochDays() == s.today.toEpochDays() - 1,
            dayLoad = s.planned?.let { t("trf_day_planned", msg(load), msg(NutritionTexts.plannedTraining(it))) } ?: load,
            showIntro = !s.loading && !profile.fuelIntroAccepted,
            showGuideButton = !s.loading && profile.fuelIntroAccepted,
            energyCard = EnergyUi.card(s.energy, profile, canOpenGoal = true),
            needsWeight = !s.loading && s.targets == null,
            rings = rings(s),
            ownNotes = ownNotes(s),
            water = water(s),
            previousDayCount = s.previousDayCount,
            showRepeatCard = s.entries.isEmpty() && s.previousDayCount > 0,
            showRepeatLink = s.entries.isNotEmpty() && s.previousDayCount > 0,
            repeatCardText = plural("tru_copy_text", s.previousDayCount, int(s.previousDayCount)),
            repeatConfirmText = plural("trf_copy_confirm_text", s.previousDayCount, int(s.previousDayCount)),
            meals = meals.map { meal -> meal(meal, byMeal[meal], s.entries.filter { it.meal == meal }, profile.showCalories, units, foodsById) },
            micros = micros(e.micros),
            showCalories = profile.showCalories,
            unitsCode = if (units == UnitSystem.IMPERIAL) "us" else "metric",
            massUnit = Units.massUnit(units),
            energySheet = EnergyUi.sheet(s, currentYear()),
            search = search(s, q, e),
            scan = e.scan,
            undo = e.undo?.let { NutritionUndoUi(true, it.message) } ?: NutritionUndoUi(false, NutritionTexts.empty),
            settings = settings(s, e),
            productsState = productState,
        )
    }

    private fun rings(s: FuelState): List<NutritionRingUi> {
        val targets = s.targets
        val totals = MacroTotals.of(s.entries)
        val profile = s.profile
        val out = mutableListOf<NutritionRingUi>()
        // Energy is a ring while calories are shown: eaten against the need, or against the calorie target while losing weight.
        if (profile.showCalories) {
            val plan = s.plan
            val target = NutritionTexts.energyTarget(s.need, plan, profile)
            val losing = plan != null && plan.paceDeficitKcal > 0
            val value = totals.kcal.roundToInt()
            val below = when {
                target == null -> t("trn_energy_need_short")
                value < target -> t("trn_to_go", int(target - value))
                losing && value > target -> t("trn_over_target", int(value - target))
                losing -> t("trn_target_met")
                else -> t("trn_need_met")
            }
            out += NutritionRingUi(
                code = "energy", label = t("trn_energy"),
                value = literal((if (totals.kcalEstimated) "≈" else "") + value),
                progress = if (target != null && target > 0) totals.kcal / target else 0.0,
                hasTarget = target != null, reached = false,
                targetText = if (target != null) t("trn_of_kcal", int(target)) else literal("kcal"),
                compactTargetText = if (target != null) t("tru_of_short", int(target)) else literal("kcal"),
                belowText = below, hasBelowText = true,
                explanation = emptyList(),
                ownTarget = EnergyUi.ownTargetUi(FuelTargets.TargetKind.ENERGY, profile, recommended(s, FuelTargets.TargetKind.ENERGY),
                    t("trn_target_heading")),
            )
        }
        out += macroRing(s, "carbs", t("tru_carbs_short"), totals.carbs, targets?.carbsG, FuelTargets.TargetKind.CARBS, carbsExplanation(s))
        out += macroRing(s, "protein", t("trf_protein"), totals.protein, targets?.proteinG, FuelTargets.TargetKind.PROTEIN,
            listOfNotNull(targets?.let { t("trf_protein_info", int(it.proteinRangeG.first), int(it.proteinRangeG.last)) }))
        out += macroRing(s, "fat", t("trf_fat"), totals.fat, targets?.fatG, FuelTargets.TargetKind.FAT, listOfNotNull(
            targets?.let { tg ->
                t(if (tg.fatFromEnergy) "trf_fat_target_energy" else "trf_fat_target_weight", int(tg.fatRangeG.first), int(tg.fatRangeG.last))
            },
            totals.fatEnergyShare?.let { t("trf_fat_share_today", int((it * 100).roundToInt())) },
            t("trf_fat_info"),
        ))
        return out
    }

    private fun carbsExplanation(s: FuelState): List<NutriText> {
        val tg = s.targets
        return listOfNotNull(
            tg?.let { t("trf_carbs_info", msg(NutritionTexts.dayLoad(it.dayLoad)), num(it.carbsPerKg)) },
            // Before the training: the target already counts what is planned (audit W4), and says so.
            s.planned?.let { t("trf_carbs_planned", msg(NutritionTexts.plannedTraining(it))) },
            // The evening before a hard day: a hint only, the target stays (audit P1-5).
            if (s.tomorrowHard) t("trf_carbs_tomorrow_hard") else null,
            // While losing weight: the day's deficit shrinks so these carbohydrates fit, named with both numbers.
            s.plan.takeIf { s.profile.ownKcal == null && tg != null }?.let { NutritionTexts.easedDeficit(it, s.dayLoad, s.profile) },
        )
    }

    private fun macroRing(
        s: FuelState, code: String, label: NutriText, value: Double, target: Int?, kind: FuelTargets.TargetKind, explanation: List<NutriText>,
    ) = NutritionRingUi(
        code = code, label = label,
        value = literal("${value.roundToInt()}"),
        progress = if (target != null && target > 0) value / target else 0.0,
        hasTarget = target != null,
        reached = target != null && target > 0 && value >= target,
        targetText = if (target != null) t("tru_of_g", int(target)) else literal("g"),
        compactTargetText = if (target != null) t("tru_of_short", int(target)) else literal("g"),
        belowText = target?.let { NutritionTexts.remaining(value, it) } ?: NutritionTexts.empty,
        hasBelowText = target != null,
        explanation = explanation.ifEmpty { listOf(t("trf_needs_weight")) },
        ownTarget = EnergyUi.ownTargetUi(kind, s.profile, recommended(s, kind), t("trn_target_heading")),
    )

    /**
     * Under the rings: an own carbohydrate or calorie target stays the same on every day, so the line says it does not
     * follow the training day and names the day's recommendation.
     */
    private fun ownNotes(s: FuelState): List<NutriText> {
        val recommended = s.targets?.recommended
        return buildList {
            if (recommended != null && FuelTargets.TargetKind.CARBS in s.targets.own) {
                add(t(if (s.isToday) "trn_own_carbs_note_today" else "trn_own_carbs_note_day", int(recommended.carbsG)))
            }
            // The recommendation without the own value: the need, less the day's deficit while losing weight.
            val kcal = NutritionTexts.energyTarget(s.need, s.plan)
            if (s.profile.showCalories && s.profile.ownKcal != null && kcal != null) {
                add(t(if (s.isToday) "trn_own_energy_note_today" else "trn_own_energy_note_day", int(kcal)))
            }
        }
    }

    /**
     * Water as glasses: tap the next empty one for another glass, the last full
     * one to take it back. The target comes from the body weight; without one,
     * eight glasses show.
     */
    private fun water(s: FuelState): NutritionWaterUi {
        val units = s.profile.units
        val volume = FuelUnits.unitFor(units, drink = true)
        val glass = FuelUnits.waterPresetsMl(units).first()
        val target = s.targets?.waterMl
        val glasses = ((target ?: (glass * 8)) + glass - 1) / glass
        val full = (s.waterMl / glass).coerceAtMost(glasses)
        val reached = target != null && s.waterMl >= target
        return NutritionWaterUi(
            glassMl = glass,
            glasses = glasses,
            full = full,
            reached = reached,
            hint = if (reached) t("tru_water_reached") else t("tru_water_glass_hint", msg(NutritionTexts.amount(glass.toDouble(), volume))),
            total = if (target != null) t("trf_progress_volume", NutritionTexts.inputNumber(s.waterMl.toDouble(), volume),
                msg(NutritionTexts.amount(target.toDouble(), volume)))
            else NutritionTexts.amount(s.waterMl.toDouble(), volume),
            addLabel = t("trf_water_add", msg(NutritionTexts.amount(glass.toDouble(), volume))),
            lastEntryId = s.water.maxByOrNull(HydrationEntry::loggedAt)?.id.orEmpty(),
        )
    }

    private fun isDrink(e: FoodLogEntry, foodsById: Map<String, FoodItem>): Boolean =
        foodsById[e.foodItemId]?.let { FuelUnits.isDrink(it.id, it.name, it.servingLabel) } ?: FuelUnits.isDrink(e.foodItemId, e.name)

    private fun entrySubtitle(entry: FoodLogEntry, unit: FuelUnits.Amount, showCalories: Boolean): NutriText {
        val amount = when {
            entry.amountG != null -> NutritionTexts.amount(entry.amountG!!, unit)
            entry.portions != null && entry.portions != 1.0 -> t("trf_entry_portions", num(entry.portions!!))
            else -> null
        }
        val macros = NutritionTexts.macros(entry.proteinG, entry.carbsG, entry.fatG, MacroTotals.energy(entry), showCalories)
        return join(listOfNotNull(amount, macros))
    }

    private fun meal(
        meal: Meal, totals: MacroTotals?, entries: List<FoodLogEntry>, showCalories: Boolean, units: UnitSystem,
        foodsById: Map<String, FoodItem>,
    ): NutritionMealUi {
        val title = t(NutritionTexts.mealKey(meal))
        val totalsText = totals?.takeIf { it.entries > 0 }?.let {
            NutritionTexts.macros(it.protein, it.carbs, it.fat, MacroTotals.Energy(it.kcal, it.kcalEstimated), showCalories)
        }
        return NutritionMealUi(
            code = NutritionFormat.mealCode(meal),
            title = title,
            totals = totalsText ?: NutritionTexts.empty,
            hasTotals = totalsText != null,
            addLabel = t("tru_meal_add", msg(title)),
            entries = entries.map { entry ->
                FoodEntryUi(
                    id = entry.id, name = entry.name,
                    subtitle = entrySubtitle(entry, FuelUnits.unitFor(units, isDrink(entry, foodsById)), showCalories),
                    editsByAmount = entry.foodItemId?.let { foodsById[it] } != null,
                )
            },
        )
    }

    /** The week's iron, calcium and vitamin D next to EFSA reference values – an estimate, no red states. */
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
                    code = code, label = t(label),
                    value = t("trf_micro_value", num(item.perDay, if (item.reference < 100) 1 else 0), num(item.reference, 0),
                        NutritionTexts.str(unit)),
                    progress = if (item.reference > 0) (item.perDay / item.reference).coerceIn(0.0, 1.0) else 0.0,
                )
            },
            coverage = plural("trf_micro_coverage", summary.loggedDays, int((summary.coverage * 100).roundToInt()), int(summary.loggedDays)),
        )
    }

    private fun per100(protein: Double?, carbs: Double?, fat: Double?): NutriText {
        fun v(x: Double?) = x?.let { num(it) } ?: NutritionTexts.str("–")
        return t("trf_entry_macros", v(protein), v(carbs), v(fat))
    }

    private fun search(s: FuelState, q: FoodSearchPresenter.State, e: Extra): FoodSearchUi {
        val profile = s.profile
        val german = catalog.german()
        val ingredient = e.purpose == "ingredient"
        val query = q.query.trim().lowercase()
        val foods = if (ingredient) s.foods.filter { it.id != e.excludeId && !FuelRules.isPerPortion(it) } else s.foods
        // The repository already orders favourites first, then by use count.
        val visible = foods.filter { query.isEmpty() || it.name.lowercase().contains(query) || (it.brand?.lowercase()?.contains(query) == true) }
        val recent = if (ingredient || query.isNotEmpty()) emptyList() else s.recentByMeal[e.meal].orEmpty()
        fun per100Line(kcal: Double, protein: Double, carbs: Double, fat: Double) =
            t("fvp_per_100", num(kcal), num(protein), num(carbs), num(fat))
        val bls = q.bls.map { food ->
            val item = FoodItems.blsFoodItem(food, german).also { pickable[it.id] = it }
            FoodRowUi(item.id, item.name, per100Line(food.kcal, food.protein, food.carbs, food.fat), false, false, false)
        }
        val usda = q.usda.map { food ->
            val item = FoodItems.usdaFoodItem(food).also { pickable[it.id] = it }
            FoodRowUi(item.id, food.nameEn, per100Line(food.kcal, food.protein, food.carbs, food.fat), false, false, false)
        }
        val products = q.products.map { p ->
            val item = FoodItems.offFoodItem(p, german).also { pickable[it.id] = it }
            FoodRowUi(item.id, p.displayName(german),
                join(listOfNotNull(p.brand.ifEmpty { null }?.let { literal(it) }, per100Line(p.kcal, p.protein, p.carbs, p.fat))),
                false, false, false)
        }
        val preparing = catalog.products.state.value is OffProductStore.State.Preparing
        return FoodSearchUi(
            purpose = e.purpose,
            query = q.query,
            mealCode = NutritionFormat.mealCode(e.meal),
            showMeal = !ingredient,
            recent = recent.map { entry ->
                val unit = FuelUnits.unitFor(profile.units, FuelUnits.isDrink(entry.foodItemId, entry.name))
                FoodRowUi(entry.id, entry.name, entrySubtitle(entry, unit, profile.showCalories), false, false, false)
            },
            own = visible.map { item ->
                val basis = t(if (FuelRules.isPerPortion(item)) "trf_food_per_portion" else "trf_food_per_100")
                FoodRowUi(
                    ref = item.id, title = item.name,
                    subtitle = join(listOfNotNull(item.brand?.let { literal(it) },
                        join(listOf(per100(item.proteinPer100, item.carbsPer100, item.fatPer100), literal(" ("), basis, literal(")")), ""))),
                    isFavorite = item.favorite, isRecipe = item.source == Recipe.SOURCE, canDelete = !ingredient,
                )
            },
            showOwnHeader = !ingredient,
            ownEmpty = visible.isEmpty() && query.isEmpty(),
            blsHint = t(when {
                query.length < 2 -> "trf_foods_bls_hint"
                q.searchingBls && q.bls.isEmpty() -> "trf_foods_searching"
                q.bls.isEmpty() -> "trf_foods_bls_none"
                else -> "fvp_attribution"
            }),
            bls = bls,
            usda = usda,
            showProducts = query.length >= 2,
            productsHint = t(when {
                preparing && q.products.isEmpty() -> "trf_products_preparing"
                q.searching && q.products.isEmpty() -> "trf_foods_searching"
                q.products.isEmpty() -> "trf_foods_bls_none"
                else -> "trf_off_attribution"
            }),
            products = products,
        )
    }

    private fun settings(s: FuelState, e: Extra): NutritionSettingsUi {
        val p = s.profile
        val kinds = FuelTargets.TargetKind.entries.filter { it != FuelTargets.TargetKind.ENERGY || p.showCalories }
        val choice = FuelTargets.PROTEIN_PER_KG_CHOICE
        val losing = FuelTargets.PROTEIN_PER_KG_LOSING
        val imperial = p.units == UnitSystem.IMPERIAL
        return NutritionSettingsUi(
            unitsCode = if (imperial) "us" else "metric",
            showCalories = p.showCalories,
            everydayChoices = EnergyUi.everydayChoices(p),
            targets = kinds.map { EnergyUi.ownTargetUi(it, p, recommended(s, it), null) },
            ownKcalHidden = !p.showCalories && p.ownKcal != null,
            showProteinPerKg = p.ownProteinG == null,
            proteinPerKg = p.proteinPerKg,
            proteinPerKgText = if (imperial) t("tra_protein_per_kg_lb", num(p.proteinPerKg), num(p.proteinPerKg / Units.LB_PER_KG, 2))
                else t("tra_protein_per_kg", num(p.proteinPerKg)),
            proteinMin = choice.start,
            proteinMax = choice.endInclusive,
            proteinLosingHint = if (imperial) t("tra_protein_losing_lb", num(losing.start), num(losing.endInclusive),
                num(losing.start / Units.LB_PER_KG, 2), num(losing.endInclusive / Units.LB_PER_KG, 2))
                else t("tra_protein_losing", num(losing.start), num(losing.endInclusive)),
            hasProteinLosingHint = p.goal == com.cruxcoach.athlete.model.AthleteGoal.LOSE_WEIGHT,
            productBytes = e.productBytes,
        )
    }

    private companion object {
        /** Meals that always have a place in the day, each with its own "+". */
        val MAIN_MEALS = listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.DINNER, Meal.SNACK)
    }
}
