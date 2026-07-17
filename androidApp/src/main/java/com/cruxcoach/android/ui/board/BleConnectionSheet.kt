package com.cruxcoach.android.ui.board

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.android.ui.common.LocalBleShareManager
import com.cruxcoach.android.data.SessionRole
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleConnectionSheet(
    onDismiss: () -> Unit,
    onNavigateToClimb: ((uuid: String, angle: Int) -> Unit)? = null,
    autoStartScan: Boolean = false,
    sessionRole: SessionRole = SessionRole.NONE,
    viewModel: BleConnectionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // BleConnectionViewModel is scoped per nav-backstack entry, so the board
    // browser and the detail screen hold separate instances — a permission
    // grant in one leaves the other's cached hasPermissions stale at false.
    // Android has no permission-change broadcast (nor a location-toggle one),
    // so re-check the live OS permission + Bluetooth + location state on every
    // sheet open AND on every resume: registering the observer replays
    // ON_RESUME for an already-resumed owner, and returning from the system
    // location settings (LocationDisabledContent's button) re-fires it — the
    // only feedback path for that toggle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Location services gate the *discovery scan* on API ≤ 30 only — never a
    // direct GATT connect, and nothing at all on API 31+ (BLUETOOTH_SCAN is
    // declared neverForLocation). Computed once here and used both to pick the
    // prompt branch below and to hold back the auto-scan: the prompt must only
    // ever show while the flow it blocks genuinely cannot proceed.
    val locationPromptNeeded = BlePermissionHelper.isLocationRequired(
        apiLevel = Build.VERSION.SDK_INT,
        flowNeedsScan = true, // this sheet's disconnected flow discovers boards by scanning
        locationEnabled = state.isLocationEnabled
    )

    // Auto-close once the connect succeeds — the top-bar BLE icon flips
    // to green (BluetoothConnected) so the sheet has no further purpose.
    // Only on the transition into CONNECTED, not when the sheet was
    // opened while already connected (user then wants the Disconnect UI).
    // Brief delay so the user catches a glimpse of the "Verbunden" state.
    val initialConnectionState = remember { state.connectionState }
    LaunchedEffect(state.connectionState) {
        if (state.connectionState == ConnectionState.CONNECTED &&
            initialConnectionState != ConnectionState.CONNECTED
        ) {
            kotlinx.coroutines.delay(400L)
            onDismiss()
        }
    }

    // Auto-start scan when sheet opens (if permissions granted, BT enabled and
    // the scan isn't location-gated — starting one behind the location prompt
    // would contradict it: the OS accepts the registration and, depending on
    // the device, may even deliver results and auto-connect while the sheet
    // still claims location is required). Use the auto-connect-on-single
    // variant: after a 2 s settling window, if exactly one board was found,
    // the VM connects without the user tapping the list entry. 2+ boards
    // leave the list visible for manual pick. Keying on locationPromptNeeded
    // also re-fires the effect the moment the user comes back from settings
    // with location enabled.
    if (autoStartScan) {
        LaunchedEffect(state.hasPermissions, state.isBluetoothEnabled, locationPromptNeeded) {
            if (state.hasPermissions && state.isBluetoothEnabled && !locationPromptNeeded &&
                state.connectionState == ConnectionState.DISCONNECTED && !state.isScanning) {
                viewModel.startScanWithAutoConnect()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.onPermissionsGranted()
        }
    }

    // System dialog that asks the user to turn Bluetooth on. The
    // BoardBleScanner's broadcast-receiver picks up the resulting
    // ACTION_STATE_CHANGED → bluetoothEnabled flips → the
    // BluetoothDisabled branch unmounts and the sheet drops into the
    // scan flow. No further action needed on the result callback.
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result is observed via the state flow above */ }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("ble_connection_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.board_ble_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            when {
                // State 1: Permissions missing
                !state.hasPermissions -> {
                    PermissionContent(
                        onRequestPermissions = {
                            permissionLauncher.launch(BlePermissionHelper.getRequiredPermissions())
                        }
                    )
                }

                // State 2: Bluetooth disabled. Auto-fire the system
                // "turn Bluetooth on?" dialog the moment the user lands
                // on this branch — pre-fix the user saw only a static
                // "Bluetooth ist aus" hint and had to leave the app to
                // toggle it manually. The same launcher is wired to a
                // retry button below in case the system dialog was
                // dismissed without enabling.
                !state.isBluetoothEnabled -> {
                    LaunchedEffect(Unit) {
                        bluetoothEnableLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        )
                    }
                    BluetoothDisabledContent(
                        onRequestEnable = {
                            bluetoothEnableLauncher.launch(
                                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            )
                        }
                    )
                }

                // State 3: Connected. Deliberately ranked above the location
                // gate: a live GATT connection never needs location services,
                // so an existing connection (session auto-connect, or one made
                // before the user toggled location off) must show as connected
                // instead of hiding behind an "enable location" prompt it
                // plainly contradicts.
                state.connectionState == ConnectionState.CONNECTED ||
                state.connectionState == ConnectionState.SENDING -> {
                    ConnectedContent(
                        boardName = state.connectedBoardName ?: "Board",
                        isSending = state.connectionState == ConnectionState.SENDING,
                        onDisconnect = { viewModel.disconnect() }
                    )
                }

                // State 4: Connecting — above the location gate for the same
                // reason as State 3.
                state.connectionState == ConnectionState.CONNECTING -> {
                    ConnectingContent(boardName = state.connectedBoardName)
                }

                // State 5: Session participant — board is controlled by host,
                // this device runs no discovery scan of its own here.
                sessionRole == SessionRole.PARTICIPANT -> {
                    SessionParticipantContent()
                }

                // State 5b: Location services off while the remaining flow is
                // the discovery scan, which the OS location-gates on API ≤ 30
                // (always false on API 31+, see BlePermissionHelper). Sits
                // directly above the scan branch it blocks — and only that
                // branch — so the message can never contradict an ongoing or
                // established connection.
                locationPromptNeeded -> {
                    LocationDisabledContent()
                }

                // State 6: Scanning / Board list
                else -> {
                    // Honest connect-failure reason from the last attempt
                    // (e.g. a pre-2017 RedBear-UART MoonBoard LED kit we
                    // can't drive yet) — otherwise the board just "drops"
                    // back into the scan list with no explanation.
                    state.connectFailureReason?.let { reasonRes ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = WarningYellow
                            )
                            Text(
                                stringResource(reasonRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = WarningYellow
                            )
                        }
                    }
                    val bleShareState by LocalBleShareManager.current.uiState.collectAsStateWithLifecycle()
                    ScanContent(
                        isScanning = state.isScanning,
                        boards = state.discoveredBoards,
                        bleShareState = bleShareState,
                        isRequestingDisconnect = state.isRequestingDisconnect,
                        climbSharingEnabled = state.climbSharingEnabled,
                        onStartScan = { viewModel.startScan() },
                        onStopScan = { viewModel.stopScan() },
                        onConnectBoard = { viewModel.connectToBoard(it) },
                        onRequestDisconnect = { viewModel.requestDisconnect() },
                        onClimbTapped = if (onNavigateToClimb != null) {
                            { uuid, angle -> onDismiss(); onNavigateToClimb(uuid, angle) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionContent(onRequestPermissions: () -> Unit) {
    Icon(
        Icons.Default.BluetoothDisabled,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        stringResource(R.string.board_ble_permission_title),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        stringResource(R.string.board_ble_permission_message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(
        onClick = onRequestPermissions,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_request_permissions"),
        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(stringResource(R.string.board_ble_grant_permission), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BluetoothDisabledContent(onRequestEnable: () -> Unit) {
    Icon(
        Icons.Default.BluetoothDisabled,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = ErrorRed
    )
    Text(
        stringResource(R.string.board_ble_bt_disabled_title),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        stringResource(R.string.board_ble_bt_disabled_message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(
        onClick = onRequestEnable,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
    ) {
        Text(
            stringResource(R.string.board_ble_bt_enable_button),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LocationDisabledContent() {
    val context = LocalContext.current
    Icon(
        Icons.Default.SignalCellularAlt,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = WarningYellow
    )
    Text(
        stringResource(R.string.board_ble_location_disabled_title),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        stringResource(R.string.board_ble_location_disabled_message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // Dead-end fixed: jump straight to the system toggle instead of making
    // the user find it themselves.
    OutlinedButton(
        onClick = {
            context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningYellow)
    ) {
        Text(stringResource(R.string.board_ble_open_location_settings), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SessionParticipantContent() {
    Icon(
        Icons.Default.BluetoothConnected,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        stringResource(R.string.board_ble_session_active_title),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        stringResource(R.string.board_ble_session_active_message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ConnectedContent(
    boardName: String,
    isSending: Boolean,
    onDisconnect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.BluetoothConnected,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = SuccessGreen
        )
        Column {
            Text(
                stringResource(R.string.board_ble_connected),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = SuccessGreen
            )
            // The advertised display name already identifies the board for every
            // brand (Kilter Board / MoonBoard / Tension Board / …), so show it
            // directly. The old "brand · name" prefix used a binary
            // MoonBoard-else-Kilter check that mislabeled every Aurora board
            // (e.g. Tension) as "Kilter Board".
            Text(
                boardName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (isSending) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = OrangeAccent,
                strokeWidth = 2.dp
            )
            Text(
                stringResource(R.string.board_ble_sending),
                style = MaterialTheme.typography.bodySmall,
                color = OrangeAccent
            )
        }
    }

    OutlinedButton(
        onClick = onDisconnect,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_disconnect"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(stringResource(R.string.board_ble_disconnect))
    }
}

@Composable
private fun ConnectingContent(boardName: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = OrangeAccent,
            strokeWidth = 3.dp
        )
        Text(
            if (boardName != null) stringResource(R.string.board_ble_connecting_to, boardName) else stringResource(R.string.board_ble_connecting),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ScanContent(
    isScanning: Boolean,
    boards: List<DiscoveredBoard>,
    bleShareState: com.cruxcoach.android.data.BleShareUiState,
    isRequestingDisconnect: Boolean,
    climbSharingEnabled: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectBoard: (DiscoveredBoard) -> Unit,
    onRequestDisconnect: () -> Unit,
    onClimbTapped: ((uuid: String, angle: Int) -> Unit)? = null
) {
    // Show on-board climb from BleShareManager (remote active climb or board occupied)
    val onBoard = bleShareState.onBoardClimb
    if (onBoard != null && onBoard.source == com.cruxcoach.android.data.OnBoardSource.REMOTE_ACTIVE) {
        NearbyActiveClimbCard(
            climbName = onBoard.name,
            angle = onBoard.angle,
            connectedOnly = false,
            isRequestingDisconnect = isRequestingDisconnect,
            climbSharingEnabled = climbSharingEnabled,
            onRequestDisconnect = onRequestDisconnect,
            onClimbTapped = if (onClimbTapped != null) {
                { onClimbTapped(onBoard.climbUuid, onBoard.angle) }
            } else null
        )
    } else if (bleShareState.boardOccupiedCount > 0) {
        NearbyActiveClimbCard(
            climbName = null,
            angle = 0,
            connectedOnly = true,
            isRequestingDisconnect = isRequestingDisconnect,
            climbSharingEnabled = climbSharingEnabled,
            onRequestDisconnect = onRequestDisconnect,
            onClimbTapped = null
        )
    }

    if (isScanning) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = OrangeAccent,
                strokeWidth = 2.dp
            )
            Text(stringResource(R.string.board_ble_scanning), style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (boards.isNotEmpty()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 300.dp)
        ) {
            items(boards, key = { it.address }) { board ->
                BoardItem(board = board, onClick = { onConnectBoard(board) })
            }
        }
    } else if (!isScanning) {
        Text(
            stringResource(R.string.board_ble_no_boards),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Button(
        onClick = if (isScanning) onStopScan else onStartScan,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_scan_button"),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isScanning) MaterialTheme.colorScheme.secondaryContainer else OrangeAccent
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            Icons.Default.Bluetooth,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            stringResource(if (isScanning) R.string.board_ble_stop_scan else R.string.board_ble_start_scan),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun NearbyActiveClimbCard(
    climbName: String?,
    angle: Int,
    connectedOnly: Boolean = false,
    isRequestingDisconnect: Boolean,
    climbSharingEnabled: Boolean,
    onRequestDisconnect: () -> Unit,
    onClimbTapped: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_nearby_climb_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CellTower,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = OrangeAccent
                )
                Text(
                    stringResource(if (connectedOnly) R.string.board_ble_board_occupied else R.string.board_ble_someone_climbing),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (!connectedOnly) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onClimbTapped != null) Modifier.clickable { onClimbTapped() }
                            else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        climbName ?: stringResource(R.string.board_ble_unknown_climb),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (onClimbTapped != null) OrangeAccent
                                else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        "${angle}°",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onClimbTapped != null) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.cd_open),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!climbSharingEnabled) {
                Text(
                    stringResource(R.string.board_ble_enable_sharing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedButton(
                    onClick = onRequestDisconnect,
                    enabled = !isRequestingDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent)
                ) {
                    if (isRequestingDisconnect) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = OrangeAccent
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.board_ble_request_sent))
                    } else {
                        Icon(
                            Icons.Default.CellTower,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.board_ble_request_disconnect))
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardItem(board: DiscoveredBoard, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_board_item"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = OrangeAccent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    board.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                // FEAT-027: brand label so the user can tell a discovered
                // Kilter board apart from a MoonBoard. For Kilter the serial
                // stays the secondary line; MoonBoard has no serial.
                Text(
                    text = if (board.serial.isNotBlank()) {
                        "${brandLabel(board.boardBrand)} · ${board.serial}"
                    } else {
                        brandLabel(board.boardBrand)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.SignalCellularAlt,
                    contentDescription = stringResource(R.string.cd_signal_strength),
                    modifier = Modifier.size(16.dp),
                    tint = rssiColor(board.rssi)
                )
                Text(
                    "${board.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun rssiColor(rssi: Int) = when {
    rssi >= -60 -> SuccessGreen
    rssi >= -75 -> WarningYellow
    else -> ErrorRed
}

/** Localized brand label for a discovered / connected board (FEAT-027).
 *  Only the interactive families (Kilter / MoonBoard) ever reach BLE; the
 *  map-only info-layer brands fall back to their raw name defensively. */
@Composable
private fun brandLabel(brand: BoardBrand): String = when (brand) {
    BoardBrand.KILTER -> stringResource(R.string.board_ble_brand_kilter)
    BoardBrand.MOONBOARD -> stringResource(R.string.board_ble_brand_moonboard)
    else -> brand.wireValue
}
