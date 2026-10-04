package com.cruxcoach.athlete.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.model.*
import com.cruxcoach.db.athlete.AthleteDatabase
import com.cruxcoach.db.athlete.Body_measurement
import com.cruxcoach.db.athlete.Checkin as CheckinRow
import com.cruxcoach.db.athlete.Exercise_set
import com.cruxcoach.db.athlete.Food_item
import com.cruxcoach.db.athlete.Food_log
import com.cruxcoach.db.athlete.Hydration_log
import com.cruxcoach.db.athlete.Injury as InjuryRow
import com.cruxcoach.db.athlete.Pause_period
import com.cruxcoach.db.athlete.Workout as WorkoutRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * All access to the athlete database (training, body, fueling, wellbeing).
 * Blocking calls are meant for an IO dispatcher; the `observe*` flows emit
 * on [dispatcher] whenever the underlying table changes.
 */
class AthleteRepository(
    private val db: AthleteDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val training get() = db.trainingQueries
    private val body get() = db.bodyQueries
    private val fuel get() = db.fuelQueries
    private val wellbeing get() = db.wellbeingQueries

    @OptIn(ExperimentalUuidApi::class)
    fun newId(): String = Uuid.random().toString()

    fun <T> transaction(block: () -> T): T = db.transactionWithResult { block() }

    // ── Profile ──────────────────────────────────────────────────────

    fun profile(): AthleteProfile =
        body.getSetting(KEY_PROFILE).executeAsOneOrNull()?.let { decodeProfile(it) } ?: AthleteProfile()

    fun observeProfile(): Flow<AthleteProfile> =
        body.getSetting(KEY_PROFILE).asFlow().mapToOneOrNull(dispatcher)
            .map { it?.let(::decodeProfile) ?: AthleteProfile() }

    fun saveProfile(profile: AthleteProfile) {
        body.putSetting(KEY_PROFILE, json.encodeToString(AthleteProfile.serializer(), profile), clock())
    }

    fun updateProfile(transform: (AthleteProfile) -> AthleteProfile) = transaction { saveProfile(transform(profile())) }

    private fun decodeProfile(text: String): AthleteProfile =
        runCatching { json.decodeFromString(AthleteProfile.serializer(), text) }.getOrDefault(AthleteProfile())

    // ── Workouts & sets ──────────────────────────────────────────────

    fun saveWorkout(w: Workout) = training.upsertWorkout(
        w.id, w.startedAt, w.endedAt, w.day, w.title, w.routineId, w.sessionRpe?.toLong(), w.notes, w.updatedAt,
    )

    fun workout(id: String): Workout? = training.getWorkout(id).executeAsOneOrNull()?.toModel()
    fun openWorkout(): Workout? = training.getOpenWorkout().executeAsOneOrNull()?.toModel()
    fun observeOpenWorkout(): Flow<Workout?> =
        training.getOpenWorkout().asFlow().mapToOneOrNull(dispatcher).map { it?.toModel() }

    fun workoutsBetween(fromDay: String, toDay: String): List<Workout> =
        training.getWorkoutsBetween(fromDay, toDay).executeAsList().map { it.toModel() }

    fun observeWorkoutsBetween(fromDay: String, toDay: String): Flow<List<Workout>> =
        training.getWorkoutsBetween(fromDay, toDay).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }

    fun recentWorkouts(limit: Int): List<Workout> = training.getRecentWorkouts(limit.toLong()).executeAsList().map { it.toModel() }
    fun observeRecentWorkouts(limit: Int): Flow<List<Workout>> =
        training.getRecentWorkouts(limit.toLong()).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }

    fun finishWorkout(id: String, endedAt: Long, sessionRpe: Int?, notes: String?) =
        training.finishWorkout(endedAt, sessionRpe?.toLong(), notes, clock(), id)

    fun deleteWorkout(id: String) = transaction {
        training.deleteSetsForWorkout(id)
        training.deleteWorkout(id)
    }

    fun saveSet(s: ExerciseSet) = training.upsertSet(
        s.id, s.workoutId, s.exerciseSlug, s.blockIndex.toLong(), s.setIndex.toLong(), s.setType.name, s.side?.name,
        s.targetReps?.toLong(), s.targetDurationS, s.targetLoadKg,
        s.reps?.toLong(), s.durationS, s.loadKg, s.edgeMm, s.grip?.name, s.rir?.toLong(),
        s.workS, s.restBetweenS, s.repsPerSet?.toLong(), s.restS?.toLong(), s.bodyweightKg, s.completedAt, s.note,
    )

    fun setsFor(workoutId: String): List<ExerciseSet> = training.getSetsForWorkout(workoutId).executeAsList().map { it.toModel() }
    fun observeSets(workoutId: String): Flow<List<ExerciseSet>> =
        training.getSetsForWorkout(workoutId).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }

    fun deleteSet(id: String) = training.deleteSet(id)
    fun deleteBlock(workoutId: String, blockIndex: Int) = training.deleteSetsForBlock(workoutId, blockIndex.toLong())

    /** Completed sets of one exercise, newest first. */
    fun history(slug: String, limit: Int = 200): List<ExerciseSet> =
        training.getCompletedSetsForExercise(slug, limit.toLong()).executeAsList().map { it.toModel() }

    fun completedSetsSince(epochMillis: Long): List<ExerciseSet> =
        training.getCompletedSetsSince(epochMillis).executeAsList().map { it.toModel() }

    data class TrainedExercise(val slug: String, val sets: Long, val lastAt: Long?)

    fun trainedExercises(): List<TrainedExercise> =
        training.getTrainedExerciseSlugs().executeAsList().map { TrainedExercise(it.exercise_slug, it.cnt, it.last_at) }

    // ── Routines ─────────────────────────────────────────────────────

    private val itemsSerializer = ListSerializer(RoutineItem.serializer())

    fun saveRoutine(r: Routine) = training.upsertRoutine(
        r.id, r.name, r.notes, json.encodeToString(itemsSerializer, r.items), r.createdAt, r.updatedAt,
    )

    fun routines(): List<Routine> = training.getRoutines().executeAsList().map {
        Routine(it.id, it.name, it.notes, decodeItems(it.items_json), it.created_at, it.updated_at)
    }

    fun observeRoutines(): Flow<List<Routine>> = training.getRoutines().asFlow().mapToList(dispatcher).map { rows ->
        rows.map { Routine(it.id, it.name, it.notes, decodeItems(it.items_json), it.created_at, it.updated_at) }
    }

    fun deleteRoutine(id: String) = training.deleteRoutine(id)

    private fun decodeItems(text: String): List<RoutineItem> =
        runCatching { json.decodeFromString(itemsSerializer, text) }.getOrDefault(emptyList())

    // ── Custom exercises & favourites ────────────────────────────────

    fun customExercises(): List<ExerciseDefinition> = training.getCustomExercises().executeAsList().mapNotNull {
        runCatching { ExerciseCatalog.decodeDefinition(it.definition_json).copy(custom = true) }.getOrNull()
    }

    fun saveCustomExercise(def: ExerciseDefinition) =
        training.upsertCustomExercise(def.slug, ExerciseCatalog.encodeDefinition(def.copy(custom = true)), clock())

    fun deleteCustomExercise(slug: String) = training.deleteCustomExercise(slug)

    fun favorites(): Set<String> = training.getFavorites().executeAsList().map { it.slug }.toSet()
    fun observeFavorites(): Flow<Set<String>> =
        training.getFavorites().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.slug }.toSet() }

    fun setFavorite(slug: String, favorite: Boolean) {
        if (favorite) training.addFavorite(slug, clock()) else training.removeFavorite(slug)
    }

    // ── Performance values ───────────────────────────────────────────

    private val benchmarkQueries get() = db.benchmarksQueries

    fun saveBenchmark(b: Benchmark) = benchmarkQueries.upsertBenchmark(
        b.id, b.exerciseSlug, b.side?.name, b.edgeMm, b.grip?.name, b.loadKg, b.reps?.toLong(), b.durationS,
        b.bodyweightKg, b.source.name, b.measuredAt, b.note,
    )

    /** Newest first. */
    fun benchmarks(slug: String): List<Benchmark> = benchmarkQueries.getBenchmarksForExercise(slug).executeAsList().map { it.toModel() }
    fun observeBenchmarks(slug: String): Flow<List<Benchmark>> =
        benchmarkQueries.getBenchmarksForExercise(slug).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }
    fun allBenchmarks(): List<Benchmark> = benchmarkQueries.getAllBenchmarks().executeAsList().map { it.toModel() }
    fun observeAllBenchmarks(): Flow<List<Benchmark>> =
        benchmarkQueries.getAllBenchmarks().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }
    fun deleteBenchmark(id: String) = benchmarkQueries.deleteBenchmark(id)

    // ── Body ─────────────────────────────────────────────────────────

    fun saveMeasurement(m: BodyMeasurement) =
        body.upsertMeasurement(m.day, m.metric, m.value, m.unit, m.measuredAt, m.source)

    fun deleteMeasurement(day: String, metric: String) = body.deleteMeasurement(day, metric)

    fun series(metric: String): List<BodyMeasurement> = body.getSeries(metric).executeAsList().map { it.toModel() }
    fun observeSeries(metric: String): Flow<List<BodyMeasurement>> =
        body.getSeries(metric).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }

    fun latest(metric: String): BodyMeasurement? = body.getLatest(metric).executeAsOneOrNull()?.toModel()
    fun observeRecentMeasurements(limit: Int): Flow<List<BodyMeasurement>> =
        body.getRecentEntries(limit.toLong()).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }

    fun allMeasurements(): List<BodyMeasurement> = body.getAllMeasurements().executeAsList().map { it.toModel() }

    /**
     * One-time copy of the 0.1–0.2.3 `body_stats` rows into the new table.
     * Never overwrites a value the new table already has for that day.
     */
    fun importLegacyBodyStats(rows: List<LegacyBodyStat>): Int = transaction {
        var added = 0
        rows.forEach { row ->
            val day = row.date.take(10)
            if (day.length != 10 || !row.value.isFinite()) return@forEach
            val before = body.countMeasurements().executeAsOne()
            body.insertMeasurementIfAbsent(day, BodyMetric.legacyKey(row.statName), row.value, row.unit,
                clock(), BodyMeasurement.SOURCE_LEGACY)
            if (body.countMeasurements().executeAsOne() > before) added++
        }
        added
    }

    data class LegacyBodyStat(val date: String, val statName: String, val value: Double, val unit: String)

    // ── Fueling ──────────────────────────────────────────────────────

    fun saveFoodItem(f: FoodItem) = fuel.upsertFoodItem(
        f.id, f.name, f.brand, f.barcode, f.kcalPer100, f.proteinPer100, f.carbsPer100, f.fatPer100,
        f.servingG, f.servingLabel, if (f.favorite) 1 else 0, f.source, f.useCount.toLong(), f.lastUsedAt, f.updatedAt,
    )

    fun foodItem(id: String): FoodItem? = fuel.getFoodItem(id).executeAsOneOrNull()?.toModel()
    fun foodItems(): List<FoodItem> = fuel.getFoodItems().executeAsList().map { it.toModel() }
    fun observeFoodItems(): Flow<List<FoodItem>> = fuel.getFoodItems().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }
    fun searchFoodItems(query: String): List<FoodItem> = fuel.searchFoodItems(query, query).executeAsList().map { it.toModel() }
    fun markFoodItemUsed(id: String) = fuel.markFoodItemUsed(clock(), id)
    fun setFoodItemFavorite(id: String, favorite: Boolean) = fuel.setFoodItemFavorite(if (favorite) 1 else 0, clock(), id)
    fun deleteFoodItem(id: String) = fuel.deleteFoodItem(id)

    fun saveFoodLog(e: FoodLogEntry) = fuel.upsertFoodLog(
        e.id, e.day, e.loggedAt, e.meal.name, e.foodItemId, e.name, e.amountG, e.portions, e.kcal, e.proteinG, e.carbsG, e.fatG,
    )

    fun foodLog(day: String): List<FoodLogEntry> = fuel.getFoodLogForDay(day).executeAsList().map { it.toModel() }
    fun observeFoodLog(day: String): Flow<List<FoodLogEntry>> =
        fuel.getFoodLogForDay(day).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }
    fun foodLogBetween(from: String, to: String): List<FoodLogEntry> = fuel.getFoodLogBetween(from, to).executeAsList().map { it.toModel() }
    fun recentFoodLog(limit: Int): List<FoodLogEntry> = fuel.getRecentFoodLog(limit.toLong()).executeAsList().map { it.toModel() }
    fun deleteFoodLog(id: String) = fuel.deleteFoodLog(id)

    fun addHydration(day: String, ml: Int) = fuel.insertHydration(newId(), day, clock(), ml.toLong())
    fun hydration(day: String): List<HydrationEntry> = fuel.getHydrationForDay(day).executeAsList().map { it.toModel() }
    fun observeHydration(day: String): Flow<List<HydrationEntry>> =
        fuel.getHydrationForDay(day).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toModel() } }
    fun hydrationBetween(from: String, to: String): List<HydrationEntry> = fuel.getHydrationBetween(from, to).executeAsList().map { it.toModel() }
    fun deleteHydration(id: String) = fuel.deleteHydration(id)

    // ── Wellbeing ────────────────────────────────────────────────────

    fun saveCheckin(c: Checkin) = wellbeing.upsertCheckin(
        c.day, c.createdAt, c.sleep?.toLong(), c.energy?.toLong(), c.skin?.toLong(), c.fingers?.toLong(),
        c.motivation?.toLong(), if (c.sick) 1 else 0, c.note,
    )

    fun checkin(day: String): Checkin? = wellbeing.getCheckin(day).executeAsOneOrNull()?.toModel()
    fun observeCheckin(day: String): Flow<Checkin?> = wellbeing.getCheckin(day).asFlow().mapToOneOrNull(dispatcher).map { it?.toModel() }
    fun checkinsBetween(from: String, to: String): List<Checkin> = wellbeing.getCheckinsBetween(from, to).executeAsList().map { it.toModel() }
    fun deleteCheckin(day: String) = wellbeing.deleteCheckin(day)

    fun saveInjury(i: Injury) = wellbeing.upsertInjury(
        i.id, i.region.name, i.side?.name, i.detail, i.severity.toLong(), if (i.climbingPaused) 1 else 0,
        i.startedOn, i.resolvedOn, i.note, i.updatedAt,
    )

    fun activeInjuries(): List<Injury> = wellbeing.getActiveInjuries().executeAsList().mapNotNull { it.toModel() }
    fun observeActiveInjuries(): Flow<List<Injury>> =
        wellbeing.getActiveInjuries().asFlow().mapToList(dispatcher).map { rows -> rows.mapNotNull { it.toModel() } }
    fun allInjuries(): List<Injury> = wellbeing.getAllInjuries().executeAsList().mapNotNull { it.toModel() }
    fun deleteInjury(id: String) = wellbeing.deleteInjury(id)

    fun savePause(p: PausePeriod) = wellbeing.upsertPause(p.id, p.reason.name, p.startDay, p.endDay, p.note)
    fun pauses(): List<PausePeriod> = wellbeing.getPauses().executeAsList().mapNotNull { it.toModel() }
    fun observePauses(): Flow<List<PausePeriod>> = wellbeing.getPauses().asFlow().mapToList(dispatcher).map { rows -> rows.mapNotNull { it.toModel() } }
    fun openPause(): PausePeriod? = wellbeing.getOpenPause().executeAsOneOrNull()?.toModel()
    fun deletePause(id: String) = wellbeing.deletePause(id)

    // ── Backup snapshot ──────────────────────────────────────────────

    fun snapshot(): AthleteSnapshot = AthleteSnapshot(
        profile = profile(),
        workouts = training.getAllWorkouts().executeAsList().map { it.toModel() },
        sets = training.getAllSets().executeAsList().map { it.toModel() },
        routines = routines(),
        customExercises = customExercises(),
        favorites = favorites().toList(),
        measurements = allMeasurements(),
        foodItems = foodItems(),
        foodLog = fuel.getAllFoodLog().executeAsList().map { it.toModel() },
        hydration = fuel.getAllHydration().executeAsList().map { it.toModel() },
        checkins = wellbeing.getAllCheckins().executeAsList().map { it.toModel() },
        injuries = allInjuries(),
        pauses = pauses(),
        benchmarks = allBenchmarks(),
    )

    /**
     * Merges a snapshot into the database. Rows are keyed by their ids (or
     * day+metric / day), so importing the same backup twice changes nothing,
     * a restore never deletes data the device already has, and a row the
     * device changed later than the backup wins: an older backup cannot
     * reopen a finished training or revive a healed injury.
     */
    fun restore(snapshot: AthleteSnapshot, includeProfile: Boolean): Int = transaction {
        var rows = 0
        val incomingProfile = snapshot.profile
        val local = profile()
        // Settings come back only onto a device that has none of its own yet
        // (bookkeeping flags aside); this device's migration flag is kept.
        val untouched = local.copy(legacyBodyStatsImported = false) == AthleteProfile()
        if (includeProfile && incomingProfile != null && untouched) {
            saveProfile(incomingProfile.copy(legacyBodyStatsImported = local.legacyBodyStatsImported)); rows++
        }
        val keptLocal = mutableSetOf<String>()
        snapshot.workouts.forEach { w ->
            val local = workout(w.id)
            if (local != null && local.updatedAt > w.updatedAt) keptLocal += w.id else { saveWorkout(w); rows++ }
        }
        snapshot.sets.filter { it.workoutId !in keptLocal }.forEach { saveSet(it); rows++ }
        snapshot.routines.forEach { r ->
            val local = training.getRoutine(r.id).executeAsOneOrNull()
            if (local == null || local.updated_at <= r.updatedAt) { saveRoutine(r); rows++ }
        }
        snapshot.customExercises.forEach { saveCustomExercise(it); rows++ }
        snapshot.favorites.forEach { training.addFavorite(it, clock()); rows++ }
        snapshot.measurements.forEach { m ->
            val local = body.getMeasurement(m.day, m.metric).executeAsOneOrNull()
            if (local == null || local.measured_at <= m.measuredAt) { saveMeasurement(m); rows++ }
        }
        snapshot.foodItems.forEach { f ->
            val local = fuel.getFoodItem(f.id).executeAsOneOrNull()
            if (local == null || local.updated_at <= f.updatedAt) { saveFoodItem(f); rows++ }
        }
        snapshot.foodLog.forEach { saveFoodLog(it); rows++ }
        snapshot.hydration.forEach { fuel.insertHydration(it.id, it.day, it.loggedAt, it.ml.toLong()); rows++ }
        snapshot.checkins.forEach { c ->
            val local = wellbeing.getCheckin(c.day).executeAsOneOrNull()
            if (local == null || local.created_at <= c.createdAt) { saveCheckin(c); rows++ }
        }
        snapshot.injuries.forEach { i ->
            val local = wellbeing.getInjury(i.id).executeAsOneOrNull()
            if (local == null || local.updated_at <= i.updatedAt) { saveInjury(i); rows++ }
        }
        snapshot.benchmarks.forEach { b ->
            if (benchmarkQueries.getBenchmark(b.id).executeAsOneOrNull() == null) { saveBenchmark(b); rows++ }
        }
        snapshot.pauses.forEach { p ->
            val local = wellbeing.getPauses().executeAsList().firstOrNull { it.id == p.id }
            // An ended pause never becomes open again.
            if (local == null || local.end_day == null || p.endDay != null) { savePause(p); rows++ }
        }
        rows
    }

    companion object {
        const val KEY_PROFILE = "profile"
    }
}

