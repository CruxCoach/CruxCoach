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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.PeerSummary
import com.cruxcoach.android.sharing.SharingDemoData
import com.cruxcoach.domain.sharing.NativeSharingGateState
import com.cruxcoach.android.sharing.PeerIdParseError
import kotlinx.coroutines.launch
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle

/**
 * FEAT-062 entry screen: what each circle sees by default, who is involved, and
 * an honest statement about what withdrawing cannot undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(
    onNavigateBack: () -> Unit,
    onOpenPeer: (PeerId) -> Unit,
    viewModel: SharingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inviteError by viewModel.inviteError.collectAsStateWithLifecycle()
    val writeError by viewModel.writeError.collectAsStateWithLifecycle()
    val signing by viewModel.signing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sharing_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.nativeAvailable) SharingGateBanner(state.gate)
            MarmotTransportCard(viewModel, onOpenPeer = onOpenPeer)
            SharingSigningBanner(signing)

            SectionHeading(stringResource(R.string.sharing_circles_title))
            Text(
                stringResource(R.string.sharing_circles_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            SharingCircle.ordered.forEach { circle ->
                CircleBaselineCard(
                    circleLabel = stringResource(SharingLabels.circle(circle)),
                    explicit = state.baselines.explicitFor(circle),
                    effective = state.baselines.effectiveFor(circle),
                    // One permission change at a time: a second toggle while a
                    // signer prompt is open would be dropped anyway, so it must
                    // not look available.
                    enabled = !signing,
                    onToggle = { category, granted -> viewModel.setBaseline(circle, category, granted) },
                )
            }

            SectionHeading(stringResource(R.string.sharing_people_title))
            writeError?.let { SharingWriteErrorMessage(it) }
            InviteRow(
                error = inviteError,
                enabled = !signing,
                onInvite = { typed, circle ->
                    // Offers whatever the chosen circle already grants. Offering
                    // nothing would produce a relationship that can be accepted
                    // and still release nothing; the person still has to accept
                    // either way.
                    viewModel.invite(typed, circle)
                },
                scope = scope,
                onEdit = viewModel::clearInviteError,
            )
            if (state.peers.isEmpty()) {
                Text(stringResource(R.string.sharing_people_empty))
            } else {
                state.peers.forEach { peer -> PeerRow(peer) { onOpenPeer(peer.peer) } }
            }

            if (viewModel.demoAvailable) {
                SectionHeading(stringResource(R.string.sharing_demo_title))
                Text(
                    stringResource(R.string.sharing_demo_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { viewModel.seedDemoData() }, enabled = !signing) {
                    Text(stringResource(R.string.sharing_demo_seed))
                }
            }

            SharingDeviceSection(viewModel)

            SharingRecoverySection(viewModel)

            SharingExternalCopiesCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InviteRow(
    error: PeerIdParseError?,
    enabled: Boolean,
    onInvite: suspend (String, SharingCircle) -> Boolean,
    onEdit: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    // The widest circle is the safe default; the picker exists because circles
    // are exclusive and a person has to be filed somewhere deliberately.
    var circle by rememberSaveable { mutableStateOf(SharingCircle.ALL_OTHER_USERS) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it; onEdit() },
            label = { Text(stringResource(R.string.sharing_invite_label)) },
            supportingText = { Text(stringResource(R.string.sharing_invite_hint)) },
            isError = error != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { SharingInviteError(it) }
        Text(
            stringResource(R.string.sharing_invite_circle_label),
            style = MaterialTheme.typography.labelLarge,
        )
        SharingCirclePicker(selected = circle, onSelect = { circle = it })
        OutlinedButton(
            // Cleared only once the entry is really in the ledger, so neither a
            // rejected identity nor a refused write looks like success.
            onClick = { scope.launch { if (onInvite(typed, circle)) typed = "" } },
            enabled = enabled && typed.isNotBlank(),
        ) {
            Text(stringResource(R.string.sharing_action_invite))
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun CircleBaselineCard(
    circleLabel: String,
    explicit: Set<SharingCategory>,
    effective: Set<SharingCategory>,
    enabled: Boolean = true,
    onToggle: (SharingCategory, Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                circleLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            SharingCategory.entries.forEach { category ->
                val categoryLabel = stringResource(SharingLabels.category(category))
                val inherited = category in effective && category !in explicit
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(categoryLabel, style = MaterialTheme.typography.bodyMedium)
                        if (inherited) {
                            Text(
                                stringResource(R.string.sharing_source_inherited, circleLabel),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Switch(
                        checked = category in explicit || inherited,
                        // An inherited grant cannot be switched off here: it
                        // belongs to the wider circle, and pretending otherwise
                        // would silently break the monotonicity the model
                        // guarantees.
                        enabled = enabled && !inherited,
                        onCheckedChange = { granted -> onToggle(category, granted) },
                        modifier = Modifier.semantics {
                            contentDescription = "$circleLabel: $categoryLabel"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerSummary, onClick: () -> Unit) {
    val name = SharingDemoData.displayName(peer.peer)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = name },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(SharingLabels.status(peer.status)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    R.string.sharing_person_summary,
                    peer.releasedCount,
                    SharingCategory.entries.size,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (peer.pendingConsentCount > 0) {
                Text(
                    stringResource(R.string.sharing_person_pending_consent, peer.pendingConsentCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClick) {
                Text(stringResource(R.string.sharing_cd_open_person, name))
            }
        }
    }
}
