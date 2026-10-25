package com.cruxcoach.android.ui.training.workout

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.training.catalogLanguage
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.model.Routine
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A start of the athlete's own workout that holds exercises the open injury
 * rules out ([AthleteService.ruledOutByInjury]). The coach warns, the athlete
 * decides (owner 2026-10-25): without them, or "add anyway".
 */
class PendingInjuryStart(
    val exercises: List<ExerciseDefinition>,
    /** Runs the start with the slugs that stay in despite the injury (empty: without them). */
    val start: suspend (despiteInjury: Set<String>) -> Unit,
)

/** Starts at once when nothing is ruled out, else waits in [pending] for the athlete's answer. */
suspend fun startCheckingInjuries(
    service: AthleteService,
    routine: Routine,
    pending: MutableStateFlow<PendingInjuryStart?>,
    start: suspend (despiteInjury: Set<String>) -> Unit,
) {
    val ruledOut = service.ruledOutByInjury(routine)
    if (ruledOut.isEmpty()) start(emptySet()) else pending.value = PendingInjuryStart(ruledOut, start)
}

/** true: add them anyway; false: start without them; null: start nothing. */
suspend fun answerInjuryStart(pending: MutableStateFlow<PendingInjuryStart?>, despite: Boolean?) {
    val p = pending.value ?: return
    pending.value = null
    if (despite != null) p.start(if (despite) p.exercises.map { it.slug }.toSet() else emptySet())
}

@Composable
fun InjuryStartDialog(pending: PendingInjuryStart, onResolve: (despite: Boolean?) -> Unit) {
    val language = catalogLanguage()
    val count = pending.exercises.size
    AlertDialog(
        onDismissRequest = { onResolve(null) },
        title = { Text(stringResource(R.string.trwo_injury_start_title)) },
        text = {
            Text(pluralStringResource(R.plurals.trwo_injury_start_text, count,
                pending.exercises.joinToString(", ") { it.name(language) }, count))
        },
        confirmButton = {
            TextButton(onClick = { onResolve(false) }, modifier = Modifier.testTag("injury_start_without")) {
                Text(pluralStringResource(R.plurals.trwo_injury_start_without, count))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(true) }, modifier = Modifier.testTag("injury_start_anyway")) {
                Text(stringResource(R.string.trwo_add_anyway))
            }
        },
        modifier = Modifier.testTag("injury_start_dialog"),
    )
}
