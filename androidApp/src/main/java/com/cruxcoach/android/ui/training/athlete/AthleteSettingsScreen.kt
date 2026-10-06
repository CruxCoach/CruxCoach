package com.cruxcoach.android.ui.training.athlete

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
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
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.RedsGuard
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
    val lossGoalAllowed: Boolean = true,
    val loaded: Boolean = false,
    /** How personal the coach can be (FEAT-071). */
    val completeness: com.cruxcoach.athlete.logic.Completeness? = null,
    /** "Nie vorschlagen" exercises with their definitions (unknown slugs fall back to a stub). */
    val excluded: List<com.cruxcoach.athlete.catalog.ExerciseDefinition> = emptyList(),
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
            ) { profile, heights, benchmarks ->
                val height = heights.lastOrNull()?.value
                AthleteSettingsState(
                    profile, height, RedsGuard.lossGoalAllowed(profile, height, service.weightTrend()), loaded = true,
                    completeness = com.cruxcoach.athlete.logic.CoachLogic.completeness(profile, benchmarks, logbook),
                    excluded = profile.excludedExercises.sorted().map { service.catalog.fallbackFor(it) },
                )
            }.collect { _state.value = it }
        }
    }

    fun update(transform: (AthleteProfile) -> AthleteProfile) = io { service.repo.updateProfile(transform) }

    /** Lets an excluded exercise be suggested again. */
    fun allow(slug: String) = update { it.copy(excludedExercises = it.excludedExercises - slug) }

    fun setHeight(cm: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.HEIGHT.key, cm, "cm", System.currentTimeMillis()))
    }

    private fun io(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() } }
}

private val PRESET_HOME = setOf(EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.BANDS, EquipmentV2.DUMBBELL)
private val PRESET_GYM = setOf(EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.RINGS, EquipmentV2.DUMBBELL,
    EquipmentV2.KETTLEBELL, EquipmentV2.BARBELL, EquipmentV2.PLATES, EquipmentV2.BANDS, EquipmentV2.BENCH, EquipmentV2.BOX,
    EquipmentV2.CABLE, EquipmentV2.WALL, EquipmentV2.BOARD, EquipmentV2.CAMPUS_BOARD, EquipmentV2.FOAM_ROLLER, EquipmentV2.DIP_BARS)
