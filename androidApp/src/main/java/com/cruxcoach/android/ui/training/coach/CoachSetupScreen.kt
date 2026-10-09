package com.cruxcoach.android.ui.training.coach

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.workouts.planEntryLabel
import com.cruxcoach.android.ui.training.workouts.weekdayName
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.CoachLogic
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.LogbookSummary
import com.cruxcoach.athlete.logic.WeekPlanSuggester
import com.cruxcoach.athlete.model.*
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.util.DateTimeUtil
import com.cruxcoach.util.GradeConverter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import javax.inject.Inject
import kotlin.math.roundToInt

/** A climb from the logbook the athlete may pick as their project. */
data class CoachProject(val climbUuid: String, val name: String, val angle: Int, val grade: String?, val tries: Int)

/** Which answers were prefilled, and from where — shown as "aus deinem Logbuch". */
enum class PrefillField { TRAINING_DAYS, CLIMBING_DAYS, EXPERIENCE, AGE, GRADE, FLASH_GRADE }
enum class PrefillSource { LOGBOOK, PROFILE, HISTORY }

data class CoachSetupState(
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
) {
    val proposal: Map<Int, String>
        get() = WeekPlanSuggester.suggest(draft, logbook, routines, profile.equipment, injuries)
}

const val COACH_STEPS = 5

/**
 * The optional coach setup (FEAT-071 §3): four short cards and a closing
 * step. Every card can be skipped, answers are stored after each card, and
 * what the logbook or the old settings profile already knows is prefilled
 * and only confirmed. Everything stays in the local profile JSON.
 */
