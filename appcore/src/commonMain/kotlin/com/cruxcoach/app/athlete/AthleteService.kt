// Port of androidApp/.../athlete/AthleteService.kt (0e1bad0c6) for the iOS app.
// Kept line-for-line close to the Android file so later Android changes can be
// carried over by diff; the only changes are the platform seams: no Hilt, an
// injected IO dispatcher, a reentrant lock instead of `synchronized`, the
// common clock, a platform asset reader and TrainingRestTimer instead of
// Android's BoardSessionManager.
package com.cruxcoach.app.athlete

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.app.util.ReentrantLock
import com.cruxcoach.app.util.withReentrantLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.catch
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

/** The packaged exercise catalogue plus the athlete's own exercises, loaded once off the main thread. */
class ExerciseCatalogStore(
    /** Reads the bundled `training/exercise_catalog.json` (Android: the APK's assets). */
    private val readPackaged: () -> String,
    private val repository: () -> AthleteRepository,
    private val io: CoroutineDispatcher,
) {
    private val _catalog = MutableStateFlow(ExerciseCatalog.EMPTY)
    val catalog: StateFlow<ExerciseCatalog> = _catalog.asStateFlow()
    @Volatile private var packaged: ExerciseCatalog? = null
    @Volatile private var dirty = true
    private val mutex = Mutex()

    suspend fun ensureLoaded(): ExerciseCatalog = withContext(io) {
        mutex.withLock {
            if (!dirty) _catalog.value else reload()
        }
    }

    /** Custom exercises changed outside the app's own screens (a restore). */
    fun invalidate() { dirty = true }

    /** Call after custom exercises changed. */
    suspend fun refresh(): ExerciseCatalog = withContext(io) { mutex.withLock { reload() } }

    private fun reload(): ExerciseCatalog {
        val base = packaged ?: ExerciseCatalog.parse(readPackaged())
            .also { packaged = it }
        return base.withCustom(repository().customExercises()).also { _catalog.value = it; dirty = false }
    }

    companion object {
        const val ASSET = "training/exercise_catalog.json"
    }
}

/**
 * Climbing days from the board logbook (sessions, sends, attempts) — the
 * automatic half of "what did I train", so nobody logs a board session twice.
 */
class ClimbingDaysReader(private val secureDb: SecureDatabase) {

    private val logbook by lazy { com.cruxcoach.data.repository.PersonalBoardRepositoryImpl(secureDb) }

    /**
     * Every logbook row (sends and failed sessions on all boards) for the
     * coach: grades, tries, flashes. Reads the light logbook snapshot the
     * playlist generator uses, without frame blobs.
     */
    fun logbookRows(): List<com.cruxcoach.athlete.logic.ClimbRow> {
        val all = logbook.getUserLogbookAllLight()
        val flashUuids = com.cruxcoach.app.logbook.BoardStatsComputer.trueFlashUuids(all)
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
        // Tries per logged climb, at least one: a flash is one try, a send after three tries three – not four (audit W18).
        val sent = q.ascentCountsSince(fromDay).executeAsList()
            .associate { row -> row.day to (row.tries ?: 0.0).toInt() }
        val tries = q.bidCountsSince(fromDay).executeAsList()
            .associate { row -> row.day to (row.tries ?: 0.0).toInt() }
        return (minutes.keys + sent.keys + tries.keys).filter { it.length == 10 }.associateWith { day ->
            ClimbingDay(day, minutes[day] ?: 0, (sent[day] ?: 0) + (tries[day] ?: 0))
        }
    }

    /** Start and end of every board session since [fromDay] (an open one has no end yet). */
    fun sessionTimes(fromDay: String): List<BoardOverlap.SessionTimes> =
        secureDb.trainingDaysQueries.boardSessionTimesSince(fromDay).executeAsList()
            .map { BoardOverlap.SessionTimes(it.started_at, it.ended_at, it.total_duration_seconds) }

    /** Emits whenever board sessions, sends or attempts since [fromDay] change, and once at the start. */
    fun changes(fromDay: String): kotlinx.coroutines.flow.Flow<Unit> =
        com.cruxcoach.data.repository.trainingDayChanges(secureDb, fromDay)
}

/**
 * Application service for the training, body and fueling features. Screens
 * talk to this instead of the repository so cross-cutting rules (injury
 * filter, ghost values, rest timer, body weight snapshot) live in one place.
 * All methods block; call them from an IO dispatcher.
 */
