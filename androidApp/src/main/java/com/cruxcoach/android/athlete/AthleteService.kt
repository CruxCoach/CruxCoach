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
import kotlinx.datetime.atStartOfDayIn
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

    private val logbook by lazy { com.cruxcoach.data.repository.PersonalBoardRepositoryImpl(secureDb) }

    /**
     * Every logbook row (sends and failed sessions on all boards) for the
     * coach: grades, tries, flashes. Reads the light logbook snapshot the
     * playlist generator uses, without frame blobs.
     */
    fun logbookRows(): List<com.cruxcoach.athlete.logic.ClimbRow> {
        val all = logbook.getUserLogbookAllLight()
        val flashUuids = com.cruxcoach.android.ui.board.BoardStatsComputer.trueFlashUuids(all)
        return all.map { a ->
            com.cruxcoach.athlete.logic.ClimbRow(
                day = a.climbedAt.take(10),
                climbUuid = a.climbUuid,
                difficulty = a.difficultyAverage,
                tries = a.bidCount.toInt().coerceAtLeast(1),
                sent = a.isSend,
                flash = a.isSend && a.uuid in flashUuids,
                angle = a.angle.toInt(),
                brand = a.boardBrand,
                climbedAt = a.climbedAt,
            )
        }.filter { it.day.length == 10 }
    }

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

    @Volatile private var logbookCache: Pair<Long, List<ClimbRow>>? = null

    /** Board logbook rows for the coach, cached for a minute (the logbook only grows during a session). */
    fun logbookRows(): List<ClimbRow> {
        val now = System.currentTimeMillis()
        logbookCache?.let { (at, rows) -> if (now - at < 60_000) return rows }
        val rows = runCatching { climbingDays.logbookRows() }.getOrDefault(emptyList())
        logbookCache = now to rows
        return rows
    }

    fun invalidateLogbook() { logbookCache = null }

    /** Working grade, flash grade, rhythm — the coach setup's prefill and the load model's anchor. */
    fun logbookSummary(): LogbookSummary = LogbookSummaries.summarize(logbookRows(), today())

    /** [logbookSummary], with the coach-setup grades standing in when there is no graded logbook. */
    fun gradeSummary(): LogbookSummary {
        val coach = repo.profile().coach
        return LogbookSummaries.withCoachGrades(logbookSummary(), coach.currentGrade, coach.currentFlashGrade)
    }

    private val readyMutex = Mutex()
    @Volatile private var ready = false

    /** The phone's region for first-start defaults; unset when a test builds the service by hand. */
    @Inject lateinit var systemRegion: SystemRegion

    /** Opens the database, loads the catalogue and imports 0.2.3 body stats once. */
    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        catalogStore.ensureLoaded()
        readyMutex.withLock {
            if (ready) return@withLock
            setFirstStartUnits()
            // Places: an older profile gets its one equipment set as its first place; a place picked yesterday ends.
            runCatching { usePlaceOfToday() }
            // The coach learns from the last months; older ledger rows only cost space.
            runCatching { repo.pruneSuggestionEvents(System.currentTimeMillis() - LEDGER_KEEP_DAYS * 86_400_000L) }
            importLegacyBodyStats()
            ready = true
        }
    }

    /** A new athlete starts in the units of the phone's region: US customary in the US, metric elsewhere. */
    private fun setFirstStartUnits() {
        if (repo.hasStoredProfile() || !::systemRegion.isInitialized) return
        repo.saveProfile(Units.withUnits(AthleteProfile(), Units.defaultFor(systemRegion.country())))
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

    /**
     * Board days, climbing logged outside the app and logged trainings, per
     * day, for [days] days up to today — with the day's climbing intensity
     * (relative to the athlete's own grade) and load per structure
     * ([ClimbingLoad]). Sets of a still open training already count for load;
     * its minutes count once it ends.
     */
    fun activities(days: Int): Map<String, DayActivity> {
        val today = today()
        val fromDate = today.minus(DatePeriod(days = days - 1))
        val from = fromDate.toString()
        val to = today.toString()
        val climbing = runCatching { climbingDays.since(from) }.getOrDefault(emptyMap())
        val allWorkouts = repo.workoutsBetween(from, to)
        val workouts = allWorkouts.filter { !it.isOpen }.groupBy { it.day }
        val manual = runCatching { repo.climbingDaysSince(from) }.getOrDefault(emptyList())
            .filter { it.day <= to }.groupBy { it.day }
        val rows = logbookRows()
        val summary = LogbookSummaries.summarize(rows, today)
        val rowsByDay = rows.filter { it.day in from..to }.groupBy { it.day }
        val workoutDay = allWorkouts.associate { it.id to it.day }
        val zone = TimeZone.currentSystemDefault()
        val setsByDay = repo.completedSetsSince(fromDate.atStartOfDayIn(zone).toEpochMilliseconds())
            .mapNotNull { s -> workoutDay[s.workoutId]?.let { it to s } }
            .groupBy({ it.first }, { it.second })
        val cat = this.catalog
        val keys = (climbing.keys + workouts.keys + manual.keys + rowsByDay.keys).filter { it in from..to }
        return keys.associateWith { day ->
            val c = climbing[day]
            val w = workouts[day].orEmpty()
            val m = manual[day].orEmpty()
            val dayRows = rowsByDay[day].orEmpty()
            val boardIntensity = ClimbingLoad.classifyDay(dayRows, summary)
            val intensity = (listOfNotNull(boardIntensity) + m.map { it.intensity }).maxByOrNull { it.ordinal }
            val units = ClimbingLoad.dayUnits(dayRows, summary, c?.minutes ?: 0, m, setsByDay[day].orEmpty(), cat)
            DayActivity(
                day = day,
                climbingMinutes = (c?.minutes ?: 0) + m.sumOf { it.minutes },
                climbingEfforts = c?.efforts ?: 0,
                workoutMinutes = w.sumOf { it.durationMinutes ?: 0 },
                workoutLoad = w.sumOf { (it.durationMinutes ?: 0) * (it.sessionRpe ?: 6) },
                climbIntensity = intensity,
                fingerLoad = units[LoadStructure.FINGER] ?: 0.0,
                skinLoad = units[LoadStructure.SKIN] ?: 0.0,
                shoulderLoad = units[LoadStructure.SHOULDER] ?: 0.0,
            )
        }
    }

    /** Acute against chronic load per structure over the last eight weeks. */
    fun loadStatus(): LoadStatus {
        val daily = activities(ClimbingLoad.SERIES_DAYS + ClimbingLoad.LEAD_DAYS).mapNotNull { (day, a) ->
            runCatching { LocalDate.parse(day) }.getOrNull()?.let { d ->
                d to mapOf(LoadStructure.FINGER to a.fingerLoad, LoadStructure.SKIN to a.skinLoad, LoadStructure.SHOULDER to a.shoulderLoad)
            }
        }.toMap()
        return ClimbingLoad.status(daily, today())
    }

    /** The climber profile: grade timeline, strength to weight, grade band, correlation, bottlenecks. */
    fun climberProfile(): ClimberProfileData {
        val today = today()
        val rows = logbookRows()
        val coach = repo.profile().coach
        val summary = LogbookSummaries.withCoachGrades(LogbookSummaries.summarize(rows, today), coach.currentGrade, coach.currentFlashGrade)
        val zone = TimeZone.currentSystemDefault()
        val sets = PerformanceProfiles.RELEVANT_SLUGS.flatMap { slug -> repo.history(slug, HISTORY_LIMIT) }
            .filter { it.isCompleted }
            .mapNotNull { s -> s.completedAt?.let { ms -> dayOfMillis(ms, zone) to s } }
        return PerformanceProfiles.build(
            rows, summary, sets, repo.allBenchmarks(), catalog,
            weightTrend().map { it.day to it.trend }, today,
        ) { ms -> dayOfMillis(ms, zone) }
    }

    private fun dayOfMillis(ms: Long, zone: TimeZone): LocalDate =
        kotlin.time.Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date

    /** Climbing outside the board app (gym, rock, another board). */
    fun saveClimbingDay(entry: ClimbingDayEntry) = repo.saveClimbingDay(entry.copy(updatedAt = System.currentTimeMillis()))

    fun deleteClimbingDay(id: String) = repo.deleteClimbingDay(id)

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
            val modifier = todaysModifier()
            routine?.items?.forEachIndexed { index, item -> addItem(id, index, item, modifier) }
            if (routine?.items?.any { it.warmup } == true) addSpecificWarmups(id)
        }
        id
    }

    /**
     * The warm-up fits what follows and builds up from lighter versions of it
     * (owner 2026-10-09): before the first finger, pull, push and leg exercise
     * come lighter sets of that same exercise – a 50/70/85 % ramp for loaded
     * work, one set at half the reps or time for body-weight work. A loaded
     * finger warm-up that is already there (the hangboard ramp before
     * hangboard work) counts for the fingers.
     */
    private fun addSpecificWarmups(workoutId: String) {
        val sets = repo.setsFor(workoutId)
        val ramped = setOf(
            com.cruxcoach.athlete.catalog.ExerciseCategoryV2.FINGER, com.cruxcoach.athlete.catalog.ExerciseCategoryV2.PULL,
            com.cruxcoach.athlete.catalog.ExerciseCategoryV2.PUSH, com.cruxcoach.athlete.catalog.ExerciseCategoryV2.LEGS,
        )
        val fingerCovered = sets.any { s ->
            s.setType == SetType.WARMUP && catalog.fallbackFor(s.exerciseSlug).let { d ->
                com.cruxcoach.athlete.catalog.LoadDomain.FINGER in d.domains && d.load != com.cruxcoach.athlete.catalog.LoadMode.NONE
            }
        }
        sets.filter { it.setType == SetType.WORK }
            .sortedWith(compareBy({ it.blockIndex }, { it.setIndex }))
            .groupBy { catalog.fallbackFor(it.exerciseSlug).category }
            .filterKeys { it in ramped && !(it == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.FINGER && fingerCovered) }
            .values.forEach { inCategory ->
                val first = inCategory.first()
                // Only the first exercise of the area, and not when its block already starts with warm-up sets.
                if (sets.none { it.blockIndex == first.blockIndex && it.setType == SetType.WARMUP }) addWarmupRamp(first)
            }
    }

    /**
     * Lighter sets of [firstWork]'s exercise before it, for every side: the
     * 50/70/85 % ramp for loaded work, else one set at half the reps or time
     * (body-weight work, or a load still unknown). Negative set indices sort first.
     */
    fun addWarmupRamp(firstWork: ExerciseSet) {
        val def = catalog.fallbackFor(firstWork.exerciseSlug)
        if (def.load == com.cruxcoach.athlete.catalog.LoadMode.NONE || def.kind == com.cruxcoach.athlete.catalog.ExerciseKind.CLIMB) return
        val steps = WarmupRamp.build(def, firstWork.loadKg, firstWork.bodyweightKg ?: currentBodyweight(), repo.profile().smallestIncrementKg)
            .ifEmpty { listOfNotNull(WarmupRamp.lighterSet(def, firstWork.targetReps ?: firstWork.reps, (firstWork.targetDurationS ?: firstWork.durationS)?.toInt(), firstWork.loadKg)) }
        val sides = repo.setsFor(firstWork.workoutId)
            .filter { it.blockIndex == firstWork.blockIndex && it.setIndex == firstWork.setIndex && it.setType == SetType.WORK }
            .map { it.side }
        steps.forEachIndexed { i, step ->
            sides.forEach { side ->
                repo.saveSet(firstWork.copy(
                    id = repo.newId(), setType = SetType.WARMUP, setIndex = i - steps.size, side = side,
                    // Body-weight work keeps "no load"; loaded work gets the step's load.
                    loadKg = firstWork.loadKg?.let { step.loadKg }, targetLoadKg = firstWork.loadKg?.let { step.loadKg },
                    reps = step.reps ?: firstWork.reps, targetReps = step.reps ?: firstWork.targetReps,
                    durationS = step.durationS?.toDouble() ?: firstWork.durationS,
                    targetDurationS = step.durationS?.toDouble() ?: firstWork.targetDurationS,
                    restS = 60, rir = null, note = null, completedAt = null,
                ))
            }
        }
    }

    /** Appends an exercise; returns false when the open injury rules it out. */
    fun addExercise(workoutId: String, slug: String): Boolean = synchronized(structureLock) {
        val def = catalog[slug] ?: return false
        val nextBlock = (repo.setsFor(workoutId).maxOfOrNull { it.blockIndex } ?: -1) + 1
        addItem(workoutId, nextBlock, WorkoutPlanner.itemFor(def), todaysModifier())
    }

    /**
     * Today's form as a change to the prescription (FEAT-071): the check-in
     * and hard climbing earlier today trim sets and load of main work.
     */
    fun todaysModifier(): ReadinessModifier = runCatching {
        val today = today()
        val readiness = ReadinessEvaluator.evaluate(repo.checkin(today.toString()), repo.activeInjuries())
        val activity = activities(1)[today.toString()]
        val climbed = activity?.takeIf { it.climbingMinutes > 0 || it.climbingEfforts > 0 }?.climbIntensity
        ReadinessModifiers.of(readiness, climbed)
    }.getOrDefault(ReadinessModifier.NONE)

    private fun addItem(workoutId: String, blockIndex: Int, item: RoutineItem, modifier: ReadinessModifier = ReadinessModifier.NONE): Boolean {
        val def = catalog.fallbackFor(item.slug)
        val bodyweight = currentBodyweight()
        val increment = repo.profile().smallestIncrementKg
        val adjusted = ReadinessModifiers.applyToItem(item, def, modifier)
        val history = repo.history(item.slug, 60)
        val planned = WorkoutPlanner.plan(
            workoutId, blockIndex, adjusted, catalog, history, repo.activeInjuries(),
            bodyweight,
            capacityFor = { side ->
                capacityFor(def, side, item.edgeMm?.toDouble(), item.grip, bodyweight)
                    // Nothing logged yet: a careful start load instead of "0 kg" for block lifts and pick-ups.
                    ?: startEstimate(def, bodyweight).takeIf { item.loadKg == null && history.none { it.isCompleted && it.setType != SetType.WARMUP } }
            },
            incrementKg = increment,
            newId = repo::newId,
        )
        val factor = if (item.warmup || item.test) 1.0 else ReadinessModifiers.loadFactorFor(def, modifier)
        planned.sets.forEach { set ->
            val scaled = if (factor == 1.0 || set.setType != SetType.WORK) set else set.copy(
                targetLoadKg = ReadinessModifiers.scaleLoad(def, set.targetLoadKg, set.bodyweightKg ?: bodyweight, factor, increment),
                loadKg = ReadinessModifiers.scaleLoad(def, set.loadKg, set.bodyweightKg ?: bodyweight, factor, increment),
            )
            repo.saveSet(scaled)
        }
        return !planned.skippedForInjury
    }

    // ── Performance values ───────────────────────────────────────────

    /** Repeaters without a value of their own borrow the two-arm max hang on the same edge. */
    private fun benchmarkSourceSlugs(def: com.cruxcoach.athlete.catalog.ExerciseDefinition): List<String> =
        if (def.kind == com.cruxcoach.athlete.catalog.ExerciseKind.INTERVAL && def.load == com.cruxcoach.athlete.catalog.LoadMode.BODYWEIGHT_PLUS)
            listOf(def.slug, MAX_HANG_SLUG) else listOf(def.slug)

    fun capacityFor(
        def: com.cruxcoach.athlete.catalog.ExerciseDefinition,
        side: Side?,
        edgeMm: Double?,
        grip: Grip?,
        bodyweightKg: Double? = currentBodyweight(),
    ): Capacity? {
        for (slug in benchmarkSourceSlugs(def)) {
            val source = catalog[slug] ?: continue
            val chosen = BenchmarkMath.select(repo.benchmarks(slug), side, edgeMm, grip) ?: continue
            val capacity = BenchmarkMath.capacity(source, chosen, bodyweightKg) ?: continue
            // The other hand is a reasonable first guess for one-sided work, but a cautious one.
            return if (side != null && chosen.side != null && chosen.side != side)
                capacity.copy(value = capacity.value * OTHER_SIDE_FACTOR, fromOtherSide = true) else capacity
        }
        return null
    }

    /**
     * [StartEstimate] for a block lift or pick-up without any value: from a
     * related value (two-arm pick-up, max hang, one-arm pick-up) when there is
     * one, else from body weight and the climbing grade (logbook or coach setup).
     */
    fun startEstimate(def: com.cruxcoach.athlete.catalog.ExerciseDefinition, bodyweightKg: Double? = currentBodyweight()): Capacity? {
        if (!StartEstimate.applies(def)) return null
        fun known(slug: String, side: Side? = null) = catalog[slug]?.let { capacityFor(it, side, 20.0, null, bodyweightKg) }?.value
        val twoHand = known(TWO_ARM_PICKUP_SLUG)
            ?: known(MAX_HANG_SLUG)?.times(StartEstimate.PICKUP_OF_HANG)
            ?: (known(ONE_ARM_PICKUP_SLUG, Side.LEFT) ?: known(ONE_ARM_PICKUP_SLUG, Side.RIGHT))?.div(StartEstimate.ONE_ARM_SHARE)
        val difficulty = if (twoHand == null) runCatching { gradeSummary().workingDifficulty }.getOrNull() else null
        return StartEstimate.capacity(def, bodyweightKg, twoHand, difficulty)
    }

    /**
     * Saves a value the athlete entered and applies it at once to the open
     * training: planned work sets of that exercise the athlete has not touched
     * yet (load still equals the plan) get the new prescription.
     */
    fun saveBenchmark(b: Benchmark) {
        repo.saveBenchmark(b.copy(bodyweightKg = b.bodyweightKg ?: currentBodyweight()))
        replanOpenSets(b.exerciseSlug)
    }

    private fun replanOpenSets(slug: String) {
        val workout = repo.openWorkout() ?: return
        val def = catalog[slug] ?: return
        val bodyweight = currentBodyweight()
        val increment = repo.profile().smallestIncrementKg
        repo.setsFor(workout.id)
            .filter { it.exerciseSlug == slug && !it.isCompleted && it.setType == SetType.WORK && (it.loadKg ?: 0.0) == (it.targetLoadKg ?: 0.0) }
            .forEach { set ->
                val item = RoutineItem(slug, sets = 1, repsMin = set.targetReps, repsMax = set.targetReps,
                    durationS = set.targetDurationS?.toInt(), workS = set.workS?.toInt(), edgeMm = set.edgeMm?.toInt(), grip = set.grip)
                val target = LoadPrescriber.prescribe(def, item, capacityFor(def, set.side, set.edgeMm, set.grip, bodyweight), bodyweight, increment)
                    ?: return@forEach
                repo.saveSet(set.copy(
                    loadKg = target.loadKg ?: set.loadKg, targetLoadKg = target.loadKg ?: set.targetLoadKg,
                    reps = target.reps ?: set.reps, targetReps = target.reps ?: set.targetReps,
                ))
            }
        // An untouched ramp in front of the replanned sets follows the new load.
        val sets = repo.setsFor(workout.id).filter { it.exerciseSlug == slug }
        sets.filter { it.setType == SetType.WARMUP }.groupBy { it.blockIndex }.forEach { (block, ramp) ->
            if (ramp.any { it.isCompleted || (it.loadKg ?: 0.0) != (it.targetLoadKg ?: 0.0) }) return@forEach
            val firstWork = sets.filter { it.blockIndex == block && it.setType == SetType.WORK && !it.isCompleted }
                .minByOrNull { it.setIndex } ?: return@forEach
            ramp.forEach { repo.deleteSet(it.id) }
            addWarmupRamp(firstWork)
        }
    }

    /**
     * A short test for one exercise (MCI's strength test, climber version):
     * finger ramp first for finger work, then test sets logged as TEST; the
     * best one becomes the new performance value.
     */
    fun startTest(slug: String): String? {
        val def = catalog[slug] ?: return null
        val d = def.defaults
        val test = when (def.kind) {
            com.cruxcoach.athlete.catalog.ExerciseKind.HANG, com.cruxcoach.athlete.catalog.ExerciseKind.INTERVAL ->
                RoutineItem(slug, sets = 3, durationS = 10, restS = 180, edgeMm = d.edgeMm, test = true)
            com.cruxcoach.athlete.catalog.ExerciseKind.LOAD_REPS -> RoutineItem(slug, sets = 3, repsMin = 3, repsMax = 5, restS = 180, test = true)
            com.cruxcoach.athlete.catalog.ExerciseKind.REPS -> RoutineItem(slug, sets = 1, repsMin = 1, repsMax = 30, restS = 180, test = true)
            com.cruxcoach.athlete.catalog.ExerciseKind.TIME -> RoutineItem(slug, sets = 1, durationS = (d.durationS ?: 30) * 2, restS = 120, test = true)
            com.cruxcoach.athlete.catalog.ExerciseKind.CLIMB -> return null
        }
        val items = buildList {
            if (com.cruxcoach.athlete.catalog.LoadDomain.FINGER in def.domains) {
                add(RoutineItem("warmup.finger_ramp", sets = 4, durationS = 8, restS = 20, edgeMm = 30, warmup = true))
            }
            add(test)
        }
        val routine = Routine(id = "test:$slug", name = def.name("en"), items = items)
        return startWorkout(routine, null)
    }

    /** What a completed set changed besides the log. */
    data class SetOutcome(val record: PersonalRecord?, val raisedBenchmark: Benchmark?, val capacity: Capacity?)

    private fun updateBenchmark(set: ExerciseSet): Pair<Benchmark, Capacity>? {
        if (set.setType == SetType.WARMUP) return null
        val def = catalog[set.exerciseSlug] ?: return null
        val implied = BenchmarkMath.implied(def, set) ?: return null
        val existing = BenchmarkMath.select(repo.benchmarks(def.slug), set.side, set.edgeMm, set.grip)
            ?.takeIf { it.side == set.side }
        val current = existing?.let { BenchmarkMath.capacity(def, it, set.bodyweightKg) }
        val raise = when {
            set.setType == SetType.TEST -> {
                // A fresh test is the truth, also when lower; within one test the best set counts.
                val sameTest = repo.setsFor(set.workoutId).filter {
                    it.id != set.id && it.isCompleted && it.setType == SetType.TEST && it.exerciseSlug == set.exerciseSlug && it.side == set.side
                }.mapNotNull { BenchmarkMath.implied(def, it)?.value }
                sameTest.all { implied.value > it }
            }
            current == null -> true                          // the first session learns the starting value
            current.kind != implied.kind -> false
            else -> implied.value > current.value * 1.02     // only a clear improvement moves the value up
        }
        if (!raise) return null
        val source = if (set.setType == SetType.TEST) BenchmarkSource.TEST else BenchmarkSource.AUTO
        val b = BenchmarkMath.benchmarkFromSet(def, set, repo.newId(), source, System.currentTimeMillis())
        repo.saveBenchmark(b)
        return b to implied
    }

    /**
     * Marks a set done and, if the athlete wants that, starts the shared rest
     * timer (the same one the board player uses: banner on every screen,
     * Doze-safe alarm, survives process death). Returns a record if one fell.
     */
    fun completeSet(set: ExerciseSet, startRest: Boolean): PersonalRecord? = completeSetDetailed(set, startRest).record

    fun completeSetDetailed(set: ExerciseSet, startRest: Boolean, restSeconds: Int? = null): SetOutcome {
        val done = set.copy(completedAt = System.currentTimeMillis(), bodyweightKg = set.bodyweightKg ?: currentBodyweight())
        val history = repo.history(set.exerciseSlug, HISTORY_LIMIT).filter { it.id != set.id }
        repo.saveSet(done)
        if (startRest) (restSeconds ?: done.restS ?: catalog[set.exerciseSlug]?.defaults?.restS)?.takeIf { it > 0 }?.let(::startRest)
        val raised = runCatching { updateBenchmark(done) }.getOrNull()
        return SetOutcome(PersonalRecords.detect(catalog.fallbackFor(set.exerciseSlug), done, history), raised?.first, raised?.second)
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

    /**
     * Set-to-set autoregulation: after a completed work set, the athlete's
     * untouched planned sets of the same block and side move by one small
     * step ([SetAutoregulation]). [previous] is what an earlier call already
     * applied for the same set (e.g. before the reserve answer came in), so
     * changing the answer never stacks steps. Returns the adjustment that now
     * stands for [done], or null when nothing changes.
     */
    fun adjustUpcomingSets(workoutId: String, done: ExerciseSet, previous: SetAdjustment? = null): SetAdjustment? = synchronized(structureLock) {
        val def = catalog[done.exerciseSlug] ?: return null
        val open = repo.setsFor(workoutId)
            .filter {
                it.id != done.id && it.blockIndex == done.blockIndex && it.exerciseSlug == done.exerciseSlug &&
                    it.side == done.side && it.setType == SetType.WORK && SetAutoregulation.untouched(it)
            }
            .sortedBy { it.setIndex }
        val next = open.firstOrNull() ?: return null
        val increment = repo.profile().smallestIncrementKg
        val bodyweight = done.bodyweightKg ?: currentBodyweight()
        // Increases stop one step above what the performance value prescribes.
        val ceiling = runCatching {
            val item = RoutineItem(def.slug, sets = 1, repsMin = next.targetReps, repsMax = next.targetReps,
                durationS = next.targetDurationS?.toInt(), edgeMm = next.edgeMm?.toInt(), grip = next.grip)
            LoadPrescriber.prescribe(def, item, capacityFor(def, next.side, next.edgeMm, next.grip, bodyweight), bodyweight, increment)
                ?.loadKg?.plus(increment)
        }.getOrNull()
        // The first session with a block lift or pick-up feels out its guessed start load in bigger steps.
        val adjustment = SetAutoregulation.adjust(def, done.copy(bodyweightKg = bodyweight), next, increment, ceiling,
            feelOut = feelingOut(def, workoutId))
        val delta = SetAutoregulation.difference(adjustment, previous) ?: return adjustment
        repo.transaction { open.forEach { repo.saveSet(SetAutoregulation.apply(def, it, delta)) } }
        adjustment
    }

    /**
     * True in the first session with an exercise whose start load is a guess
     * ([StartEstimate]): no own value from before this training and no earlier
     * training with it. The value the first set leaves does not count.
     */
    private fun feelingOut(def: com.cruxcoach.athlete.catalog.ExerciseDefinition, workoutId: String): Boolean {
        if (!StartEstimate.applies(def)) return false
        val started = repo.workout(workoutId)?.startedAt ?: return false
        return repo.benchmarks(def.slug).none { it.measuredAt < started } &&
            repo.history(def.slug, HISTORY_LIMIT).none { it.isCompleted && it.setType != SetType.WARMUP && it.workoutId != workoutId }
    }

    /** What "it hurts" changed in the open training. */
    data class PainOutcome(val action: PainAction, val newSlug: String? = null, val injuryId: String? = null)

    /**
     * "Tut weh" in the middle of a training (MCI's pain sheet, climber
     * version): swap the block's open sets to an exercise that spares the
     * region, make them lighter, or end the exercise. Completed sets stay.
     * With [remember] the pain becomes an injury, so every later suggestion
     * and plan respects it; strong pain pauses climbing.
     */
    fun painStop(
        workoutId: String,
        blockIndex: Int,
        region: InjuryRegion,
        side: InjurySide?,
        severity: Int,
        action: PainAction,
        remember: Boolean = false,
    ): PainOutcome = synchronized(structureLock) {
        val sets = repo.setsFor(workoutId)
        val block = sets.filter { it.blockIndex == blockIndex }
        val def = block.firstOrNull()?.let { catalog.fallbackFor(it.exerciseSlug) } ?: return PainOutcome(action)
        val now = System.currentTimeMillis()
        val injury = Injury(
            id = repo.newId(), region = region, side = side, severity = severity.coerceIn(0, 9),
            climbingPaused = severity >= PAIN_PAUSE_SEVERITY, startedOn = today().toString(), updatedAt = now,
        )
        val open = block.filter { !it.isCompleted }
        repo.transaction {
            if (remember) repo.saveInjury(injury)
            when (action) {
                PainAction.SWAP -> {
                    val alternative = painAlternative(def, repo.activeInjuries() + (if (remember) emptyList<Injury>() else listOf(injury)))
                    if (alternative == null) {
                        lighten(def, open)
                        return@transaction PainOutcome(PainAction.LIGHTER, injuryId = injury.id.takeIf { remember })
                    }
                    open.forEach { repo.deleteSet(it.id) }
                    // The alternative comes right after this block: later blocks move one place down.
                    sets.filter { it.blockIndex > blockIndex }.forEach { repo.saveSet(it.copy(blockIndex = it.blockIndex + 1)) }
                    val remaining = open.filter { it.setType != SetType.WARMUP }.map { it.setIndex }.distinct().size.coerceAtLeast(1)
                    addItem(workoutId, blockIndex + 1, WorkoutPlanner.itemFor(alternative).copy(sets = remaining))
                    PainOutcome(PainAction.SWAP, alternative.slug, injury.id.takeIf { remember })
                }
                PainAction.LIGHTER -> { lighten(def, open); PainOutcome(PainAction.LIGHTER, injuryId = injury.id.takeIf { remember }) }
                PainAction.END_EXERCISE -> {
                    open.forEach { repo.deleteSet(it.id) }
                    PainOutcome(PainAction.END_EXERCISE, injuryId = injury.id.takeIf { remember })
                }
            }
        }
    }

    /** −20 % total load and two reps less (or 20 % shorter holds) on the open sets. */
    private fun lighten(def: com.cruxcoach.athlete.catalog.ExerciseDefinition, open: List<ExerciseSet>) {
        val increment = repo.profile().smallestIncrementKg
        val bodyweight = currentBodyweight()
        open.filter { it.setType != SetType.WARMUP }.forEach { s ->
            val bw = s.bodyweightKg ?: bodyweight
            repo.saveSet(s.copy(
                targetLoadKg = ReadinessModifiers.scaleLoad(def, s.targetLoadKg, bw, PAIN_LIGHTER_FACTOR, increment),
                loadKg = ReadinessModifiers.scaleLoad(def, s.loadKg, bw, PAIN_LIGHTER_FACTOR, increment),
                targetReps = s.targetReps?.let { (it - 2).coerceAtLeast(1) },
                reps = s.reps?.let { (it - 2).coerceAtLeast(1) },
                targetDurationS = s.targetDurationS?.takeIf { s.loadKg == null }?.let { (it * PAIN_LIGHTER_FACTOR).coerceAtLeast(3.0) } ?: s.targetDurationS,
                durationS = s.durationS?.takeIf { s.loadKg == null }?.let { (it * PAIN_LIGHTER_FACTOR).coerceAtLeast(3.0) } ?: s.durationS,
            ))
        }
    }

    /**
     * An exercise that spares the hurting region: the easier chain partner
     * first, then the same category, same kind and closest difficulty, with
     * owned equipment and nothing the athlete excluded.
     */
    private fun painAlternative(def: com.cruxcoach.athlete.catalog.ExerciseDefinition, injuries: List<Injury>): com.cruxcoach.athlete.catalog.ExerciseDefinition? {
        val profile = repo.profile()
        val owned = if (profile.equipmentConfigured) profile.equipment + com.cruxcoach.athlete.catalog.EquipmentV2.NONE else null
        fun fits(d: com.cruxcoach.athlete.catalog.ExerciseDefinition): Boolean {
            if (d.slug == def.slug || d.kind == com.cruxcoach.athlete.catalog.ExerciseKind.CLIMB) return false
            if (d.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.WARMUP || d.slug in profile.excludedExercises) return false
            if (owned != null && !d.equipment.all { com.cruxcoach.athlete.catalog.EquipmentV2.satisfied(it, owned) }) return false
            val verdict = InjuryAdvisor.assess(d, injuries).verdict
            return verdict == InjuryVerdict.OK || verdict == InjuryVerdict.ONE_SIDE_ONLY
        }
        def.easier?.let { catalog[it] }?.takeIf(::fits)?.let { return it }
        return catalog.all.filter { it.category == def.category && fits(it) }
            .minWithOrNull(compareBy({ if (it.kind == def.kind) 0 else 1 }, { kotlin.math.abs(it.difficulty - def.difficulty) }, { it.slug }))
    }

    fun startRest(seconds: Int) = sessionManager.startRestTimer(seconds)

    /** The shared rest timer (same as the board player): banner, Doze-safe alarm, notification. */
    val restTimer: kotlinx.coroutines.flow.StateFlow<com.cruxcoach.android.data.RestTimerState> get() = sessionManager.restTimer

    fun cancelRest() = sessionManager.cancelRestTimer()

    /** +15 s / −15 s in the rest screen: restarts the timer with the adjusted remainder (min. 5 s). */
    fun adjustRest(deltaSeconds: Int) {
        val state = sessionManager.restTimer.value
        if (!state.isRunning) return
        sessionManager.startRestTimer((state.secondsRemaining + deltaSeconds).coerceAtLeast(5))
    }

    fun dismissRestFinished() = sessionManager.dismissRestTimerFinished()

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

    /**
     * Deletes a training with its sets, and the performance values its sets raised (training or test values
     * recorded while it ran): a training logged by mistake must not leave a higher start load behind.
     * Values the athlete entered stay.
     */
    fun discardWorkout(id: String) {
        val workout = repo.workout(id)
        if (workout != null) {
            val slugs = repo.setsFor(id).map { it.exerciseSlug }.toSet()
            val until = (workout.endedAt ?: System.currentTimeMillis()) + 60_000L
            slugs.flatMap { repo.benchmarks(it) }
                .filter { (it.source == BenchmarkSource.AUTO || it.source == BenchmarkSource.TEST) && it.measuredAt in workout.startedAt..until }
                .forEach { repo.deleteBenchmark(it.id) }
        }
        repo.deleteWorkout(id)
    }

    /** A training still open when another one is about to start. */
    data class OpenConflict(val open: Workout, val doneSets: Int, val totalSets: Int)

    /**
     * The open training, when starting [routineId] would otherwise silently
     * reopen it (owner 2026-10-09: "Mobility" opened the finger ramp of an open
     * finger training). Starting the open training's own routine again is no conflict.
     */
    fun openConflict(routineId: String?): OpenConflict? {
        val open = repo.openWorkout() ?: return null
        if (routineId != null && open.routineId == routineId) return null
        val sets = repo.setsFor(open.id)
        return OpenConflict(open, sets.count { it.isCompleted }, sets.size)
    }

    /** Ends the open training before another starts: kept with what was done, dropped when nothing was. */
    fun closeOpenWorkout() {
        val open = repo.openWorkout() ?: return
        if (repo.setsFor(open.id).any { it.isCompleted }) finishWorkout(open.id, null, null) else discardWorkout(open.id)
    }

    /** One entry in the recommendation ledger the coach learns preferences from (FEAT-071). */
    fun logSuggestion(
        kind: SuggestionEventKind,
        focus: String?,
        slugs: List<String>,
        feedback: SuggestionFeedback? = null,
        workoutId: String? = null,
        value: Double? = null,
    ) {
        runCatching {
            repo.logSuggestionEvent(SuggestionEvent(repo.newId(), today().toString(), System.currentTimeMillis(), kind, focus,
                slugs, feedback, workoutId, value))
        }
    }

    /** "Nie vorschlagen": the exercise stays in the library but is never suggested. */
    fun setExcluded(slug: String, excluded: Boolean) {
        repo.updateProfile { p -> p.copy(excludedExercises = if (excluded) p.excludedExercises + slug else p.excludedExercises - slug) }
        if (excluded) logSuggestion(SuggestionEventKind.EXCLUDED, null, listOf(slug))
    }

    /** A routine handed to the editor (today's suggestion), taken once; in memory only. */
    @Volatile private var editorDraft: Routine? = null
    fun offerEditorDraft(routine: Routine) { editorDraft = routine }
    fun takeEditorDraft(): Routine? = editorDraft.also { editorDraft = null }

    // ── Backup ───────────────────────────────────────────────────────

    fun backupSnapshot(): com.cruxcoach.athlete.data.AthleteSnapshot = repo.snapshot()

    /** Restores a backup part and makes restored custom exercises visible without a restart. */
    fun restoreBackup(snapshot: com.cruxcoach.athlete.data.AthleteSnapshot, includeProfile: Boolean): Int =
        repo.restore(snapshot, includeProfile).also { catalogStore.invalidate() }

    fun importBackupBodyStats(rows: List<com.cruxcoach.domain.model.BodyStat>): Int =
        repo.importLegacyBodyStats(rows.map { AthleteRepository.LegacyBodyStat(it.date, it.statName, it.value, it.unit) })

    companion object {
        const val LEDGER_KEEP_DAYS = 180L
        /** Pain from this level on pauses climbing when it is remembered as an injury. */
        const val PAIN_PAUSE_SEVERITY = 5
        const val PAIN_LIGHTER_FACTOR = 0.8
        const val MAX_HANG_SLUG = "finger.max_hang"
        const val TWO_ARM_PICKUP_SLUG = "finger.two_arm_pickup"
        const val ONE_ARM_PICKUP_SLUG = "finger.one_arm_pickup"
        const val OTHER_SIDE_FACTOR = 0.95

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

    // ── Places ───────────────────────────────────────────────────────

    /** Puts today's place in use (picked today, else the default); writes only when that changes something. */
    fun usePlaceOfToday() {
        val day = today().toString()
        val profile = repo.profile()
        if (TrainingPlaces.inUse(profile, day) != profile) repo.updateProfile { TrainingPlaces.inUse(it, day) }
    }

    fun pickPlace(placeId: String) = repo.updateProfile { TrainingPlaces.pick(it, placeId, today().toString()) }

    /** New equipment for a place; null is where the athlete trains today. */
    fun savePlaceEquipment(placeId: String?, equipment: Set<com.cruxcoach.athlete.catalog.EquipmentV2>) =
        repo.updateProfile { TrainingPlaces.withEquipment(it, placeId, equipment, today().toString()) }

    /** Adds a place filled with [equipment] and returns its id. */
    fun addPlace(kind: PlaceKind, equipment: Set<com.cruxcoach.athlete.catalog.EquipmentV2>): String {
        var id = ""
        repo.updateProfile { p -> TrainingPlaces.add(p, kind, equipment, today().toString()).also { id = it.second }.first }
        return id
    }

    fun renamePlace(placeId: String, name: String?) = repo.updateProfile { TrainingPlaces.rename(it, placeId, name, today().toString()) }
    fun makeDefaultPlace(placeId: String) = repo.updateProfile { TrainingPlaces.makeDefault(it, placeId, today().toString()) }
    fun removePlace(placeId: String) = repo.updateProfile { TrainingPlaces.remove(it, placeId, today().toString()) }

    // ── Fueling ──────────────────────────────────────────────────────

    /**
     * The day's macro targets: recommended, with the athlete's own targets in
     * their place. Fat comes from the energy target – the own one, else the
     * need minus the deficit while losing – when it is known.
     */
    fun fuelTargets(dayActivity: DayActivity?, profile: AthleteProfile): FuelTargets.Targets? {
        val weight = currentBodyweight() ?: return null
        val height = heightCm()
        val need = energyNeed(profile, dayActivity, weight, height)
        val deficit = if (profile.goal == AthleteGoal.LOSE_WEIGHT) {
            WeightPlan.plan(profile.weeklyLossKg, profile.targetWeightKg, weight, height, profile.sex, need).dailyDeficitKcal
        } else 0
        val energy = profile.ownKcal ?: need?.let { it.totalKcal - deficit }
        return FuelTargets.withOwn(FuelTargets.compute(weight, dayActivity, profile.proteinPerKg, energy), profile)
    }

    /** The day's estimated energy need; null without weight or height. */
    fun energyNeed(profile: AthleteProfile, dayActivity: DayActivity?, weightKg: Double? = currentBodyweight(), heightCm: Double? = heightCm()): EnergyBalance.Need? =
        EnergyBalance.need(profile, weightKg, heightCm, dayActivity, today().year)

    /** Today's weight-loss plan while the goal is set; null otherwise or without a body weight. */
    fun weightPlan(profile: AthleteProfile, todayActivity: DayActivity?): WeightPlan.Plan? {
        if (profile.goal != AthleteGoal.LOSE_WEIGHT) return null
        val weight = currentBodyweight() ?: return null
        val height = heightCm()
        return WeightPlan.plan(profile.weeklyLossKg, profile.targetWeightKg, weight, height, profile.sex,
            energyNeed(profile, todayActivity, weight, height))
    }

    /**
     * The energy guard over the last seven days, with its numbers. Today is
     * never compared with its need: the day is still running.
     */
    fun energyReport(profile: AthleteProfile, activities: Map<String, DayActivity>): EnergyReport {
        val today = today()
        val from = today.minus(DatePeriod(days = 6)).toString()
        val logs = repo.foodLogBetween(from, today.toString()).groupBy { it.day }
        val weight = currentBodyweight()
        val height = heightCm()
        val fuelDays = (0..6).map { back ->
            val day = today.minus(DatePeriod(days = 6 - back)).toString()
            val entries = logs[day].orEmpty()
            val energies = entries.mapNotNull { MacroTotals.energy(it) }
            FuelDay(day, logged = entries.isNotEmpty(), carbsG = entries.mapNotNull { it.carbsG }.takeIf { it.isNotEmpty() }?.sum(),
                dayLoad = FuelTargets.dayLoad(activities[day]),
                kcal = energies.takeIf { it.isNotEmpty() }?.sumOf { it.kcal },
                needKcal = energyNeed(profile, activities[day], weight, height)?.totalKcal,
                complete = day != today.toString() && entries.map { it.meal }.distinct().size >= 2)
        }
        val todayNeed = energyNeed(profile, activities[today.toString()], weight, height)
        val own = profile.ownKcal?.let { kcal -> todayNeed?.let { OwnEnergyTarget(kcal, it.totalKcal, it.restingKcal) } }
        return RedsGuard.evaluate(profile, height, weightTrend(), fuelDays, weightPlan(profile, activities[today.toString()]), own)
    }

    /** Turns the weight-loss goal on with its target and pace, or off (target and pace stay for next time). */
    fun saveWeightGoal(lose: Boolean, targetKg: Double?, paceKg: Double) = repo.updateProfile {
        it.copy(
            goal = if (lose) AthleteGoal.LOSE_WEIGHT
                else if (it.goal == AthleteGoal.LOSE_WEIGHT) it.coach.goal?.let(CoachLogic::athleteGoalFor) ?: AthleteGoal.PERFORM
                else it.goal,
            targetWeightKg = targetKg,
            weeklyLossKg = WeightPlan.clampPace(paceKg),
        )
    }
}

/** What "it hurts" does to the current exercise. */
enum class PainAction { SWAP, LIGHTER, END_EXERCISE }
