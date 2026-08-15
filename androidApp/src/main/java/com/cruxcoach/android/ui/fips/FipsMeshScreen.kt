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
import com.cruxcoach.android.ui.board.BleConnectionSheet
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
            item { CurrentMeshCard(state, onConnect = { showConnectionSheet = true }) }
            item { SectionTitle(stringResource(R.string.fips_mesh_connected_peers)) }
            if (state.peers.isEmpty()) {
                item { EmptyCard(stringResource(R.string.fips_mesh_no_connected_peers)) }
            } else {
                items(state.peers, key = { it.npub }) { PeerCard(it) }
            }
            item { SectionTitle(stringResource(R.string.fips_mesh_nearby_meshes)) }
            if (!state.running) {
                item { EmptyCard(stringResource(R.string.fips_mesh_connect_board_hint)) }
            } else if (state.nearbyMeshes.isEmpty()) {
                item { EmptyCard(stringResource(R.string.fips_mesh_no_nearby_meshes)) }
            } else {
                items(
                    state.nearbyMeshes,
                    key = { "${it.address}:${it.realmTag}:${it.cellTag}" },
                ) { NearbyMeshCard(it) }
            }
            item { Spacer(Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun CurrentMeshCard(state: FipsMeshUiState, onConnect: () -> Unit) {
    val active = state.running && state.cellId != null
    MeshCard(container = if (active) SuccessGreen.copy(alpha = 0.10f) else InfoBlue.copy(alpha = 0.08f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Hub, contentDescription = null, tint = if (active) SuccessGreen else InfoBlue)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (active) stringResource(R.string.fips_mesh_own_active)
                    else stringResource(R.string.fips_mesh_own_inactive),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                state.boardName?.let { Text("$it · ${state.boardBrand.orEmpty()}") }
            }
        }
        if (state.cellId != null) {
            Detail(stringResource(R.string.fips_mesh_cell), shortId(state.cellId))
            Detail(stringResource(R.string.fips_mesh_members), state.memberCount.toString())
            Detail(stringResource(R.string.fips_mesh_state), state.availability.orEmpty())
            Detail(stringResource(R.string.fips_mesh_own_node), shortNpub(state.localNpub))
            Detail(stringResource(R.string.fips_mesh_controller), shortNpub(state.controllerNpub))
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
private fun PeerCard(peer: FipsMeshPeerUi) {
    MeshCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BluetoothConnected, contentDescription = null, tint = SuccessGreen)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(shortNpub(peer.npub), fontWeight = FontWeight.SemiBold)
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
        Detail(stringResource(R.string.fips_mesh_transport), peer.transport.uppercase())
    }
}

@Composable
private fun NearbyMeshCard(mesh: NearbyFipsMeshUi) {
    MeshCard(container = if (mesh.currentMesh) OrangeAccent.copy(alpha = 0.10f) else Color.Unspecified) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Hub, contentDescription = null, tint = OrangeAccent)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (mesh.currentMesh) stringResource(R.string.fips_mesh_nearby_own)
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
        Detail(stringResource(R.string.fips_mesh_realm_tag), mesh.realmTag)
        Detail(stringResource(R.string.fips_mesh_cell_tag), mesh.cellTag)
        Detail(stringResource(R.string.fips_mesh_signal), "${mesh.rssi} dBm")
    }
}

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
