package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.Routine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WeekPlanState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val routines: List<Routine> = emptyList(),
    /** Standard week proposed from the coach answers and the logbook's rhythm (FEAT-071). */
    val proposal: Map<Int, String> = emptyMap(),
    /** The usual climbing days (ISO weekdays) the proposal is built around: the athlete's own, else the logbook's. */
    val climbingDays: Set<Int> = emptySet(),
    /** [climbingDays] are the logbook's: the athlete named none yet. */
    val climbingDaysFromLogbook: Boolean = false,
)

@HiltViewModel
class WeekPlanViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(WeekPlanState())
    val state: StateFlow<WeekPlanState> = _state.asStateFlow()

    /** The weekdays the board logbook shows as usual climbing days; they stand in while the athlete named none. */
    @Volatile private var logbookDays: Set<Int> = emptySet()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val logbook = runCatching { service.logbookSummary() }.getOrDefault(com.cruxcoach.athlete.logic.LogbookSummary())
            logbookDays = logbook.usualClimbingDays.filter { it in 1..7 }.toSet()
            combine(service.repo.observeProfile(), service.repo.observeRoutines(), service.repo.observeActiveInjuries()) { profile, routines, injuries ->
                val proposal = com.cruxcoach.athlete.logic.WeekPlanSuggester.suggest(profile.coach, logbook, routines, profile.equipment, injuries,
                    runCatching { service.recoveryTier(profile) }.getOrDefault(com.cruxcoach.athlete.logic.DayPlans.Tier.STANDARD))
                WeekPlanState(false, profile, routines, proposal)
            }.collect {
                _state.value = it.copy(climbingDays = usualDays(it.profile),
                    climbingDaysFromLogbook = it.profile.coach.climbingDays.none { d -> d in 1..7 } && logbookDays.isNotEmpty())
            }
        }
    }

    private fun usualDays(profile: AthleteProfile): Set<Int> = profile.coach.climbingDays.filter { it in 1..7 }.toSet().ifEmpty { logbookDays }

    /**
     * A usual climbing day, from the chips above the proposal (Nokia walk 2026-11-22: a new athlete's proposed week
     * had no climbing days, because only the coach setup asks for them). The same answer as on the setup's week card,
     * kept at once like every choice on this page; the proposal follows.
     */
    fun setClimbingDay(day: Int, climbs: Boolean) = io {
        service.repo.updateProfile { p ->
            val days = usualDays(p)
            p.copy(coach = p.coach.copy(climbingDays = if (climbs) days + day else days - day))
        }
    }

    /** [entry] null = free day. */
    fun set(day: Int, entry: String?) = io {
        service.repo.updateProfile { p ->
            val plan = p.weekPlan.toMutableMap()
            if (entry == null) plan.remove(day) else plan[day] = entry
            p.copy(weekPlan = plan)
        }
    }

    fun setMinutes(minutes: Int) = io { service.repo.updateProfile { it.copy(sessionMinutes = minutes) } }

    /** The week before "Übernehmen", for "Rückgängig" (audit P2-14). */
    private var previousPlan: Map<Int, String>? = null

    fun applyProposal() {
        val plan = _state.value.proposal
        if (plan.isEmpty()) return
        previousPlan = _state.value.profile.weekPlan
        io { service.repo.updateProfile { it.copy(weekPlan = plan) } }
    }

    fun undoProposal() {
        val previous = previousPlan ?: return
        previousPlan = null
        io { service.repo.updateProfile { it.copy(weekPlan = previous) } }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() }
    }
}

