package com.cruxcoach.android.ui.training.fuel

import com.cruxcoach.athlete.logic.Recipe
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.logic.DayLoad
import com.cruxcoach.athlete.logic.FuelTargets
import com.cruxcoach.athlete.logic.EnergyBalance
import com.cruxcoach.athlete.logic.EnergyReport
import com.cruxcoach.athlete.logic.WeightPlan
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import javax.inject.Inject

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
    val planned: com.cruxcoach.athlete.logic.PlannedTraining.Planned? = null,
    /** Today, before a planned board day or a usual climbing day: the carbohydrate info says so (audit P1-5). */
    val tomorrowHard: Boolean = false,
    /** The last seven days' needs, today last: what a pace really adds up to in the goal sheet. */
    val weekNeeds: List<EnergyBalance.Need> = emptyList(),
    val heightCm: Double? = null,
    /** Per meal: foods eaten there again and again, to log once more with one tap ([com.cruxcoach.athlete.logic.RecentFoods]). */
    val recentByMeal: Map<Meal, List<FoodLogEntry>> = emptyMap(),
    /** Newest entry per saved food: where its amount dialog starts. */
    val lastByFood: Map<String, FoodLogEntry> = emptyMap(),
    /** When the last training or board session ended (epoch ms); "add" starts in "after training" shortly after it. */
    val lastTrainingEndedAt: Long? = null,
) {
    val waterMl: Int get() = water.sumOf { it.ml }
    val isToday: Boolean get() = day != null && day == today
}

/** Nutrient values of one log entry, already scaled to the amount eaten. */
data class Nutrients(val kcal: Double?, val protein: Double?, val carbs: Double?, val fat: Double?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FuelViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(FuelState())
    val state: StateFlow<FuelState> = _state.asStateFlow()
    /** The day picked with the arrows; null = today, following the clock (device test D41). */
    private val selectedDay = MutableStateFlow<LocalDate?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
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
                    combine(repo.observeSeries(com.cruxcoach.athlete.model.BodyMetric.WEIGHT.key),
                        repo.observeSeries(com.cruxcoach.athlete.model.BodyMetric.HEIGHT.key),
                        repo.observeRecentWorkouts(10),
                        service.observeActivityChanges(LocalDate.parse(d).minus(kotlinx.datetime.DatePeriod(days = 7)).toString()),
                        // Today's planned training as Today computed it (audit W4).
                        repo.observeSetting(com.cruxcoach.athlete.logic.PlannedTraining.SETTING_KEY),
                    ) { w, h, _, _, _ -> w.size to h.lastOrNull()?.value },
                ) { profile, entries, water, foods, _ -> Snapshot(day, profile, entries, water, foods) }
            }.collect { refresh(it) }
        }
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
            service.repo.foodLogBetween(today.minus(DatePeriod(days = com.cruxcoach.athlete.logic.RecentFoods.WINDOW_DAYS)).toString(), today.toString())
        }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                loading = false,
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
                recentByMeal = Meal.entries.associateWith { com.cruxcoach.athlete.logic.RecentFoods.forMeal(log, it, s.entries) },
                lastByFood = com.cruxcoach.athlete.logic.RecentFoods.lastByFood(log),
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
     * also becomes a [FoodItem] of source [SOURCE_PORTION], whose nutrient
     * fields describe ONE portion (not 100 g), so it can be re-logged with a
     * tap and scaled by portions later.
     */
    fun quickAdd(name: String, meal: Meal, nutrients: Nutrients, remember: Boolean) = io {
        val repo = service.repo
        val now = System.currentTimeMillis()
        var itemId: String? = null
        if (remember) {
            itemId = repo.newId()
            repo.saveFoodItem(
                FoodItem(
                    id = itemId, name = name, kcalPer100 = nutrients.kcal, proteinPer100 = nutrients.protein,
                    carbsPer100 = nutrients.carbs, fatPer100 = nutrients.fat, servingLabel = null,
                    source = SOURCE_PORTION, useCount = 1, lastUsedAt = now, updatedAt = now,
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
        val n = scale(item, portions, grams) ?: return@io
        val now = System.currentTimeMillis()
        // A food picked from the BLS or product search becomes one of "my foods" on first use.
        if (item.source in setOf(SOURCE_BLS, SOURCE_OFF, SOURCE_USDA) && repo.foodItem(item.id) == null) repo.saveFoodItem(item.copy(updatedAt = now))
        repo.saveFoodLog(
            FoodLogEntry(
                id = repo.newId(), day = currentDay(), loggedAt = now, meal = meal, foodItemId = item.id, name = item.name,
                amountG = loggedGrams(item, portions, grams),
                portions = portions, kcal = n.kcal, proteinG = n.protein, carbsG = n.carbs, fatG = n.fat,
            ),
        )
        repo.markFoodItemUsed(item.id)
    }

    /** A logged food with a new amount or meal; its nutrients are scaled again from the food. */
    fun updateEntry(entry: FoodLogEntry, item: FoodItem, meal: Meal, portions: Double?, grams: Double?) = io {
        val n = scale(item, portions, grams) ?: return@io
        service.repo.saveFoodLog(
            entry.copy(meal = meal, amountG = loggedGrams(item, portions, grams), portions = portions,
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
        val copy = entry.copy(id = java.util.UUID.randomUUID().toString(), day = currentDay(), loggedAt = System.currentTimeMillis(), meal = meal)
        io {
            service.repo.saveFoodLog(copy)
            entry.foodItemId?.let { service.repo.markFoodItemUsed(it) }
        }
        return copy
    }

    /** Saves a recipe as one of "my foods"; a new one gets an id. */
    fun saveRecipe(id: String?, recipe: Recipe) = io { service.repo.saveRecipe(id ?: service.repo.newId(), recipe) }

    suspend fun loadRecipe(id: String): Recipe? = withContext(Dispatchers.IO) { service.ensureReady(); service.repo.recipe(id) }
    fun restoreEntry(entry: FoodLogEntry) = io { service.repo.saveFoodLog(entry) }

    fun addWater(ml: Int) = io { service.repo.addHydration(currentDay(), ml) }

    /** Today's body weight, entered where the targets ask for it. */
    fun logWeight(kg: Double) = io {
        service.repo.saveMeasurement(com.cruxcoach.athlete.model.BodyMeasurement(service.today().toString(),
            com.cruxcoach.athlete.model.BodyMetric.WEIGHT.key, kg, "kg", System.currentTimeMillis()))
    }
    fun logHeight(cm: Double) = io {
        service.repo.saveMeasurement(com.cruxcoach.athlete.model.BodyMeasurement(service.today().toString(),
            com.cruxcoach.athlete.model.BodyMetric.HEIGHT.key, cm, "cm", System.currentTimeMillis()))
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
        val now = System.currentTimeMillis()
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
                barcode = barcode, source = SOURCE_USER, updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() }
    }

    companion object {
        /** How often the screen checks whether the date changed; tests shorten it. */
        @androidx.annotation.VisibleForTesting
        internal var dayTickMs = 60_000L
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
    }
}