class AthleteService(
    private val repoLazy: () -> AthleteRepository,
    val catalogStore: ExerciseCatalogStore,
    private val climbingDays: ClimbingDaysReader,
    private val bodyStatRepository: BodyStatRepository,
    private val sessionManager: TrainingRestTimer,
    private val io: CoroutineDispatcher,
) {
    val repo: AthleteRepository get() = repoLazy()
    val catalog: ExerciseCatalog get() = catalogStore.catalog.value

    @Volatile private var logbookCache: Pair<Long, List<ClimbRow>>? = null

    /** Board logbook rows for the coach, cached for a minute (the logbook only grows during a session). */
    fun logbookRows(): List<ClimbRow> {
        val now = nowMs()
        logbookCache?.let { (at, rows) -> if (now - at < 60_000) return rows }
        val rows = runCatching { climbingDays.logbookRows() }.getOrDefault(emptyList())
        logbookCache = now to rows
        return rows
    }

    fun invalidateLogbook() { logbookCache = null }

    /**
     * Everything that changes a day's load (audit W2): climbing days entered or synced from Health Connect,
     * board sessions and the logbook. Today and nutrition re-derive on it, so a climbing day entered on the
     * tile or a board session just finished shows at once in the tile, the suggestion and the carbs.
     */
    fun observeActivityChanges(fromDay: String): kotlinx.coroutines.flow.Flow<Unit> =
        kotlinx.coroutines.flow.combine(
            repo.observeClimbingDaysSince(fromDay),
            climbingDays.changes(fromDay).catch { emit(Unit) },
        ) { _, _ -> invalidateLogbook() }

    /** Working grade, flash grade, rhythm — the coach setup's prefill and the load model's anchor. */
    fun logbookSummary(): LogbookSummary = LogbookSummaries.summarize(logbookRows(), today())

    /**
     * "Your logbook shows 7a – take it over?": the logbook's working grade when it is clearly above the saved grade and
     * not one the athlete said no to ([LogbookSummaries.higherWorkingGrade]).
     */
    fun gradeOffer(profile: AthleteProfile = repo.profile(), summary: LogbookSummary = logbookSummary()): String? = runCatching {
        LogbookSummaries.higherWorkingGrade(summary, profile.coach.currentGrade, repo.setting(GRADE_DECLINED_KEY)?.takeIf { it.isNotBlank() })
    }.getOrNull()

    /** Takes over [grade] as the athlete's grade; returns the one before, for "Rückgängig". */
    fun takeOverGrade(grade: String): String? {
        val before = repo.profile().coach.currentGrade
        repo.updateProfile { p -> p.copy(coach = p.coach.copy(currentGrade = grade, currentFlashGrade = p.coach.currentFlashGrade?.takeIf {
            (LogbookSummaries.difficultyOf(it) ?: 0.0) <= (LogbookSummaries.difficultyOf(grade) ?: 0.0) }, setupUpdatedAt = nowMs())) }
        return before
    }

    /** "Nein": not asked again for [grade] (a higher one later asks again). */
    fun declineGrade(grade: String) = repo.putSetting(GRADE_DECLINED_KEY, grade)

    /** [logbookSummary], with the coach-setup grades standing in when there is no graded logbook. */
    fun gradeSummary(): LogbookSummary {
        val coach = repo.profile().coach
        return LogbookSummaries.withCoachGrades(logbookSummary(), coach.currentGrade, coach.currentFlashGrade)
    }

    private val readyMutex = Mutex()
    @Volatile private var ready = false

    /** The phone's region for first-start defaults (ISO country); unset when a test builds the service by hand. */
    var systemRegion: (() -> String)? = null

    /** Opens the database, loads the catalogue and imports 0.2.3 body stats once. */
    suspend fun ensureReady() = withContext(io) {
        catalogStore.ensureLoaded()
        readyMutex.withLock {
            if (ready) return@withLock
            setFirstStartUnits()
            // Places: an older profile gets its one equipment set as its first place; a place picked yesterday ends.
            runCatching { usePlaceOfToday() }
            // The coach learns from the last months; older ledger rows only cost space.
            runCatching { repo.pruneSuggestionEvents(nowMs() - LEDGER_KEEP_DAYS * 86_400_000L) }
            importLegacyBodyStats()
            ready = true
        }
    }

    /** A new athlete starts in the units of the phone's region: US customary in the US, metric elsewhere. */
    private fun setFirstStartUnits() {
        val region = systemRegion ?: return
        if (repo.hasStoredProfile()) return
        repo.saveProfile(Units.withUnits(AthleteProfile(), Units.defaultFor(runCatching { region() }.getOrDefault(""))))
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
        val logged = runCatching { repo.climbingDaysSince(from) }.getOrDefault(emptyList()).filter { it.day <= to }
        // Health Connect minutes the board logbook already counts are not counted twice (audit W3).
        val overlaps = boardOverlaps()
        val counted = logged.map { e -> overlaps[e.id]?.let { o -> e.copy(minutes = o.counted(e.minutes)) } ?: e }
        val manual = counted.groupBy { it.day }
        // Those minutes stand in for the board time of a day whose logbook has attempts but no timed session.
        val boardTimeFromWatch = logged.zip(counted).groupBy({ it.first.day }, { (e, c) -> e.minutes - c.minutes })
            .mapValues { (_, minutes) -> minutes.sum() }
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
            val boardMinutes = c?.minutes?.takeIf { it > 0 } ?: boardTimeFromWatch[day] ?: 0
            // The watch's covered minutes stand in for the load too while the logbook has no rows yet.
            val units = ClimbingLoad.dayUnits(dayRows, summary, boardMinutes, m, setsByDay[day].orEmpty(), cat)
            DayActivity(
                day = day,
                climbingMinutes = boardMinutes + m.sumOf { it.minutes },
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
    fun saveClimbingDay(entry: ClimbingDayEntry) = repo.saveClimbingDay(entry.copy(updatedAt = nowMs()))

    /**
     * When the last training or board session ended (epoch ms), from the last two days; null without one. Adding food
     * shortly after it starts in "after training" (holistic audit P2-15).
     */
    fun lastTrainingEndedAt(): Long? {
        val workouts = repo.recentWorkouts(5).mapNotNull { it.endedAt }
        val zone = TimeZone.currentSystemDefault()
        val boards = runCatching { climbingDays.sessionTimes(today().minus(DatePeriod(days = 1)).toString()) }.getOrDefault(emptyList())
            .mapNotNull { s -> s.endedAt?.takeIf { it.isNotBlank() }?.let { BoardOverlap.epochMillis(it, zone) } }
        return (workouts + boards).maxOrNull()
    }

    /** Board sessions and timed logbook attempts since [fromDay]: what a Health Connect session may overlap (audit W3). */
    fun boardEvidence(fromDay: String): BoardOverlap.Evidence {
        val rows = logbookRows().filter { it.day >= fromDay }
        val sessions = runCatching { climbingDays.sessionTimes(fromDay) }.getOrDefault(emptyList())
        return BoardOverlap.evidence(rows, sessions, TimeZone.currentSystemDefault(), nowMs())
    }

    /** Stored board overlaps of synced climbing days, by entry id. */
    fun boardOverlaps(): Map<String, BoardOverlap.Stored> = runCatching {
        repo.settingsWithPrefix(BoardOverlap.SETTING_PREFIX).mapNotNull { (key, value) ->
            BoardOverlap.Stored.parse(value)?.let { key.removePrefix(BoardOverlap.SETTING_PREFIX) to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun boardOverlap(entryId: String): BoardOverlap.Stored? = BoardOverlap.Stored.parse(repo.setting(BoardOverlap.key(entryId)))

    /**
     * Stores what the sync found ([coveredMinutes]) or what the athlete chose ([countAnyway]), keeping the other
     * part; writes only on a change, since every observer of the settings re-derives on a write.
     */
    fun saveBoardOverlap(entryId: String, coveredMinutes: Int? = null, countAnyway: Boolean? = null) {
        val key = BoardOverlap.key(entryId)
        val old = BoardOverlap.Stored.parse(repo.setting(key))
        val new = BoardOverlap.Stored(coveredMinutes ?: old?.coveredMinutes ?: 0, countAnyway ?: old?.countAnyway ?: false)
        val value = if (new.coveredMinutes == 0 && !new.countAnyway) "" else new.encode()
        if (value != (old?.encode() ?: "")) repo.putSetting(key, value)
    }

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
    private val structureLock = ReentrantLock()

    /** [trimSets] false: today's form changes loads only (the suggestion is already shorter for fatigue). */
    fun startWorkout(routine: Routine?, title: String?, trimSets: Boolean = true): String = withReentrantLock(structureLock) {
        repo.openWorkout()?.let { return it.id }
        val now = nowMs()
        val id = repo.newId()
        repo.transaction {
            repo.saveWorkout(Workout(id, now, null, today().toString(), title ?: routine?.name, routine?.id, updatedAt = now))
            val modifier = todaysModifier().let { if (trimSets) it else it.copy(setsDelta = 0) }
            // Circuits in their canonical form, every member with the circuit's rests (the round rest needs all members).
            val items = routine?.items?.let { Circuits.resolveRests(Circuits.normalize(it, catalog), catalog) }
            items?.forEachIndexed { index, item -> addItem(id, index, item, modifier) }
            if (items?.any { it.warmup } == true) addSpecificWarmups(id)
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
        // Not inside a circuit: its first round warms up (device test 2026-10-17: three ramp sets of a light
        // dumbbell press and a pull-up warm-up stood before the first round).
        sets.filter { it.setType == SetType.WORK && it.circuit == null }
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
     * The rows are copies of [firstWork], so a circuit member's warm-ups carry
     * its circuit and are played before the circuit's first round.
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

    /** Open injuries plus what today's check-in says hurts (spared today only). */
    fun injuriesToday(): List<Injury> =
        repo.activeInjuries() + com.cruxcoach.athlete.logic.Soreness.injuries(repo.checkin(today().toString()))

    /** Appends an exercise; returns false when the open injury rules it out. */
    fun addExercise(workoutId: String, slug: String): Boolean = withReentrantLock(structureLock) {
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
        // "As planned" earlier today: the athlete decided, nothing is trimmed for the rest of the day.
        if (repo.setting(ReadinessModifiers.AS_PLANNED_KEY) == today.toString()) return@runCatching ReadinessModifier.NONE
        val checkin = repo.checkin(today.toString())
        val readiness = ReadinessEvaluator.evaluate(checkin, repo.activeInjuries() + com.cruxcoach.athlete.logic.Soreness.injuries(checkin))
        val activity = activities(1)[today.toString()]
        val climbed = activity?.takeIf { it.climbingMinutes > 0 || it.climbingEfforts > 0 }?.climbIntensity
        ReadinessModifiers.of(readiness, climbed)
    }.getOrDefault(ReadinessModifier.NONE)

    /** What today's form changed in this training, while the athlete has not chosen "as planned". */
    fun dayForm(workoutId: String): ReadinessModifier? =
        runCatching { ReadinessModifier.decode(repo.setting(ReadinessModifiers.settingKey(workoutId))) }.getOrNull()

    /**
     * "Wie geplant": undoes what today's form trimmed in the open part of the training – loads of open
     * work sets back to full, the trimmed set back for exercises (one round for circuits) that still have
     * an open set – and nothing is trimmed for the rest of the day. Done sets stay as they are.
     */
    /** What "Wie geplant" changed, so "Rückgängig" can put today's trim back. */
    data class AsPlannedUndo(val changed: List<ExerciseSet>, val added: List<String>, val dayForm: String?, val loads: String?, val asPlannedDay: String?)

    suspend fun asPlanned(workoutId: String): AsPlannedUndo? = withContext(io) {
        val m = dayForm(workoutId) ?: return@withContext null
        val changed = mutableListOf<ExerciseSet>()
        val added = mutableListOf<String>()
        val previousDayForm = repo.setting(ReadinessModifiers.settingKey(workoutId))
        val previousLoads = repo.setting(ReadinessModifiers.settingKey(workoutId) + ".loads")
        val previousAsPlanned = repo.setting(ReadinessModifiers.AS_PLANNED_KEY)
        withReentrantLock(structureLock) {
            val bodyweight = currentBodyweight()
            val increment = repo.profile().smallestIncrementKg
            repo.transaction {
                val work = repo.setsFor(workoutId).filter { it.setType == SetType.WORK }
                val loadsKey = ReadinessModifiers.settingKey(workoutId) + ".loads"
                // id → planned target, trimmed target, planned load, trimmed load
                val planned = repo.setting(loadsKey).orEmpty().split(";").mapNotNull { row ->
                    row.split(",").takeIf { it.size == 5 }?.let { f -> f[0] to f.drop(1).map { it.toDoubleOrNull() } }
                }.toMap()
                work.filter { !it.isCompleted }.forEach { set ->
                    val def = catalog.fallbackFor(set.exerciseSlug)
                    val f = ReadinessModifiers.loadFactorFor(def, m)
                    fun back(now: Double?, plannedValue: Double?, trimmedValue: Double?): Double? = when {
                        // Untouched since it was trimmed: exactly the planned value.
                        planned.containsKey(set.id) && now == trimmedValue -> plannedValue
                        // Changed since (e.g. after a missed set): scale what is there back up.
                        f > 0 && f != 1.0 -> ReadinessModifiers.scaleLoad(def, now, set.bodyweightKg ?: bodyweight, 1 / f, increment)
                        else -> now
                    }
                    val p = planned[set.id]
                    val restored = set.copy(targetLoadKg = back(set.targetLoadKg, p?.get(0), p?.get(1)), loadKg = back(set.loadKg, p?.get(2), p?.get(3)))
                    if (restored != set) { changed += set; repo.saveSet(restored) }
                }
                val open = work.filter { !it.isCompleted }
                // Circuits: a round more when the rounds were trimmed.
                open.mapNotNull { it.circuit }.distinct().forEach { circuit ->
                    if (m.setsDelta < 0) {
                        val sets = repo.setsFor(workoutId)
                        val members = sets.filter { it.circuit == circuit }.map { it.blockIndex }.toSet()
                        val round = (sets.filter { it.blockIndex in members && it.setType != SetType.WARMUP }.maxOfOrNull { it.setIndex } ?: -1) + 1
                        members.sorted().forEach { block ->
                            WorkoutPlanner.extraSet(sets.filter { it.blockIndex == block }, repo::newId)
                                .forEach { added += it.id; repo.saveSet(it.copy(setIndex = round, circuit = circuit)) }
                        }
                    }
                }
                open.filter { it.circuit == null }.map { it.blockIndex }.distinct().forEach { block ->
                    val rows = repo.setsFor(workoutId).filter { it.blockIndex == block }
                    val def = catalog.fallbackFor(rows.first().exerciseSlug)
                    val workSets = rows.filter { it.setType == SetType.WORK }.map { it.setIndex }.distinct().size
                    repeat(ReadinessModifiers.setsToRestore(def, workSets, m)) {
                        WorkoutPlanner.extraSet(repo.setsFor(workoutId).filter { it.blockIndex == block }, repo::newId)
                            .forEach { added += it.id; repo.saveSet(it) }
                    }
                }
                repo.putSetting(ReadinessModifiers.settingKey(workoutId), "")
                repo.putSetting(loadsKey, "")
                repo.putSetting(ReadinessModifiers.AS_PLANNED_KEY, today().toString())
            }
        }
        AsPlannedUndo(changed, added, previousDayForm, previousLoads, previousAsPlanned)
    }

    /** "Rückgängig" after "Wie geplant": the added sets go (unless done meanwhile), loads and today's trim come back. */
    suspend fun undoAsPlanned(workoutId: String, undo: AsPlannedUndo): Unit = withContext(io) {
        withReentrantLock(structureLock) {
            repo.transaction {
                val now = repo.setsFor(workoutId).associateBy { it.id }
                undo.added.forEach { id -> if (now[id]?.isCompleted == false) repo.deleteSet(id) }
                undo.changed.forEach { old -> if (now[old.id]?.isCompleted == false) repo.saveSet(old) }
                repo.putSetting(ReadinessModifiers.settingKey(workoutId), undo.dayForm.orEmpty())
                repo.putSetting(ReadinessModifiers.settingKey(workoutId) + ".loads", undo.loads.orEmpty())
                repo.putSetting(ReadinessModifiers.AS_PLANNED_KEY, undo.asPlannedDay.orEmpty())
            }
        }
    }

    /** [setIndexOf] renumbers the planned sets (0..n-1), e.g. so an exercise taking over in a circuit plays the remaining rounds. */
    private fun addItem(
        workoutId: String,
        blockIndex: Int,
        item: RoutineItem,
        modifier: ReadinessModifier = ReadinessModifier.NONE,
        setIndexOf: (Int) -> Int = { it },
    ): Boolean {
        val def = catalog.fallbackFor(item.slug)
        val bodyweight = currentBodyweight()
        val increment = repo.profile().smallestIncrementKg
        val adjusted = ReadinessModifiers.applyToItem(item, def, modifier)
        val history = repo.history(item.slug, 60)
        val planned = WorkoutPlanner.plan(
            // Real injuries only: what the check-in says hurts today shapes the suggestion, but never
            // takes exercises out of a workout the athlete chose (device test 2026-10-16).
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
        // The training remembers what today's form changed, so the player can say why and offer "as planned".
        if (!item.warmup && !item.test && !modifier.isNeutral && ReadinessModifiers.affects(def, modifier) &&
            repo.setting(ReadinessModifiers.settingKey(workoutId)) == null
        ) repo.putSetting(ReadinessModifiers.settingKey(workoutId), modifier.encode())
        val trimmed = mutableListOf<String>()
        planned.sets.forEach { row ->
            val set = setIndexOf(row.setIndex).let { if (it == row.setIndex) row else row.copy(setIndex = it) }
            val scaled = if (factor == 1.0 || set.setType != SetType.WORK) set else set.copy(
                targetLoadKg = ReadinessModifiers.scaleLoad(def, set.targetLoadKg, set.bodyweightKg ?: bodyweight, factor, increment),
                loadKg = ReadinessModifiers.scaleLoad(def, set.loadKg, set.bodyweightKg ?: bodyweight, factor, increment),
            )
            if (scaled.targetLoadKg != set.targetLoadKg || scaled.loadKg != set.loadKg) trimmed += listOf(
                set.id, set.targetLoadKg, scaled.targetLoadKg, set.loadKg, scaled.loadKg).joinToString(",") { it?.toString().orEmpty() }
            repo.saveSet(scaled)
        }
        // The planned loads, so "as planned" restores them exactly (scaling back would round differently).
        if (trimmed.isNotEmpty()) {
            val key = ReadinessModifiers.settingKey(workoutId) + ".loads"
            repo.putSetting(key, (listOfNotNull(repo.setting(key)) + trimmed).joinToString(";"))
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
     * [StartEstimate] for a dumbbell, kettlebell or barbell lift (a share of body weight) or a block lift or
     * pick-up without any value: from a
     * related value (two-arm pick-up, max hang, one-arm pick-up) when there is
     * one, else from body weight and the climbing grade (logbook or coach setup).
     */
    fun startEstimate(def: com.cruxcoach.athlete.catalog.ExerciseDefinition, bodyweightKg: Double? = currentBodyweight()): Capacity? {
        StartEstimate.liftCapacity(def, bodyweightKg)?.let { return it }
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
     * A short test for one exercise (a strength test, climber version):
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

    /** What a completed set changed besides the log; [firstValue]: it gave the exercise its first performance value. */
    data class SetOutcome(val record: PersonalRecord?, val raisedBenchmark: Benchmark?, val capacity: Capacity?, val firstValue: Boolean = false)

    /**
     * The value a completed set leaves ([ValueUpdates]): a test is the truth, a first training learns the value
     * (also below a self-estimate), and a trusted value rises by at most 10 % on one set's word.
     */
    private fun updateBenchmark(set: ExerciseSet): Triple<Benchmark, Capacity, Boolean>? {
        if (set.setType == SetType.WARMUP) return null
        val def = catalog[set.exerciseSlug] ?: return null
        val all = repo.benchmarks(def.slug)
        fun sameSide(list: List<Benchmark>) = BenchmarkMath.select(list, set.side, set.edgeMm, set.grip)?.takeIf { it.side == set.side }
        val existing = sameSide(all)
        // The value the training started with: what the athlete trusted before these sets.
        val started = repo.workout(set.workoutId)?.startedAt
        val before = sameSide(if (started == null) all else all.filter { it.measuredAt < started })
        val session = repo.setsFor(set.workoutId).filter {
            it.id != set.id && it.isCompleted && it.exerciseSlug == set.exerciseSlug && it.side == set.side
        }
        val update = ValueUpdates.decide(def, set, existing, before, session, set.bodyweightKg) ?: return null
        val source = if (set.setType == SetType.TEST) BenchmarkSource.TEST else BenchmarkSource.AUTO
        val raw = BenchmarkMath.benchmarkFromSet(def, update.source, repo.newId(), source, nowMs())
        val b = if (update.capped) ValueUpdates.scaledTo(def, raw, update.value, set.bodyweightKg) else raw
        repo.saveBenchmark(b)
        return Triple(b, Capacity(update.kind, update.value, set.bodyweightKg), existing == null)
    }

    /**
     * Marks a set done and, if the athlete wants that, starts the shared rest
     * timer (the same one the board player uses: banner on every screen,
     * Doze-safe alarm, survives process death). Returns a record if one fell.
     */
    fun completeSet(set: ExerciseSet, startRest: Boolean): PersonalRecord? = completeSetDetailed(set, startRest).record

    fun completeSetDetailed(set: ExerciseSet, startRest: Boolean, restSeconds: Int? = null): SetOutcome {
        val done = set.copy(completedAt = nowMs(), bodyweightKg = set.bodyweightKg ?: currentBodyweight())
        val history = repo.history(set.exerciseSlug, HISTORY_LIMIT).filter { it.id != set.id }
        repo.saveSet(done)
        if (startRest) (restSeconds ?: done.restS ?: catalog[set.exerciseSlug]?.defaults?.restS)?.takeIf { it > 0 }?.let(::startRest)
        val raised = runCatching { updateBenchmark(done) }.getOrNull()
        return SetOutcome(PersonalRecords.detect(catalog.fallbackFor(set.exerciseSlug), done, history), raised?.first, raised?.second,
            firstValue = raised?.third == true)
    }

    fun reopenSet(set: ExerciseSet) = repo.saveSet(set.copy(completedAt = null))
    fun updateSet(set: ExerciseSet) {
        repo.saveSet(set)
        // The reserve answer comes after the set: it counts for the value too (device test 2026-10-17: a first
        // value ignored "3+ in reserve" and placed a 7a climber at 5a). It counts like a fresh set ([ValueUpdates]).
        if (set.isCompleted && set.setType != SetType.WARMUP) runCatching { updateBenchmark(set) }
    }
    fun addSet(workoutId: String, blockIndex: Int) {
        val block = repo.setsFor(workoutId).filter { it.blockIndex == blockIndex }
        val extra = WorkoutPlanner.extraSet(block, repo::newId)
        extra.forEach(repo::saveSet)
        val slug = extra.firstOrNull()?.exerciseSlug ?: return
        val workSets = (block + extra).filter { it.setType != SetType.WARMUP }.map { it.setIndex }.distinct().size
        logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.EXTRA_SET, listOf(slug), workoutId = workoutId, value = workSets.toDouble())
    }

    /**
     * "Übung beenden" / "Zirkel beenden": removes the open sets of [blockIndexes] in one go and returns
     * them for "Rückgängig" ([restoreSets]); done sets stay. A block that had at least one done set is
     * logged as ended early ([PreferenceLearning.ENDED_EARLY], value = share of its work sets done) –
     * not as skipped sets, so stopping after two good sets costs the exercise nothing.
     */
    suspend fun endBlocks(workoutId: String, blockIndexes: Collection<Int>): List<ExerciseSet> = withContext(io) {
        val blocks = blockIndexes.toSet()
        val (removed, ended) = withReentrantLock(structureLock) {
            val sets = repo.setsFor(workoutId).filter { it.blockIndex in blocks }
            val open = sets.filter { !it.isCompleted }
            repo.transaction { open.forEach { repo.deleteSet(it.id) } }
            open to sets.groupBy { it.blockIndex }.toSortedMap().values.filter { rows -> rows.any { it.isCompleted } && rows.any { !it.isCompleted } }
        }
        ended.forEach { rows ->
            val work = rows.filter { it.setType != SetType.WARMUP }
            val share = if (work.isEmpty()) null else work.count { it.isCompleted }.toDouble() / work.size
            logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.ENDED_EARLY, listOf(rows.first().exerciseSlug),
                workoutId = workoutId, value = share)
        }
        removed
    }

    /** Puts sets back as they were, e.g. the open sets [endBlocks] removed ("Rückgängig"). */
    suspend fun restoreSets(sets: List<ExerciseSet>): Unit = withContext(io) {
        if (sets.isEmpty()) return@withContext
        withReentrantLock(structureLock) { repo.transaction { sets.forEach(repo::saveSet) } }
    }

    /**
     * "+ Runde": one more set for every member exercise of [circuit], all in a new round after the
     * circuit's last one, each copying its exercise's last set with the target prefilled
     * ([WorkoutPlanner.extraSet]). Returns the new sets.
     */
    suspend fun addRound(workoutId: String, circuit: Int): List<ExerciseSet> = withContext(io) {
        withReentrantLock(structureLock) {
            val sets = repo.setsFor(workoutId)
            val members = sets.filter { it.circuit == circuit }.map { it.blockIndex }.toSet()
            val round = (sets.filter { it.blockIndex in members && it.setType != SetType.WARMUP }.maxOfOrNull { it.setIndex } ?: -1) + 1
            val added = members.sorted().flatMap { block ->
                WorkoutPlanner.extraSet(sets.filter { it.blockIndex == block }, repo::newId).map { it.copy(setIndex = round, circuit = circuit) }
            }
            if (added.isNotEmpty()) repo.transaction { added.forEach(repo::saveSet) }
            added
        }
    }
    fun removeSet(id: String) = repo.deleteSet(id)
    fun removeBlock(workoutId: String, blockIndex: Int) = repo.deleteBlock(workoutId, blockIndex)

    /**
     * What could replace [slug] in an open training with [slugs] (UX review 2026-10-16: swapping in the
     * player only worked through "it hurts"): the suggestion's filters with today's place, injuries
     * and what hurts today; around an injury that pauses climbing only what spares it.
     */
    fun alternativesInTraining(slug: String, slugs: List<String>): List<SessionSuggester.Alternative> {
        val today = today()
        val profile = TrainingPlaces.inUse(repo.profile(), today.toString())
        val checkin = repo.checkin(today.toString())
        val active = repo.activeInjuries()
        val injuries = active + Soreness.injuries(checkin)
        // The same knowledge as the card (comparison review #11): what the athlete chooses comes first,
        // what they turned down lately comes last.
        val (affinity, resting) = learnedChoices(today, profile)
        val input = SuggestionInput(catalog, profile, today, ReadinessEvaluator.evaluate(checkin, injuries), injuries,
            favorites = repo.favorites(), benchmarkSlugs = repo.allBenchmarks().map { it.exerciseSlug }.toSet(),
            affinity = affinity, resting = resting)
        val focus = if (active.any { it.climbingPaused }) SuggestionFocus.INJURY_SAFE else SuggestionFocus.PULL_PUSH
        return SessionSuggester.alternatives(input, slugs.distinct().map { RoutineItem(it) }, focus, slug)
    }

    /** Learned preference per exercise and the exercises resting after a rejection or a kept choice (today's included). */
    private fun learnedChoices(today: LocalDate, profile: AthleteProfile): Pair<Map<String, Double>, Set<String>> = runCatching {
        val sinceMs = nowMs() - PreferenceLearning.WINDOW_DAYS * 24L * 3600 * 1000
        val resets = profile.learningResets
        val events = repo.suggestionEventsSince(sinceMs)
        PreferenceLearning.affinity(events, repo.completedSetsSince(sinceMs), today, resets) to
            (PreferenceLearning.resting(events, today, resets).keys + PreferenceLearning.keptEffect(PreferenceLearning.kept(events, today, resets)).resting.keys)
    }.getOrDefault(emptyMap<String, Double>() to emptySet())

    // ── Weekly volume as a hint (comparison review #13) ──

    /** This week's volume per area as the weekly targets show it (the block without fatigue signals). */
    fun weeklyProgress(profile: AthleteProfile = repo.profile(), injuries: List<Injury> = repo.activeInjuries()): List<AreaProgress> {
        val today = today()
        val weekStart = WeeklyVolume.weekStartOf(today)
        val coach = profile.coach
        fun day(iso: String?) = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val block = TrainingBlocks.state(day(coach.blockStartDay), day(coach.targetDate), today)
        val targets = WeeklyVolume.targets(profile, block, injuries, coach.trainingDaysPerWeek ?: profile.weeklyGoal)
        val workoutDays = repo.workoutsBetween(weekStart.toString(), today.toString()).associate { it.id to LocalDate.parse(it.day) }
        val startMs = java.time.LocalDate.parse(weekStart.toString()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val sets = repo.completedSetsSince(startMs).mapNotNull { set -> workoutDays[set.workoutId]?.let { it to set } }
        return WeeklyVolume.progress(sets, activities(days = 8), catalog, weekStart, targets, today)
    }

    /** How far the week is for the area [slug] trains – a hint where it is left out or removed, never a lock; null without a target. */
    fun weeklyFor(slug: String): AreaProgress? = runCatching {
        val area = WeeklyVolume.areaOf(catalog.fallbackFor(slug)) ?: return null
        weeklyProgress().firstOrNull { it.area == area }?.takeIf { !it.target.isOff && it.goal > 0 }
    }.getOrNull()

    // ── "From now on like this?" after a training (comparison review #3) ──

    /**
     * What the athlete changed in the suggestion [workout] was started from (swapped or left out), asked once right
     * after it: empty for another training, another day, no change or an answer already given.
     */
    fun keepQuestion(workout: Workout): List<PreferenceLearning.Change> = runCatching {
        if (workout.routineId?.startsWith("suggestion") != true || workout.day != today().toString()) return emptyList()
        val events = repo.suggestionEventsForDay(workout.day)
        val answered = events.any { it.workoutId == workout.id && it.kind == SuggestionEventKind.FEEDBACK &&
            (it.focus == PreferenceLearning.KEEP_CHOICE || it.focus == PreferenceLearning.KEEP_TODAY_ONLY) }
        if (answered) emptyList() else PreferenceLearning.changes(events, workout.id)
    }.getOrDefault(emptyList())

    /**
     * "Ja, künftig so": each chosen exercise leads its slot and each replaced or left-out one rests until the training
     * block ends, three weeks at least. Returns the ledger entries (for "Rückgängig") and the day it holds until.
     */
    fun keepChoices(workout: Workout, changes: List<PreferenceLearning.Change>): Pair<List<String>, LocalDate> {
        val today = today()
        val coach = repo.profile().coach
        fun day(iso: String?) = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val until = PreferenceLearning.keepUntil(today, TrainingBlocks.blockEnd(day(coach.blockStartDay), day(coach.targetDate), today))
        val ids = changes.mapNotNull { c ->
            logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.KEEP_CHOICE, listOfNotNull(c.replaced, c.chosen),
                workoutId = workout.id, value = until.toEpochDays().toDouble())
        }
        return ids to until
    }

    /** "Nur heute": the question is answered; the swaps teach as lightly as always. */
    fun keepTodayOnly(workout: Workout): String? =
        logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.KEEP_TODAY_ONLY, emptyList(), workoutId = workout.id)

    /** Swaps a whole block to another exercise (progression chain "easier/harder"). */
    fun swapBlock(workoutId: String, blockIndex: Int, newSlug: String) {
        val def = catalog[newSlug] ?: return
        repo.transaction {
            // The new exercise keeps the replaced one's number of sets and its warm-up role (device test
            // 2026-10-17: a swap in an intro week came with 5 fresh sets where the plan had 2).
            val old = repo.setsFor(workoutId).filter { it.blockIndex == blockIndex }
            val workIndices = old.filter { it.setType == SetType.WORK }.map { it.setIndex }.distinct().sorted()
            val work = workIndices.size
            val warm = old.filter { it.setType == SetType.WARMUP }.map { it.setIndex }.distinct().size
            val planned = WorkoutPlanner.itemFor(def)
            val base = when {
                work > 0 -> planned.copy(sets = work)
                warm > 0 -> planned.copy(sets = warm, warmup = true)
                else -> planned
            }
            // In a circuit the new exercise takes the old one's place in the same rounds, with the circuit's rests.
            val member = old.firstOrNull { it.circuit != null && it.setType != SetType.WARMUP }
            val item = if (member == null || base.warmup) base
                else base.copy(circuit = member.circuit, circuitRestS = member.restS, roundRestS = member.roundRestS)
            repo.deleteBlock(workoutId, blockIndex)
            addItem(workoutId, blockIndex, item, setIndexOf = { i -> if (item.circuit == null) i else roundAt(workIndices, i) })
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
    fun adjustUpcomingSets(workoutId: String, done: ExerciseSet, previous: SetAdjustment? = null): SetAdjustment? = withReentrantLock(structureLock) {
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
     * "Tut weh" in the middle of a training (a pain sheet, climber
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
    ): PainOutcome = withReentrantLock(structureLock) {
        val sets = repo.setsFor(workoutId)
        val block = sets.filter { it.blockIndex == blockIndex }
        val def = block.firstOrNull()?.let { catalog.fallbackFor(it.exerciseSlug) } ?: return PainOutcome(action)
        val now = nowMs()
        val injury = Injury(
            id = repo.newId(), region = region, side = side, severity = severity.coerceIn(0, 9),
            climbingPaused = severity >= PAIN_PAUSE_SEVERITY, startedOn = today().toString(), updatedAt = now,
        )
        val open = block.filter { !it.isCompleted }
        repo.transaction {
            if (remember) repo.saveInjury(injury)
            when (action) {
                PainAction.SWAP -> {
                    // What today's check-in says hurts counts too, as on the card and in the player's swap (audit W9).
                    val alternative = painAlternative(def, injuriesToday() + (if (remember) emptyList<Injury>() else listOf(injury)))
                    if (alternative == null) {
                        lighten(def, open)
                        return@transaction PainOutcome(PainAction.LIGHTER, injuryId = injury.id.takeIf { remember })
                    }
                    open.forEach { repo.deleteSet(it.id) }
                    // The alternative comes right after this block: later blocks move one place down.
                    sets.filter { it.blockIndex > blockIndex }.forEach { repo.saveSet(it.copy(blockIndex = it.blockIndex + 1)) }
                    val openRounds = open.filter { it.setType != SetType.WARMUP }.map { it.setIndex }.distinct().sorted()
                    val remaining = openRounds.size.coerceAtLeast(1)
                    // In a circuit the alternative joins it and takes over the hurt exercise's remaining rounds.
                    val member = block.firstOrNull { it.circuit != null && it.setType != SetType.WARMUP }
                    val item = WorkoutPlanner.itemFor(alternative).copy(sets = remaining).let { item ->
                        if (member == null) item else item.copy(circuit = member.circuit, circuitRestS = member.restS, roundRestS = member.roundRestS)
                    }
                    val rounds = openRounds.ifEmpty {
                        listOf((block.filter { it.setType != SetType.WARMUP }.maxOfOrNull { it.setIndex } ?: -1) + 1)
                    }
                    addItem(workoutId, blockIndex + 1, item, setIndexOf = { i -> if (member == null) i else roundAt(rounds, i) })
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
    val restTimer: kotlinx.coroutines.flow.StateFlow<RestTimerState> get() = sessionManager.restTimer

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
        val end = nowMs()
        // Planned sets never done are dropped: the log shows what happened.
        repo.transaction {
            repo.setsFor(id).filter { !it.isCompleted }.forEach { repo.deleteSet(it.id) }
            repo.finishWorkout(id, end, sessionRpe, notes?.takeIf { it.isNotBlank() })
        }
        runCatching { learnOwnLoads(id) }
        // The rest after the last set ends with the training (device test 2026-10-17: it ran on over the summary).
        runCatching { sessionManager.cancelRestTimer() }
        return summarize(id, workout?.let { ((end - it.startedAt) / 60_000L).toInt() })
    }

    /**
     * Loads the athlete lowered during the training are theirs next time ([OwnLoads]): a value "from
     * your training" in working-set form, saved when it plans less than the value that applied.
     * Returns what was saved (the summary names it).
     */
    fun learnOwnLoads(workoutId: String): List<Benchmark> {
        val sets = repo.setsFor(workoutId)
        val now = nowMs()
        return sets.map { it.exerciseSlug }.distinct().flatMap { slug ->
            val def = catalog[slug] ?: return@flatMap emptyList()
            OwnLoads.lowered(def, sets, repo::newId, now).filter { b ->
                val bw = b.bodyweightKg ?: currentBodyweight()
                val mine = BenchmarkMath.capacity(def, b, bw) ?: return@filter false
                val current = capacityFor(def, b.side, b.edgeMm, b.grip, bw)
                current == null || current.kind != mine.kind || mine.value < current.value * 0.99
            }.onEach { repo.saveBenchmark(it) }
        }
    }

    /**
     * Values the athlete's own lower loads left in a training ([learnOwnLoads]): working-set values
     * "from your training" measured while it ran or right after.
     */
    fun ownLoadsOf(workout: Workout): List<Benchmark> {
        val until = (workout.endedAt ?: nowMs()) + 60_000L
        return repo.setsFor(workout.id).map { it.exerciseSlug }.distinct().flatMap { repo.benchmarks(it) }
            .filter { it.source == BenchmarkSource.AUTO && it.sets != null && it.measuredAt in workout.startedAt..until }
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
            val until = (workout.endedAt ?: nowMs()) + 60_000L
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
    ): String? = runCatching {
        val id = repo.newId()
        repo.logSuggestionEvent(SuggestionEvent(id, today().toString(), nowMs(), kind, focus,
            slugs, feedback, workoutId, value))
        id
    }.getOrNull()

    /** Takes a logged decision back ("Rückgängig"): it no longer teaches the coach. */
    fun forgetSuggestion(id: String) { runCatching { repo.deleteSuggestionEvent(id) } }

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
        /** The [i]-th of [rounds] (set indices in order), counting on past the last one. */
        private fun roundAt(rounds: List<Int>, i: Int): Int =
            rounds.getOrNull(i) ?: ((rounds.lastOrNull() ?: -1) + 1 + (i - rounds.size))

        const val LEDGER_KEEP_DAYS = 180L
        /** The logbook grade the athlete last said no to taking over; a higher one asks again. */
        const val GRADE_DECLINED_KEY = "logbook.grade.declined"
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

    /** A user-made exercise (the form, or a name typed into a workout that matches nothing); returns its slug. */
    suspend fun createCustomExercise(
        name: String,
        category: com.cruxcoach.athlete.catalog.ExerciseCategoryV2,
        kind: com.cruxcoach.athlete.catalog.ExerciseKind,
        load: com.cruxcoach.athlete.catalog.LoadMode,
        unilateral: Boolean,
        equipment: Collection<com.cruxcoach.athlete.catalog.EquipmentV2>,
        domains: Collection<com.cruxcoach.athlete.catalog.LoadDomain>,
        note: String = "",
    ): String {
        val slug = "user." + CustomExerciseSlug.slugify(name) + "_" +
            repo.newId().filter { it.isLetterOrDigit() }.take(6).lowercase()
        val text = com.cruxcoach.athlete.catalog.ExerciseText(name = name.trim(), why = note.trim())
        val gear = equipment.toList().ifEmpty { listOf(com.cruxcoach.athlete.catalog.EquipmentV2.NONE) }
        val needsWall = kind == com.cruxcoach.athlete.catalog.ExerciseKind.CLIMB || gear.any {
            it == com.cruxcoach.athlete.catalog.EquipmentV2.WALL || it == com.cruxcoach.athlete.catalog.EquipmentV2.BOARD ||
                it == com.cruxcoach.athlete.catalog.EquipmentV2.CAMPUS_BOARD
        }
        val tags = buildList {
            if (!needsWall) add(com.cruxcoach.athlete.catalog.ExerciseDefinition.TAG_NO_CLIMBING)
            if (com.cruxcoach.athlete.catalog.LoadDomain.FINGER !in domains) add(com.cruxcoach.athlete.catalog.ExerciseDefinition.TAG_FINGER_FREE)
        }
        repo.saveCustomExercise(com.cruxcoach.athlete.catalog.ExerciseDefinition(
            slug = slug, category = category, kind = kind, load = load, unilateral = unilateral,
            equipment = gear, domains = domains.toList(), difficulty = 2,
            defaults = com.cruxcoach.athlete.logic.ExerciseGuess.defaultPrescription(kind), tags = tags,
            i18n = mapOf("de" to text, "en" to text), custom = true,
        ))
        catalogStore.refresh()
        return slug
    }

    /**
     * Removes a user-made exercise: from the library, the athlete's workouts and its
     * performance values. Logged sets stay in the history under the exercise's name.
     */
    suspend fun deleteCustomExercise(slug: String) {
        if (catalog[slug]?.custom != true) return
        repo.transaction {
            repo.routines().filter { r -> r.items.any { it.slug == slug } }.forEach { r ->
                val items = r.items.filterNot { it.slug == slug }
                if (items.isEmpty()) repo.deleteRoutine(r.id)
                else repo.saveRoutine(r.copy(items = items, updatedAt = nowMs()))
            }
            repo.benchmarks(slug).forEach { repo.deleteBenchmark(it.id) }
            repo.deleteCustomExercise(slug)
        }
        catalogStore.refresh()
    }

    /**
     * Items for typed lines ([com.cruxcoach.athlete.logic.WorkoutTextParser]): catalogue
     * exercises as they are, unknown names as new exercises of their own, one per name.
     */
    suspend fun resolveTyped(lines: List<com.cruxcoach.athlete.logic.WorkoutTextParser.Line>): List<RoutineItem> {
        val created = mutableMapOf<String, String>()
        return lines.map { line ->
            if (line.match != null) line.item
            else {
                val g = line.guess
                val slug = created.getOrPut(line.name.trim().lowercase()) {
                    createCustomExercise(line.name, g.category, g.kind, g.load, g.unilateral, g.equipment, g.domains)
                }
                line.item.copy(slug = slug)
            }
        }
    }

    /**
     * Typed exercises appended to the open training, each with what the text said. Returns
     * the slugs an open injury ruled out, so the screen can say so instead of dropping them silently.
     */
    suspend fun addTyped(workoutId: String, lines: List<com.cruxcoach.athlete.logic.WorkoutTextParser.Line>): List<String> {
        val items = resolveTyped(lines)
        val skipped = mutableListOf<String>()
        withReentrantLock(structureLock) {
            val modifier = todaysModifier()
            var next = (repo.setsFor(workoutId).maxOfOrNull { it.blockIndex } ?: -1) + 1
            repo.transaction { items.forEach { item -> if (addItem(workoutId, next, item, modifier)) next++ else skipped += item.slug } }
        }
        return skipped
    }

    fun saveRoutineFromWorkout(workoutId: String, name: String): Routine {
        val sets = repo.setsFor(workoutId)
        val items = sets.groupBy { it.blockIndex }.toSortedMap().values.map { block ->
            // Inserted warm-up rows sort first; the routine describes the work sets.
            val work = block.filter { it.setType != SetType.WARMUP }.ifEmpty { block }
            val first = work.first()
            val rows = work.map { it.setIndex }.distinct().size
            // A circuit member: its set rest is the short one to the next exercise; the item keeps no own rest.
            val circuit = first.circuit.takeIf { first.setType != SetType.WARMUP }
            RoutineItem(
                slug = first.exerciseSlug, sets = rows,
                repsMin = first.targetReps ?: first.reps, repsMax = first.targetReps ?: first.reps,
                // No fixed load: a load in a workout wins over the prescription, and a saved
                // training should keep progressing from the performance value and last time.
                durationS = (first.targetDurationS ?: first.durationS)?.toInt(), loadKg = null,
                restS = if (circuit != null) null else first.restS, edgeMm = first.edgeMm?.toInt(), grip = first.grip,
                workS = first.workS?.toInt(), restBetweenS = first.restBetweenS?.toInt(), repsPerSet = first.repsPerSet,
                warmup = first.setType == SetType.WARMUP, test = first.setType == SetType.TEST,
                circuit = circuit, circuitRestS = circuit?.let { first.restS }, roundRestS = circuit?.let { first.roundRestS },
            )
        }
        val now = nowMs()
        return Routine(repo.newId(), name, items = Circuits.normalize(items, catalog), createdAt = now, updatedAt = now)
            .also(repo::saveRoutine)
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
     * need minus the day's deficit while losing – when it is known. The
     * day's deficit leaves room for protein, carbohydrate and 20 % fat
     * ([WeightPlan.dayDeficit]), so the rings fit the calorie target.
     */
    fun fuelTargets(dayActivity: DayActivity?, profile: AthleteProfile): FuelTargets.Targets? {
        val weight = currentBodyweight() ?: return null
        val height = heightCm()
        val need = energyNeed(profile, dayActivity, weight, height)
        val plan = if (profile.goal == AthleteGoal.LOSE_WEIGHT) {
            WeightPlan.plan(profile.weeklyLossKg, profile.targetWeightKg, weight, height, profile.sex, need)
        } else null
        val energy = profile.ownKcal ?: plan?.targetKcal ?: need?.totalKcal
        return FuelTargets.withOwn(FuelTargets.compute(weight, dayActivity, profile.proteinPerKg, energy), profile)
    }

    /** The day's estimated energy need; null without weight or height. */
    fun energyNeed(profile: AthleteProfile, dayActivity: DayActivity?, weightKg: Double? = currentBodyweight(), heightCm: Double? = heightCm()): EnergyBalance.Need? =
        EnergyBalance.need(profile, weightKg, heightCm, dayActivity, today().year)

    /**
     * The day's weight-loss plan while the goal is set; null otherwise or
     * without a body weight. With the last seven days' needs
     * ([AthleteService.weekNeeds]) it also says what pace they really add up to.
     */
    fun weightPlan(profile: AthleteProfile, dayActivity: DayActivity?, weekNeeds: List<EnergyBalance.Need> = emptyList()): WeightPlan.Plan? {
        if (profile.goal != AthleteGoal.LOSE_WEIGHT) return null
        val weight = currentBodyweight() ?: return null
        val height = heightCm()
        return WeightPlan.plan(profile.weeklyLossKg, profile.targetWeightKg, weight, height, profile.sex,
            energyNeed(profile, dayActivity, weight, height), weekNeeds)
    }

    // ── Planned training for today's targets (audit W4) ──────────────

    /**
     * Stores today's suggestion [s] as today's plan for nutrition ([PlannedTraining]) and returns what it
     * stands for. Writes only when it differs from what the same screen wrote before ([previous]): two Today
     * screens alive at once (a reminder can open a second one) would otherwise overwrite each other in turn,
     * each write making the other re-derive. The last change the athlete made wins.
     */
    fun rememberPlannedToday(s: SessionSuggestion, previous: String? = null): String {
        val value = PlannedTraining.encode(PlannedTraining.summaryOf(s, today()))
        if (value != previous && repo.setting(PlannedTraining.SETTING_KEY) != value) repo.putSetting(PlannedTraining.SETTING_KEY, value)
        return value
    }

    /**
     * Today's planned training estimated as a [DayActivity]: from today's suggestion as Today last computed it,
     * else from the week plan and the usual climbing days. Null when nothing is planned.
     */
    fun plannedActivityToday(): PlannedTraining.Planned? = runCatching {
        val today = today()
        val stored = PlannedTraining.decode(repo.setting(PlannedTraining.SETTING_KEY))?.takeIf { it.day == today.toString() }
        PlannedTraining.planned(stored ?: run {
            // Sick or resting by the check-in: Today would suggest rest, so nothing is planned.
            val resting = ReadinessEvaluator.evaluate(repo.checkin(today.toString()), repo.activeInjuries()).level == ReadinessLevel.REST
            if (resting) null else PlannedTraining.fromWeekPlan(repo.profile(), today, repo.routines(), catalog, repo.pauses())
        })
    }.getOrNull()

    /** Tomorrow is a planned board day or a usual climbing day (audit P1-5): today's carbohydrate info says so. */
    fun tomorrowHard(profile: AthleteProfile): Boolean =
        runCatching { PlannedTraining.tomorrowHard(profile, today(), repo.pauses()) }.getOrDefault(false)

    /** The plan today's targets follow while it is not done and weighs more than what is done ([real]); null otherwise. */
    fun plannedForTargets(real: DayActivity?): PlannedTraining.Planned? = PlannedTraining.forTargets(real, plannedActivityToday())

    /**
     * Today's weight-loss plan for [activity] as today's targets see it: the last six days stay real, so the
     * week's pace and the deficit fit the rings. The energy check ([energyReport]) keeps the real today.
     */
    fun todayWeightPlan(profile: AthleteProfile, activity: DayActivity?, activities: Map<String, DayActivity>): WeightPlan.Plan? =
        weightPlan(profile, activity, weekNeeds(profile, withToday(activities, activity)))

    /** [activities] with today as today's targets see it ([activity]: the planned training while it counts). */
    fun withToday(activities: Map<String, DayActivity>, activity: DayActivity?): Map<String, DayActivity> {
        val day = today().toString()
        return if (activity == null) activities - day else activities + (day to activity)
    }

    /** The estimated needs of the last seven days, today last; empty without weight or height. */
    fun weekNeeds(profile: AthleteProfile, activities: Map<String, DayActivity>): List<EnergyBalance.Need> {
        val today = today()
        val weight = currentBodyweight()
        val height = heightCm()
        return (6 downTo 0).mapNotNull { back -> energyNeed(profile, activities[today.minus(DatePeriod(days = back)).toString()], weight, height) }
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
        val days = (6 downTo 0).map { back -> today.minus(DatePeriod(days = back)).toString() }
        val needs = days.map { day -> energyNeed(profile, activities[day], weight, height) }
        val fuelDays = days.mapIndexed { i, day ->
            val entries = logs[day].orEmpty()
            val energies = entries.mapNotNull { MacroTotals.energy(it) }
            FuelDay(day, logged = entries.isNotEmpty(), carbsG = entries.mapNotNull { it.carbsG }.takeIf { it.isNotEmpty() }?.sum(),
                dayLoad = FuelTargets.dayLoad(activities[day]),
                kcal = energies.takeIf { it.isNotEmpty() }?.sumOf { it.kcal },
                needKcal = needs[i]?.totalKcal,
                complete = day != today.toString() && entries.map { it.meal }.distinct().size >= 2)
        }
        val todayNeed = needs.last()
        val own = profile.ownKcal?.let { kcal -> todayNeed?.let { OwnEnergyTarget(kcal, it.totalKcal, it.restingKcal) } }
        return RedsGuard.evaluate(profile, height, weightTrend(), fuelDays,
            weightPlan(profile, activities[today.toString()], needs.filterNotNull()), own, year = today.year)
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

/** Epoch milliseconds (Android: System.currentTimeMillis()). */
internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Port of `CustomExerciseScreen.slugify` (Android keeps it next to its screen). */
object CustomExerciseSlug {
    fun slugify(name: String): String {
        val ascii = name.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .trim('_')
        return ascii.ifEmpty { "exercise" }
    }
}
