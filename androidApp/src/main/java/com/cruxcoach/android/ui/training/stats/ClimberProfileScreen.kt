package com.cruxcoach.android.ui.training.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.logic.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class ClimberProfileState(
    val loading: Boolean = true,
    val profile: ClimberProfileData? = null,
    val load: LoadStatus? = null,
    val fingerPoints: List<PositionPoint> = emptyList(),
    val pullPoints: List<PositionPoint> = emptyList(),
)

@HiltViewModel
class ClimberProfileViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(ClimberProfileState())
    val state: StateFlow<ClimberProfileState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val profile = service.climberProfile()
            val load = service.loadStatus()
            _state.value = ClimberProfileState(
                loading = false,
                profile = profile,
                load = load,
                fingerPoints = positionPoints(profile, profile.finger),
                pullPoints = positionPoints(profile, profile.pull),
            )
        }
    }

    /** Each measurement at the working grade of its week (the newest earlier week, else today's anchor). */
    private fun positionPoints(p: ClimberProfileData, values: List<StrengthPoint>): List<PositionPoint> = values.mapNotNull { v ->
        val week = PerformanceProfiles.weekStart(v.day)
        val grade = p.gradeTimeline.lastOrNull { it.weekStart <= week }?.difficulty ?: p.summary.workingDifficulty
        grade?.let { PositionPoint(it, v.pctBw, v.day, v.source == StrengthSource.ESTIMATE) }
    }
}

private enum class PositionTab { FINGER, PULL }

/**
 * "Kletterprofil": how the working grade moves, how strong fingers and
 * pulling are relative to body weight, where that sits among climbers of the
 * same grade, how grade and finger strength move together, and where the
 * load stands. Orientation, never a target.
 */
@Composable
fun ClimberProfileScreen(
    onBack: () -> Unit,
    onOpenBenchmarks: () -> Unit,
    onLogClimbing: () -> Unit,
    viewModel: ClimberProfileViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (entered) viewModel.reload() else entered = true }
    TrainingScaffold(title = stringResource(R.string.trl_profile_title), onBack = onBack) { padding ->
        val p = s.profile
        if (s.loading || p == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("climber_profile_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeadlineTiles(p) }
            if (p.isEmpty) {
                item { EmptyProfile(onOpenBenchmarks, onLogClimbing) }
            }

            // ── Position in the grade band ─────────────────────────────
            item { SectionTitle(stringResource(R.string.trl_position_title)) }
            item { PositionSection(p, s.fingerPoints, s.pullPoints, onOpenBenchmarks, onLogClimbing) }

            // ── Grade and finger strength over time ────────────────────
            item { SectionTitle(stringResource(R.string.trl_timeline_title)) }
            item { TimelineSection(p, onOpenBenchmarks) }

            // ── Flash, working grade, hardest send ─────────────────────
            if (p.summary.workingDifficulty != null) {
                item { SectionTitle(stringResource(R.string.trl_gap_title)) }
                item { GapSection(p) }
            }

            // ── One-arm pick-up per hand ───────────────────────────────
            if (p.pickupLeft.isNotEmpty() || p.pickupRight.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.trl_pickup_title)) }
                item { PickupSection(p) }
            }

            // ── Load ───────────────────────────────────────────────────
            s.load?.let { load ->
                item { SectionTitle(stringResource(R.string.trl_load_title)) }
                item { LoadSection(load, onLogClimbing) }
            }

            // ── Bottlenecks ────────────────────────────────────────────
            item { SectionTitle(stringResource(R.string.trl_bottleneck_title)) }
            item {
                BottleneckBars(p.bottlenecks.map { b ->
                    BottleneckBar(bottleneckLabel(b.area), b.score, bottleneckReason(b))
                }, Modifier.fillMaxWidth().testTag("profile_bottlenecks"))
            }
            item {
                Text(stringResource(R.string.trl_disclaimer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun HeadlineTiles(p: ClimberProfileData) {
    val dash = "–"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.trl_tile_working), p.summary.workingDifficulty?.let(::fontLabel) ?: dash, Modifier.weight(1f),
                supporting = if (p.summary.sampleSize > 0) stringResource(R.string.trl_tile_working_sub, p.summary.sampleSize) else null)
            StatTile(stringResource(R.string.trl_tile_flash), p.summary.flashDifficulty?.let(::fontLabel) ?: dash, Modifier.weight(1f),
                supporting = p.summary.maxDifficulty?.let { stringResource(R.string.trl_tile_max_sub, fontLabel(it)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.trl_tile_finger), p.finger.lastOrNull()?.let { pct(it.pctBw) } ?: dash, Modifier.weight(1f),
                supporting = stringResource(R.string.trl_tile_finger_sub))
            StatTile(stringResource(R.string.trl_tile_pull), p.pull.lastOrNull()?.let { pct(it.pctBw) } ?: dash, Modifier.weight(1f),
                supporting = stringResource(R.string.trl_tile_pull_sub))
        }
    }
}

