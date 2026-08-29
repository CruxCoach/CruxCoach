package com.cruxcoach.android.ui.bodystat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.ErrorCard
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.data.CruxCoachBackup.Category
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataImportScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: DataExchangeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val importBugReportTitle = stringResource(R.string.error_bug_report_import_title)
    val snackbarHostState = SnackbarHostState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadImportPreview(it) }
    }

    // Success messages still use Snackbar
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            delay(1000)
            viewModel.clearMessage()
        }
    }

    if (state.importPubkeyMismatch) {
        PubkeyMismatchDialog(
            sourcePubkeyHex = state.importSourcePubkey ?: "",
            onConfirm = { viewModel.confirmMismatchImport() },
            onDismiss = { viewModel.dismissPubkeyMismatch() }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.bodystat_import)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("data_import_back")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Error card (persistent, with bug report action)
            state.error?.let { error ->
                ErrorCard(
                    error = error,
                    onDismiss = { viewModel.clearMessage() },
                    onReportBug = {
                        onNavigateToBugReport(
                            importBugReportTitle,
                            error
                        )
                        viewModel.clearMessage()
                    }
                )
            }

            // Persistent banner while a cross-pubkey import override is
            // active. Confirms the user hasn't forgotten the override
            // between the dialog and the Import button, and discloses the
            // downstream consequence (climbs land under the active
            // Kilter account on republish).
            if (state.importMismatchAccepted) {
                val sourceNpub = remember(state.importSourcePubkey) {
                    val hex = state.importSourcePubkey
                    if (hex.isNullOrBlank()) "?" else try {
                        val npub = hex.hexToByteArray().toNpub()
                        if (npub.length > 20) "${npub.take(12)}…${npub.takeLast(6)}" else npub
                    } catch (_: Exception) {
                        hex.take(12) + "…"
                    }
                }
                PubkeyOverrideBanner(sourceNpubTruncated = sourceNpub)
            }

            if (state.importPreview == null) {
                Text(
                    stringResource(R.string.bodystat_select_file),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    stringResource(R.string.import_auto_detect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf(
                            "application/json",
                            "application/zip",
                            "application/x-zip-compressed",
                        ))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("import_pick_file"),
                    enabled = !state.isLoadingPreview,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (state.isLoadingPreview) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.bodystat_analyzing_file))
                    } else {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.bodystat_choose_file))
                    }
                }
            } else {
                ImportPreviewCard(
                    preview = state.importPreview!!,
                    selectedCategories = state.importCategories,
                    onToggleCategory = { viewModel.toggleImportCategory(it) },
                    onConfirm = { viewModel.confirmImport() },
                    onCancel = { viewModel.cancelImport() },
                    isImporting = state.isImporting,
                    boardImportInProgress = state.boardImportInProgress,
                    waitingForBoardSync = state.waitingForBoardSync,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ImportPreviewCard(
    preview: com.cruxcoach.data.CruxCoachBackup.ImportPreview,
    selectedCategories: Set<Category>,
    onToggleCategory: (Category) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isImporting: Boolean,
    boardImportInProgress: Boolean,
    waitingForBoardSync: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = InfoBlue.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.import_backup_detected),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = InfoBlue
            )

            Text(
                stringResource(R.string.import_select_categories),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val detected = preview.detectedCategories()
            VISIBLE_CATEGORIES.forEach { category ->
                if (category in detected) {
                    CategoryCheckboxRow(
                        label = category.localizedLabel(),
                        checked = category in selectedCategories,
                        onCheckedChange = { onToggleCategory(category) },
                        count = preview.summaryLine(category)
                    )
                }
            }

            if (detected.isEmpty()) {
                Text(
                    stringResource(R.string.import_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                stringResource(R.string.import_duplicate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (boardImportInProgress || waitingForBoardSync) {
                Text(
                    text = stringResource(
                        if (waitingForBoardSync) {
                            R.string.import_waiting_for_board_data
                        } else {
                            R.string.import_board_data_not_ready
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting && !boardImportInProgress && selectedCategories.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (waitingForBoardSync) {
                                    R.string.import_waiting_for_board_data_short
                                } else {
                                    R.string.import_in_progress_short
                                },
                            ),
                        )
                    } else {
                        Text(stringResource(R.string.bodystat_import), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PubkeyOverrideBanner(sourceNpubTruncated: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.import_pubkey_override_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    stringResource(
                        R.string.import_pubkey_override_banner_body,
                        sourceNpubTruncated,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun PubkeyMismatchDialog(
    sourcePubkeyHex: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val npubTruncated = try {
        val npub = sourcePubkeyHex.hexToByteArray().toNpub()
        if (npub.length > 20) "${npub.take(12)}...${npub.takeLast(6)}" else npub
    } catch (_: Exception) {
        sourcePubkeyHex.take(12) + "..."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.import_pubkey_mismatch_title)) },
        text = { Text(stringResource(R.string.import_pubkey_mismatch_body, npubTruncated)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.import_pubkey_mismatch_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
