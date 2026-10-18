package com.cruxcoach.app.ui

import com.cruxcoach.app.coach.COACH_STEPS
import com.cruxcoach.app.coach.CoachSetupPresenter
import com.cruxcoach.app.coach.CoachSetupRunState
import com.cruxcoach.app.coach.PrefillField
import com.cruxcoach.app.coach.PrefillSource
import com.cruxcoach.app.training.TodayPresenter
import com.cruxcoach.app.training.TrainingPendingStart
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.CoachLogic
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.ClimbingContext
import com.cruxcoach.athlete.model.CoachGoal
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.FingerPreference
import com.cruxcoach.athlete.model.FocusArea
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** A chip of the coach setup: a goal, a context, a band, a focus, … [code] is the enum name. */
class CoachChipUi(
    val code: String,
    val label: TrText,
    val selected: Boolean,
    /** False for a fourth focus area while three are chosen. */
    val enabled: Boolean,
)

/** One weekday chip (1 = Monday); Swift names the day. */
class CoachDayUi(val isoDay: Int, val selected: Boolean)

/** One day of the proposed week: the entry's localized name (Android `planEntryLabel`). */
class CoachPlanDayUi(val isoDay: Int, val label: TrText)

/** A favourite exercise, or a search hit of the favourites picker. */
class CoachExerciseUi(val slug: String, val name: String, val isFavorite: Boolean)

/** One Font grade of the picker: [code] "6c+", [label] "6c+ (V5)". */
class CoachGradeUi(val code: String, val label: String)

class CoachSetupScreenState(
    val loading: Boolean,
    val failed: Boolean,
    /** 0 goal, 1 week, 2 climbing and experience, 3 preferences, 4 start values and week plan. */
    val step: Int,
    val stepCount: Int,
    val stepText: TrText,
    val isLastStep: Boolean,
    /** "Fertig": Swift calls `onDone`. */
    val finished: Boolean,
    /** The baseline test started: Swift opens the player, then calls `consumeTestStarted()`. */
    val testStarted: Boolean,
    val todayIso: String,
    // ── Card 1: goal ──
    val goals: List<CoachChipUi>,
    /** Font grade or "". */
    val targetGrade: String,
    /** "7a (V6)", or "" without one. */
    val targetGradeText: String,
    val projects: List<CoachChipUi>,
    /** ISO date or "". */
    val targetDate: String,
    val goalPreview: TrText,
    // ── Card 2: week ──
    /** What the stepper shows: the answer, else the weekly goal (one number for both). */
    val trainingDays: Int,
    val trainingDaysText: TrText,
    val trainingDaysPrefill: TrText,
    val climbingDays: List<CoachDayUi>,
    val climbingDaysPrefill: TrText,
    val minutes: List<CoachChipUi>,
    /** yes | no | "" */
    val addOnCode: String,
    val weekPreview: List<CoachPlanDayUi>,
    // ── Card 3: climbing and experience ──
    val contexts: List<CoachChipUi>,
    val experiences: List<CoachChipUi>,
    val experiencePrefill: TrText,
    val ages: List<CoachChipUi>,
    val agePrefill: TrText,
    val currentGrade: String,
    val currentGradeText: String,
    val gradePrefill: TrText,
    val flashGrade: String,
    val flashGradeText: String,
    val flashPrefill: TrText,
    val ropeGrade: String,
    val hasWeight: Boolean,
    /** The weight, "hidden", or "For nutrition targets and loads" without one. */
    val weightText: TrText,
    /** metric | imperial */
    val unitsCode: String,
    val guardrailPreview: TrText,
    // ── Card 4: preferences ──
    /** "Don't know" focus: nothing chosen. */
    val focusUnknown: Boolean,
    val focus: List<CoachChipUi>,
    val fingers: List<CoachChipUi>,
    /** 0–100, -1 = no preference. */
    val intensity: Int,
    val variety: Int,
    val favoritesHint: TrText,
    val favorites: List<CoachExerciseUi>,
    val equipmentText: TrText,
    /** EquipmentV2 names of the place trained at today (for `TrainingEquipmentEditor`). */
    val equipmentCodes: List<String>,
    val preferencesPreview: TrText,
    // ── Step 5: start values and week plan ──
    val plan: List<CoachPlanDayUi>,
    val planApplied: Boolean,
    /** The baseline test routine's name: handed back to `startTestSession`. */
    val testTitle: TrText,
    // ── "Continue the open training or end it" ──
    val hasPendingStart: Boolean,
    val pendingTitle: TrText,
    val pendingText: TrText,
    val pendingReplace: TrText,
    val pendingContinue: TrText,
)

