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
    val pauses: List<PausePeriod> = emptyList(),
    val benchmarks: List<Benchmark> = emptyList(),
) {
    val trainingRows: Int get() = workouts.size + sets.size + routines.size + customExercises.size + benchmarks.size
    val bodyRows: Int get() = measurements.size
    val fuelRows: Int get() = foodItems.size + foodLog.size + hydration.size
    val wellbeingRows: Int get() = checkins.size + injuries.size + pauses.size
    val isEmpty: Boolean get() = profile == null && trainingRows + bodyRows + fuelRows + wellbeingRows + favorites.size == 0

    fun onlyTraining() = AthleteSnapshot(profile = profile, workouts = workouts, sets = sets, routines = routines,
        customExercises = customExercises, favorites = favorites, checkins = checkins, injuries = injuries, pauses = pauses,
        benchmarks = benchmarks)
    fun onlyBody() = AthleteSnapshot(measurements = measurements)
    fun onlyFuel() = AthleteSnapshot(foodItems = foodItems, foodLog = foodLog, hydration = hydration)

    operator fun plus(other: AthleteSnapshot) = AthleteSnapshot(
        profile = other.profile ?: profile,
        workouts = workouts + other.workouts, sets = sets + other.sets, routines = routines + other.routines,
        customExercises = customExercises + other.customExercises, favorites = (favorites + other.favorites).distinct(),
        measurements = measurements + other.measurements, foodItems = foodItems + other.foodItems,
        foodLog = foodLog + other.foodLog, hydration = hydration + other.hydration, checkins = checkins + other.checkins,
        injuries = injuries + other.injuries, pauses = pauses + other.pauses,
        benchmarks = benchmarks + other.benchmarks,
    )
}
