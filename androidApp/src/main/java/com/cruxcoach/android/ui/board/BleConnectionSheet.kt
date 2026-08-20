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
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Search
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
import com.cruxcoach.android.ble.BoardConnectFlow
import com.cruxcoach.android.ble.BoardConnectFlowPolicy
import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.ble.BoardControllerProfiles
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.android.ui.common.LocalBleShareManager
import com.cruxcoach.android.data.RememberedBoardController
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.boardcell.BoardCellId
import com.cruxcoach.android.boardcell.PhysicalBoardIdentity
import com.cruxcoach.android.fips.FipsNearbyMesh
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

private enum class PendingScanStart {
    MANUAL,
    AUTO_CONNECT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleConnectionSheet(
    onDismiss: () -> Unit,
    onNavigateToClimb: ((uuid: String, angle: Int) -> Unit)? = null,
    viewModel: BleConnectionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rememberedBoard = state.rememberedBoardControllers[state.activeBoardBrand]
    var discoveryRequested by remember(state.activeBoardBrand) { mutableStateOf(false) }
    var pendingScanStart by remember(state.activeBoardBrand) {
        mutableStateOf<PendingScanStart?>(null)
    }

    // Where scanning is free (Android 12+, BLUETOOTH_SCAN/neverForLocation)
    // discovery is simply the flow: every board in range gets listed and an
    // unambiguous one connects itself. Where scanning means location access,
    // the remembered controller is tried first and discovery takes over only
    // once that failed — or the user asked for it.
    val connectFlow = BoardConnectFlowPolicy.initialFlow(rememberedBoard != null)
    val discoveryFlowActive = state.rememberedBoardControllersLoaded &&
        (discoveryRequested || connectFlow == BoardConnectFlow.DISCOVER ||
            state.directReconnectFailed)

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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onConnectionSheetDismissed()
        }
    }

    // Location services gate the *discovery scan* on API ≤ 30 only — never a
    // direct GATT connect, and nothing at all on API 31+ (BLUETOOTH_SCAN is
    // declared neverForLocation). Computed once here and used both to pick the
    // prompt branch below and to hold back the auto-scan: the prompt must only
    // ever show while the flow it blocks genuinely cannot proceed.
    val locationPromptNeeded = BlePermissionHelper.isLocationRequired(
        apiLevel = Build.VERSION.SDK_INT,
        flowNeedsScan = discoveryFlowActive,
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

    // Opening the sheet means "get me on a board". On Android 12+ that starts
    // a scan every time — the list of what is actually in range is the honest
    // answer, and a single board in it needs no picking. On older versions the
    // same tap first reaches for the remembered controller directly, because
    // scanning there would mean asking for location access before we even know
    // whether the board is present.
    //
    // Only for a sheet that OPENED disconnected, and only once. The effect
    // re-runs on every connection-state change, so without both guards
    // tapping Disconnect would be answered by an immediate scan and a
    // reconnect to the very board just released — the one thing the user
    // unambiguously did not ask for. A failed direct reconnect is the single
    // case that earns a second start, which is why it re-keys the flag.
    val openedDisconnected = remember { state.connectionState == ConnectionState.DISCONNECTED }
    var autoStarted by remember(state.activeBoardBrand, state.directReconnectFailed) {
        mutableStateOf(false)
    }
    LaunchedEffect(
        state.rememberedBoardControllersLoaded,
        rememberedBoard?.address,
        connectFlow,
        state.connectionState,
        state.directReconnectFailed,
        autoStarted,
    ) {
        if (autoStarted || !openedDisconnected) return@LaunchedEffect
        if (!state.rememberedBoardControllersLoaded) return@LaunchedEffect
        if (state.connectionState != ConnectionState.DISCONNECTED) return@LaunchedEffect
        when {
            connectFlow == BoardConnectFlow.DIRECT_THEN_DISCOVER &&
                !discoveryRequested && !state.directReconnectFailed -> {
                autoStarted = true
                viewModel.tryRememberedControllerFirst()
            }

            connectFlow == BoardConnectFlow.DISCOVER || state.directReconnectFailed -> {
                autoStarted = true
                pendingScanStart = PendingScanStart.AUTO_CONNECT
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (BlePermissionHelper.hasPermissions(context)) {
            viewModel.onPermissionsGranted()
        }
    }

    val connectionPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (BlePermissionHelper.hasConnectionPermission(context)) {
            viewModel.onPermissionsGranted()
            viewModel.tryRememberedControllerFirst()
        }
    }

    // Every "use the remembered board" affordance must perform the direct
    // GATT connect itself. In particular, after an automatic reconnect has
    // failed, merely leaving discovery is not enough: directReconnectFailed
    // keeps the legacy location gate active and the button otherwise appears
    // to do nothing. Resetting that state is part of
    // tryRememberedControllerFirst(), so the retry works with location off on
    // Android 8-11 as intended.
    val reconnectRememberedBoard: () -> Unit = {
        pendingScanStart = null
        discoveryRequested = false
        val needed = BlePermissionHelper.getReconnectPermissions()
        if (BlePermissionHelper.hasReconnectPermissions(context) || needed.isEmpty()) {
            viewModel.tryRememberedControllerFirst()
        } else {
            connectionPermissionLauncher.launch(needed)
        }
    }

    // A scan requested before permission or before the legacy location toggle
    // was enabled resumes exactly once when that prerequisite becomes true.
    LaunchedEffect(
        pendingScanStart,
        discoveryFlowActive,
        state.hasPermissions,
        state.isBluetoothEnabled,
        locationPromptNeeded,
        state.connectionState,
    ) {
        val requestedStart = pendingScanStart ?: return@LaunchedEffect
        if (discoveryFlowActive && state.hasPermissions && state.isBluetoothEnabled &&
            !locationPromptNeeded && state.connectionState == ConnectionState.DISCONNECTED
        ) {
            pendingScanStart = null
            when (requestedStart) {
                PendingScanStart.MANUAL -> viewModel.startScan()
                PendingScanStart.AUTO_CONNECT -> viewModel.startScanWithAutoConnect()
            }
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

    // Fires the enable dialog, acquiring its prerequisite first if needed.
    // Kept separate from connectionPermissionLauncher, whose success path
    // reconnects a remembered board — here the only goal is the enable dialog.
    val enableBluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            runCatching {
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }
    val requestBluetoothEnable: () -> Unit = {
        if (BlePermissionHelper.canRequestBluetoothEnable(state.hasConnectionPermission)) {
            // runCatching as a backstop: OEM ROMs have been known to refuse
            // this activity even with the permission held, and a refused
            // system dialog must not take the whole app down.
            runCatching {
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } else {
            // The full set, not just the connect permission this dialog needs.
            // Asking narrowly here bought nothing: the scan that follows the
            // moment Bluetooth comes on asks for the rest, so the user answered
            // two permission dialogs a few seconds apart. Android puts them in
            // one dialog when they are requested together.
            enableBluetoothPermissionLauncher.launch(
                BlePermissionHelper.getRequiredPermissions()
            )
        }
    }

    // While a board is already active, populate alternatives in the same
    // sheet. Picking one performs leave + connect as one operation; users do
    // not have to disconnect, reopen the menu and scan again.
    val logicalBoardConnected = state.activeBoardCellId != null ||
        state.connectionState == ConnectionState.CONNECTED ||
        state.connectionState == ConnectionState.SENDING
    LaunchedEffect(
        logicalBoardConnected,
        state.hasPermissions,
        state.isBluetoothEnabled,
        state.isLocationEnabled,
    ) {
        val legacyLocationReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
            state.isLocationEnabled
        if (logicalBoardConnected && state.hasPermissions && state.isBluetoothEnabled &&
            legacyLocationReady && !state.isScanning
        ) {
            viewModel.startScan()
        }
    }

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

            if (state.connectionState == ConnectionState.DISCONNECTED) {
                state.connectFailureReason?.let { ConnectionFailureMessage(it) }
            }

            when {
                // Connected. Deliberately ranked above every discovery gate:
                // a live GATT connection never needs location services,
                // so an existing connection (session auto-connect, or one made
                // before the user toggled location off) must show as connected
                // instead of hiding behind an "enable location" prompt it
                // plainly contradicts.
                state.connectionState == ConnectionState.CONNECTED ||
                state.connectionState == ConnectionState.SENDING -> {
                    ConnectedContent(
                        boardName = state.connectedBoardName ?: "Board",
                        board = state.connectedBoard,
                        isSending = state.connectionState == ConnectionState.SENDING,
                        activeMeshName = if (state.activeBoardCellId != null) {
                            state.activeMeshBoardName ?: state.connectedBoardName ?: "Board"
                        } else null,
                        activeMeshMemberCount = state.activeMeshMemberCount,
                        onDisconnect = { viewModel.disconnect() }
                    )
                    NearbyBoardSwitchSection(
                        boards = state.discoveredBoards,
                        nearbyMeshes = state.nearbyMeshes,
                        connectedAddress = state.connectedBoard?.address,
                        activeBoardCellId = state.activeBoardCellId,
                        joiningBoardCellId = state.joiningBoardCellId,
                        joinStage = state.meshJoinStage,
                        switchingBoardAddress = state.switchingBoardAddress,
                        isScanning = state.isScanning,
                        onSwitchBoard = viewModel::switchToBoard,
                        onJoinMesh = viewModel::joinBoardMesh,
                    )
                }

                // Connecting is also a direct-GATT state and must not disappear
                // behind a permission or location prompt.
                state.connectionState == ConnectionState.CONNECTING -> {
                    ConnectingContent(
                        boardName = state.connectedBoardName,
                        onSearchInstead = if (state.directReconnectInFlight) {
                            {
                                viewModel.abandonDirectReconnect()
                                discoveryRequested = true
                                pendingScanStart = PendingScanStart.AUTO_CONNECT
                            }
                        } else null,
                    )
                }

                // A mesh participant has a real logical board connection even
                // though it deliberately owns no physical GATT link. Render it
                // as that one board here, before the legacy session branch.
                state.activeBoardCellId != null -> {
                    ConnectedContent(
                        boardName = state.activeMeshBoardName ?: "Board",
                        board = null,
                        isSending = false,
                        activeMeshName = state.activeMeshBoardName ?: "Board",
                        activeMeshMemberCount = state.activeMeshMemberCount,
                        onDisconnect = { viewModel.disconnect() },
                    )
                    NearbyBoardSwitchSection(
                        boards = state.discoveredBoards,
                        nearbyMeshes = state.nearbyMeshes,
                        connectedAddress = null,
                        activeBoardCellId = state.activeBoardCellId,
                        joiningBoardCellId = state.joiningBoardCellId,
                        joinStage = state.meshJoinStage,
                        switchingBoardAddress = state.switchingBoardAddress,
                        isScanning = state.isScanning,
                        onSwitchBoard = viewModel::switchToBoard,
                        onJoinMesh = viewModel::joinBoardMesh,
                    )
                }

                // Session participant: board is controlled by host,
                // this device runs no discovery scan of its own here.
                state.sessionRole == SessionRole.PARTICIPANT -> {
                    SessionParticipantContent()
                }

                // Bluetooth is needed by both reconnect and discovery. Auto-fire
                // the platform enable dialog, with an explicit retry button.
                !state.isBluetoothEnabled -> {
                    // Keyed on the permission, not Unit: when the user grants
                    // BLUETOOTH_CONNECT the effect re-runs and now gets as far
                    // as the enable dialog. A denial leaves the key unchanged,
                    // so this cannot spin.
                    LaunchedEffect(state.hasConnectionPermission) {
                        requestBluetoothEnable()
                    }
                    BluetoothDisabledContent(
                        onRequestEnable = requestBluetoothEnable
                    )
                }

                !state.rememberedBoardControllersLoaded -> {
                    LoadingConnectionOptionsContent()
                }

                // Default for a known controller: let the user choose direct
                // reuse or a fresh scan before asking for either permission set.
                // Legacy fallback surface: the direct attempt is what normally
                // runs here (see the effect above), so this shows when it has
                // not started yet or the user came back to it. A reconnect asks
                // for nothing but the connect permission — and on these
                // versions not even that.
                !discoveryFlowActive && rememberedBoard != null -> {
                    RememberedBoardContent(
                        board = rememberedBoard,
                        onReconnect = reconnectRememberedBoard,
                        onSearchOtherBoards = {
                            viewModel.stopScan()
                            discoveryRequested = true
                            pendingScanStart = PendingScanStart.MANUAL
                        },
                    )
                }

                // Discovery permissions are requested only after discovery was
                // selected. On Android 8-11 this is the sole location-permission
                // branch; direct reconnect never reaches it.
                !state.hasPermissions -> {
                    PermissionContent(
                        isLegacyAndroid = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
                        onRequestPermissions = {
                            permissionLauncher.launch(BlePermissionHelper.getRequiredPermissions())
                        },
                        onUseRememberedBoard = rememberedBoard?.let {
                            reconnectRememberedBoard
                        },
                    )
                }

                // Location services gate the discovery scan on API 23-30 only.
                locationPromptNeeded -> {
                    LocationDisabledContent(
                        onUseRememberedBoard = rememberedBoard?.let {
                            reconnectRememberedBoard
                        },
                    )
                }

                // Discovery scan / board list.
                else -> {
                    val bleShareState by LocalBleShareManager.current.uiState.collectAsStateWithLifecycle()
                    ScanContent(
                        isScanning = state.isScanning,
                        boards = state.discoveredBoards,
                        nearbyMeshes = state.nearbyMeshes,
                        activeBoardCellId = state.activeBoardCellId,
                        activeMeshBoardName = state.activeMeshBoardName,
                        activeMeshMemberCount = state.activeMeshMemberCount,
                        joiningBoardCellId = state.joiningBoardCellId,
                        meshJoinStage = state.meshJoinStage,
                        meshJoinFailed = state.meshJoinFailed,
                        meshJoinRetryAfterEpochMs = state.meshJoinRetryAfterEpochMs,
                        lastUsedBoardAddresses = state.lastUsedBoardAddresses,
                        bleShareState = bleShareState,
                        climbSharingEnabled = state.climbSharingEnabled,
                        onStartScan = {
                            pendingScanStart = PendingScanStart.MANUAL
                        },
                        onStopScan = { viewModel.stopScan() },
                        onConnectBoard = { viewModel.connectToBoard(it) },
                        onJoinMesh = { viewModel.joinBoardMesh(it) },
                        onReconnectRemembered = rememberedBoard?.let {
                            {
                                viewModel.stopScan()
                                reconnectRememberedBoard()
                            }
                        },
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
private fun ConnectionFailureMessage(@androidx.annotation.StringRes reasonRes: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = WarningYellow,
        )
        Text(
            stringResource(reasonRes),
            style = MaterialTheme.typography.bodyMedium,
            color = WarningYellow,
        )
    }
}

@Composable
private fun LoadingConnectionOptionsContent() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = OrangeAccent,
            strokeWidth = 2.dp,
        )
        Text(
            stringResource(R.string.board_ble_loading_connection_options),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RememberedBoardContent(
    board: RememberedBoardController,
    onReconnect: () -> Unit,
    onSearchOtherBoards: () -> Unit,
) {
    // The card carries the board's identity, so tapping it is the gesture
    // people try first — the button below stays as the explicit affordance
    // rather than the only way in.
    Card(
        onClick = onReconnect,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_remembered_board_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.BluetoothConnected,
                contentDescription = null,
                tint = OrangeAccent,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    board.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Badge(containerColor = OrangeAccent) {
                    Text(stringResource(R.string.board_ble_last_used))
                }
            }
        }
    }

    Text(
        stringResource(R.string.board_ble_direct_reconnect_privacy_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        onClick = onReconnect,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_reconnect_button"),
        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(
            Icons.Default.BluetoothConnected,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.board_ble_reconnect), fontWeight = FontWeight.Bold)
    }

    OutlinedButton(
        onClick = onSearchOtherBoards,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ble_search_other_boards_button"),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.board_ble_search_other_boards))
    }
}

@Composable
private fun PermissionContent(
    isLegacyAndroid: Boolean,
    onRequestPermissions: () -> Unit,
    onUseRememberedBoard: (() -> Unit)?,
) {
    Icon(
        Icons.Default.Search,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        stringResource(R.string.board_ble_scan_permission_title),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold
    )
    Text(
        stringResource(
            if (isLegacyAndroid) {
                R.string.board_ble_scan_permission_message_legacy
            } else {
                R.string.board_ble_scan_permission_message_modern
            }
        ),
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
        Text(stringResource(R.string.board_ble_allow_search), fontWeight = FontWeight.Bold)
    }
    if (onUseRememberedBoard != null) {
        TextButton(
            onClick = onUseRememberedBoard,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.board_ble_use_remembered_instead))
        }
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
private fun LocationDisabledContent(onUseRememberedBoard: (() -> Unit)?) {
    val context = LocalContext.current
    Icon(
        Icons.Default.LocationOff,
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
    if (onUseRememberedBoard != null) {
        TextButton(
            onClick = onUseRememberedBoard,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.board_ble_use_remembered_instead))
        }
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
    board: DiscoveredBoard?,
    isSending: Boolean,
    activeMeshName: String? = null,
    activeMeshMemberCount: Int = 0,
    onDisconnect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            if (activeMeshName != null) Icons.Default.Hub else Icons.Default.BluetoothConnected,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = SuccessGreen
        )
        Column {
            Text(
                stringResource(
                    if (activeMeshName != null) R.string.fips_mesh_own_active
                    else R.string.board_ble_connected,
                ),
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
                activeMeshName ?: boardName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Two answers, never a third: physical controllers are treated as
            // exclusive; CruxRelay is multi-client by construction.
            if (activeMeshName != null) {
                Text(
                    pluralStringResource(
                        R.plurals.board_people_count,
                        activeMeshMemberCount,
                        activeMeshMemberCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val connectionMode = when {
                    board?.isCruxRelay == true -> R.string.board_ble_connection_via_relay
                    BoardControllerProfiles.forBoard(board).connectionCapacity ==
                        BoardConnectionCapacity.MULTIPLE ->
                        R.string.board_ble_connection_multi
                    else -> R.string.board_ble_connection_single
                }
                Text(
                    stringResource(connectionMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        Text(stringResource(
            if (activeMeshName != null) R.string.fips_mesh_leave_action
            else R.string.board_ble_disconnect,
        ))
    }
}

@Composable
private fun NearbyBoardSwitchSection(
    boards: List<DiscoveredBoard>,
    nearbyMeshes: List<FipsNearbyMesh>,
    connectedAddress: String?,
    activeBoardCellId: String?,
    joiningBoardCellId: String?,
    joinStage: com.cruxcoach.android.fips.FipsConnectionStage,
    switchingBoardAddress: String?,
    isScanning: Boolean,
    onSwitchBoard: (DiscoveredBoard) -> Unit,
    onJoinMesh: (FipsNearbyMesh) -> Unit,
) {
    val meshCells = nearbyMeshes.mapNotNull { it.joinableBoardCellId }.toSet()
    val otherBoards = boards.filter { board ->
        val cell = runCatching {
            BoardCellId.forPhysical(PhysicalBoardIdentity.resolve(board)).value
        }.getOrNull()
        !board.address.equals(connectedAddress, ignoreCase = true) &&
            cell != activeBoardCellId && cell !in meshCells
    }
    if (nearbyMeshes.isEmpty() && otherBoards.isEmpty() && !isScanning) return

    HorizontalDivider()
    Text(
        stringResource(R.string.board_ble_other_boards),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    if (nearbyMeshes.isEmpty() && otherBoards.isEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.board_ble_scanning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.heightIn(max = 220.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(nearbyMeshes, key = { "switch-mesh:${it.realmTag}:${it.cellTag}" }) { mesh ->
            MeshBoardItem(
                mesh = mesh,
                joined = false,
                joining = mesh.joinableBoardCellId == joiningBoardCellId,
                joinStage = joinStage,
                joinEnabled = joiningBoardCellId == null && switchingBoardAddress == null,
                onClick = { onJoinMesh(mesh) },
            )
        }
        items(otherBoards, key = { "switch-board:${it.address}" }) { board ->
            BoardItem(
                board = board,
                isLastUsed = false,
                onClick = {
                    if (switchingBoardAddress == null) onSwitchBoard(board)
                },
            )
        }
    }
    if (switchingBoardAddress != null) {
        Text(
            stringResource(R.string.board_ble_switching),
            style = MaterialTheme.typography.bodySmall,
            color = OrangeAccent,
        )
    }
}

@Composable
private fun ConnectingContent(
    boardName: String?,
    /** Present while this is the speculative attempt at the remembered board —
     *  it may well not be here, so the way out must be visible immediately. */
    onSearchInstead: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        if (onSearchInstead != null) {
            TextButton(
                onClick = onSearchInstead,
                modifier = Modifier.testTag("ble_search_instead"),
            ) {
                Text(stringResource(R.string.board_ble_search_instead), color = OrangeAccent)
            }
        }
    }
}

@Composable
private fun ScanContent(
    isScanning: Boolean,
    boards: List<DiscoveredBoard>,
    nearbyMeshes: List<FipsNearbyMesh>,
    activeBoardCellId: String?,
    activeMeshBoardName: String?,
    activeMeshMemberCount: Int,
    joiningBoardCellId: String?,
    meshJoinStage: com.cruxcoach.android.fips.FipsConnectionStage,
    meshJoinFailed: Boolean,
    meshJoinRetryAfterEpochMs: Long,
    lastUsedBoardAddresses: Map<BoardBrand, String>,
    bleShareState: com.cruxcoach.android.data.BleShareUiState,
    climbSharingEnabled: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectBoard: (DiscoveredBoard) -> Unit,
    onJoinMesh: (FipsNearbyMesh) -> Unit,
    onReconnectRemembered: (() -> Unit)?,
    onClimbTapped: ((uuid: String, angle: Int) -> Unit)? = null
) {
    // Show on-board climb from BleShareManager (remote active climb or board occupied)
    val onBoard = bleShareState.onBoardClimb
    if (onBoard != null && onBoard.source == com.cruxcoach.android.data.OnBoardSource.REMOTE_ACTIVE) {
        NearbyActiveClimbCard(
            climbName = onBoard.name,
            angle = onBoard.angle,
            connectedOnly = false,
            climbSharingEnabled = climbSharingEnabled,
            onClimbTapped = if (onClimbTapped != null) {
                { onClimbTapped(onBoard.climbUuid, onBoard.angle) }
            } else null
        )
    } else if (bleShareState.boardOccupiedCount > 0) {
        NearbyActiveClimbCard(
            climbName = null,
            angle = 0,
            connectedOnly = true,
            climbSharingEnabled = climbSharingEnabled,
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

    if (meshJoinFailed) {
        var nowEpochMs by remember(meshJoinRetryAfterEpochMs) {
            mutableLongStateOf(System.currentTimeMillis())
        }
        LaunchedEffect(meshJoinRetryAfterEpochMs) {
            while (meshJoinRetryAfterEpochMs > System.currentTimeMillis()) {
                nowEpochMs = System.currentTimeMillis()
                kotlinx.coroutines.delay(1_000L)
            }
            nowEpochMs = System.currentTimeMillis()
        }
        val cooldownSeconds = ((meshJoinRetryAfterEpochMs - nowEpochMs + 999L) / 1_000L)
            .coerceAtLeast(0L)
        Text(stringResource(
            if (cooldownSeconds > 0) R.string.fips_mesh_join_cooldown
            else R.string.fips_mesh_join_failed,
            *if (cooldownSeconds > 0) arrayOf(cooldownSeconds) else emptyArray(),
        ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }

    if (activeBoardCellId != null) {
        ActiveMeshBoardItem(activeMeshBoardName, activeMeshMemberCount)
    }

    val standaloneBoards = visibleStandaloneBoards(
        boards = boards,
        nearbyMeshes = nearbyMeshes,
        activeBoardCellId = activeBoardCellId,
        activeMeshBoardName = activeMeshBoardName,
    )
    if (standaloneBoards.isNotEmpty() || nearbyMeshes.isNotEmpty()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 300.dp)
        ) {
            items(nearbyMeshes, key = { "mesh:${it.realmTag}:${it.cellTag}" }) { mesh ->
                MeshBoardItem(mesh = mesh,
                    joined = mesh.joinableBoardCellId == activeBoardCellId,
                    joining = mesh.joinableBoardCellId == joiningBoardCellId,
                    joinStage = meshJoinStage,
                    joinEnabled = joiningBoardCellId == null,
                    onClick = { onJoinMesh(mesh) })
            }
            items(standaloneBoards, key = { it.address }) { board ->
                BoardItem(
                    board = board,
                    isLastUsed = lastUsedBoardAddresses[board.boardBrand] == board.address,
                    onClick = { onConnectBoard(board) },
                )
            }
        }
    } else if (!isScanning && activeBoardCellId == null) {
        Text(
            stringResource(R.string.board_ble_no_boards),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (onReconnectRemembered != null) {
        OutlinedButton(
            onClick = onReconnectRemembered,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                Icons.Default.BluetoothConnected,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.board_ble_use_remembered_instead))
        }
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
private fun ActiveMeshBoardItem(boardName: String?, memberCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("active_mesh_board_item"),
        colors = CardDefaults.cardColors(
            containerColor = SuccessGreen.copy(alpha = 0.12f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Hub, contentDescription = null, tint = SuccessGreen)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    boardName ?: stringResource(R.string.fips_mesh_nearby_own),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    pluralStringResource(R.plurals.board_people_count, memberCount, memberCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = SuccessGreen,
                )
            }
        }
    }
}

@Composable
private fun MeshBoardItem(
    mesh: FipsNearbyMesh,
    joined: Boolean,
    joining: Boolean,
    joinStage: com.cruxcoach.android.fips.FipsConnectionStage,
    joinEnabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = mesh.joinableBoardCellId != null && !joined && joinEnabled,
        modifier = Modifier.fillMaxWidth().testTag("mesh_board_item"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CellTower, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(mesh.boardName ?: stringResource(R.string.fips_mesh_nearby_other),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(if (joining) meshJoinStageLabel(joinStage)
                else if (joined)
                    stringResource(R.string.fips_mesh_nearby_own)
                else stringResource(R.string.fips_mesh_nearby_other),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text("${mesh.rssi} dBm", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun meshJoinStageLabel(stage: com.cruxcoach.android.fips.FipsConnectionStage): String =
    stringResource(when (stage) {
        com.cruxcoach.android.fips.FipsConnectionStage.IDLE -> R.string.fips_mesh_join_stage_starting
        com.cruxcoach.android.fips.FipsConnectionStage.ADVERTISEMENT_SEEN ->
            R.string.fips_mesh_join_stage_connecting
        com.cruxcoach.android.fips.FipsConnectionStage.CHANNEL_OPEN ->
            R.string.fips_mesh_join_stage_authenticating
        com.cruxcoach.android.fips.FipsConnectionStage.PEER_AUTHENTICATED ->
            R.string.fips_mesh_join_stage_admitting
        com.cruxcoach.android.fips.FipsConnectionStage.DIRECT_AUTHENTICATED ->
            R.string.fips_mesh_join_stage_syncing
    })

@Composable
private fun NearbyActiveClimbCard(
    climbName: String?,
    angle: Int,
    connectedOnly: Boolean = false,
    climbSharingEnabled: Boolean,
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
            }
        }
    }
}

@Composable
private fun BoardItem(
    board: DiscoveredBoard,
    isLastUsed: Boolean,
    onClick: () -> Unit,
) {
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
                if (isLastUsed || board.isCruxRelay || board.serial.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isLastUsed) {
                            Badge(containerColor = OrangeAccent) {
                                Text(stringResource(R.string.board_ble_last_used))
                            }
                        }
                        if (board.isCruxRelay) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                Text(stringResource(R.string.board_ble_via_cruxcoach))
                            }
                        }
                        if (board.serial.isNotBlank()) {
                            // A bare MoonBoard name contains no reliable setup,
                            // angle or venue information. Do not present the
                            // app-selected setup as controller metadata.
                            Text(
                                text = "${brandLabel(board.boardBrand)} · ${board.serial}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
