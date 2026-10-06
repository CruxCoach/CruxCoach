package com.cruxcoach.android.ui.training.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.training.EmptyHint
import com.cruxcoach.android.ui.training.TrainingScaffold
import com.cruxcoach.android.ui.training.body.shortLabel
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ClimbingDaySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import javax.inject.Inject

data class ClimbingDayFormState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    val day: LocalDate? = null,
    val kind: ClimbingDayKind = ClimbingDayKind.GYM_BOULDER,
    val minutes: Int = 90,
    val intensity: ClimbIntensity = ClimbIntensity.VOLUME,
    val note: String = "",
    val existing: ClimbingDayEntry? = null,
)

@HiltViewModel
class ClimbingDayViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(ClimbingDayFormState())
    val state: StateFlow<ClimbingDayFormState> = _state.asStateFlow()
    private val _done = Channel<Unit>(Channel.CONFLATED)
    val done = _done.receiveAsFlow()
    private var loadedFor: String? = "\u0000"

    fun load(editId: String?) {
        if (loadedFor == editId) return
        loadedFor = editId
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val today = service.today()
            val existing = editId?.let { service.repo.climbingDay(it) }
            _state.value = if (existing != null) ClimbingDayFormState(
                loading = false, today = today, day = runCatching { LocalDate.parse(existing.day) }.getOrDefault(today),
                kind = existing.kind, minutes = existing.minutes, intensity = existing.intensity,
                note = existing.note.orEmpty(), existing = existing,
            ) else ClimbingDayFormState(loading = false, today = today, day = today)
        }
    }

    fun setDay(d: LocalDate) = _state.update { it.copy(day = d) }
    fun setKind(k: ClimbingDayKind) = _state.update { it.copy(kind = k) }
    fun setMinutes(m: Int) = _state.update { it.copy(minutes = m.coerceIn(5, 600)) }
    fun setIntensity(i: ClimbIntensity) = _state.update { it.copy(intensity = i) }
    fun setNote(n: String) = _state.update { it.copy(note = n.take(300)) }

    fun save() {
        val s = _state.value
        val day = s.day ?: return
        viewModelScope.launch(Dispatchers.IO) {
            service.saveClimbingDay(
                ClimbingDayEntry(
                    id = s.existing?.id ?: service.repo.newId(),
                    day = day.toString(),
                    kind = s.kind,
                    minutes = s.minutes,
                    intensity = s.intensity,
                    source = s.existing?.source ?: ClimbingDaySource.MANUAL,
                    externalId = s.existing?.externalId,
                    note = s.note.trim().ifEmpty { null },
                ),
            )
            _done.send(Unit)
        }
    }

    fun delete() {
        val id = _state.value.existing?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            service.deleteClimbingDay(id)
            _done.send(Unit)
        }
    }
}

@Composable
fun climbingDayKindLabel(k: ClimbingDayKind): String = stringResource(when (k) {
    ClimbingDayKind.GYM_BOULDER -> R.string.trl_kind_gym_boulder
    ClimbingDayKind.GYM_ROPE -> R.string.trl_kind_gym_rope
    ClimbingDayKind.OUTDOOR -> R.string.trl_kind_outdoor
    ClimbingDayKind.OTHER_BOARD -> R.string.trl_kind_other_board
    ClimbingDayKind.OTHER -> R.string.trl_kind_other
})

@Composable
fun climbIntensityLabel(i: ClimbIntensity): String = stringResource(when (i) {
    ClimbIntensity.LIGHT -> R.string.trl_intensity_light
    ClimbIntensity.VOLUME -> R.string.trl_intensity_volume
    ClimbIntensity.HARD -> R.string.trl_intensity_hard
    ClimbIntensity.LIMIT -> R.string.trl_intensity_limit
})

@Composable
fun climbIntensityHint(i: ClimbIntensity): String = stringResource(when (i) {
    ClimbIntensity.LIGHT -> R.string.trl_intensity_light_hint
    ClimbIntensity.VOLUME -> R.string.trl_intensity_volume_hint
    ClimbIntensity.HARD -> R.string.trl_intensity_hard_hint
    ClimbIntensity.LIMIT -> R.string.trl_intensity_limit_hint
})

