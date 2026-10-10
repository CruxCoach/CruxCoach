package com.cruxcoach.android.ui.training.coach

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.cruxcoach.athlete.logic.BenchmarkMath
import com.cruxcoach.athlete.logic.LearningState
import com.cruxcoach.athlete.model.SetType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Distinct earlier trainings with completed work sets of [slug]; the open training does not count yet. */
internal fun workSessionCount(service: AthleteService, slug: String): Int {
    val open = service.repo.openWorkout()?.id
    return service.repo.history(slug, 200)
        .filter { it.isCompleted && it.setType == SetType.WORK && it.workoutId != open }
        .map { it.workoutId }.distinct().size
}

@HiltViewModel
class LearningBadgeViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow<LearningState.State?>(null)
    val state: StateFlow<LearningState.State?> = _state.asStateFlow()
    /** Nothing known, and the load is a careful start guess ([com.cruxcoach.athlete.logic.StartEstimate]). */
    private val _guessed = MutableStateFlow(false)
    val guessed: StateFlow<Boolean> = _guessed.asStateFlow()
    private var loaded: String? = null

    fun load(slug: String) {
        if (loaded == slug) return
        loaded = slug
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val def = service.catalog[slug]
            // Only exercises whose loads come from a performance value have something to learn.
            // Stretches and warm-ups have nothing to learn ("how many more would have gone" means nothing there).
            val noValue = def == null || BenchmarkMath.capacityKind(def) == null ||
                def.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.MOBILITY ||
                def.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.WARMUP
            if (noValue) { _state.value = LearningState.State.Known; return@launch }
            val learning = LearningState.of(slug, service.repo.benchmarks(slug), workSessionCount(service, slug))
            _guessed.value = learning == LearningState.State.None && service.startEstimate(def) != null
            _state.value = learning
        }
    }
}

/**
 * Small hint in the guided player while a starting value is still being
 * learnt (MCI's first week, climber version): the reps-in-reserve answer
 * after the set is what teaches the coach.
 */
@Composable
fun LearningBadge(slug: String, modifier: Modifier = Modifier) {
    val viewModel: LearningBadgeViewModel = hiltViewModel(key = "learning-$slug")
    LaunchedEffect(slug) { viewModel.load(slug) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val guessed by viewModel.guessed.collectAsStateWithLifecycle()
    // One line on every work set, the explanation behind the info icon (device test 2026-10-10: five lines above each set).
    val (short, text) = when (val s = state) {
        null, LearningState.State.Known -> return
        LearningState.State.None -> if (guessed) stringResource(R.string.trc_learning_guess_short) to stringResource(R.string.trc_learning_guess)
            else stringResource(R.string.trc_learning_none_short) to stringResource(R.string.trc_learning_none)
        LearningState.State.Estimated -> stringResource(R.string.trc_learning_estimated_short) to stringResource(R.string.trc_learning_estimated)
        is LearningState.State.Learning -> stringResource(R.string.trc_learning_progress_short, s.sessions, s.needed) to
            stringResource(R.string.trc_learning_progress, s.sessions, s.needed)
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().testTag("player_learning_badge"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(short, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f))
            com.cruxcoach.android.ui.common.InfoButton(short, text)
        }
    }
}

/** Short label for lists: "geschätzt", "wird gelernt 1/2", "bekannt". */
@Composable
fun learningLabel(state: LearningState.State): String? = when (state) {
    LearningState.State.None -> null
    LearningState.State.Estimated -> stringResource(R.string.trc_badge_estimated)
    is LearningState.State.Learning -> stringResource(R.string.trc_badge_learning, state.sessions, state.needed)
    LearningState.State.Known -> stringResource(R.string.trc_badge_known)
}
