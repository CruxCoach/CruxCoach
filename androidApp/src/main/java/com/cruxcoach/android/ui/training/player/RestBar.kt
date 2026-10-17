package com.cruxcoach.android.ui.training.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.formatClock
import com.cruxcoach.android.ui.training.sideLabel
import com.cruxcoach.athlete.logic.SetOutcomes
import com.cruxcoach.athlete.logic.TargetOutcome
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * The rest as a bar under the page (owner 2026-10-17: the sets stay in view): the countdown,
 * ±15 s and "on". After the last set of an exercise it says how it went and what comes next.
 * The timer is collected here only, so its ticks never recompose the rows above.
 */
@Composable
fun RestBar(
    timerFlow: StateFlow<RestTimerState>,
    state: PlayerState,
    language: String,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
    onEnd: () -> Unit,
) {
    val timer by timerFlow.collectAsStateWithLifecycle()
    val armed = state.restArmed
    val remaining = if (armed) timer.secondsRemaining else 0
    val total = if (armed) timer.totalSeconds.coerceAtLeast(1) else 1
    val progress = if (state.restFinished || !armed) 1f else (remaining.toFloat() / total).coerceIn(0f, 1f)
    val done = state.lastDone
    val next = state.next ?: state.current
    // The exercise is finished and the next set is another one: say how it went, and what follows.
    val finishedExercise = done != null && next != null && done.set.blockIndex != next.set.blockIndex &&
        state.sets.none { !it.isCompleted && it.blockIndex == done.set.blockIndex } && state.pages.none { p ->
            done.set.blockIndex in p.blockIndexes && next.set.blockIndex in p.blockIndexes
        }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag("player_rest")) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.sideSwitch && !state.restFinished) {
                    Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Column(Modifier.weight(1f)) {
                    val headline = when {
                        state.restFinished -> stringResource(R.string.trp_rest_go)
                        finishedExercise -> {
                            val work = state.sets.filter { it.blockIndex == done!!.set.blockIndex && it.setType == SetType.WORK }
                            val onTarget = work.count { s ->
                                SetOutcomes.of(done!!.def, s).main.let { it == TargetOutcome.MET || it == TargetOutcome.OVER }
                            }
                            stringResource(R.string.trpg_exercise_done, done!!.def.name(language), onTarget, work.size)
                        }
                        state.sideSwitch -> state.next?.set?.side?.let { stringResource(R.string.trp_side_switch) + " → " + sideLabel(it) }
                            ?: stringResource(R.string.trp_side_switch)
                        else -> stringResource(R.string.trp_rest_title)
                    }
                    Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(if (finishedExercise) "player_exercise_done" else "player_rest_title"))
                    if (finishedExercise && next != null) {
                        val parts = listOfNotNull(next.def.name(language), targetSummary(next.set, next.def, null, state.profile.units))
                        Text(stringResource(R.string.trp_next_after, parts.joinToString(" · ")), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("player_upcoming"))
                    }
                }
                // TalkBack hears the remainder in 10-s steps, not every tick.
                val announced = if (remaining <= 10) remaining else (remaining / 10) * 10
                val description = stringResource(R.string.trp_rest_remaining_cd, formatClock(announced))
                Text(
                    if (state.restFinished) "✓" else if (armed) formatClock(remaining) else "…",
                    fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    color = when {
                        state.restFinished -> CruxCoachDesign.colors.positive
                        remaining in 1..10 -> CruxCoachDesign.colors.brandAccent
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(start = 12.dp)
                        .semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite }
                        .testTag("player_rest_countdown"),
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(6.dp),
                color = if (remaining in 1..10) CruxCoachDesign.colors.brandAccent else MaterialTheme.colorScheme.primary,
            )
            if (!state.restFinished) {
                // Narrow padding and one line each: at 360 dp "+15 s" broke into two lines.
                val sidePadding = PaddingValues(horizontal = 14.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onMinus, enabled = armed, contentPadding = sidePadding,
                        modifier = Modifier.heightIn(min = 52.dp).testTag("player_rest_minus")) {
                        Text(stringResource(R.string.trp_rest_minus), maxLines = 1, softWrap = false)
                    }
                    Button(onClick = onEnd, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("player_rest_skip")) {
                        Text(stringResource(if (finishedExercise) R.string.trpg_to_next else R.string.trpg_rest_on), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onPlus, enabled = armed, contentPadding = sidePadding,
                        modifier = Modifier.heightIn(min = 52.dp).testTag("player_rest_plus")) {
                        Text(stringResource(R.string.trp_rest_plus), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** "max. 1 rep with +18 kg", "10 s with 74 kg (109 % BW)", "max. 14 reps", "max. 60 s". */
@Composable
internal fun capacityText(def: com.cruxcoach.athlete.catalog.ExerciseDefinition, capacity: com.cruxcoach.athlete.logic.Capacity, units: UnitSystem): String {
    val bw = capacity.bodyweightKg
    return when (capacity.kind) {
        com.cruxcoach.athlete.logic.CapacityKind.E1RM_TOTAL -> {
            val shown = if (def.load == com.cruxcoach.athlete.catalog.LoadMode.BODYWEIGHT_PLUS && bw != null)
                com.cruxcoach.android.ui.training.formatMass(capacity.value - bw, units, signed = true)
            else com.cruxcoach.android.ui.training.formatMass(capacity.value, units)
            stringResource(R.string.trp_capacity_e1rm, shown)
        }
        com.cruxcoach.athlete.logic.CapacityKind.TEN_SECOND_MAX -> {
            val mass = com.cruxcoach.android.ui.training.formatMass(capacity.value, units)
            // Rounded like the set's own "% BW" line (15 kg at 69.5 kg showed 22 % there and 21 % here).
            val pct = bw?.takeIf { it > 0 }?.let { (capacity.value / it * 100).roundToInt() }
            if (pct != null) stringResource(R.string.trp_capacity_ten_pct, mass, stringResource(R.string.tr_format_percent_bw, pct))
            else stringResource(R.string.trp_capacity_ten, mass)
        }
        com.cruxcoach.athlete.logic.CapacityKind.MAX_REPS ->
            androidx.compose.ui.res.pluralStringResource(R.plurals.trp_capacity_reps, capacity.value.toInt(), capacity.value.toInt())
        com.cruxcoach.athlete.logic.CapacityKind.MAX_SECONDS -> stringResource(R.string.trp_capacity_seconds, capacity.value.toInt())
    }
}

/** "Keine Reserve → nächste Sätze −2,5 kg", "Wiederholungen verpasst → nächste Sätze −1 Wdh.". */
@Composable
internal fun adjustmentText(
    adj: com.cruxcoach.athlete.logic.SetAdjustment,
    units: UnitSystem,
    /** One-arm work adjusts only that hand's sets: the line names it (it read "next sets +2 kg" while the other hand stayed). */
    side: com.cruxcoach.athlete.model.Side? = null,
): String {
    val reason = stringResource(when (adj.reason) {
        com.cruxcoach.athlete.logic.AdjustReason.MISSED_REPS -> R.string.trp2_adj_missed
        com.cruxcoach.athlete.logic.AdjustReason.NO_RESERVE -> R.string.trp2_adj_no_reserve
        com.cruxcoach.athlete.logic.AdjustReason.BIG_RESERVE -> R.string.trp2_adj_big_reserve
        com.cruxcoach.athlete.logic.AdjustReason.HOLD_FAILED -> R.string.trp2_adj_hold_failed
        com.cruxcoach.athlete.logic.AdjustReason.HOLD_EASY -> R.string.trp2_adj_hold_easy
    })
    val change = buildList {
        if (kotlin.math.abs(adj.loadDeltaKg) > 1e-9) add(com.cruxcoach.android.ui.training.formatMass(adj.loadDeltaKg, units, signed = true))
        if (adj.repsDelta != 0) add(stringResource(R.string.trp2_adj_reps, adj.repsDelta))
        if (kotlin.math.abs(adj.durationDeltaS) > 1e-9) add(stringResource(R.string.trp2_adj_seconds, kotlin.math.round(adj.durationDeltaS).toInt()))
    }.joinToString(" · ")
    return if (side != null) stringResource(R.string.trp2_adj_line_side, reason, stringResource(
        if (side == com.cruxcoach.athlete.model.Side.LEFT) R.string.trp2_side_left_lower else R.string.trp2_side_right_lower), change)
    else stringResource(R.string.trp2_adj_line, reason, change)
}
