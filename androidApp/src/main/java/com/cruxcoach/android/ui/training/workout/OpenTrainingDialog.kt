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

/** A start that waits for "continue the open training or end it" ([AthleteService.openConflict]). */
class PendingStart(
    val conflict: AthleteService.OpenConflict,
    /** Title of the training about to start; null for an empty one. */
    val newTitle: String?,
    /** Runs the start; with [replace] the open training is ended first. */
    val start: suspend (replace: Boolean) -> Unit,
)

/**
 * Another training is still open: continue it, or end it (kept with what was
 * done, dropped when nothing was) and start the new one. Dismissing starts nothing.
 */
@Composable
fun OpenTrainingDialog(pending: PendingStart, onResolve: (replace: Boolean?) -> Unit) {
    val c = pending.conflict
    val openTitle = c.open.title ?: stringResource(R.string.trw_open_untitled)
    AlertDialog(
        onDismissRequest = { onResolve(null) },
        title = { Text(stringResource(R.string.trw_open_title, openTitle)) },
        text = {
            Text(
                pluralStringResource(R.plurals.trw_open_done, c.totalSets, c.doneSets, c.totalSets) + " " +
                    stringResource(if (c.doneSets == 0) R.string.trw_open_dropped else R.string.trw_open_kept),
            )
        },
        confirmButton = {
            TextButton(onClick = { onResolve(true) }, modifier = Modifier.testTag("open_training_replace")) {
                Text(pending.newTitle?.let { stringResource(R.string.trw_open_replace, it) } ?: stringResource(R.string.trw_open_replace_empty))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(false) }, modifier = Modifier.testTag("open_training_continue")) {
                Text(stringResource(R.string.trw_open_continue, openTitle))
            }
        },
        modifier = Modifier.testTag("open_training_dialog"),
    )
}
