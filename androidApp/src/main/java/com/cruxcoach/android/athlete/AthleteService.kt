package com.cruxcoach.android.athlete

import android.content.Context
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.db.secure.SecureDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** The packaged exercise catalogue plus the athlete's own exercises, loaded once off the main thread. */
@Singleton
class ExerciseCatalogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: dagger.Lazy<AthleteRepository>,
) {
    private val _catalog = MutableStateFlow(ExerciseCatalog.EMPTY)
    val catalog: StateFlow<ExerciseCatalog> = _catalog.asStateFlow()
    @Volatile private var packaged: ExerciseCatalog? = null
    @Volatile private var dirty = true
    private val mutex = Mutex()

    suspend fun ensureLoaded(): ExerciseCatalog = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!dirty) _catalog.value else reload()
        }
    }

    /** Custom exercises changed outside the app's own screens (a restore). */
    fun invalidate() { dirty = true }

    /** Call after custom exercises changed. */
    suspend fun refresh(): ExerciseCatalog = withContext(Dispatchers.IO) { mutex.withLock { reload() } }

    private fun reload(): ExerciseCatalog {
        val base = packaged ?: context.assets.open(ASSET).bufferedReader().use { ExerciseCatalog.parse(it.readText()) }
            .also { packaged = it }
        return base.withCustom(repository.get().customExercises()).also { _catalog.value = it; dirty = false }
    }

    companion object {
        const val ASSET = "training/exercise_catalog.json"
    }
}

/**
 * Climbing days from the board logbook (sessions, sends, attempts) — the
 * automatic half of "what did I train", so nobody logs a board session twice.
 */
@Singleton
class ClimbingDaysReader @Inject constructor(private val secureDb: SecureDatabase) {

    data class ClimbingDay(val day: String, val minutes: Int, val efforts: Int)

    fun since(fromDay: String): Map<String, ClimbingDay> {
        val q = secureDb.trainingDaysQueries
        // SQLite SUM() comes back as REAL for expressions; normalise to whole units.
        val minutes = q.boardSessionMinutesSince(fromDay).executeAsList()
            .associate { row -> row.day to ((row.active_seconds ?: 0.0) / 60.0).toInt() }
        val sends = q.ascentCountsSince(fromDay).executeAsList()
            .associate { row -> row.day to (row.sends + (row.tries ?: 0L)).toInt() }
        val tries = q.bidCountsSince(fromDay).executeAsList()
            .associate { row -> row.day to (row.tries ?: 0.0).toInt() }
        return (minutes.keys + sends.keys + tries.keys).filter { it.length == 10 }.associateWith { day ->
            ClimbingDay(day, minutes[day] ?: 0, (sends[day] ?: 0) + (tries[day] ?: 0))
        }
    }
}

/**
 * Application service for the training, body and fueling features. Screens
 * talk to this instead of the repository so cross-cutting rules (injury
 * filter, ghost values, rest timer, body weight snapshot) live in one place.
 * All methods block; call them from an IO dispatcher.
 */
