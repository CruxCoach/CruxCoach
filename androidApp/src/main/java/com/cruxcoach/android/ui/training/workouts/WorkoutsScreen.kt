package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
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
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.workout.estimatedMinutes
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import kotlinx.datetime.isoDayNumber
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class WorkoutsState(
    val loading: Boolean = true,
    val routines: List<Routine> = emptyList(),
    val profile: AthleteProfile = AthleteProfile(),
    val openWorkout: Workout? = null,
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val today: kotlinx.datetime.LocalDate? = null,
    /** Monday to Sunday of the current week: plan and status. */
    val week: List<WeekDayCell> = emptyList(),
    /** Re-entry ramp after a break or a healed finger injury. */
    val returnState: com.cruxcoach.athlete.logic.ReturnState? = null,
)

sealed interface WorkoutsEvent { data object Started : WorkoutsEvent }

@HiltViewModel
class WorkoutsViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(WorkoutsState())
    val state: StateFlow<WorkoutsState> = _state.asStateFlow()
    private val _events = Channel<WorkoutsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    /** Board sessions live in another database; a return to the tab recomputes the week. */
    private val weekTick = MutableStateFlow(0)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            combine(
                repo.observeRoutines(),
                repo.observeProfile(),
                repo.observeOpenWorkout(),
                service.catalogStore.catalog,
            ) { routines, profile, open, catalog -> Base(routines, profile, open, catalog) }
                .collect { b ->
                    // Keeps the week fields, which the second collector owns.
                    _state.update { it.copy(loading = false, routines = b.routines, profile = b.profile, openWorkout = b.open, catalog = b.catalog) }
                }
        }
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val from = service.today().minus(kotlinx.datetime.DatePeriod(days = ACTIVITY_DAYS)).toString()
            combine(
                repo.observeProfile(),
                repo.observeRecentWorkouts(30),
                repo.observeClimbingDaysSince(from),
                repo.observePauses(),
                weekTick,
            ) { profile, _, _, pauses, _ -> profile to pauses }
                .collect { (profile, pauses) -> runCatching { computeWeek(profile, pauses) } }
        }
    }

    private data class Base(val routines: List<Routine>, val profile: AthleteProfile, val open: Workout?, val catalog: ExerciseCatalog)

    fun refreshWeek() { weekTick.update { it + 1 } }

    /** Computes the week on the calling thread; also used by tests to get a deterministic state. */
    internal fun computeWeekNow() = computeWeek(service.repo.profile(), service.repo.pauses())

    private fun computeWeek(profile: AthleteProfile, pauses: List<PausePeriod>) {
        val repo = service.repo
        val today = service.today()
        val activities = service.activities(days = ACTIVITY_DAYS)
        val monday = today.minus(kotlinx.datetime.DatePeriod(days = today.dayOfWeek.isoDayNumber - 1))
        val sunday = monday.plus(kotlinx.datetime.DatePeriod(days = 6))
        val week = buildWeek(today, profile.weekPlan, activities, repo.workoutsBetween(monday.toString(), sunday.toString()), pauses)
        // The week shows even if the re-entry check below fails.
        _state.update { it.copy(today = today, week = week) }
        // Max-finger work before the break decides whether a long ramp's second week allows it again.
        val since = today.minus(kotlinx.datetime.DatePeriod(days = 120))
        val workoutDays = repo.workoutsBetween(since.toString(), today.toString()).associate { it.id to it.day }
        val sets = repo.completedSetsSince(System.currentTimeMillis() - 120L * 86_400_000L)
            .mapNotNull { s -> workoutDays[s.workoutId]?.let { d -> runCatching { kotlinx.datetime.LocalDate.parse(d) }.getOrNull() }?.let { it to s } }
        val maxBefore = com.cruxcoach.athlete.logic.ReturnToTraining.maxFingerBefore(sets, service.catalog, today)
        val ret = com.cruxcoach.athlete.logic.ReturnToTraining.state(activities, pauses, repo.allInjuries(), today, maxBefore)
        _state.update { it.copy(returnState = ret) }
    }

    fun start(routine: Routine, title: String?) = io {
        service.startWorkout(routine, title)
        _events.send(WorkoutsEvent.Started)
    }

    fun duplicate(routine: Routine, copySuffix: String) = io {
        val now = System.currentTimeMillis()
        service.repo.saveRoutine(routine.copy(id = service.repo.newId(), name = routine.name + copySuffix,
            createdAt = now, updatedAt = now, builtinKey = null))
    }

    fun delete(routine: Routine) = io { service.repo.deleteRoutine(routine.id) }

    private companion object {
        /** Enough history for a 21-day break plus a two-week ramp. */
        const val ACTIVITY_DAYS = 70
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() }
    }
}

