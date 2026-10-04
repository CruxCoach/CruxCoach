package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
)

@HiltViewModel
class WeekPlanViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(WeekPlanState())
    val state: StateFlow<WeekPlanState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            combine(service.repo.observeProfile(), service.repo.observeRoutines()) { profile, routines ->
                WeekPlanState(false, profile, routines)
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

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() }
    }
}

private val SESSION_MINUTES = listOf(20, 30, 45, 60, 90)

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
