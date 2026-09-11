package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.DeviceRole

/**
 * FEAT-062 §11: the device list, as a person actually receives it.
 *
 * These take plain strings rather than a ViewModel for the same reason the
 * other sharing rows do: it lets the accessibility behaviour be asserted
 * against the real production composables instead of a copy of them.
 *
 * The governing decision here is that a device row is **one spoken sentence**.
 * A screen reader announcing "Laptop", "Vertrautes Gerät", "gesperrt" as three
 * disconnected fragments tells somebody that a device stopped working and not
 * why — and *why* is the only part they can act on.
 */

/** The role, named for what it lets you do rather than for its rank. */
@Composable
fun sharingDeviceRoleLabel(role: DeviceRole): String = stringResource(
    when (role) {
        DeviceRole.PRIMARY -> R.string.sharing_device_role_primary
        DeviceRole.TRUSTED -> R.string.sharing_device_role_trusted
        DeviceRole.READ_ONLY -> R.string.sharing_device_role_read_only
        DeviceRole.REVOKED -> R.string.sharing_device_role_revoked
    },
)

/**
 * The capabilities, spelled out.
 *
 * Never the enum names: "MUTATE_PERMISSIONS" on a screen is a leak of our
 * vocabulary into somebody else's decision.
 */
@Composable
fun sharingDeviceCapabilityText(capabilities: Set<DeviceCapability>): String {
    if (capabilities.isEmpty()) return stringResource(R.string.sharing_device_can_nothing)
    // Fixed order, so the same device reads the same way every time.
    val parts = DeviceCapability.entries
        .filter { it in capabilities }
        .map {
            stringResource(
                when (it) {
                    DeviceCapability.READ -> R.string.sharing_device_can_read
                    DeviceCapability.MUTATE_PERMISSIONS -> R.string.sharing_device_can_mutate
                    DeviceCapability.ADMINISTER_DEVICES -> R.string.sharing_device_can_administer
                    DeviceCapability.SOVEREIGN_RESET -> R.string.sharing_device_can_reset
                },
            )
        }
    return stringResource(R.string.sharing_device_can_prefix, parts.joinToString(", "))
}

/**
 * Why a device is not doing what its role suggests, and what fixes it.
 *
 * Empty when nothing is wrong, so the row's sentence does not grow a trailing
 * "everything is fine" nobody needs to hear.
 */
@Composable
fun sharingDeviceStateText(fenced: Boolean, revoked: Boolean): String = when {
    revoked -> stringResource(R.string.sharing_device_state_revoked)
    fenced -> stringResource(R.string.sharing_device_state_fenced)
    else -> ""
}

/**
 * One device.
 *
 * The whole row carries a single content description and its children are
 * cleared, so the sentence arrives intact instead of as fragments.
 */
@Composable
fun SharingDeviceRow(
    deviceLabel: String,
    roleLabel: String,
    capabilityText: String,
    stateText: String,
    isThisDevice: Boolean,
    canRevoke: Boolean,
    modifier: Modifier = Modifier,
    onRevoke: () -> Unit = {},
) {
    val sentence = buildString {
        append(deviceLabel)
        append(": ")
        append(roleLabel)
        append(". ")
        append(capabilityText)
        if (stateText.isNotBlank()) {
            append(". ")
            append(stateText)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = sentence }) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = deviceLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isThisDevice) {
                        // Named rather than iconified: somebody is about to
                        // decide whether to revoke it.
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(stringResource(R.string.sharing_device_this_device)) },
                        )
                    }
                }
                Text(roleLabel, style = MaterialTheme.typography.bodyMedium)
                Text(capabilityText, style = MaterialTheme.typography.bodySmall)
                if (stateText.isNotBlank()) {
                    Text(
                        text = stateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = onRevoke, enabled = canRevoke) {
                Text(stringResource(R.string.sharing_device_revoke))
            }
        }
    }
}

/**
 * The estate header: which authority generation is in force, or the fact that
 * no device is enrolled at all.
 *
 * An install with no device is deliberately *not* an empty list. An empty list
 * reads as "nothing to show"; the truth is "nothing works until you do one
 * specific thing", and that has to be the thing on screen.
 */
@Composable
fun SharingDeviceEstateBanner(
    needsEnrolment: Boolean,
    authorityGeneration: Long,
    modifier: Modifier = Modifier,
) {
    val body = if (needsEnrolment) {
        stringResource(R.string.sharing_device_needs_enrolment)
    } else {
        stringResource(R.string.sharing_device_generation, authorityGeneration)
    }
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.sharing_device_estate_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = body },
            )
            if (!needsEnrolment) {
                Text(
                    stringResource(R.string.sharing_device_generation_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.sharing_device_conflict_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The read-only preview a stale backup produces.
 *
 * Says what is blocked and both ways out, because "read-only" on its own is a
 * dead end a person cannot act on.
 */
@Composable
fun SharingRecoveryLockBanner(reason: String, modifier: Modifier = Modifier) {
    val body = stringResource(R.string.sharing_recovery_lock_body, reason)
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.sharing_recovery_lock_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = body },
            )
        }
    }
}

/**
 * The confirmation for the most destructive thing in the feature.
 *
 * Every consequence is named before the button is offered, and the dismissal is
 * the ordinary-looking one: a person who taps through a dialog they did not
 * read should land on "nothing happened".
 */
@Composable
fun SharingSovereignResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val warning = stringResource(R.string.sharing_sovereign_reset_warning)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sharing_sovereign_reset_title)) },
        text = {
            Text(
                text = warning,
                modifier = Modifier.semantics { contentDescription = warning },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.sharing_sovereign_reset_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sharing_sovereign_reset_cancel))
            }
        },
    )
}

/**
 * The way out of a read-only preview.
 *
 * A preview is the right answer — a fresh install cannot show a backup is
 * current, and applying it anyway would re-grant access that was withdrawn.
 * But on its own it is a dead end: permissions visible, nothing changeable,
 * and the only way forward on an unrelated screen with no hint that it is the
 * way forward. So the offer sits right where the person already is.
 *
 * The price is named in full before the button exists, and the dismissal is
 * the ordinary-looking one: tapping through a dialog you did not read lands on
 * "still read-only".
 */
@Composable
fun SharingPreviewRetryCard(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val body = stringResource(R.string.sharing_preview_retry_body)

    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.sharing_preview_retry_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = body },
            )
            OutlinedButton(onClick = { confirming = true }, enabled = enabled) {
                Text(stringResource(R.string.sharing_preview_retry_action))
            }
        }
    }

    if (confirming) {
        val warning = stringResource(R.string.sharing_preview_retry_warning)
        AlertDialog(
            onDismissRequest = { confirming = false; onDismiss() },
            title = { Text(stringResource(R.string.sharing_sovereign_reset_title)) },
            text = {
                Text(warning, modifier = Modifier.semantics { contentDescription = warning })
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onConfirm() }) {
                    Text(stringResource(R.string.sharing_preview_retry_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false; onDismiss() }) {
                    Text(stringResource(R.string.sharing_preview_retry_cancel))
                }
            },
        )
    }
}
