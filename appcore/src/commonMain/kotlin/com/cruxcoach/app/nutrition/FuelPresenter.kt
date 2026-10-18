// Port of androidApp/.../ui/training/fuel/FuelViewModel.kt (0e1bad0c6). Kept
// close to the Android file so later changes carry over by diff; the seams are
// an injected scope, IO dispatcher and clock instead of Hilt and viewModelScope.
package com.cruxcoach.app.nutrition

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.logic.DayLoad
import com.cruxcoach.athlete.logic.EnergyBalance
import com.cruxcoach.athlete.logic.EnergyReport
import com.cruxcoach.athlete.logic.FuelTargets
import com.cruxcoach.athlete.logic.PlannedTraining
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.logic.RecentFoods
import com.cruxcoach.athlete.logic.WeightPlan
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

data class FuelState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val today: LocalDate? = null,
    val day: LocalDate? = null,
    val dayLoad: DayLoad = DayLoad.REST,
    /** Null while no body weight is known — targets need it. */
    val targets: FuelTargets.Targets? = null,
    val bodyweightKg: Double? = null,
    val entries: List<FoodLogEntry> = emptyList(),
    val water: List<HydrationEntry> = emptyList(),
    val foods: List<FoodItem> = emptyList(),
    val previousDayCount: Int = 0,
    /** Energy guard with its numbers, including today's weight-loss plan while one is set. */
    val energy: EnergyReport = EnergyReport(),
    /** The shown day's estimated energy need; null without weight or height. */
    val need: EnergyBalance.Need? = null,
    /** The shown day's weight-loss plan while one is set: its deficit leaves room for the day's macro targets. */
    val plan: WeightPlan.Plan? = null,
    /**
     * Today's planned training while today's targets follow it (audit W4): not done yet and more than what is
     * done. Null on earlier days and once it is done.
     */
    val planned: PlannedTraining.Planned? = null,
    /** Today, before a planned board day or a usual climbing day: the carbohydrate info says so (audit P1-5). */
    val tomorrowHard: Boolean = false,
    /** The last seven days' needs, today last: what a pace really adds up to in the goal sheet. */
    val weekNeeds: List<EnergyBalance.Need> = emptyList(),
    val heightCm: Double? = null,
    /** Per meal: foods eaten there again and again, to log once more with one tap ([RecentFoods]). */
    val recentByMeal: Map<Meal, List<FoodLogEntry>> = emptyMap(),
    /** Newest entry per saved food: where its amount dialog starts. */
    val lastByFood: Map<String, FoodLogEntry> = emptyMap(),
    /** When the last training or board session ended (epoch ms); "add" starts in "after training" shortly after it. */
    val lastTrainingEndedAt: Long? = null,
    /** The athlete database could not be read; the screen says so instead of showing an empty day. */
    val failed: Boolean = false,
) {
    val waterMl: Int get() = water.sumOf { it.ml }
    val isToday: Boolean get() = day != null && day == today
}

/** Nutrient values of one log entry, already scaled to the amount eaten. */
data class Nutrients(val kcal: Double?, val protein: Double?, val carbs: Double?, val fat: Double?)

