package com.cruxcoach.android.ui.training.body

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.EmptyHint
import com.cruxcoach.android.ui.training.TrainingScaffold
import com.cruxcoach.android.ui.training.TrainingSnackbarHost
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every body reading, newest first, by month: filtered by group, each one opens the edit dialog of the body page
 * (delete there, with undo). Numbers stay hidden as on the body page.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BodyHistoryScreen(
    onBack: () -> Unit,
    viewModel: BodyHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<BodyMeasurement?>(null) }
    val deletedText = stringResource(R.string.trb_deleted)
    val undoText = stringResource(R.string.trb_undo)

    fun deleteWithUndo(entry: BodyMeasurement) {
        viewModel.delete(entry)
        scope.launch {
            if (snackbar.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                viewModel.restore(entry)
            }
        }
    }

    TrainingScaffold(
        title = stringResource(R.string.trb_history_title),
        onBack = onBack,
        snackbarHost = { TrainingSnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        val units = state.profile.units
        val hide = state.profile.hideBodyNumbers
        val visible = state.visible
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("body_history_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "filters") {
                // Wraps at the largest font instead of pushing a chip off the screen.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    HistoryFilter.entries.forEach { f ->
                        FilterChip(
                            selected = state.filter == f,
                            onClick = { viewModel.setFilter(f) },
                            label = { Text(filterLabel(f)) },
                            modifier = Modifier.testTag("body_history_filter_${f.key}"),
                        )
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyHint(
                        stringResource(if (state.entries.isEmpty()) R.string.trb_no_measurements else R.string.trb_history_empty_filter),
                        Modifier.testTag("body_history_empty"),
                    )
                }
            }
            // Newest month first; "yyyy-MM" of the ISO day.
            visible.groupBy { it.day.take(7) }.forEach { (month, inMonth) ->
                stickyHeader(key = "month_$month") {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            monthLabel(month),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 8.dp).testTag("body_history_month_$month"),
                        )
                    }
                }
                items(inMonth, key = { "h_" + it.day + "_" + it.metric }) { entry ->
                    RecentRow(entry, units, hidden = isHidden(entry.metric, hide), tagPrefix = "body_history") { editing = entry }
                }
            }
        }
    }

    editing?.let { entry ->
        MeasurementDialog(
            initial = entry,
            defaultMetric = BodyMetric.fromKey(entry.metric) ?: BodyMetric.WEIGHT,
            units = state.profile.units,
            today = state.today ?: LocalDate.parse(java.time.LocalDate.now().toString()),
            onSave = { key, day, value, unit -> viewModel.save(key, day, value, unit, entry); editing = null },
            onDelete = { deleteWithUndo(entry); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun filterLabel(f: HistoryFilter): String = stringResource(
    when (f) {
        HistoryFilter.ALL -> R.string.trb_history_all
        HistoryFilter.WEIGHT -> R.string.trb_history_weight
        HistoryFilter.COMPOSITION -> R.string.trb_history_composition
        HistoryFilter.CIRCUMFERENCE -> R.string.trb_history_circumference
    },
)

/** "2026-11" → "November 2026" in the app's language, as in the training history. */
private fun monthLabel(month: String): String = runCatching {
    YearMonth.parse(month).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
}.getOrDefault(month)