/** Localized name of a week-plan entry. */
@Composable
internal fun planEntryLabel(entry: String?, routines: List<Routine>): String = when {
    entry == null -> stringResource(R.string.trwo_plan_free)
    entry == PLAN_REST -> stringResource(R.string.trwo_plan_rest)
    entry == PLAN_BOARD -> stringResource(R.string.trwo_plan_board)
    entry.startsWith("builtin:") -> BuiltinRoutines.byKey(entry.removePrefix("builtin:"))?.let { routineName(it) }
        ?: stringResource(R.string.trwo_plan_unknown)
    else -> routines.firstOrNull { it.id == entry }?.name ?: stringResource(R.string.trwo_plan_unknown)
}

internal fun weekdayName(isoDay: Int, style: TextStyle = TextStyle.FULL): String =
    DayOfWeek.of(isoDay).getDisplayName(style, Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }

@Composable
fun WorkoutsScreen(
    onBack: () -> Unit,
    onOpenEditor: (routineId: String?, from: String?) -> Unit,
    onOpenWeekPlan: () -> Unit,
    onOpenHistory: () -> Unit,
    onWorkoutStarted: () -> Unit,
    viewModel: WorkoutsViewModel = hiltViewModel(),
    tabBar: @Composable () -> Unit = {},
    /** Weekly volume per area (integrator slot), shown under the week view. */
    volumeCard: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lang = catalogLanguage()
    var deleting by remember { mutableStateOf<Routine?>(null) }
    var dayCell by remember { mutableStateOf<WeekDayCell?>(null) }
    var logClimbing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshWeek() }
    val copySuffix = stringResource(R.string.trwo_copy_suffix)
    LaunchedEffect(Unit) { viewModel.events.collect { if (it is WorkoutsEvent.Started) onWorkoutStarted() } }

    TrainingScaffold(
        title = stringResource(R.string.tr_tab_workouts),
        onBack = onBack,
        bottomBar = tabBar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenEditor(null, null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.trwo_new)) },
                modifier = Modifier.testTag("workouts_new"),
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("workouts_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.returnState?.let { r -> item(key = "return") { ReturnBanner(r) } }
            if (state.week.isNotEmpty()) {
                item(key = "week") { WeekCalendarCard(state.week, state.routines, onDayClick = { dayCell = it }) }
            }
            item(key = "volume") { volumeCard() }
            state.openWorkout?.let {
                item {
                    Card(
                        onClick = onWorkoutStarted,
                        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.brandAccent),
                        modifier = Modifier.fillMaxWidth().testTag("workouts_open"),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, null, tint = CruxCoachDesign.colors.onBrandAccent)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.trwo_continue), color = CruxCoachDesign.colors.onBrandAccent,
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.trwo_mine)) }
            if (state.routines.isEmpty()) {
                item { EmptyHint(stringResource(R.string.trwo_mine_empty)) }
            }
            items(state.routines, key = { it.id }) { routine ->
                RoutineCard(
                    routine = routine,
                    title = routine.name,
                    catalog = state.catalog,
                    lang = lang,
                    onStart = { viewModel.start(routine, null) },
                    onEdit = { onOpenEditor(routine.id, null) },
                    onDuplicate = { viewModel.duplicate(routine, copySuffix) },
                    onDelete = { deleting = routine },
                )
            }

            item { SectionTitle(stringResource(R.string.trwo_week_plan)) }
            item { WeekPlanCard(state.profile.weekPlan, state.routines, onOpenWeekPlan) }

            item { SectionTitle(stringResource(R.string.trwo_templates)) }
            items(BuiltinRoutines.all, key = { "b-" + it.builtinKey }) { routine ->
                val name = routineName(routine)
                RoutineCard(
                    routine = routine,
                    title = name,
                    subtitle = routineDescription(routine),
                    catalog = state.catalog,
                    lang = lang,
                    onStart = { viewModel.start(routine, name) },
                    onCopy = { onOpenEditor(null, "builtin:" + routine.builtinKey) },
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.trwo_history)) },
                    leadingContent = { Icon(Icons.Default.History, null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable(onClick = onOpenHistory).testTag("workouts_history"),
                )
            }
        }
    }

    dayCell?.let { cell ->
        val planned = cell.planned?.let { entry ->
            when {
                entry == PLAN_BOARD || entry == PLAN_REST -> null
                entry.startsWith("builtin:") -> BuiltinRoutines.byKey(entry.removePrefix("builtin:"))
                else -> state.routines.firstOrNull { it.id == entry }
            }
        }
        val plannedTitle = planned?.let { routineName(it) }
        WeekDaySheet(
            cell = cell,
            today = state.today ?: cell.date,
            routines = state.routines,
            plannedRoutine = planned,
            onDismiss = { dayCell = null },
            onStart = { r -> dayCell = null; viewModel.start(r, plannedTitle) },
            onAdapt = { r ->
                dayCell = null
                if (r.builtinKey != null && state.routines.none { it.id == r.id }) onOpenEditor(null, "builtin:" + r.builtinKey)
                else onOpenEditor(r.id, null)
            },
            onLogClimbing = { dayCell = null; logClimbing = true },
            onOpenHistory = { dayCell = null; onOpenHistory() },
        )
    }
    if (logClimbing) {
        com.cruxcoach.android.ui.training.today.ClimbingDaySheet(onDismiss = { logClimbing = false; viewModel.refreshWeek() })
    }

    deleting?.let { routine ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.trwo_delete_title)) },
            text = { Text(stringResource(R.string.trwo_delete_text, routine.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(routine); deleting = null }, modifier = Modifier.testTag("workouts_delete_confirm")) {
                    Text(stringResource(R.string.tr_action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

@Composable
private fun RoutineCard(
    routine: Routine,
    title: String,
    catalog: ExerciseCatalog,
    lang: String,
    onStart: () -> Unit,
    subtitle: String? = null,
    onEdit: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val tag = routine.builtinKey?.let { "builtin_$it" } ?: routine.id
    Card(Modifier.fillMaxWidth().testTag("routine_card_$tag")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val count = routine.items.size
                    Text(
                        pluralStringResource(R.plurals.trwo_exercise_count, count, count) + " · " +
                            stringResource(R.string.trwo_minutes, estimatedMinutes(routine)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onEdit != null || onDuplicate != null || onDelete != null) {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("routine_menu_$tag")) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trwo_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            onEdit?.let {
                                DropdownMenuItem(text = { Text(stringResource(R.string.tr_action_edit)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; it() })
                            }
                            onDuplicate?.let {
                                DropdownMenuItem(text = { Text(stringResource(R.string.trwo_duplicate)) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; it() })
                            }
                            onDelete?.let {
                                DropdownMenuItem(text = { Text(stringResource(R.string.tr_action_delete)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; it() })
                            }
                        }
                    }
                }
            }
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            val preview = routine.items.map { catalog.fallbackFor(it.slug).name(lang) }.distinct()
            if (preview.isNotEmpty()) {
                Text(preview.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, modifier = Modifier.testTag("routine_start_$tag")) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tr_action_start))
                }
                onEdit?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.tr_action_edit)) } }
                onCopy?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.testTag("routine_copy_$tag")) {
                        Text(stringResource(R.string.trwo_copy_adjust))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekPlanCard(plan: Map<Int, String>, routines: List<Routine>, onEdit: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("workouts_week_plan")) {
        Column(Modifier.padding(16.dp)) {
            if (plan.isEmpty()) {
                Text(stringResource(R.string.trwo_week_plan_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                (1..7).forEach { day ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(weekdayName(day, TextStyle.SHORT), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                        Text(planEntryLabel(plan[day], routines), style = MaterialTheme.typography.bodyMedium,
                            color = if (plan[day] == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            TextButton(onClick = onEdit, modifier = Modifier.testTag("workouts_week_plan_edit")) {
                Text(stringResource(R.string.trwo_week_plan_edit))
            }
        }
    }
}
