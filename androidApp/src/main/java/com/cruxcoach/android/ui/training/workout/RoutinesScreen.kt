package com.cruxcoach.android.ui.training.workout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class RoutinesState(
    val loading: Boolean = true,
    val own: List<Routine> = emptyList(),
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val workoutOpen: Boolean = false,
)

@HiltViewModel
class RoutinesViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(RoutinesState())
    val state: StateFlow<RoutinesState> = _state.asStateFlow()

    private val _started = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val started: SharedFlow<Unit> = _started.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            combine(repo.observeRoutines(), repo.observeOpenWorkout(), service.catalogStore.catalog) { routines, open, catalog ->
                RoutinesState(loading = false, own = routines, catalog = catalog, workoutOpen = open != null)
            }.collect { s -> _state.update { s } }
        }
    }

    /** Starts the routine; with a training already open the athlete simply returns to it. */
    fun start(routine: Routine, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            service.startWorkout(routine, title)
            _started.emit(Unit)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); service.repo.deleteRoutine(id) }
    }
}

@Composable
fun RoutinesScreen(
    onBack: () -> Unit,
    onWorkoutStarted: () -> Unit,
    viewModel: RoutinesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = catalogLanguage()
    LaunchedEffect(Unit) { viewModel.started.collect { onWorkoutStarted() } }
    var preview by remember { mutableStateOf<Routine?>(null) }
    var deleting by remember { mutableStateOf<Routine?>(null) }

    TrainingScaffold(title = stringResource(R.string.trw_routines_title), onBack = onBack) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("routines_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle(stringResource(R.string.trw_builtin_section)) }
            items(BuiltinRoutines.all, key = { it.id }) { routine ->
                RoutineCard(routine, state.catalog, language, onClick = { preview = routine }, onDelete = null)
            }
            item { SectionTitle(stringResource(R.string.trw_own_section)) }
            if (state.own.isEmpty()) item { EmptyHint(stringResource(R.string.trw_own_empty)) }
            items(state.own, key = { it.id }) { routine ->
                RoutineCard(routine, state.catalog, language, onClick = { preview = routine }, onDelete = { deleting = routine })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    preview?.let { routine ->
        val name = routineName(routine)
        AlertDialog(
            onDismissRequest = { preview = null },
            modifier = Modifier.testTag("routine_preview"),
            title = { Text(name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    routineDescription(routine)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(8.dp))
                    routine.items.forEach { item ->
                        val def = state.catalog.fallbackFor(item.slug)
                        Text("• " + def.name(language) + " — " + itemSummary(item), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.workoutOpen) {
                        Text(stringResource(R.string.trw_open_running), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        preview = null
                        if (state.workoutOpen) onWorkoutStarted() else viewModel.start(routine, name)
                    },
                    modifier = Modifier.testTag("routine_start"),
                ) {
                    Text(stringResource(if (state.workoutOpen) R.string.trw_go_to_workout else R.string.tr_action_start))
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }

    deleting?.let { routine ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.trw_delete_routine_title)) },
            text = { Text(stringResource(R.string.trw_delete_routine_text, routine.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; viewModel.delete(routine.id) }) { Text(stringResource(R.string.tr_action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

@Composable
private fun RoutineCard(
    routine: Routine,
    catalog: ExerciseCatalog,
    language: String,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("routine_${routine.builtinKey ?: routine.id}")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(routineName(routine), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                routineDescription(routine)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val count = routine.items.size
                Text(
                    pluralStringResource(R.plurals.trw_exercise_count, count, count) + " · " +
                        stringResource(R.string.trw_est_minutes, estimatedMinutes(routine)),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    routine.items.take(4).joinToString(", ") { catalog.fallbackFor(it.slug).name(language) } +
                        if (routine.items.size > 4) " …" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.testTag("routine_delete_${routine.id}")) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.tr_action_delete))
                }
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
            }
        }
    }
}

/** Rough duration: work per set plus the rest after it. */
internal fun estimatedMinutes(routine: Routine): Int {
    val seconds = routine.items.sumOf { item ->
        val work = when {
            item.workS != null -> (item.workS!! + (item.restBetweenS ?: 0)) * (item.repsPerSet ?: 1)
            item.durationS != null -> item.durationS!!
            else -> (item.repsMax ?: item.repsMin ?: 8) * 3
        }
        item.sets.coerceAtLeast(1) * (work + (item.restS ?: 60))
    }
    return (seconds / 60.0).roundToInt().coerceAtLeast(1)
}

@Composable
private fun itemSummary(item: RoutineItem): String {
    val reps = when {
        item.repsMin != null && item.repsMax != null && item.repsMin != item.repsMax -> "${item.repsMin}–${item.repsMax}"
        else -> (item.repsMax ?: item.repsMin)?.toString()
    }
    return when {
        item.workS != null -> stringResource(R.string.trw_item_interval, item.sets, item.workS!!, item.restBetweenS ?: 0, item.repsPerSet ?: 1)
        item.durationS != null -> stringResource(R.string.trw_item_seconds, item.sets, item.durationS!!)
        reps != null -> stringResource(R.string.trw_item_reps, item.sets, reps)
        else -> pluralStringResource(R.plurals.trw_sets_count, item.sets, item.sets)
    }
}
