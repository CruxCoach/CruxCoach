package com.cruxcoach.android.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SuggestionChip
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
import com.cruxcoach.android.nostr.AmberIntegration
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
            restartApp(context)
        }
    }

    // Amber ActivityResult launcher — returns the pubkey in the "signature"
    // extra (NIP-55 get_public_key), which NostrSigner.normalizeToHex then
    // converts from npub into the hex format the keystore expects.
    val amberLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pubkey = result.data?.getStringExtra("signature") ?: return@rememberLauncherForActivityResult
            val packageName = result.data?.getStringExtra("package") ?: AmberIntegration.AMBER_PACKAGE
            viewModel.onAmberLoginSuccess(pubkey, packageName)
        }
    }
    var showAmberNotInstalled by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.key_import_title)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.key_import_prompt),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            // Amber shortcut — preferred path for users who already use a
            // hardware/external signer. Doesn't touch the text field below;
            // on success the view model handles switchToAmber + restart.
            Button(
                onClick = {
                    if (AmberIntegration.isInstalled(context)) {
                        amberLauncher.launch(AmberIntegration.buildGetPubkeyIntent())
                    } else {
                        showAmberNotInstalled = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.key_import_amber_button), fontWeight = FontWeight.Bold)
            }

            Text(
                stringResource(R.string.key_import_or),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Masked by default — the field accepts nsec / mnemonic /
            // hex / ncryptsec, all of which are high-value secrets the
            // user should not have to expose on-screen while typing.
            // Hold the eye icon to reveal for verification; switching
            // the keyboard to Password + disabling autocorrect keeps
            // the IME from learning the value into its dictionary or
            // auto-capitalizing.
            var revealKey by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = state.input,
                onValueChange = { viewModel.updateInput(it) },
                label = { Text(stringResource(R.string.key_import_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
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
                supportingText = {
                    Text(
                        text = stringResource(R.string.key_import_supported_formats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            // Detected format indicator
            val detectedFormat = state.detectedFormat
            if (detectedFormat != ImportFormat.UNKNOWN) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = stringResource(R.string.key_import_detected, detectedFormat.name.lowercase()),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
                enabled = detectedFormat != ImportFormat.UNKNOWN,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.key_import_button),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // Amber not installed dialog — same behaviour as KeyManagementScreen,
    // but inline here so users who tapped "Import key" from onboarding can
    // still discover that Amber is the recommended route.
    if (showAmberNotInstalled) {
        AlertDialog(
            onDismissRequest = { showAmberNotInstalled = false },
            title = { Text(stringResource(R.string.key_import_amber_not_installed_title)) },
            text = { Text(stringResource(R.string.key_import_amber_not_installed_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showAmberNotInstalled = false
                    val url = "https://zapstore.dev/apps/${AmberIntegration.AMBER_PACKAGE}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }) {
                    Text(stringResource(R.string.key_import_amber_get))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAmberNotInstalled = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
            title = { Text(stringResource(R.string.key_import_confirm_title)) },
            text = {
                Text(
                    text = state.derivedNpub,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport() }) {
                    Text(stringResource(R.string.key_import_confirm_button), color = OrangeAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissConfirmDialog() }) {
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
