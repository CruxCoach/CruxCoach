package com.cruxcoach.android.ui.board

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.data.RelayError
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
    viewModel: RelayShareViewModel = hiltViewModel()
) {
    // Session participants must not re-share the host's board.
    if (viewModel.isSessionParticipant) return

    val context = LocalContext.current
    val state by viewModel.relayState.collectAsStateWithLifecycle()
    val disclosureSeen by viewModel.disclosureSeen.collectAsStateWithLifecycle()
    val hostLabel = stringResource(R.string.board_queue_title)

    var showDisclosure by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            if (disclosureSeen) viewModel.enableSharing(hostLabel) else showDisclosure = true
        }
    }
    val startSharing = {
        if (!BlePermissionHelper.hasAdvertisingPermission(context)) {
            permissionLauncher.launch(BlePermissionHelper.getAdvertisingPermissions())
        } else if (!disclosureSeen) {
            showDisclosure = true
        } else {
            viewModel.enableSharing(hostLabel)
        }
    }

    if (showDisclosure) {
        RelayDisclosureDialog(
            onConfirm = {
                showDisclosure = false
                viewModel.confirmDisclosure()
                viewModel.enableSharing(hostLabel)
            },
            onDismiss = { showDisclosure = false }
        )
    }

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

    if (!state.enabled) {
        OutlinedButton(
            onClick = startSharing,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("relay_share_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent)
        ) {
            Icon(
                Icons.Default.CellTower,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.relay_share_button), fontWeight = FontWeight.Bold)
        }
    } else {
        RelayActiveCard(
            clientCount = state.clientCount,
            advertisedName = state.advertisedName,
            onStop = { viewModel.disableSharing() }
        )
    }
}

@Composable
private fun RelayActiveCard(
    clientCount: Int,
    advertisedName: String?,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("relay_active_card"),
        colors = CardDefaults.cardColors(
            containerColor = SuccessGreen.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CellTower,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = SuccessGreen
                )
                Text(
                    stringResource(R.string.relay_chip_text, clientCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (advertisedName != null) {
                Text(
                    stringResource(R.string.relay_advertised_as, advertisedName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("relay_stop_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.relay_notification_stop))
            }
        }
    }
}

/** One-time disclosure (§12): the phone's GLOBAL Bluetooth name changes to
 *  "CruxRelay…" while sharing, plus the non-affiliation disclaimer. */
@Composable
private fun RelayDisclosureDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("relay_disclosure_dialog"),
        title = { Text(stringResource(R.string.relay_disclosure_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.relay_disclosure_text))
                Text(
                    stringResource(R.string.relay_disclosure_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
            ) {
                Text(stringResource(R.string.relay_disclosure_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun relayErrorText(error: RelayError, detail: String?): String {
    val base = when (error) {
        RelayError.SERVER_START_FAILED -> stringResource(R.string.relay_error_server)
        RelayError.ADVERTISE_FAILED -> stringResource(R.string.relay_error_advertise)
        RelayError.NAME_SET_FAILED -> stringResource(R.string.relay_error_name)
        RelayError.BOARD_LOST -> stringResource(R.string.relay_error_board_lost)
    }
    return if (detail != null) "$base ($detail)" else base
}
