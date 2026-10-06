package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.catalogLanguage
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.WorkoutPlanner
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.Workout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddToWorkoutState(
    val def: ExerciseDefinition? = null,
    val openWorkout: Workout? = null,
    val routines: List<Routine> = emptyList(),
    val favorite: Boolean = false,
)

sealed interface AddToWorkoutEvent {
    data object TrainingStarted : AddToWorkoutEvent
    /** The open injury rules the exercise out. */
    data object Rejected : AddToWorkoutEvent
    data class AddedToRoutine(val name: String) : AddToWorkoutEvent
}

@HiltViewModel
class AddToWorkoutViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(AddToWorkoutState())
    val state: StateFlow<AddToWorkoutState> = _state.asStateFlow()
    private val _events = Channel<AddToWorkoutEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var slug: String? = null

    fun load(slug: String) {
        if (this.slug == slug) return
        this.slug = slug
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            combine(repo.observeOpenWorkout(), repo.observeRoutines(), repo.observeFavorites()) { open, routines, favs ->
                AddToWorkoutState(service.catalog[slug], open, routines, slug in favs)
            }.collect { _state.value = it }
        }
    }

    fun addToOpen() = io { slug ->
        val open = service.repo.openWorkout() ?: return@io
        if (service.addExercise(open.id, slug)) _events.send(AddToWorkoutEvent.TrainingStarted)
        else _events.send(AddToWorkoutEvent.Rejected)
    }

    fun startNew(title: String) = io { slug ->
        // Check first: a rejected exercise must not leave an empty training behind.
        val def = service.catalog[slug] ?: return@io
        if (com.cruxcoach.athlete.logic.InjuryAdvisor.assess(def, service.repo.activeInjuries()).verdict ==
            com.cruxcoach.athlete.logic.InjuryVerdict.AVOID) {
            _events.send(AddToWorkoutEvent.Rejected)
            return@io
        }
        val hadOpen = service.repo.openWorkout() != null
        val id = service.startWorkout(null, title)
        if (service.addExercise(id, slug)) _events.send(AddToWorkoutEvent.TrainingStarted)
        else {
            if (!hadOpen) service.discardWorkout(id)
            _events.send(AddToWorkoutEvent.Rejected)
        }
    }

    fun addToRoutine(routine: Routine) = io { slug ->
        val def = service.catalog[slug] ?: return@io
        service.repo.saveRoutine(routine.copy(items = routine.items + WorkoutPlanner.itemFor(def), updatedAt = System.currentTimeMillis()))
        _events.send(AddToWorkoutEvent.AddedToRoutine(routine.name))
    }

    fun toggleFavorite() = io { slug -> service.repo.setFavorite(slug, !_state.value.favorite) }

    private fun io(block: suspend (String) -> Unit) {
        val s = slug ?: return
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block(s) }
    }
}

/**
 * "Plan this exercise": into the running training, a new training, one of
 * the athlete's own workouts or a new workout — plus the favourite star.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToWorkoutSheet(
    slug: String,
    onDismiss: () -> Unit,
    onTrainingStarted: () -> Unit,
    onCreateRoutine: (from: String) -> Unit,
) {
    val viewModel: AddToWorkoutViewModel = hiltViewModel(key = "add-$slug")
    LaunchedEffect(slug) { viewModel.load(slug) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lang = catalogLanguage()
    var message by remember { mutableStateOf<String?>(null) }
    val rejected = stringResource(R.string.trwo_add_rejected)
    val addedTemplate = stringResource(R.string.trwo_add_added_to)
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                AddToWorkoutEvent.TrainingStarted -> onTrainingStarted()
                AddToWorkoutEvent.Rejected -> message = rejected
                is AddToWorkoutEvent.AddedToRoutine -> message = addedTemplate.format(e.name)
            }
        }
    }
    val name = state.def?.name(lang).orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("add_to_workout_sheet")) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = CruxCoachDesign.colors.positive,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("add_to_workout_message"))
            }
            ListItem(
                headlineContent = { Text(stringResource(if (state.favorite) R.string.trwo_fav_remove else R.string.trwo_fav_add)) },
                leadingContent = {
                    Icon(if (state.favorite) Icons.Default.Star else Icons.Default.StarBorder, null,
                        tint = if (state.favorite) CruxCoachDesign.colors.brandAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier.clickable { viewModel.toggleFavorite() }.testTag("add_to_workout_favorite"),
            )
            HorizontalDivider()
            if (state.openWorkout != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.trwo_add_to_open)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    modifier = Modifier.clickable { viewModel.addToOpen() }.testTag("add_to_workout_open"),
                )
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.trwo_add_start_new)) },
                    leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                    modifier = Modifier.clickable { viewModel.startNew(name) }.testTag("add_to_workout_start"),
                )
            }
            if (state.routines.isNotEmpty()) {
                Text(stringResource(R.string.trwo_add_to_routine), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(state.routines, key = { it.id }) { routine ->
                        ListItem(
                            headlineContent = { Text(routine.name) },
                            leadingContent = { Icon(Icons.Default.FitnessCenter, null) },
                            modifier = Modifier.clickable { viewModel.addToRoutine(routine) }
                                .testTag("add_to_workout_routine_${routine.id}"),
                        )
                    }
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.trwo_add_new_routine)) },
                leadingContent = { Icon(Icons.Default.Add, null) },
                modifier = Modifier.clickable { onCreateRoutine("ex:$slug") }.testTag("add_to_workout_new_routine"),
            )
        }
    }
}