@HiltViewModel
class CoachSetupViewModel @Inject constructor(
    private val service: AthleteService,
    private val userRepository: UserRepository,
    private val boardLogbook: PersonalBoardRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CoachSetupState())
    val state: StateFlow<CoachSetupState> = _state.asStateFlow()

    /** What is stored; "Überspringen" restores a card's fields from here. */
    private var persisted = CoachProfile()
    private var persistedMinutes = 45

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val profile = repo.profile()
            val logbook = service.logbookSummary()
            val old = runCatching { userRepository.getActiveProfile() }.getOrNull()
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
            _state.value = CoachSetupState(
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
            )
            // Equipment may change in the settings screen opened from here.
            repo.observeProfile().collect { p -> _state.update { it.copy(profile = p) } }
        }
    }

    fun update(transform: (CoachProfile) -> CoachProfile) = _state.update { it.copy(draft = transform(it.draft)) }
    fun setMinutes(minutes: Int) = _state.update { it.copy(sessionMinutes = minutes) }

    /** Equipment from the sheet on the preferences card; stored at once like the settings do. */
    fun saveEquipment(equipment: Set<EquipmentV2>) = io {
        service.repo.updateProfile { it.copy(equipment = equipment, equipmentConfigured = true) }
    }

    fun logWeight(kg: Double) = io {
        service.repo.saveMeasurement(com.cruxcoach.athlete.model.BodyMeasurement(service.today().toString(),
            com.cruxcoach.athlete.model.BodyMetric.WEIGHT.key, kg, "kg", System.currentTimeMillis()))
        _state.update { it.copy(bodyweightKg = service.currentBodyweight() ?: kg) }
    }

    /** "Weiter": stores this card and moves on. */
    fun next() {
        var s = _state.value
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
        io { service.repo.updateProfile { it.copy(weekPlan = plan) } }
        _state.update { it.copy(planApplied = true) }
    }

    fun finish() {
        val s = _state.value
        save(s.step, s.draft, s.sessionMinutes, finish = true)
        _state.update { it.copy(finished = true) }
    }

    /** "Test-Session": completes the setup and starts the baseline test routine (or opens a running training). */
    fun startTestSession(title: String) {
        val s = _state.value
        save(s.step, s.draft, s.sessionMinutes, finish = true)
        io {
            val routine = BuiltinRoutines.byKey(BuiltinRoutines.BASELINE_TESTS)
            val begin: suspend (Boolean) -> Unit = { replace ->
                if (replace) service.closeOpenWorkout()
                service.startWorkout(routine, title)
                _state.update { it.copy(testStarted = true) }
            }
            val conflict = service.openConflict(routine?.id)
            if (conflict != null) pendingStart.value = com.cruxcoach.android.ui.training.workout.PendingStart(conflict, title, begin) else begin(false)
        }
    }

    /** A start waiting for "continue the open training or end it". */
    val pendingStart = kotlinx.coroutines.flow.MutableStateFlow<com.cruxcoach.android.ui.training.workout.PendingStart?>(null)

    fun resolveStart(replace: Boolean?) = io {
        val pending = pendingStart.value ?: return@io
        pendingStart.value = null
        if (replace != null) pending.start(replace)
    }

    fun consumeTestStarted() = _state.update { it.copy(testStarted = false) }

    /** "Nicht mehr fragen": keeps every answer, hides the setup prompts. */
    fun dismiss() = io {
        service.repo.updateProfile { p ->
            p.copy(coach = p.coach.copy(setupState = SetupState.DISMISSED, setupUpdatedAt = System.currentTimeMillis()))
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
                setupUpdatedAt = System.currentTimeMillis(),
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
        runCatching {
            val old = userRepository.getActiveProfile() ?: return
            var updated = old
            if (step == 1) draft.trainingDaysPerWeek?.let { updated = updated.copy(sessionsPerWeek = it.coerceIn(1, 7)) }
            if (step == 2) {
                val grade = draft.currentGrade
                val newIndex = grade?.let { GradeConverter.gradeToIndex(it) } ?: -1
                val oldIndex = GradeConverter.gradeToIndex(old.maxBoulderGrade)
                if (newIndex >= 0 && newIndex > oldIndex) updated = updated.copy(maxBoulderGrade = GradeConverter.indexToFrench(newIndex))
            }
            if (updated != old) userRepository.updateProfile(updated.copy(updatedAt = DateTimeUtil.nowIso()))
        }
    }

    /** Open projects first (attempted, never sent, tried at least three times), newest first. */
    private fun projects(brand: String?): List<CoachProject> {
        val rows = boardLogbook.getUserLogbookAllLight().filter { brand == null || it.boardBrand == brand }
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

    private fun io(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() } }

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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachSetupScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onOpenBenchmarks: () -> Unit,
    onStartTest: () -> Unit,
    viewModel: CoachSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingStart by viewModel.pendingStart.collectAsStateWithLifecycle()
    pendingStart?.let { com.cruxcoach.android.ui.training.workout.OpenTrainingDialog(it, viewModel::resolveStart) }
    var estimating by rememberSaveable { mutableStateOf(false) }
    var equipmentOpen by rememberSaveable { mutableStateOf(false) }
    var weightOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDismiss by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    LaunchedEffect(state.testStarted) { if (state.testStarted) { viewModel.consumeTestStarted(); onStartTest() } }
    val testTitle = BuiltinRoutines.byKey(BuiltinRoutines.BASELINE_TESTS)?.let { routineName(it) } ?: ""

    BackHandler(enabled = state.step > 0) { viewModel.back() }

    TrainingScaffold(
        title = stringResource(R.string.trc_setup_title),
        onBack = onBack,
        actions = {
            TextButton(onClick = { confirmDismiss = true }, modifier = Modifier.testTag("coach_dismiss")) {
                Text(stringResource(R.string.trc_dont_ask))
            }
        },
        bottomBar = {
            if (!state.loading) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.step < COACH_STEPS - 1) {
                            TextButton(onClick = viewModel::skip, modifier = Modifier.testTag("coach_skip")) {
                                Text(stringResource(R.string.trc_skip))
                            }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = viewModel::next, modifier = Modifier.heightIn(min = 48.dp).testTag("coach_next")) {
                                Text(stringResource(R.string.trc_next))
                            }
                        } else {
                            TextButton(onClick = viewModel::back) { Text(stringResource(R.string.trc_back)) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = viewModel::finish, modifier = Modifier.heightIn(min = 48.dp).testTag("coach_finish")) {
                                Text(stringResource(R.string.trc_finish))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        // Every card starts at its top, not where the previous one was scrolled to.
        val scroll = rememberScrollState()
        LaunchedEffect(state.step) { scroll.scrollTo(0) }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(horizontal = 16.dp)
                .testTag("coach_setup_${state.step}"),
        ) {
            StepDots(state.step, Modifier.padding(top = 12.dp, bottom = 4.dp))
            when (state.step) {
                0 -> GoalCard(state, viewModel::update)
                1 -> WeekCard(state, viewModel::update, viewModel::setMinutes)
                2 -> ExperienceCard(state, viewModel::update, onLogWeight = { weightOpen = true })
                3 -> PreferencesCard(state, viewModel::update, onOpenEquipment = { equipmentOpen = true })
                else -> FinishCard(state, onEstimate = { estimating = true }, onStartTest = { viewModel.startTestSession(testTitle) },
                    onOpenBenchmarks = onOpenBenchmarks, onApplyPlan = viewModel::applyPlan)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (estimating) QuickEstimateSheet(onDismiss = { estimating = false })
    // Equipment and weight are answered right here, without leaving the setup.
    if (equipmentOpen) {
        com.cruxcoach.android.ui.training.common.EquipmentSheet(
            initial = state.profile.equipment.takeIf { state.profile.equipmentConfigured } ?: emptySet(),
            onDismiss = { equipmentOpen = false },
            onSave = { set -> viewModel.saveEquipment(set); equipmentOpen = false },
        )
    }
    if (weightOpen) {
        com.cruxcoach.android.ui.training.common.WeightSheet(
            units = state.profile.units, lastKg = state.bodyweightKg, hideNumbers = state.profile.hideBodyNumbers,
            reason = stringResource(R.string.tru_weight_reason_setup),
            onDismiss = { weightOpen = false },
            onSave = { kg -> viewModel.logWeight(kg); weightOpen = false },
        )
    }
    if (confirmDismiss) {
        AlertDialog(
            onDismissRequest = { confirmDismiss = false },
            title = { Text(stringResource(R.string.trc_dont_ask)) },
            text = { Text(stringResource(R.string.trc_dont_ask_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDismiss = false; viewModel.dismiss(); onBack() },
                    modifier = Modifier.testTag("coach_dismiss_confirm")) { Text(stringResource(R.string.trc_dont_ask_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDismiss = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

@Composable
private fun StepDots(step: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(COACH_STEPS) { i ->
            val color = if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.size(if (i == step) 10.dp else 8.dp).background(color, CircleShape))
        }
    }
    Text(stringResource(R.string.trc_step_of, step + 1, COACH_STEPS), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** Card header: question, what it is for. */
@Composable
private fun CardHeader(title: String, why: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp))
}

/** The immediate effect of the answers so far. */
@Composable
private fun EffectPreview(text: String) {
    Card(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("coach_preview"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.trc_preview_title), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun PrefillHint(source: PrefillSource?) {
    if (source == null) return
    Text(stringResource(when (source) {
        PrefillSource.LOGBOOK -> R.string.trc_prefill_logbook
        PrefillSource.PROFILE -> R.string.trc_prefill_profile
        PrefillSource.HISTORY -> R.string.trc_prefill_history
    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun QuestionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

// ── Card 1: goal ─────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalCard(state: CoachSetupState, update: ((CoachProfile) -> CoachProfile) -> Unit) {
    val d = state.draft
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    CardHeader(stringResource(R.string.trc_goal_title), stringResource(R.string.trc_goal_why))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
        CoachGoal.entries.forEach { g ->
            FilterChip(selected = d.goal == g, onClick = { update { it.copy(goal = if (it.goal == g) null else g) } },
                label = { Text(coachGoalLabel(g)) }, modifier = Modifier.testTag("coach_goal_${g.name.lowercase()}"))
        }
    }
    QuestionLabel(stringResource(R.string.trc_target_grade))
    GradeStepper(
        value = d.targetGrade,
        startFrom = d.currentGrade?.let { LogbookSummaries.difficultyOf(it)?.plus(1.0) } ?: state.logbook.workingDifficulty?.plus(1.0),
        tag = "coach_target_grade",
        onChange = { g -> update { it.copy(targetGrade = g) } },
    )
    if (state.projects.isNotEmpty()) {
        QuestionLabel(stringResource(R.string.trc_target_project))
        Text(stringResource(R.string.trc_target_project_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.projects.forEach { p ->
                val selected = d.targetClimbUuid == p.climbUuid
                FilterChip(
                    selected = selected,
                    onClick = {
                        update {
                            if (selected) it.copy(targetClimbUuid = null, targetClimbName = null, targetClimbAngle = null)
                            else it.copy(targetClimbUuid = p.climbUuid, targetClimbName = p.name, targetClimbAngle = p.angle,
                                goal = it.goal ?: CoachGoal.PROJECT)
                        }
                    },
                    label = { Text(stringResource(R.string.trc_project_chip, p.name, p.grade ?: "?", p.angle)) },
                    modifier = Modifier.testTag("coach_project_${p.climbUuid}"),
                )
            }
        }
    }
    QuestionLabel(stringResource(R.string.trc_target_date))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(d.targetDate?.let { formatIsoDay(it) } ?: stringResource(R.string.trc_none), modifier = Modifier.weight(1f))
        if (d.targetDate != null) {
            IconButton(onClick = { update { it.copy(targetDate = null) } }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.trc_clear))
            }
        }
        OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.testTag("coach_target_date")) {
            Text(stringResource(R.string.trc_pick_date))
        }
    }
    EffectPreview(goalPreview(d))
    if (pickingDate) {
        FutureDatePickerDialog(
            initial = d.targetDate,
            onPick = { day -> update { it.copy(targetDate = day, goal = it.goal ?: CoachGoal.EVENT) }; pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
}

@Composable
private fun goalPreview(d: CoachProfile): String {
    val parts = mutableListOf<String>()
    parts += when (d.goal) {
        null -> stringResource(R.string.trc_preview_goal_none)
        CoachGoal.CLIMB_HARDER, CoachGoal.PROJECT -> stringResource(R.string.trc_preview_goal_perform)
        CoachGoal.BUILD_STRENGTH -> stringResource(R.string.trc_preview_goal_strength)
        CoachGoal.STAY_HEALTHY -> stringResource(R.string.trc_preview_goal_healthy)
        CoachGoal.COMEBACK -> stringResource(R.string.trc_preview_goal_comeback)
        CoachGoal.EVENT -> stringResource(R.string.trc_preview_goal_event)
    }
    d.targetClimbName?.let { parts += stringResource(R.string.trc_preview_project, it, d.targetClimbAngle ?: 0) }
    d.targetDate?.let { parts += stringResource(R.string.trc_preview_date, formatIsoDay(it)) }
    return parts.joinToString(" ")
}

// ── Card 2: week ─────────────────────────────────────────────────────

private val MINUTES = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekCard(state: CoachSetupState, update: ((CoachProfile) -> CoachProfile) -> Unit, setMinutes: (Int) -> Unit) {
    val d = state.draft
    CardHeader(stringResource(R.string.trc_week_title), stringResource(R.string.trc_week_why))
    QuestionLabel(stringResource(R.string.trc_training_days))
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Starts from the weekly goal (one number for both), so "+" never jumps from "–" to 4.
        val base = state.profile.weeklyGoal
        Text(stringResource(R.string.trc_days_per_week, d.trainingDaysPerWeek ?: base),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("coach_training_days"))
        OutlinedButton(onClick = { update { it.copy(trainingDaysPerWeek = ((it.trainingDaysPerWeek ?: base) - 1).coerceAtLeast(1)) } },
            modifier = Modifier.testTag("coach_training_days_minus")) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { update { it.copy(trainingDaysPerWeek = ((it.trainingDaysPerWeek ?: base) + 1).coerceAtMost(7)) } },
            modifier = Modifier.testTag("coach_training_days_plus")) { Text("+") }
    }
    PrefillHint(state.prefill[PrefillField.TRAINING_DAYS])
    QuestionLabel(stringResource(R.string.trc_climbing_days))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..7).forEach { day ->
            val selected = day in d.climbingDays
            FilterChip(selected = selected,
                onClick = { update { it.copy(climbingDays = if (selected) it.climbingDays - day else it.climbingDays + day) } },
                label = { Text(weekdayName(day, TextStyle.SHORT)) }, modifier = Modifier.testTag("coach_climbing_day_$day"))
        }
    }
    PrefillHint(state.prefill[PrefillField.CLIMBING_DAYS])
    QuestionLabel(stringResource(R.string.trc_session_minutes))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MINUTES.forEach { m ->
            FilterChip(selected = state.sessionMinutes == m, onClick = { setMinutes(m) },
                label = { Text(stringResource(R.string.trc_minutes, m)) }, modifier = Modifier.testTag("coach_minutes_$m"))
        }
    }
    QuestionLabel(stringResource(R.string.trc_add_on))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = d.addOnAfterClimbing == true, onClick = { update { it.copy(addOnAfterClimbing = if (it.addOnAfterClimbing == true) null else true) } },
            label = { Text(stringResource(R.string.trc_yes)) }, modifier = Modifier.testTag("coach_add_on_yes"))
        FilterChip(selected = d.addOnAfterClimbing == false, onClick = { update { it.copy(addOnAfterClimbing = if (it.addOnAfterClimbing == false) null else false) } },
            label = { Text(stringResource(R.string.trc_no)) }, modifier = Modifier.testTag("coach_add_on_no"))
    }
    val plan = state.proposal
    val routines = state.routines
    Card(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("coach_preview"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.trc_preview_week_title), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            (1..7).forEach { day ->
                Row(Modifier.padding(top = 2.dp)) {
                    Text(weekdayName(day, TextStyle.SHORT), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(planEntryLabel(plan[day], routines), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

// ── Card 3: climbing and experience ──────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExperienceCard(state: CoachSetupState, update: ((CoachProfile) -> CoachProfile) -> Unit, onLogWeight: () -> Unit = {}) {
    val d = state.draft
    CardHeader(stringResource(R.string.trc_experience_title), stringResource(R.string.trc_experience_why))
    QuestionLabel(stringResource(R.string.trc_contexts))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ClimbingContext.entries.forEach { c ->
            val selected = c in d.contexts
            FilterChip(selected = selected, onClick = { update { it.copy(contexts = if (selected) it.contexts - c else it.contexts + c) } },
                label = { Text(contextLabel(c)) }, modifier = Modifier.testTag("coach_context_${c.name.lowercase()}"))
        }
    }
    QuestionLabel(stringResource(R.string.trc_experience))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ExperienceBand.entries.forEach { e ->
            FilterChip(selected = d.experience == e, onClick = { update { it.copy(experience = if (it.experience == e) null else e) } },
                label = { Text(experienceLabel(e)) }, modifier = Modifier.testTag("coach_experience_${e.name.lowercase()}"))
        }
    }
    PrefillHint(state.prefill[PrefillField.EXPERIENCE])
    QuestionLabel(stringResource(R.string.trc_age))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AgeBand.entries.forEach { a ->
            FilterChip(selected = d.ageBand == a, onClick = { update { it.copy(ageBand = if (it.ageBand == a) null else a) } },
                label = { Text(ageLabel(a)) }, modifier = Modifier.testTag("coach_age_${a.name.lowercase()}"))
        }
    }
    PrefillHint(state.prefill[PrefillField.AGE])
    QuestionLabel(stringResource(R.string.trc_current_grade))
    GradeStepper(d.currentGrade, startFrom = state.logbook.workingDifficulty, tag = "coach_current_grade",
        onChange = { g -> update { it.copy(currentGrade = g, currentFlashGrade = capFlash(it.currentFlashGrade, g)) } })
    PrefillHint(state.prefill[PrefillField.GRADE])
    QuestionLabel(stringResource(R.string.trc_flash_grade))
    GradeStepper(d.currentFlashGrade, startFrom = state.logbook.flashDifficulty ?: d.currentGrade?.let { LogbookSummaries.difficultyOf(it)?.minus(2.0) },
        tag = "coach_flash_grade", onChange = { g -> update { it.copy(currentFlashGrade = capFlash(g, it.currentGrade)) } })
    PrefillHint(state.prefill[PrefillField.FLASH_GRADE])
    OutlinedTextField(
        value = d.ropeGrade.orEmpty(),
        onValueChange = { v -> update { it.copy(ropeGrade = v.take(8).ifBlank { null }) } },
        singleLine = true,
        label = { Text(stringResource(R.string.trc_rope_grade)) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("coach_rope_grade"),
    )
    QuestionLabel(stringResource(R.string.tru_setup_weight))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                state.bodyweightKg == null -> stringResource(R.string.tru_setup_weight_text)
                state.profile.hideBodyNumbers -> stringResource(R.string.trt_numbers_hidden_short)
                else -> formatMass(state.bodyweightKg, state.profile.units)
            },
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onLogWeight, modifier = Modifier.testTag("coach_weight")) {
            Text(stringResource(if (state.bodyweightKg == null) R.string.tru_enter_weight else R.string.trt_checkin_change))
        }
    }
    EffectPreview(guardrailPreview(d))
}

@Composable
private fun guardrailPreview(d: CoachProfile): String {
    val parts = mutableListOf<String>()
    parts += if (CoachLogic.fingerMaxAllowed(d)) stringResource(R.string.trc_preview_finger_ok)
        else stringResource(R.string.trc_preview_finger_guard)
    if (CoachLogic.needsLongerRecovery(d)) parts += stringResource(R.string.trc_preview_recovery)
    d.currentGrade?.let { parts += stringResource(R.string.trc_preview_grade, gradeWithV(it)) }
    return parts.joinToString(" ")
}

// ── Card 4: preferences ──────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferencesCard(state: CoachSetupState, update: ((CoachProfile) -> CoachProfile) -> Unit, onOpenEquipment: () -> Unit) {
    val d = state.draft
    CardHeader(stringResource(R.string.trc_prefs_title), stringResource(R.string.trc_prefs_why))
    QuestionLabel(stringResource(R.string.trc_focus))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = d.focus.isEmpty(), onClick = { update { it.copy(focus = emptySet()) } },
            label = { Text(stringResource(R.string.trc_focus_unknown)) }, modifier = Modifier.testTag("coach_focus_unknown"))
        FocusArea.entries.forEach { f ->
            val selected = f in d.focus
            FilterChip(
                selected = selected,
                enabled = selected || d.focus.size < 3,
                onClick = { update { it.copy(focus = if (selected) it.focus - f else it.focus + f) } },
                label = { Text(focusLabel(f)) },
                modifier = Modifier.testTag("coach_focus_${f.name.lowercase()}"),
            )
        }
    }
    Text(stringResource(R.string.trc_focus_max), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    QuestionLabel(stringResource(R.string.trc_finger_pref))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FingerPreference.entries.forEach { fp ->
            FilterChip(selected = d.fingerPreference == fp,
                onClick = { update { it.copy(fingerPreference = if (it.fingerPreference == fp) null else fp) } },
                label = { Text(fingerPrefLabel(fp)) }, modifier = Modifier.testTag("coach_finger_${fp.name.lowercase()}"))
        }
    }
    PreferenceSlider(
        title = stringResource(R.string.trc_style_intensity),
        left = stringResource(R.string.trc_style_intense),
        right = stringResource(R.string.trc_style_calm),
        value = d.intensityStyle,
        tag = "coach_intensity",
        onChange = { v -> update { it.copy(intensityStyle = v) } },
    )
    PreferenceSlider(
        title = stringResource(R.string.trc_style_variety),
        left = stringResource(R.string.trc_style_routine),
        right = stringResource(R.string.trc_style_varied),
        value = d.variety,
        tag = "coach_variety",
        onChange = { v -> update { it.copy(variety = v) } },
    )
    QuestionLabel(stringResource(R.string.trc_equipment))
    val owned = state.profile.equipment.filter { it != EquipmentV2.NONE && it != EquipmentV2.MAT }
    Text(
        if (!state.profile.equipmentConfigured) stringResource(R.string.trc_equipment_none)
        else owned.map { equipmentLabel(it) }.joinToString(", ").ifEmpty { stringResource(R.string.trc_equipment_bodyweight) },
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = onOpenEquipment, modifier = Modifier.padding(top = 4.dp).testTag("coach_open_equipment")) {
        Text(stringResource(R.string.trc_equipment_edit))
    }
    EffectPreview(preferencesPreview(d))
}

@Composable
private fun PreferenceSlider(title: String, left: String, right: String, value: Int?, tag: String, onChange: (Int?) -> Unit) {
    QuestionLabel(title)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(right, style = MaterialTheme.typography.bodySmall)
    }
    var position by remember(value) { mutableFloatStateOf((value ?: 50).toFloat()) }
    Slider(
        value = position,
        onValueChange = { position = it },
        onValueChangeFinished = { onChange(position.roundToInt()) },
        valueRange = 0f..100f,
        colors = if (value == null) SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.outline,
            activeTrackColor = MaterialTheme.colorScheme.outlineVariant,
        ) else SliderDefaults.colors(),
        modifier = Modifier.testTag(tag),
    )
    FilterChip(selected = value == null, onClick = { onChange(null) }, label = { Text(stringResource(R.string.trc_no_preference)) },
        modifier = Modifier.testTag("${tag}_none"))
}

