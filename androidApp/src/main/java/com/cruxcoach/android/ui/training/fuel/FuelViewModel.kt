package com.cruxcoach.android.ui.training.fuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.logic.DayLoad
import com.cruxcoach.athlete.logic.FuelTargets
import com.cruxcoach.athlete.logic.RedsSignal
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val redsSignals: List<RedsSignal> = emptyList(),
) {
    val protein: Double get() = entries.sumOf { it.proteinG ?: 0.0 }
    val carbs: Double get() = entries.sumOf { it.carbsG ?: 0.0 }
    val fat: Double get() = entries.sumOf { it.fatG ?: 0.0 }
    val kcal: Double get() = entries.sumOf { it.kcal ?: 0.0 }
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
    private val selectedDay = MutableStateFlow<LocalDate?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            if (selectedDay.value == null) selectedDay.value = service.today()
            selectedDay.flatMapLatest { day ->
                val d = (day ?: service.today()).toString()
                combine(
                    repo.observeProfile(),
                    repo.observeFoodLog(d),
                    repo.observeHydration(d),
                    repo.observeFoodItems(),
                ) { profile, entries, water, foods -> Snapshot(day ?: service.today(), profile, entries, water, foods) }
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
        val activity = activities[s.day.toString()]
        val previous = service.repo.foodLog(s.day.minus(DatePeriod(days = 1)).toString()).size
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
                redsSignals = if (s.profile.fuelEnabled) service.redsSignals(s.profile, activities) else emptyList(),
            )
        }
    }

    // ── Navigation between days ──────────────────────────────────────

    fun previousDay() { selectedDay.value?.let { selectedDay.value = it.minus(DatePeriod(days = 1)) } }

    fun nextDay() {
        val day = selectedDay.value ?: return
        val today = service.today()
        if (day < today) selectedDay.value = day.plus(DatePeriod(days = 1))
    }

    fun goToday() { selectedDay.value = service.today() }

    private fun currentDay(): String = (selectedDay.value ?: service.today()).toString()

    // ── Module switch ────────────────────────────────────────────────

    fun enableFuel() = io { service.repo.updateProfile { it.copy(fuelEnabled = true, fuelIntroAccepted = true) } }

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
        repo.saveFoodLog(
            FoodLogEntry(
                id = repo.newId(), day = currentDay(), loggedAt = now, meal = meal, foodItemId = item.id, name = item.name,
                amountG = grams ?: if (item.source != SOURCE_PORTION) portions?.let { p -> item.servingG?.let { it * p } } else null,
                portions = portions, kcal = n.kcal, proteinG = n.protein, carbsG = n.carbs, fatG = n.fat,
            ),
        )
        repo.markFoodItemUsed(item.id)
    }

    fun deleteEntry(entry: FoodLogEntry) = io { service.repo.deleteFoodLog(entry.id) }
    fun restoreEntry(entry: FoodLogEntry) = io { service.repo.saveFoodLog(entry) }

    fun addWater(ml: Int) = io { service.repo.addHydration(currentDay(), ml) }
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

    fun createFood(name: String, brand: String?, per100: Nutrients, servingG: Double?, servingLabel: String?) = io {
        val repo = service.repo
        repo.saveFoodItem(
            FoodItem(
                id = repo.newId(), name = name, brand = brand?.takeIf { it.isNotBlank() },
                kcalPer100 = per100.kcal, proteinPer100 = per100.protein, carbsPer100 = per100.carbs, fatPer100 = per100.fat,
                servingG = servingG, servingLabel = servingLabel?.takeIf { it.isNotBlank() },
                source = SOURCE_USER, updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() }
    }

    companion object {
        /** Nutrient fields hold the values of one portion. */
        const val SOURCE_PORTION = "portion"
        /** Nutrient fields hold values per 100 g. */
        const val SOURCE_USER = "user"

        fun isPerPortion(item: FoodItem) = item.source == SOURCE_PORTION

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
