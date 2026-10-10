package com.cruxcoach.android.ui.training.athlete

import kotlinx.coroutines.flow.first
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.BodyReminders
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AthleteSettingsState(
    val profile: AthleteProfile = AthleteProfile(),
    val heightCm: Double? = null,
    val loaded: Boolean = false,
    /** How personal the coach can be (FEAT-071). */
    val completeness: com.cruxcoach.athlete.logic.Completeness? = null,
    /** "Nie vorschlagen" exercises with their definitions (unknown slugs fall back to a stub). */
    val excluded: List<com.cruxcoach.athlete.catalog.ExerciseDefinition> = emptyList(),
    /** Latest body weight, shown and entered on "Über dich". */
    val weightKg: Double? = null,
    /** Today's estimated energy need and, while losing weight, the plan with its warnings. */
    val need: com.cruxcoach.athlete.logic.EnergyBalance.Need? = null,
    val plan: com.cruxcoach.athlete.logic.WeightPlan.Plan? = null,
)

@HiltViewModel
class AthleteSettingsViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(AthleteSettingsState())
    val state: StateFlow<AthleteSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val logbook = runCatching { service.logbookSummary() }.getOrDefault(com.cruxcoach.athlete.logic.LogbookSummary())
            combine(
                service.repo.observeProfile(),
                service.repo.observeSeries(BodyMetric.HEIGHT.key),
                service.repo.observeAllBenchmarks(),
                service.repo.observeSeries(BodyMetric.WEIGHT.key),
            ) { profile, heights, benchmarks, _ ->
                val height = heights.lastOrNull()?.value
                val trend = service.weightTrend()
                val todayActivity = service.activities(days = 1)[service.today().toString()]
                AthleteSettingsState(
                    profile, height, loaded = true,
                    need = service.energyNeed(profile, todayActivity),
                    plan = service.weightPlan(profile, todayActivity),
                    completeness = com.cruxcoach.athlete.logic.CoachLogic.completeness(profile, benchmarks, logbook),
                    excluded = profile.excludedExercises.sorted().map { service.catalog.fallbackFor(it) },
                    weightKg = trend.lastOrNull()?.trend,
                )
            }.collect { _state.value = it }
        }
    }

    fun update(transform: (AthleteProfile) -> AthleteProfile) = io { service.repo.updateProfile(transform) }

    /** Lets an excluded exercise be suggested again. */
    fun allow(slug: String) = update { it.copy(excludedExercises = it.excludedExercises - slug) }

    /** Training days per week: the streak's goal and the coach's days are one number. */
    fun setWeeklyGoal(days: Int) = update {
        val d = days.coerceIn(1, 7)
        it.copy(weeklyGoal = d, coach = it.coach.copy(trainingDaysPerWeek = d))
    }

    fun setBirthYear(year: Int?) = update { it.copy(birthYear = year) }

    // Places with their own equipment; every change is kept at once.
    fun savePlaceEquipment(placeId: String, equipment: Set<com.cruxcoach.athlete.catalog.EquipmentV2>) = io { service.savePlaceEquipment(placeId, equipment) }
    fun renamePlace(placeId: String, name: String?) = io { service.renamePlace(placeId, name) }
    fun makeDefaultPlace(placeId: String) = io { service.makeDefaultPlace(placeId) }
    fun removePlace(placeId: String) = io { service.removePlace(placeId) }
    fun addPlace(kind: com.cruxcoach.athlete.model.PlaceKind, equipment: Set<com.cruxcoach.athlete.catalog.EquipmentV2>) = io { service.addPlace(kind, equipment) }

    fun logWeight(kg: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, kg, "kg", System.currentTimeMillis()))
    }

    fun saveWeightGoal(lose: Boolean, targetKg: Double?, paceKg: Double) = io { service.saveWeightGoal(lose, targetKg, paceKg) }

    fun setHeight(cm: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.HEIGHT.key, cm, "cm", System.currentTimeMillis()))
    }

    private fun io(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() } }
}

