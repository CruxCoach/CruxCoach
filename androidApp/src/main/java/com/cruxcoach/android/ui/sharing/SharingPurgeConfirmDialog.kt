package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

/**
 * Asks before deleting everything this device holds about one relationship.
 *
 * The button used to run straight from the tap. It sits next to buttons that
 * are reversible, it throws away a signed history, and there is nothing to undo
 * afterwards — so a mis-tap was all it took.
 *
 * The dialog has a second job beyond confirming, which is why the wording is
 * long. "Delete" on a sharing screen reads as "revoke their access", and this
 * is not that: it removes what *this device* stores, it leaves the owner's own
 * content and keys alone, no other relationship changes, and it cannot reach a
 * copy the other person already has. Somebody who reads only this dialog
 * should not come away believing any of those went the other way.
 */
@Composable
fun SharingPurgeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // One description covering scope and irreversibility together. Split over
    // two unlabelled paragraphs, a screen reader hands them out as fragments,
    // and "this cannot be undone" is the half most easily skipped.
    val spoken = stringResource(R.string.sharing_cd_purge_confirm)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sharing_purge_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.sharing_purge_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.sharing_purge_confirm_irreversible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.sharing_purge_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sharing_purge_confirm_cancel))
            }
        },
    )
}
