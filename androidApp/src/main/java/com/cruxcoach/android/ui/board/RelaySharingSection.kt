package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import com.cruxcoach.android.ui.common.InfoButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.data.RelayError
import com.cruxcoach.android.data.BoardRelayAvailability
import com.cruxcoach.android.data.BoardRelayPolicy
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

/**
 * "Share this board" (party mode) surface (FEAT-044 §12) — rendered on the
 * board-connection sheet while connected to a REAL board.
 *
 * Inactive: one deliberate action button, gated by the BLUETOOTH_ADVERTISE
 * permission and (once per app install) the disclosure dialog about the
 * global Bluetooth-name change + non-affiliation.
 * Active: status card with client count, the advertised name and a one-tap
 * stop. Errors are always surfaced.
 */
@Composable
fun RelaySharingSection(
    board: DiscoveredBoard?,
    viewModel: RelayShareViewModel = hiltViewModel()
) {
    val connectedBoard = board ?: return
    val availability = BoardRelayPolicy.availability(connectedBoard)
    if (availability == BoardRelayAvailability.NO_BOARD ||
        availability == BoardRelayAvailability.RELAY_ENDPOINT
    ) return

    if (availability == BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED) {
        Text(
            stringResource(R.string.relay_multi_connect_direct),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("relay_multi_connect_direct"),
        )
        return
    }

    // Quantum writes require a scoped route/user identity plus authoritative
    // readback. Explain that boundary instead of silently omitting or showing
    // a button whose manager must reject the request.
    if (availability == BoardRelayAvailability.UNSUPPORTED_PROTOCOL) {
        Text(
            stringResource(R.string.relay_unsupported_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("relay_unsupported_explanation"),
        )
        return
    }

    val state by viewModel.relayState.collectAsStateWithLifecycle()

    state.error?.let { error ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = ErrorRed
            )
            Text(
                relayErrorText(error, state.errorDetail),
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                modifier = Modifier.weight(1f).testTag("relay_error")
            )
        }
    }

    // Until the stored answer arrives, assume it is missing: never show the short summary
    // to someone who has not read the full disclosure yet.
    val disclosureSeen by viewModel.disclosureSeen.collectAsStateWithLifecycle(initialValue = false)
    val sharingOn by viewModel.sharingOn.collectAsStateWithLifecycle(initialValue = false)

    // One control, one meaning: on shares this board while it is connected, off stops now and
    // stays off. A start button plus a separate "automatic" switch contradicted each other.
    Card(
        modifier = Modifier.fillMaxWidth().testTag("relay_info_card"),
        colors = CardDefaults.cardColors(
            containerColor = if (state.enabled) SuccessGreen.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CellTower,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (state.enabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.relay_disclosure_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                InfoButton(
                    stringResource(R.string.relay_disclosure_title),
                    stringResource(R.string.relay_disclosure_text),
                )
                Switch(
                    checked = sharingOn,
                    onCheckedChange = viewModel::setSharing,
                    modifier = Modifier.testTag("relay_share_switch"),
                )
            }
            val status = when {
                state.enabled -> stringResource(
                    R.string.relay_chip_text,
                    state.boardName ?: connectedBoard.displayName,
                    state.clientCount,
                )
                sharingOn -> stringResource(R.string.relay_status_waiting)
                disclosureSeen -> stringResource(R.string.relay_share_description)
                else -> stringResource(R.string.relay_disclosure_text)
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            state.advertisedName?.takeIf { state.enabled }?.let { name ->
                Text(
                    stringResource(R.string.relay_advertised_as, name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun relayErrorText(error: RelayError, detail: String?): String {
    val base = when (error) {
        RelayError.SERVER_START_FAILED -> stringResource(R.string.relay_error_server)
        RelayError.ADVERTISE_FAILED -> stringResource(R.string.relay_error_advertise)
        RelayError.NAME_SET_FAILED -> stringResource(R.string.relay_error_name)
        RelayError.BOARD_LOST -> stringResource(R.string.relay_error_board_lost)
        RelayError.UNSUPPORTED_BOARD -> stringResource(R.string.relay_error_unsupported_board)
        RelayError.FORWARD_FAILED -> stringResource(R.string.relay_error_forward_failed)
    }
    return if (detail != null) "$base ($detail)" else base
}
