package com.cruxcoach.athlete.data

import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.model.*
import kotlinx.serialization.Serializable

/**
 * Backup payload of the athlete database, carried as the additive
 * `athlete` field of the version-3 backup envelope. Older apps ignore the
 * field; this app ignores fields it does not know yet.
 */
@Serializable
data class AthleteSnapshot(
    val profile: AthleteProfile? = null,
    val workouts: List<Workout> = emptyList(),
    val sets: List<ExerciseSet> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val customExercises: List<ExerciseDefinition> = emptyList(),
    val favorites: List<String> = emptyList(),
    val measurements: List<BodyMeasurement> = emptyList(),
    val foodItems: List<FoodItem> = emptyList(),
    val foodLog: List<FoodLogEntry> = emptyList(),
    val hydration: List<HydrationEntry> = emptyList(),
    val checkins: List<Checkin> = emptyList(),
    val injuries: List<Injury> = emptyList(),
    /** Comeback diary (health data): travels only with the opt-in category "Verletzungen & Comeback-Tagebuch". */
    val comebackEntries: List<ComebackEntry> = emptyList(),
    val pauses: List<PausePeriod> = emptyList(),
    val benchmarks: List<Benchmark> = emptyList(),
    val climbingDays: List<ClimbingDayEntry> = emptyList(),
    val suggestionEvents: List<SuggestionEvent> = emptyList(),
    /** Ingredients of recipe foods by food item id (FEAT-069); the food item itself is in [foodItems]. */
    val recipes: Map<String, com.cruxcoach.athlete.logic.Recipe> = emptyMap(),
) {
    val trainingRows: Int get() = workouts.size + sets.size + routines.size + customExercises.size + benchmarks.size +
        climbingDays.size + suggestionEvents.size
    val bodyRows: Int get() = measurements.size
    val fuelRows: Int get() = foodItems.size + foodLog.size + hydration.size
    val wellbeingRows: Int get() = checkins.size + pauses.count { !it.isHealth() }
    /** Injuries, the comeback diary and pauses for an injury or an illness. */
    val healthRows: Int get() = injuries.size + comebackEntries.size + pauses.count { it.isHealth() }
    val isEmpty: Boolean get() = profile == null && trainingRows + bodyRows + fuelRows + wellbeingRows + healthRows + favorites.size == 0

    fun onlyTraining() = AthleteSnapshot(profile = profile, workouts = workouts, sets = sets, routines = routines,
        customExercises = customExercises, favorites = favorites, checkins = checkins, pauses = pauses.filterNot { it.isHealth() },
        benchmarks = benchmarks, climbingDays = climbingDays, suggestionEvents = suggestionEvents)
    /** Health data: injuries, the comeback diary and injury or illness pauses (start, end, note) – opt-in. */
    fun onlyHealth() = AthleteSnapshot(injuries = injuries, comebackEntries = comebackEntries, pauses = pauses.filter { it.isHealth() })
    fun onlyBody() = AthleteSnapshot(measurements = measurements)
    fun onlyFuel() = AthleteSnapshot(foodItems = foodItems, foodLog = foodLog, hydration = hydration, recipes = recipes)

    operator fun plus(other: AthleteSnapshot) = AthleteSnapshot(
        profile = other.profile ?: profile,
        workouts = workouts + other.workouts, sets = sets + other.sets, routines = routines + other.routines,
        customExercises = customExercises + other.customExercises, favorites = (favorites + other.favorites).distinct(),
        measurements = measurements + other.measurements, foodItems = foodItems + other.foodItems,
        foodLog = foodLog + other.foodLog, hydration = hydration + other.hydration, checkins = checkins + other.checkins,
        injuries = injuries + other.injuries, comebackEntries = comebackEntries + other.comebackEntries,
        pauses = pauses + other.pauses,
        benchmarks = benchmarks + other.benchmarks,
        climbingDays = climbingDays + other.climbingDays,
        suggestionEvents = suggestionEvents + other.suggestionEvents,
        recipes = recipes + other.recipes,
    )
}

/** A pause for an injury or an illness says something about health; a holiday does not. */
private fun PausePeriod.isHealth(): Boolean = reason == PauseReason.INJURY || reason == PauseReason.ILLNESS