/**
 * Logs climbing outside the board app — gym, rock, another board — in a few
 * taps, so finger-load rules, streak, stats and fueling know about it.
 * [editId] edits an existing entry (manual or from Health Connect).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClimbingDaySheet(onDismiss: () -> Unit, editId: String? = null) {
    val viewModel: ClimbingDayViewModel = hiltViewModel(key = "climbing-day-${editId ?: "new"}")
    LaunchedEffect(editId) { viewModel.load(editId) }
    LaunchedEffect(Unit) { viewModel.done.collect { onDismiss() } }
    val s by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("climbing_day_sheet")) {
        val today = s.today
        val day = s.day
        if (s.loading || today == null || day == null) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (s.existing != null) R.string.trl_day_edit_title else R.string.trl_day_title),
                style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.trl_day_intro), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Day
            val yesterday = today.minus(DatePeriod(days = 1))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = day == today, onClick = { viewModel.setDay(today) },
                    label = { Text(stringResource(R.string.trl_day_today)) }, modifier = Modifier.testTag("climbing_day_today"))
                FilterChip(selected = day == yesterday, onClick = { viewModel.setDay(yesterday) },
                    label = { Text(stringResource(R.string.trl_day_yesterday)) }, modifier = Modifier.testTag("climbing_day_yesterday"))
                FilterChip(selected = day != today && day != yesterday, onClick = { picking = true },
                    label = { Text(if (day != today && day != yesterday) day.shortLabel() else stringResource(R.string.trl_day_pick)) },
                    modifier = Modifier.testTag("climbing_day_pick"))
            }

            // Kind
            Text(stringResource(R.string.trl_day_kind), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClimbingDayKind.entries.forEach { k ->
                    FilterChip(selected = s.kind == k, onClick = { viewModel.setKind(k) }, label = { Text(climbingDayKindLabel(k)) },
                        modifier = Modifier.testTag("climbing_day_kind_${k.name.lowercase()}"))
                }
            }

            // Minutes
            Text(stringResource(R.string.trl_day_minutes), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { viewModel.setMinutes(s.minutes - 15) }, modifier = Modifier.testTag("climbing_day_minus")) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.trl_day_minutes_less))
                }
                Text(stringResource(R.string.trl_day_minutes_value, s.minutes), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp).testTag("climbing_day_minutes"))
                FilledTonalIconButton(onClick = { viewModel.setMinutes(s.minutes + 15) }, modifier = Modifier.testTag("climbing_day_plus")) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.trl_day_minutes_more))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(45, 60, 90, 120, 180).forEach { m ->
                    FilterChip(selected = s.minutes == m, onClick = { viewModel.setMinutes(m) },
                        label = { Text(stringResource(R.string.trl_day_minutes_value, m)) })
                }
            }

            // Intensity
            Text(stringResource(R.string.trl_day_intensity), style = MaterialTheme.typography.labelLarge)
            Column {
                ClimbIntensity.entries.forEach { i ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = s.intensity == i, role = Role.RadioButton, onClick = { viewModel.setIntensity(i) })
                            .padding(vertical = 6.dp)
                            .testTag("climbing_day_intensity_${i.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.intensity == i, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(climbIntensityLabel(i), style = MaterialTheme.typography.bodyLarge)
                            Text(climbIntensityHint(i), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = s.note, onValueChange = viewModel::setNote,
                label = { Text(stringResource(R.string.trl_day_note)) },
                modifier = Modifier.fillMaxWidth().testTag("climbing_day_note"),
            )
            if (s.existing?.source == ClimbingDaySource.HEALTH_CONNECT) {
                Text(stringResource(R.string.trl_day_from_health), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.existing != null) {
                    OutlinedButton(onClick = viewModel::delete, modifier = Modifier.testTag("climbing_day_delete")) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
                Button(onClick = viewModel::save, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("climbing_day_save")) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
    if (picking && s.today != null && s.day != null) {
        PastDayPicker(s.day!!, s.today!!, onPick = { viewModel.setDay(it); picking = false }, onDismiss = { picking = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastDayPicker(day: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    fun LocalDate.utcMillis(): Long = java.time.LocalDate.parse(toString()).toEpochDay() * 86_400_000L
    val todayMillis = today.utcMillis()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = day.utcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
            override fun isSelectableYear(year: Int) = year <= java.time.LocalDate.now().year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = pickerState.selectedDateMillis?.let { ms ->
                    LocalDate.parse(java.time.LocalDate.ofEpochDay(ms / 86_400_000L).toString())
                } ?: day
                onPick(picked)
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = pickerState)
    }
}

// ── List of climbing days ────────────────────────────────────────────

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ClimbingDaysViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    val days: StateFlow<List<ClimbingDayEntry>?> = flow {
        service.ensureReady()
        emit(service.today().minus(DatePeriod(days = 365)).toString())
    }.flatMapLatest { from -> service.repo.observeClimbingDaysSince(from) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Climbing logged outside the board app (by hand or from Health Connect), with edit and delete. */
@Composable
fun ClimbingDaysScreen(onBack: () -> Unit, viewModel: ClimbingDaysViewModel = hiltViewModel()) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    TrainingScaffold(
        title = stringResource(R.string.trl_days_title),
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.trl_action_log_climbing)) },
                modifier = Modifier.testTag("climbing_days_add"),
            )
        },
    ) { padding ->
        val list = days
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyHint(stringResource(R.string.trl_days_empty), Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("climbing_days_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { d ->
                    Card(Modifier.fillMaxWidth().clickable { editing = d.id }.testTag("climbing_day_row_${d.id}")) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terrain, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(climbingDayKindLabel(d.kind), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOf(
                                        runCatching { LocalDate.parse(d.day).shortLabel() }.getOrDefault(d.day),
                                        stringResource(R.string.trl_day_minutes_value, d.minutes),
                                        climbIntensityLabel(d.intensity),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                d.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (d.source == ClimbingDaySource.HEALTH_CONNECT) {
                                AssistChip(onClick = { editing = d.id }, label = { Text(stringResource(R.string.trl_days_source_health)) })
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { id -> ClimbingDaySheet(onDismiss = { editing = null }, editId = id) }
    if (adding) ClimbingDaySheet(onDismiss = { adding = false })
}
