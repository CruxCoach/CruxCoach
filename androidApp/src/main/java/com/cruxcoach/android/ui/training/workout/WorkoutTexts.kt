package com.cruxcoach.android.ui.training.workout

import android.content.res.Resources
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.android.ui.training.routineName
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.PersonalRecord
import com.cruxcoach.athlete.logic.RecordKind
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.UnitSystem
import com.cruxcoach.athlete.model.Workout
import kotlin.math.roundToInt

/** Finishing a training without one done set: nothing would be saved, so offer to discard it. */
@Composable
internal fun NothingDoneDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        modifier = Modifier.testTag("workout_nothing_done"),
        title = { Text(stringResource(R.string.trw_nothing_done_title)) },
        text = { Text(stringResource(R.string.trw_nothing_done_text)) },
        confirmButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.testTag("nothing_done_discard")) {
                Text(stringResource(R.string.trw_discard), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.trw_keep_training)) } },
    )
}

// ── Text helpers shared with the summary and history ────────────────

/** Built-in routines are stored by key; show their localized name. */
@Composable
internal fun workoutTitle(workout: Workout): String {
    val builtin = workout.routineId?.takeIf { it.startsWith("builtin:") }?.removePrefix("builtin:")?.let { BuiltinRoutines.byKey(it) }
    return when {
        builtin != null -> routineName(builtin)
        !workout.title.isNullOrBlank() -> workout.title!!
        else -> stringResource(R.string.trw_workout_default)
    }
}

internal fun massText(resources: Resources, kg: Double, units: UnitSystem, signed: Boolean = false): String {
    val v = Units.massToDisplay(kg, units)
    val t = (if (signed && v > 0) "+" else "") + formatNumber(v)
    return resources.getString(if (units == UnitSystem.IMPERIAL) R.string.tr_format_lb else R.string.tr_format_kg, t)
}

internal fun recordValueText(resources: Resources, def: ExerciseDefinition, record: PersonalRecord, units: UnitSystem): String =
    when (record.kind) {
        RecordKind.LOAD ->
            if (def.load == LoadMode.BODYWEIGHT_PLUS) resources.getString(R.string.trw_record_total, massText(resources, record.value, units))
            else massText(resources, record.value, units)
        RecordKind.ESTIMATED_MAX -> resources.getString(R.string.trw_record_e1rm, massText(resources, record.value, units))
        RecordKind.REPS -> record.value.roundToInt().let { resources.getQuantityString(R.plurals.trw_reps, it, it) }
        RecordKind.DURATION -> resources.getString(R.string.tr_format_seconds, record.value.roundToInt())
    }
