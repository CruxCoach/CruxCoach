package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    val rows = s.progress.filter { !it.target.isOff }
    Card(modifier.fillMaxWidth().testTag("weekly_volume_card")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trv_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trv_info_title), stringResource(R.string.trv_info_text))
            }
            when {
                s.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                rows.isEmpty() -> Text(stringResource(R.string.trv_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    rows.forEach { VolumeRow(it) }
                    if (rows.any { it.climbingCredit > 0.0 }) {
                        Text(stringResource(R.string.trv_legend), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeRow(p: AreaProgress) {
    val finger = p.area == VolumeArea.FINGER
    val label = volumeAreaLabel(p.area)
    val value = if (finger) stringResource(R.string.trv_value_sessions, formatNumber(p.effective), p.goal)
        else stringResource(R.string.trv_value_sets, formatNumber(p.effective), p.target.minSets, p.target.maxSets)
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("weekly_volume_${p.area.name.lowercase()}")
            .clearAndSetSemantics { contentDescription = "$label: $value, $statusText" },
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
