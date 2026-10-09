package com.cruxcoach.android.ui.training.basics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.coach.GradeStepper
import com.cruxcoach.android.ui.training.coach.experienceLabel
import com.cruxcoach.android.ui.training.common.EquipmentEditor
import com.cruxcoach.android.ui.training.common.PLAUSIBLE_HEIGHT_CM
import com.cruxcoach.android.ui.training.common.PLAUSIBLE_WEIGHT_KG
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.android.ui.training.parseDecimal
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * The basics on the first visit to Training or Nutrition (owner 2026-10-09:
 * "the most important basics, so the whole thing works at all"): body data
 * for targets, energy and loads; climbing level, experience and equipment for
 * suggestions and start loads. Everything is optional and editable later;
 * the longer coach setup stays a step of Today's checklist.
 */

data class BasicsState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    /** Working grade from the board logbook, suggested for the climbing level. */
    val logbookGrade: String? = null,
    val logbookDifficulty: Double? = null,
    /** Saved or put off: the screen closes. */
    val finished: Boolean = false,
)

@HiltViewModel
class BasicsViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(BasicsState())
    val state: StateFlow<BasicsState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val logbook = runCatching { service.logbookSummary() }.getOrNull()?.takeIf { it.hasGrades }
            _state.value = BasicsState(false, service.repo.profile(), service.currentBodyweight(), service.heightCm(),
                logbookGrade = logbook?.workingGrade, logbookDifficulty = logbook?.workingDifficulty)
        }
    }

    /** Saves what was entered; values left empty stay as they were. */
    fun save(
        weightKg: Double?, heightCm: Double?, birthYear: Int?, sex: Sex?,
        grade: String?, experience: ExperienceBand?, equipment: Set<EquipmentV2>?,
    ) = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        val repo = service.repo
        val today = service.today()
        val now = System.currentTimeMillis()
        if (weightKg != null && weightKg != _state.value.weightKg) {
            repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.WEIGHT.key, weightKg, "kg", now))
        }
        if (heightCm != null && heightCm != _state.value.heightCm) {
            repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.HEIGHT.key, heightCm, "cm", now))
        }
        repo.updateProfile { p ->
            p.copy(
                birthYear = birthYear ?: p.birthYear,
                sex = sex ?: p.sex,
                equipment = equipment ?: p.equipment,
                equipmentConfigured = p.equipmentConfigured || equipment != null,
                basicsAsked = true,
                coach = p.coach.copy(
                    currentGrade = grade ?: p.coach.currentGrade,
                    experience = experience ?: p.coach.experience,
                    // The coach's age guard rails (youth, 40+) follow the birth year.
                    ageBand = birthYear?.let { ageBandFor(today.year - it) } ?: p.coach.ageBand,
                ),
            )
        }
        _state.update { it.copy(finished = true) }
    }

    /** "Später": asked once, never pushed again; the checklist and hints still offer every value. */
    fun skip() = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        service.repo.updateProfile { it.copy(basicsAsked = true) }
        _state.update { it.copy(finished = true) }
    }

    companion object {
        fun ageBandFor(age: Int): AgeBand = when {
            age < 16 -> AgeBand.UNDER_16
            age < 18 -> AgeBand.Y16_17
            age < 40 -> AgeBand.Y18_39
            age < 55 -> AgeBand.Y40_54
            else -> AgeBand.Y55_PLUS
        }
    }
}

/**
 * Opens the basics once, on the first visit to Training or Nutrition, while
 * something essential is missing. Collected by the navigation, so the
 * screens' own tests render without it.
 */
