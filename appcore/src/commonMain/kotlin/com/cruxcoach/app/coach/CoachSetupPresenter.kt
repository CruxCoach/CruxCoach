// Port of androidApp/.../ui/training/coach/CoachSetupScreen.kt's CoachSetupViewModel (0e1bad0c6) for
// the iOS app. Kept close to the Android file so later Android changes carry over by diff.
// Platform seams: an injected scope and IO dispatcher, the common clock, the old settings profile and
// the board logbook as optional repositories (a test may leave them out).
package com.cruxcoach.app.coach

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.CoachLogic
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.LogbookSummary
import com.cruxcoach.athlete.logic.WeekPlanSuggester
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.SetupState
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.util.DateTimeUtil
import com.cruxcoach.util.GradeConverter
import com.cruxcoach.app.training.TrainingPendingStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A climb from the logbook the athlete may pick as their project. */
internal data class CoachProject(val climbUuid: String, val name: String, val angle: Int, val grade: String?, val tries: Int)

/** Which answers were prefilled, and from where — shown as "aus deinem Logbuch". */
internal enum class PrefillField { TRAINING_DAYS, CLIMBING_DAYS, EXPERIENCE, AGE, GRADE, FLASH_GRADE }
internal enum class PrefillSource { LOGBOOK, PROFILE, HISTORY }

internal data class CoachSetupRunState(
    val loading: Boolean = true,
    /** 0 = goal, 1 = week, 2 = climbing and experience, 3 = preferences, 4 = start values and week plan. */
    val step: Int = 0,
    val draft: CoachProfile = CoachProfile(),
    val sessionMinutes: Int = 45,
    val profile: AthleteProfile = AthleteProfile(),
    val logbook: LogbookSummary = LogbookSummary(),
    val prefill: Map<PrefillField, PrefillSource> = emptyMap(),
    val projects: List<CoachProject> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val injuries: List<Injury> = emptyList(),
    val planApplied: Boolean = false,
    val finished: Boolean = false,
    /** The baseline test was started; the screen hands over to the guided player. */
    val testStarted: Boolean = false,
    /** Current body weight; asked on the climbing card, where loads in % body weight start. */
    val bodyweightKg: Double? = null,
    /** Favourite exercises, chosen next to "routine or variety" (saved at once, like the equipment). */
    val favorites: Set<String> = emptySet(),
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    /** Loading failed: the screen says so instead of spinning. */
    val failed: Boolean = false,
) {
    val proposal: Map<Int, String>
        get() = WeekPlanSuggester.suggest(draft, logbook, routines, profile.equipment, injuries)
}

internal const val COACH_STEPS = 5

/**
 * The optional coach setup (FEAT-071 §3): four short cards and a closing
 * step. Every card can be skipped, answers are stored after each card, and
 * what the logbook or the old settings profile already knows is prefilled
 * and only confirmed. Everything stays in the local profile JSON.
 */
