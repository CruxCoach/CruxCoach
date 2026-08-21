package com.cruxcoach.android.ui.fips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.boardcell.MeshMembershipTransition
import com.cruxcoach.android.boardcell.BoardJoinMode
import com.cruxcoach.android.fips.FipsConnectionStage
import com.cruxcoach.android.ui.board.BleConnectionSheet
import com.cruxcoach.android.ui.common.LocalCruxRelayManager
import com.cruxcoach.android.ui.theme.InfoBlue
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FipsMeshScreen(
    onNavigateBack: () -> Unit,
    viewModel: FipsMeshViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val joiningBoardCellId by viewModel.joiningBoardCellId.collectAsStateWithLifecycle()
    val joinFailed by viewModel.joinFailed.collectAsStateWithLifecycle()
    val leaveFailed by viewModel.leaveFailed.collectAsStateWithLifecycle()
    val membershipTransition by viewModel.membershipTransition.collectAsStateWithLifecycle()
    val relayState by LocalCruxRelayManager.current.state.collectAsStateWithLifecycle()
    var showConnectionSheet by remember { mutableStateOf(false) }
    if (showConnectionSheet) {
        BleConnectionSheet(onDismiss = { showConnectionSheet = false })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fips_mesh_overview_title)) },
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
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.fips_mesh_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item { CurrentMeshCard(state,
                relayClientCount = relayState.clientCount.takeIf { relayState.enabled },
                membershipTransition = membershipTransition,
                leaveFailed = leaveFailed,
                onLeave = viewModel::leave,
                onJoinModeChange = viewModel::setJoinMode,
                onConnect = { showConnectionSheet = true }) }
            if (membershipTransition == MeshMembershipTransition.ERROR && !joinFailed && !leaveFailed) {
                item {
                    Text(stringResource(R.string.fips_mesh_disconnected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            item { SectionTitle(stringResource(R.string.fips_mesh_connected_peers)) }
            if (state.peers.isEmpty()) {
                item { EmptyCard(stringResource(R.string.fips_mesh_no_connected_peers)) }
            } else {
                items(state.peers.size) { index -> PeerCard(state.peers[index], index + 1) }
            }
            item { SectionTitle(stringResource(R.string.fips_mesh_nearby_meshes)) }
            if (joinFailed) {
                item {
                    Text(stringResource(R.string.fips_mesh_join_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            if (state.nearbyMeshes.isEmpty()) {
                item { EmptyCard(stringResource(R.string.fips_mesh_no_nearby_meshes)) }
            } else {
                items(
                    state.nearbyMeshes,
                    key = { "${it.address}:${it.realmTag}:${it.cellTag}" },
                ) { mesh -> NearbyMeshCard(mesh,
                    joining = joiningBoardCellId == mesh.joinableBoardCellId,
                    joinEnabled = joiningBoardCellId == null,
                    joinStage = state.joinStage,
                    onJoin = { viewModel.join(mesh) }) }
            }
            item { Spacer(Modifier.size(20.dp)) }
        }
    }
}

internal enum class BoardMembershipDisplayState {
    INACTIVE,
    ACTIVE,
    JOINING,
    LEAVING,
    CONTROLLER_RECOVERY,
    CONFIRM_BOARD,
    SYNCHRONIZING,
}

internal fun boardMembershipDisplayState(
    localIsMember: Boolean,
    availability: String?,
    transition: MeshMembershipTransition,
): BoardMembershipDisplayState {
    if (!localIsMember) return BoardMembershipDisplayState.INACTIVE
    if (transition == MeshMembershipTransition.LEAVING) return BoardMembershipDisplayState.LEAVING
    if (transition in setOf(
            MeshMembershipTransition.JOINING,
            MeshMembershipTransition.WAITING_APPROVAL,
        )) return BoardMembershipDisplayState.JOINING
    if (availability == "ACTIVE") return BoardMembershipDisplayState.ACTIVE
    if (availability == "FROZEN_NEEDS_CONTROLLER" && transition == MeshMembershipTransition.IDLE)
        return BoardMembershipDisplayState.CONTROLLER_RECOVERY
    if (availability == "FROZEN_WRITE_RECOVERY")
        return BoardMembershipDisplayState.CONFIRM_BOARD
    return BoardMembershipDisplayState.SYNCHRONIZING
}

@Composable
private fun CurrentMeshCard(
    state: FipsMeshUiState,
    relayClientCount: Int?,
    membershipTransition: MeshMembershipTransition,
    leaveFailed: Boolean,
    onLeave: () -> Unit,
    onJoinModeChange: (BoardJoinMode) -> Unit,
    onConnect: () -> Unit,
) {
    val hasMembership = state.cellId != null && state.localIsMember
    val displayState = boardMembershipDisplayState(
        localIsMember = hasMembership,
        availability = state.availability,
        transition = membershipTransition,
    )
    val active = displayState == BoardMembershipDisplayState.ACTIVE
    val leaving = displayState == BoardMembershipDisplayState.LEAVING
    MeshCard(container = if (active) SuccessGreen.copy(alpha = 0.10f) else InfoBlue.copy(alpha = 0.08f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Hub, contentDescription = null, tint = if (active) SuccessGreen else InfoBlue)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    when {
                        active -> stringResource(R.string.fips_mesh_own_active)
                        displayState == BoardMembershipDisplayState.LEAVING ->
                            stringResource(R.string.fips_mesh_leaving)
                        displayState != BoardMembershipDisplayState.INACTIVE ->
                            stringResource(R.string.fips_mesh_own_recovering)
                        else -> stringResource(R.string.fips_mesh_own_inactive)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                state.boardName?.let { Text("$it · ${state.boardBrand.orEmpty()}") }
            }
        }
        if (hasMembership) {
            Detail(stringResource(R.string.fips_mesh_members), state.memberCount.toString())
            Detail(
                stringResource(R.string.fips_mesh_direct_peers),
                state.peers.count { it.directAuthenticated }.toString(),
            )
            relayClientCount?.let {
                Detail(stringResource(R.string.fips_mesh_relay_connections), it.toString())
            }
            Detail(
                stringResource(R.string.fips_mesh_role),
                stringResource(
                    when {
                        displayState == BoardMembershipDisplayState.CONTROLLER_RECOVERY ->
                            R.string.fips_mesh_role_recovering
                        displayState == BoardMembershipDisplayState.JOINING ->
                            R.string.fips_mesh_role_joining
                        displayState == BoardMembershipDisplayState.LEAVING ->
                            R.string.fips_mesh_leaving
                        displayState == BoardMembershipDisplayState.CONFIRM_BOARD ->
                            R.string.fips_mesh_role_confirm_board
                        displayState == BoardMembershipDisplayState.SYNCHRONIZING ->
                            R.string.fips_mesh_role_synchronizing
                        state.localNpub == state.controllerNpub -> R.string.fips_mesh_role_controls
                        else -> R.string.fips_mesh_role_connected
                    },
                ),
            )
            Text(
                stringResource(R.string.fips_mesh_join_mode_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.fips_mesh_join_mode_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.joinMode == BoardJoinMode.OPEN,
                    enabled = active,
                    onClick = { onJoinModeChange(BoardJoinMode.OPEN) },
                    label = { Text(stringResource(R.string.settings_board_join_open)) },
                )
                FilterChip(
                    selected = state.joinMode == BoardJoinMode.APPROVAL_REQUIRED,
                    enabled = active,
                    onClick = { onJoinModeChange(BoardJoinMode.APPROVAL_REQUIRED) },
                    label = { Text(stringResource(R.string.settings_board_join_approval)) },
                )
            }
            if (!state.bluetoothAvailable) {
                Text(stringResource(R.string.board_bt_off_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = onLeave,
                enabled = membershipTransition == MeshMembershipTransition.IDLE,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (leaving) R.string.fips_mesh_leaving
                    else R.string.fips_mesh_leave_action))
            }
            if (leaveFailed) {
                Text(stringResource(R.string.fips_mesh_leave_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text(
                stringResource(R.string.fips_mesh_connect_board_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.fips_mesh_connect_action))
            }
        }
    }
}

@Composable
private fun PeerCard(peer: FipsMeshPeerUi, number: Int) {
    MeshCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BluetoothConnected, contentDescription = null, tint = SuccessGreen)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    peer.displayName ?: if (peer.controller) stringResource(R.string.fips_mesh_peer_controller_short)
                    else stringResource(R.string.fips_mesh_peer_number, number),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        peer.controller -> stringResource(R.string.fips_mesh_peer_controller)
                        peer.member -> stringResource(R.string.fips_mesh_peer_member)
                        peer.directAuthenticated -> stringResource(R.string.fips_mesh_peer_authenticated)
                        else -> stringResource(R.string.fips_mesh_peer_verifying)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NearbyMeshCard(
    mesh: NearbyFipsMeshUi,
    joining: Boolean,
    joinEnabled: Boolean,
    joinStage: FipsConnectionStage,
    onJoin: () -> Unit,
) {
    MeshCard(container = if (mesh.currentMesh) OrangeAccent.copy(alpha = 0.10f) else Color.Unspecified) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Hub, contentDescription = null, tint = OrangeAccent)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    mesh.boardName ?: if (mesh.currentMesh) stringResource(R.string.fips_mesh_nearby_own)
                    else stringResource(R.string.fips_mesh_nearby_other),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    signalLabel(mesh.rssi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Detail(stringResource(R.string.fips_mesh_signal), "${mesh.rssi} dBm")
        if (!mesh.currentMesh && mesh.joinableBoardCellId != null) {
            Button(onClick = onJoin, enabled = joinEnabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (joining) joinStageLabel(joinStage) else stringResource(R.string.fips_mesh_join_action))
            }
        }
    }
}

@Composable
private fun joinStageLabel(stage: FipsConnectionStage): String = stringResource(when (stage) {
    FipsConnectionStage.IDLE -> R.string.fips_mesh_join_stage_starting
    FipsConnectionStage.ADVERTISEMENT_SEEN -> R.string.fips_mesh_join_stage_connecting
    FipsConnectionStage.CHANNEL_OPEN -> R.string.fips_mesh_join_stage_authenticating
    FipsConnectionStage.PEER_AUTHENTICATED -> R.string.fips_mesh_join_stage_admitting
    FipsConnectionStage.DIRECT_AUTHENTICATED -> R.string.fips_mesh_join_stage_syncing
})

@Composable
private fun MeshCard(
    container: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = if (container == Color.Unspecified) CardDefaults.cardColors()
        else CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun EmptyCard(text: String) = MeshCard {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Detail(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun shortNpub(value: String?): String = when {
    value.isNullOrBlank() -> "–"
    value.length <= 22 -> value
    else -> "${value.take(12)}…${value.takeLast(8)}"
}

private fun shortId(value: String): String = if (value.length <= 18) value else "${value.take(8)}…${value.takeLast(6)}"

@Composable
private fun signalLabel(rssi: Int): String = stringResource(
    when {
        rssi >= -60 -> R.string.fips_mesh_signal_near
        rssi >= -75 -> R.string.fips_mesh_signal_reachable
        else -> R.string.fips_mesh_signal_weak
    }
)
