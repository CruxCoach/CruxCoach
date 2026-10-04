package com.cruxcoach.android.ui.training.stats

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.cruxcoach.android.ui.training.catalogLanguage
import com.cruxcoach.android.ui.training.sideLabel
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.ExerciseProgress
import com.cruxcoach.athlete.logic.ProgressStats
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Side
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import javax.inject.Inject

@HiltViewModel
class ExerciseProgressViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    data class CardState(
        val loaded: Boolean = false,
        val def: ExerciseDefinition? = null,
        val progress: ExerciseProgress? = null,
        val profile: AthleteProfile = AthleteProfile(),
    )
    private val _state = MutableStateFlow(CardState())
    val state: StateFlow<CardState> = _state.asStateFlow()
    private var loadedSlug: String? = null

    fun load(slug: String) {
        if (loadedSlug == slug) return
        loadedSlug = slug
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val def = service.catalog.fallbackFor(slug)
            val progress = ProgressStats.exerciseProgress(def, service.repo.history(slug, AthleteService.HISTORY_LIMIT),
                TimeZone.currentSystemDefault(), service.currentBodyweight())
            _state.value = CardState(true, def, progress, service.repo.profile())
        }
    }
}

/** Compact progress chart for the exercise detail page, with the way into the full statistics. */
@Composable
fun ExerciseProgressCard(slug: String, onOpenStats: () -> Unit) {
    val viewModel: ExerciseProgressViewModel = hiltViewModel(key = "progress-$slug")
    LaunchedEffect(slug) { viewModel.load(slug) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val def = s.def ?: return
    val progress = s.progress ?: return
    if (!s.loaded) return
    val lang = catalogLanguage()
    val colors = seriesColors()
    Card(Modifier.fillMaxWidth().testTag("exercise_progress_card")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Insights, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trs_progress_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            if (progress.sessions.isEmpty()) {
                Text(stringResource(R.string.trs_progress_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                return@Column
            }
            val units = s.profile.units
            val sides: List<Side?> = if (def.unilateral) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
            val series = sides.mapIndexedNotNull { i, side ->
                val points = progress.sessions.mapNotNull { p -> (if (side == null) p.bestDisplay else p.display[side])?.let { p.day to it } }
                if (points.isEmpty()) null else ChartSeries(side?.let { sideLabel(it) } ?: capacityLabel(progress.kind), colors[i % colors.size], points)
            }
            progress.last?.let { last ->
                Text(capacityLabel(progress.kind) + ": " + capacityText(def, progress.kind, last, units) +
                    (progress.change28?.let { " · " + changeText(def, progress.kind, it, units) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (series.isNotEmpty()) {
                DateLineChart(series, { capacityText(def, progress.kind, it, units) },
                    stringResource(R.string.trs_capacity_cd, def.name(lang), progress.sessionCount),
                    Modifier.fillMaxWidth().padding(top = 8.dp), height = 110.dp)
            }
            TextButton(onClick = onOpenStats, modifier = Modifier.testTag("exercise_progress_open")) {
                Text(stringResource(R.string.trs_open_stats))
            }
        }
    }
}
