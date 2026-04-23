package com.cruxcoach.android.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onNavigateToSync: () -> Unit = {},
    onNavigateToKeyImport: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // Honest 3-step progress bar.
        val steps = OnboardingStep.entries
        val currentIdx = steps.indexOf(state.currentStep)
        LinearProgressIndicator(
            progress = { (currentIdx + 1).toFloat() / steps.size },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = OrangeAccent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        AnimatedContent(
            targetState = state.currentStep,
            modifier = Modifier.weight(1f),
            label = "onboarding_step",
        ) { step ->
            when (step) {
                OnboardingStep.BOARD_SETUP -> BoardSetupStep(state, onNavigateToSync)
                OnboardingStep.PRIVACY -> PrivacyStep(state, viewModel)
                OnboardingStep.KILTER -> KilterStep(state, viewModel)
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.currentStep != OnboardingStep.BOARD_SETUP) {
                OutlinedButton(
                    onClick = { viewModel.previousStep() },
                    modifier = Modifier.weight(1f).testTag("onboarding_back_button"),
                    enabled = !state.restoreInProgress && !state.isSaving,
                ) {
                    Text(stringResource(R.string.action_back))
                }
            }

            when (state.currentStep) {
                OnboardingStep.BOARD_SETUP -> {
                    Button(
                        onClick = { viewModel.nextStep() },
                        modifier = Modifier.weight(1f).testTag("onboarding_next_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    ) {
                        Text(stringResource(R.string.action_next))
                    }
                }
                OnboardingStep.PRIVACY -> {
                    // Block "Weiter" when the user picked RESTORE but hasn't
                    // actually imported a key yet — otherwise the choice
                    // silently degrades into FRESH on completeOnboarding,
                    // which is the opposite of what they asked for.
                    val restoreIncomplete = state.backupOptIn &&
                        state.backupChoice == BackupChoice.RESTORE &&
                        !state.hasNostrKey &&
                        !state.restoreSucceeded
                    Button(
                        onClick = { viewModel.nextStep() },
                        enabled = !state.isCheckingForBackup &&
                            !state.restoreInProgress &&
                            !restoreIncomplete,
                        modifier = Modifier.weight(1f).testTag("onboarding_next_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    ) {
                        Text(stringResource(R.string.action_next))
                    }
                }
                OnboardingStep.KILTER -> {
                    TextButton(
                        onClick = { viewModel.completeOnboarding(onComplete) },
                        enabled = !state.isSaving,
                        modifier = Modifier.testTag("onboarding_skip_button"),
                    ) {
                        Text(stringResource(R.string.action_skip))
                    }
                    Button(
                        onClick = { viewModel.completeOnboarding(onComplete) },
                        enabled = !state.isSaving,
                        modifier = Modifier.weight(1f).testTag("onboarding_finish_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }
            }
        }

        if (state.error != null) {
            Text(
                text = state.error ?: "",
                color = ErrorRed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    // Pre-restart confirm dialog (RESTORE path only).
    if (state.showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRestartConfirm() },
            title = { Text(stringResource(R.string.onboarding_restart_confirm_title)) },
            text = { Text(stringResource(R.string.onboarding_restart_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmKeyImportNavigation(onNavigateToKeyImport)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                ) {
                    Text(stringResource(R.string.action_next))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRestartConfirm() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Restore-found dialog overlays everything.
    state.pendingRestore?.let { info ->
        val sizeKb = info.pointer.size / 1024
        AlertDialog(
            onDismissRequest = { viewModel.dismissOnboardingRestore() },
            title = { Text(stringResource(R.string.settings_backup_restore_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.onboarding_restore_dialog_body,
                        if (sizeKb < 1024) "$sizeKb KB" else "${sizeKb / 1024} MB",
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmOnboardingRestore() },
                    enabled = !state.restoreInProgress,
                ) {
                    Text(stringResource(R.string.settings_backup_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissOnboardingRestore() },
                    enabled = !state.restoreInProgress,
                ) {
                    Text(stringResource(R.string.settings_backup_restore_cancel))
                }
            },
        )
    }
    if (state.restoreFailed) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeRestoreFailure() },
            text = { Text(stringResource(R.string.settings_backup_restore_failed)) },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeRestoreFailure() }) {
                    Text(stringResource(R.string.settings_backup_restore_cancel))
                }
            },
        )
    }
}

// ─── Step 1: Board setup (with inline welcome header) ─────────────────────

@Composable
private fun BoardSetupStep(state: OnboardingState, onNavigateToSync: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Compact welcome header (no longer a standalone step).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_round),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.onboarding_welcome),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.onboarding_welcome_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            stringResource(R.string.onboarding_board_setup_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.onboarding_board_setup_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The only must-have card on this step.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.CloudSync, null, tint = OrangeAccent, modifier = Modifier.size(40.dp))
                Text(
                    stringResource(R.string.onboarding_board_db_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.onboarding_board_db_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.boardDataImported) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.onboarding_board_db_imported),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SuccessGreen,
                        )
                    }
                } else {
                    Button(
                        onClick = onNavigateToSync,
                        modifier = Modifier.fillMaxWidth().testTag("onboarding_sync_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.onboarding_board_sync), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ─── Step 2: Privacy + backup + inline restore ────────────────────────────

@Composable
private fun PrivacyStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_privacy_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.onboarding_privacy_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PrivacyToggleCard(
            icon = { Icon(Icons.Default.Bluetooth, null, tint = OrangeAccent, modifier = Modifier.size(32.dp)) },
            title = stringResource(R.string.onboarding_privacy_ble_title),
            description = stringResource(R.string.onboarding_privacy_ble_desc),
            checked = state.bleSharing,
            onCheckedChange = { viewModel.updateBleSharing(it) },
            testTag = "onboarding_ble_switch",
        )

        BackupCard(state, viewModel)

        PrivacyToggleCard(
            icon = { Icon(Icons.Default.Forum, null, tint = OrangeAccent, modifier = Modifier.size(32.dp)) },
            title = stringResource(R.string.onboarding_privacy_community_title),
            description = stringResource(R.string.onboarding_privacy_community_desc),
            checked = state.communityFeatures,
            onCheckedChange = { viewModel.updateCommunityFeatures(it) },
            testTag = "onboarding_community_switch",
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InfoBlue.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_privacy_hint),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = InfoBlue,
            )
        }
    }
}

