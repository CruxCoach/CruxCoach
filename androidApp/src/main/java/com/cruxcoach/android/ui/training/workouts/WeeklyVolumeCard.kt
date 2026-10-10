package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.athlete.logic.AreaProgress
import com.cruxcoach.athlete.logic.AreaStatus
import com.cruxcoach.athlete.logic.TrainingBlocks
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.logic.WeeklyVolume
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Injury
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import kotlin.math.max

data class WeeklyVolumeState(
    val loading: Boolean = true,
    val progress: List<AreaProgress> = emptyList(),
    /** An open pause (illness, injury, holiday): the week's targets rest. */
    val paused: Boolean = false,
    /** The athlete's own targets per area (sets; finger: sessions). */
    val own: Map<String, Int> = emptyMap(),
)

@HiltViewModel
class WeeklyVolumeViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(WeeklyVolumeState())
    val state: StateFlow<WeeklyVolumeState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val weekStart = WeeklyVolume.weekStartOf(service.today())
            combine(
                repo.observeProfile(),
                repo.observeRecentWorkouts(30),
                repo.observeActiveInjuries(),
                repo.observeClimbingDaysSince(weekStart.toString()),
            ) { profile, _, injuries, _ -> profile to injuries }
                .collect { (profile, injuries) -> runCatching { compute(profile, injuries) } }
        }
    }

    /** An own weekly target for [area] (sets, finger: sessions), or back to the recommendation with null. */
    fun setOwn(area: VolumeArea, value: Int?) = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        service.repo.updateProfile { p ->
            p.copy(ownWeeklyTargets = if (value == null) p.ownWeeklyTargets - area.name else p.ownWeeklyTargets + (area.name to value))
        }
        runCatching { compute(service.repo.profile(), service.repo.activeInjuries()) }
    }

    /** Logged sets do not touch the observed tables; the tab recomputes when it is shown again. */
    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        runCatching { compute(service.repo.profile(), service.repo.activeInjuries()) }
    }

    private fun compute(profile: AthleteProfile, injuries: List<Injury>) {
        val today = service.today()
        val weekStart = WeeklyVolume.weekStartOf(today)
        val coach = profile.coach
        // Display only: the block without fatigue signals (Today may pull a deload forward).
        val block = TrainingBlocks.state(
            coach.blockStartDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            coach.targetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            today,
        )
        val targets = WeeklyVolume.targets(profile, block, injuries, coach.trainingDaysPerWeek ?: profile.weeklyGoal)
        val repo = service.repo
        val workoutDays = repo.workoutsBetween(weekStart.toString(), today.toString()).associate { it.id to LocalDate.parse(it.day) }
        val startMs = java.time.LocalDate.parse(weekStart.toString()).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val sets = repo.completedSetsSince(startMs).mapNotNull { set -> workoutDays[set.workoutId]?.let { it to set } }
        val activities = service.activities(days = 8)
        _state.value = WeeklyVolumeState(
            loading = false,
            progress = WeeklyVolume.progress(sets, activities, service.catalog, weekStart, targets, today),
            paused = repo.openPause() != null,
            own = profile.ownWeeklyTargets,
        )
    }
}

@Composable
internal fun volumeAreaLabel(area: VolumeArea): String = stringResource(when (area) {
    VolumeArea.FINGER -> R.string.trv_area_finger
    VolumeArea.PULL -> R.string.trv_area_pull
    VolumeArea.PUSH -> R.string.trv_area_push
    VolumeArea.LEGS -> R.string.trv_area_legs
    VolumeArea.CORE -> R.string.trv_area_core
    VolumeArea.ANTAGONIST -> R.string.trv_area_antagonist
    VolumeArea.MOBILITY -> R.string.trv_area_mobility
})

/**
 * "Diese Woche": one compact bar per area — logged work in full colour,
 * what climbing counted in a lighter shade, the weekly range as markers.
 */
