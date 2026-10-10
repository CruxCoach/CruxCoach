package com.cruxcoach.android.ui.training.athlete

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.PauseReason
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.android.ui.training.bodymap.BodyArea
import com.cruxcoach.android.ui.training.bodymap.BodyMapPicker
import com.cruxcoach.android.ui.training.bodymap.bodyAreaLabel
import com.cruxcoach.android.ui.training.bodymap.bodyAreas
import com.cruxcoach.android.ui.training.bodymap.toInjuryRegion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InjuriesState(
    val active: List<Injury> = emptyList(),
    val past: List<Injury> = emptyList(),
    val pauses: List<PausePeriod> = emptyList(),
)

@HiltViewModel
class InjuriesViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(InjuriesState())
    val state: StateFlow<InjuriesState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            combine(service.repo.observeActiveInjuries(), service.repo.observePauses()) { active, pauses ->
                InjuriesState(active, service.repo.allInjuries().filter { !it.isActive }, pauses)
            }.collect { _state.value = it }
        }
    }

    fun add(region: InjuryRegion, side: InjurySide?, detail: String, severity: Int, pauseClimbing: Boolean, pauseStreak: Boolean) = io {
        val repo = service.repo
        val today = service.today().toString()
        repo.saveInjury(Injury(repo.newId(), region, side, detail.trim().ifBlank { null }, severity, pauseClimbing, today,
            updatedAt = System.currentTimeMillis()))
        if (pauseStreak && repo.openPause() == null) repo.savePause(PausePeriod(repo.newId(), PauseReason.INJURY, today))
    }

    fun update(injury: Injury) = io { service.repo.saveInjury(injury.copy(updatedAt = System.currentTimeMillis())) }

    /** Healing an injury also ends an injury pause once nothing else is open. */
    fun resolve(injury: Injury) = io {
        val repo = service.repo
        val today = service.today().toString()
        repo.saveInjury(injury.copy(resolvedOn = today, updatedAt = System.currentTimeMillis()))
        if (repo.activeInjuries().isEmpty()) {
            repo.openPause()?.takeIf { it.reason == PauseReason.INJURY }?.let { repo.savePause(it.copy(endDay = today)) }
        }
    }

    fun delete(injury: Injury) = io { service.repo.deleteInjury(injury.id) }

    fun startHoliday() = io {
        val repo = service.repo
        if (repo.openPause() == null) repo.savePause(PausePeriod(repo.newId(), PauseReason.HOLIDAY, service.today().toString()))
    }

    fun endPause(p: PausePeriod) = io { service.repo.savePause(p.copy(endDay = service.today().toString())) }
    fun deletePause(p: PausePeriod) = io { service.repo.deletePause(p.id) }

    private fun io(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() } }
}