private fun pct(v: Double): String = "${v.roundToInt()} %"

@Composable
private fun EmptyProfile(onOpenBenchmarks: () -> Unit, onLogClimbing: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Insights, contentDescription = null, tint = CruxCoachDesign.colors.brandAccent)
            Text(stringResource(R.string.trl_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.trl_empty_text), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenBenchmarks, modifier = Modifier.testTag("profile_open_benchmarks")) {
                    Text(stringResource(R.string.trl_action_values))
                }
                OutlinedButton(onClick = onLogClimbing, modifier = Modifier.testTag("profile_log_climbing")) {
                    Text(stringResource(R.string.trl_action_log_climbing))
                }
            }
        }
    }
}

@Composable
private fun PositionSection(
    p: ClimberProfileData,
    fingerPoints: List<PositionPoint>,
    pullPoints: List<PositionPoint>,
    onOpenBenchmarks: () -> Unit,
    onLogClimbing: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(PositionTab.FINGER) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == PositionTab.FINGER, onClick = { tab = PositionTab.FINGER },
                label = { Text(stringResource(R.string.trl_tab_finger)) }, modifier = Modifier.testTag("profile_tab_finger"))
            FilterChip(selected = tab == PositionTab.PULL, onClick = { tab = PositionTab.PULL },
                label = { Text(stringResource(R.string.trl_tab_pull)) }, modifier = Modifier.testTag("profile_tab_pull"))
        }
        val metric = if (tab == PositionTab.FINGER) GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM else GradeStrengthNorms.Metric.PULL_UP_1RM
        val points = if (tab == PositionTab.FINGER) fingerPoints else pullPoints
        val position = if (tab == PositionTab.FINGER) p.fingerPosition else p.pullPosition
        Text(stringResource(if (tab == PositionTab.FINGER) R.string.trl_position_finger_axis else R.string.trl_position_pull_axis),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            points.isEmpty() && (if (tab == PositionTab.FINGER) p.finger else p.pull).isEmpty() -> {
                Text(stringResource(if (tab == PositionTab.FINGER) R.string.trl_position_no_finger else R.string.trl_position_no_pull),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onOpenBenchmarks) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.trl_action_values))
                }
            }
            points.isEmpty() -> {
                Text(stringResource(R.string.trl_position_no_grade), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onLogClimbing) { Text(stringResource(R.string.trl_action_log_climbing)) }
            }
            else -> {
                val newest = points.maxBy { it.day.toEpochDays() }
                val label = stringResource(R.string.trl_position_you, pct(newest.pctBw), fontLabel(newest.difficulty))
                GradePositionChart(metric, points, label,
                    stringResource(R.string.trl_position_cd, pct(newest.pctBw), fontLabel(newest.difficulty), points.size),
                    Modifier.fillMaxWidth().testTag("profile_position_chart"))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Legend(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), stringResource(R.string.trl_legend_band))
                    Legend(CruxCoachDesign.colors.brandAccent, stringResource(R.string.trl_legend_you))
                }
                position?.let { pos -> Text(positionReading(pos), style = MaterialTheme.typography.bodyMedium) }
                if (points.any { it.estimated }) {
                    Text(stringResource(R.string.trl_position_estimated), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun positionReading(pos: NormPosition): String {
    val typical = fontLabel(pos.typicalDifficulty)
    val value = pct(pos.latestPctBw)
    val subject = stringResource(if (pos.metric == GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM) R.string.trl_subject_finger else R.string.trl_subject_pull)
    val working = pos.workingDifficulty ?: return stringResource(R.string.trl_reading_no_grade, subject, value, typical)
    val climb = fontLabel(working)
    return when (pos.position) {
        GradeStrengthNorms.Position.BELOW -> stringResource(R.string.trl_reading_below, subject, value, typical, climb)
        GradeStrengthNorms.Position.ABOVE -> stringResource(R.string.trl_reading_above, subject, value, typical, climb)
        else -> stringResource(R.string.trl_reading_within, subject, value, climb)
    }
}

@Composable
private fun TimelineSection(p: ClimberProfileData, onOpenBenchmarks: () -> Unit) {
    val colors = seriesColors()
    val gradeLabel = stringResource(R.string.trl_timeline_grade)
    val fingerLabel = stringResource(R.string.trl_timeline_finger)
    val rows = buildList {
        add(TimelineRow(gradeLabel, colors[0], p.gradeTimeline.map { it.weekStart to it.difficulty }) { fontLabel(it) })
        add(TimelineRow(fingerLabel, colors[1], p.finger.filter { it.source != StrengthSource.ESTIMATE }.map { it.day to it.pctBw }) { pct(it) })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.gradeTimeline.isEmpty() && p.finger.isEmpty()) {
            Text(stringResource(R.string.trl_timeline_empty), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenBenchmarks) { Text(stringResource(R.string.trl_action_values)) }
            return@Column
        }
        AlignedTimeline(rows, stringResource(R.string.trl_timeline_cd, p.gradeTimeline.size, p.finger.size),
            Modifier.fillMaxWidth().testTag("profile_timeline"))
        val c = p.correlation
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val r = c.r
                if (r == null) {
                    Text(stringResource(R.string.trl_corr_waiting, c.missingWeeks, c.pairedWeeks), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.trl_corr_value, formatNumber(r, 2), c.pairedWeeks),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("profile_corr"))
                    Text(correlationReading(r), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.trl_corr_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun correlationReading(r: Double): String = stringResource(when {
    r >= 0.7 -> R.string.trl_corr_strong
    r >= 0.4 -> R.string.trl_corr_clear
    r >= 0.2 -> R.string.trl_corr_weak
    r > -0.2 -> R.string.trl_corr_none
    else -> R.string.trl_corr_negative
})

@Composable
private fun GapSection(p: ClimberProfileData) {
    val sum = p.summary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GradeGapGauge(
            sum.flashDifficulty, sum.workingDifficulty, sum.maxDifficulty,
            Triple(stringResource(R.string.trl_gap_flash), stringResource(R.string.trl_gap_working), stringResource(R.string.trl_gap_max)),
            stringResource(R.string.trl_gap_cd, sum.flashDifficulty?.let(::fontLabel) ?: "–", sum.workingDifficulty?.let(::fontLabel) ?: "–"),
            Modifier.fillMaxWidth().testTag("profile_gap"),
        )
        p.flashGap?.let { gap ->
            val steps = gap.roundToInt()
            Text(stringResource(when {
                gap <= 1.0 -> R.string.trl_gap_small
                gap >= 5.0 -> R.string.trl_gap_large
                else -> R.string.trl_gap_normal
            }, steps), style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.trl_attempts_tile), p.attemptsPerSend?.let { formatNumber(it, 1) } ?: "–", Modifier.weight(1f),
                supporting = stringResource(R.string.trl_attempts_sub))
            val topAngle = p.angleDistribution.maxByOrNull { it.value }?.key
            StatTile(stringResource(R.string.trl_angle_tile), topAngle?.let { "$it°" } ?: "–", Modifier.weight(1f),
                supporting = sum.primaryBrand?.replaceFirstChar { it.uppercase() })
        }
    }
}

@Composable
private fun PickupSection(p: ClimberProfileData) {
    val colors = seriesColors()
    val left = p.pickupLeft.map { it.day to it.pctBw }
    val right = p.pickupRight.map { it.day to it.pctBw }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AlignedTimeline(listOf(
            TimelineRow(stringResource(R.string.tr_side_left), colors[0], left) { pct(it) },
            TimelineRow(stringResource(R.string.tr_side_right), colors[1], right) { pct(it) },
        ), stringResource(R.string.trl_pickup_cd), Modifier.fillMaxWidth(), rowHeight = 72.dp)
        val l = p.pickupLeft.lastOrNull()?.pctBw
        val r = p.pickupRight.lastOrNull()?.pctBw
        if (l != null && r != null && l > 0 && r > 0) {
            val diff = (abs(l - r) / maxOf(l, r) * 100).roundToInt()
            Text(
                if (diff <= 5) stringResource(R.string.trl_pickup_balanced)
                else stringResource(R.string.trl_pickup_imbalance, stringResource(if (l > r) R.string.tr_side_left else R.string.tr_side_right), diff),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LoadSection(load: LoadStatus, onLogClimbing: () -> Unit) {
    var structure by rememberSaveable { mutableStateOf(LoadStructure.FINGER) }
    Column(Modifier.testTag("profile_load"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadStructure.entries.forEach { st ->
                val trend = load.structures[st]?.trend ?: LoadTrend.LOW
                FilterChip(
                    selected = structure == st,
                    onClick = { structure = st },
                    label = { Text(structureLabel(st) + " · " + trendLabel(trend)) },
                    modifier = Modifier.testTag("profile_load_${st.name.lowercase()}"),
                )
            }
        }
        val series = load.series[structure].orEmpty()
        val sl = load.structures[structure]
        LoadTrendChart(series, stringResource(R.string.trl_load_cd, structureLabel(structure), trendLabel(sl?.trend ?: LoadTrend.LOW)),
            Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(CruxCoachDesign.colors.brandAccent, stringResource(R.string.trl_legend_acute))
            Legend(MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.trl_legend_chronic))
        }
        sl?.let { Text(trendExplanation(it), style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(R.string.trl_load_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onLogClimbing, modifier = Modifier.testTag("profile_load_log")) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.trl_action_log_climbing))
        }
    }
}

@Composable
fun structureLabel(s: LoadStructure): String = stringResource(when (s) {
    LoadStructure.FINGER -> R.string.trl_structure_finger
    LoadStructure.SKIN -> R.string.trl_structure_skin
    LoadStructure.SHOULDER -> R.string.trl_structure_shoulder
})

@Composable
fun trendLabel(t: LoadTrend): String = stringResource(when (t) {
    LoadTrend.LOW -> R.string.trl_trend_low
    LoadTrend.NORMAL -> R.string.trl_trend_normal
    LoadTrend.RISING -> R.string.trl_trend_rising
    LoadTrend.SPIKE -> R.string.trl_trend_spike
})

@Composable
private fun trendExplanation(l: StructureLoad): String {
    val ratio = l.ratio
    return when (l.trend) {
        LoadTrend.SPIKE -> stringResource(R.string.trl_trend_spike_text, ((ratio ?: 1.5) * 100 - 100).roundToInt())
        LoadTrend.RISING -> if (ratio != null) stringResource(R.string.trl_trend_rising_text, (ratio * 100 - 100).roundToInt())
            else stringResource(R.string.trl_trend_new_text)
        LoadTrend.NORMAL -> stringResource(R.string.trl_trend_normal_text)
        LoadTrend.LOW -> stringResource(R.string.trl_trend_low_text)
    }
}

@Composable
private fun bottleneckLabel(a: BottleneckArea): String = stringResource(when (a) {
    BottleneckArea.FINGER -> R.string.trl_area_finger
    BottleneckArea.PULL -> R.string.trl_area_pull
    BottleneckArea.POWER -> R.string.trl_area_power
    BottleneckArea.ENDURANCE -> R.string.trl_area_endurance
    BottleneckArea.CORE -> R.string.trl_area_core
})

@Composable
private fun bottleneckReason(b: Bottleneck): String = stringResource(when (b.reason) {
    BottleneckReason.BELOW_TYPICAL -> R.string.trl_reason_below
    BottleneckReason.WITHIN_TYPICAL -> if (b.area == BottleneckArea.POWER) R.string.trl_reason_gap_normal else R.string.trl_reason_within
    BottleneckReason.ABOVE_TYPICAL -> R.string.trl_reason_above
    BottleneckReason.LARGE_FLASH_GAP -> R.string.trl_reason_large_gap
    BottleneckReason.SMALL_FLASH_GAP -> R.string.trl_reason_small_gap
    BottleneckReason.NO_NORM -> R.string.trl_reason_no_norm
    BottleneckReason.NO_DATA -> when (b.area) {
        BottleneckArea.FINGER -> R.string.trl_reason_no_finger
        BottleneckArea.PULL -> R.string.trl_reason_no_pull
        BottleneckArea.POWER -> R.string.trl_reason_no_grade
        BottleneckArea.ENDURANCE -> R.string.trl_reason_no_endurance
        BottleneckArea.CORE -> R.string.trl_reason_no_core
    }
})
