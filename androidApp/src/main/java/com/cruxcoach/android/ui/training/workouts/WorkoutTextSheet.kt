package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.catalogLanguage
import com.cruxcoach.android.ui.training.exercises.kindLabel
import com.cruxcoach.android.ui.training.formatMass
import com.cruxcoach.android.ui.training.sideLabel
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.WorkoutTextParser
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.SideMode
import com.cruxcoach.athlete.model.UnitSystem

/**
 * "Type a workout" (owner 2026-10-15: like the food entry): one exercise per
 * line, read while typing, with a preview of what each line becomes – a
 * catalogue exercise, or a new one the app creates on "Add".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutTextSheet(
    catalog: ExerciseCatalog,
    units: UnitSystem,
    onDismiss: () -> Unit,
    onApply: (WorkoutTextParser.Result) -> Unit,
    /** "Workout per Text" in the editor, "Übungen per Text" in an open training. */
    title: String = stringResource(R.string.trwo_text_title),
) {
    var text by rememberSaveable { mutableStateOf("") }
    val result = remember(text, catalog) { WorkoutTextParser.parse(text, catalog) }
    val lang = catalogLanguage()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("workout_text_sheet"),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(WorkoutTextParser.MAX_CHARS) },
                label = { Text(stringResource(R.string.trwo_text_label)) },
                placeholder = { Text(stringResource(R.string.trwo_text_placeholder)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 4,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("workout_text_input"),
            )
            Text(stringResource(R.string.trwo_text_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            result.title?.let {
                Text(stringResource(R.string.trwo_text_name, it), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("workout_text_title"))
            }
            result.lines.forEachIndexed { i, line ->
                val kind = line.match?.kind ?: line.guess.kind
                val load = line.match?.load ?: line.guess.load
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().testTag("workout_text_line_$i")) {
                    Icon(
                        if (line.isNew) Icons.Default.AddCircle else Icons.Default.CheckCircle, null,
                        tint = if (line.isNew) CruxCoachDesign.colors.brandAccent else CruxCoachDesign.colors.positive,
                        modifier = Modifier.padding(top = 2.dp).size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(line.match?.name(lang) ?: line.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(routineItemSummary(line.item, kind, load, units), style = MaterialTheme.typography.bodyMedium)
                        if (line.isNew) {
                            Text(stringResource(R.string.trwo_text_new, kindLabel(kind)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(
                onClick = { onApply(result) },
                enabled = result.lines.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("workout_text_apply"),
            ) {
                Text(if (result.lines.isEmpty()) stringResource(R.string.trwo_text_apply_none)
                    else pluralStringResource(R.plurals.trwo_text_apply, result.lines.size, result.lines.size))
            }
        }
    }
}

/** "5 × 5 · +20 kg", "3 × 60 s", "6 × 7 s on / 3 s off × 6 · 20 mm", "warm-up". */
@Composable
internal fun routineItemSummary(
    item: RoutineItem, kind: ExerciseKind, load: LoadMode, units: UnitSystem,
    /** Only sets and reps or time ("3 × 8"), for a one-line list. */
    short: Boolean = false,
): String {
    val amount = when (kind) {
        ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> {
            val min = item.repsMin
            val max = item.repsMax ?: min
            val reps = if (min != null && max != null && min != max) "$min–$max" else max?.toString()
            reps?.let { "${item.sets} × $it" } ?: "${item.sets} ×"
        }
        ExerciseKind.TIME, ExerciseKind.HANG -> "${item.sets} × " + stringResource(R.string.tr_format_seconds, item.durationS ?: 0)
        ExerciseKind.INTERVAL -> "${item.sets} × " + stringResource(R.string.trw_interval_format, item.workS ?: 7, item.restBetweenS ?: 3, item.repsPerSet ?: 6)
        ExerciseKind.CLIMB -> item.durationS?.let { stringResource(R.string.trwo_minutes, it / 60) }
            ?: pluralStringResource(R.plurals.trp_rounds, item.repsPerSet ?: 4, item.repsPerSet ?: 4)
    }
    if (short) return amount
    val parts = buildList {
        add(amount)
        item.loadKg?.let { kg ->
            when {
                load == LoadMode.BODYWEIGHT_PLUS && kg < 0 -> add(stringResource(R.string.trw_load_assisted, formatMass(-kg, units)))
                load == LoadMode.BODYWEIGHT_PLUS -> add(formatMass(kg, units, signed = true))
                load == LoadMode.EXTERNAL -> add(formatMass(kg, units))
            }
        }
        if (kind == ExerciseKind.HANG || kind == ExerciseKind.INTERVAL) item.edgeMm?.let { add(stringResource(R.string.tr_format_mm, it.toString())) }
        when (item.sides) {
            SideMode.LEFT_ONLY -> add(sideLabel(Side.LEFT))
            SideMode.RIGHT_ONLY -> add(sideLabel(Side.RIGHT))
            SideMode.BOTH -> Unit
        }
        if (item.warmup) add(stringResource(R.string.trwo_text_warmup))
    }
    return parts.joinToString(" · ")
}
