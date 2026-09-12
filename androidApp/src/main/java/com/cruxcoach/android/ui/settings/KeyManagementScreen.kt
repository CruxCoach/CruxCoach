package com.cruxcoach.android.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.common.InfoHeading
import com.cruxcoach.android.nostr.AmberIntegration
import com.cruxcoach.android.nostr.SignerMode
import kotlin.system.exitProcess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagementScreen(
    onNavigateBack: () -> Unit,
    onNavigateToImport: () -> Unit,
    viewModel: KeyManagementViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val npubCopiedMessage = stringResource(R.string.key_toast_npub_copied)

    var showNsecWarning by remember { mutableStateOf(false) }
    var showBiometricUnavailable by remember { mutableStateOf(false) }
    var showNoSecurityWarning by remember { mutableStateOf(false) }
    var noSecurityPendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showAmberNotInstalled by remember { mutableStateOf(false) }
    var showAmberSuccess by remember { mutableStateOf(false) }

    // Process restart after identity change (Amber login, switch to local)
    LaunchedEffect(state.requireRestart) {
        if (state.requireRestart) {
            restartApp(context)
        }
    }

    // FLAG_SECURE: prevent screenshots on this screen
    DisposableEffect(Unit) {
        (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val amberLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pubkey = result.data?.getStringExtra("signature") ?: return@rememberLauncherForActivityResult
            val packageName = result.data?.getStringExtra("package") ?: AmberIntegration.AMBER_PACKAGE
            viewModel.onAmberLoginSuccess(pubkey, packageName)
            showAmberSuccess = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.key_button_manage)) },
                actions = { InfoButton(stringResource(R.string.key_button_manage), stringResource(R.string.ux_account_help)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        AccountManagementContent(
            state = state,
            onCopyNsec = {
                requestBiometric(
                    context = context,
                    onSuccess = { showNsecWarning = true },
                    onUnavailable = { showBiometricUnavailable = true },
                    onNoHardware = {
                        noSecurityPendingAction = { showNsecWarning = true }
                        showNoSecurityWarning = true
                    },
                )
            },
            onImport = onNavigateToImport,
            onSetupAmber = {
                if (AmberIntegration.isInstalled(context)) {
                    amberLauncher.launch(AmberIntegration.buildGetPubkeyIntent())
                } else {
                    showAmberNotInstalled = true
                }
            },
            onDisconnectAmber = viewModel::switchToLocalSigner,
            onCopyNpub = {
                copyToClipboard(context, state.npubFull, "npub", sensitive = false)
                Toast.makeText(context, npubCopiedMessage, Toast.LENGTH_SHORT).show()
            },
            onAcknowledgeBackup = viewModel::acknowledgeKeyBackup,
            modifier = Modifier.padding(padding),
        )
    }

    // Dialogs
    if (showNsecWarning) {
        NsecWarningDialog(
            onDismiss = { showNsecWarning = false },
            onConfirm = {
                showNsecWarning = false
                viewModel.confirmNsecCopy()
            }
        )
    }

    if (showBiometricUnavailable) {
        BiometricUnavailableDialog(
            onDismiss = { showBiometricUnavailable = false },
            onOpenSettings = {
                showBiometricUnavailable = false
                val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                context.startActivity(intent)
            }
        )
    }

    if (showAmberNotInstalled) {
        AmberNotInstalledDialog(
            onDismiss = { showAmberNotInstalled = false },
            onInstallZapstore = {
                showAmberNotInstalled = false
                openInStoreOrBrowser(
                    context = context,
                    storePackage = "dev.zapstore.app",
                    storeUri = Uri.parse("market://details?id=${AmberIntegration.AMBER_PACKAGE}"),
                    browserUrl = "https://zapstore.dev/apps/${AmberIntegration.AMBER_PACKAGE}"
                )
            },
            onInstallFdroid = {
                showAmberNotInstalled = false
                openInStoreOrBrowser(
                    context = context,
                    storePackage = "org.fdroid.fdroid",
                    storeUri = Uri.parse("market://details?id=${AmberIntegration.AMBER_PACKAGE}"),
                    browserUrl = "https://f-droid.org/packages/${AmberIntegration.AMBER_PACKAGE}/"
                )
            }
        )
    }

    if (showNoSecurityWarning) {
        NoSecurityWarningDialog(
            onDismiss = {
                showNoSecurityWarning = false
                noSecurityPendingAction = null
            },
            onOpenSettings = {
                showNoSecurityWarning = false
                noSecurityPendingAction = null
                val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                context.startActivity(intent)
            },
            onProceedAnyway = {
                showNoSecurityWarning = false
                noSecurityPendingAction?.invoke()
                noSecurityPendingAction = null
            }
        )
    }

    if (showAmberSuccess) {
        AmberSuccessDialog(
            onKeepLocalKey = {
                showAmberSuccess = false
                restartApp(context)
            },
            onDeleteLocalKey = {
                showAmberSuccess = false
                viewModel.deleteLocalKeyAfterAmber()
                restartApp(context)
            }
        )
    }
}

