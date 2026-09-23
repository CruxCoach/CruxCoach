package com.cruxcoach.android.ui.sharing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.PreviewItem
import com.cruxcoach.android.sharing.SharingFriend
import com.cruxcoach.android.sharing.SharingInvitation
import com.cruxcoach.android.sharing.SharingNames
import com.cruxcoach.android.sharing.SharingPersonDetail
import com.cruxcoach.android.sharing.SharingPreset
import com.cruxcoach.android.sharing.VisibleState
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.common.InfoHeading
import com.cruxcoach.android.ui.settings.SettingsChoices
import com.cruxcoach.android.ui.settings.SettingsGroupHeader
import com.cruxcoach.android.ui.settings.SettingsSectionCard
import com.cruxcoach.android.ui.settings.SettingsToggleRow
import com.cruxcoach.domain.sharing.Bech32
import com.cruxcoach.domain.sharing.CircleBaselines
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import kotlinx.coroutines.delay

/** The pages of the one sharing destination (same pattern as the settings pages). */
internal sealed interface SharingPage {
    val key: String

    data object Overview : SharingPage { override val key = "" }
    data class Person(val peer: String) : SharingPage { override val key get() = "person:$peer" }
    data class Preset(val circle: SharingCircle) : SharingPage { override val key get() = "preset:${circle.name}" }
    data object Connection : SharingPage { override val key = "connection" }

    companion object {
        fun parse(key: String): SharingPage = when {
            key.startsWith("person:") -> Person(key.removePrefix("person:"))
            key.startsWith("preset:") -> SharingCircle.entries.firstOrNull { it.name == key.removePrefix("preset:") }?.let(::Preset) ?: Overview
            key == Connection.key -> Connection
            else -> Overview
        }
    }
}

/** Everything a page can ask for; tests pass no-op defaults. */
internal class SharingActions(
    val setReachable: (Boolean) -> Unit = {},
    val syncNow: () -> Unit = {},
    val request: (String, SharingCircle, String, () -> Unit) -> Unit = { _, _, _, _ -> },
    val clearRequestError: () -> Unit = {},
    val accept: (String, SharingCircle, Boolean) -> Unit = { _, _, _ -> },
    val decline: (String) -> Unit = {},
    val requestAgain: (String, SharingCircle, String?) -> Unit = { _, _, _ -> },
    val setOutgoing: (String, Boolean) -> Unit = { _, _ -> },
    val setCircle: (String, SharingCircle) -> Unit = { _, _ -> },
    val setLabel: (String, String) -> Unit = { _, _ -> },
    val end: (String) -> Unit = {},
    val remove: (String, () -> Unit) -> Unit = { _, _ -> },
    val setPreset: (SharingPreset) -> Unit = {},
    val setCategory: (SharingPersonDetail, SharingCategory, Boolean) -> Unit = { _, _, _ -> },
    val setTrainingDays: (SharingPersonDetail, Int) -> Unit = { _, _ -> },
    val setItem: (SharingPersonDetail, PreviewItem, Boolean) -> Unit = { _, _, _ -> },
)

