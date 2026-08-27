package com.cruxcoach.android.ui.bodystat

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataExportScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: DataExchangeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val exportBugReportTitle = stringResource(R.string.error_bug_report_export_title)
    val snackbarHostState = SnackbarHostState()

    val jsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportBackup(it) }
    }
    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportBackup(it) }
    }
    val excelExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
    ) { uri: Uri? ->
        uri?.let { viewModel.exportBackup(it) }
    }

    LaunchedEffect(state.pendingShare) {
        state.pendingShare?.let { share ->
            try {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = share.mimeType
                            putExtra(Intent.EXTRA_STREAM, share.uri)
                            clipData = ClipData.newRawUri("CruxCoach export", share.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        context.getString(R.string.export_share_chooser),
                    ),
                )
            } finally {
                viewModel.shareHandled()
            }
        }
    }

    // Success messages still use Snackbar
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            delay(1000)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.bodystat_export)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("data_export_back")
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
                            exportBugReportTitle,
                            error
                        )
                        viewModel.clearMessage()
                    }
                )
            }

            Text(
                stringResource(R.string.export_format_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.exportFormat == DataExchangeFormat.JSON,
                    onClick = { viewModel.setExportFormat(DataExchangeFormat.JSON) },
                    label = { Text(stringResource(R.string.export_format_json)) },
                )
                FilterChip(
                    selected = state.exportFormat == DataExchangeFormat.CSV_ZIP,
                    onClick = { viewModel.setExportFormat(DataExchangeFormat.CSV_ZIP) },
                    label = { Text(stringResource(R.string.export_format_csv)) },
                )
                FilterChip(
                    selected = state.exportFormat == DataExchangeFormat.EXCEL,
                    onClick = { viewModel.setExportFormat(DataExchangeFormat.EXCEL) },
                    label = { Text(stringResource(R.string.export_format_excel)) },
                )
            }

            if (state.exportFormat == DataExchangeFormat.EXCEL) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("excel_not_backup_notice"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.export_excel_not_backup),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { viewModel.selectAllExportCategories() }) {
                        Text(stringResource(R.string.bodystat_all), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { viewModel.deselectAllExportCategories() }) {
                        Text(stringResource(R.string.bodystat_none), style = MaterialTheme.typography.labelSmall)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        VISIBLE_CATEGORIES.forEach { category ->
                            CategoryCheckboxRow(
                                label = category.localizedLabel(),
                                checked = category in state.exportCategories,
                                onCheckedChange = { viewModel.toggleExportCategory(category) }
                            )
                        }
                    }
                }
            }

            val exportEnabled = !state.isExporting && state.exportCategories.isNotEmpty()

            Button(
                onClick = {
                    when (state.exportFormat) {
                        DataExchangeFormat.JSON -> jsonExportLauncher.launch(viewModel.exportFilename())
                        DataExchangeFormat.CSV_ZIP -> csvExportLauncher.launch(viewModel.exportFilename())
                        DataExchangeFormat.EXCEL -> excelExportLauncher.launch(viewModel.exportFilename())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("full_export_button"),
                enabled = exportEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (state.isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.export_save_file), fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(
                onClick = { viewModel.shareExport() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("share_export_button"),
                enabled = exportEnabled,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.export_share_app), fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
internal fun CategoryCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    count: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = OrangeAccent)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (count != null) {
            Text(
                text = count,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
