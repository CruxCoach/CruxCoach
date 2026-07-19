package com.cruxcoach.android.ui.aurora

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.R
import com.cruxcoach.android.aurora.AuroraImportProgress
import com.cruxcoach.android.aurora.AuroraImportResult
import com.cruxcoach.android.aurora.ImportCounts

/**
 * Aurora-data migration screen. Hosts the shared [MigrationFlowContent]
 * inside a Scaffold + back-arrow TopAppBar; same content composable can
 * be embedded in an onboarding ModalBottomSheet later (FEAT-005 §6.2)
 * without changes here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuroraMigrationScreen(
    onNavigateBack: () -> Unit,
    viewModel: AuroraMigrationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aurora_migration_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MigrationFlowContent(
                state = state,
                onPickFile = viewModel::importFromUri,
                onReset = viewModel::reset,
            )
        }
    }
}

/**
 * Shared body composable. Three numbered sections + inline result
 * area at the bottom — same flow, regardless of whether it's hosted
 * in the settings full-screen or a future onboarding bottom-sheet.
 */
@Composable
fun MigrationFlowContent(
    state: AuroraMigrationViewModel.State,
    onPickFile: (Uri) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val fallbackToast = stringResource(R.string.aurora_migration_email_copy_fallback_toast)
    val copiedToast = stringResource(R.string.aurora_migration_email_copy_toast)

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(onPickFile) },
    )

    Text(
        text = stringResource(R.string.aurora_migration_what_happened_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(R.string.aurora_migration_what_happened_body),
        style = MaterialTheme.typography.bodyMedium,
    )

    HorizontalDivider()

    NumberedStep(
        number = 1,
        title = stringResource(R.string.aurora_migration_step1_title),
        body = stringResource(R.string.aurora_migration_step1_body),
    ) {
        OutlinedButton(
            onClick = {
                // Body + subject are intentionally hard-coded in English
                // regardless of device locale: Aurora's support is an
                // English-speaking inbox, and the user only needs to fill
                // in the two `[…]` placeholders before sending.
                // To-address mirrors Kilter's official "Get Your Data Back"
                // page (support@, not the community-shared peter@).
                val intent = Intent(Intent.ACTION_SENDTO, "mailto:$AURORA_SUPPORT_EMAIL".toUri())
                    .putExtra(Intent.EXTRA_SUBJECT, AURORA_EMAIL_SUBJECT)
                    .putExtra(Intent.EXTRA_TEXT, AURORA_EMAIL_BODY)
                val launched = runCatching { context.startActivity(intent) }.isSuccess
                if (!launched) {
                    // No mailto-capable app on the device — fall back to
                    // clipboard so the user can manually paste into a
                    // webmail client. We mirror the same behaviour the
                    // explicit copy button below offers, plus an
                    // informational Toast so it isn't silent.
                    copyAuroraEmailToClipboard(context)
                    Toast.makeText(
                        context,
                        fallbackToast,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.aurora_migration_email_button))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                copyAuroraEmailToClipboard(context)
                Toast.makeText(
                    context,
                    copiedToast,
                    Toast.LENGTH_LONG,
                ).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.aurora_migration_email_copy_button))
        }
    }

    NumberedStep(
        number = 2,
        title = stringResource(R.string.aurora_migration_step2_title),
        body = stringResource(R.string.aurora_migration_step2_body),
    ) {
        Button(
            onClick = {
                pickFileLauncher.launch(arrayOf("application/json", "text/plain"))
            },
            enabled = !state.isImporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.FileUpload,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.aurora_migration_pick_file_button))
        }
    }

    if (state.isImporting) {
        ImportInFlightCard(state.progress)
    }
    state.result?.let { result ->
        Spacer(Modifier.height(8.dp))
        ImportResultCard(result, onReset = onReset)
    }
}

@Composable
private fun NumberedStep(
    number: Int,
    title: String,
    body: String,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
            content()
        }
    }
}