@Composable
private fun BackupCard(state: OnboardingState, viewModel: OnboardingViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Lock, null, tint = OrangeAccent, modifier = Modifier.size(32.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_backup_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.settings_backup_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.backupOptIn,
                    onCheckedChange = { viewModel.setBackupOptIn(it) },
                    modifier = Modifier.testTag("onboarding_backup_switch"),
                    colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent),
                )
            }

            AnimatedVisibility(visible = state.backupOptIn) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider()

                    BackupChoiceRow(
                        selected = state.backupChoice == BackupChoice.FRESH,
                        title = stringResource(R.string.onboarding_backup_choice_fresh_title),
                        description = stringResource(R.string.onboarding_backup_choice_fresh_desc),
                        onSelect = { viewModel.setBackupChoice(BackupChoice.FRESH) },
                        testTag = "onboarding_backup_choice_fresh",
                    )

                    BackupChoiceRow(
                        selected = state.backupChoice == BackupChoice.RESTORE,
                        title = stringResource(R.string.onboarding_backup_choice_restore_title),
                        description = stringResource(R.string.onboarding_backup_choice_restore_desc),
                        onSelect = { viewModel.setBackupChoice(BackupChoice.RESTORE) },
                        testTag = "onboarding_backup_choice_restore",
                    )

                    if (state.backupChoice == BackupChoice.RESTORE) {
                        RestoreSubSection(state, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupChoiceRow(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .testTag(testTag),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = OrangeAccent),
        )
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RestoreSubSection(state: OnboardingState, viewModel: OnboardingViewModel) {
    Column(
        modifier = Modifier.padding(start = 36.dp),  // align under radio label
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.isCheckingForBackup -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.onboarding_backup_restore_checking),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.restoreSucceeded -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.onboarding_backup_restore_success),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SuccessGreen,
                )
            }
            state.noBackupFoundForKey -> Text(
                stringResource(R.string.onboarding_backup_restore_none_for_key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            !state.hasNostrKey -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Filled (not outlined) because this is now a required
                // action: "Weiter" is disabled until it's been completed.
                Button(
                    onClick = { viewModel.requestKeyImport() },
                    modifier = Modifier.testTag("onboarding_backup_import_key"),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                ) {
                    Text(stringResource(R.string.onboarding_backup_import_key_button))
                }
                Text(
                    stringResource(R.string.onboarding_backup_restore_waiting_for_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                // Key present, check not yet attempted (rare race) — no UI.
            }
        }
    }
}

