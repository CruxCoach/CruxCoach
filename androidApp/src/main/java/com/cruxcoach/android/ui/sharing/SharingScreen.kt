package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.MarmotRelay
import com.cruxcoach.android.sharing.MarmotStatus
import com.cruxcoach.android.ui.common.InfoHeading
import com.cruxcoach.android.ui.settings.SettingsSectionCard
import com.cruxcoach.android.ui.settings.SettingsToggleRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(
    onNavigateBack: () -> Unit,
    viewModel: SharingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sharing_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("sharing_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        SharingContent(
            state = state,
            onReachable = viewModel::setReachable,
            onSync = viewModel::syncNow,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun SharingContent(
    state: SharingUiState,
    onReachable: (Boolean) -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.available) {
        Box(modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(R.string.sharing_unavailable), modifier = Modifier.testTag("sharing_unavailable"))
        }
        return
    }
    val status = state.status
    if (state.loading && status == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("sharing_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.error?.let {
            Text(stringResource(R.string.sharing_failed, it), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("sharing_error"))
        }
        SettingsSectionCard {
            SettingsToggleRow(
                title = stringResource(R.string.sharing_reachable_title),
                description = stringResource(R.string.sharing_reachable_info),
                checked = status?.discovery?.enabled == true,
                enabled = !state.busy && status != null,
                onCheckedChange = onReachable,
                modifier = Modifier.testTag("sharing_reachable"),
            )
        }
        if (status != null) {
            PeopleSection(status)
            ConnectionSection(status, busy = state.busy, onSync = onSync)
        }
    }
}

@Composable
private fun PeopleSection(status: MarmotStatus) {
    SettingsSectionCard {
        Text(stringResource(R.string.sharing_people_title), style = MaterialTheme.typography.titleMedium)
        if (status.peers.isEmpty()) {
            Text(stringResource(R.string.sharing_no_people), style = MaterialTheme.typography.bodyMedium)
        }
        status.peers.forEach { peer ->
            Row(Modifier.fillMaxWidth().testTag("sharing_peer_${peer.account.take(12)}"),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(peer.account.take(12) + "…", style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(peerStateLabel(peer.state)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal fun peerStateLabel(state: String): Int = when (state) {
    "active" -> R.string.sharing_state_active
    "invited" -> R.string.sharing_state_invited
    "ended" -> R.string.sharing_state_ended
    else -> R.string.sharing_state_pending
}

@Composable
private fun ConnectionSection(status: MarmotStatus, busy: Boolean, onSync: () -> Unit) {
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_connection_title), stringResource(R.string.sharing_connection_info))
        RelayLine(stringResource(R.string.sharing_local_relay),
            stringResource(if (status.localRelay) R.string.sharing_relay_connected else R.string.sharing_relay_offline))
        status.relays.forEach { relay -> RelayLine(relay.url.removePrefix("wss://"), relayLabel(relay)) }
        if (status.local.outboundPending > 0) {
            Text(stringResource(R.string.sharing_pending_messages, status.local.outboundPending.toInt()),
                style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onSync, enabled = !busy, modifier = Modifier.testTag("sharing_sync_now")) {
            Text(stringResource(R.string.sharing_sync_now))
        }
    }
}

@Composable
private fun relayLabel(relay: MarmotRelay): String {
    val connection = stringResource(when (relay.connection) {
        "connected" -> R.string.sharing_relay_connected
        "connecting" -> R.string.sharing_relay_connecting
        "banned" -> R.string.sharing_relay_banned
        "sleeping" -> R.string.sharing_relay_sleeping
        else -> R.string.sharing_relay_offline
    })
    val result = when (relay.lastResult) {
        "accepted" -> R.string.sharing_relay_accepted
        "rejected" -> R.string.sharing_relay_rejected
        "auth_required" -> R.string.sharing_relay_auth_required
        "unavailable" -> R.string.sharing_relay_unavailable
        else -> null
    }?.let { stringResource(it) }
    return listOfNotNull(connection, result).joinToString(" · ")
}

@Composable
private fun RelayLine(name: String, state: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