// ── Row mappers ──────────────────────────────────────────────────────

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

private fun WorkoutRow.toModel() = Workout(id, started_at, ended_at, day, title, routine_id, session_rpe?.toInt(), notes, updated_at)

private fun Exercise_set.toModel() = ExerciseSet(
    id = id, workoutId = workout_id, exerciseSlug = exercise_slug, blockIndex = block_index.toInt(),
    setIndex = set_index.toInt(), setType = enumOrNull<SetType>(set_type) ?: SetType.WORK, side = enumOrNull<Side>(side),
    targetReps = target_reps?.toInt(), targetDurationS = target_duration_s, targetLoadKg = target_load_kg,
    reps = reps?.toInt(), durationS = duration_s, loadKg = load_kg, edgeMm = edge_mm, grip = enumOrNull<Grip>(grip),
    rir = rir?.toInt(), workS = work_s, restBetweenS = rest_between_s, repsPerSet = reps_per_set?.toInt(),
    restS = rest_s?.toInt(), bodyweightKg = bodyweight_kg, completedAt = completed_at, note = note,
)

private fun Body_measurement.toModel() = BodyMeasurement(day, metric, value_, unit, measured_at, source)

private fun Food_item.toModel() = FoodItem(
    id, name, brand, barcode, kcal_per_100, protein_per_100, carbs_per_100, fat_per_100, serving_g, serving_label,
    favorite != 0L, source, use_count.toInt(), last_used_at, updated_at,
)