/**
 * The nutrition day: targets that follow the training day, the food log by
 * meal, water, and every way of changing them. All writes go through the
 * athlete repository on [io]; the screen state re-derives from its flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = { nowMs() },
    /** How often the screen checks whether the date changed; tests shorten it. */
    private val dayTickMs: Long = DAY_TICK_MS,
) {
    private val _state = MutableStateFlow(FuelState())
    val state: StateFlow<FuelState> = _state.asStateFlow()
    /** The day picked with the arrows; null = today, following the clock (device test D41). */
    private val selectedDay = MutableStateFlow<LocalDate?>(null)
    private var observer: Job? = null

    init {
        observer = scope.launch(io) {
            try {
                service.ensureReady()
                val repo = service.repo
                // On today the screen follows the clock, so a screen left open over midnight shows the new day (D41);
                // a day picked with the arrows stays.
                val clockDay = flow { while (true) { emit(service.today()); delay(dayTickMs) } }.distinctUntilChanged()
                // The clock's day is part of the key, so "yesterday" on a picked day is relabelled after midnight too.
                combine(selectedDay, clockDay) { picked, today -> (picked ?: today) to today }.distinctUntilChanged().flatMapLatest { (day, _) ->
                    val d = day.toString()
                    combine(
                        repo.observeProfile(),
                        repo.observeFoodLog(d),
                        repo.observeHydration(d),
                        repo.observeFoodItems(),
                        // A weight or height entered right here (targets need them) updates the targets at once.
                        // So do a finished training, a climbing day and a board session: the day type sets the carbs (audit W2).
                        combine(
                            repo.observeSeries(BodyMetric.WEIGHT.key),
                            repo.observeSeries(BodyMetric.HEIGHT.key),
                            repo.observeRecentWorkouts(10),
                            service.observeActivityChanges(LocalDate.parse(d).minus(DatePeriod(days = 7)).toString()),
                            // Today's planned training as Today computed it (audit W4).
                            repo.observeSetting(PlannedTraining.SETTING_KEY),
                        ) { w, h, _, _, _ -> w.size to h.lastOrNull()?.value },
                    ) { profile, entries, water, foods, _ -> Snapshot(day, profile, entries, water, foods) }
                }.collect { refresh(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun close() {
        observer?.cancel()
        scope.cancel()
    }

    private data class Snapshot(
        val day: LocalDate, val profile: AthleteProfile, val entries: List<FoodLogEntry>,
        val water: List<HydrationEntry>, val foods: List<FoodItem>,
    )

    private fun refresh(s: Snapshot) {
        val today = service.today()
        val span = (today.toEpochDays() - s.day.toEpochDays()).toInt().coerceAtLeast(0) + 1
        val activities = service.activities(days = maxOf(span, 7))
        // Today's planned training counts until it is done (audit W4); earlier days and the energy check stay real.
        val planned = if (s.day == today) service.plannedForTargets(activities[s.day.toString()]) else null
        val activity = planned?.activity ?: activities[s.day.toString()]
        val previous = service.repo.foodLog(s.day.minus(DatePeriod(days = 1)).toString()).size
        val energy = service.energyReport(s.profile, activities)
        val log = runCatching {
            service.repo.foodLogBetween(today.minus(DatePeriod(days = RecentFoods.WINDOW_DAYS)).toString(), today.toString())
        }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                loading = false,
                failed = false,
                profile = s.profile,
                today = today,
                day = s.day,
                dayLoad = FuelTargets.dayLoad(activity),
                targets = service.fuelTargets(activity, s.profile),
                bodyweightKg = service.currentBodyweight(),
                entries = s.entries,
                water = s.water,
                foods = s.foods,
                previousDayCount = previous,
                energy = energy,
                need = service.energyNeed(s.profile, activity),
                // Today's plan comes with the week's pace; an earlier day gets its own deficit.
                plan = when {
                    s.day != today -> service.weightPlan(s.profile, activity)
                    planned != null -> service.todayWeightPlan(s.profile, activity, activities)
                    else -> energy.plan
                },
                planned = planned,
                tomorrowHard = s.day == today && service.tomorrowHard(s.profile),
                // The goal sheet plans from today as the rings see it.
                weekNeeds = service.weekNeeds(s.profile, if (planned != null) service.withToday(activities, activity) else activities),
                heightCm = service.heightCm(),
                recentByMeal = Meal.entries.associateWith { RecentFoods.forMeal(log, it, s.entries) },
                lastByFood = RecentFoods.lastByFood(log),
                lastTrainingEndedAt = runCatching { service.lastTrainingEndedAt() }.getOrNull(),
            )
        }
    }

    // ── Navigation between days ──────────────────────────────────────

    fun previousDay() { selectedDay.value = (selectedDay.value ?: service.today()).minus(DatePeriod(days = 1)) }

    /** One day on; reaching today means following the clock again. */
    fun nextDay() {
        val day = selectedDay.value ?: return
        val next = day.plus(DatePeriod(days = 1))
        selectedDay.value = if (next < service.today()) next else null
    }

    fun goToday() { selectedDay.value = null }

    private fun currentDay(): String = (selectedDay.value ?: service.today()).toString()

    // ── Intro ────────────────────────────────────────────────────────

    fun acceptIntro() = io { service.repo.updateProfile { it.copy(fuelIntroAccepted = true) } }

    // ── Logging ──────────────────────────────────────────────────────

    /**
     * A quick entry: every nutrient is optional. With [remember], the entry
     * also becomes a [FoodItem] of source [FuelRules.SOURCE_PORTION], whose nutrient
     * fields describe ONE portion (not 100 g), so it can be re-logged with a
     * tap and scaled by portions later.
     */
    fun quickAdd(name: String, meal: Meal, nutrients: Nutrients, remember: Boolean) = io {
        val repo = service.repo
        val now = clock()
        var itemId: String? = null
        if (remember) {
            itemId = repo.newId()
            repo.saveFoodItem(
                FoodItem(
                    id = itemId, name = name, kcalPer100 = nutrients.kcal, proteinPer100 = nutrients.protein,
                    carbsPer100 = nutrients.carbs, fatPer100 = nutrients.fat, servingLabel = null,
                    source = FuelRules.SOURCE_PORTION, useCount = 1, lastUsedAt = now, updatedAt = now,
                ),
            )
        }
        repo.saveFoodLog(
            FoodLogEntry(
                id = repo.newId(), day = currentDay(), loggedAt = now, meal = meal, foodItemId = itemId, name = name,
                portions = 1.0, kcal = nutrients.kcal, proteinG = nutrients.protein, carbsG = nutrients.carbs, fatG = nutrients.fat,
            ),
        )
    }

    /** Logs a saved food by [portions] or by [grams] (the latter only for per-100 g foods). */
    fun addFood(item: FoodItem, meal: Meal, portions: Double?, grams: Double?) = io {
        val repo = service.repo
        val n = FuelRules.scale(item, portions, grams) ?: return@io
        val now = clock()
        // A food picked from the BLS or product search becomes one of "my foods" on first use.
        val database = item.source in setOf(FuelRules.SOURCE_BLS, FuelRules.SOURCE_OFF, FuelRules.SOURCE_USDA)
        val saved = if (database) repo.foodItem(item.id) else null
        if (database && saved == null) repo.saveFoodItem(item.copy(updatedAt = now))
        // A database food saved before it knew its piece weight learns it now (device test D43).
        else if (saved != null && saved.servingG == null && item.servingG != null)
            repo.saveFoodItem(saved.copy(servingG = item.servingG, servingLabel = item.servingLabel, updatedAt = now))
        repo.saveFoodLog(
            FoodLogEntry(
                id = repo.newId(), day = currentDay(), loggedAt = now, meal = meal, foodItemId = item.id, name = item.name,
                amountG = FuelRules.loggedGrams(item, portions, grams),
                portions = portions, kcal = n.kcal, proteinG = n.protein, carbsG = n.carbs, fatG = n.fat,
            ),
        )
        repo.markFoodItemUsed(item.id)
    }

    /** A logged food with a new amount or meal; its nutrients are scaled again from the food. */
    fun updateEntry(entry: FoodLogEntry, item: FoodItem, meal: Meal, portions: Double?, grams: Double?) = io {
        val n = FuelRules.scale(item, portions, grams) ?: return@io
        service.repo.saveFoodLog(
            entry.copy(meal = meal, amountG = FuelRules.loggedGrams(item, portions, grams), portions = portions,
                kcal = n.kcal, proteinG = n.protein, carbsG = n.carbs, fatG = n.fat),
        )
    }

    /** A quick entry (no saved food) with what was typed now. */
    fun updateQuickEntry(entry: FoodLogEntry, name: String, meal: Meal, nutrients: Nutrients) = io {
        service.repo.saveFoodLog(
            entry.copy(name = name, meal = meal, kcal = nutrients.kcal, proteinG = nutrients.protein,
                carbsG = nutrients.carbs, fatG = nutrients.fat),
        )
    }

    fun deleteEntry(entry: FoodLogEntry) = io { service.repo.deleteFoodLog(entry.id) }

    /**
     * "Log again": the same food with the same amount into [meal] of the shown day, in one tap.
     * Returns the new entry, for "undo".
     */
    fun relog(entry: FoodLogEntry, meal: Meal): FoodLogEntry {
        val copy = entry.copy(id = service.repo.newId(), day = currentDay(), loggedAt = clock(), meal = meal)
        io {
            service.repo.saveFoodLog(copy)
            entry.foodItemId?.let { service.repo.markFoodItemUsed(it) }
        }
        return copy
    }

    /** Saves a recipe as one of "my foods"; a new one gets an id. */
    fun saveRecipe(id: String?, recipe: Recipe) = io { service.repo.saveRecipe(id ?: service.repo.newId(), recipe) }

    /** Reads a saved recipe; [onLoaded] gets null when it is gone or unreadable. */
    fun loadRecipe(id: String, onLoaded: (Recipe?) -> Unit) {
        scope.launch {
            val recipe = try {
                withContext(io) { service.ensureReady(); service.repo.recipe(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            onLoaded(recipe)
        }
    }

    fun restoreEntry(entry: FoodLogEntry) = io { service.repo.saveFoodLog(entry) }

    fun addWater(ml: Int) = io { service.repo.addHydration(currentDay(), ml) }

    /** Today's body weight, entered where the targets ask for it. */
    fun logWeight(kg: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, kg, "kg", clock()))
    }

    fun logHeight(cm: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.HEIGHT.key, cm, "cm", clock()))
    }

    fun saveWeightGoal(lose: Boolean, targetKg: Double?, paceKg: Double) = io { service.saveWeightGoal(lose, targetKg, paceKg) }

    /** Birth year, sex and everyday activity from the energy sheet. */
    fun updateProfile(transform: (AthleteProfile) -> AthleteProfile) = io { service.repo.updateProfile(transform) }

    fun deleteWater(entry: HydrationEntry) = io { service.repo.deleteHydration(entry.id) }

    /** "Same as yesterday": copies the previous day's entries into the selected day. */
    fun copyPreviousDay() = io {
        val repo = service.repo
        val day = selectedDay.value ?: service.today()
        val source = repo.foodLog(day.minus(DatePeriod(days = 1)).toString())
        val now = clock()
        repo.transaction {
            source.forEachIndexed { i, e ->
                repo.saveFoodLog(e.copy(id = repo.newId(), day = day.toString(), loggedAt = now + i))
            }
        }
    }

    // ── Saved foods ──────────────────────────────────────────────────

    fun toggleFavorite(item: FoodItem) = io { service.repo.setFoodItemFavorite(item.id, !item.favorite) }
    fun deleteFood(item: FoodItem) = io { service.repo.deleteFoodItem(item.id) }

    fun createFood(name: String, brand: String?, per100: Nutrients, servingG: Double?, servingLabel: String?, barcode: String? = null) = io {
        val repo = service.repo
        repo.saveFoodItem(
            FoodItem(
                id = repo.newId(), name = name, brand = brand?.takeIf { it.isNotBlank() },
                kcalPer100 = per100.kcal, proteinPer100 = per100.protein, carbsPer100 = per100.carbs, fatPer100 = per100.fat,
                servingG = servingG, servingLabel = servingLabel?.takeIf { it.isNotBlank() },
                barcode = barcode, source = FuelRules.SOURCE_USER, updatedAt = clock(),
            ),
        )
    }

    /** The meal "add" starts with now: by the time of day, or "after training" within two hours of one today. */
    fun mealNow(): Meal {
        val now = clock()
        val hour = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).hour
        val s = _state.value
        return FuelRules.mealFor(now, hour, s.lastTrainingEndedAt.takeIf { s.isToday })
    }

    // A failed write must not take the screen down: the flows simply do not change.
    private fun io(block: suspend () -> Unit) {
        scope.launch(io) {
            try {
                service.ensureReady()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("FuelPresenter: write failed: ${e.message}")
            }
        }
    }

    companion object {
        /** Android's `FuelViewModel.dayTickMs`: once a minute. */
        const val DAY_TICK_MS = 60_000L
    }
}

/** The pure rules of FuelViewModel's companion, shared by every nutrition presenter. */
object FuelRules {
    /** Nutrient fields hold the values of one portion. */
    const val SOURCE_PORTION = "portion"
    /** Nutrient fields hold values per 100 g. */
    const val SOURCE_USER = "user"
    /** From the bundled BLS 4.0, per 100 g; id "bls:" + code. */
    const val SOURCE_BLS = "bls"
    /** From the bundled Open Food Facts extract, per 100 g; id "off:" + barcode. */
    const val SOURCE_OFF = "off"
    /** From the bundled USDA SR Legacy extract, per 100 g; id "usda:" + FDC id. */
    const val SOURCE_USDA = "usda"

    fun isPerPortion(item: FoodItem) = item.source == SOURCE_PORTION

    /** Grams eaten: as typed, or portions times the portion size; unknown for per-portion foods. */
    fun loggedGrams(item: FoodItem, portions: Double?, grams: Double?): Double? =
        grams ?: if (item.source != SOURCE_PORTION) portions?.let { p -> item.servingG?.let { it * p } } else null

    /** Scales a food to what was eaten; null when neither amount applies. */
    fun scale(item: FoodItem, portions: Double?, grams: Double?): Nutrients? {
        val factor = when {
            isPerPortion(item) -> portions ?: return null
            grams != null -> grams / 100.0
            portions != null && item.servingG != null -> portions * item.servingG!! / 100.0
            else -> return null
        }
        fun Double?.scaled(f: Double) = this?.let { it * f }
        return Nutrients(item.kcalPer100.scaled(factor), item.proteinPer100.scaled(factor),
            item.carbsPer100.scaled(factor), item.fatPer100.scaled(factor))
    }

    /** Adding food within this long after a finished training starts in "after training" (holistic audit P2-15). */
    const val POST_TRAINING_WINDOW_MS = 2 * 60 * 60 * 1000L

    /**
     * The meal "add" starts with at [nowMs] (local [hour]): "after training" within [POST_TRAINING_WINDOW_MS] of a
     * training that ended at [trainingEndedAtMs], else the meal of the time of day. The athlete can change it.
     */
    fun mealFor(nowMs: Long, hour: Int, trainingEndedAtMs: Long?): Meal =
        if (trainingEndedAtMs != null && nowMs - trainingEndedAtMs in 0..POST_TRAINING_WINDOW_MS) Meal.POST_TRAINING
        else mealForHour(hour)

    /** Sensible meal for the current time of day. */
    fun mealForHour(hour: Int): Meal = when (hour) {
        in 4..9 -> Meal.BREAKFAST
        in 10..13 -> Meal.LUNCH
        in 14..16 -> Meal.SNACK
        in 17..21 -> Meal.DINNER
        else -> Meal.SNACK
    }

    /** The local hour now, for [mealForHour]. */
    fun hourNow(): Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

    /** The current year, for ages and birth-year limits. */
    fun yearNow(): Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).year
}