@Composable
private fun PrivacyToggleCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(testTag),
                colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent),
            )
        }
    }
}

// ─── Step 3: Kilter (optional) ────────────────────────────────────────────

@Composable
private fun KilterStep(state: OnboardingState, viewModel: OnboardingViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_kilter_step_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.onboarding_kilter_step_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Login, null, tint = OrangeAccent, modifier = Modifier.size(28.dp))
                    Text(
                        stringResource(R.string.onboarding_kilter_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    stringResource(R.string.onboarding_kilter_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.kilterImportResult != null) {
                    KilterImportDoneContent(state)
                } else if (state.kilterConnected && state.kilterImportPreview != null) {
                    KilterPreviewContent(state, viewModel)
                } else {
                    KilterLoginContent(state, viewModel)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InfoBlue.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_kilter_skip_hint),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = InfoBlue,
            )
        }
    }
}

@Composable
private fun KilterLoginContent(state: OnboardingState, viewModel: OnboardingViewModel) {
    OutlinedTextField(
        value = state.kilterEmail,
        onValueChange = { viewModel.updateKilterEmail(it) },
        label = { Text(stringResource(R.string.kilter_email_label)) },
        modifier = Modifier.fillMaxWidth().testTag("onboarding_kilter_email"),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        enabled = !state.isKilterLoggingIn,
    )

    OutlinedTextField(
        value = state.kilterPassword,
        onValueChange = { viewModel.updateKilterPassword(it) },
        label = { Text(stringResource(R.string.kilter_password_label)) },
        modifier = Modifier.fillMaxWidth().testTag("onboarding_kilter_password"),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        enabled = !state.isKilterLoggingIn,
    )

    if (state.kilterLoginError != null) {
        Text(
            state.kilterLoginError,
            style = MaterialTheme.typography.bodySmall,
            color = ErrorRed,
        )
    }

    Button(
        onClick = { viewModel.kilterLogin() },
        enabled = state.kilterEmail.isNotBlank() && state.kilterPassword.isNotBlank() && !state.isKilterLoggingIn,
        modifier = Modifier.fillMaxWidth().testTag("onboarding_kilter_login"),
        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
        shape = RoundedCornerShape(12.dp),
    ) {
        if (state.isKilterLoggingIn) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(stringResource(R.string.kilter_login_button))
        }
    }

    Text(
        stringResource(R.string.onboarding_kilter_credentials_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun KilterPreviewContent(state: OnboardingState, viewModel: OnboardingViewModel) {
    val preview = state.kilterImportPreview ?: return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.onboarding_kilter_logged_in, state.kilterUsername),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = SuccessGreen,
        )
    }

    Text(
        stringResource(
            R.string.onboarding_kilter_preview,
            preview.totalLogs,
            preview.newAscents,
            preview.newBids,
            preview.duplicateCount,
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { viewModel.kilterImportOneTime() },
            enabled = !state.isKilterImporting,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_kilter_import_once),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Button(
            onClick = { viewModel.kilterImportPersistent() },
            enabled = !state.isKilterImporting,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (state.isKilterImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    stringResource(R.string.onboarding_kilter_import_sync),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun KilterImportDoneContent(state: OnboardingState) {
    val result = state.kilterImportResult ?: return
    val isError = result.toIntOrNull() == null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            null,
            tint = if (isError) ErrorRed else SuccessGreen,
            modifier = Modifier.size(20.dp),
        )
        Text(
            if (isError) stringResource(R.string.onboarding_kilter_import_error, result)
            else stringResource(R.string.onboarding_kilter_import_success, result.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) ErrorRed else SuccessGreen,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
