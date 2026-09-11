package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.domain.sharing.DeviceRole

/**
 * FEAT-062 §11: the owner's own devices, as a section of the sharing screen.
 *
 * A section rather than a screen of its own, deliberately. Which of your
 * devices may change a permission is part of the same question as which person
 * may see a video, and splitting them across a navigation boundary would let
 * somebody manage one without ever noticing the other exists.
 */
@Composable
fun SharingDeviceSection(viewModel: SharingViewModel, modifier: Modifier = Modifier) {
    val estate by viewModel.estate.collectAsStateWithLifecycle()
    val signing by viewModel.signing.collectAsStateWithLifecycle()
    var confirmingReset by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.sharing_device_estate_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )

        SharingDeviceEstateBanner(
            needsEnrolment = estate.needsEnrolment,
            authorityGeneration = estate.authorityGeneration,
        )

        // The only thing a fresh install can do. Without it every
        // administrative path is closed and the screen is a dead end.
        if (estate.canEnrolGenesis) {
            Text(
                stringResource(R.string.sharing_device_genesis_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { viewModel.enrolGenesisDevice() }, enabled = !signing) {
                Text(stringResource(R.string.sharing_device_genesis_action))
            }
        }

        estate.recoveryLockReason
            ?.takeIf { estate.administrativeWritesLocked }
            ?.let { SharingRecoveryLockBanner(reason = it) }

        estate.devices.forEach { row ->
            SharingDeviceRow(
                // The id is the key, so it is what the row is named by. A
                // friendlier name would have to come from somewhere, and every
                // "somewhere" here is unauthenticated.
                deviceLabel = row.device.value.take(12),
                roleLabel = sharingDeviceRoleLabel(row.role),
                capabilityText = sharingDeviceCapabilityText(row.capabilities),
                stateText = sharingDeviceStateText(fenced = row.fenced, revoked = row.revoked),
                isThisDevice = row.isThisDevice,
                // Never the device in your hand: revoking it would leave the
                // person holding the only device that can no longer fix this.
                canRevoke = estate.canAdministerDevices &&
                    !row.isThisDevice &&
                    !row.revoked &&
                    !signing,
                onRevoke = { viewModel.revokeOwnDevice(row.device) },
            )
            if (estate.canAdministerDevices && !row.revoked && !row.isThisDevice) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeviceRole.entries
                        .filter { it != DeviceRole.REVOKED && it != row.role }
                        .forEach { role ->
                            OutlinedButton(
                                onClick = { viewModel.changeDeviceRole(row.device, role) },
                                enabled = !signing,
                            ) {
                                Text(sharingDeviceRoleLabel(role))
                            }
                        }
                }
            }
        }

        if (estate.canAdministerDevices) {
            Text(
                stringResource(R.string.sharing_device_enrol_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Debug only. A release build cannot mint a device key it does not
        // already hold, so this would be a button that cannot work.
        if (viewModel.demoAvailable && estate.canAdministerDevices) {
            OutlinedButton(onClick = { viewModel.enrolDemoDevice() }, enabled = !signing) {
                Text(stringResource(R.string.sharing_device_demo_enrol))
            }
        }

        if (estate.canSovereignReset) {
            OutlinedButton(onClick = { confirmingReset = true }, enabled = !signing) {
                Text(stringResource(R.string.sharing_sovereign_reset_title))
            }
        }
    }

    if (confirmingReset) {
        SharingSovereignResetDialog(
            onConfirm = {
                confirmingReset = false
                viewModel.startSovereignReset()
            },
            onDismiss = { confirmingReset = false },
        )
    }
}
