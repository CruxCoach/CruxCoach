package com.cruxcoach.android.ui.training.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.weightDeltaText
import com.cruxcoach.android.ui.training.weightText
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The progression in words (owner 2026-11-21: "offering the fitting progression is the main feature"): what the last
 * training of an exercise was, what the next one plans, and the step between the two. Today's rows, the player and
 * the summary say it with the same words.
 */

private fun timed(def: ExerciseDefinition) = def.kind == ExerciseKind.TIME || def.kind == ExerciseKind.HANG

private fun loaded(def: ExerciseDefinition) = def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL

/** "3 × 5", "5 · 5 · 4", "3 × 30 s", "8 · 8 · 7 · +5 kg": the work sets of a training; null when they say nothing. */
@Composable
fun setsDoneText(sets: List<ExerciseSet>, def: ExerciseDefinition, units: UnitSystem): String? {
    if (def.kind == ExerciseKind.CLIMB || def.kind == ExerciseKind.INTERVAL) return null
    val values = if (timed(def)) sets.mapNotNull { it.durationS?.roundToInt() } else sets.mapNotNull { it.reps }
    if (values.isEmpty()) return null
    val same = values.distinct().size == 1
    val main = when {
        timed(def) && same -> stringResource(R.string.trpr_sets_seconds, values.size, values.first())
        timed(def) -> stringResource(R.string.trpr_seconds_list, values.joinToString(" · "))
        same -> stringResource(R.string.trpr_sets_reps, values.size, values.first())
        else -> values.joinToString(" · ")
    }
    val loads = sets.mapNotNull { it.loadKg }.distinct()
    val load = loads.singleOrNull()?.takeIf { loaded(def) && (def.load != LoadMode.BODYWEIGHT_PLUS || it != 0.0) }
    return if (load == null) main else main + " · " + weightText(def, load, units)
}

/** "3 × 7", "3 × 35 s", "3 × 7 · +6 kg": a plan in one line; null without a number to show. */
@Composable
fun planText(sets: Int, reps: Int?, seconds: Int?, loadKg: Double?, def: ExerciseDefinition, units: UnitSystem): String? {
    val main = when {
        timed(def) && seconds != null -> stringResource(R.string.trpr_sets_seconds, sets, seconds)
        !timed(def) && reps != null -> stringResource(R.string.trpr_sets_reps, sets, reps)
        else -> return null
    }
    val load = loadKg?.takeIf { loaded(def) && (def.load != LoadMode.BODYWEIGHT_PLUS || it != 0.0) }
    return if (load == null) main else main + " · " + weightText(def, load, units)
}

/** Which way a step goes; the row colours it. */
enum class StepTone { UP, SAME, DOWN }

/**
 * The step from the last training to the plan, one lever: the weight when it moves ("+1 kg"), else the repetitions
 * ("+1 rep") or the seconds ("+5 s"); "same" when nothing moves. Null without a last training to compare with.
 */
@Composable
fun stepText(last: List<ExerciseSet>, reps: Int?, seconds: Int?, loadKg: Double?, def: ExerciseDefinition, units: UnitSystem): Pair<String, StepTone>? {
    if (last.isEmpty() || def.kind == ExerciseKind.CLIMB || def.kind == ExerciseKind.INTERVAL) return null
    val lastLoad = last.mapNotNull { it.loadKg }.maxOrNull()
    if (loaded(def) && loadKg != null && lastLoad != null && abs(loadKg - lastLoad) > 1e-6) {
        return weightDeltaText(def, loadKg - lastLoad, units) to if (loadKg > lastLoad) StepTone.UP else StepTone.DOWN
    }
    val delta = if (timed(def)) {
        val before = last.mapNotNull { it.durationS?.roundToInt() }.minOrNull() ?: return null
        (seconds ?: return null) - before
    } else {
        val before = last.mapNotNull { it.reps }.minOrNull() ?: return null
        (reps ?: return null) - before
    }
    if (delta == 0) return stringResource(R.string.trpr_step_same) to StepTone.SAME
    val amount = abs(delta)
    val text = if (timed(def)) stringResource(R.string.tr_format_seconds, amount) else pluralStringResource(R.plurals.trp_reps_big, amount, amount)
    return (if (delta > 0) "+" else "−") + text to if (delta > 0) StepTone.UP else StepTone.DOWN
}
