package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.cruxcoach.android.R
import com.cruxcoach.domain.sharing.PeerId

@Composable
internal fun MarmotTransportCard(viewModel: SharingViewModel, peer: PeerId? = null, onOpenPeer: (PeerId) -> Unit = {}) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, viewModel) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { viewModel.synchronizeWhileVisible(); delay(15_000) }
        }
    }
    val clockHealthy by viewModel.clockHealthy.collectAsStateWithLifecycle()
    val capacity by viewModel.snapshotCapacity.collectAsStateWithLifecycle()
    var confirmClock by remember { mutableStateOf(false) }
    if (confirmClock) AlertDialog(onDismissRequest = { confirmClock = false },
        title = { Text(stringResource(R.string.marmot_clock_title)) },
        text = { Text(stringResource(R.string.marmot_clock_recovery)) },
        confirmButton = { TextButton(onClick = { confirmClock = false; viewModel.recoverSharingClock() }) {
            Text(stringResource(R.string.marmot_clock_withdraw))
        } }, dismissButton = { TextButton(onClick = { confirmClock = false }) { Text(stringResource(android.R.string.cancel)) } })
    var confirmTransport by remember { mutableStateOf(false) }
    if (confirmTransport) AlertDialog(onDismissRequest = { confirmTransport = false },
        title = { Text(stringResource(R.string.marmot_transport_recovery_title)) },
        text = { Text(stringResource(R.string.marmot_transport_recovery_detail)) },
        confirmButton = { TextButton(onClick = { confirmTransport = false; viewModel.recoverSharingTransport() }) {
            Text(stringResource(R.string.marmot_clock_withdraw))
        } }, dismissButton = { TextButton(onClick = { confirmTransport = false }) { Text(stringResource(android.R.string.cancel)) } })
    val app by viewModel.state.collectAsStateWithLifecycle()
    val signing by viewModel.signing.collectAsStateWithLifecycle()
    val peers by viewModel.nativePeers.collectAsStateWithLifecycle()
    val relays by viewModel.nativeRelays.collectAsStateWithLifecycle()
    val status by viewModel.relayStatus.collectAsStateWithLifecycle()
    val incoming by viewModel.incomingPolicy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var pool by remember(relays) { mutableStateOf(relays.joinToString("\n")) }
    val enabled = app.nativeAvailable && !signing
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.marmot_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.marmot_trust), style = MaterialTheme.typography.bodySmall)
            if (!clockHealthy) {
                Text(stringResource(R.string.marmot_clock_recovery))
                Button(enabled = !signing, onClick = { confirmClock = true }) { Text(stringResource(R.string.marmot_clock_withdraw)) }
            }
            if (capacity) {
                Text(stringResource(R.string.marmot_capacity))
                TextButton(onClick = { viewModel.collectExpiredSnapshots() }) { Text(stringResource(R.string.marmot_collect_expired)) }
            }
            if (!app.nativeAvailable) Text(stringResource(R.string.sharing_snapshot_unavailable))
            if (relays.isEmpty()) Text(stringResource(R.string.marmot_invalid_relay_pool))
            if (peer == null) {
                Text(stringResource(R.string.marmot_discovery_consent), style = MaterialTheme.typography.bodySmall)
                Button(enabled = enabled, onClick = { viewModel.bootstrapMarmot() }) { Text(stringResource(R.string.marmot_enable_discovery)) }
                TextButton(enabled = !signing, onClick = { editing = !editing }) { Text(stringResource(R.string.marmot_configure_relays)) }
                if (editing) {
                    OutlinedTextField(pool, { if (it.length <= 8192) pool = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.marmot_relay_pool)) })
                    Button(enabled = enabled, onClick = { viewModel.configureMarmotRelays(pool) }) { Text(stringResource(R.string.marmot_save_relays)) }
                }
                TextButton(enabled = enabled, onClick = { confirmTransport = true }) { Text(stringResource(R.string.marmot_transport_recovery_title)) }
                relays.forEach { relay ->
                    Text(relay, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(when (status[relay]) {
                        "limited" -> R.string.marmot_relay_limited
                        "accepted" -> R.string.marmot_relay_accepted
                        "auth_required" -> R.string.marmot_relay_auth
                        "rejected" -> R.string.marmot_relay_rejected
                        "unavailable" -> R.string.marmot_relay_unavailable
                        else -> R.string.marmot_relay_pending
                    }), style = MaterialTheme.typography.labelSmall)
                }
                peers.forEach { invitation ->
                    HorizontalDivider()
                    Text(invitation.account, style = MaterialTheme.typography.bodySmall)
                    if (!invitation.accepted) Button(enabled = enabled, onClick = { viewModel.connectMarmot(PeerId(invitation.account), true, invitation.group) }) {
                        Text(stringResource(R.string.marmot_accept_invitation))
                    } else TextButton(onClick = { onOpenPeer(PeerId(invitation.account)) }) { Text(stringResource(R.string.marmot_open_peer)) }
                }
            } else {
                val connection = peers.firstOrNull { it.account == peer.value }
                Text(stringResource(if (connection?.ready == true) R.string.marmot_connected else if (connection?.accepted == true) R.string.marmot_waiting_identity else R.string.marmot_no_session))
                Button(enabled = enabled, onClick = { viewModel.connectMarmot(peer, connection?.accepted == false) }) {
                    Text(stringResource(if (connection?.accepted == false) R.string.marmot_accept_invitation else R.string.marmot_invite))
                }
                Text(stringResource(R.string.marmot_session_reset_detail), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = enabled && connection != null, onClick = { viewModel.resetMarmotPeer(peer) }) {
                    Text(stringResource(R.string.marmot_session_reset))
                }
                incoming?.takeIf { it.peer == peer.value }?.let { proposal ->
                    Text(stringResource(R.string.marmot_permission_proposal))
                    proposal.categories.forEach { Text(stringResource(SharingLabels.category(it))) }
                    if (proposal.accepted) Text(stringResource(R.string.marmot_consent_queued))
                    else Button(enabled = enabled, onClick = { viewModel.acceptRemotePolicy(peer, proposal.categories) }) {
                        Text(stringResource(R.string.marmot_accept_policy))
                    }
                }
            }
            OutlinedButton(enabled = enabled, onClick = { viewModel.synchronizeSnapshots() }) { Text(stringResource(R.string.sharing_snapshot_sync)) }
        }
    }
}
