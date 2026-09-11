package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.CategoryRow
import com.cruxcoach.android.sharing.PeerDetail
import com.cruxcoach.android.sharing.SharingDemoData
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.DecisionSource
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.SharingCategory
import kotlinx.coroutines.launch
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SharingCircle

/**
 * FEAT-062 person detail: per-category outcome *with the rule that produced
 * it*, the individual exceptions, the devices, and the consent and withdrawal
 * actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingPeerDetailScreen(
    peer: PeerId,
    onNavigateBack: () -> Unit,
    viewModel: SharingViewModel = hiltViewModel(),
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val writeError by viewModel.writeError.collectAsStateWithLifecycle()
    val signing by viewModel.signing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(peer) { viewModel.openPeer(peer) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SharingDemoData.displayName(peer)) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closePeer(); onNavigateBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val current = detail
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (current == null) {
                Text(stringResource(R.string.sharing_people_empty))
                return@Column
            }
            SharingSigningBanner(signing)
            writeError?.let { SharingWriteErrorMessage(it) }
            StatusCard(current)
            MarmotTransportCard(viewModel, peer)
            SharingExpiryCard(current, viewModel, !signing)
            SharingSnapshotCard(current, viewModel, !signing)
            // Permission changes are taken one at a time, so while a
            // signer prompt is open every control that would start
            // another one is switched off rather than silently ignored.
            val enabled = !signing
            CircleCard(current, viewModel, enabled)
            current.categories.forEach { row -> CategoryCard(current, row, viewModel, enabled) }
            ObjectRulesCard(current, viewModel, enabled)
            DevicesCard(current, viewModel, scope, enabled)
            ActionsCard(current, viewModel, onNavigateBack, scope, enabled)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(detail: PeerDetail) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (detail.status == RelationshipStatus.FAIL_CLOSED) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(SharingLabels.status(detail.status)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(SharingLabels.circle(detail.circle)),
                style = MaterialTheme.typography.bodySmall,
            )
            detail.failClosedReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (detail.awaitingRevokeSync) {
                Text(
                    stringResource(R.string.sharing_source_awaiting_revoke_sync),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CircleCard(detail: PeerDetail, viewModel: SharingViewModel, enabled: Boolean = true) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sharing_circles_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.sharing_circle_change_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            SharingCirclePicker(
                selected = detail.circle,
                enabled = enabled,
                onSelect = { viewModel.setPeerCircle(detail.peer, it) },
            )
        }
    }
}

@Composable
private fun CategoryCard(
    detail: PeerDetail,
    row: CategoryRow,
    viewModel: SharingViewModel,
    enabled: Boolean = true,
) {
    SharingCategoryRow(
        categoryLabel = stringResource(SharingLabels.category(row.category)),
        released = row.effectiveDecision.effect == AccessEffect.ALLOW,
        reason = sharingReasonText(row.policyDecision, row.effectiveDecision, detail.circle),
        allowSelected = row.policyDecision.source == DecisionSource.PERSON_ALLOW,
        denySelected = row.policyDecision.source == DecisionSource.PERSON_DENY,
        enabled = enabled,
        onAllow = { viewModel.setPeerRule(detail.peer, row.category, AccessEffect.ALLOW) },
        onDeny = { viewModel.setPeerRule(detail.peer, row.category, AccessEffect.DENY) },
        onClear = { viewModel.setPeerRule(detail.peer, row.category, null) },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ObjectRulesCard(detail: PeerDetail, viewModel: SharingViewModel, enabled: Boolean = true) {
    val objectError by viewModel.objectRuleError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var objectId by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(SharingCategory.VIDEOS) }
    var allow by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sharing_objects_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )

            detail.objectRules.forEach { rule ->
                val objectLabel = "${stringResource(SharingLabels.category(rule.category))} · ${rule.objectId.value}"
                val effectLabel = stringResource(
                    if (rule.effect == AccessEffect.ALLOW) {
                        R.string.sharing_source_object_allow
                    } else {
                        R.string.sharing_source_object_deny
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = "$objectLabel: $effectLabel"
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.sharing_object_row, objectLabel, effectLabel),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    val remove = stringResource(R.string.sharing_object_remove, objectLabel)
                    OutlinedButton(
                        onClick = { viewModel.removeObjectRule(detail.peer, rule.objectId, rule.category) },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = remove },
                    ) {
                        Text(stringResource(R.string.sharing_action_clear))
                    }
                }
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.sharing_object_add_title),
                style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(
                value = objectId,
                onValueChange = { objectId = it; viewModel.clearObjectRuleError() },
                label = { Text(stringResource(R.string.sharing_object_id_label)) },
                isError = objectError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            objectError?.let {
                val text = stringResource(R.string.sharing_object_error_empty)
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { contentDescription = text },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SharingCategory.entries.forEach { entry ->
                    val label = stringResource(SharingLabels.category(entry))
                    FilterChip(
                        selected = category == entry,
                        onClick = { category = entry },
                        label = { Text(label) },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = allow,
                    onClick = { allow = true },
                    label = { Text(stringResource(R.string.sharing_action_allow)) },
                )
                FilterChip(
                    selected = !allow,
                    onClick = { allow = false },
                    label = { Text(stringResource(R.string.sharing_action_deny)) },
                )
            }
            OutlinedButton(
                // Cleared only once the exception is really recorded.
                onClick = {
                    scope.launch {
                        val effect = if (allow) AccessEffect.ALLOW else AccessEffect.DENY
                        if (viewModel.addObjectRule(detail.peer, objectId, category, effect)) objectId = ""
                    }
                },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.sharing_object_add))
            }
        }
    }
}

@Composable
private fun DevicesCard(
    detail: PeerDetail,
    viewModel: SharingViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    enabled: Boolean = true,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sharing_devices_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.sharing_devices_hint), style = MaterialTheme.typography.bodySmall)
            // Authorising a device is the peer proving the device is theirs, so
            // it is signed with the peer's key. This build can only stand in
            // for a demo peer whose key it holds; otherwise the screen says
            // what it is waiting for rather than offering a button the owner
            // has no authority to press.
            if (viewModel.canSimulate(detail.peer)) {
                var newDevice by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = newDevice,
                    onValueChange = { newDevice = it },
                    label = { Text(stringResource(R.string.sharing_action_authorize_device)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            // Cleared only when the entry really exists.
                            if (viewModel.simulateAuthorizeDevice(detail.peer, DeviceId(newDevice.trim()))) {
                                newDevice = ""
                            }
                        }
                    },
                    enabled = enabled && newDevice.isNotBlank(),
                ) {
                    Text(stringResource(R.string.sharing_action_authorize_device))
                }
            } else {
                Text(
                    stringResource(R.string.sharing_new_device_needs_peer_signature),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            detail.devices.forEach { device ->
                val stateLabel = stringResource(
                    if (device.revoked) R.string.sharing_device_revoked else R.string.sharing_device_authorised
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${device.device.value}: $stateLabel"
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.device.value, style = MaterialTheme.typography.bodyMedium)
                        Text(stateLabel, style = MaterialTheme.typography.labelSmall)
                    }
                    if (device.authorised) {
                        OutlinedButton(
                            onClick = { viewModel.revokeDevice(detail.peer, device.device) },
                            enabled = enabled,
                        ) {
                            Text(stringResource(R.string.sharing_action_revoke_device))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(
    detail: PeerDetail,
    viewModel: SharingViewModel,
    onNavigateBack: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    enabled: Boolean = true,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (detail.status == RelationshipStatus.PENDING) {
                // Accepting is the peer's act, signed with the peer's key. This
                // build can only stand in for a demo peer whose key it holds;
                // for anybody else the screen says what it is waiting for
                // instead of offering a button that would forge consent.
                if (viewModel.canSimulate(detail.peer)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                viewModel.simulateAccept(
                                    detail.peer,
                                    detail.pendingConsentCategories + detail.consentedCategories,
                                    DeviceId("${detail.peer.value.take(8)}-device"),
                                )
                            }
                        },
                        enabled = enabled,
                    ) { Text(stringResource(R.string.sharing_action_accept)) }
                    OutlinedButton(
                        onClick = { scope.launch { viewModel.simulateDecline(detail.peer) } },
                        enabled = enabled,
                    ) {
                        Text(stringResource(R.string.sharing_action_decline))
                    }
                } else {
                    Text(
                        stringResource(R.string.sharing_awaiting_peer_signature),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (detail.pendingConsentCategories.isNotEmpty() &&
                detail.status == RelationshipStatus.ACCEPTED
            ) {
                Text(
                    stringResource(R.string.sharing_source_consent_expansion),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (viewModel.canSimulate(detail.peer)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                viewModel.simulateAccept(
                                    detail.peer,
                                    detail.consentedCategories + detail.pendingConsentCategories,
                                    detail.devices.firstOrNull { it.authorised }?.device
                                        ?: DeviceId("${detail.peer.value.take(8)}-device"),
                                )
                            }
                        },
                        enabled = enabled,
                    ) { Text(stringResource(R.string.sharing_action_confirm_consent)) }
                } else {
                    Text(
                        stringResource(R.string.sharing_awaiting_peer_signature),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!detail.status.isTerminal) {
                OutlinedButton(onClick = { viewModel.revoke(detail.peer) }, enabled = enabled) {
                    Text(stringResource(R.string.sharing_action_revoke))
                }
            }
            // Irreversible, and next to buttons that are not: it asks first.
            var confirmingPurge by rememberSaveable { mutableStateOf(false) }
            OutlinedButton(onClick = { confirmingPurge = true }, enabled = enabled) {
                Text(stringResource(R.string.sharing_action_purge))
            }
            if (confirmingPurge) {
                SharingPurgeConfirmDialog(
                    onConfirm = {
                        confirmingPurge = false
                        // Navigates away only when the deletion actually ran;
                        // otherwise leaving would claim data was removed that
                        // is still there.
                        scope.launch { if (viewModel.purge(detail.peer)) onNavigateBack() }
                    },
                    onDismiss = { confirmingPurge = false },
                )
            }
            Text(
                stringResource(R.string.sharing_external_copies_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