@Composable
fun InjuriesScreen(onBack: () -> Unit, onOpenRoutines: () -> Unit, viewModel: InjuriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    TrainingScaffold(
        title = stringResource(R.string.tra_injuries_title),
        onBack = onBack,
        actions = { InfoButton(stringResource(R.string.tra_injuries_info_title), stringResource(R.string.tra_injuries_info_text)) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.tra_injury_add)) },
                containerColor = com.cruxcoach.android.ui.theme.CruxCoachDesign.colors.brandAccent,
                contentColor = com.cruxcoach.android.ui.theme.CruxCoachDesign.colors.onBrandAccent,
                modifier = Modifier.testTag("injury_add"))
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.tr_not_medical_advice), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.active.isEmpty()) item { EmptyHint(stringResource(R.string.tra_injuries_none)) }
            items(state.active, key = { it.id }) { injury ->
                InjuryCard(injury, onUpdate = viewModel::update, onResolve = { viewModel.resolve(injury) },
                    onDelete = { viewModel.delete(injury) })
            }
            if (state.active.isNotEmpty()) {
                item {
                    OutlinedButton(onClick = onOpenRoutines, modifier = Modifier.fillMaxWidth().testTag("injury_routines")) {
                        Text(stringResource(R.string.tra_injury_train_around))
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.tra_pauses_title)) }
            val open = state.pauses.firstOrNull { it.endDay == null }
            item {
                if (open == null) {
                    OutlinedButton(onClick = viewModel::startHoliday, modifier = Modifier.testTag("pause_holiday")) {
                        Text(stringResource(R.string.tra_pause_holiday))
                    }
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.tra_pause_open, pauseReasonLabel(open.reason), open.startDay),
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.endPause(open) }) { Text(stringResource(R.string.trt_pause_end)) }
                        }
                    }
                }
            }
            items(state.pauses.filter { it.endDay != null }.take(10), key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(pauseReasonLabel(p.reason)) },
                    supportingContent = { Text("${p.startDay} – ${p.endDay}") },
                    trailingContent = { TextButton(onClick = { viewModel.deletePause(p) }) { Text(stringResource(R.string.tr_action_delete)) } },
                )
            }
            if (state.past.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.tra_injuries_past)) }
                items(state.past.take(20), key = { "past-" + it.id }) { i ->
                    ListItem(
                        headlineContent = { Text(listOfNotNull(regionLabel(i.region), i.side?.let { injurySideLabel(it) }, i.detail).joinToString(" · ")) },
                        supportingContent = { Text("${i.startedOn} – ${i.resolvedOn}") },
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (adding) {
        AddInjuryDialog(onDismiss = { adding = false }) { region, side, detail, severity, pauseClimbing, pauseStreak ->
            viewModel.add(region, side, detail, severity, pauseClimbing, pauseStreak); adding = false
        }
    }
}

@Composable
private fun pauseReasonLabel(r: PauseReason): String = stringResource(when (r) {
    PauseReason.ILLNESS -> R.string.tra_pause_illness
    PauseReason.INJURY -> R.string.tra_pause_injury
    PauseReason.HOLIDAY -> R.string.tra_pause_holiday_label
})

@Composable
private fun InjuryCard(injury: Injury, onUpdate: (Injury) -> Unit, onResolve: () -> Unit, onDelete: () -> Unit) {
    var severity by remember(injury.id, injury.severity) { mutableFloatStateOf(injury.severity.toFloat()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.cautionContainer,
            contentColor = CruxCoachDesign.colors.onCautionContainer),
        modifier = Modifier.fillMaxWidth().testTag("injury_${injury.id}"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Healing, null)
                Spacer(Modifier.width(8.dp))
                Text(listOfNotNull(regionLabel(injury.region), injury.side?.let { injurySideLabel(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            injury.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.tra_injury_since, injury.startedOn), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.tra_injury_pain, severity.toInt()), modifier = Modifier.padding(top = 8.dp))
            Slider(value = severity, onValueChange = { severity = it }, valueRange = 0f..9f, steps = 8,
                onValueChangeFinished = { onUpdate(injury.copy(severity = severity.toInt())) },
                modifier = Modifier.testTag("injury_pain_${injury.id}"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tra_injury_pause_climbing), modifier = Modifier.weight(1f))
                Switch(checked = injury.climbingPaused, onCheckedChange = { onUpdate(injury.copy(climbingPaused = it)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResolve, modifier = Modifier.testTag("injury_resolve_${injury.id}")) {
                    Text(stringResource(R.string.tra_injury_resolve))
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.tr_action_delete)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddInjuryDialog(
    onDismiss: () -> Unit,
    onSave: (InjuryRegion, InjurySide?, String, Int, Boolean, Boolean) -> Unit,
) {
    var region by rememberSaveable { mutableStateOf(InjuryRegion.FINGER) }
    var side by rememberSaveable { mutableStateOf<InjurySide?>(null) }
    var detail by rememberSaveable { mutableStateOf("") }
    var severity by rememberSaveable { mutableFloatStateOf(3f) }
    var pauseClimbing by rememberSaveable { mutableStateOf(true) }
    var pauseStreak by rememberSaveable { mutableStateOf(true) }
    var area by rememberSaveable { mutableStateOf<BodyArea?>(BodyArea.FINGERS) }
    val areaLabels = BodyArea.entries.associateWith { bodyAreaLabel(it) }
    fun chooseRegion(r: InjuryRegion) {
        region = r
        pauseClimbing = r in setOf(InjuryRegion.FINGER, InjuryRegion.WRIST, InjuryRegion.ELBOW, InjuryRegion.SHOULDER, InjuryRegion.SKIN)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tra_injury_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.tra_injury_where), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.trb_injury_where), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Tap the body: the area picks the region, a paired area also the side, and a finer
                // area than the region (forearm → elbow, hamstring → knee) goes into the detail field.
                BodyMapPicker(
                    selected = area,
                    onSelect = { a ->
                        area = a
                        a.toInjuryRegion()?.let { r ->
                            chooseRegion(r)
                            if (detail.isBlank() && a !in r.bodyAreas()) detail = areaLabels.getValue(a)
                        }
                    },
                    onSide = { tapped ->
                        if (region.limb && tapped != null) side = if (tapped == Side.LEFT) InjurySide.LEFT else InjurySide.RIGHT
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("injury_body_map"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InjuryRegion.entries.forEach { r ->
                        FilterChip(selected = region == r, onClick = {
                            chooseRegion(r)
                            area = r.bodyAreas().firstOrNull()
                        }, label = { Text(regionLabel(r)) }, modifier = Modifier.testTag("injury_region_${r.name.lowercase()}"))
                    }
                }
                Text(stringResource(R.string.tra_injury_side), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InjurySide.entries.forEach { s ->
                        FilterChip(selected = side == s, onClick = { side = if (side == s) null else s },
                            label = { Text(injurySideLabel(s)) }, modifier = Modifier.testTag("injury_side_${s.name.lowercase()}"))
                    }
                }
                OutlinedTextField(value = detail, onValueChange = { detail = it.take(120) },
                    label = { Text(stringResource(R.string.tra_injury_detail)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(stringResource(R.string.tra_injury_pain, severity.toInt()), modifier = Modifier.padding(top = 8.dp))
                Slider(value = severity, onValueChange = { severity = it }, valueRange = 0f..9f, steps = 8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tra_injury_pause_climbing), modifier = Modifier.weight(1f))
                    Switch(checked = pauseClimbing, onCheckedChange = { pauseClimbing = it }, modifier = Modifier.testTag("injury_pause_climbing"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tra_injury_pause_streak), modifier = Modifier.weight(1f))
                    Switch(checked = pauseStreak, onCheckedChange = { pauseStreak = it })
                }
                Text(stringResource(R.string.tra_red_flags), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(region, side, detail, severity.toInt(), pauseClimbing, pauseStreak) },
                modifier = Modifier.testTag("injury_save")) { Text(stringResource(R.string.tr_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}
