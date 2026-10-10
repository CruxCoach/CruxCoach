package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardCleaningState
import com.cruxcoach.android.ble.ConnectionState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardCleaningSheet(onDismiss: () -> Unit, viewModel: BoardCleaningViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val board by viewModel.board.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val relay by viewModel.relayState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var showConnection by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(30_000) // Refresh an open sheet across the local date boundary.
        }
    }
    if (showConnection) {
        BleConnectionSheet(onDismiss = { showConnection = false })
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        BoardCleaningContent(
            state = state,
            boardName = board?.displayName,
            connected = connection == ConnectionState.CONNECTED || connection == ConnectionState.SENDING,
            ready = connection == ConnectionState.CONNECTED,
            relayEnabled = relay.enabled,
            error = error,
            onConnect = { showConnection = true },
            onStart = viewModel::start,
            onFinish = viewModel::finish,
            onClose = onDismiss,
        )
    }
}

@Composable
internal fun BoardCleaningContent(
    state: BoardCleaningState,
    boardName: String?,
    connected: Boolean,
    ready: Boolean,
    relayEnabled: Boolean,
    error: Int?,
    onConnect: () -> Unit,
    onStart: () -> Unit,
    onFinish: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp)
            .testTag("board_cleaning_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.board_cleaning_title), style = MaterialTheme.typography.titleLarge)
        boardName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(stringResource(R.string.board_cleaning_description))
        when {
            !connected -> {
                Text(stringResource(R.string.board_cleaning_connect_hint))
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.board_cleaning_connect))
                }
            }
            !state.available -> Text(stringResource(R.string.board_cleaning_unsupported))
            else -> {
                Text(
                    pluralStringResource(R.plurals.board_cleaning_count, state.holdCount, state.holdCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.holdCount == 0) Text(stringResource(R.string.board_cleaning_empty))
                if (relayEnabled) Text(stringResource(R.string.board_cleaning_relay))
                if (state.active) {
                    Text(stringResource(R.string.board_cleaning_active_hint))
                    Button(
                        onClick = { onFinish(true) },
                        enabled = ready && !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("board_cleaning_done"),
                    ) { Text(stringResource(R.string.board_cleaning_done)) }
                    OutlinedButton(
                        onClick = { onFinish(false) },
                        enabled = ready && !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("board_cleaning_stop"),
                    ) { Text(stringResource(R.string.board_cleaning_stop)) }
                }
                Button(
                    onClick = onStart,
                    enabled = ready && !state.busy && !relayEnabled && state.holdCount > 0,
                    modifier = Modifier.fillMaxWidth().testTag("board_cleaning_start"),
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (state.active) R.string.board_cleaning_retry else R.string.board_cleaning_start))
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_close))
        }
    }
}

/** Visible on every board screen even after the cleaning sheet is dismissed. */
@Composable
fun BoardCleaningBanner(viewModel: BoardCleaningViewModel = hiltViewModel()): Boolean {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    if (showSheet) BoardCleaningSheet(onDismiss = { showSheet = false }, viewModel = viewModel)
    if (!state.active) return false
    FilledTonalButton(
        onClick = { showSheet = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("board_cleaning_banner"),
    ) {
        Icon(Icons.Default.CleaningServices, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.board_cleaning_active))
    }
    return true
}
