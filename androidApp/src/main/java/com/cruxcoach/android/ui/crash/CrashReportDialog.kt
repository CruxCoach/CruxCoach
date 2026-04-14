package com.cruxcoach.android.ui.crash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.util.Log
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import kotlinx.coroutines.launch

/**
 * @param onSend Suspend function that sends the crash report. Returns true on success.
 * @param onDismiss Called when user dismisses without sending (deletes report).
 * @param onSendResult Called after send attempt with success/failure.
 * @param onRememberChoice Called when "Nicht mehr fragen" is checked. Boolean = user chose to send (true) or not (false).
 */
@Composable
internal fun CrashReportDialog(
    reportText: String,
    onSend: suspend () -> Boolean,
    onDismiss: () -> Unit,
    onSendResult: (success: Boolean) -> Unit,
    onRememberChoice: (optIn: Boolean) -> Unit
) {
    var showReportViewer by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var dontAskAgain by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showReportViewer) {
        CrashReportViewerDialog(
            reportText = reportText,
            onDismiss = { showReportViewer = false }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { Text(stringResource(R.string.crash_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.crash_dialog_message))
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.CenterHorizontally),
                        color = OrangeAccent,
                        strokeWidth = 2.dp
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = dontAskAgain,
                        onCheckedChange = { dontAskAgain = it },
                        enabled = !isSending,
                        colors = CheckboxDefaults.colors(checkedColor = OrangeAccent)
                    )
                    Text(
                        text = stringResource(R.string.crash_dialog_dont_ask_again),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isSending = true
                    scope.launch {
                        val success = try {
                            onSend()
                        } catch (e: Exception) {
                            Log.e("CrashReportDialog", "Crash report send failed", e)
                            false
                        }
                        if (success && dontAskAgain) {
                            onRememberChoice(true)
                        }
                        isSending = false
                        onSendResult(success)
                        onDismiss()
                    }
                },
                enabled = !isSending
            ) {
                Text(stringResource(R.string.crash_dialog_send), color = OrangeAccent)
            }
        },
        dismissButton = {
            if (!isSending) {
                Row {
                    TextButton(onClick = {
                        if (dontAskAgain) onRememberChoice(false)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.crash_dialog_dismiss))
                    }
                    TextButton(onClick = { showReportViewer = true }) {
                        Text(stringResource(R.string.crash_dialog_view))
                    }
                }
            }
        }
    )
}

@Composable
private fun CrashReportViewerDialog(
    reportText: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_viewer_title)) },
        text = {
            Text(
                text = reportText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_viewer_close), color = OrangeAccent)
            }
        }
    )
}