@HiltViewModel
class BasicsGateViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _open = Channel<Unit>(Channel.CONFLATED)
    val open: Flow<Unit> = _open.receiveAsFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val p = service.repo.profile()
            val missing = service.currentBodyweight() == null || service.heightCm() == null || p.birthYear == null ||
                !p.equipmentConfigured || p.coach.currentGrade == null
            if (!p.basicsAsked && missing) {
                // Marked before opening: backing out never brings it back on its own.
                service.repo.updateProfile { it.copy(basicsAsked = true) }
                _open.trySend(Unit)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BasicsScreen(onDone: () -> Unit, viewModel: BasicsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onDone() }
    if (state.loading) return
    val p = state.profile
    val units = p.units
    var page by rememberSaveable { mutableIntStateOf(0) }
    var weight by rememberSaveable { mutableStateOf(state.weightKg?.takeIf { !p.hideBodyNumbers }?.let { formatNumber(Units.massToDisplay(it, units)) } ?: "") }
    var height by rememberSaveable { mutableStateOf(state.heightCm?.let { formatNumber(Units.lengthToDisplay(it, units)) } ?: "") }
    var year by rememberSaveable { mutableStateOf(p.birthYear?.toString() ?: "") }
    var sex by rememberSaveable { mutableStateOf(p.sex) }
    // The board logbook's working grade is the suggestion when no grade was given yet.
    var grade by rememberSaveable { mutableStateOf(p.coach.currentGrade ?: state.logbookGrade) }
    var experience by rememberSaveable { mutableStateOf(p.coach.experience) }
    var equipment by rememberSaveable { mutableStateOf(p.equipment.takeIf { p.equipmentConfigured }?.map { it.name }?.toSet() ?: emptySet()) }

    val weightKg = parseDecimal(weight)?.let { Units.massFromDisplay(it, units) }?.takeIf { it in PLAUSIBLE_WEIGHT_KG }
    val heightCm = parseDecimal(height)?.let { Units.lengthFromDisplay(it, units) }?.takeIf { it in PLAUSIBLE_HEIGHT_CM }
    val thisYear = java.time.LocalDate.now().year
    val birthYear = year.trim().toIntOrNull()?.takeIf { it in (thisYear - 100)..(thisYear - 8) }

    fun finish() = viewModel.save(
        weightKg, heightCm, birthYear, sex, grade, experience,
        equipment.mapNotNull { n -> EquipmentV2.entries.firstOrNull { it.name == n } }.toSet().takeIf { it.isNotEmpty() }?.plus(com.cruxcoach.android.ui.training.common.ALWAYS_THERE),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trq_title)) },
                actions = {
                    TextButton(onClick = { viewModel.skip() }, modifier = Modifier.testTag("basics_later")) {
                        Text(stringResource(R.string.trq_later))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page == 1) {
                        TextButton(onClick = { page = 0 }, modifier = Modifier.testTag("basics_back")) { Text(stringResource(R.string.trq_back)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { if (page == 0) page = 1 else finish() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(if (page == 0) "basics_next" else "basics_done"),
                    ) { Text(stringResource(if (page == 0) R.string.trq_next else R.string.trq_done)) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .testTag("basics_page_$page"),
        ) {
            LinearProgressIndicator(progress = { (page + 1) / 2f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = CruxCoachDesign.colors.brandAccent)
            // For every value on both pages, so it stands on top, not under the last field.
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CruxCoachDesign.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("basics_private")) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = CruxCoachDesign.colors.brandAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.trq_private), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (page == 0) {
                Header(stringResource(R.string.trq_you_title), stringResource(R.string.trq_you_why))
                NumberField(weight, { weight = it }, stringResource(R.string.trq_weight), Units.massUnit(units), "basics_weight",
                    invalid = weight.isNotBlank() && weightKg == null)
                NumberField(height, { height = it }, stringResource(R.string.trq_height), Units.lengthUnit(units), "basics_height",
                    invalid = height.isNotBlank() && heightCm == null)
                NumberField(year, { v -> year = v.filter { it.isDigit() }.take(4) }, stringResource(R.string.trq_birth_year), null, "basics_birth_year",
                    invalid = year.isNotBlank() && birthYear == null, decimal = false)
                Label(stringResource(R.string.trq_sex))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Sex.FEMALE to R.string.tra_sex_female, Sex.MALE to R.string.tra_sex_male, Sex.OTHER to R.string.trq_sex_none)
                        .forEach { (s, label) ->
                            FilterChip(selected = sex == s, onClick = { sex = if (sex == s) null else s }, label = { Text(stringResource(label)) },
                                leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(sex == s),
                                modifier = Modifier.testTag("basics_sex_${s.name.lowercase()}"))
                        }
                }
                Spacer(Modifier.height(16.dp))
            } else {
                Header(stringResource(R.string.trq_climb_title), stringResource(R.string.trq_climb_why))
                Label(stringResource(R.string.trq_grade))
                GradeStepper(grade, startFrom = state.logbookDifficulty, tag = "basics_grade", onChange = { grade = it })
                if (state.logbookGrade != null && grade == state.logbookGrade && p.coach.currentGrade == null) {
                    Text(stringResource(R.string.trq_grade_from_logbook), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("basics_grade_logbook"))
                }
                Label(stringResource(R.string.trc_experience))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExperienceBand.entries.forEach { e ->
                        FilterChip(selected = experience == e, onClick = { experience = if (experience == e) null else e },
                            leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(experience == e),
                            label = { Text(experienceLabel(e)) }, modifier = Modifier.testTag("basics_experience_${e.name.lowercase()}"))
                    }
                }
                Spacer(Modifier.height(16.dp))
                EquipmentEditor(equipment.mapNotNull { n -> EquipmentV2.entries.firstOrNull { it.name == n } }.toSet()) { set ->
                    equipment = set.map { it.name }.toSet()
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Header(title: String, why: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
    Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, suffix: String?, tag: String, invalid: Boolean, decimal: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, isError = invalid,
        label = { Text(label) }, suffix = suffix?.let { s -> { Text(s) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(tag),
    )
}