// The same steps as the coach setup asks (15 … 90 min).
/** The same choices as the time on Today's card (UX review 2026-10-16: the two lists differed). */
private val SESSION_MINUTES = com.cruxcoach.android.ui.training.today.TodayViewModel.DURATION_OPTIONS

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeekPlanScreen(onBack: () -> Unit, viewModel: WeekPlanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    val appliedText = stringResource(R.string.trwo_week_applied)
    val undoText = stringResource(R.string.trpg_undo)
    val apply = {
        viewModel.applyProposal()
        snackScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(appliedText, undoText, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) viewModel.undoProposal()
        }
        Unit
    }
    // What the standard week is for: the app bar's info icon, not a paragraph above the days (design.md).
    TrainingScaffold(title = stringResource(R.string.trwo_week_plan), onBack = onBack, snackbarHost = { TrainingSnackbarHost(snackbar) },
        actions = { com.cruxcoach.android.ui.common.InfoButton(stringResource(R.string.trwo_week_plan), stringResource(R.string.trwo_week_plan_intro)) }) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("week_plan_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.proposal.isNotEmpty()) {
                item(key = "proposal") { ProposalCard(state, onApply = apply, onClimbingDay = viewModel::setClimbingDay) }
            }
            items((1..7).toList(), key = { it }) { day ->
                val entry = state.profile.weekPlan[day]
                Card(
                    onClick = { choosing = day },
                    modifier = Modifier.fillMaxWidth().testTag("week_plan_day_$day"),
                ) {
                    // The day above its plan at a large font ("Donnerst/ag" in a fixed column).
                    DayAbovePlan(Modifier.padding(16.dp), dayWidth = 120.dp, day = {
                        Text(weekdayName(day), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = it)
                    }) {
                        Text(planEntryLabel(entry, state.routines), style = MaterialTheme.typography.bodyLarge,
                            color = if (entry == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = it)
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.trwo_session_minutes), keepBeside = true) {
                    com.cruxcoach.android.ui.common.InfoButton(stringResource(R.string.trwo_session_minutes), stringResource(R.string.trwo_session_minutes_hint))
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SESSION_MINUTES.forEach { m ->
                        FilterChip(
                            selected = state.profile.sessionMinutes == m,
                            onClick = { viewModel.setMinutes(m) },
                            label = { Text(stringResource(R.string.trwo_minutes, m)) },
                            modifier = Modifier.testTag("week_plan_minutes_$m"),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    choosing?.let { day ->
        PlanChooserDialog(
            title = weekdayName(day),
            current = state.profile.weekPlan[day],
            routines = state.routines,
            onDismiss = { choosing = null },
            onChoose = { entry -> viewModel.set(day, entry); choosing = null },
        )
    }
}

/** "Vorschlag aus deinem Rhythmus": the coach's standard week, previewed, applied only on a tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposalCard(state: WeekPlanState, onApply: () -> Unit, onClimbingDay: (Int, Boolean) -> Unit = { _, _ -> }) {
    // Without a standard week the proposal is what this page is opened for: it shows at once.
    var open by rememberSaveable { mutableStateOf(state.profile.weekPlan.isEmpty()) }
    val same = state.proposal == state.profile.weekPlan
    OutlinedCard(Modifier.fillMaxWidth().testTag("week_plan_proposal")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.trc_week_proposal_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (same) R.string.trc_week_proposal_same else R.string.trc_week_proposal_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The days the week is built around, right where it is proposed: one tap each, the proposal follows.
            Text(stringResource(R.string.trc_climbing_days), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { day ->
                    val selected = day in state.climbingDays
                    // A tap shows what it changes: the proposal opens.
                    FilterChip(selected = selected, onClick = { open = true; onClimbingDay(day, !selected) },
                        label = { Text(weekdayName(day, java.time.format.TextStyle.SHORT)) },
                        modifier = Modifier.testTag("week_plan_climbing_day_$day"))
                }
            }
            if (state.climbingDaysFromLogbook) {
                Text(stringResource(R.string.trc_prefill_logbook), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("week_plan_climbing_days_logbook"))
            }
            // A proposal that is already the week needs no second list above the days.
            if (open && !same) {
                Column(Modifier.padding(top = 8.dp)) {
                    (1..7).forEach { day ->
                        DayAbovePlan(Modifier.padding(vertical = 2.dp), day = {
                            Text(weekdayName(day), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = it)
                        }) {
                            Text(planEntryLabel(state.proposal[day], state.routines), style = MaterialTheme.typography.bodyMedium, modifier = it)
                        }
                    }
                }
            }
            if (!same) {
                // Wraps at the largest font instead of squeezing "Übern/ehme/n" into a round blob.
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { open = !open }, modifier = Modifier.testTag("week_plan_proposal_preview")) {
                        Text(stringResource(if (open) R.string.trc_week_proposal_hide else R.string.trc_week_proposal_show))
                    }
                    FilledTonalButton(onClick = onApply, modifier = Modifier.testTag("week_plan_proposal_apply")) {
                        Text(stringResource(R.string.trc_plan_apply))
                    }
                }
            }
        }
    }
}
