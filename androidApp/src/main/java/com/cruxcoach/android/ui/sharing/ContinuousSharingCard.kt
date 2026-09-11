package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.*
import com.cruxcoach.domain.sharing.*
import java.text.DateFormat
import java.util.Date
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.delay

/** Current read-only data, with origin and age; the wire stream is never UI. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContinuousSharingCard(viewModel: SharingViewModel, peer: PeerId? = null) {
    val app by viewModel.state.collectAsStateWithLifecycle()
    val grants by viewModel.continuous.collectAsStateWithLifecycle()
    val pendingRequests by viewModel.pendingFriendships.collectAsStateWithLifecycle()
    val roles by viewModel.continuousRoles.collectAsStateWithLifecycle()
    val preview by viewModel.continuousPreview.collectAsStateWithLifecycle()
    val previewScope by viewModel.continuousPreviewScope.collectAsStateWithLifecycle()
    val signing by viewModel.signing.collectAsStateWithLifecycle()
    val discoveryEnabled by viewModel.discoveryEnabled.collectAsStateWithLifecycle()
    val relayStatus by viewModel.relayStatus.collectAsStateWithLifecycle()
    var editing by remember(peer) { mutableStateOf(false) }
    var selected by remember(peer) { mutableStateOf(peer?.let { setOf(it) } ?: emptySet()) }
    var categories by remember { mutableStateOf(setOf(SharingCategory.PROFILE_AND_GOALS)) }
    var historyDays by remember { mutableIntStateOf(30) }
    var chosenRoles by remember { mutableStateOf<Map<PeerId, SnapshotEndpointRole>>(emptyMap()) }
    val scope = ContinuousScope(categories, LocalDate.now(ZoneOffset.UTC).minusDays(historyDays.toLong()).toString())
    val selectedRoles = selected.associateWith { chosenRoles[it] ?: roles[it] ?: SnapshotEndpointRole.USER }
    LaunchedEffect(selected, categories, historyDays) { viewModel.clearContinuousPreview() }
    LaunchedEffect(grants) {
        val next = grants.flatMap { listOf(it.offer.expiresAt, it.asOf + 7 * 86_400_000L) }
            .filter { it > System.currentTimeMillis() }.minOrNull()
        if (next != null) { delay((next - System.currentTimeMillis()).coerceAtLeast(1)); viewModel.refreshContinuousView() }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.continuous_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.continuous_explanation))
            Text(stringResource(R.string.continuous_os_limits), style = MaterialTheme.typography.bodySmall)
            if (relayStatus.isNotEmpty() && relayStatus.values.none { it == "accepted" })
                Text(stringResource(R.string.continuous_offline))
            if (!discoveryEnabled && peer == null) {
                Text(stringResource(R.string.friendship_connectivity_notice), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !signing && app.nativeAvailable, onClick = { viewModel.bootstrapMarmot() }) {
                    Text(stringResource(R.string.friendship_receive_requests))
                }
            }
            Button(enabled = !signing && app.nativeAvailable, onClick = { editing = !editing }) {
                Text(stringResource(R.string.continuous_new))
            }
            TextButton(enabled = !signing && app.nativeAvailable, onClick = { viewModel.synchronizeSnapshots() }) {
                Text(stringResource(R.string.friendship_sync_now))
            }
            pendingRequests.filter { peer == null || it.peer == peer.value }.forEach { request ->
                Text(request.peer, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.friendship_preparing))
                TextButton(enabled = !signing, onClick = { viewModel.cancelFriendshipRequest(request.peer) }) { Text(stringResource(android.R.string.cancel)) }
            }
            if (editing) {
                if (peer == null) {
                    var identity by remember { mutableStateOf("") }
                    OutlinedTextField(identity, onValueChange = { if(it.length <= 128) identity = it }, label = { Text(stringResource(R.string.sharing_invite_label)) })
                    val parsed = SharingPeerIdParser.parse(identity)
                    TextButton(enabled = parsed is PeerIdParseResult.Valid, onClick = { selected = selected + (parsed as PeerIdParseResult.Valid).peer; identity = "" }) {
                        Text(stringResource(R.string.continuous_choose_people))
                    }
                    selected.filter { id -> app.peers.none { it.peer == id } }.forEach { person ->
                        Row { Checkbox(true, onCheckedChange = { selected = selected - person }); Text(person.value, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text(stringResource(R.string.friendship_connectivity_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.continuous_choose_people), style = MaterialTheme.typography.titleSmall)
                if (peer == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SharingCircle.entries.forEach { circle -> FilterChip(
                        selected = false, onClick = { selected = app.peers.filter { it.circle == circle }.map { it.peer }.toSet() },
                        label = { Text(stringResource(SharingLabels.circle(circle))) }) }
                }
                Text(stringResource(R.string.continuous_circle_fixed), style = MaterialTheme.typography.bodySmall)
                app.peers.filter { peer == null || it.peer == peer }.forEach { person ->
                    Row {
                        Checkbox(person.peer in selected, onCheckedChange = { checked -> selected = if (checked) selected + person.peer else selected - person.peer })
                        Text(person.peer.value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                selected.forEach { person ->
                    Row {
                        Checkbox(selectedRoles[person] == SnapshotEndpointRole.SERVER, onCheckedChange = { server ->
                            chosenRoles = chosenRoles + (person to if (server) SnapshotEndpointRole.SERVER else SnapshotEndpointRole.USER)
                        })
                        Column { Text(person.value, style = MaterialTheme.typography.labelSmall); Text(stringResource(R.string.continuous_server_role)) }
                    }
                }
                Text(stringResource(R.string.continuous_choose_scope), style = MaterialTheme.typography.titleSmall)
                listOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.TRAINING_HISTORY, SharingCategory.PRIVATE_NOTES).forEach { category ->
                    Row {
                        Checkbox(category in categories, onCheckedChange = { checked -> categories = if (checked) categories + category else categories - category })
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(SharingLabels.category(category)))
                            Text(stringResource(continuousScopeDescription(category)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(stringResource(R.string.continuous_excluded), style = MaterialTheme.typography.bodySmall)
                if (SharingCategory.TRAINING_HISTORY in categories) {
                    Text(stringResource(R.string.continuous_history))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0,30,90).forEach { days -> FilterChip(historyDays == days, onClick = { historyDays = days },
                            label = { Text(if (days == 0) stringResource(R.string.continuous_from_today) else stringResource(R.string.continuous_past_days, days)) }) }
                    }
                    Text(stringResource(R.string.continuous_since, scope.trainingSince))
                }
                OutlinedButton(enabled = !signing && selected.isNotEmpty() && selected.size <= 16,
                    onClick = { viewModel.previewContinuous(selected, scope) }) { Text(stringResource(R.string.continuous_preview)) }
                if (previewScope == scope && preview.keys == selected && selected.isNotEmpty()) {
                    preview.forEach { (recipient, records) ->
                        Text(recipient.value, style = MaterialTheme.typography.labelSmall)
                        Text(stringResource(R.string.continuous_preview_count, records.size))
                        ContinuousRecords(records)
                    }
                    Text(stringResource(R.string.continuous_replace_warning), style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !signing, onClick = {
                        viewModel.offerContinuous(selected, scope, selectedRoles)
                        editing = false; viewModel.clearContinuousPreview()
                    }) { Text(stringResource(R.string.continuous_offer)) }
                }
            }
            val shown = grants.filter { peer == null || it.offer.owner == peer.value || it.offer.recipient == peer.value }
            if (shown.isEmpty()) Text(stringResource(R.string.continuous_empty))
            shown.forEach { grant ->
                HorizontalDivider()
                val o = grant.offer
                Text(stringResource(if (grant.outgoing) R.string.continuous_shared_with else R.string.continuous_from,
                    if (grant.outgoing) o.recipient else o.owner), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(continuousStatus(grant.status)))
                grant.categories.forEach { Text(stringResource(SharingLabels.category(it))) }
                if (SharingCategory.TRAINING_HISTORY in o.scope.categories) Text(stringResource(R.string.continuous_since, o.scope.trainingSince))
                if (grant.status in setOf("ENDED", "DECLINED", "CONFLICT"))
                    Text(stringResource(if (grant.cleanupConfirmed) R.string.friendship_cleanup_confirmed else R.string.friendship_cleanup_pending), style = MaterialTheme.typography.bodySmall)
                if (grant.asOf > 0) Text(stringResource(R.string.continuous_as_of, displayTime(grant.asOf)))
                if (grant.confirmedAt > 0) Text(stringResource(R.string.continuous_confirmed, displayTime(grant.confirmedAt)))
                if (grant.pending) Text(stringResource(R.string.continuous_pending))
                if (grant.error == "NEEDS_OPEN") Text(stringResource(R.string.continuous_needs_open))
                if (grant.error == "OFFLINE") Text(stringResource(R.string.continuous_offline))
                if (grant.error == "RETRY") Text(stringResource(R.string.continuous_retry))
                if (grant.status == "STALE") Text(stringResource(R.string.continuous_stale))
                if (grant.error == "CAPACITY") Text(stringResource(R.string.continuous_capacity))
                if (grant.error == "LIMIT") Text(stringResource(R.string.continuous_limit))
                if (grant.status == "INVITED") {
                    o.scope.categories.forEach { Text(stringResource(continuousScopeDescription(it)), style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.continuous_accept_scope))
                    var server by remember(o.id) { mutableStateOf(false) }
                    Row { Checkbox(server, onCheckedChange = { server = it }); Text(stringResource(R.string.continuous_server_role)) }
                    var ownCategories by remember(o.id) { mutableStateOf(emptySet<SharingCategory>()) }
                    var ownDays by remember(o.id) { mutableIntStateOf(0) }
                    Text(stringResource(R.string.friendship_own_choice))
                    listOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.TRAINING_HISTORY, SharingCategory.PRIVATE_NOTES).forEach { category ->
                        Row { Checkbox(category in ownCategories, onCheckedChange = { checked -> ownCategories = if (checked) ownCategories + category else ownCategories - category })
                            Text(stringResource(SharingLabels.category(category))) }
                    }
                    if (SharingCategory.TRAINING_HISTORY in ownCategories) FlowRow {
                        listOf(0,30,90).forEach { days -> FilterChip(ownDays == days, onClick = { ownDays = days }, label = {
                            Text(if(days==0) stringResource(R.string.continuous_from_today) else stringResource(R.string.continuous_past_days, days)) }) }
                    }
                    val ownScope = ContinuousScope(ownCategories, LocalDate.now(ZoneOffset.UTC).minusDays(ownDays.toLong()).toString())
                    OutlinedButton(enabled = !signing, onClick = { viewModel.previewContinuous(setOf(PeerId(o.owner)), ownScope) }) { Text(stringResource(R.string.continuous_preview)) }
                    val previewReady = previewScope == ownScope && preview.keys == setOf(PeerId(o.owner))
                    if (previewReady) ContinuousRecords(preview.values.flatten())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !signing && (ownCategories.isEmpty() || previewReady), onClick = { viewModel.acceptContinuous(o.id, server, ownScope) }) { Text(stringResource(R.string.continuous_accept)) }
                        OutlinedButton(enabled = !signing, onClick = { viewModel.endContinuous(o.id) }) { Text(stringResource(R.string.continuous_decline)) }
                    }
                } else if (grant.status in setOf("OFFERED", "ACCEPTED", "ACTIVE", "RECONNECT", "STALE")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (grant.outgoing && grant.status in setOf("ACCEPTED", "ACTIVE")) TextButton(enabled = !signing, onClick = {
                            selected = setOf(PeerId(o.recipient)); categories = o.scope.categories; editing = true
                        }) { Text(stringResource(R.string.friendship_edit_own)) }
                        if (grant.outgoing && grant.status in setOf("ACCEPTED", "ACTIVE")) OutlinedButton(enabled = !signing, onClick = { viewModel.endContinuous(o.id, pause = true) }) { Text(stringResource(R.string.continuous_pause)) }
                        OutlinedButton(enabled = !signing, onClick = { viewModel.endContinuous(o.id) }) { Text(stringResource(R.string.continuous_end)) }
                    }
                }
                if (!grant.outgoing) {
                    Text(stringResource(R.string.continuous_read_only), style = MaterialTheme.typography.bodySmall)
                    if (grant.status == "ACTIVE" && grant.records.isEmpty()) Text(stringResource(R.string.continuous_no_current_data))
                    ContinuousRecords(grant.records)
                }
            }
        }
    }
}

private fun displayTime(value: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

@Composable
internal fun ContinuousRecords(records: List<ContinuousRecord>) {
    var count by remember(records.map { it.id }) { mutableIntStateOf(10) }
    records.take(count).forEach { record ->
        val f = record.fields
        when (record.category) {
            SharingCategory.PROFILE_AND_GOALS -> {
                Text(f["name"].orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.continuous_profile_grades, f["boulderGrade"].orEmpty(), f["sportGrade"].orEmpty()))
                Text(stringResource(R.string.continuous_profile_frequency, f["climbingYears"].orEmpty(), f["sessionsPerWeek"].orEmpty()))
                Text(stringResource(R.string.continuous_goals, f["goals"].orEmpty()))
                Text(stringResource(R.string.continuous_equipment, f["equipment"].orEmpty()))
            }
            SharingCategory.TRAINING_HISTORY -> {
                Text(f["name"].orEmpty().ifEmpty { f["climbId"].orEmpty() }, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.continuous_log_detail, f["date"].orEmpty(), f["board"].orEmpty(), f["angle"].orEmpty(), f["attempts"].orEmpty()))
                f["grade"]?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.continuous_difficulty, it)) }
                Text(stringResource(if (f["outcome"] == "SEND") R.string.continuous_send else R.string.continuous_attempt))
            }
            SharingCategory.PRIVATE_NOTES -> { Text(f["climbId"].orEmpty(), style = MaterialTheme.typography.labelSmall); Text(f["note"].orEmpty()) }
            else -> Unit
        }
        Spacer(Modifier.height(6.dp))
    }
    if (records.size > count) TextButton(onClick = { count += 20 }) { Text(stringResource(R.string.continuous_more, records.size - count)) }
}
internal fun continuousScopeDescription(category: SharingCategory) = when(category) {
    SharingCategory.PROFILE_AND_GOALS -> R.string.continuous_profile_scope
    SharingCategory.TRAINING_HISTORY -> R.string.continuous_training_scope
    SharingCategory.PRIVATE_NOTES -> R.string.continuous_notes_scope
    else -> R.string.continuous_excluded
}
internal fun continuousStatus(status: String) = when(status) {
    "OFFERED", "INVITED" -> R.string.continuous_invited
    "ACCEPTED" -> R.string.continuous_first_sync
    "ACTIVE" -> R.string.continuous_active
    "PAUSED" -> R.string.continuous_paused
    "RECONNECT" -> R.string.continuous_reconnect
    "EXPIRED" -> R.string.continuous_expired
    "STALE" -> R.string.continuous_stale
    "CONFLICT" -> R.string.continuous_conflict
    else -> R.string.continuous_ended
}
