package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.PeerDetail
import com.cruxcoach.android.sharing.SnapshotEndpointRole
import com.cruxcoach.android.sharing.SnapshotState
import com.cruxcoach.domain.sharing.PeerId
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SharingExpiryCard(detail: PeerDetail, viewModel: SharingViewModel, enabled: Boolean) {
    LaunchedEffect(detail.peer, detail.expiresAt) {
        detail.expiresAt?.let {
            val remaining = it - System.currentTimeMillis()
            if (remaining > 0) { kotlinx.coroutines.delay(remaining); viewModel.refresh() }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sharing_expiry_title), style = MaterialTheme.typography.titleMedium)
            Text(detail.expiresAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
                ?: stringResource(R.string.sharing_expiry_none))
            Text(stringResource(R.string.sharing_expiry_consent), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = enabled, onClick = { viewModel.setExpiry(detail.peer, System.currentTimeMillis() + 3_600_000) }) {
                    Text(stringResource(R.string.sharing_expiry_hour))
                }
                OutlinedButton(enabled = enabled, onClick = { viewModel.setExpiry(detail.peer, System.currentTimeMillis() + 86_400_000) }) {
                    Text(stringResource(R.string.sharing_expiry_day))
                }
            }
            TextButton(enabled = enabled && detail.expiresAt != null, onClick = { viewModel.setExpiry(detail.peer, null) }) {
                Text(stringResource(R.string.sharing_expiry_remove))
            }
        }
    }
}

@Composable
internal fun SharingSnapshotCard(detail: PeerDetail, viewModel: SharingViewModel, enabled: Boolean) {
    val peer = detail.peer
    val app by viewModel.state.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val openedText by viewModel.snapshotText.collectAsStateWithLifecycle()
    // Sensitive draft text intentionally never enters saved-instance state.
    var text by remember(peer) { mutableStateOf("") }
    var server by remember(peer, detail.snapshotRole) { mutableStateOf(detail.snapshotRole == SnapshotEndpointRole.SERVER) }
    DisposableEffect(peer) { onDispose { viewModel.clearSnapshotText() } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sharing_snapshot_title), style = MaterialTheme.typography.titleMedium)
            Text(peer.value, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sharing_snapshot_explanation), style = MaterialTheme.typography.bodySmall)
            if (!app.nativeAvailable) Text(stringResource(R.string.sharing_snapshot_unavailable))
            OutlinedTextField(text, onValueChange = { if (it.encodeToByteArray().size <= 32_768) text = it },
                enabled = enabled && app.nativeAvailable, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sharing_snapshot_note)) })
            Row {
                Checkbox(server, onCheckedChange = { server = it }, enabled = enabled)
                Text(stringResource(R.string.sharing_snapshot_server_pin), modifier = Modifier.padding(top = 12.dp))
            }
            OutlinedButton(enabled = enabled, onClick = { viewModel.pinSnapshotPeer(peer, server) }) {
                Text(stringResource(R.string.sharing_snapshot_pin))
            }
            detail.snapshotRole?.let { savedRole ->
                Text(stringResource(if (savedRole == SnapshotEndpointRole.SERVER) R.string.sharing_snapshot_server
                    else R.string.sharing_snapshot_user))
            }
            Button(enabled = enabled && app.nativeAvailable && text.isNotBlank(), onClick = {
                val submitted = text
                viewModel.shareNoteSnapshot(peer, submitted, server) { if (text == submitted) text = "" }
            }) { Text(stringResource(R.string.sharing_snapshot_offer)) }
            OutlinedButton(enabled = enabled && app.nativeAvailable, onClick = { viewModel.synchronizeSnapshots() }) {
                Text(stringResource(R.string.sharing_snapshot_sync))
            }
            snapshots.forEach { snapshot ->
                HorizontalDivider()
                Text(stringResource(if (snapshot.role == SnapshotEndpointRole.SERVER) R.string.sharing_snapshot_server
                    else R.string.sharing_snapshot_user))
                Text(stringResource(snapshotStateLabel(snapshot.state)))
                Text(stringResource(SharingLabels.category(snapshot.category)), style = MaterialTheme.typography.bodySmall)
                Text(snapshot.id.take(12), style = MaterialTheme.typography.labelSmall)
                Text(DateFormat.getDateTimeInstance().format(Date(snapshot.expiresAt)), style = MaterialTheme.typography.bodySmall)
                if (snapshot.attempted && snapshot.state !in setOf(SnapshotState.DELIVERED, SnapshotState.RECEIVED)) {
                    Text(stringResource(R.string.sharing_snapshot_handoff_unclear), style = MaterialTheme.typography.bodySmall)
                }
                if (snapshot.incoming && snapshot.state == SnapshotState.OFFERED) {
                    Button(enabled = enabled, onClick = { viewModel.acceptSnapshot(snapshot.id) }) {
                        Text(stringResource(R.string.sharing_snapshot_accept))
                    }
                }
                if (snapshot.incoming && snapshot.state == SnapshotState.RECEIVED) {
                    OutlinedButton(onClick = { viewModel.readSnapshot(snapshot.id) }) { Text(stringResource(R.string.sharing_snapshot_open)) }
                }
                TextButton(enabled = enabled, onClick = { viewModel.revokeSnapshot(snapshot.id) }) {
                    Text(stringResource(R.string.sharing_snapshot_revoke))
                }
            }
        }
    }
    openedText?.let { content ->
        AlertDialog(onDismissRequest = viewModel::clearSnapshotText,
            title = { Text(stringResource(R.string.sharing_snapshot_note)) }, text = { Text(content) },
            confirmButton = { TextButton(onClick = viewModel::clearSnapshotText) { Text(stringResource(R.string.sharing_snapshot_close)) } })
    }
}

internal fun snapshotStateLabel(state: SnapshotState): Int = when (state) {
    SnapshotState.OFFERED -> R.string.sharing_snapshot_offered
    SnapshotState.CONSENTED -> R.string.sharing_snapshot_consented
    SnapshotState.ACCEPTED -> R.string.sharing_snapshot_accepted
    SnapshotState.RECEIVED -> R.string.sharing_snapshot_received
    SnapshotState.DELIVERED -> R.string.sharing_snapshot_delivered
    SnapshotState.REVOKED -> R.string.sharing_snapshot_revoked
    SnapshotState.DECLINED -> R.string.sharing_snapshot_declined
    SnapshotState.INVALIDATED -> R.string.sharing_snapshot_invalidated
    SnapshotState.EXPIRED -> R.string.sharing_source_expired
}