/**
 * The optional coach setup (Android `CoachSetupScreen` + `CoachSetupViewModel`): goal, week,
 * climbing and experience, what suits you, start values and the proposed week. Every card can be
 * skipped; "Next" stores it. [language] picks exercise names (de | en).
 */
class CoachSetupScreenModel internal constructor(
    private val presenter: CoachSetupPresenter,
    private val language: String,
) {
    val currentState: CoachSetupScreenState get() = map(presenter.state.value, presenter.pendingStart.value)

    fun watch(onState: (CoachSetupScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { combine(presenter.state, presenter.pendingStart) { s, p -> map(s, p) }.collect { onState(it) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    /** The Font scale of the grade picker. */
    val grades: List<CoachGradeUi> get() = LogbookSummaries.fontScale.map { CoachGradeUi(it, Tx.gradeWithV(it)) }

    // ── Navigation ──
    fun next() = presenter.next()
    fun skip() = presenter.skip()
    fun back() = presenter.back()
    fun finish() = presenter.finish()
    fun dismiss() = presenter.dismiss()

    // ── Card 1 ──
    fun toggleGoal(code: String) {
        val g = CoachGoal.entries.firstOrNull { it.name == code } ?: return
        presenter.update { it.copy(goal = if (it.goal == g) null else g) }
    }

    fun toggleProject(climbUuid: String) {
        val p = presenter.state.value.projects.firstOrNull { it.climbUuid == climbUuid } ?: return
        presenter.update {
            if (it.targetClimbUuid == p.climbUuid) it.copy(targetClimbUuid = null, targetClimbName = null, targetClimbAngle = null)
            else it.copy(targetClimbUuid = p.climbUuid, targetClimbName = p.name, targetClimbAngle = p.angle, goal = it.goal ?: CoachGoal.PROJECT)
        }
    }

    /** A trip or competition day, today or later; picking one makes "event" the goal when none is set. */
    fun setTargetDate(iso: String): Boolean {
        val day = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return false
        if (day < presenter.today()) return false
        presenter.update { it.copy(targetDate = day.toString(), goal = it.goal ?: CoachGoal.EVENT) }
        return true
    }

    fun clearTargetDate() = presenter.update { it.copy(targetDate = null) }

    /**
     * − / + of a grade ([field]: target | current | flash), as Android's `GradeStepper`: from no
     * value the first tap lands next to what the logbook knows.
     */
    fun stepGrade(field: String, delta: Int) {
        val s = presenter.state.value
        val d = s.draft
        val step = if (delta < 0) -1 else 1
        when (field) {
            "target" -> {
                val start = d.currentGrade?.let { LogbookSummaries.difficultyOf(it)?.plus(1.0) } ?: s.logbook.workingDifficulty?.plus(1.0)
                setGrade(field, CoachSetupPresenter.stepGrade(d.targetGrade, start, step))
            }
            "current" -> setGrade(field, CoachSetupPresenter.stepGrade(d.currentGrade, s.logbook.workingDifficulty, step))
            "flash" -> {
                val start = s.logbook.flashDifficulty ?: d.currentGrade?.let { LogbookSummaries.difficultyOf(it)?.minus(2.0) }
                setGrade(field, CoachSetupPresenter.stepGrade(d.currentFlashGrade, start, step))
            }
        }
    }

    /** Sets a grade from the picker ([grade] "" clears it); a flash never ends up above the working grade. */
    fun setGrade(field: String, grade: String) {
        val g = grade.lowercase().takeIf { it.isNotEmpty() }
        if (g != null && g !in LogbookSummaries.fontScale) return
        when (field) {
            "target" -> presenter.update { it.copy(targetGrade = g) }
            "current" -> presenter.update { it.copy(currentGrade = g, currentFlashGrade = CoachSetupPresenter.capFlash(it.currentFlashGrade, g)) }
            "flash" -> presenter.update { it.copy(currentFlashGrade = CoachSetupPresenter.capFlash(g, it.currentGrade)) }
        }
    }

    // ── Card 2 ──
    fun changeTrainingDays(delta: Int) {
        val base = presenter.state.value.profile.weeklyGoal
        presenter.update { it.copy(trainingDaysPerWeek = ((it.trainingDaysPerWeek ?: base) + delta).coerceIn(1, 7)) }
    }

    fun toggleClimbingDay(isoDay: Int) {
        if (isoDay !in 1..7) return
        presenter.update { it.copy(climbingDays = if (isoDay in it.climbingDays) it.climbingDays - isoDay else it.climbingDays + isoDay) }
    }

    fun setMinutes(minutes: Int) {
        if (minutes in TodayPresenter.DURATION_OPTIONS) presenter.setMinutes(minutes)
    }

    /** [code] yes | no: tapping the chosen one again clears the answer. */
    fun toggleAddOn(code: String) {
        val v = when (code) { "yes" -> true; "no" -> false; else -> return }
        presenter.update { it.copy(addOnAfterClimbing = if (it.addOnAfterClimbing == v) null else v) }
    }

    // ── Card 3 ──
    fun toggleContext(code: String) {
        val c = ClimbingContext.entries.firstOrNull { it.name == code } ?: return
        presenter.update { it.copy(contexts = if (c in it.contexts) it.contexts - c else it.contexts + c) }
    }

    fun toggleExperience(code: String) {
        val e = ExperienceBand.entries.firstOrNull { it.name == code } ?: return
        presenter.update { it.copy(experience = if (it.experience == e) null else e) }
    }

    fun toggleAge(code: String) {
        val a = AgeBand.entries.firstOrNull { it.name == code } ?: return
        presenter.update { it.copy(ageBand = if (it.ageBand == a) null else a) }
    }

    fun setRopeGrade(text: String) = presenter.update { it.copy(ropeGrade = text.take(8).ifBlank { null }) }

    /** [display] in the profile's units; false outside 20–300 kg (nothing is stored then). */
    fun logWeight(display: Double): Boolean {
        val kg = Units.massFromDisplay(display, presenter.state.value.profile.units)
        if (!kg.isFinite() || kg < TodayScreenModel.PLAUSIBLE_MIN_KG || kg > TodayScreenModel.PLAUSIBLE_MAX_KG) return false
        presenter.logWeight(kg)
        return true
    }

    // ── Card 4 ──
    /** A focus area, at most three; "" is "don't know" and clears them. */
    fun toggleFocus(code: String) {
        if (code.isEmpty()) { presenter.update { it.copy(focus = emptySet()) }; return }
        val f = FocusArea.entries.firstOrNull { it.name == code } ?: return
        presenter.update {
            when {
                f in it.focus -> it.copy(focus = it.focus - f)
                it.focus.size < MAX_FOCUS -> it.copy(focus = it.focus + f)
                else -> it
            }
        }
    }

    fun toggleFinger(code: String) {
        val f = FingerPreference.entries.firstOrNull { it.name == code } ?: return
        presenter.update { it.copy(fingerPreference = if (it.fingerPreference == f) null else f) }
    }

    /** 0–100; -1 = no preference. */
    fun setIntensity(value: Int) = presenter.update { it.copy(intensityStyle = slider(value)) }
    fun setVariety(value: Int) = presenter.update { it.copy(variety = slider(value)) }

    fun toggleFavorite(slug: String) {
        val s = presenter.state.value
        if (s.catalog[slug] == null) return
        presenter.setFavorite(slug, slug !in s.favorites)
    }

    fun removeFavorite(slug: String) = presenter.setFavorite(slug, false)

    /** The favourites picker: catalogue search in the app's language, favourites marked. */
    fun searchExercises(query: String): List<CoachExerciseUi> {
        val s = presenter.state.value
        return s.catalog.search(query.take(MAX_QUERY), language).take(MAX_HITS)
            .map { CoachExerciseUi(it.slug, it.name(language), it.slug in s.favorites) }
    }

    /** Equipment names (EquipmentV2) of the place trained at today; stored at once. */
    fun saveEquipment(codes: List<String>) = presenter.saveEquipment(
        codes.mapNotNull { c -> EquipmentV2.entries.firstOrNull { it.name == c } }.toSet() + TodayScreenModel.ALWAYS_THERE)

    // ── Step 5 ──
    fun applyPlan() = presenter.applyPlan()
    fun undoPlan() = presenter.undoPlan()

    /** [title]: the localized name of the baseline test (`testTitle`). */
    fun startTestSession(title: String) = presenter.startTestSession(title)

    /** 1: end the open training and start; 0: continue the open one; else start nothing. */
    fun resolveStart(choice: Int) = presenter.resolveStart(when (choice) { 1 -> true; 0 -> false; else -> null })

    fun consumeTestStarted() = presenter.consumeTestStarted()

    private fun slider(value: Int): Int? = if (value < 0) null else value.coerceIn(0, 100)

    private var lastGood: CoachSetupScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: CoachSetupRunState, pending: TrainingPendingStart?): CoachSetupScreenState = try {
        mapUnsafe(s, pending).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(CoachSetupRunState(), null)
    }

    private fun mapUnsafe(s: CoachSetupRunState, pending: TrainingPendingStart?): CoachSetupScreenState {
        val d = s.draft
        val proposal = s.proposal
        val plan = (1..7).map { CoachPlanDayUi(it, planEntry(proposal[it], s.routines)) }
        val units = s.profile.units
        val owned = s.profile.equipment.filter { it != EquipmentV2.NONE && it != EquipmentV2.MAT }
        return CoachSetupScreenState(
            loading = s.loading,
            failed = s.failed,
            step = s.step,
            stepCount = COACH_STEPS,
            stepText = Tx.t("trc_step_of", Tx.i(s.step + 1), Tx.i(COACH_STEPS)),
            isLastStep = s.step >= COACH_STEPS - 1,
            finished = s.finished,
            testStarted = s.testStarted,
            todayIso = presenter.today().toString(),
            goals = CoachGoal.entries.map { chip(it.name, goalKey(it), d.goal == it) },
            targetGrade = d.targetGrade ?: "",
            targetGradeText = d.targetGrade?.let { Tx.gradeWithV(it) } ?: "",
            projects = s.projects.map { p ->
                CoachChipUi(p.climbUuid, Tx.t("trc_project_chip", Tx.s(p.name), Tx.s(p.grade ?: "?"), Tx.i(p.angle)),
                    d.targetClimbUuid == p.climbUuid, true)
            },
            targetDate = d.targetDate ?: "",
            goalPreview = goalPreview(d),
            trainingDays = d.trainingDaysPerWeek ?: s.profile.weeklyGoal,
            trainingDaysText = Tx.t("trc_days_per_week", Tx.i(d.trainingDaysPerWeek ?: s.profile.weeklyGoal)),
            trainingDaysPrefill = prefill(s.prefill[PrefillField.TRAINING_DAYS]),
            climbingDays = (1..7).map { CoachDayUi(it, it in d.climbingDays) },
            climbingDaysPrefill = prefill(s.prefill[PrefillField.CLIMBING_DAYS]),
            minutes = TodayPresenter.DURATION_OPTIONS.map { m ->
                CoachChipUi(m.toString(), Tx.t("trc_minutes", Tx.i(m)), s.sessionMinutes == m, true)
            },
            addOnCode = when (d.addOnAfterClimbing) { true -> "yes"; false -> "no"; null -> "" },
            weekPreview = plan,
            contexts = ClimbingContext.entries.map { chip(it.name, contextKey(it), it in d.contexts) },
            experiences = ExperienceBand.entries.map { chip(it.name, experienceKey(it), d.experience == it) },
            experiencePrefill = prefill(s.prefill[PrefillField.EXPERIENCE]),
            ages = AgeBand.entries.map { chip(it.name, ageKey(it), d.ageBand == it) },
            agePrefill = prefill(s.prefill[PrefillField.AGE]),
            currentGrade = d.currentGrade ?: "",
            currentGradeText = d.currentGrade?.let { Tx.gradeWithV(it) } ?: "",
            gradePrefill = prefill(s.prefill[PrefillField.GRADE]),
            flashGrade = d.currentFlashGrade ?: "",
            flashGradeText = d.currentFlashGrade?.let { Tx.gradeWithV(it) } ?: "",
            flashPrefill = prefill(s.prefill[PrefillField.FLASH_GRADE]),
            ropeGrade = d.ropeGrade ?: "",
            hasWeight = s.bodyweightKg != null,
            weightText = when {
                s.bodyweightKg == null -> Tx.t("tru_setup_weight_text")
                s.profile.hideBodyNumbers -> Tx.t("trt_numbers_hidden_short")
                else -> Tx.mass(s.bodyweightKg, units)
            },
            unitsCode = if (units == UnitSystem.IMPERIAL) "imperial" else "metric",
            guardrailPreview = guardrailPreview(d),
            focusUnknown = d.focus.isEmpty(),
            focus = FocusArea.entries.map { f ->
                val selected = f in d.focus
                CoachChipUi(f.name, Tx.t(focusKey(f)), selected, selected || d.focus.size < MAX_FOCUS)
            },
            fingers = FingerPreference.entries.map { chip(it.name, fingerKey(it), d.fingerPreference == it) },
            intensity = d.intensityStyle ?: -1,
            variety = d.variety ?: -1,
            favoritesHint = (d.variety ?: 50).let {
                Tx.t(if (it < 40) "trc_favorites_routine" else if (it > 60) "trc_favorites_varied" else "trc_favorites_some")
            },
            favorites = s.favorites.mapNotNull { s.catalog[it] }.sortedBy { it.name(language) }
                .map { CoachExerciseUi(it.slug, it.name(language), true) },
            equipmentText = when {
                !s.profile.equipmentConfigured -> Tx.t("trc_equipment_none")
                owned.isEmpty() -> Tx.t("trc_equipment_bodyweight")
                else -> Tx.join(owned.map { Tx.t(Tx.equipmentKey(it)) }, ", ")
            },
            equipmentCodes = (s.profile.equipment.takeIf { s.profile.equipmentConfigured } ?: emptySet()).map { it.name }.sorted(),
            preferencesPreview = preferencesPreview(d),
            plan = plan,
            planApplied = s.planApplied,
            testTitle = BuiltinRoutines.byKey(BuiltinRoutines.BASELINE_TESTS)?.let { Tx.routineName(it) } ?: Tx.EMPTY,
            hasPendingStart = pending != null,
            pendingTitle = pending?.let { Tx.t("trw_open_title", Tx.x(openTitle(it))) } ?: Tx.EMPTY,
            pendingText = pending?.let { pnd ->
                val c = pnd.conflict
                Tx.join(listOf(Tx.p("trw_open_done", c.totalSets, Tx.i(c.doneSets), Tx.i(c.totalSets)),
                    Tx.t(if (c.doneSets == 0) "trw_open_dropped" else "trw_open_kept")), " ")
            } ?: Tx.EMPTY,
            pendingReplace = pending?.let { pnd -> pnd.newTitle?.let { Tx.t("trw_open_replace", Tx.s(it)) } ?: Tx.t("trw_open_replace_empty") } ?: Tx.EMPTY,
            pendingContinue = pending?.let { Tx.t("trw_open_continue", Tx.x(openTitle(it))) } ?: Tx.EMPTY,
        )
    }

    private fun chip(code: String, key: String, selected: Boolean) = CoachChipUi(code, Tx.t(key), selected, true)

    private fun openTitle(p: TrainingPendingStart): TrText =
        p.conflict.open.title?.let { Tx.plain(it) } ?: Tx.t("trw_open_untitled")

    private fun prefill(source: PrefillSource?): TrText = when (source) {
        null -> Tx.EMPTY
        PrefillSource.LOGBOOK -> Tx.t("trc_prefill_logbook")
        PrefillSource.PROFILE -> Tx.t("trc_prefill_profile")
        PrefillSource.HISTORY -> Tx.t("trc_prefill_history")
    }

    internal companion object {
        const val MAX_FOCUS = 3
        const val MAX_QUERY = 60
        const val MAX_HITS = 80

        /** Android `goalPreview`. */
        fun goalPreview(d: CoachProfile): TrText {
            val parts = mutableListOf<TrText>()
            parts += Tx.t(when (d.goal) {
                null -> "trc_preview_goal_none"
                CoachGoal.CLIMB_HARDER, CoachGoal.PROJECT -> "trc_preview_goal_perform"
                CoachGoal.BUILD_STRENGTH -> "trc_preview_goal_strength"
                CoachGoal.STAY_HEALTHY -> "trc_preview_goal_healthy"
                CoachGoal.COMEBACK -> "trc_preview_goal_comeback"
                CoachGoal.EVENT -> "trc_preview_goal_event"
            })
            d.targetClimbName?.let { parts += Tx.t("trc_preview_project", Tx.s(it), Tx.i(d.targetClimbAngle ?: 0)) }
            d.targetDate?.let { parts += Tx.t("trc_preview_date", Tx.date(it)) }
            return Tx.join(parts, " ")
        }

        /** Android `guardrailPreview`. */
        fun guardrailPreview(d: CoachProfile): TrText {
            val parts = mutableListOf<TrText>()
            parts += Tx.t(if (CoachLogic.fingerMaxAllowed(d)) "trc_preview_finger_ok" else "trc_preview_finger_guard")
            if (CoachLogic.needsLongerRecovery(d)) parts += Tx.t("trc_preview_recovery")
            d.currentGrade?.let { parts += Tx.t("trc_preview_grade", Tx.s(Tx.gradeWithV(it))) }
            return Tx.join(parts, " ")
        }

        /** Android `preferencesPreview`. */
        fun preferencesPreview(d: CoachProfile): TrText {
            val parts = mutableListOf<TrText>()
            parts += if (d.focus.isEmpty()) Tx.t("trc_preview_focus_auto")
                else Tx.t("trc_preview_focus", Tx.x(Tx.join(d.focus.map { Tx.t(focusKey(it)) }, ", ")))
            d.intensityStyle?.let {
                parts += Tx.t(if (it < 40) "trc_preview_intense" else if (it > 60) "trc_preview_calm" else "trc_preview_balanced")
            }
            d.variety?.let {
                parts += Tx.t(if (it < 40) "trc_preview_routine" else if (it > 60) "trc_preview_varied" else "trc_preview_some_variety")
            }
            if (d.fingerPreference == FingerPreference.NONE) parts += Tx.t("trc_preview_no_finger")
            return Tx.join(parts, " ")
        }

        /** Localized name of a week-plan entry (Android `planEntryLabel`). */
        fun planEntry(entry: String?, routines: List<Routine>): TrText = when {
            entry == null -> Tx.t("trwo_plan_free")
            entry == PLAN_REST -> Tx.t("trwo_plan_rest")
            entry == PLAN_BOARD -> Tx.t("trwo_plan_board")
            entry.startsWith("builtin:") -> BuiltinRoutines.byKey(entry.removePrefix("builtin:"))?.let { Tx.routineName(it) }
                ?: Tx.t("trwo_plan_unknown")
            else -> routines.firstOrNull { it.id == entry }?.let { Tx.plain(it.name) } ?: Tx.t("trwo_plan_unknown")
        }

        fun goalKey(g: CoachGoal) = when (g) {
            CoachGoal.CLIMB_HARDER -> "trc_goal_climb_harder"
            CoachGoal.PROJECT -> "trc_goal_project"
            CoachGoal.BUILD_STRENGTH -> "trc_goal_strength"
            CoachGoal.STAY_HEALTHY -> "trc_goal_healthy"
            CoachGoal.COMEBACK -> "trc_goal_comeback"
            CoachGoal.EVENT -> "trc_goal_event"
        }

        fun contextKey(c: ClimbingContext) = when (c) {
            ClimbingContext.HOME_BOARD -> "trc_context_home_board"
            ClimbingContext.GYM_BOARD -> "trc_context_gym_board"
            ClimbingContext.GYM_BOULDER -> "trc_context_gym_boulder"
            ClimbingContext.ROPE -> "trc_context_rope"
            ClimbingContext.OUTDOOR -> "trc_context_outdoor"
        }

        fun experienceKey(e: ExperienceBand) = when (e) {
            ExperienceBand.UNDER_1 -> "trc_experience_under_1"
            ExperienceBand.Y1_2 -> "trc_experience_1_2"
            ExperienceBand.Y3_5 -> "trc_experience_3_5"
            ExperienceBand.OVER_5 -> "trc_experience_over_5"
        }

        fun ageKey(a: AgeBand) = when (a) {
            AgeBand.UNDER_16 -> "trc_age_under_16"
            AgeBand.Y16_17 -> "trc_age_16_17"
            AgeBand.Y18_39 -> "trc_age_18_39"
            AgeBand.Y40_54 -> "trc_age_40_54"
            AgeBand.Y55_PLUS -> "trc_age_55_plus"
        }

        fun focusKey(f: FocusArea) = when (f) {
            FocusArea.FINGER_STRENGTH -> "trc_focus_finger"
            FocusArea.PULL_STRENGTH -> "trc_focus_pull"
            FocusArea.POWER -> "trc_focus_power"
            FocusArea.POWER_ENDURANCE -> "trc_focus_power_endurance"
            FocusArea.CORE -> "trc_focus_core"
            FocusArea.MOBILITY -> "trc_focus_mobility"
            FocusArea.PREVENTION -> "trc_focus_prevention"
        }

        fun fingerKey(f: FingerPreference) = when (f) {
            FingerPreference.HANGBOARD -> "trc_finger_hangboard"
            FingerPreference.PICKUP -> "trc_finger_pickup"
            FingerPreference.ONE_ARM -> "trc_finger_one_arm"
            FingerPreference.NONE -> "trc_finger_none"
        }
    }
}
