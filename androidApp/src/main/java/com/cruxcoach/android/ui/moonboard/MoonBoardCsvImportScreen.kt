package com.cruxcoach.android.ui.moonboard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.moonboard.MoonBoardAccessibilityBridge
import com.cruxcoach.android.ui.theme.OrangeAccent

/**
 * Whether the official Moon app is reachable from here. The manifest declares
 * it under <queries>, so package visibility does not hide it on Android 11+.
 */
private fun isMoonAppInstalled(context: android.content.Context): Boolean =
    context.packageManager.getLaunchIntentForPackage("com.trainingboard.moon") != null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoonBoardCsvImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: MoonBoardCsvImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanState by MoonBoardAccessibilityBridge.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Without Moon there is nothing to transfer, and finding that out *after*
    // being sent to Android's accessibility settings and granting a service is
    // a bad trade to ask of anyone. Re-checked on every resume so installing
    // Moon and coming back does the obvious thing.
    val lifecycleOwner = LocalLifecycleOwner.current
    var moonInstalled by remember { mutableStateOf(isMoonAppInstalled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) moonInstalled = isMoonAppInstalled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val requestSubject = stringResource(R.string.moon_csv_request_subject)
    val requestBody = stringResource(R.string.moon_csv_request_body)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::import)
    }
    val result = scanState.result ?: state.result
    result?.let { importResult ->
        val failed = importResult.error != null
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    if (failed || importResult.notImported > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                )
            },
            title = {
                Text(stringResource(if (failed) R.string.moon_csv_failed else R.string.moon_import_result_title))
            },
            text = {
                if (failed) {
                    Text(importResult.error.orEmpty())
                } else {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (importResult.sessionsExpected > 0) {
                            Text(stringResource(
                                R.string.moon_import_result_sessions,
                                importResult.sessionsScanned,
                                importResult.sessionsExpected,
                            ))
                        }
                        if (importResult.expectedEntries > 0) {
                            Text(stringResource(R.string.moon_import_result_expected, importResult.expectedEntries))
                        }
                        Text(stringResource(R.string.moon_import_result_found, importResult.foundEntries))
                        Text(stringResource(R.string.moon_import_result_imported, importResult.imported))
                        Text(stringResource(R.string.moon_import_result_duplicates, importResult.duplicates))
                        if (importResult.sessionsSkipped > 0) {
                            Text(stringResource(R.string.moon_import_result_skipped, importResult.sessionsSkipped))
                        }
                        Text(stringResource(R.string.moon_import_result_not_imported, importResult.notImported))
                        if (importResult.replacedEntries > 0) {
                            Text(stringResource(R.string.moon_import_result_replaced, importResult.replacedEntries))
                        }
                        if (importResult.snapshotOnly > 0) {
                            Text(stringResource(R.string.moon_import_result_snapshots, importResult.snapshotOnly))
                        }
                        if (importResult.unresolvedLabels.isNotEmpty()) {
                            Text(importResult.unresolvedLabels.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        }
                        // Every session Moon announced but the scan could not
                        // fully read is named here rather than being rolled up
                        // into a single "import failed".
                        if (importResult.warnings.isNotEmpty()) {
                            Text(
                                stringResource(R.string.moon_import_result_warnings),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                importResult.warnings.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.reset()
                    MoonBoardAccessibilityBridge.reset()
                }) { Text(stringResource(R.string.moon_import_result_close)) }
            },
        )
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.moon_csv_title)) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.moon_csv_intro), style = MaterialTheme.typography.bodyLarge)
            Card(
                colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = .10f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.moon_device_title), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(
                            if (moonInstalled) R.string.moon_device_explanation
                            else R.string.moon_device_not_installed,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { MoonBoardAccessibilityBridge.start(context) },
                        enabled = moonInstalled && !scanState.running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (scanState.running) CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp), strokeWidth = 2.dp,
                        )
                        Text(stringResource(
                            when {
                                !moonInstalled -> R.string.moon_device_unavailable
                                scanState.running -> R.string.moon_device_running
                                scanState.serviceConnected -> R.string.moon_device_start
                                else -> R.string.moon_device_enable
                            },
                        ))
                    }
                    if (scanState.running) {
                        Text(scanState.status, style = MaterialTheme.typography.bodySmall)
                        // Determinate as soon as Moon's logbook header has told
                        // the scan how many training days there are.
                        val progress = scanState.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(
                                    R.string.moon_device_progress,
                                    scanState.sessionsDone,
                                    scanState.sessionsTotal,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            stringResource(R.string.moon_device_captured, scanState.captured),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Everything read so far is already stored, so stopping
                        // is cheap and the next run resumes where this one got to.
                        OutlinedButton(
                            onClick = { MoonBoardAccessibilityBridge.cancel() },
                            enabled = !scanState.cancelling,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(
                                if (scanState.cancelling) R.string.moon_device_cancelling
                                else R.string.moon_device_cancel,
                            ))
                        }
                    }
                }
            }
            Text(stringResource(R.string.moon_csv_fallback), fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(requestBody))
                    uriHandler.openUri(
                        "mailto:moonboardsupport@moonclimbing.com?subject=${Uri.encode(requestSubject)}",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.moon_csv_request_data))
            }
            Text(stringResource(R.string.moon_csv_request_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.moon_csv_angle_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.moon_csv_privacy), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/*")) },
                enabled = !state.importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.importing) CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.FileUpload, contentDescription = null)
                Text(stringResource(if (state.importing) R.string.moon_csv_importing else R.string.moon_csv_choose))
            }
        }
    }
}
