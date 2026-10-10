package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.athlete.logic.DayActivity
import com.cruxcoach.athlete.logic.ReturnReason
import com.cruxcoach.athlete.logic.ReturnState
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.Workout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** What a day of the current week stands for. */
enum class WeekDayStatus { DONE, PLANNED, MISSED, PAUSED, REST, FREE }

data class WeekDayCell(
    val date: LocalDate,
    /** Week-plan entry: routine id ("builtin:<key>" or own), [PLAN_BOARD], [PLAN_REST] or null. */
    val planned: String?,
    val activity: DayActivity?,
    val workouts: List<Workout>,
    val paused: Boolean,
    val isToday: Boolean,
    val status: WeekDayStatus,
)

/** Monday-to-Sunday cells of the week holding [today]: plan, what was done, what was missed. */
internal fun buildWeek(
    today: LocalDate,
    weekPlan: Map<Int, String>,
    activities: Map<String, DayActivity>,
    workouts: List<Workout>,
    pauses: List<PausePeriod>,
): List<WeekDayCell> {
    val monday = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1))
    return (0 until 7).map { offset ->
        val date = monday.plus(DatePeriod(days = offset))
        val key = date.toString()
        val planned = weekPlan[date.dayOfWeek.isoDayNumber]
        val activity = activities[key]
        val dayWorkouts = workouts.filter { it.day == key && !it.isOpen }
        val paused = pauses.any { p ->
            val start = runCatching { LocalDate.parse(p.startDay) }.getOrNull()
            val end = p.endDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
            start != null && date in start..end && date <= today
        }
        val done = activity?.trained == true || dayWorkouts.isNotEmpty()
        val status = when {
            done -> WeekDayStatus.DONE
            paused -> WeekDayStatus.PAUSED
            planned == PLAN_REST -> WeekDayStatus.REST
            planned != null && date < today -> WeekDayStatus.MISSED
            planned != null -> WeekDayStatus.PLANNED
            else -> WeekDayStatus.FREE
        }
        WeekDayCell(date, planned, activity, dayWorkouts, paused, date == today, status)
    }
}

/** "Diese Woche": seven tappable days with plan and status. */
@Composable
fun WeekCalendarCard(
    cells: List<WeekDayCell>,
    routines: List<Routine>,
    onDayClick: (WeekDayCell) -> Unit,
    /** The standard week lives in this card: an empty plan says so, the button edits it. */
    onEditPlan: (() -> Unit)? = null,
    planEmpty: Boolean = false,
) {
    // Without a standard week the seven empty days would only repeat Today's week strip: one line and the button instead.
    val compact = onEditPlan != null && planEmpty
    Card(Modifier.fillMaxWidth().testTag("week_calendar")) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (compact) R.string.trwo_week_plan else R.string.trw_week_title),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val done = cells.count { it.status == WeekDayStatus.DONE }
                val planned = cells.count { it.planned != null && it.planned != PLAN_REST }
                if (planned > 0) {
                    Text(stringResource(R.string.trw_week_progress, done, planned), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!compact) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    cells.forEach { cell -> DayCell(cell, routines, Modifier.weight(1f), onClick = { onDayClick(cell) }) }
                }
            }
            if (onEditPlan != null) {
                if (planEmpty) {
                    Text(stringResource(R.string.trwo_week_plan_empty), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp))
                }
                TextButton(onClick = onEditPlan, modifier = Modifier.padding(start = 0.dp, top = 4.dp).testTag("workouts_week_plan_edit")) {
                    Text(stringResource(R.string.trwo_week_plan_edit))
                }
            }
        }
    }
}