private val PRESET_TRAVEL = setOf(EquipmentV2.BANDS)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AthleteSettingsScreen(
    onBack: () -> Unit,
    viewModel: AthleteSettingsViewModel = hiltViewModel(),
    onOpenBenchmarks: () -> Unit = {},
    onOpenCoachSetup: () -> Unit = {},
    /** Room for device cards (Health Connect, force gauge) added by the integration. */
    extraSections: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val p = state.profile
    TrainingScaffold(title = stringResource(R.string.tr_action_settings), onBack = onBack) { padding ->
        if (!state.loaded) return@TrainingScaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Coach profile (FEAT-071)
            CoachProfileSection(p, state.completeness, onOpenCoachSetup)

            // Equipment profile
            SectionTitle(stringResource(R.string.tra_equipment_title)) {
                InfoButton(stringResource(R.string.tra_equipment_title), stringResource(R.string.tra_equipment_info))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { viewModel.update { it.copy(equipment = PRESET_HOME + EquipmentV2.NONE + EquipmentV2.MAT, equipmentConfigured = true) } },
                    label = { Text(stringResource(R.string.tra_preset_home)) }, modifier = Modifier.testTag("preset_home"))
                AssistChip(onClick = { viewModel.update { it.copy(equipment = PRESET_GYM + EquipmentV2.NONE + EquipmentV2.MAT, equipmentConfigured = true) } },
                    label = { Text(stringResource(R.string.tra_preset_gym)) }, modifier = Modifier.testTag("preset_gym"))
                AssistChip(onClick = { viewModel.update { it.copy(equipment = PRESET_TRAVEL + EquipmentV2.NONE + EquipmentV2.MAT, equipmentConfigured = true) } },
                    label = { Text(stringResource(R.string.tra_preset_travel)) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EquipmentV2.entries.filter { it != EquipmentV2.NONE && it != EquipmentV2.MAT }.forEach { e ->
                    val selected = e in p.equipment
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.update { it.copy(equipment = if (selected) it.equipment - e else it.equipment + e, equipmentConfigured = true) } },
                        label = { Text(equipmentLabel(e)) },
                        modifier = Modifier.testTag("equipment_${e.name.lowercase()}"),
                    )
                }
            }
            Text(stringResource(R.string.tra_increment), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Steps are offered in the athlete's own plates: lb plates for imperial,
                // stored in kg so the logger's kg grid lands exactly on them.
                val steps = if (p.units == UnitSystem.IMPERIAL) listOf(1.0, 2.5, 5.0).map { it / Units.LB_PER_KG }
                    else listOf(0.5, 1.0, 1.25, 2.5)
                steps.forEach { inc ->
                    FilterChip(selected = kotlin.math.abs(p.smallestIncrementKg - inc) < 1e-6,
                        onClick = { viewModel.update { it.copy(smallestIncrementKg = inc) } },
                        label = { Text(formatMass(inc, p.units)) })
                }
            }

            // Plan
            SectionTitle(stringResource(R.string.tra_plan_title))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tra_weekly_goal, p.weeklyGoal), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { viewModel.update { it.copy(weeklyGoal = (it.weeklyGoal - 1).coerceAtLeast(1)) } },
                    modifier = Modifier.testTag("weekly_goal_minus")) { Text("−") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { viewModel.update { it.copy(weeklyGoal = (it.weeklyGoal + 1).coerceAtMost(7)) } },
                    modifier = Modifier.testTag("weekly_goal_plus")) { Text("+") }
            }
            OutlinedCard(onClick = onOpenBenchmarks, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("open_benchmarks")) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.trr_benchmarks), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.trr_benchmarks_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.tra_goal), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AthleteGoal.entries.forEach { g ->
                    val enabled = g != AthleteGoal.LOSE_WEIGHT || state.lossGoalAllowed || p.goal == g
                    FilterChip(selected = p.goal == g, enabled = enabled, onClick = { viewModel.update { it.copy(goal = g) } },
                        label = { Text(goalLabel(g)) }, modifier = Modifier.testTag("goal_${g.name.lowercase()}"))
                }
            }
            if (!state.lossGoalAllowed) {
                Text(stringResource(R.string.tra_goal_loss_blocked), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Modules
            SectionTitle(stringResource(R.string.tra_modules_title))
            SwitchRow(stringResource(R.string.tra_module_checkin), p.checkinEnabled, "module_checkin") { v -> viewModel.update { it.copy(checkinEnabled = v) } }
            SwitchRow(stringResource(R.string.tra_module_body), p.bodyEnabled, "module_body") { v -> viewModel.update { it.copy(bodyEnabled = v) } }
            SwitchRow(stringResource(R.string.tra_hide_numbers), p.hideBodyNumbers, "hide_numbers") { v -> viewModel.update { it.copy(hideBodyNumbers = v) } }
            SwitchRow(stringResource(R.string.tra_module_fuel), p.fuelEnabled, "module_fuel") { v -> viewModel.update { it.copy(fuelEnabled = v) } }
            if (p.fuelEnabled) {
                SwitchRow(stringResource(R.string.tra_show_calories), p.showCalories, "show_calories") { v -> viewModel.update { it.copy(showCalories = v) } }
                Text(stringResource(R.string.tra_protein_per_kg, formatNumber(p.proteinPerKg)), modifier = Modifier.padding(top = 8.dp))
                var ppk by remember(p.proteinPerKg) { mutableFloatStateOf(p.proteinPerKg.toFloat()) }
                Slider(value = ppk, onValueChange = { ppk = it }, valueRange = 1.4f..2.0f, steps = 5,
                    onValueChangeFinished = { viewModel.update { it.copy(proteinPerKg = (ppk * 10).toInt() / 10.0) } })
            }

            // Units and personal data
            SectionTitle(stringResource(R.string.tra_units_title))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = p.units == UnitSystem.METRIC, onClick = { viewModel.update { it.copy(units = UnitSystem.METRIC, smallestIncrementKg = 1.0) } },
                    label = { Text(stringResource(R.string.tra_units_metric)) })
                FilterChip(selected = p.units == UnitSystem.IMPERIAL, onClick = { viewModel.update { it.copy(units = UnitSystem.IMPERIAL, smallestIncrementKg = 2.5 / Units.LB_PER_KG) } },
                    label = { Text(stringResource(R.string.tra_units_imperial)) })
            }
            SectionTitle(stringResource(R.string.tra_personal_title)) {
                InfoButton(stringResource(R.string.tra_personal_title), stringResource(R.string.tra_personal_info))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Sex.entries.forEach { s ->
                    FilterChip(selected = p.sex == s, onClick = { viewModel.update { it.copy(sex = if (it.sex == s) null else s) } },
                        label = { Text(sexLabel(s)) })
                }
            }
            HeightField(state.heightCm, p.units, viewModel::setHeight)

            // Reminders
            ReminderSection(p) { transform -> viewModel.update(transform) }
            TrainingReminderSection(p) { transform -> viewModel.update(transform) }

            // Timer
            SectionTitle(stringResource(R.string.tra_timer_title))
            SwitchRow(stringResource(R.string.tra_auto_rest), p.autoRestTimer, "auto_rest") { v -> viewModel.update { it.copy(autoRestTimer = v) } }
            SwitchRow(stringResource(R.string.tra_timer_sound), p.timerSound, "timer_sound") { v -> viewModel.update { it.copy(timerSound = v) } }
            SwitchRow(stringResource(R.string.tra_timer_vibration), p.timerVibration, "timer_vibration") { v -> viewModel.update { it.copy(timerVibration = v) } }
            SwitchRow(stringResource(R.string.tra_timer_voice), p.timerVoice, "timer_voice") { v -> viewModel.update { it.copy(timerVoice = v) } }

            // Exercises the athlete never wants suggested
            SectionTitle(stringResource(R.string.trc_excluded_title))
            if (state.excluded.isEmpty()) {
                Text(stringResource(R.string.trc_excluded_none), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            extraSections()

            Text(stringResource(R.string.tra_privacy_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        }
    }
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
                }, modifier = Modifier.testTag("reminder_time_save")) { Text(stringResource(R.string.tr_action_save)) }
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
                }, modifier = Modifier.testTag("training_reminder_time_save")) { Text(stringResource(R.string.tr_action_save)) }
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
    var text by rememberSaveable(heightCm, units) {
        mutableStateOf(heightCm?.let { formatNumber(Units.lengthToDisplay(it, units)) } ?: "")
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
            label = { Text(stringResource(R.string.tra_height, Units.lengthUnit(units))) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).testTag("height_input"))
        Spacer(Modifier.width(8.dp))
        val cm = parseDecimal(text)?.let { Units.lengthFromDisplay(it, units) }
        FilledTonalButton(onClick = { cm?.let(onSave) }, enabled = cm != null && cm in 100.0..250.0 && cm != heightCm) {
            Text(stringResource(R.string.tr_action_save))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag("switch_$tag"))
    }
}

@Composable
private fun goalLabel(g: AthleteGoal): String = stringResource(when (g) {
    AthleteGoal.PERFORM -> R.string.tra_goal_perform
    AthleteGoal.MAINTAIN -> R.string.tra_goal_maintain
    AthleteGoal.BUILD_STRENGTH -> R.string.tra_goal_strength
    AthleteGoal.LOSE_WEIGHT -> R.string.tra_goal_lose
})

@Composable
private fun sexLabel(s: Sex): String = stringResource(when (s) {
    Sex.FEMALE -> R.string.tra_sex_female
    Sex.MALE -> R.string.tra_sex_male
    Sex.OTHER -> R.string.tra_sex_other
})