@Composable
private fun ImportInFlightCard(progress: AuroraImportProgress?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.aurora_migration_importing),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val (label, frac) = when (progress) {
                AuroraImportProgress.Parsing -> stringResource(R.string.aurora_migration_progress_parsing) to null
                is AuroraImportProgress.ResolvingClimbNames ->
                    stringResource(R.string.aurora_migration_progress_resolving, progress.totalNames) to null
                is AuroraImportProgress.ImportingClimbs ->
                    stringResource(R.string.aurora_migration_progress_climbs, progress.current, progress.total) to
                        if (progress.total > 0) progress.current.toFloat() / progress.total else null
                is AuroraImportProgress.ImportingAscents ->
                    stringResource(R.string.aurora_migration_progress_ascents, progress.current, progress.total) to
                        if (progress.total > 0) progress.current.toFloat() / progress.total else null
                is AuroraImportProgress.ImportingBids ->
                    stringResource(R.string.aurora_migration_progress_bids, progress.current, progress.total) to
                        if (progress.total > 0) progress.current.toFloat() / progress.total else null
                is AuroraImportProgress.ImportingCircuits ->
                    stringResource(R.string.aurora_migration_progress_circuits, progress.current, progress.total) to
                        if (progress.total > 0) progress.current.toFloat() / progress.total else null
                AuroraImportProgress.Done -> stringResource(R.string.aurora_migration_progress_finalising) to null
                null -> stringResource(R.string.aurora_migration_progress_starting) to null
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (frac != null) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ImportResultCard(
    result: AuroraImportResult,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = if (result.parseError != null) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (result.parseError != null) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (result.parseError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (result.parseError != null)
                        stringResource(R.string.aurora_migration_result_failed)
                    else stringResource(R.string.aurora_migration_result_success),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (result.parseError != null) {
                Text(
                    text = stringResource(
                        R.string.aurora_migration_result_parse_error,
                        result.parseError,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                CountRow(
                    label = stringResource(R.string.aurora_migration_count_ascents),
                    counts = result.ascents,
                )
                CountRow(
                    label = stringResource(R.string.aurora_migration_count_bids),
                    counts = result.bids,
                )
                CountRow(
                    label = stringResource(R.string.aurora_migration_count_circuits),
                    counts = result.circuits,
                )
                if (result.climbs.total > 0) {
                    CountRow(
                        label = stringResource(R.string.aurora_migration_count_climbs),
                        counts = result.climbs,
                    )
                }
                if (result.unresolvedClimbNames.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.aurora_migration_unresolved_title,
                            result.unresolvedClimbNames.size,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = result.unresolvedClimbNames.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.aurora_migration_import_another))
            }
        }
    }
}

@Composable
private fun CountRow(label: String, counts: ImportCounts) {
    Text(
        text = stringResource(
            R.string.aurora_migration_count_summary,
            label,
            counts.imported,
            counts.skipped,
            counts.failed,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private const val AURORA_SUPPORT_EMAIL = "support@auroraclimbing.com"

private const val AURORA_EMAIL_SUBJECT = "Data export request"

private val AURORA_EMAIL_BODY = """
    Hi Aurora support,

    Please send me a copy of my Kilter Board account data export.

    Old app username: [your old username]
    Email on old app: [your old email]

    Thanks!
""".trimIndent()

/** Mirror of what the mailto-intent puts into a fresh email — flat blob
 *  with To/Subject prefixes so the user can paste it into a webmail
 *  client and immediately see what goes where. */
private fun composeAuroraEmailClipboardText(): String = buildString {
    append("To: ").append(AURORA_SUPPORT_EMAIL).append('\n')
    append("Subject: ").append(AURORA_EMAIL_SUBJECT).append("\n\n")
    append(AURORA_EMAIL_BODY)
}

private fun copyAuroraEmailToClipboard(context: Context) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(
        ClipData.newPlainText("Aurora data export request", composeAuroraEmailClipboardText()),
    )
}