private fun Food_log.toModel() = FoodLogEntry(
    id, day, logged_at, enumOrNull<Meal>(meal) ?: Meal.SNACK, food_item_id, name, amount_g, portions, kcal, protein_g, carbs_g, fat_g,
)

private fun Hydration_log.toModel() = HydrationEntry(id, day, logged_at, ml.toInt())

private fun CheckinRow.toModel() = Checkin(
    day, created_at, sleep?.toInt(), energy?.toInt(), skin?.toInt(), fingers?.toInt(), motivation?.toInt(), sick != 0L, note,
)

private fun InjuryRow.toModel(): Injury? {
    val region = enumOrNull<InjuryRegion>(region) ?: return null
    return Injury(id, region, enumOrNull<InjurySide>(side), detail, severity.toInt(), climbing_paused != 0L,
        started_on, resolved_on, note, updated_at)
}

private fun com.cruxcoach.db.athlete.Exercise_benchmark.toModel() = Benchmark(
    id = id, exerciseSlug = exercise_slug, side = enumOrNull<Side>(side), edgeMm = edge_mm, grip = enumOrNull<Grip>(grip),
    loadKg = load_kg, reps = reps?.toInt(), durationS = duration_s, bodyweightKg = bodyweight_kg,
    source = enumOrNull<BenchmarkSource>(source) ?: BenchmarkSource.MANUAL, measuredAt = measured_at, note = note,
)

private fun Pause_period.toModel(): PausePeriod? {
    val reason = enumOrNull<PauseReason>(reason) ?: return null
    return PausePeriod(id, reason, start_day, end_day, note)
}
