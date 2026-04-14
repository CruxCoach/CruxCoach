package com.cruxcoach.android.ui.bodystat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataExportScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: DataExchangeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = SnackbarHostState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(viewModel.exportMimeType())
    ) { uri: Uri? ->
        uri?.let { viewModel.exportBackup(it) }
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
                            context.getString(R.string.error_bug_report_export_title),
                            error
                        )
                        viewModel.clearMessage()
                    }
                )
            }

            // Format selector hidden — only CruxCoach backup is exposed for now.
            // Text(
            //     stringResource(R.string.export_format_title),
            //     style = MaterialTheme.typography.titleMedium,
            //     fontWeight = FontWeight.Bold
            // )
            //
            // ExportFormatSelector(
            //     selectedFormat = state.exportFormat,
            //     onFormatSelected = { viewModel.setExportFormat(it) }
            // )

            AnimatedVisibility(visible = state.exportFormat == ExportFormat.CRUXCOACH) {
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
                                    label = category.label,
                                    checked = category in state.exportCategories,
                                    onCheckedChange = { viewModel.toggleExportCategory(category) }
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = state.exportFormat != ExportFormat.CRUXCOACH) {
                Text(
                    stringResource(R.string.export_waistline_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val exportEnabled = !state.isExporting && (
                state.exportFormat != ExportFormat.CRUXCOACH || state.exportCategories.isNotEmpty()
            )
            val exportLabel = when (state.exportFormat) {
                ExportFormat.CRUXCOACH -> stringResource(R.string.export_backup_label, state.exportCategories.size, VISIBLE_CATEGORIES.size)
                ExportFormat.WAISTLINE_JSON -> stringResource(R.string.export_waistline_json_label)
                ExportFormat.WAISTLINE_CSV -> stringResource(R.string.export_waistline_csv_label)
            }

            Button(
                onClick = { exportLauncher.launch(viewModel.exportFilename()) },
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
                    Text(exportLabel, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ExportFormatSelector(
    selectedFormat: ExportFormat,
    onFormatSelected: (ExportFormat) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            FormatRadioRow(
                label = stringResource(R.string.export_format_cruxcoach),
                subtitle = stringResource(R.string.export_format_cruxcoach_desc),
                selected = selectedFormat == ExportFormat.CRUXCOACH,
                onClick = { onFormatSelected(ExportFormat.CRUXCOACH) }
            )
            FormatRadioRow(
                label = stringResource(R.string.export_format_waistline_json),
                subtitle = stringResource(R.string.export_format_waistline_json_desc),
                selected = selectedFormat == ExportFormat.WAISTLINE_JSON,
                onClick = { onFormatSelected(ExportFormat.WAISTLINE_JSON) }
            )
            FormatRadioRow(
                label = stringResource(R.string.export_format_waistline_csv),
                subtitle = stringResource(R.string.export_format_waistline_csv_desc),
                selected = selectedFormat == ExportFormat.WAISTLINE_CSV,
                onClick = { onFormatSelected(ExportFormat.WAISTLINE_CSV) }
            )
        }
    }
}

@Composable
private fun FormatRadioRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = OrangeAccent)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