@Composable
private fun DayCell(cell: WeekDayCell, routines: List<Routine>, modifier: Modifier, onClick: () -> Unit) {
    val dayName = weekdayName(cell.date.dayOfWeek.isoDayNumber, TextStyle.SHORT)
    val statusText = statusLabel(cell.status)
    val plan = if (cell.planned == null) null else shortPlanLabel(cell.planned, routines)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .semantics { contentDescription = listOfNotNull(dayName, plan, statusText).joinToString(", ") }
            .testTag("week_day_${cell.date.dayOfWeek.isoDayNumber}"),
    ) {
        Text(dayName, style = MaterialTheme.typography.labelSmall,
            fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.height(4.dp))
        val accent = CruxCoachDesign.colors.brandAccent
        val (bg, fg) = when (cell.status) {
            WeekDayStatus.DONE -> accent to CruxCoachDesign.colors.onBrandAccent
            WeekDayStatus.MISSED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(bg)
                .border(if (cell.isToday) 2.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val icon = when (cell.status) {
                WeekDayStatus.DONE -> Icons.Default.Check
                WeekDayStatus.PAUSED -> Icons.Default.Bedtime
                WeekDayStatus.REST -> Icons.Default.Weekend
                WeekDayStatus.PLANNED, WeekDayStatus.MISSED ->
                    if (cell.planned == PLAN_BOARD) Icons.Default.Terrain else Icons.Default.FitnessCenter
                WeekDayStatus.FREE -> null
            }
            icon?.let { Icon(it, null, Modifier.size(16.dp), tint = fg) }
        }
        Spacer(Modifier.height(2.dp))
        Text(plan ?: "", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
    }
}

/** Plan label short enough for a seventh of the screen. */
@Composable
private fun shortPlanLabel(entry: String, routines: List<Routine>): String = when (entry) {
    PLAN_BOARD -> stringResource(R.string.trw_short_board)
    PLAN_REST -> stringResource(R.string.trw_short_rest)
    else -> planEntryLabel(entry, routines)
}

@Composable
private fun statusLabel(s: WeekDayStatus): String = stringResource(when (s) {
    WeekDayStatus.DONE -> R.string.trw_status_done
    WeekDayStatus.PLANNED -> R.string.trw_status_planned
    WeekDayStatus.MISSED -> R.string.trw_status_missed
    WeekDayStatus.PAUSED -> R.string.trw_status_paused
    WeekDayStatus.REST -> R.string.trw_status_rest
    WeekDayStatus.FREE -> R.string.trw_status_free
})

/**
 * Details of one day: what was planned and done, and what makes sense now —
 * start or adapt a planned workout today or later, log climbing or open the
 * history for a past day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekDaySheet(
    cell: WeekDayCell,
    today: LocalDate,
    routines: List<Routine>,
    plannedRoutine: Routine?,
    onDismiss: () -> Unit,
    onStart: (Routine) -> Unit,
    onAdapt: (Routine) -> Unit,
    onLogClimbing: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val isToday = cell.date == today
    val past = cell.date < today
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("week_day_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            val dateText = java.time.LocalDate.parse(cell.date.toString())
                .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL))
            Text(dateText, style = MaterialTheme.typography.titleMedium)
            Text(statusLabel(cell.status), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            cell.planned?.let { entry ->
                Text(stringResource(R.string.trw_day_planned, planEntryLabel(entry, routines)), style = MaterialTheme.typography.bodyMedium)
            }
            val a = cell.activity
            if (a != null && a.climbingMinutes > 0) {
                Text(pluralStringResource(R.plurals.trt_board_minutes, a.climbingMinutes, a.climbingMinutes),
                    style = MaterialTheme.typography.bodyMedium)
            } else if (a != null && a.climbingEfforts > 0) {
                Text(pluralStringResource(R.plurals.trt_board_efforts, a.climbingEfforts, a.climbingEfforts),
                    style = MaterialTheme.typography.bodyMedium)
            }
            cell.workouts.forEach { w ->
                Text("• " + com.cruxcoach.android.ui.training.workout.workoutTitle(w) +
                    (w.durationMinutes?.let { " · " + pluralStringResource(R.plurals.trt_minutes, it, it) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
            }
            when (cell.status) {
                WeekDayStatus.MISSED -> Text(stringResource(R.string.trw_day_missed), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
                WeekDayStatus.PAUSED -> Text(stringResource(R.string.trw_day_paused), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
                WeekDayStatus.FREE -> if (cell.planned == null && cell.workouts.isEmpty()) {
                    Text(stringResource(R.string.trw_day_nothing), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Unit
            }
            Spacer(Modifier.height(12.dp))
            // Today or later with a workout planned: start it or adapt it.
            if (plannedRoutine != null && (isToday || cell.status == WeekDayStatus.PLANNED)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isToday) {
                        Button(onClick = { onStart(plannedRoutine) }, modifier = Modifier.testTag("week_day_start")) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tr_action_start))
                        }
                    }
                    OutlinedButton(onClick = { onAdapt(plannedRoutine) }, modifier = Modifier.testTag("week_day_adapt")) {
                        Text(stringResource(R.string.trw_action_adapt))
                    }
                }
            }
            if (isToday || past) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onLogClimbing, modifier = Modifier.testTag("week_day_log")) {
                        Icon(Icons.Default.Terrain, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.trw_action_log))
                    }
                    if (past) {
                        TextButton(onClick = onOpenHistory, modifier = Modifier.testTag("week_day_history")) {
                            Icon(Icons.Default.History, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.trw_action_history))
                        }
                    }
                }
            }
        }
    }
}

/** "Wiedereinstieg": the running ramp after a break or a healed finger injury. */
@Composable
fun ReturnBanner(state: ReturnState) {
    val title = when (state.reason) {
        ReturnReason.BREAK_SHORT, ReturnReason.BREAK_LONG ->
            stringResource(R.string.trw_return_break, state.week, state.weeksTotal, state.daysOff)
        ReturnReason.AFTER_ILLNESS -> stringResource(R.string.trw_return_illness, state.daysOff, state.week, state.weeksTotal)
        ReturnReason.FINGER_INJURY_HEALED -> stringResource(R.string.trw_return_finger, state.week, state.weeksTotal)
    }
    val percent = (state.setsFactor * 100).roundToInt()
    val detail = when {
        state.fingerOnly -> stringResource(R.string.trw_return_detail_finger)
        state.noMaxFinger -> stringResource(R.string.trw_return_detail_nomax, percent)
        else -> stringResource(R.string.trw_return_detail, percent)
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("workouts_return_banner"),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Restore, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            InfoButton(stringResource(R.string.trw_return_info_title), stringResource(R.string.trw_return_info))
        }
    }
}
