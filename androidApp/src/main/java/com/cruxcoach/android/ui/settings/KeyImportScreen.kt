package com.cruxcoach.android.ui.settings

import android.app.Activity
import android.net.Uri
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import com.cruxcoach.android.nostr.AmberIntegration
import android.view.WindowManager
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: KeyImportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var useAmber by rememberSaveable { mutableStateOf(false) }
    var amberMissing by rememberSaveable { mutableStateOf(false) }
    val amberLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra("signature")?.let { publicKey ->
                viewModel.previewAmberAccount(publicKey, result.data?.getStringExtra("package"))
            }
        }
    }
    state.amberTargetNpub?.let { target ->
        AccountAccessConfirmation(targetNpub = target, sameAccount = state.sameAccount, toAmber = true,
            onDismiss = viewModel::dismissAmberImport, onConfirm = viewModel::confirmAmberImport)
    }
    if (amberMissing) {
        AmberNotInstalledDialog(
            onDismiss = { amberMissing = false },
            onInstallZapstore = {
                amberMissing = false
                openInStoreOrBrowser(context, "dev.zapstore.app",
                    Uri.parse("market://details?id=${AmberIntegration.AMBER_PACKAGE}"),
                    "https://zapstore.dev/apps/${AmberIntegration.AMBER_PACKAGE}")
            },
            onInstallFdroid = {
                amberMissing = false
                openInStoreOrBrowser(context, "org.fdroid.fdroid",
                    Uri.parse("market://details?id=${AmberIntegration.AMBER_PACKAGE}"),
                    "https://f-droid.org/packages/${AmberIntegration.AMBER_PACKAGE}/")
            },
        )
    }

    // Prevent screenshots and the recents-thumbnail from capturing pasted
    // nsec/mnemonic while this screen is active.
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(state.requireRestart) {
        if (state.requireRestart) {
            restartApp(context, openBackup = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_restore_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsChoices(
                options = listOf(false to stringResource(R.string.account_restore_key_method),
                    true to stringResource(R.string.account_method_amber)),
                selected = useAmber,
                onSelect = { if (!state.isWorking) useAmber = it },
            )
            if (useAmber) SettingsSectionCard {
                com.cruxcoach.android.ui.common.InfoHeading(stringResource(R.string.account_method_amber),
                    stringResource(R.string.account_amber_help) + "\n\n" + stringResource(R.string.account_import_backup_explanation))
                Text(stringResource(R.string.account_amber_restore_summary), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        if (AmberIntegration.isInstalled(context)) amberLauncher.launch(AmberIntegration.buildGetPubkeyIntent())
                        else amberMissing = true
                    },
                    enabled = !state.isWorking,
                    modifier = Modifier.fillMaxWidth().testTag("restore_with_amber"),
                ) { Text(stringResource(R.string.account_amber_connect)) }
                state.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            } else SettingsSectionCard {
                com.cruxcoach.android.ui.common.InfoHeading(stringResource(R.string.account_method_local), stringResource(R.string.account_import_help) + "\n\n" + stringResource(R.string.key_import_supported_formats) + "\n\n" + stringResource(R.string.account_import_backup_explanation))
                Text(
                    text = stringResource(R.string.key_import_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )

                Text(stringResource(R.string.import_key_access_only),
                    style = MaterialTheme.typography.bodyMedium)

                // Keep input masked by default; never persist the reveal state.
                var revealKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = state.input,
                    enabled = !state.isWorking,
                    onValueChange = { viewModel.updateInput(it) },
                    label = { Text(stringResource(R.string.key_import_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 112.dp),
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrect = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(
                                imageVector = if (revealKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (revealKey) R.string.key_import_hide else R.string.key_import_reveal
                                ),
                            )
                        }
                    },

                )

                // Detected format indicator
                val detectedFormat = state.detectedFormat
                if (detectedFormat != ImportFormat.UNKNOWN) {
                    Text(stringResource(R.string.key_import_detected, when (detectedFormat) {
                        ImportFormat.MNEMONIC -> stringResource(R.string.account_recovery_words)
                        else -> detectedFormat.name.lowercase()
                    }), style = MaterialTheme.typography.bodySmall)

                }

                // A disabled button alone does not tell a beginner what is wrong. Only the
                // public "npub" prefix is inspected; the input itself is never displayed.
                val trimmedInput = state.input.trim()
                if (detectedFormat == ImportFormat.UNKNOWN && trimmedInput.isNotEmpty() && state.error == null) {
                    Text(
                        text = stringResource(
                            if (trimmedInput.startsWith("npub", ignoreCase = true)) R.string.key_import_hint_public_id
                            else R.string.key_import_hint_unrecognized
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("key_import_format_hint"),
                    )
                }

                // Error message
                val error = state.error
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.startImport() },
                    enabled = detectedFormat != ImportFormat.UNKNOWN && !state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(if (state.isWorking) R.string.account_import_checking else R.string.key_import_button),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Password dialog for ncryptsec
    if (state.showPasswordDialog) {
        NcryptsecPasswordDialog(
            onDismiss = { viewModel.dismissPasswordDialog() },
            onConfirm = { password -> viewModel.submitPassword(password) }
        )
    }

    // Overwrite warning dialog
    if (state.showOverwriteWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissOverwriteWarning() },
            title = { Text(stringResource(R.string.key_import_overwrite_title)) },
            text = {
                Text(stringResource(R.string.key_import_overwrite_text))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmOverwrite() }) {
                    Text(stringResource(R.string.key_import_overwrite_confirm), color = OrangeAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissOverwriteWarning() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Confirm dialog showing derived npub
    if (state.showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissConfirmDialog() },
            title = { Text(stringResource(if (state.sameAccount) R.string.account_access_change_title else R.string.key_import_overwrite_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (state.sameAccount) R.string.account_access_same else R.string.account_access_different))
                    Text(state.derivedNpub, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.account_access_to_local))
                    Text(stringResource(R.string.import_key_access_only))
                    if (state.replacesLocalKey) Text(stringResource(R.string.account_import_replaces_local))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport() }, enabled = !state.isWorking) {
                    Text(stringResource(R.string.key_import_confirm_button), color = OrangeAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissConfirmDialog() }, enabled = !state.isWorking) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun NcryptsecPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_import_password_title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.key_import_password_label)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank()
            ) {
                Text(
                    stringResource(R.string.key_import_decrypt_button),
                    color = if (password.isNotBlank()) OrangeAccent
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
