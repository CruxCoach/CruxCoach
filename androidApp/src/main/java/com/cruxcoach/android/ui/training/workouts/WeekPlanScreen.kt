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
)

@HiltViewModel
class WeekPlanViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(WeekPlanState())
    val state: StateFlow<WeekPlanState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val logbook = runCatching { service.logbookSummary() }.getOrDefault(com.cruxcoach.athlete.logic.LogbookSummary())
            combine(service.repo.observeProfile(), service.repo.observeRoutines(), service.repo.observeActiveInjuries()) { profile, routines, injuries ->
                val proposal = com.cruxcoach.athlete.logic.WeekPlanSuggester.suggest(profile.coach, logbook, routines, profile.equipment, injuries)
                WeekPlanState(false, profile, routines, proposal)
            }.collect { _state.value = it }
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

    fun applyProposal() {
        val plan = _state.value.proposal
        if (plan.isNotEmpty()) io { service.repo.updateProfile { it.copy(weekPlan = plan) } }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() }
    }
}

// The same steps as the coach setup asks (15 … 90 min).
private val SESSION_MINUTES = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeekPlanScreen(onBack: () -> Unit, viewModel: WeekPlanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<Int?>(null) }
    TrainingScaffold(title = stringResource(R.string.trwo_week_plan), onBack = onBack) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("week_plan_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(stringResource(R.string.trwo_week_plan_intro), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.proposal.isNotEmpty()) {
                item(key = "proposal") { ProposalCard(state, onApply = viewModel::applyProposal) }
            }
            items((1..7).toList(), key = { it }) { day ->
                val entry = state.profile.weekPlan[day]
                Card(
                    onClick = { choosing = day },
                    modifier = Modifier.fillMaxWidth().testTag("week_plan_day_$day"),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(weekdayName(day), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(120.dp))
                        Text(planEntryLabel(entry, state.routines), style = MaterialTheme.typography.bodyLarge,
                            color = if (entry == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.trwo_session_minutes)) }
            item {
                Text(stringResource(R.string.trwo_session_minutes_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            day = day,
            current = state.profile.weekPlan[day],
            routines = state.routines,
            onDismiss = { choosing = null },
            onChoose = { entry -> viewModel.set(day, entry); choosing = null },
        )
    }
}

/** "Vorschlag aus deinem Rhythmus": the coach's standard week, previewed, applied only on a tap. */
@Composable
private fun ProposalCard(state: WeekPlanState, onApply: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val same = state.proposal == state.profile.weekPlan
    OutlinedCard(Modifier.fillMaxWidth().testTag("week_plan_proposal")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.trc_week_proposal_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (same) R.string.trc_week_proposal_same else R.string.trc_week_proposal_text),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (open) {
                Column(Modifier.padding(top = 8.dp)) {
                    (1..7).forEach { day ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(weekdayName(day), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(112.dp))
                            Text(planEntryLabel(state.proposal[day], state.routines), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (!same) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun PlanChooserDialog(
    day: Int,
    current: String?,
    routines: List<Routine>,
    onDismiss: () -> Unit,
    onChoose: (String?) -> Unit,
) {
    // null = free day; the other options are stored as their plan entry.
    val options: List<String?> = listOf<String?>(null, PLAN_REST, PLAN_BOARD) +
        routines.map { it.id } + BuiltinRoutines.all.map { "builtin:" + it.builtinKey }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(weekdayName(day)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp).testTag("week_plan_chooser")) {
                items(options, key = { it ?: "free" }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onChoose(entry) }.padding(vertical = 12.dp)
                            .testTag("week_plan_option_${entry ?: "free"}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(planEntryLabel(entry, routines), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (entry == current) Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}
