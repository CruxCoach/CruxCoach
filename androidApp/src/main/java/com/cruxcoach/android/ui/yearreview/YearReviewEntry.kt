package com.cruxcoach.android.ui.yearreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.OrangeAccent
import kotlinx.datetime.LocalDate

/**
 * The invitation from 1 December to 31 January, at the top of the statistics
 * and on the start screen, with enough data only. Its X hides it for that year;
 * the review stays in the statistics.
 */
@Composable
fun YearReviewEntryCard(onOpen: (Int) -> Unit, modifier: Modifier = Modifier, viewModel: YearReviewEntryViewModel = hiltViewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.checkCard() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val year = state.cardYear ?: return
    YearReviewCard(year, soFar = state.today?.year == year, onOpen = { onOpen(year) }, onHide = { viewModel.hideCard(year) }, modifier = modifier)
}

/** The card itself: "your year so far" in December, the finished year in January. */
@Composable
internal fun YearReviewCard(year: Int, soFar: Boolean, onOpen: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    // The same card as "At a glance" in the logbook; the orange button is its one accent.
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(if (soFar) R.string.yr_card_title_so_far else R.string.yr_card_title, year),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent, contentColor = DarkBackground),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.yr_card_open), fontWeight = FontWeight.Bold) }
            }
            IconButton(onClick = onHide) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.yr_card_hide), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The review's permanent place in the statistics: a year picker, and below the
 * minimum a short "your year so far" instead of the button. Feature builds can
 * pretend another date to try the December and January cards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YearReviewStatsEntry(onOpen: (Int) -> Unit, viewModel: YearReviewEntryViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel) { viewModel.startStats() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    YearReviewStatsContent(state, onOpen, onSelect = viewModel::select, onTestDate = viewModel::setTestDate)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun YearReviewStatsContent(
    state: YearReviewEntryViewModel.State,
    onOpen: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onTestDate: (LocalDate?) -> Unit,
) {
    val selected = state.selectedYear ?: return
    val review = state.selected
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.yr_stats_entry_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (review != null && review.hasEnoughData) {
            // Only years with a full review are offered; the button opens the one picked.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.years.size > 1) {
                    FlowRow(Modifier.weight(1f).padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.years.forEach { year ->
                            FilterChip(selected = year == selected, onClick = { onSelect(year) }, label = { Text(year.toString()) })
                        }
                    }
                } else {
                    Text(
                        stringResource(if (review.isFinal) R.string.yr_card_title else R.string.yr_card_title_so_far, selected),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
                Button(
                    onClick = { onOpen(selected) },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent, contentColor = DarkBackground),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.yr_card_open), fontWeight = FontWeight.Bold) }
            }
        } else if (review != null) {
            // No year has a full review yet: how far this one got.
            val title = littleTitle(selected, review)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                InfoButton(title, minimumNote())
            }
            if (review.isEmpty()) {
                Text(emptyText(selected, review), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                littleRows(review).forEach { row ->
                    Row {
                        Text(row.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(row.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (YearReviewTestDate.available) TestDates(state.today, onTestDate)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestDates(today: LocalDate?, onPick: (LocalDate?) -> Unit) {
    if (today == null) return
    // The choices follow the real year, not the pretended one.
    val year = java.time.Year.now().value
    val real = YearReviewTestDate.today == null
    Text(stringResource(R.string.yr_test_date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val options = listOf(
            null,
            LocalDate(year, 12, 1),
            LocalDate(year, 12, 24),
            LocalDate(year + 1, 1, 10),
            LocalDate(year + 1, 1, 31),
            LocalDate(year + 1, 2, 1),
        ).distinct()
        options.forEach { day ->
            FilterChip(
                selected = if (day == null) real else YearReviewTestDate.today == day,
                onClick = { onPick(day) },
                label = { Text(day?.toString() ?: stringResource(R.string.yr_test_date_real)) },
            )
        }
    }
}