internal class CoachSetupPresenter(
    private val service: AthleteService,
    private val userRepository: UserRepository?,
    private val boardLogbook: PersonalBoardRepository?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(CoachSetupRunState())
    val state: StateFlow<CoachSetupRunState> = _state.asStateFlow()

    /** What is stored; "Überspringen" restores a card's fields from here. */
    private var persisted = CoachProfile()
    private var persistedMinutes = 45

    /** The week plan before "apply", for "undo". */
    private var planBeforeApply: Map<Int, String>? = null

    init {
        scope.launch(io) {
            try {
                load()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("CoachSetupPresenter: not loaded: ${e.message}")
                _state.update { it.copy(loading = false, failed = true) }
                return@launch
            }
            // Equipment may change in the settings screen opened from here.
            service.repo.observeProfile().collect { p -> _state.update { it.copy(profile = p) } }
        }
        scope.launch(io) {
            service.ensureReady()
            service.repo.observeFavorites().collect { f -> _state.update { it.copy(favorites = f) } }
        }
    }

    private suspend fun load() {
        service.ensureReady()
        val repo = service.repo
        val profile = repo.profile()
        val logbook = service.logbookSummary()
        val old = runCatching { userRepository?.getActiveProfile() }.getOrNull()
        val trainedPerWeek = runCatching {
            service.activities(days = 56).values.count { it.trained } / 8.0
        }.getOrDefault(0.0)
        persisted = profile.coach
        persistedMinutes = profile.sessionMinutes

        val prefill = mutableMapOf<PrefillField, PrefillSource>()
        var d = profile.coach
        if (d.trainingDaysPerWeek == null) {
            val fromHistory = trainedPerWeek.roundToInt().takeIf { it > 0 }
            val value = fromHistory ?: old?.sessionsPerWeek?.takeIf { it in 1..7 }
            if (value != null) {
                d = d.copy(trainingDaysPerWeek = value.coerceIn(1, 7))
                prefill[PrefillField.TRAINING_DAYS] = if (fromHistory != null) PrefillSource.HISTORY else PrefillSource.PROFILE
            }
        }
        if (d.climbingDays.isEmpty() && logbook.usualClimbingDays.isNotEmpty()) {
            d = d.copy(climbingDays = logbook.usualClimbingDays)
            prefill[PrefillField.CLIMBING_DAYS] = PrefillSource.LOGBOOK
        }
        if (d.experience == null && old != null) {
            d = d.copy(experience = experienceFor(old.climbingYears))
            prefill[PrefillField.EXPERIENCE] = PrefillSource.PROFILE
        }
        if (d.ageBand == null) {
            val age = old?.age?.takeIf { it in 5..100 }
                ?: profile.birthYear?.let { service.today().year - it }?.takeIf { it in 5..100 }
            if (age != null) {
                d = d.copy(ageBand = ageBandFor(age))
                prefill[PrefillField.AGE] = PrefillSource.PROFILE
            }
        }
        if (d.currentGrade == null) {
            val fromLogbook = logbook.workingGrade.takeIf { logbook.hasGrades }
            val fromProfile = old?.maxBoulderGrade?.lowercase()?.takeIf { LogbookSummaries.difficultyOf(it) != null }
            (fromLogbook ?: fromProfile)?.let {
                d = d.copy(currentGrade = it)
                prefill[PrefillField.GRADE] = if (fromLogbook != null) PrefillSource.LOGBOOK else PrefillSource.PROFILE
            }
        }
        if (d.currentFlashGrade == null && logbook.hasGrades && logbook.flashGrade != null) {
            d = d.copy(currentFlashGrade = logbook.flashGrade)
            prefill[PrefillField.FLASH_GRADE] = PrefillSource.LOGBOOK
        }
        _state.value = CoachSetupRunState(
            loading = false,
            draft = d,
            sessionMinutes = profile.sessionMinutes,
            profile = profile,
            logbook = logbook,
            prefill = prefill,
            projects = runCatching { projects(logbook.primaryBrand) }.getOrDefault(emptyList()),
            routines = repo.routines(),
            injuries = repo.activeInjuries(),
            bodyweightKg = service.currentBodyweight(),
            favorites = repo.favorites(),
            catalog = service.catalog,
        )
    }

    fun setFavorite(slug: String, favorite: Boolean) = io { service.repo.setFavorite(slug, favorite) }

    fun update(transform: (CoachProfile) -> CoachProfile) = _state.update { it.copy(draft = transform(it.draft)) }
    fun setMinutes(minutes: Int) = _state.update { it.copy(sessionMinutes = minutes) }

    /** Equipment from the sheet on the preferences card; stored at once like the settings do. */
    fun saveEquipment(equipment: Set<EquipmentV2>) = io { service.savePlaceEquipment(null, equipment) }

    fun logWeight(kg: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, kg, "kg", nowMs()))
        _state.update { it.copy(bodyweightKg = service.currentBodyweight() ?: kg) }
    }

    /** "Weiter": stores this card and moves on. */
    fun next() {
        var s = _state.value
        if (s.loading) return
        // The week card shows the weekly goal when nothing was chosen; "Weiter" takes what it shows.
        if (s.step == 1 && s.draft.trainingDaysPerWeek == null) {
            s = s.copy(draft = s.draft.copy(trainingDaysPerWeek = s.profile.weeklyGoal.coerceIn(1, 7)))
            _state.value = s
        }
        save(s.step, s.draft, s.sessionMinutes, finish = false)
        _state.update { it.copy(step = (it.step + 1).coerceAtMost(COACH_STEPS - 1)) }
    }

    /** "Überspringen": this card's changes are dropped, the stored answers stay. */
    fun skip() = _state.update { s ->
        s.copy(draft = revertCard(s.step, s.draft, persisted), sessionMinutes = if (s.step == 1) persistedMinutes else s.sessionMinutes,
            step = (s.step + 1).coerceAtMost(COACH_STEPS - 1))
    }

    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun applyPlan() {
        val plan = _state.value.proposal
        io {
            service.repo.updateProfile { p ->
                planBeforeApply = p.weekPlan
                p.copy(weekPlan = plan)
            }
        }
        _state.update { it.copy(planApplied = true) }
    }

    /** "Rückgängig" after "apply": the week plan as it was before. */
    fun undoPlan() {
        if (!_state.value.planApplied) return
        io {
            val before = planBeforeApply ?: return@io
            service.repo.updateProfile { it.copy(weekPlan = before) }
            planBeforeApply = null
        }
        _state.update { it.copy(planApplied = false) }
    }

    fun finish() {
        val s = _state.value
        if (s.loading) return
        save(s.step, s.draft, s.sessionMinutes, finish = true)
        _state.update { it.copy(finished = true) }
    }

    /** "Test-Session": completes the setup and starts the baseline test routine (or opens a running training). */
    fun startTestSession(title: String) {
        val s = _state.value
        if (s.loading) return
        save(s.step, s.draft, s.sessionMinutes, finish = true)
        io {
            val routine = BuiltinRoutines.byKey(BuiltinRoutines.BASELINE_TESTS)
            val begin: suspend (Boolean) -> Unit = { replace ->
                if (replace) service.closeOpenWorkout()
                service.startWorkout(routine, title)
                _state.update { it.copy(testStarted = true) }
            }
            val conflict = service.openConflict(routine?.id)
            if (conflict != null) pendingStart.value = TrainingPendingStart(conflict, title, begin) else begin(false)
        }
    }

    /** A start waiting for "continue the open training or end it". */
    val pendingStart = MutableStateFlow<TrainingPendingStart?>(null)

    /** true: end the open training and start; false: continue the open one; null: start nothing. */
    fun resolveStart(replace: Boolean?) = io {
        val pending = pendingStart.value ?: return@io
        pendingStart.value = null
        if (replace != null) pending.start(replace)
    }

    fun consumeTestStarted() = _state.update { it.copy(testStarted = false) }

    /** "Nicht mehr fragen": keeps every answer, hides the setup prompts. */
    fun dismiss() = io {
        service.repo.updateProfile { p ->
            p.copy(coach = p.coach.copy(setupState = SetupState.DISMISSED, setupUpdatedAt = nowMs()))
        }
    }

    private fun save(step: Int, draft: CoachProfile, minutes: Int, finish: Boolean) = io {
        val today = service.today().toString()
        service.repo.updateProfile { p ->
            // Only this card's fields come from the draft; everything else stays as other screens left it.
            var coach = mergeCard(step, p.coach, draft)
            val state = when {
                finish -> SetupState.DONE
                coach.setupState == SetupState.DONE -> SetupState.DONE
                else -> SetupState.IN_PROGRESS
            }
            coach = coach.copy(
                setupState = state,
                setupUpdatedAt = nowMs(),
                // A goal starts the first training block.
                blockStartDay = coach.blockStartDay ?: today.takeIf { finish && coach.goal != null },
            )
            p.copy(
                coach = coach,
                sessionMinutes = if (step == 1) minutes else p.sessionMinutes,
                // One number for the week: the streak's goal follows the coach's training days.
                weeklyGoal = coach.trainingDaysPerWeek?.takeIf { step == 1 }?.coerceIn(1, 7) ?: p.weeklyGoal,
                goal = coach.goal?.takeIf { step == 0 && p.goal != AthleteGoal.LOSE_WEIGHT }?.let(CoachLogic::athleteGoalFor) ?: p.goal,
            )
        }
        persisted = mergeCard(step, persisted, draft)
        if (step == 1) persistedMinutes = minutes
        writeBackOldProfile(step, draft)
    }

    /** Keeps the old settings profile in step: sessions per week, and a harder max grade. */
    private fun writeBackOldProfile(step: Int, draft: CoachProfile) {
        if (step != 1 && step != 2) return
        val users = userRepository ?: return
        runCatching {
            val old = users.getActiveProfile() ?: return
            var updated = old
            if (step == 1) draft.trainingDaysPerWeek?.let { updated = updated.copy(sessionsPerWeek = it.coerceIn(1, 7)) }
            if (step == 2) {
                val grade = draft.currentGrade
                val newIndex = grade?.let { GradeConverter.gradeToIndex(it) } ?: -1
                val oldIndex = GradeConverter.gradeToIndex(old.maxBoulderGrade)
                if (newIndex >= 0 && newIndex > oldIndex) updated = updated.copy(maxBoulderGrade = GradeConverter.indexToFrench(newIndex))
            }
            if (updated != old) users.updateProfile(updated.copy(updatedAt = DateTimeUtil.nowIso()))
        }
    }

    /** Open projects first (attempted, never sent, tried at least three times), newest first. */
    private fun projects(brand: String?): List<CoachProject> {
        val rows = (boardLogbook ?: return emptyList()).getUserLogbookAllLight().filter { brand == null || it.boardBrand == brand }
        val sent = rows.filter { it.isSend }.map { it.climbUuid }.toSet()
        return rows.filter { !it.isSend && it.climbUuid !in sent }
            .groupBy { it.climbUuid }
            .filter { (_, tries) -> tries.sumOf { it.bidCount } >= 3 }
            .map { (uuid, tries) ->
                val last = tries.maxBy { it.climbedAt }
                CoachProject(uuid, last.climbName.ifBlank { uuid.take(8) }, last.angle.toInt(),
                    last.difficultyAverage?.let(LogbookSummaries::fontOf), tries.sumOf { it.bidCount }.toInt()) to last.climbedAt
            }
            .sortedByDescending { it.second }
            .take(6)
            .map { it.first }
    }

    fun today() = service.today()

    fun close() = scope.cancel()

    private fun io(block: suspend () -> Unit) { scope.launch(io) { service.ensureReady(); block() } }

    companion object {
        fun experienceFor(years: Double): ExperienceBand = when {
            years < 1.0 -> ExperienceBand.UNDER_1
            years < 3.0 -> ExperienceBand.Y1_2
            years <= 5.0 -> ExperienceBand.Y3_5
            else -> ExperienceBand.OVER_5
        }

        fun ageBandFor(age: Int): AgeBand = when {
            age < 16 -> AgeBand.UNDER_16
            age < 18 -> AgeBand.Y16_17
            age < 40 -> AgeBand.Y18_39
            age < 55 -> AgeBand.Y40_54
            else -> AgeBand.Y55_PLUS
        }

        fun mergeCard(step: Int, into: CoachProfile, from: CoachProfile): CoachProfile = when (step) {
            0 -> into.copy(goal = from.goal, targetGrade = from.targetGrade, targetClimbUuid = from.targetClimbUuid,
                targetClimbName = from.targetClimbName, targetClimbAngle = from.targetClimbAngle, targetDate = from.targetDate)
            1 -> into.copy(trainingDaysPerWeek = from.trainingDaysPerWeek, climbingDays = from.climbingDays,
                addOnAfterClimbing = from.addOnAfterClimbing)
            2 -> into.copy(contexts = from.contexts, experience = from.experience, ageBand = from.ageBand,
                currentGrade = from.currentGrade, currentFlashGrade = from.currentFlashGrade, ropeGrade = from.ropeGrade)
            3 -> into.copy(focus = from.focus, fingerPreference = from.fingerPreference, intensityStyle = from.intensityStyle,
                variety = from.variety)
            else -> into
        }

        fun revertCard(step: Int, draft: CoachProfile, stored: CoachProfile): CoachProfile = mergeCard(step, draft, stored)

        /** A flash is never harder than what the athlete can work out. */
        fun capFlash(flash: String?, working: String?): String? {
            val f = flash?.let { LogbookSummaries.difficultyOf(it) } ?: return flash
            val w = working?.let { LogbookSummaries.difficultyOf(it) } ?: return flash
            return if (f > w) working else flash
        }

        /**
         * Android `GradeStepper`'s − / +: from no value, the first tap lands on [startFrom] (else 6a);
         * from a value, one step along the Font scale, clamped at its ends.
         */
        fun stepGrade(value: String?, startFrom: Double?, delta: Int): String {
            val scale = LogbookSummaries.fontScale
            val index = value?.let { v -> scale.indexOf(v.lowercase()) }?.takeIf { it >= 0 }
            val start = startFrom?.let { LogbookSummaries.fontOf(it) }?.let { scale.indexOf(it) }?.takeIf { it >= 0 }
                ?: scale.indexOf("6a").coerceAtLeast(0)
            val next = if (index == null) start else index + delta
            return scale[next.coerceIn(0, scale.lastIndex)]
        }
    }
}