private const val REFRESH_MS = 5_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(
    onNavigateBack: () -> Unit,
    viewModel: SharingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var key by rememberSaveable { mutableStateOf(SharingPage.Overview.key) }
    val page = SharingPage.parse(key)
    LaunchedEffect(key) { viewModel.openPerson((page as? SharingPage.Person)?.peer) }
    // Friends accept, end and send while the screen is open.
    LaunchedEffect(Unit) { while (true) { delay(REFRESH_MS); viewModel.refresh() } }
    val back = { if (page == SharingPage.Overview) onNavigateBack() else key = SharingPage.Overview.key }
    BackHandler(enabled = page != SharingPage.Overview) { back() }
    val actions = remember(viewModel) {
        SharingActions(
            setReachable = viewModel::setReachable,
            syncNow = viewModel::syncNow,
            request = viewModel::request,
            clearRequestError = viewModel::clearRequestError,
            accept = viewModel::accept,
            decline = viewModel::decline,
            requestAgain = viewModel::requestAgain,
            setOutgoing = viewModel::setOutgoing,
            setCircle = viewModel::setCircle,
            setLabel = viewModel::setLabel,
            end = viewModel::end,
            remove = viewModel::remove,
            setPreset = viewModel::setPreset,
            setCategory = viewModel::setCategory,
            setTrainingDays = viewModel::setTrainingDays,
            setItem = viewModel::setItem,
        )
    }
    val title = when (page) {
        SharingPage.Overview -> stringResource(R.string.sharing_title)
        is SharingPage.Person -> state.detail?.friend?.displayName ?: SharingNames.shortId(page.peer)
        is SharingPage.Preset -> stringResource(SharingLabels.circle(page.circle))
        SharingPage.Connection -> stringResource(R.string.sharing_connection_title)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.testTag("sharing_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        SharingContent(state, page, onOpen = { key = it.key }, actions = actions, modifier = Modifier.padding(padding))
    }
}

@Composable
internal fun SharingContent(
    state: SharingUiState,
    page: SharingPage,
    onOpen: (SharingPage) -> Unit,
    actions: SharingActions,
    modifier: Modifier = Modifier,
) {
    if (!state.available) {
        Box(modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(R.string.sharing_unavailable), modifier = Modifier.testTag("sharing_unavailable"))
        }
        return
    }
    val overview = state.overview
    if (overview == null) {
        Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            if (state.loading) CircularProgressIndicator()
            else Text(stringResource(R.string.sharing_failed, state.error ?: "unexpected"), modifier = Modifier.testTag("sharing_error"))
        }
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
        when (page) {
            SharingPage.Overview -> OverviewPage(state, onOpen, actions)
            is SharingPage.Person -> {
                val detail = state.detail?.takeIf { it.friend.peer == page.peer }
                when {
                    detail != null -> PersonPage(detail, state.busy, actions, onRemoved = { onOpen(SharingPage.Overview) })
                    overview.friends.none { it.peer == page.peer } ->
                        Text(stringResource(R.string.sharing_person_missing), modifier = Modifier.testTag("sharing_person_missing"))
                    else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            is SharingPage.Preset -> PresetPage(overview.presets, page.circle, state.busy, actions)
            SharingPage.Connection -> ConnectionPage(overview.status, state.busy, actions)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OverviewPage(state: SharingUiState, onOpen: (SharingPage) -> Unit, actions: SharingActions) {
    val overview = state.overview ?: return
    val status = overview.status
    SettingsSectionCard {
        SettingsToggleRow(
            title = stringResource(R.string.sharing_reachable_title),
            description = stringResource(R.string.sharing_reachable_info),
            checked = status.discovery.enabled,
            enabled = !state.busy,
            onCheckedChange = actions.setReachable,
            modifier = Modifier.testTag("sharing_reachable"),
        )
        OwnIdRow(status.account)
    }
    if (overview.invitations.isNotEmpty()) {
        SettingsGroupHeader(stringResource(R.string.sharing_invitations_title))
        overview.invitations.forEach { InvitationCard(it, overview.presets, state.busy, actions) }
    }
    SettingsGroupHeader(stringResource(R.string.sharing_people_title))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        if (overview.friends.isEmpty()) {
            Text(stringResource(R.string.sharing_no_people), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp).testTag("sharing_no_people"))
        }
        val order = listOf(VisibleState.PENDING, VisibleState.ACTIVE, VisibleState.STOPPED, VisibleState.ENDED)
        val friends = overview.friends.sortedWith(compareBy<SharingFriend> { order.indexOf(it.visible) }.thenBy { it.displayName.lowercase() })
        friends.forEachIndexed { index, friend ->
            SharingDestinationRow(
                title = friend.displayName,
                summary = personSummary(friend),
                icon = Icons.Outlined.Person,
                modifier = Modifier.testTag("sharing_person_${friend.peer.take(12)}"),
                onClick = { onOpen(SharingPage.Person(friend.peer)) },
            )
            if (index < friends.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp))
        }
    }
    AddPersonCard(state, actions)
    InfoHeading(stringResource(R.string.sharing_presets_title), stringResource(R.string.sharing_presets_info))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        SharingLabels.circles.forEachIndexed { index, circle ->
            val preset = overview.presets.getValue(circle)
            SharingDestinationRow(
                title = stringResource(SharingLabels.circle(circle)),
                summary = categoriesLabel(effectiveCategories(overview.presets, circle)),
                icon = if (circle == SharingCircle.FRIENDS) Icons.Outlined.Group else Icons.Outlined.PeopleOutline,
                modifier = Modifier.testTag("sharing_preset_${preset.circle.name}"),
                onClick = { onOpen(SharingPage.Preset(circle)) },
            )
            if (index == 0) HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp))
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        val connected = status.relays.count { it.connection == "connected" }
        val pending = status.local.outboundPending.toInt()
        SharingDestinationRow(
            title = stringResource(R.string.sharing_connection_title),
            summary = listOfNotNull(
                stringResource(R.string.sharing_connection_summary, connected, status.relays.size),
                if (pending > 0) pluralStringResource(R.plurals.sharing_pending_messages, pending, pending) else null,
            ).joinToString(" · "),
            icon = Icons.Outlined.Hub,
            modifier = Modifier.testTag("sharing_open_connection"),
            onClick = { onOpen(SharingPage.Connection) },
        )
    }
}

