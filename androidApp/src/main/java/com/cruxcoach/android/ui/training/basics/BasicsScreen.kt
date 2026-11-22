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
 * The start on the first visit to Training (owner 2026-11-21: "onboarding focused on what matters most"): four
 * answers on one page, each one tap, and the first training is there. Climbing level and years set how hard the
 * exercises are and keep finger load careful; the age band carries the rules for growing and for older climbers;
 * the place and its equipment decide what can be suggested at all. Body data is not asked here any more: nutrition
 * asks for it when it is opened, a load asks for the body weight when it needs it. Everything is optional and
 * editable later ("Coach anpassen").
 */

data class BasicsState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
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
            _state.value = BasicsState(false, service.repo.profile(),
                logbookGrade = logbook?.workingGrade, logbookDifficulty = logbook?.workingDifficulty)
        }
    }

    /** Saves the four answers; what was left open stays as it was. */
    fun save(grade: String?, experience: ExperienceBand?, ageBand: AgeBand?, equipment: Set<EquipmentV2>?) = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        val today = service.today()
        service.repo.updateProfile { p ->
            // The equipment asked here is the default place's; a first place is where the athlete said they train.
            val placed = equipment?.let {
                com.cruxcoach.athlete.logic.TrainingPlaces.withEquipment(p, com.cruxcoach.athlete.logic.TrainingPlaces.default(p)?.id, it, today.toString(),
                    com.cruxcoach.android.ui.training.common.placeKindOf(it))
            } ?: p
            placed.copy(
                basicsAsked = true,
                coach = p.coach.copy(
                    currentGrade = grade ?: p.coach.currentGrade,
                    experience = experience ?: p.coach.experience,
                    // The coach's guard rails for growing climbers and from 40 on read the age band.
                    ageBand = ageBand ?: p.coach.ageBand,
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
 * Opens the start once, on the first visit to Training, while one of its
 * four answers is missing. Collected by the navigation, so the
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
            val missing = !p.equipmentConfigured || p.coach.currentGrade == null || p.coach.experience == null || p.coach.ageBand == null
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
    // The board logbook's working grade is the suggestion when no grade was given yet.
    var grade by rememberSaveable { mutableStateOf(p.coach.currentGrade ?: state.logbookGrade) }
    var experience by rememberSaveable { mutableStateOf(p.coach.experience) }
    // A birth year from before stands for the age band.
    var age by rememberSaveable {
        mutableStateOf(p.coach.ageBand ?: p.birthYear?.let { BasicsViewModel.ageBandFor(java.time.LocalDate.now().year - it) })
    }
    var equipment by rememberSaveable {
        mutableStateOf(com.cruxcoach.athlete.logic.TrainingPlaces.default(p)?.equipment?.map { it.name }?.toSet() ?: emptySet())
    }
    val chosen = equipment.mapNotNull { n -> EquipmentV2.entries.firstOrNull { it.name == n } }.toSet()

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
                // One job, one main button.
                Button(
                    onClick = {
                        viewModel.save(grade, experience, age,
                            chosen.takeIf { it.isNotEmpty() }?.plus(com.cruxcoach.android.ui.training.common.ALWAYS_THERE))
                    },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                        .heightIn(min = 52.dp).testTag("basics_done"),
                ) { Text(stringResource(R.string.trq_first_training)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .testTag("basics_page"),
        ) {
            Text(stringResource(R.string.trq_intro), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

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

            Label(stringResource(R.string.trq_age))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgeBand.entries.forEach { a ->
                    FilterChip(selected = age == a, onClick = { age = if (age == a) null else a },
                        leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(age == a),
                        label = { Text(com.cruxcoach.android.ui.training.coach.ageLabel(a)) },
                        modifier = Modifier.testTag("basics_age_${a.name.lowercase()}"))
                }
            }
            Text(stringResource(R.string.trq_age_why), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(20.dp))
            EquipmentEditor(chosen, compact = true) { set -> equipment = set.map { it.name }.toSet() }

            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp).testTag("basics_private"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trq_private), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}
