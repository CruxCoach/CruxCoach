package com.cruxcoach.android.ui.training.exercises

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.ExerciseText
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.catalog.Prescription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomExerciseState(
    val name: String = "",
    val category: ExerciseCategoryV2 = ExerciseCategoryV2.PULL,
    val kind: ExerciseKind = ExerciseKind.REPS,
    val load: LoadMode = LoadMode.BODYWEIGHT,
    val unilateral: Boolean = false,
    val equipment: Set<EquipmentV2> = emptySet(),
    val domains: Set<LoadDomain> = emptySet(),
    val note: String = "",
    val showNameError: Boolean = false,
    val saving: Boolean = false,
    val savedSlug: String? = null,
)

@HiltViewModel
class CustomExerciseViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(CustomExerciseState())
    val state: StateFlow<CustomExerciseState> = _state.asStateFlow()

    fun setName(v: String) = _state.update { it.copy(name = v.take(60), showNameError = false) }
    fun setCategory(c: ExerciseCategoryV2) = _state.update { it.copy(category = c) }
    fun setKind(k: ExerciseKind) = _state.update { it.copy(kind = k, load = defaultLoad(k)) }
    fun setLoad(l: LoadMode) = _state.update { it.copy(load = l) }
    fun setUnilateral(v: Boolean) = _state.update { it.copy(unilateral = v) }
    fun setNote(v: String) = _state.update { it.copy(note = v.take(300)) }
    fun toggleEquipment(e: EquipmentV2) = _state.update { s ->
        s.copy(equipment = if (e in s.equipment) s.equipment - e else s.equipment + e)
    }
    fun toggleDomain(d: LoadDomain) = _state.update { s ->
        s.copy(domains = if (d in s.domains) s.domains - d else s.domains + d)
    }

    fun save() {
        val s = _state.value
        val name = s.name.trim()
        if (name.isEmpty()) { _state.update { it.copy(showNameError = true) }; return }
        if (s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val slug = "user." + slugify(name) + "_" + repo.newId().filter { it.isLetterOrDigit() }.take(6).lowercase()
            val text = ExerciseText(name = name, why = s.note.trim())
            val equipment = s.equipment.toList().ifEmpty { listOf(EquipmentV2.NONE) }
            val needsWall = s.kind == ExerciseKind.CLIMB ||
                equipment.any { it == EquipmentV2.WALL || it == EquipmentV2.BOARD || it == EquipmentV2.CAMPUS_BOARD }
            val tags = buildList {
                if (!needsWall) add(ExerciseDefinition.TAG_NO_CLIMBING)
                if (LoadDomain.FINGER !in s.domains) add(ExerciseDefinition.TAG_FINGER_FREE)
            }
            val def = ExerciseDefinition(
                slug = slug,
                category = s.category,
                kind = s.kind,
                load = s.load,
                unilateral = s.unilateral,
                equipment = equipment,
                domains = s.domains.toList(),
                difficulty = 2,
                defaults = defaultPrescription(s.kind),
                tags = tags,
                i18n = mapOf("de" to text, "en" to text),
                custom = true,
            )
            repo.saveCustomExercise(def)
            service.catalogStore.refresh()
            _state.update { it.copy(saving = false, savedSlug = slug) }
        }
    }

    fun consumeSaved() = _state.update { it.copy(savedSlug = null) }

    companion object {
        fun defaultLoad(kind: ExerciseKind): LoadMode = when (kind) {
            ExerciseKind.REPS -> LoadMode.BODYWEIGHT
            ExerciseKind.LOAD_REPS -> LoadMode.EXTERNAL
            ExerciseKind.TIME -> LoadMode.NONE
            ExerciseKind.HANG, ExerciseKind.INTERVAL -> LoadMode.BODYWEIGHT_PLUS
            ExerciseKind.CLIMB -> LoadMode.NONE
        }

        fun defaultPrescription(kind: ExerciseKind): Prescription = when (kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> Prescription(sets = 3, repsMin = 8, repsMax = 12, restS = 90)
            ExerciseKind.TIME -> Prescription(sets = 3, durationS = 30, restS = 60)
            ExerciseKind.HANG -> Prescription(sets = 5, durationS = 10, restS = 120, edgeMm = 20)
            ExerciseKind.INTERVAL -> Prescription(sets = 4, workS = 7, restBetweenS = 3, repsPerSet = 6, restS = 180)
            ExerciseKind.CLIMB -> Prescription(rounds = 4, restS = 120)
        }

        /** ASCII slug part: "Einarmige Blöcke!" → "einarmige_bloecke". */
        fun slugify(name: String): String {
            val ascii = name.lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .take(40)
                .trim('_')
            return ascii.ifEmpty { "exercise" }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomExerciseScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: CustomExerciseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedSlug) {
        state.savedSlug?.let { viewModel.consumeSaved(); onSaved(it) }
    }
    TrainingScaffold(title = stringResource(R.string.trx_custom_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.trx_custom_name)) },
                isError = state.showNameError,
                supportingText = if (state.showNameError) { { Text(stringResource(R.string.trx_custom_name_required)) } } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag("custom_exercise_name"),
            )

            SectionTitle(stringResource(R.string.trx_custom_category))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExerciseCategoryV2.entries.forEach { c ->
                    FilterChip(selected = state.category == c, onClick = { viewModel.setCategory(c) },
                        label = { Text(categoryLabel(c)) }, modifier = Modifier.testTag("custom_category_${c.name.lowercase()}"))
                }
            }

            SectionTitle(stringResource(R.string.trx_custom_kind))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExerciseKind.entries.forEach { k ->
                    FilterChip(selected = state.kind == k, onClick = { viewModel.setKind(k) },
                        label = { Text(kindLabel(k)) }, modifier = Modifier.testTag("custom_kind_${k.name.lowercase()}"))
                }
            }

            SectionTitle(stringResource(R.string.trx_custom_load))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadMode.entries.forEach { l ->
                    FilterChip(selected = state.load == l, onClick = { viewModel.setLoad(l) },
                        label = { Text(loadLabel(l)) }, modifier = Modifier.testTag("custom_load_${l.name.lowercase()}"))
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clickable(role = Role.Switch) { viewModel.setUnilateral(!state.unilateral) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.trx_custom_unilateral), modifier = Modifier.weight(1f))
                Switch(checked = state.unilateral, onCheckedChange = viewModel::setUnilateral,
                    modifier = Modifier.testTag("custom_unilateral"))
            }

            SectionTitle(stringResource(R.string.trx_custom_equipment))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EquipmentV2.entries.filterNot { it == EquipmentV2.NONE }.forEach { e ->
                    FilterChip(selected = e in state.equipment, onClick = { viewModel.toggleEquipment(e) },
                        label = { Text(equipmentLabel(e)) }, modifier = Modifier.testTag("custom_equipment_${e.name.lowercase()}"))
                }
            }

            SectionTitle(stringResource(R.string.trx_custom_domains))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadDomain.entries.forEach { d ->
                    FilterChip(selected = d in state.domains, onClick = { viewModel.toggleDomain(d) },
                        label = { Text(domainLabel(d)) }, modifier = Modifier.testTag("custom_domain_${d.name.lowercase()}"))
                }
            }

            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                label = { Text(stringResource(R.string.trx_custom_note)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("custom_exercise_note"),
            )

            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp).testTag("custom_exercise_save"),
            ) { Text(stringResource(R.string.tr_action_save)) }
        }
    }
}

@Composable
private fun kindLabel(k: ExerciseKind): String = stringResource(when (k) {
    ExerciseKind.REPS -> R.string.trx_kind_reps
    ExerciseKind.LOAD_REPS -> R.string.trx_kind_load_reps
    ExerciseKind.TIME -> R.string.trx_kind_time
    ExerciseKind.HANG -> R.string.trx_kind_hang
    ExerciseKind.INTERVAL -> R.string.trx_kind_interval
    ExerciseKind.CLIMB -> R.string.trx_kind_climb
})

@Composable
private fun loadLabel(l: LoadMode): String = stringResource(when (l) {
    LoadMode.NONE -> R.string.trx_load_none
    LoadMode.BODYWEIGHT -> R.string.trx_load_bodyweight
    LoadMode.BODYWEIGHT_PLUS -> R.string.trx_load_bodyweight_plus
    LoadMode.EXTERNAL -> R.string.trx_load_external
})