@Composable
private fun preferencesPreview(d: CoachProfile): String {
    val parts = mutableListOf<String>()
    parts += if (d.focus.isEmpty()) stringResource(R.string.trc_preview_focus_auto)
        else stringResource(R.string.trc_preview_focus, d.focus.map { focusLabel(it) }.joinToString(", "))
    d.intensityStyle?.let {
        parts += stringResource(if (it < 40) R.string.trc_preview_intense else if (it > 60) R.string.trc_preview_calm else R.string.trc_preview_balanced)
    }
    d.variety?.let {
        parts += stringResource(if (it < 40) R.string.trc_preview_routine else if (it > 60) R.string.trc_preview_varied else R.string.trc_preview_some_variety)
    }
    if (d.fingerPreference == FingerPreference.NONE) parts += stringResource(R.string.trc_preview_no_finger)
    return parts.joinToString(" ")
}

// ── Final step: start values and week plan ───────────────────────────

@Composable
private fun FinishCard(
    state: CoachSetupState,
    onEstimate: () -> Unit,
    onStartTest: () -> Unit,
    onOpenBenchmarks: () -> Unit,
    onApplyPlan: () -> Unit,
) {
    var path by rememberSaveable { mutableIntStateOf(0) }
    CardHeader(stringResource(R.string.trc_values_title), stringResource(R.string.trc_values_why))
    StartPath(0, path, stringResource(R.string.trc_path_learn), stringResource(R.string.trc_path_learn_text), "coach_path_learn") { path = 0 }
    StartPath(1, path, stringResource(R.string.trc_path_estimate), stringResource(R.string.trc_path_estimate_text), "coach_path_estimate") {
        path = 1; onEstimate()
    }
    StartPath(2, path, stringResource(R.string.trc_path_test), stringResource(R.string.trc_path_test_text), "coach_path_test") {
        path = 2; onStartTest()
    }
    TextButton(onClick = onOpenBenchmarks, modifier = Modifier.testTag("coach_open_benchmarks")) {
        Text(stringResource(R.string.trc_open_benchmarks))
    }

    SectionTitle(stringResource(R.string.trc_plan_title))
    Text(stringResource(R.string.trc_plan_why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val plan = state.proposal
    Card(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("coach_plan")) {
        Column(Modifier.padding(12.dp)) {
            (1..7).forEach { day ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(weekdayName(day), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(112.dp))
                    Text(planEntryLabel(plan[day], state.routines), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.planApplied) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.trc_plan_applied), modifier = Modifier.testTag("coach_plan_applied"))
        } else {
            FilledTonalButton(onClick = onApplyPlan, modifier = Modifier.testTag("coach_plan_apply")) {
                Text(stringResource(R.string.trc_plan_apply))
            }
            Text(stringResource(R.string.trc_plan_later), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StartPath(index: Int, selected: Int, title: String, text: String, tag: String, onClick: () -> Unit) {
    val isSelected = index == selected
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(tag),
        border = if (isSelected) CardDefaults.outlinedCardBorder().copy(width = 2.dp) else CardDefaults.outlinedCardBorder(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Shared pieces ────────────────────────────────────────────────────

/** Font grade with − / + along the difficulty scale; "–" clears it. */
@Composable
internal fun GradeStepper(value: String?, startFrom: Double?, tag: String, onChange: (String?) -> Unit) {
    val scale = LogbookSummaries.fontScale
    val index = value?.let { v -> scale.indexOf(v.lowercase()) }?.takeIf { it >= 0 }
    fun startIndex(): Int = startFrom?.let { LogbookSummaries.fontOf(it) }?.let { scale.indexOf(it) }?.takeIf { it >= 0 }
        ?: scale.indexOf("6a").coerceAtLeast(0)
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Tapping the value opens the whole scale: one tap instead of a dozen "+".
        Text(value?.let { gradeWithV(it) } ?: stringResource(R.string.trc_grade_pick), style = MaterialTheme.typography.titleMedium,
            color = if (value == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).clickable { picking = true }.padding(vertical = 8.dp).testTag(tag))
        if (value != null) {
            IconButton(onClick = { onChange(null) }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.trc_clear)) }
        }
        OutlinedButton(onClick = { onChange(scale[((index ?: startIndex()) - if (index == null) 0 else 1).coerceIn(0, scale.lastIndex)]) },
            modifier = Modifier.testTag("${tag}_minus")) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange(scale[((index ?: startIndex()) + if (index == null) 0 else 1).coerceIn(0, scale.lastIndex)]) },
            modifier = Modifier.testTag("${tag}_plus")) { Text("+") }
    }
    if (picking) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = ((index ?: startIndex()) - 3).coerceAtLeast(0))
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.trc_grade_pick)) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp).testTag("${tag}_list")) {
                    items(scale.size) { i ->
                        val g = scale[i]
                        val selected = i == index
                        Text(gradeWithV(g), style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { onChange(g); picking = false }.padding(vertical = 12.dp)
                                .testTag("${tag}_item_$g"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

/** "6c+ (V5)" — Font with the V grade where one exists. */
internal fun gradeWithV(font: String): String {
    val index = GradeConverter.gradeToIndex(font.lowercase())
    val v = if (index >= 0) GradeConverter.GRADES.getOrNull(index)?.vScale else null
    return if (v != null) "$font ($v)" else font
}

internal fun formatIsoDay(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(iso)

/** Trip or competition day: today or later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FutureDatePickerDialog(initial: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val today = java.time.LocalDate.now()
    val todayMillis = today.toEpochDay() * 86_400_000L
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = (initial?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: today.plusWeeks(8))
            .toEpochDay() * 86_400_000L,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayMillis
            override fun isSelectableYear(year: Int) = year >= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { ms -> onPick(java.time.LocalDate.ofEpochDay(ms / 86_400_000L).toString()) } ?: onDismiss()
            }, modifier = Modifier.testTag("coach_date_ok")) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
internal fun coachGoalLabel(g: CoachGoal): String = stringResource(when (g) {
    CoachGoal.CLIMB_HARDER -> R.string.trc_goal_climb_harder
    CoachGoal.PROJECT -> R.string.trc_goal_project
    CoachGoal.BUILD_STRENGTH -> R.string.trc_goal_strength
    CoachGoal.STAY_HEALTHY -> R.string.trc_goal_healthy
    CoachGoal.COMEBACK -> R.string.trc_goal_comeback
    CoachGoal.EVENT -> R.string.trc_goal_event
})

@Composable
internal fun contextLabel(c: ClimbingContext): String = stringResource(when (c) {
    ClimbingContext.HOME_BOARD -> R.string.trc_context_home_board
    ClimbingContext.GYM_BOARD -> R.string.trc_context_gym_board
    ClimbingContext.GYM_BOULDER -> R.string.trc_context_gym_boulder
    ClimbingContext.ROPE -> R.string.trc_context_rope
    ClimbingContext.OUTDOOR -> R.string.trc_context_outdoor
})

@Composable
internal fun experienceLabel(e: ExperienceBand): String = stringResource(when (e) {
    ExperienceBand.UNDER_1 -> R.string.trc_experience_under_1
    ExperienceBand.Y1_2 -> R.string.trc_experience_1_2
    ExperienceBand.Y3_5 -> R.string.trc_experience_3_5
    ExperienceBand.OVER_5 -> R.string.trc_experience_over_5
})

@Composable
internal fun ageLabel(a: AgeBand): String = stringResource(when (a) {
    AgeBand.UNDER_16 -> R.string.trc_age_under_16
    AgeBand.Y16_17 -> R.string.trc_age_16_17
    AgeBand.Y18_39 -> R.string.trc_age_18_39
    AgeBand.Y40_54 -> R.string.trc_age_40_54
    AgeBand.Y55_PLUS -> R.string.trc_age_55_plus
})

@Composable
internal fun focusLabel(f: FocusArea): String = stringResource(when (f) {
    FocusArea.FINGER_STRENGTH -> R.string.trc_focus_finger
    FocusArea.PULL_STRENGTH -> R.string.trc_focus_pull
    FocusArea.POWER -> R.string.trc_focus_power
    FocusArea.POWER_ENDURANCE -> R.string.trc_focus_power_endurance
    FocusArea.CORE -> R.string.trc_focus_core
    FocusArea.MOBILITY -> R.string.trc_focus_mobility
    FocusArea.PREVENTION -> R.string.trc_focus_prevention
})

@Composable
internal fun fingerPrefLabel(f: FingerPreference): String = stringResource(when (f) {
    FingerPreference.HANGBOARD -> R.string.trc_finger_hangboard
    FingerPreference.PICKUP -> R.string.trc_finger_pickup
    FingerPreference.ONE_ARM -> R.string.trc_finger_one_arm
    FingerPreference.NONE -> R.string.trc_finger_none
})

/** A flash is never harder than what the athlete can work out. */
internal fun capFlash(flash: String?, working: String?): String? {
    val f = flash?.let { LogbookSummaries.difficultyOf(it) } ?: return flash
    val w = working?.let { LogbookSummaries.difficultyOf(it) } ?: return flash
    return if (f > w) working else flash
}