@Singleton
class AthleteService @Inject constructor(
    private val repoLazy: dagger.Lazy<AthleteRepository>,
    val catalogStore: ExerciseCatalogStore,
    private val climbingDays: ClimbingDaysReader,
    private val bodyStatRepository: BodyStatRepository,
    private val sessionManager: BoardSessionManager,
) {
    val repo: AthleteRepository get() = repoLazy.get()
    val catalog: ExerciseCatalog get() = catalogStore.catalog.value

    private val readyMutex = Mutex()
    @Volatile private var ready = false

    /** Opens the database, loads the catalogue and imports 0.2.3 body stats once. */
    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        catalogStore.ensureLoaded()
        readyMutex.withLock {
            if (ready) return@withLock
            importLegacyBodyStats()
            ready = true
        }
    }

    private fun importLegacyBodyStats() {
        val profile = repo.profile()
        if (profile.legacyBodyStatsImported) return
        val legacy = runCatching { bodyStatRepository.getAll() }.getOrDefault(emptyList())
        repo.importLegacyBodyStats(legacy.map { AthleteRepository.LegacyBodyStat(it.date, it.statName, it.value, it.unit) })
        repo.saveProfile(profile.copy(legacyBodyStatsImported = true))
    }

    fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    // ── Activity ─────────────────────────────────────────────────────

    /** Board days plus logged trainings, per day, for [days] days up to today. */
    fun activities(days: Int): Map<String, DayActivity> {
        val today = today()
        val from = today.minus(DatePeriod(days = days - 1)).toString()
        val climbing = runCatching { climbingDays.since(from) }.getOrDefault(emptyMap())
        val workouts = repo.workoutsBetween(from, today.toString()).filter { !it.isOpen }.groupBy { it.day }
        return (climbing.keys + workouts.keys).associateWith { day ->
            val c = climbing[day]
            val w = workouts[day].orEmpty()
            val minutes = w.sumOf { it.durationMinutes ?: 0 }
            DayActivity(
                day = day,
                climbingMinutes = c?.minutes ?: 0,
                climbingEfforts = c?.efforts ?: 0,
                workoutMinutes = minutes,
                workoutLoad = w.sumOf { (it.durationMinutes ?: 0) * (it.sessionRpe ?: 6) },
            )
        }
    }

    fun streak(profile: AthleteProfile, activities: Map<String, DayActivity>): StreakState {
        val trainingDays = activities.values.filter { it.trained }.mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }.toSet()
        val weeks = ConsistencyStreak.weeksFrom(trainingDays, repo.pauses(), today(), weekCount = 26)
        return ConsistencyStreak.compute(weeks, profile.weeklyGoal)
    }

    // ── Body ─────────────────────────────────────────────────────────

    fun weightTrend(): List<TrendWeight.Point> = TrendWeight.compute(
        repo.series(BodyMetric.WEIGHT.key).mapNotNull { m -> runCatching { LocalDate.parse(m.day) to m.value }.getOrNull() },
    )

    /** Trend weight (not the noisy last reading) — what relative loads and targets use. */
    fun currentBodyweight(): Double? = weightTrend().lastOrNull()?.trend

    fun heightCm(): Double? = repo.latest(BodyMetric.HEIGHT.key)?.value

    // ── Workouts ─────────────────────────────────────────────────────

    /** Serialises structural edits so a double tap cannot open two trainings or reuse a block number. */
    private val structureLock = Any()

    fun startWorkout(routine: Routine?, title: String?): String = synchronized(structureLock) {
        repo.openWorkout()?.let { return it.id }
        val now = System.currentTimeMillis()
        val id = repo.newId()
        repo.transaction {
            repo.saveWorkout(Workout(id, now, null, today().toString(), title ?: routine?.name, routine?.id, updatedAt = now))
            routine?.items?.forEachIndexed { index, item -> addItem(id, index, item) }
        }
        id
    }

    /** Appends an exercise; returns false when the open injury rules it out. */
    fun addExercise(workoutId: String, slug: String): Boolean = synchronized(structureLock) {
        val def = catalog[slug] ?: return false
        val nextBlock = (repo.setsFor(workoutId).maxOfOrNull { it.blockIndex } ?: -1) + 1
        addItem(workoutId, nextBlock, WorkoutPlanner.itemFor(def))
    }

    private fun addItem(workoutId: String, blockIndex: Int, item: RoutineItem): Boolean {
        val planned = WorkoutPlanner.plan(
            workoutId, blockIndex, item, catalog, repo.history(item.slug, 60), repo.activeInjuries(),
            currentBodyweight(), repo::newId,
        )
        planned.sets.forEach(repo::saveSet)
        return !planned.skippedForInjury
    }

    /**
     * Marks a set done and, if the athlete wants that, starts the shared rest
     * timer (the same one the board player uses: banner on every screen,
     * Doze-safe alarm, survives process death). Returns a record if one fell.
     */
    fun completeSet(set: ExerciseSet, startRest: Boolean): PersonalRecord? {
        val done = set.copy(completedAt = System.currentTimeMillis(), bodyweightKg = set.bodyweightKg ?: currentBodyweight())
        val history = repo.history(set.exerciseSlug, HISTORY_LIMIT).filter { it.id != set.id }
        repo.saveSet(done)
        if (startRest) (done.restS ?: catalog[set.exerciseSlug]?.defaults?.restS)?.takeIf { it > 0 }?.let(::startRest)
        return PersonalRecords.detect(catalog.fallbackFor(set.exerciseSlug), done, history)
    }

    fun reopenSet(set: ExerciseSet) = repo.saveSet(set.copy(completedAt = null))
    fun updateSet(set: ExerciseSet) = repo.saveSet(set)
    fun addSet(workoutId: String, blockIndex: Int) {
        val block = repo.setsFor(workoutId).filter { it.blockIndex == blockIndex }
        WorkoutPlanner.extraSet(block, repo::newId).forEach(repo::saveSet)
    }
    fun removeSet(id: String) = repo.deleteSet(id)
    fun removeBlock(workoutId: String, blockIndex: Int) = repo.deleteBlock(workoutId, blockIndex)

    /** Swaps a whole block to another exercise (progression chain "easier/harder"). */
    fun swapBlock(workoutId: String, blockIndex: Int, newSlug: String) {
        val def = catalog[newSlug] ?: return
        repo.transaction {
            repo.deleteBlock(workoutId, blockIndex)
            addItem(workoutId, blockIndex, WorkoutPlanner.itemFor(def))
        }
    }

    fun startRest(seconds: Int) = sessionManager.startRestTimer(seconds)

    fun finishWorkout(id: String, sessionRpe: Int?, notes: String?): WorkoutSummary {
        val workout = repo.workout(id)
        val end = System.currentTimeMillis()
        // Planned sets never done are dropped: the log shows what happened.
        repo.transaction {
            repo.setsFor(id).filter { !it.isCompleted }.forEach { repo.deleteSet(it.id) }
            repo.finishWorkout(id, end, sessionRpe, notes?.takeIf { it.isNotBlank() })
        }
        return summarize(id, workout?.let { ((end - it.startedAt) / 60_000L).toInt() })
    }

    /** Records are judged against what came before this training — also when an old one is reopened. */
    fun summarize(id: String, durationMinutes: Int? = repo.workout(id)?.durationMinutes): WorkoutSummary {
        val startedAt = repo.workout(id)?.startedAt ?: Long.MAX_VALUE
        return WorkoutSummarizer.summarize(repo.setsFor(id), durationMinutes, catalog,
            historyBefore = { slug ->
                repo.history(slug, HISTORY_LIMIT).filter { it.workoutId != id && (it.completedAt ?: 0L) < startedAt }
            },
            smallestIncrementKg = repo.profile().smallestIncrementKg)
    }

    fun discardWorkout(id: String) = repo.deleteWorkout(id)

    // ── Backup ───────────────────────────────────────────────────────

    fun backupSnapshot(): com.cruxcoach.athlete.data.AthleteSnapshot = repo.snapshot()

    /** Restores a backup part and makes restored custom exercises visible without a restart. */
    fun restoreBackup(snapshot: com.cruxcoach.athlete.data.AthleteSnapshot, includeProfile: Boolean): Int =
        repo.restore(snapshot, includeProfile).also { catalogStore.invalidate() }

    fun importBackupBodyStats(rows: List<com.cruxcoach.domain.model.BodyStat>): Int =
        repo.importLegacyBodyStats(rows.map { AthleteRepository.LegacyBodyStat(it.date, it.statName, it.value, it.unit) })

    companion object {
        /** Enough history for every exercise's best values; far above a lifetime of sets per slug and side. */
        const val HISTORY_LIMIT = 20_000
    }

    fun saveRoutineFromWorkout(workoutId: String, name: String): Routine {
        val sets = repo.setsFor(workoutId)
        val items = sets.groupBy { it.blockIndex }.toSortedMap().values.map { block ->
            // Inserted warm-up rows sort first; the routine describes the work sets.
            val work = block.filter { it.setType != SetType.WARMUP }.ifEmpty { block }
            val first = work.first()
            val rows = work.map { it.setIndex }.distinct().size
            RoutineItem(
                slug = first.exerciseSlug, sets = rows,
                repsMin = first.targetReps ?: first.reps, repsMax = first.targetReps ?: first.reps,
                durationS = (first.targetDurationS ?: first.durationS)?.toInt(), loadKg = first.loadKg,
                restS = first.restS, edgeMm = first.edgeMm?.toInt(), grip = first.grip,
                workS = first.workS?.toInt(), restBetweenS = first.restBetweenS?.toInt(), repsPerSet = first.repsPerSet,
                warmup = first.setType == SetType.WARMUP, test = first.setType == SetType.TEST,
            )
        }
        val now = System.currentTimeMillis()
        return Routine(repo.newId(), name, items = items, createdAt = now, updatedAt = now).also(repo::saveRoutine)
    }

    // ── Fueling ──────────────────────────────────────────────────────

    fun fuelTargets(dayActivity: DayActivity?, profile: AthleteProfile): FuelTargets.Targets? {
        val weight = currentBodyweight() ?: return null
        return FuelTargets.compute(weight, dayActivity, profile.proteinPerKg)
    }

    fun redsSignals(profile: AthleteProfile, activities: Map<String, DayActivity>): List<RedsSignal> {
        val today = today()
        val from = today.minus(DatePeriod(days = 6)).toString()
        val logs = repo.foodLogBetween(from, today.toString()).groupBy { it.day }
        val fuelDays = (0..6).map { back ->
            val day = today.minus(DatePeriod(days = 6 - back)).toString()
            val entries = logs[day].orEmpty()
            FuelDay(day, logged = entries.isNotEmpty(), carbsG = entries.mapNotNull { it.carbsG }.takeIf { it.isNotEmpty() }?.sum(),
                dayLoad = FuelTargets.dayLoad(activities[day]))
        }
        return RedsGuard.evaluate(profile, heightCm(), weightTrend(), if (profile.fuelEnabled) fuelDays else emptyList())
    }
}
