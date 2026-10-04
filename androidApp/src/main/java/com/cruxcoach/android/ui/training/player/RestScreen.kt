package com.cruxcoach.android.ui.training.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.formatClock
import com.cruxcoach.android.ui.training.sideLabel
import com.cruxcoach.android.ui.training.workout.recordValueText
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.UnitSystem

/**
 * The rest between two sets — its own screen, deliberately unlike the board
 * playlist: one large countdown ring, ±15 s, "end rest", what comes next, the
 * reserve question for the set just done and a quiet line when a best or a
 * performance value moved. A side switch gets its own headline.
 */
@Composable
fun RestScreen(
    timer: RestTimerState,
    state: PlayerState,
    language: String,
    onPlus: () -> Unit,
    onMinus: () -> Unit,
    onEnd: () -> Unit,
    onReserve: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val units = state.profile.units
    val armed = state.restArmed
    val remaining = if (armed) timer.secondsRemaining else 0
    val total = if (armed) timer.totalSeconds.coerceAtLeast(1) else 1
    val progress = if (state.restFinished || !armed) 1f else (remaining.toFloat() / total).coerceIn(0f, 1f)
    val ringColor = when {
        state.restFinished -> CruxCoachDesign.colors.positive
        remaining in 1..10 -> CruxCoachDesign.colors.brandAccent
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("player_rest"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Headline: rest, side switch, or go.
        val headline = when {
            state.restFinished -> stringResource(R.string.trp_rest_go)
            state.sideSwitch -> stringResource(R.string.trp_side_switch)
            else -> stringResource(R.string.trp_rest_title)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.sideSwitch && !state.restFinished) {
                Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(headline, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.sideSwitch) {
            state.next?.set?.side?.let { side ->
                Text(stringResource(R.string.trp_side_switch_to, sideLabel(side)), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))

        // Countdown ring. TalkBack hears the remainder in 10-s steps, not every tick.
        val announced = if (remaining <= 10) remaining else (remaining / 10) * 10
        val ringDescription = stringResource(R.string.trp_rest_remaining_cd, formatClock(announced))
        Box(
            Modifier.size(240.dp).semantics {
                contentDescription = ringDescription
                liveRegion = LiveRegionMode.Polite
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 18.dp.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                drawArc(trackColor, 0f, 360f, false, topLeft, Size(diameter, diameter), style = Stroke(stroke))
                drawArc(ringColor, -90f, 360f * progress, false, topLeft, Size(diameter, diameter),
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(
                if (state.restFinished) "✓" else if (armed) formatClock(remaining) else "…",
                fontSize = 64.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("player_rest_countdown"),
            )
        }
        Spacer(Modifier.height(20.dp))

        if (!state.restFinished) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onMinus, enabled = armed, modifier = Modifier.heightIn(min = 56.dp).testTag("player_rest_minus")) {
                    Text(stringResource(R.string.trp_rest_minus))
                }
                Button(onClick = onEnd, modifier = Modifier.heightIn(min = 56.dp).testTag("player_rest_skip")) {
                    Text(stringResource(R.string.trp_rest_skip))
                }
                OutlinedButton(onClick = onPlus, enabled = armed, modifier = Modifier.heightIn(min = 56.dp).testTag("player_rest_plus")) {
                    Text(stringResource(R.string.trp_rest_plus))
                }
            }
        }

        // Quiet feedback for the set just done.
        state.feedback?.let { fb ->
            Spacer(Modifier.height(16.dp))
            val resources = LocalResources.current
            Card(
                colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.positiveContainer,
                    contentColor = CruxCoachDesign.colors.onPositiveContainer),
                modifier = Modifier.fillMaxWidth().testTag("player_feedback"),
            ) {
                Column(Modifier.padding(12.dp)) {
                    fb.record?.let { record ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.EmojiEvents, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.trp_record, recordValueText(resources, fb.def, record, units)),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    fb.raisedCapacity?.let { capacity ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.TrendingUp, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.trp_benchmark_raised, capacityText(fb.def, capacity, units)),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // Reserve question (MCI's "reps in the tank") for the set just done.
        state.lastDone?.let { done ->
            val asks = done.set.setType != SetType.WARMUP &&
                done.def.kind in setOf(ExerciseKind.REPS, ExerciseKind.LOAD_REPS, ExerciseKind.HANG)
            if (asks && !state.restFinished) {
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(if (done.def.kind == ExerciseKind.HANG) R.string.trp_rir_question_seconds else R.string.trp_rir_question_reps),
                    style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 2, 3).forEach { value ->
                        FilterChip(
                            selected = done.set.rir == value,
                            onClick = { onReserve(value) },
                            label = { Text(if (value == 3) stringResource(R.string.trw_rir_3plus) else value.toString()) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("player_rir_$value"),
                        )
                    }
                }
            }
        }

        // What comes next.
        state.next?.let { next ->
            Spacer(Modifier.height(24.dp))
            OutlinedCard(Modifier.fillMaxWidth().testTag("player_next_preview")) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.trp_next_up), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(next.def.name(language), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val parts = buildList {
                        add(stringResource(R.string.trp_set_of, next.position.setNumber, next.position.setsInBlock))
                        next.set.side?.let { add(sideLabel(it)) }
                        targetSummary(next.set, next.def, state.bodyweight, units)?.let { add(it) }
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** "e1RM +18 kg", "10-s-Max 74 kg (109 % BW)", "max. 14 reps", "max. 60 s". */
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
            val pct = bw?.takeIf { it > 0 }?.let { (capacity.value / it * 100).toInt() }
            if (pct != null) stringResource(R.string.trp_capacity_ten_pct, mass, stringResource(R.string.tr_format_percent_bw, pct))
            else stringResource(R.string.trp_capacity_ten, mass)
        }
        com.cruxcoach.athlete.logic.CapacityKind.MAX_REPS ->
            androidx.compose.ui.res.pluralStringResource(R.plurals.trp_capacity_reps, capacity.value.toInt(), capacity.value.toInt())
        com.cruxcoach.athlete.logic.CapacityKind.MAX_SECONDS -> stringResource(R.string.trp_capacity_seconds, capacity.value.toInt())
    }
}
