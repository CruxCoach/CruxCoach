package com.cruxcoach.android.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.data.shinywall.ShinyWallImportPreview
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

/**
 * Settings → Imports: connect a Shiny Wall account and import its logbook,
 * favourites, lists and own routes. Read-only towards Shiny Wall.
 */
@Composable
internal fun ShinyWallAccountSection(viewModel: ShinyWallAccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ShinyWallAccountContent(
        state = state,
        onShowLogin = viewModel::showLogin,
        onDismissLogin = viewModel::dismissLogin,
        onEmailChanged = viewModel::updateEmail,
        onPasswordChanged = viewModel::updatePassword,
        onLogin = viewModel::login,
        onCheck = viewModel::loadPreview,
        onImport = viewModel::importNow,
        onDismissPreview = viewModel::dismissPreview,
        onShowDisconnectConfirm = viewModel::showDisconnectConfirm,
        onDismissDisconnectConfirm = viewModel::dismissDisconnectConfirm,
        onDisconnect = viewModel::disconnect,
        onDismissResult = viewModel::dismissResult,
    )
}

@Composable
internal fun ShinyWallAccountContent(
    state: ShinyWallAccountState,
    onShowLogin: () -> Unit,
    onDismissLogin: () -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onCheck: () -> Unit,
    onImport: () -> Unit,
    onDismissPreview: () -> Unit,
    onShowDisconnectConfirm: () -> Unit,
    onDismissDisconnectConfirm: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissResult: () -> Unit,
) {
    SettingsInfoHeading(
        title = stringResource(R.string.shinywall_section_title),
        description = stringResource(R.string.shinywall_connect_desc),
    )
    if (state.isConnected) {
        ShinyWallConnectedCard(state, onCheck, onShowLogin, onShowDisconnectConfirm)
    } else {
        OutlinedButton(
            onClick = onShowLogin,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent),
        ) { Text(stringResource(R.string.shinywall_connect_button)) }
    }

    state.resultMessage?.let { msg ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state.resultIsError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else SuccessGreen.copy(alpha = 0.1f)
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SelectionContainer { Text(msg, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = onDismissResult) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }

    if (state.showLoginSheet) {
        ShinyWallLoginSheet(
            email = state.loginEmail,
            password = state.loginPassword,
            error = state.loginError,
            isLoading = state.isLoggingIn,
            onEmailChanged = onEmailChanged,
            onPasswordChanged = onPasswordChanged,
            onLogin = onLogin,
            onDismiss = onDismissLogin,
        )
    }

    state.preview?.let { preview ->
        ShinyWallPreviewDialog(preview, state.isImporting, onImport, onDismissPreview)
    }

    if (state.showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = onDismissDisconnectConfirm,
            title = { Text(stringResource(R.string.shinywall_disconnect)) },
            text = { Text(stringResource(R.string.shinywall_disconnect_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDismissDisconnectConfirm()
                    onDisconnect()
                }) { Text(stringResource(R.string.shinywall_disconnect), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = onDismissDisconnectConfirm) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ShinyWallConnectedCard(
    state: ShinyWallAccountState,
    onCheck: () -> Unit,
    onReLogin: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.sessionExpired) OrangeAccent.copy(alpha = 0.1f) else SuccessGreen.copy(alpha = 0.1f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Same pattern as the Kilter card: an expired sign-in names itself and offers its fix.
            Text(
                if (state.sessionExpired) stringResource(R.string.shinywall_session_expired)
                else stringResource(R.string.shinywall_connected_as, state.displayName),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (state.sessionExpired) OrangeAccent else SuccessGreen,
            )
            state.lastImportAtMs?.let { at ->
                val whenText = DateUtils.formatDateTime(
                    context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                )
                Text(
                    stringResource(R.string.shinywall_last_import, whenText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.sessionExpired) {
                OutlinedButton(
                    onClick = onReLogin,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent),
                ) { Text(stringResource(R.string.shinywall_relogin_button)) }
            } else {
                // Outlined with its own spinner, like the Kilter card's "Sync now" above it.
                OutlinedButton(
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !state.isImporting,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent),
                ) {
                    if (state.isImporting && state.preview == null) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = OrangeAccent)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.shinywall_import_new))
                }
            }
            TextButton(onClick = onDisconnect, enabled = !state.isImporting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.shinywall_disconnect), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShinyWallLoginSheet(
    email: String,
    password: String,
    error: String?,
    isLoading: Boolean,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (!isLoading) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.shinywall_login_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.shinywall_login_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChanged,
                label = { Text(stringResource(R.string.shinywall_email_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                enabled = !isLoading,
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChanged,
                label = { Text(stringResource(R.string.shinywall_password_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!isLoading && email.isNotBlank() && password.isNotBlank()) onLogin()
                }),
                enabled = !isLoading,
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
            ) {
                if (isLoading) {
                    // The button is disabled while signing in: an accent spinner stays visible on its grey.
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = OrangeAccent,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.shinywall_login_button))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShinyWallPreviewDialog(
    preview: ShinyWallImportPreview,
    isImporting: Boolean,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val newEntries = preview.newAscents + preview.newProjects
    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(stringResource(R.string.shinywall_preview_title)) },
        text = {
            // Full width in every state, so the dialog keeps its size when the progress bar appears.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (preview.totalEntries == 0) {
                    Text(stringResource(R.string.shinywall_import_empty))
                } else {
                    // Only what lands in CruxCoach: attempts that belong to a send are in no count.
                    Text(
                        stringResource(
                            R.string.shinywall_preview_found,
                            pluralStringResource(R.plurals.shinywall_preview_new_ascents, preview.newAscents, preview.newAscents),
                            pluralStringResource(R.plurals.shinywall_preview_new_projects, preview.newProjects, preview.newProjects),
                            stringResource(R.string.shinywall_preview_known, preview.duplicates),
                        )
                    )
                }
                if (preview.deleted > 0) {
                    Text(
                        pluralStringResource(R.plurals.shinywall_preview_deleted, preview.deleted, preview.deleted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.skipped > 0) {
                    Text(
                        pluralStringResource(R.plurals.shinywall_preview_skipped, preview.skipped, preview.skipped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isImporting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        // Both buttons in one flow: at the largest font a two-line "Listen aktualisieren" goes
        // above "Abbrechen" instead of covering it.
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isImporting) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = onImport,
                    enabled = !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                ) {
                    Text(
                        if (newEntries > 0) stringResource(R.string.shinywall_import_button)
                        else stringResource(R.string.shinywall_import_lists_button)
                    )
                }
            }
        },
    )
}
