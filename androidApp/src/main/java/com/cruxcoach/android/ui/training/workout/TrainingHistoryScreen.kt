package com.cruxcoach.android.ui.training.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.training.EmptyHint
import com.cruxcoach.android.ui.training.SectionTitle
import com.cruxcoach.android.ui.training.TrainingScaffold
import com.cruxcoach.athlete.model.Workout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class HistoryRow(val workout: Workout, val sets: Int)

data class HistoryState(
    val loading: Boolean = true,
    /** "yyyy-MM" → workouts of that month, newest first. */
    val months: List<Pair<String, List<HistoryRow>>> = emptyList(),
)

@HiltViewModel
class TrainingHistoryViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            repo.observeRecentWorkouts(100).collect { workouts ->
                val rows = workouts.map { w -> HistoryRow(w, repo.setsFor(w.id).count { it.isCompleted }) }
                val months = rows.groupBy { it.workout.day.take(7) }.toSortedMap(compareByDescending { it }).toList()
                _state.update { HistoryState(loading = false, months = months) }
            }
        }
    }
}

@Composable
fun TrainingHistoryScreen(
    onBack: () -> Unit,
    onOpenWorkout: (String) -> Unit,
    viewModel: TrainingHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrainingScaffold(title = stringResource(R.string.trw_history_title), onBack = onBack) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.months.isEmpty() -> EmptyHint(stringResource(R.string.trw_history_empty), Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("history_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                state.months.forEach { (month, rows) ->
                    item(key = "m_$month") { SectionTitle(monthLabel(month)) }
                    items(rows, key = { it.workout.id }) { row ->
                        val w = row.workout
                        val details = buildList {
                            add(formatDay(w.day))
                            w.durationMinutes?.let { add(pluralStringResource(R.plurals.trw_minutes, it, it)) }
                            add(pluralStringResource(R.plurals.trw_sets_done, row.sets, row.sets))
                        }.joinToString(" · ")
                        ListItem(
                            headlineContent = { Text(workoutTitle(w)) },
                            supportingContent = { Text(details) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                            modifier = Modifier.testTag("history_${w.id}").clickable { onOpenWorkout(w.id) },
                        )
                        HorizontalDivider()
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun monthLabel(month: String): String = runCatching {
    YearMonth.parse(month).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
}.getOrDefault(month)
