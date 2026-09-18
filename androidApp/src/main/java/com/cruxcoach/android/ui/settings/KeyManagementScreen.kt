package com.cruxcoach.android.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
    onNavigateToBackup: () -> Unit = {},
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
    var pendingAmber by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showLocalSwitch by remember { mutableStateOf(false) }
    var showBackupDone by remember { mutableStateOf(false) }

    // Process restart after identity change (Amber login, switch to local)
    LaunchedEffect(state.requireRestart) {
        if (state.requireRestart) {
            restartApp(context, openBackup = true)
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
            pendingAmber = pubkey to packageName
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
            onCopyNsec = { showNsecWarning = true },
            onImport = onNavigateToImport,
            onOpenBackup = onNavigateToBackup,
            onSetupAmber = {
                if (AmberIntegration.isInstalled(context)) {
                    amberLauncher.launch(AmberIntegration.buildGetPubkeyIntent())
                } else {
                    showAmberNotInstalled = true
                }
            },
            onDisconnectAmber = { showLocalSwitch = true },
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
                val copyAndFinish = { if (viewModel.confirmNsecCopy()) showBackupDone = true }
                requestBiometric(context, onSuccess = copyAndFinish,
                    onUnavailable = { showBiometricUnavailable = true },
                    onNoHardware = {
                        noSecurityPendingAction = copyAndFinish
                        showNoSecurityWarning = true
                    })
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

    pendingAmber?.let { (key, pkg) ->
        val target = accountNpub(key)
        AccountAccessConfirmation(
            targetNpub = target.orEmpty(),
            sameAccount = target == state.npubFull,
            toAmber = true,
            onDismiss = { pendingAmber = null },
            onConfirm = { pendingAmber = null; viewModel.onAmberLoginSuccess(key, pkg) },
        )
    }
    if (showLocalSwitch) {
        AccountAccessConfirmation(
            targetNpub = state.localNpub.orEmpty(),
            sameAccount = state.localNpub == state.npubFull,
            toAmber = false,
            onDismiss = { showLocalSwitch = false },
            onConfirm = { showLocalSwitch = false; viewModel.switchToLocalSigner() },
        )
    }
    if (showBackupDone) {
        AlertDialog(
            onDismissRequest = { showBackupDone = false },
            title = { Text(stringResource(R.string.account_backup_finish_title)) },
            text = { Text(stringResource(R.string.account_backup_finish_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                showBackupDone = false; viewModel.acknowledgeKeyBackup(); onNavigateToBackup()
            }) { Text(stringResource(R.string.account_key_saved_open_backup)) } },
            dismissButton = { TextButton(onClick = { showBackupDone = false }) {
                Text(stringResource(R.string.action_close))
            } },
        )
    }
    if (state.showAmberSuccessDialog) {
        AmberSuccessDialog(
            hasLocalKey = state.localNpub != null,
            onKeepLocalKey = {
                viewModel.dismissAmberSuccess()
                restartApp(context, openBackup = true)
            },
            onDeleteLocalKey = {
                viewModel.dismissAmberSuccess()
                viewModel.deleteLocalKeyAfterAmber()
                restartApp(context, openBackup = true)
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
    onOpenBackup: () -> Unit = {},
) {
    var showPublicQr by remember { mutableStateOf(false) }
    if (showPublicQr && state.npubFull.isNotBlank()) {
        val bitmap = remember(state.npubFull) { com.cruxcoach.android.util.ApkShareHelper.generateQrBitmap("nostr:" + state.npubFull) }
        AlertDialog(
            onDismissRequest = { showPublicQr = false },
            title = { Text(stringResource(R.string.account_public_id)) },
            text = { Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.account_show_qr),
                modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { showPublicQr = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("account_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.isLoading || state.isWorking) {
            CircularProgressIndicator()
            return@Column
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SettingsSectionCard {
            if (state.signerMode == SignerMode.LOCAL) {
                AccountRecoverySection(state.keyBackedUp, onCopyNsec, onAcknowledgeBackup)
                Text(stringResource(R.string.account_method_amber), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.account_amber_save_steps), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.account_recovery_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.account_amber_backup), style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(onClick = onSetupAmber, modifier = Modifier.fillMaxWidth().testTag("account_connect_amber")) {
                Text(stringResource(if (state.signerMode == SignerMode.AMBER)
                    R.string.account_amber_choose else R.string.account_amber_connect))
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.account_restore_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.account_restore_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().testTag("account_switch")) {
                Text(stringResource(R.string.account_import_action))
            }
            if (state.signerMode == SignerMode.AMBER && state.localNpub != null) {
                Text(stringResource(R.string.account_local_copy_available), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDisconnectAmber, modifier = Modifier.fillMaxWidth().testTag("account_use_local")) {
                    Text(stringResource(R.string.account_use_local))
                }
            }
        }
        SettingsSectionCard {
            Text(stringResource(R.string.account_data_backup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.account_data_backup_explanation), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth().testTag("account_open_data_backup")) {
                Text(stringResource(R.string.account_data_backup_action))
            }
        }
        SettingsExpandableSection(
            title = stringResource(R.string.account_public_id),
            summary = stringResource(R.string.account_public_optional),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.pictureUrl.isNotBlank()) {
                    coil.compose.AsyncImage(state.pictureUrl, contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(CircleShape))
                } else {
                    Icon(Icons.Default.Person, null, modifier = Modifier.size(48.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer).padding(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    if (state.displayName.isNotBlank()) Text(state.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(state.npubDisplay, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("account_public_id"))
                }
                IconButton(onClick = { showPublicQr = true }, enabled = state.npubFull.isNotBlank()) {
                    Icon(Icons.Default.QrCode, stringResource(R.string.account_show_qr))
                }
            }
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoHeading(stringResource(R.string.account_recovery_title), stringResource(R.string.account_recovery_help))
        Text(stringResource(R.string.account_backup_priority), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(if (backedUp) R.string.account_backup_acknowledged else R.string.key_label_not_backed_up),
            style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onCopyNsec, modifier = Modifier.fillMaxWidth().testTag("account_copy_secret")) {
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

internal fun restartApp(context: Context, openBackup: Boolean = false) {
    val intent = context.packageManager
        .getLaunchIntentForPackage(context.packageName)!!
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra("identity_switch", true)
    if (openBackup) intent.putExtra("navigate_to", "backup_settings")
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
