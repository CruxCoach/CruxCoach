package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.cruxcoach.athlete.logic.Circuits
import com.cruxcoach.athlete.logic.WorkoutTextParser
import com.cruxcoach.android.ui.training.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.SideMode
import com.cruxcoach.athlete.model.UnitSystem

/**
 * "Type a workout" (owner 2026-10-15: like the food entry; 2026-10-19: as freely as possible, circuits included,
 * typos do not matter): read while typing, with a preview of what each exercise becomes – a catalogue exercise,
 * or a new one the app creates on "Add" – and of the circuits. The text is read off the main thread, a moment
 * after the last keystroke.
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
    var result by remember { mutableStateOf(WorkoutTextParser.Result(null, emptyList())) }
    var readText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text, catalog) {
        if (text.isNotEmpty() && readText != null) delay(READ_DELAY_MS)
        val read = withContext(Dispatchers.Default) { WorkoutTextParser.parse(text, catalog) }
        result = read
        readText = text
    }
    val lang = catalogLanguage()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("workout_text_sheet"),
    ) {
        Column(
            // Above the keyboard: the apply button stays reachable while typing (device test 2026-10-17).
            Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
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
            val items = result.lines.map { it.item }
            result.lines.forEachIndexed { i, line ->
                val kind = line.match?.kind ?: line.guess.kind
                val load = line.match?.load ?: line.guess.load
                val circuit = line.circuit
                // A circuit's members under one line: rounds, the rest between the exercises, the rest after a round.
                if (circuit != null && result.lines.getOrNull(i - 1)?.circuit != circuit) {
                    val members = items.filter { it.circuit == circuit }
                    val rounds = Circuits.rounds(items, circuit)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("workout_text_circuit_$circuit")) {
                        Icon(Icons.Default.Repeat, null, Modifier.size(18.dp), tint = CruxCoachDesign.colors.brandAccent)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.trcirc_group, pluralStringResource(R.plurals.trcirc_rounds, rounds, rounds),
                            formatClock(members.first().circuitRestS ?: Circuits.DEFAULT_CIRCUIT_REST_S),
                            formatClock(members.first().roundRestS ?: Circuits.defaultRoundRest(members, catalog))),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = CruxCoachDesign.colors.brandAccent)
                    }
                }
                Row(verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().padding(start = if (circuit != null) 12.dp else 0.dp).testTag("workout_text_line_$i")) {
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
                // Typed a moment ago and not read yet: read it now, so "Add" takes exactly what is in the field.
                onClick = { onApply(if (readText == text) result else WorkoutTextParser.parse(text, catalog)) },
                enabled = result.lines.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("workout_text_apply"),
            ) {
                Text(if (result.lines.isEmpty()) stringResource(R.string.trwo_text_apply_none)
                    else pluralStringResource(R.plurals.trwo_text_apply, result.lines.size, result.lines.size))
            }
        }
    }
}

/** How long after the last keystroke the text is read again. */
private const val READ_DELAY_MS = 120L

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
