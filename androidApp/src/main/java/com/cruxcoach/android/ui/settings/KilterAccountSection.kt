package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.kilter.KilterImportPreview
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

data class KilterAccountState(
    val isConnected: Boolean = false,
    val username: String = "",
    val lastSync: String? = null,
    val pushEnabled: Boolean = false,
    val sessionExpired: Boolean = false,
    val showLoginSheet: Boolean = false,
    val loginEmail: String = "",
    val loginPassword: String = "",
    val loginError: String? = null,
    val isLoggingIn: Boolean = false,
    val showImportPreview: Boolean = false,
    val importPreview: KilterImportPreview? = null,
    val isImporting: Boolean = false,
    val isSyncing: Boolean = false,
    val showDisconnectConfirm: Boolean = false,
    val resultMessage: String? = null,
    /** Master toggle: should newly created CruxCoach climbs be pushed to
     *  the official Kilter DB via the user's own account? Default true.
     *  Greyed out when the user isn't connected; tapping then nudges
     *  them through the Kilter login flow. */
    val climbPublishEnabled: Boolean = true,
)

@Composable
internal fun KilterAccountSection(
    state: KilterAccountState,
    onShowLogin: () -> Unit,
    onDismissLogin: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onImportOneTime: () -> Unit,
    onImportPersistent: () -> Unit,
    onDismissPreview: () -> Unit,
    onSyncNow: () -> Unit,
    onPushEnabledChanged: (Boolean) -> Unit,
    onClimbPublishEnabledChanged: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onShowDisconnectConfirm: () -> Unit,
    onDismissDisconnectConfirm: () -> Unit,
    onDismissResult: () -> Unit
) {
    if (state.isConnected) {
        KilterConnectedCard(
            username = state.username,
            lastSync = state.lastSync,
            pushEnabled = state.pushEnabled,
            climbPublishEnabled = state.climbPublishEnabled,
            sessionExpired = state.sessionExpired,
            isSyncing = state.isSyncing,
            onSyncNow = onSyncNow,
            onPushEnabledChanged = onPushEnabledChanged,
            onClimbPublishEnabledChanged = onClimbPublishEnabledChanged,
            onReLogin = onShowLogin,
            onDisconnect = onShowDisconnectConfirm
        )
    } else {
        Text(
            stringResource(R.string.kilter_connect_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onShowLogin,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent)
        ) { Text(stringResource(R.string.kilter_connect_button)) }

        // Greyed-out climb-publish toggle: discoverable but inert until
        // the user connects. Tapping the row jumps to the login flow.
        DisconnectedClimbPublishHint(onConnect = onShowLogin)
    }

    // Result message (success/error)
    state.resultMessage?.let { msg ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (msg.contains("fehlgeschlagen") || msg.contains("failed"))
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else SuccessGreen.copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(msg, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismissResult) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        }
    }

    // Login bottom sheet
    if (state.showLoginSheet) {
        KilterLoginSheet(
            email = state.loginEmail,
            password = state.loginPassword,
            error = state.loginError,
            isLoading = state.isLoggingIn,
            onEmailChanged = onEmailChanged,
            onPasswordChanged = onPasswordChanged,
            onLogin = onLogin,
            onDismiss = onDismissLogin
        )
    }

    // Import preview dialog
    if (state.showImportPreview && state.importPreview != null) {
        KilterImportPreviewDialog(
            preview = state.importPreview,
            isImporting = state.isImporting,
            onImportOneTime = onImportOneTime,
            onImportPersistent = onImportPersistent,
            onDismiss = onDismissPreview
        )
    }

    // Disconnect confirmation
    if (state.showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = onDismissDisconnectConfirm,
            title = { Text(stringResource(R.string.kilter_disconnect)) },
            text = { Text(stringResource(R.string.kilter_disconnect_confirm)) },
            confirmButton = {
                TextButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.kilter_disconnect),
                        color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDisconnectConfirm) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KilterLoginSheet(
    email: String,
    password: String,
    error: String?,
    isLoading: Boolean,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.kilter_login_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = email,
                onValueChange = onEmailChanged,
                label = { Text(stringResource(R.string.kilter_email_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                enabled = !isLoading
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChanged,
                label = { Text(stringResource(R.string.kilter_password_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                enabled = !isLoading
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.kilter_login_button))
            }

            Text(
                stringResource(R.string.kilter_login_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KilterImportPreviewDialog(
    preview: KilterImportPreview,
    isImporting: Boolean,
    onImportOneTime: () -> Unit,
    onImportPersistent: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(stringResource(R.string.kilter_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(
                    R.string.kilter_preview_found,
                    preview.totalLogs,
                    preview.newAscents + preview.newBids,
                    preview.duplicateCount
                ))

                if (isImporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // One-time import
                OutlinedButton(
                    onClick = onImportOneTime,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isImporting
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.kilter_import_one_time),
                            fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.kilter_import_one_time_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Persistent sync
                Button(
                    onClick = onImportPersistent,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.kilter_import_persistent),
                            fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.kilter_import_persistent_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!isImporting) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

@Composable
private fun KilterConnectedCard(
    username: String,
    lastSync: String?,
    pushEnabled: Boolean,
    climbPublishEnabled: Boolean,
    sessionExpired: Boolean,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
    onPushEnabledChanged: (Boolean) -> Unit,
    onClimbPublishEnabledChanged: (Boolean) -> Unit,
    onReLogin: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (sessionExpired) OrangeAccent.copy(alpha = 0.1f)
            else SuccessGreen.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (sessionExpired) {
                Text(
                    stringResource(R.string.kilter_session_expired),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = OrangeAccent
                )
                OutlinedButton(
                    onClick = onReLogin,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent)
                ) { Text(stringResource(R.string.kilter_relogin_button)) }
            } else {
                Text(
                    stringResource(R.string.kilter_connected_as, username),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = SuccessGreen
                )
            }

            lastSync?.let {
                Text(
                    stringResource(R.string.kilter_last_sync, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.kilter_push_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.kilter_push_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = pushEnabled,
                    onCheckedChange = onPushEnabledChanged,
                    colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
                )
            }

            // Climb-publish toggle: also lives here, alongside the
            // ascent-push toggle, since both are "what should we mirror
            // to the connected Kilter account" choices.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.kilter_climb_publish_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.kilter_climb_publish_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = climbPublishEnabled,
                    onCheckedChange = onClimbPublishEnabledChanged,
                    colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
                )
            }

            OutlinedButton(
                onClick = onSyncNow,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent),
                enabled = !isSyncing && !sessionExpired
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = OrangeAccent
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.kilter_sync_now))
            }

            if (!sessionExpired) {
                TextButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.kilter_disconnect),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Greyed-out hint shown when the user has no Kilter connection — keeps
 * the climb-publish toggle discoverable without lying about its effect.
 * Tapping the row opens the Kilter login flow.
 */
@Composable
private fun DisconnectedClimbPublishHint(onConnect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.kilter_climb_publish_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.kilter_climb_publish_disconnected_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = false,
                onCheckedChange = null,
                enabled = false,
                colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
            )
        }
    }
}