/** The pages of the training settings; the overview lists them with their current value. */
enum class SettingsSection(val key: String) {
    PROFILE("profile"), ABOUT("about"), EQUIPMENT("equipment"), NUTRITION("nutrition"),
    REMINDERS("reminders"), TIMER("timer"), DISPLAY("display"), CONNECTIONS("connections"), EXCLUDED("excluded");

    companion object { fun of(key: String?): SettingsSection? = entries.firstOrNull { it.key == key } }
}

/**
 * Training settings as an overview of short rows – each with its current
 * value – and one page per topic, instead of one long page (UX round 2026-10-09).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AthleteSettingsScreen(
    onBack: () -> Unit,
    viewModel: AthleteSettingsViewModel = hiltViewModel(),
    onOpenBenchmarks: () -> Unit = {},
    onOpenCoachSetup: () -> Unit = {},
    /** Device cards (Health Connect, force gauge) added by the integration; shown on the connections page. */
    extraSections: @Composable () -> Unit = {},
    /** One page of the settings; null shows the overview. */
    section: SettingsSection? = null,
    onOpenSection: (SettingsSection) -> Unit = {},
    onOpenClimbingDays: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val p = state.profile
    var weightOpen by rememberSaveable { mutableStateOf(false) }
    var goalOpen by rememberSaveable { mutableStateOf(false) }
    TrainingScaffold(title = section?.let { sectionTitle(it) } ?: stringResource(R.string.tr_action_settings), onBack = onBack) { padding ->
        if (!state.loaded) return@TrainingScaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .testTag(section?.let { "settings_${it.key}" } ?: "settings_overview"),
        ) {
            when (section) {
                null -> SettingsOverview(state, onOpenSection, onOpenClimbingDays)
                SettingsSection.PROFILE -> {
                    CoachProfileSection(p, state.completeness, onOpenCoachSetup)
                    // One number for the week: the streak's goal and the coach's training days stay the same.
                    SectionTitle(stringResource(R.string.tra_plan_title))
                    Stepper(stringResource(R.string.tra_weekly_goal, p.weeklyGoal), "weekly_goal",
                        onMinus = { viewModel.setWeeklyGoal(p.weeklyGoal - 1) }, onPlus = { viewModel.setWeeklyGoal(p.weeklyGoal + 1) })
                    // The training goal is the coach's (card above); losing weight is a choice of its own. It is
                    // never locked: target, pace and calorie target are shown with every warning that applies.
                    SectionTitle(stringResource(R.string.tru_set_weight_goal))
                    val losing = p.goal == AthleteGoal.LOSE_WEIGHT
                    SwitchRow(stringResource(R.string.tra_goal_lose), losing, "goal_lose_weight") { on ->
                        if (on) goalOpen = true else viewModel.saveWeightGoal(false, p.targetWeightKg, p.weeklyLossKg)
                    }
                    if (losing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(com.cruxcoach.android.ui.training.common.weightGoalSummary(p, state.plan), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).testTag("goal_summary"))
                            TextButton(onClick = { goalOpen = true }, modifier = Modifier.testTag("goal_edit")) { Text(stringResource(R.string.trn_goal_edit)) }
                        }
                        state.plan?.takeIf { it.warnings.isNotEmpty() }?.let { plan ->
                            val preview = com.cruxcoach.athlete.logic.EnergyReport(signals = plan.warnings, plan = plan)
                            plan.warnings.forEach { w ->
                                Text("• " + com.cruxcoach.android.ui.training.energyWarningText(w, preview, p), style = MaterialTheme.typography.bodySmall,
                                    color = CruxCoachDesign.colors.caution, modifier = Modifier.padding(top = 2.dp).testTag("settings_warning_${w.name.lowercase()}"))
                            }
                        }
                    }
                    OutlinedCard(onClick = onOpenBenchmarks, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("open_benchmarks")) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.trr_benchmarks), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.trr_benchmarks_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SettingsSection.ABOUT -> {
                    Text(stringResource(R.string.tra_personal_info), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    SectionTitle(stringResource(R.string.tr_metric_weight))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                state.weightKg == null -> stringResource(R.string.tru_set_none)
                                p.hideBodyNumbers -> stringResource(R.string.trt_numbers_hidden_short)
                                else -> formatMass(state.weightKg!!, p.units)
                            },
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = { weightOpen = true }, modifier = Modifier.testTag("settings_weight")) {
                            Text(stringResource(R.string.tru_enter_weight))
                        }
                    }
                    SectionTitle(stringResource(R.string.tru_set_body_data))
                    HeightField(state.heightCm, p.units, viewModel::setHeight)
                    BirthYearField(p.birthYear, viewModel::setBirthYear)
                    Text(stringResource(R.string.tru_set_sex), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Sex.entries.forEach { s ->
                            FilterChip(selected = p.sex == s, leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(p.sex == s), onClick = { viewModel.update { it.copy(sex = if (it.sex == s) null else s) } },
                                label = { Text(sexLabel(s)) })
                        }
                    }
                }
                SettingsSection.EQUIPMENT -> {
                    Text(stringResource(R.string.tro_places_intro), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    val day = java.time.LocalDate.now().toString()
                    val places = com.cruxcoach.athlete.logic.TrainingPlaces.of(p)
                    if (places.isEmpty()) {
                        // Nothing set up yet: the first equipment becomes the first place (at home).
                        com.cruxcoach.android.ui.training.common.EquipmentEditor(emptySet()) { set ->
                            viewModel.addPlace(com.cruxcoach.athlete.model.PlaceKind.HOME, set)
                        }
                    } else {
                        com.cruxcoach.android.ui.training.common.PlacesEditor(
                            places = places,
                            defaultId = com.cruxcoach.athlete.logic.TrainingPlaces.default(p)?.id,
                            todayId = com.cruxcoach.athlete.logic.TrainingPlaces.current(p, day)?.id,
                            onEquipment = viewModel::savePlaceEquipment,
                            onRename = viewModel::renamePlace,
                            onMakeDefault = viewModel::makeDefaultPlace,
                            onRemove = viewModel::removePlace,
                            onAdd = { kind -> viewModel.addPlace(kind, com.cruxcoach.android.ui.training.common.placeTemplate(kind)) },
                        )
                    }
                    Text(stringResource(R.string.tra_increment), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Steps are offered in the athlete's own plates: lb plates for imperial,
                        // stored in kg so the logger's kg grid lands exactly on them.
                        val steps = if (p.units == UnitSystem.IMPERIAL) listOf(1.0, 2.5, 5.0).map { it / Units.LB_PER_KG }
                            else listOf(0.5, 1.0, 1.25, 2.5)
                        steps.forEach { inc ->
                            val on = kotlin.math.abs(p.smallestIncrementKg - inc) < 1e-6
                            FilterChip(selected = on, leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(on),
                                onClick = { viewModel.update { it.copy(smallestIncrementKg = inc) } },
                                label = { Text(formatMass(inc, p.units)) })
                        }
                    }
                }
                SettingsSection.NUTRITION -> {
                    SwitchRow(stringResource(R.string.tra_show_calories), p.showCalories, "show_calories") { v -> viewModel.update { it.copy(showCalories = v) } }
                    Text(stringResource(R.string.trn_set_everyday), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EverydayActivity.entries.forEach { e ->
                            FilterChip(selected = p.everydayActivity == e, leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(p.everydayActivity == e), onClick = { viewModel.update { it.copy(everydayActivity = e) } },
                                label = { Text(com.cruxcoach.android.ui.training.common.everydayLabel(e)) },
                                modifier = Modifier.testTag("settings_everyday_${e.name.lowercase()}"))
                        }
                    }
                    Text(
                        if (p.units == UnitSystem.IMPERIAL) stringResource(R.string.tra_protein_per_kg_lb, formatNumber(p.proteinPerKg),
                            formatNumber(p.proteinPerKg / Units.LB_PER_KG, 2))
                        else stringResource(R.string.tra_protein_per_kg, formatNumber(p.proteinPerKg)),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    var ppk by remember(p.proteinPerKg) { mutableFloatStateOf(p.proteinPerKg.toFloat()) }
                    Slider(value = ppk, onValueChange = { ppk = it }, valueRange = 1.4f..2.0f, steps = 5,
                        onValueChangeFinished = { viewModel.update { it.copy(proteinPerKg = (ppk * 10).toInt() / 10.0) } })
                    Text(stringResource(R.string.tru_set_nutrition_weight), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingsSection.REMINDERS -> {
                    ReminderSection(p) { transform -> viewModel.update(transform) }
                    TrainingReminderSection(p) { transform -> viewModel.update(transform) }
                }
                SettingsSection.TIMER -> {
                    SwitchRow(stringResource(R.string.tra_auto_rest), p.autoRestTimer, "auto_rest") { v -> viewModel.update { it.copy(autoRestTimer = v) } }
                    SwitchRow(stringResource(R.string.tra_timer_sound), p.timerSound, "timer_sound") { v -> viewModel.update { it.copy(timerSound = v) } }
                    SwitchRow(stringResource(R.string.tra_timer_vibration), p.timerVibration, "timer_vibration") { v -> viewModel.update { it.copy(timerVibration = v) } }
                    SwitchRow(stringResource(R.string.tra_timer_voice), p.timerVoice, "timer_voice") { v -> viewModel.update { it.copy(timerVoice = v) } }
                }
                SettingsSection.DISPLAY -> {
                    SwitchRow(stringResource(R.string.tra_module_checkin), p.checkinEnabled, "module_checkin") { v -> viewModel.update { it.copy(checkinEnabled = v) } }
                    SwitchRow(stringResource(R.string.tra_module_body), p.bodyEnabled, "module_body") { v -> viewModel.update { it.copy(bodyEnabled = v) } }
                    SwitchRow(stringResource(R.string.tra_hide_numbers), p.hideBodyNumbers, "hide_numbers") { v -> viewModel.update { it.copy(hideBodyNumbers = v) } }
                    // The same setting, labels and explanation as in the app settings, next to the language.
                    SectionTitle(stringResource(R.string.settings_units_title))
                    Text(stringResource(R.string.settings_units_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = p.units == UnitSystem.METRIC, leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(p.units == UnitSystem.METRIC),
                            onClick = { viewModel.update { Units.withUnits(it, UnitSystem.METRIC) } },
                            label = { Text(stringResource(R.string.settings_units_metric)) }, modifier = Modifier.testTag("units_metric"))
                        FilterChip(selected = p.units == UnitSystem.IMPERIAL, leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(p.units == UnitSystem.IMPERIAL),
                            onClick = { viewModel.update { Units.withUnits(it, UnitSystem.IMPERIAL) } },
                            label = { Text(stringResource(R.string.settings_units_us)) }, modifier = Modifier.testTag("units_us"))
                    }
                }
                SettingsSection.CONNECTIONS -> {
                    Spacer(Modifier.height(8.dp))
                    extraSections()
                }
                SettingsSection.EXCLUDED -> {
                    if (state.excluded.isEmpty()) {
                        Text(stringResource(R.string.trc_excluded_none), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        val lang = catalogLanguage()
                        state.excluded.forEach { def ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(def.name(lang), modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.allow(def.slug) }, modifier = Modifier.testTag("excluded_allow_${def.slug}")) {
                                    Text(stringResource(R.string.trc_excluded_allow))
                                }
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.tra_privacy_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        }
    }
    if (goalOpen) {
        com.cruxcoach.android.ui.training.common.WeightGoalSheet(
            profile = p, weightKg = state.weightKg, heightCm = state.heightCm, need = state.need,
            onDismiss = { goalOpen = false },
            onSave = viewModel::saveWeightGoal,
            onLogWeight = viewModel::logWeight, onLogHeight = viewModel::setHeight,
        )
    }
    if (weightOpen) {
        com.cruxcoach.android.ui.training.common.WeightSheet(
            units = p.units, lastKg = state.weightKg, hideNumbers = p.hideBodyNumbers,
            onDismiss = { weightOpen = false },
            onSave = { kg -> viewModel.logWeight(kg); weightOpen = false },
        )
    }
}

@Composable
private fun sectionTitle(s: SettingsSection): String = stringResource(when (s) {
    SettingsSection.PROFILE -> R.string.tru_set_profile
    SettingsSection.ABOUT -> R.string.tru_set_about
    SettingsSection.EQUIPMENT -> R.string.tru_set_equipment
    SettingsSection.NUTRITION -> R.string.tra_module_fuel
    SettingsSection.REMINDERS -> R.string.trr_title
    SettingsSection.TIMER -> R.string.tra_timer_title
    SettingsSection.DISPLAY -> R.string.tru_set_display
    SettingsSection.CONNECTIONS -> R.string.tru_set_connections
    SettingsSection.EXCLUDED -> R.string.trc_excluded_title
})

/** Every page as one row with its current value; the most important first. */
@Composable
private fun SettingsOverview(state: AthleteSettingsState, onOpen: (SettingsSection) -> Unit, onOpenClimbingDays: () -> Unit) {
    val p = state.profile
    val on = stringResource(R.string.tru_set_on)
    val off = stringResource(R.string.tru_set_off)
    val allOff = stringResource(R.string.tru_set_all_off)
    @Composable
    fun row(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, tag: String, onClick: () -> Unit) {
        ListItem(
            leadingContent = { Icon(icon, null, tint = CruxCoachDesign.colors.brandAccent) },
            headlineContent = { Text(title) },
            supportingContent = { Text(summary, maxLines = 2) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick).testTag(tag),
        )
    }
    fun open(s: SettingsSection) = { onOpen(s) }
    Spacer(Modifier.height(4.dp))
    row(Icons.Default.Flag, sectionTitle(SettingsSection.PROFILE),
        listOf(p.coach.goal?.let { com.cruxcoach.android.ui.training.coach.coachGoalLabel(it) } ?: stringResource(R.string.trc_settings_no_goal),
            pluralStringResource(R.plurals.tru_set_days, p.weeklyGoal, p.weeklyGoal)).joinToString(" · "),
        "settings_row_profile", open(SettingsSection.PROFILE))
    val aboutParts = listOfNotNull(
        state.weightKg?.let { if (p.hideBodyNumbers) stringResource(R.string.trt_numbers_hidden_short) else formatMass(it, p.units) },
        state.heightCm?.let { formatLength(it, p.units) },
        p.birthYear?.toString(),
    )
    row(Icons.Default.Person, sectionTitle(SettingsSection.ABOUT),
        if (aboutParts.isEmpty()) stringResource(R.string.tru_set_about_empty) else aboutParts.joinToString(" · "),
        "settings_row_about", open(SettingsSection.ABOUT))
    // One place: its name and how much is there; several: their names, the default marked.
    val places = com.cruxcoach.athlete.logic.TrainingPlaces.of(p)
    val defaultPlace = com.cruxcoach.athlete.logic.TrainingPlaces.default(p)
    val placeNames = places.map { place ->
        val name = com.cruxcoach.android.ui.training.common.placeLabel(place)
        if (places.size > 1 && place.id == defaultPlace?.id) stringResource(R.string.tro_summary_default, name) else name
    }
    val count = ((defaultPlace?.equipment ?: p.equipment) - com.cruxcoach.android.ui.training.common.ALWAYS_THERE).size
    row(Icons.Default.FitnessCenter, sectionTitle(SettingsSection.EQUIPMENT),
        when {
            places.isEmpty() -> stringResource(R.string.tru_set_not_set_up)
            places.size == 1 -> placeNames.single() + " · " + pluralStringResource(R.plurals.tru_set_equipment_count, count, count)
            else -> placeNames.joinToString(" · ")
        },
        "settings_row_equipment", open(SettingsSection.EQUIPMENT))
    row(Icons.Default.Restaurant, sectionTitle(SettingsSection.NUTRITION),
        stringResource(R.string.tru_set_nutrition_summary, formatNumber(p.proteinPerKg), if (p.showCalories) on else off),
        "settings_row_nutrition", open(SettingsSection.NUTRITION))
    val reminders = listOf(p.weighReminderEnabled, p.measureReminderEnabled, p.trainingReminderEnabled).count { it }
    row(Icons.Default.Notifications, sectionTitle(SettingsSection.REMINDERS),
        if (reminders == 0) allOff else pluralStringResource(R.plurals.tru_set_reminders, reminders, reminders),
        "settings_row_reminders", open(SettingsSection.REMINDERS))
    row(Icons.Default.Timer, sectionTitle(SettingsSection.TIMER),
        listOfNotNull(
            stringResource(R.string.tru_set_timer_auto).takeIf { p.autoRestTimer },
            stringResource(R.string.tru_set_timer_sound).takeIf { p.timerSound },
            stringResource(R.string.tru_set_timer_vibration).takeIf { p.timerVibration },
            stringResource(R.string.tru_set_timer_voice).takeIf { p.timerVoice },
        ).ifEmpty { listOf(allOff) }.joinToString(" · "),
        "settings_row_timer", open(SettingsSection.TIMER))
    row(Icons.Default.Visibility, sectionTitle(SettingsSection.DISPLAY),
        stringResource(if (p.units == UnitSystem.IMPERIAL) R.string.settings_units_us else R.string.settings_units_metric) +
            (if (p.hideBodyNumbers) " · " + stringResource(R.string.tru_set_numbers_hidden) else ""),
        "settings_row_display", open(SettingsSection.DISPLAY))
    row(Icons.Default.Sync, sectionTitle(SettingsSection.CONNECTIONS), stringResource(R.string.tru_set_connections_summary),
        "settings_row_connections", open(SettingsSection.CONNECTIONS))
    row(Icons.Default.Terrain, stringResource(R.string.trl_days_title), stringResource(R.string.tru_set_days_summary),
        "settings_row_climbing_days", onOpenClimbingDays)
    row(Icons.Default.Block, sectionTitle(SettingsSection.EXCLUDED),
        if (state.excluded.isEmpty()) stringResource(R.string.tru_set_none) else pluralStringResource(R.plurals.tru_set_exercises, state.excluded.size, state.excluded.size),
        "settings_row_excluded", open(SettingsSection.EXCLUDED))
}

@Composable
private fun Stepper(label: String, tag: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onMinus, modifier = Modifier.testTag("${tag}_minus")) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onPlus, modifier = Modifier.testTag("${tag}_plus")) { Text("+") }
    }
}

/** Birth year (four digits): age-based reference values for micronutrients and the coach's guard rails. */
@Composable
private fun BirthYearField(year: Int?, onSave: (Int?) -> Unit) {
    val thisYear = java.time.LocalDate.now().year
    // Saves itself; an emptied field removes the year.
    com.cruxcoach.android.ui.training.common.AutoSaveTextField(
        stored = year?.toString() ?: "",
        isValid = { t -> t.isBlank() || t.toIntOrNull()?.let { it in (thisYear - 100)..(thisYear - 8) } == true },
        onSave = { t -> onSave(t.toIntOrNull()) },
        label = stringResource(R.string.tru_set_birth_year),
        keyboardType = KeyboardType.Number,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("birth_year_input"),
    )
}

/**
 * Optional weigh-in and measuring reminders. Every change is stored in the
 * profile and rescheduled at once; enabling one asks for the notification
 * permission on Android 13+, and a denial keeps the setting but says why
 * nothing will appear.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSection(p: AthleteProfile, update: ((AthleteProfile) -> AthleteProfile) -> Unit) {
    val context = LocalContext.current
    fun applyChange(transform: (AthleteProfile) -> AthleteProfile) {
        update(transform)
        BodyReminders.reschedule(context, transform(p))
    }
    fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(notificationsAllowed()) }
    var pending by remember { mutableStateOf<((AthleteProfile) -> AthleteProfile)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        pending?.let { applyChange(it) }
        pending = null
    }
    fun enable(transform: (AthleteProfile) -> AthleteProfile) {
        if (notificationsAllowed()) { allowed = true; applyChange(transform) }
        else { pending = transform; launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    val timeText = String.format(java.util.Locale.ROOT, "%02d:%02d", p.weighReminderMinutes / 60, p.weighReminderMinutes % 60)

    SectionTitle(stringResource(R.string.trr_title)) {
        InfoButton(stringResource(R.string.trr_title), stringResource(R.string.trr_info_text))
    }
    SwitchRow(stringResource(R.string.trr_weigh), p.weighReminderEnabled, "weigh_reminder") { on ->
        if (on) enable { it.copy(weighReminderEnabled = true) } else applyChange { it.copy(weighReminderEnabled = false) }
    }
    if (p.weighReminderEnabled) {
        Text(stringResource(R.string.trr_weigh_days), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            java.time.DayOfWeek.entries.forEach { day ->
                val selected = day.value in p.weighReminderDays
                FilterChip(
                    selected = selected,
                    onClick = {
                        applyChange {
                            val days = if (selected) it.weighReminderDays - day.value else it.weighReminderDays + day.value
                            it.copy(weighReminderDays = days.ifEmpty { setOf(day.value) })
                        }
                    },
                    label = { Text(day.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())) },
                    modifier = Modifier.testTag("weigh_day_${day.value}"),
                )
            }
        }
    }
    SwitchRow(stringResource(R.string.trr_measure), p.measureReminderEnabled, "measure_reminder") { on ->
        if (on) enable { it.copy(measureReminderEnabled = true) } else applyChange { it.copy(measureReminderEnabled = false) }
    }
    if (p.measureReminderEnabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.trr_measure_day, p.measureReminderDayOfMonth), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { applyChange { it.copy(measureReminderDayOfMonth = (it.measureReminderDayOfMonth - 1).coerceAtLeast(1)) } },
                modifier = Modifier.testTag("measure_day_minus")) { Text("−") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { applyChange { it.copy(measureReminderDayOfMonth = (it.measureReminderDayOfMonth + 1).coerceAtMost(28)) } },
                modifier = Modifier.testTag("measure_day_plus")) { Text("+") }
        }
    }
    if (p.weighReminderEnabled || p.measureReminderEnabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.trr_time, timeText), modifier = Modifier.weight(1f))
            TextButton(onClick = { picking = true }, modifier = Modifier.testTag("reminder_time")) {
                Text(stringResource(R.string.trr_time_pick))
            }
        }
        if (p.weighReminderEnabled && p.measureReminderEnabled) {
            Text(stringResource(R.string.trr_measure_same_time), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!allowed) {
            Text(stringResource(R.string.trr_permission_denied), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reminder_permission_hint"))
        }
    }
    if (picking) {
        val state = rememberTimePickerState(
            initialHour = p.weighReminderMinutes / 60,
            initialMinute = p.weighReminderMinutes % 60,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.trr_time_pick)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    applyChange { it.copy(weighReminderMinutes = state.hour * 60 + state.minute) }
                    picking = false
                }, modifier = Modifier.testTag("reminder_time_save")) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

/**
 * Optional reminder on planned training days (week plan). Same pattern as
 * the body reminders: stored in the profile, rescheduled at once, the
 * notification permission asked when switching it on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrainingReminderSection(p: AthleteProfile, update: ((AthleteProfile) -> AthleteProfile) -> Unit) {
    val context = LocalContext.current
    fun applyChange(transform: (AthleteProfile) -> AthleteProfile) {
        update(transform)
        com.cruxcoach.android.athlete.TrainingReminders.reschedule(context, transform(p))
    }
    fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(notificationsAllowed()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        applyChange { it.copy(trainingReminderEnabled = true) }
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    val timeText = String.format(java.util.Locale.ROOT, "%02d:%02d", p.trainingReminderMinutes / 60, p.trainingReminderMinutes % 60)

    SectionTitle(stringResource(R.string.trw_reminder_title)) {
        InfoButton(stringResource(R.string.trw_reminder_title), stringResource(R.string.trw_reminder_info))
    }
    SwitchRow(stringResource(R.string.trw_reminder_switch), p.trainingReminderEnabled, "training_reminder") { on ->
        when {
            !on -> applyChange { it.copy(trainingReminderEnabled = false) }
            notificationsAllowed() -> { allowed = true; applyChange { it.copy(trainingReminderEnabled = true) } }
            else -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (p.trainingReminderEnabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.trr_time, timeText), modifier = Modifier.weight(1f))
            TextButton(onClick = { picking = true }, modifier = Modifier.testTag("training_reminder_time")) {
                Text(stringResource(R.string.trr_time_pick))
            }
        }
        SwitchRow(stringResource(R.string.trw_reminder_board_days), p.trainingReminderOnClimbingDays, "training_reminder_board") { v ->
            applyChange { it.copy(trainingReminderOnClimbingDays = v) }
        }
        if (p.weekPlan.isEmpty()) {
            Text(stringResource(R.string.trw_reminder_no_plan), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("training_reminder_no_plan"))
        }
        if (!allowed) {
            Text(stringResource(R.string.trr_permission_denied), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
    if (picking) {
        val state = rememberTimePickerState(
            initialHour = p.trainingReminderMinutes / 60,
            initialMinute = p.trainingReminderMinutes % 60,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.trr_time_pick)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    applyChange { it.copy(trainingReminderMinutes = state.hour * 60 + state.minute) }
                    picking = false
                }, modifier = Modifier.testTag("training_reminder_time_save")) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

@Composable
private fun CoachProfileSection(p: AthleteProfile, completeness: com.cruxcoach.athlete.logic.Completeness?, onOpen: () -> Unit) {
    SectionTitle(stringResource(R.string.trc_settings_title)) {
        InfoButton(stringResource(R.string.trc_settings_title), stringResource(R.string.trc_settings_info))
    }
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("open_coach_setup")) {
        Column(Modifier.padding(12.dp)) {
            val c = p.coach
            Text(c.goal?.let { com.cruxcoach.android.ui.training.coach.coachGoalLabel(it) } ?: stringResource(R.string.trc_settings_no_goal),
                style = MaterialTheme.typography.titleSmall)
            if (completeness != null) {
                Text(stringResource(R.string.trc_card_level, com.cruxcoach.android.ui.training.coach.levelLabel(completeness.level)) +
                    " · " + stringResource(R.string.trc_settings_score, completeness.score),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                completeness.next?.let {
                    Text(com.cruxcoach.android.ui.training.coach.stepText(it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(R.string.trc_settings_local), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun HeightField(heightCm: Double?, units: UnitSystem, onSave: (Double) -> Unit) {
    fun cm(text: String) = parseDecimal(text)?.let { Units.lengthFromDisplay(it, units) }
    com.cruxcoach.android.ui.training.common.AutoSaveTextField(
        stored = heightCm?.let { formatNumber(Units.lengthToDisplay(it, units)) } ?: "",
        isValid = { t -> cm(t)?.let { it in com.cruxcoach.android.ui.training.common.PLAUSIBLE_HEIGHT_CM } == true },
        onSave = { t -> cm(t)?.let(onSave) },
        label = stringResource(R.string.tra_height, Units.lengthUnit(units)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("height_input"),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag("switch_$tag"))
    }
}


@Composable
private fun sexLabel(s: Sex): String = stringResource(when (s) {
    Sex.FEMALE -> R.string.tra_sex_female
    Sex.MALE -> R.string.tra_sex_male
    Sex.OTHER -> R.string.tra_sex_other
})