@Composable
fun WeeklyVolumeCard(modifier: Modifier = Modifier) {
    val viewModel: WeeklyVolumeViewModel = hiltViewModel(key = "weekly-volume")
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    // Areas the athlete switched off themselves stay listed, so they can be switched on again.
    val rows = s.progress.filter { !it.target.isOff || it.target.own }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Card(modifier.fillMaxWidth().testTag("weekly_volume_card")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trv_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trv_title), stringResource(R.string.trv_info_text))
            }
            when {
                s.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                // During a pause nothing is "behind": the targets wait for the return.
                s.paused -> Text(stringResource(R.string.trv_paused), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp).testTag("weekly_volume_paused"))
                rows.isEmpty() -> Text(stringResource(R.string.trv_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    rows.forEach { row -> VolumeRow(row, onClick = { editing = row.area.name }) }
                    if (rows.any { it.climbingCredit > 0.0 }) {
                        Text(stringResource(R.string.trv_legend), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
    editing?.let { key ->
        s.progress.firstOrNull { it.area.name == key }?.let { p ->
            OwnVolumeTargetDialog(p, s.own[key], onOwn = { viewModel.setOwn(p.area, it) }, onDismiss = { editing = null })
        }
    }
}

/**
 * One area's target: the recommended range or an own number (owner
 * 2026-10-10: "define your targets yourself"); 0 switches the area off.
 */
@Composable
private fun OwnVolumeTargetDialog(p: AreaProgress, ownValue: Int?, onOwn: (Int?) -> Unit, onDismiss: () -> Unit) {
    val finger = p.area == VolumeArea.FINGER
    val rec = p.target.recommended ?: p.target
    val unit = stringResource(if (finger) R.string.trv_unit_sessions else R.string.trv_unit_sets)
    AlertDialog(
        modifier = Modifier.testTag("weekly_volume_edit"),
        onDismissRequest = onDismiss,
        title = { Text(volumeAreaLabel(p.area)) },
        text = {
            com.cruxcoach.android.ui.training.common.TargetChoice(
                label = stringResource(R.string.trn_target_heading), unit = unit,
                recommended = if (finger) rec.minSessions else rec.maxSets, own = ownValue,
                range = if (finger) WeeklyVolume.OWN_FINGER_SESSIONS else WeeklyVolume.OWN_SETS,
                fallback = if (finger) 2 else 8, tag = "own_volume_${p.area.name.lowercase()}", onOwn = onOwn,
                recommendedLabel = if (finger) stringResource(R.string.trv_recommended_sessions, rec.minSessions)
                    else stringResource(R.string.trv_recommended_sets, rec.minSets, rec.maxSets),
                fieldLabel = stringResource(R.string.trv_own_field, unit),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun VolumeRow(p: AreaProgress, onClick: () -> Unit = {}) {
    val finger = p.area == VolumeArea.FINGER
    val label = volumeAreaLabel(p.area)
    val value = when {
        finger -> stringResource(R.string.trv_value_sessions, formatNumber(p.effective), p.goal)
        p.target.minSets == p.target.maxSets -> stringResource(R.string.trv_value_sets_exact, formatNumber(p.effective), p.target.minSets)
        else -> stringResource(R.string.trv_value_sets, formatNumber(p.effective), p.target.minSets, p.target.maxSets)
    }
    val statusText = stringResource(when (p.status) {
        AreaStatus.BELOW -> R.string.trv_status_below
        AreaStatus.ON_TRACK -> R.string.trv_status_on_track
        AreaStatus.DONE -> R.string.trv_status_done
        AreaStatus.OVER -> R.string.trv_status_over
    })
    val (icon, tint) = when (p.status) {
        AreaStatus.DONE -> Icons.Default.CheckCircle to CruxCoachDesign.colors.positive
        AreaStatus.OVER -> Icons.Default.Warning to CruxCoachDesign.colors.caution
        else -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val logged = if (finger) p.sessions.toDouble() else p.doneSets.toDouble()
    val scale = max(1.0, if (finger) max(p.goal.toDouble(), p.effective) else max(p.target.maxSets.toDouble(), p.effective))
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.trv_edit_target), onClick = onClick)
            .padding(vertical = 4.dp).testTag("weekly_volume_${p.area.name.lowercase()}")
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value, $statusText" },
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(104.dp), maxLines = 1)
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val w = size.width
            val h = size.height
            if (w <= 0f) return@Canvas
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(track, size = size, cornerRadius = r)
            val loggedW = (logged / scale).toFloat().coerceIn(0f, 1f) * w
            val creditW = (p.climbingCredit / scale).toFloat().coerceIn(0f, 1f - loggedW / w) * w
            if (loggedW > 0f) drawRoundRect(fill, size = Size(loggedW, h), cornerRadius = r)
            if (creditW > 0f) drawRoundRect(fill.copy(alpha = 0.4f), topLeft = Offset(loggedW, 0f), size = Size(creditW, h), cornerRadius = r)
            // Weekly minimum (and maximum for sets) as thin markers.
            val marks = if (finger) listOf(p.goal.toDouble()) else listOf(p.target.minSets.toDouble(), p.target.maxSets.toDouble())
            marks.filter { it > 0 }.forEach { m ->
                val x = (m / scale).toFloat().coerceIn(0f, 1f) * w
                drawLine(marker, Offset(x, -2f), Offset(x, h + 2f), strokeWidth = 2f)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, modifier = Modifier.widthIn(min = 64.dp))
    }
}
