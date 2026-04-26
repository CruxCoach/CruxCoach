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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.nostr.AmberIntegration
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.util.ApkShareHelper
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
                title = { Text(stringResource(R.string.key_section_account_keys)) },
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
            // Backup reminder. Same UserPreferences.keyBackedUp flag as
            // the Cloud-Backup section's BackupKeyWarningCard, so
            // acknowledging here also hides the warning there.
            if (!state.keyBackedUp && state.signerMode == SignerMode.LOCAL) {
                var showAckDialog by remember { mutableStateOf(false) }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.key_label_not_backed_up),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.key_label_not_backed_up_desc),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { showAckDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.backup_key_warning_acknowledged))
                        }
                    }
                }
                if (showAckDialog) {
                    AlertDialog(
                        onDismissRequest = { showAckDialog = false },
                        title = { Text(stringResource(R.string.backup_key_warning_ack_dialog_title)) },
                        text = { Text(stringResource(R.string.backup_key_warning_ack_dialog_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showAckDialog = false
                                viewModel.acknowledgeKeyBackup()
                            }) {
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

            // Login method
            LoginMethodCard(
                signerMode = state.signerMode,
                amberPubkeyDisplay = state.amberPubkeyDisplay
            )

            // npub section
            NpubSection(
                npub = state.npubDisplay,
                isInactive = state.signerMode == SignerMode.AMBER,
                onCopy = {
                    copyToClipboard(context, state.npubFull, "npub", sensitive = false)
                    Toast.makeText(context, context.getString(R.string.key_toast_npub_copied), Toast.LENGTH_SHORT).show()
                }
            )

            // nsec section (only for local signer)
            if (state.signerMode == SignerMode.LOCAL) {
                NsecSection(
                    onCopyNsec = {
                        requestBiometric(
                            context = context,
                            onSuccess = { showNsecWarning = true },
                            onUnavailable = { showBiometricUnavailable = true },
                            onNoHardware = {
                                noSecurityPendingAction = { showNsecWarning = true }
                                showNoSecurityWarning = true
                            }
                        )
                    },
                    onImportNsec = onNavigateToImport,
                )

                // Warning text
                Text(
                    text = stringResource(R.string.key_label_nsec_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Amber section
            AmberSection(
                isAmberActive = state.signerMode == SignerMode.AMBER,
                amberPubkeyDisplay = state.amberPubkeyDisplay,
                onSetup = {
                    if (AmberIntegration.isInstalled(context)) {
                        amberLauncher.launch(AmberIntegration.buildGetPubkeyIntent())
                    } else {
                        showAmberNotInstalled = true
                    }
                },
                onDisconnect = { viewModel.switchToLocalSigner() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Import lives inside NsecSection now (paired with Copy nsec).
            // Amber users who want to import a different account: tap
            // "Switch to local key" first → NsecSection appears with the
            // Import option.
        }
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

@Composable
private fun LoginMethodCard(
    signerMode: SignerMode,
    amberPubkeyDisplay: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (signerMode == SignerMode.AMBER) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.key_label_login_method),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (signerMode == SignerMode.AMBER) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = when (signerMode) {
                        SignerMode.LOCAL -> stringResource(R.string.key_label_login_local)
                        SignerMode.AMBER -> stringResource(R.string.key_label_login_amber)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                if (signerMode == SignerMode.AMBER) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = OrangeAccent
                    )
                }
            }
            if (signerMode == SignerMode.AMBER && amberPubkeyDisplay != null) {
                Text(
                    text = amberPubkeyDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun NpubSection(
    npub: String,
    isInactive: Boolean,
    onCopy: () -> Unit
) {
    Text(
        text = if (isInactive) {
            stringResource(R.string.key_label_local_key_inactive)
        } else {
            stringResource(R.string.key_section_npub)
        },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = if (isInactive) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = truncateKey(npub),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = if (isInactive) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.key_button_copy_npub),
                    tint = if (isInactive) {
                        OrangeAccent.copy(alpha = 0.5f)
                    } else {
                        OrangeAccent
                    }
                )
            }
        }
    }
}

@Composable
private fun NsecSection(
    onCopyNsec: () -> Unit,
    onImportNsec: () -> Unit,
) {
    Text(
        text = stringResource(R.string.key_section_nsec),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    // Recovery reminder — surfaces the dual-purpose role of the nsec
    // (account + cloud backup recovery) right next to the export/import
    // affordances, so it's hard to miss the "save this somewhere" intent.
    Text(
        text = stringResource(R.string.key_label_nsec_save_reminder),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.key_label_nsec_hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onCopyNsec,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(" " + stringResource(R.string.key_button_copy_nsec), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onImportNsec,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.key_button_import_nsec), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AmberSection(
    isAmberActive: Boolean,
    amberPubkeyDisplay: String?,
    onSetup: () -> Unit,
    onDisconnect: () -> Unit
) {
    Text(
        text = if (isAmberActive) {
            stringResource(R.string.key_label_active_identity)
        } else {
            stringResource(R.string.key_section_recommended)
        },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAmberActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (isAmberActive) Icons.Default.CheckCircle else Icons.Default.Shield,
                    contentDescription = null,
                    tint = OrangeAccent
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.key_label_use_amber),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isAmberActive && amberPubkeyDisplay != null) {
                        Text(
                            text = amberPubkeyDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        text = if (isAmberActive) {
                            stringResource(R.string.key_label_amber_active)
                        } else {
                            stringResource(R.string.key_label_amber_inactive)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isAmberActive) {
                    OutlinedButton(
                        onClick = onSetup,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.key_button_setup))
                    }
                }
            }
            if (isAmberActive) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.key_button_disconnect_amber))
                }
            }
        }
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