/** Presentation only: key access, authentication and account changes stay in the screen. */
@Composable
internal fun AccountManagementContent(
    state: KeyManagementState,
    onCopyNsec: () -> Unit,
    onImport: () -> Unit,
    onSetupAmber: () -> Unit,
    onDisconnectAmber: () -> Unit,
    onCopyNpub: () -> Unit,
    onAcknowledgeBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("account_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator()
            return@Column
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SettingsSectionCard {
            InfoHeading(
                stringResource(R.string.account_current_access),
                stringResource(if (state.signerMode == SignerMode.AMBER) R.string.key_label_amber_active else R.string.account_access_local_summary),
            )
            Text(
                stringResource(if (state.signerMode == SignerMode.AMBER) R.string.account_access_amber else R.string.account_access_local),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        if (state.signerMode == SignerMode.LOCAL) {
            AccountRecoverySection(state.keyBackedUp, onCopyNsec, onAcknowledgeBackup)
        }

        SettingsSectionCard {
            InfoHeading(stringResource(R.string.account_switch_title), stringResource(if (state.signerMode == SignerMode.AMBER) R.string.account_switch_amber_help else R.string.account_switch_help))
            OutlinedButton(
                onClick = if (state.signerMode == SignerMode.AMBER) onDisconnectAmber else onImport,
                modifier = Modifier.fillMaxWidth().testTag("account_switch"),
            ) {
                Text(stringResource(if (state.signerMode == SignerMode.AMBER) R.string.key_button_disconnect_amber else R.string.account_import_action))
            }
        }

        SettingsExpandableSection(
            title = stringResource(R.string.account_amber_title),
            summary = stringResource(if (state.signerMode == SignerMode.AMBER) R.string.account_amber_connected else R.string.account_amber_optional),
            initiallyExpanded = state.signerMode == SignerMode.AMBER,
            help = stringResource(R.string.account_amber_help),
        ) {
            if (state.signerMode == SignerMode.AMBER) {
                state.amberPubkeyDisplay?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.key_label_amber_active), style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedButton(onClick = onSetupAmber, modifier = Modifier.fillMaxWidth().testTag("account_connect_amber")) {
                    Text(stringResource(R.string.account_amber_connect))
                }
            }
        }

        SettingsSectionCard {
            InfoHeading(stringResource(R.string.account_public_id), stringResource(R.string.account_public_id_help))
            // npubFull and npubDisplay identify the active signer in both modes.
            // Do not mislabel the active Amber identity as an inactive local key.
            Text(truncateKey(state.npubDisplay), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("account_public_id"))
            TextButton(onClick = onCopyNpub, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.account_copy_id))
            }
        }
    }
}

@Composable
internal fun AccountRecoverySection(backedUp: Boolean, onCopyNsec: () -> Unit, onAcknowledge: () -> Unit) {
    var showAckDialog by rememberSaveable { mutableStateOf(false) }
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.account_recovery_title), stringResource(R.string.account_recovery_help))
        if (!backedUp) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(stringResource(R.string.key_label_not_backed_up), fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                }
            }
        } else {
            Text(stringResource(R.string.account_backup_acknowledged), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.key_label_nsec_warning), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onCopyNsec, modifier = Modifier.fillMaxWidth().testTag("account_copy_secret")) {
            Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.account_copy_secret))
        }
        if (!backedUp) {
            TextButton(onClick = { showAckDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.backup_key_warning_acknowledged))
            }
        }
    }
    if (showAckDialog) {
        AlertDialog(
            onDismissRequest = { showAckDialog = false },
            title = { Text(stringResource(R.string.backup_key_warning_ack_dialog_title)) },
            text = { Text(stringResource(R.string.backup_key_warning_ack_dialog_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { showAckDialog = false; onAcknowledge() }) {
                    Text(stringResource(R.string.backup_key_warning_ack_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAckDialog = false }) {
                    Text(stringResource(R.string.backup_key_warning_ack_cancel))
                }
            },
        )
    }
}

internal fun restartApp(context: Context) {
    val intent = context.packageManager
        .getLaunchIntentForPackage(context.packageName)!!
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra("identity_switch", true)
    context.startActivity(intent)
    exitProcess(0)
}

private fun openInStoreOrBrowser(
    context: Context,
    storePackage: String,
    storeUri: Uri,
    browserUrl: String
) {
    val storeIntent = Intent(Intent.ACTION_VIEW, storeUri).apply {
        setPackage(storePackage)
    }
    try {
        context.startActivity(storeIntent)
    } catch (_: android.content.ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)))
    }
}
