package com.cruxcoach.android.ui.yearreview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.DarkSurface
import com.cruxcoach.android.ui.theme.OrangeAccent
import androidx.compose.ui.graphics.Color
import kotlinx.datetime.LocalDate

/**
 * The invitation from 1 December to 31 January, at the top of the statistics
 * and on the start screen, with enough data only. Its X hides it for that year;
 * the review stays in the statistics.
 */
@Composable
fun YearReviewEntryCard(onOpen: (Int) -> Unit, modifier: Modifier = Modifier, viewModel: YearReviewEntryViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel) { viewModel.startCard() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val year = state.cardYear ?: return
    val soFar = state.today?.year == year
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(DarkBackground, DarkSurface)))
            .clickable { onOpen(year) }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (soFar) R.string.yr_card_title_so_far else R.string.yr_card_title, year),
                color = OrangeAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.yr_card_text), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { onOpen(year) },
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent, contentColor = DarkBackground),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.yr_card_open), fontWeight = FontWeight.Bold) }
        }
        IconButton(onClick = { viewModel.hideCard(year) }, modifier = Modifier.align(Alignment.Top)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.yr_card_hide), tint = Color.White.copy(alpha = 0.7f))
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
        if (state.years.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.years.forEach { year ->
                    FilterChip(
                        selected = year == selected,
                        onClick = { viewModel.select(year) },
                        label = { Text(year.toString()) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = OrangeAccent, selectedLabelColor = DarkBackground),
                    )
                }
            }
        }
        if (review != null && review.hasEnoughData) {
            val soFar = !review.isFinal
            Button(
                onClick = { onOpen(selected) },
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent, contentColor = DarkBackground),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(if (soFar) R.string.yr_card_title_so_far else R.string.yr_card_title, selected), fontWeight = FontWeight.Bold)
            }
        } else if (review != null) {
            Text(stringResource(R.string.yr_so_far_title, selected), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(soFarText(review), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (YearReviewTestDate.available) TestDates(state.today) { viewModel.setTestDate(it) }
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
