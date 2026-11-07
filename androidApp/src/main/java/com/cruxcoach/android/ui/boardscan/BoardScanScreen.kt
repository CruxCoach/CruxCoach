package com.cruxcoach.android.ui.boardscan

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import java.text.NumberFormat

/**
 * Board scan (#65): the camera view of the active board and a scan button; the answer shows below the picture.
 * In test mode every answer is checked against the problem the user says was lit.
 */
@Composable
fun BoardScanScreen(
    onNavigateBack: () -> Unit,
    onOpenClimb: (uuid: String, angle: Int) -> Unit,
    viewModel: BoardScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    // the camera permission is asked only here, when a scan is opened
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    var showResults by remember { mutableStateOf(false) }

    LaunchedEffect(state.shareFile) {
        val file = state.shareFile ?: return@LaunchedEffect
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            // a broad type, so every app that takes files is offered; the CSV type travels as metadata
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv"))
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.board_scan_test_share_title)))
        } catch (e: Exception) {
            Log.w("BoardScan", "share failed", e)
        } finally {
            viewModel.shareHandled()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("board_scan_screen")) {
        if (state.board == null) {
            // the camera starts once it is known whether this build reads frames from files instead
        } else if (state.fileFed) {
            Text("File frames", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else if (granted) {
            ScanCameraView(viewModel.grabber, onCameraError = viewModel::onCameraError, modifier = Modifier.fillMaxSize())
        } else if (asked) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.board_scan_permission), color = Color.White)
                TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.board_scan_permission_allow))
                }
            }
        }
        ScanTopBar(state, onNavigateBack, Modifier.align(Alignment.TopCenter))
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ScanPanel(state, granted, viewModel, onNavigateBack, onOpenClimb, onShowResults = { showResults = true })
            }
        }
    }
    if (showResults) {
        ScanResultsDialog(state, onExport = viewModel::export, onClear = viewModel::clearResults, onDismiss = { showResults = false })
    }
}

@Composable
private fun ScanTopBar(state: BoardScanState, onNavigateBack: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("board_scan_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.board_scan_close), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.board_scan_title), color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (state.boardLabel.isNotEmpty()) {
                Text(state.boardLabel, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            stringResource(R.string.board_scan_experimental), color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 12.dp),
        )
    }
}

@Composable
private fun ScanPanel(
    state: BoardScanState,
    cameraGranted: Boolean,
    viewModel: BoardScanViewModel,
    onNavigateBack: () -> Unit,
    onOpenClimb: (String, Int) -> Unit,
    onShowResults: () -> Unit,
) {
    when (state.phase) {
        BoardScanPhase.PREPARING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.board_scan_preparing))
        }
        BoardScanPhase.UNSUPPORTED, BoardScanPhase.NO_CATALOGUE -> {
            Text(stringResource(
                if (state.phase == BoardScanPhase.UNSUPPORTED) R.string.board_scan_unsupported else R.string.board_scan_no_catalogue,
            ))
            OutlinedButton(onClick = onNavigateBack) { Text(stringResource(R.string.board_scan_close)) }
        }
        BoardScanPhase.READY -> {
            Text(pluralStringResource(R.plurals.board_scan_problems, state.problems, NumberFormat.getIntegerInstance().format(state.problems)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.board_scan_hint))
            if (state.cameraError) Text(stringResource(R.string.board_scan_camera_error), color = MaterialTheme.colorScheme.error)
            if (state.scanFailed) Text(stringResource(R.string.board_scan_failed), color = MaterialTheme.colorScheme.error)
            Button(
                onClick = viewModel::scan, enabled = cameraGranted || state.fileFed,
                modifier = Modifier.fillMaxWidth().testTag("board_scan_button"),
            ) { Text(stringResource(R.string.board_scan_button)) }
            TestModeRow(state, viewModel, onShowResults)
        }
        BoardScanPhase.SCANNING -> {
            Text(stringResource(R.string.board_scan_looking, state.frame))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.candidates.forEach { CandidateRow(it, enabled = false, onClick = {}) }
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth().testTag("board_scan_stop")) {
                Text(stringResource(R.string.board_scan_stop))
            }
        }
        BoardScanPhase.ANSWER -> AnswerPanel(state, viewModel, onOpenClimb)
    }
}

@Composable
private fun TestModeRow(state: BoardScanState, viewModel: BoardScanViewModel, onShowResults: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).toggleable(state.testMode, role = Role.Switch, onValueChange = viewModel::setTestMode)
                .testTag("board_scan_test_mode"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(state.testMode, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.board_scan_test_mode))
        }
        if (state.testMode) {
            TextButton(onClick = onShowResults, modifier = Modifier.testTag("board_scan_results")) {
                Text(stringResource(R.string.board_scan_test_results))
            }
        }
    }
    if (state.testMode) {
        FilterChip(
            selected = state.climberInFront, onClick = { viewModel.setClimberInFront(!state.climberInFront) },
            label = { Text(stringResource(R.string.board_scan_test_climber)) },
            modifier = Modifier.testTag("board_scan_test_climber"),
        )
        if (state.saved > 0) {
            Text(stringResource(R.string.board_scan_test_saved, state.saved), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Seconds with one decimal, in the user's locale. */
internal fun seconds(ms: Long): String = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(ms / 1000.0)