@Composable
private fun personSummary(friend: SharingFriend): String {
    val parts = mutableListOf(stringResource(SharingLabels.state(friend.visible)), stringResource(SharingLabels.circle(friend.person.circle)))
    if (friend.received > 0) parts += pluralStringResource(R.plurals.sharing_received_count, friend.received, friend.received)
    return parts.joinToString(" · ")
}

internal fun effectiveCategories(presets: Map<SharingCircle, SharingPreset>, circle: SharingCircle): Set<SharingCategory> =
    CircleBaselines(presets.mapValues { it.value.categories }).effectiveFor(circle)

@Composable
private fun OwnIdRow(account: String) {
    val clipboard = LocalClipboardManager.current
    val title = stringResource(R.string.sharing_own_id_title)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(SharingNames.shortId(account), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sharing_own_id"))
        }
        InfoButton(title, stringResource(R.string.sharing_own_id_info))
        IconButton(onClick = { clipboard.setText(AnnotatedString(Bech32.npub(account) ?: account)) }, modifier = Modifier.testTag("sharing_copy_id")) {
            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.sharing_copy_id))
        }
    }
}

@Composable
private fun InvitationCard(
    invitation: SharingInvitation,
    presets: Map<SharingCircle, SharingPreset>,
    busy: Boolean,
    actions: SharingActions,
) {
    var circle by rememberSaveable(invitation.peer) { mutableStateOf(SharingCircle.ACQUAINTANCES) }
    var outgoing by rememberSaveable(invitation.peer) { mutableStateOf(true) }
    SettingsSectionCard {
        Text(SharingNames.shortId(invitation.peer), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.sharing_invitation_body), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.sharing_circle_label), style = MaterialTheme.typography.labelLarge)
        SettingsChoices(SharingLabels.circles.map { it to stringResource(SharingLabels.circle(it)) }, circle, { circle = it })
        SettingsToggleRow(
            title = stringResource(R.string.sharing_invitation_share_title),
            description = stringResource(R.string.sharing_invitation_share_info),
            checked = outgoing,
            onCheckedChange = { outgoing = it },
            modifier = Modifier.testTag("sharing_invitation_share"),
        )
        Text(
            if (outgoing) stringResource(R.string.sharing_invitation_you_share, categoriesLabel(effectiveCategories(presets, circle)))
            else stringResource(R.string.sharing_invitation_you_share_nothing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.accept(invitation.peer, circle, outgoing) }, enabled = !busy,
                modifier = Modifier.testTag("sharing_accept")) { Text(stringResource(R.string.sharing_accept)) }
            OutlinedButton(onClick = { actions.decline(invitation.peer) }, enabled = !busy,
                modifier = Modifier.testTag("sharing_decline")) { Text(stringResource(R.string.sharing_decline)) }
        }
    }
}

@Composable
private fun AddPersonCard(state: SharingUiState, actions: SharingActions) {
    var typed by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var circle by rememberSaveable { mutableStateOf(SharingCircle.ACQUAINTANCES) }
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_add_title), stringResource(R.string.sharing_add_info))
        val error = state.requestError?.let {
            stringResource(when (it) {
                RequestError.EMPTY -> R.string.sharing_add_error_empty
                RequestError.NOT_A_PUBLIC_KEY -> R.string.sharing_add_error_not_public
                RequestError.MALFORMED -> R.string.sharing_add_error_malformed
                RequestError.SELF -> R.string.sharing_add_error_self
            })
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it; actions.clearRequestError() },
            label = { Text(stringResource(R.string.sharing_add_id_label)) },
            supportingText = { Text(error ?: stringResource(R.string.sharing_add_id_hint)) },
            isError = error != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("sharing_add_id"),
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(64) },
            label = { Text(stringResource(R.string.sharing_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("sharing_add_name"),
        )
        Text(stringResource(R.string.sharing_circle_label), style = MaterialTheme.typography.labelLarge)
        SettingsChoices(SharingLabels.circles.map { it to stringResource(SharingLabels.circle(it)) }, circle, { circle = it })
        Button(
            // Cleared only once the request is really recorded.
            onClick = { actions.request(typed, circle, label) { typed = ""; label = "" } },
            enabled = !state.busy && typed.isNotBlank(),
            modifier = Modifier.testTag("sharing_add_send"),
        ) { Text(stringResource(R.string.sharing_add_send)) }
    }
}

/** Like the settings destination row, with the summary visible: it is status, not help. */
@Composable
internal fun SharingDestinationRow(
    title: String,
    summary: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (summary.isNotEmpty()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
