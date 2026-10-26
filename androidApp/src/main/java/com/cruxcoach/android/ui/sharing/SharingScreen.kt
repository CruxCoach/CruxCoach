package com.cruxcoach.android.ui.sharing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.runtime.DisposableEffect
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
import com.cruxcoach.android.sharing.VisibleState
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.common.InfoHeading
import com.cruxcoach.android.ui.settings.SettingsChoices
import com.cruxcoach.android.ui.settings.SettingsGroupHeader
import com.cruxcoach.android.ui.settings.SettingsSectionCard
import com.cruxcoach.android.ui.settings.SettingsToggleRow
import com.cruxcoach.domain.sharing.Bech32
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.ShareTemplate
import com.cruxcoach.domain.sharing.ShareTemplates
import com.cruxcoach.domain.sharing.TemplateId
import kotlinx.coroutines.delay

/** The pages of the one sharing destination (same pattern as the settings pages). */
internal sealed interface SharingPage {
    val key: String

    data object Overview : SharingPage { override val key = "" }
    data class Person(val peer: String) : SharingPage { override val key get() = "person:$peer" }
    data class Template(val id: TemplateId) : SharingPage { override val key get() = "template:${id.value}" }
    data object Connection : SharingPage { override val key = "connection" }
    data object History : SharingPage { override val key = "history" }
    data object WhoSees : SharingPage { override val key = "who-sees" }
    data object ShowCode : SharingPage { override val key = "show-code" }
    data object Scan : SharingPage { override val key = "scan" }

    companion object {
        fun parse(key: String): SharingPage = when {
            key.startsWith("person:") -> Person(key.removePrefix("person:"))
            key.startsWith("template:") -> key.removePrefix("template:").takeIf { it.isNotBlank() }?.let { Template(TemplateId(it)) } ?: Overview
            key == Connection.key -> Connection
            key == History.key -> History
            key == WhoSees.key -> WhoSees
            key == ShowCode.key -> ShowCode
            key == Scan.key -> Scan
            else -> Overview
        }
    }
}

/** Everything a page can ask for; tests pass no-op defaults. */
internal class SharingActions(
    val setReachable: (Boolean) -> Unit = {},
    val syncNow: () -> Unit = {},
    val request: (String, TemplateId, String, () -> Unit) -> Unit = { _, _, _, _ -> },
    val clearRequestError: () -> Unit = {},
    val accept: (String, TemplateId, Boolean) -> Unit = { _, _, _ -> },
    val decline: (String) -> Unit = {},
    val requestAgain: (String, TemplateId, String?) -> Unit = { _, _, _ -> },
    val setOutgoing: (String, Boolean) -> Unit = { _, _ -> },
    val setTemplate: (String, TemplateId) -> Unit = { _, _ -> },
    val setLabel: (String, String) -> Unit = { _, _ -> },
    val end: (String) -> Unit = {},
    val remove: (String, () -> Unit) -> Unit = { _, _ -> },
    val putTemplate: (ShareTemplate) -> Unit = {},
    val setCategory: (SharingPersonDetail, ShareCategory, Boolean) -> Unit = { _, _, _ -> },
    val setPeriod: (SharingPersonDetail, Int) -> Unit = { _, _ -> },
    val setItem: (SharingPersonDetail, PreviewItem, Boolean) -> Unit = { _, _, _ -> },
    val setAge: (Boolean) -> Unit = {},
    /** A new link; the screen hands it to the share sheet once it exists. */
    val createLink: () -> Unit = {},
    val shareLink: (String) -> Unit = {},
    val revokeLink: (String) -> Unit = {},
    val openLink: (TemplateId, String) -> Unit = { _, _ -> },
    val dismissLink: () -> Unit = {},
    val showCode: () -> Unit = {},
    val hideCode: () -> Unit = {},
    /** A scanned text; true when it was a contact code or link. */
    val scanned: (String) -> Boolean = { false },
)

/** Hand an invitation link to whatever the person sends it with. */
private fun shareLinkUrl(context: android.content.Context, url: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, context.getString(R.string.sharing_link_share_text, url))
    val chooser = android.content.Intent.createChooser(send, context.getString(R.string.sharing_link_share_title))
    if (context !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

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
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = remember(viewModel, context) {
        SharingActions(
            setReachable = viewModel::setReachable,
            syncNow = viewModel::syncNow,
            request = viewModel::request,
            clearRequestError = viewModel::clearRequestError,
            accept = viewModel::accept,
            decline = viewModel::decline,
            requestAgain = viewModel::requestAgain,
            setOutgoing = viewModel::setOutgoing,
            setTemplate = viewModel::setTemplate,
            setLabel = viewModel::setLabel,
            end = viewModel::end,
            remove = viewModel::remove,
            putTemplate = viewModel::putTemplate,
            setCategory = viewModel::setCategory,
            setPeriod = viewModel::setPeriod,
            setItem = viewModel::setItem,
            setAge = viewModel::setAge,
            createLink = { viewModel.createLink { url -> shareLinkUrl(context, url) } },
            shareLink = { url -> shareLinkUrl(context, url) },
            revokeLink = viewModel::revokeLink,
            openLink = viewModel::openLink,
            dismissLink = viewModel::dismissLink,
            showCode = viewModel::showCode,
            hideCode = viewModel::hideCode,
            scanned = viewModel::scanned,
        )
    }
    val title = when (page) {
        SharingPage.Overview -> stringResource(R.string.sharing_title)
        is SharingPage.Person -> state.detail?.friend?.displayName ?: SharingNames.shortId(page.peer)
        is SharingPage.Template -> stringResource(SharingLabels.template(page.id))
        SharingPage.Connection -> stringResource(R.string.sharing_connection_title)
        SharingPage.History -> stringResource(R.string.sharing_history_title)
        SharingPage.WhoSees -> stringResource(R.string.sharing_whosees_title)
        SharingPage.ShowCode -> stringResource(R.string.sharing_code_title)
        SharingPage.Scan -> stringResource(R.string.sharing_scan_title)
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
    /** The camera; tests pass a stand-in. */
    scanner: @Composable (onCode: (String) -> Unit) -> Unit = { CameraQrScanner(it) },
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
                    detail != null -> PersonPage(detail, overview.allTemplates, state.busy, actions, onRemoved = { onOpen(SharingPage.Overview) })
                    overview.friends.none { it.peer == page.peer } ->
                        Text(stringResource(R.string.sharing_person_missing), modifier = Modifier.testTag("sharing_person_missing"))
                    else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            is SharingPage.Template -> TemplatePage(overview.allTemplates, page.id, state.busy, actions)
            SharingPage.Connection -> ConnectionPage(overview.status, state.busy, actions, mailboxes = overview.linkInvites,
                gaps = overview.friends.mapNotNull { f -> (f.completeness as? com.cruxcoach.android.sharing.Completeness.Filling)
                    ?.since?.takeIf { it > 0 }?.let { f.displayName to it } })
            SharingPage.History -> HistoryPage(overview)
            SharingPage.WhoSees -> WhoSeesPage(overview)
            SharingPage.ShowCode -> ShowCodePage(state.shownCode, overview.status.account, state.busy, actions)
            SharingPage.Scan -> ScanPage(state.scanRefused, scanner) { text -> if (actions.scanned(text)) onOpen(SharingPage.Overview) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun OverviewPage(state: SharingUiState, onOpen: (SharingPage) -> Unit, actions: SharingActions) {
    val overview = state.overview ?: return
    val status = overview.status
    if (!overview.ageAnswered || !overview.ageConfirmed) AgeCard(overview.ageAnswered, state.busy, actions)
    state.linkOffer?.takeIf { overview.linkInvites }?.let {
        LinkOfferCard(it, overview.templates, overview.ageConfirmed && !state.busy, state.linkError, actions)
    }
    SettingsSectionCard {
        // Requests by npub exist only over MLS; with links nobody can ask without one.
        if (!overview.linkInvites) {
            SettingsToggleRow(
                title = stringResource(R.string.sharing_reachable_title),
                description = stringResource(R.string.sharing_reachable_info),
                checked = status.discovery.enabled,
                enabled = !state.busy,
                onCheckedChange = actions.setReachable,
                modifier = Modifier.testTag("sharing_reachable"),
            )
        }
        OwnIdRow(status.account, if (overview.linkInvites) R.string.sharing_own_id_info_links else R.string.sharing_own_id_info)
    }
    if (overview.invitations.isNotEmpty()) {
        SettingsGroupHeader(stringResource(R.string.sharing_invitations_title))
        overview.invitations.forEach { InvitationCard(it, overview.templates, overview.ageConfirmed && !state.busy, actions) }
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
    if (overview.ageConfirmed) {
        if (overview.linkInvites) LinkInviteCard(overview.links, state.busy, actions, onOpen)
        else AddPersonCard(state, overview.templates, actions)
    }
    InfoHeading(stringResource(R.string.sharing_templates_title), stringResource(R.string.sharing_templates_info))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        overview.templates.forEachIndexed { index, template ->
            SharingDestinationRow(
                title = stringResource(SharingLabels.template(template.id)),
                summary = categoriesLabel(template.categories),
                icon = if (template.id == ShareTemplates.TRAINING_PARTNER.id) Icons.Outlined.Group else Icons.Outlined.PeopleOutline,
                modifier = Modifier.testTag("sharing_template_${template.id.value}"),
                onClick = { onOpen(SharingPage.Template(template.id)) },
            )
            if (index < overview.templates.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp))
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        val connected = status.relays.count { it.connection == "connected" }
        val pending = status.local.outboundPending.toInt()
        SharingDestinationRow(
            title = stringResource(R.string.sharing_connection_title),
            summary = if (overview.linkInvites) {
                // States, never errors: what is waiting goes out by itself.
                listOfNotNull(
                    stringResource(when {
                        connected > 0 -> R.string.sharing_conn_connected
                        pending > 0 -> R.string.sharing_conn_offline_pending
                        else -> R.string.sharing_conn_offline
                    }),
                    if (pending > 0 && connected > 0) pluralStringResource(R.plurals.sharing_pending_delivery, pending, pending) else null,
                ).joinToString(" · ")
            } else listOfNotNull(
                stringResource(R.string.sharing_connection_summary, connected, status.relays.size),
                if (pending > 0) pluralStringResource(R.plurals.sharing_pending_messages, pending, pending) else null,
            ).joinToString(" · "),
            icon = Icons.Outlined.Hub,
            modifier = Modifier.testTag("sharing_open_connection"),
            onClick = { onOpen(SharingPage.Connection) },
        )
        HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp))
        SharingDestinationRow(
            title = stringResource(R.string.sharing_whosees_title),
            summary = stringResource(R.string.sharing_whosees_summary),
            icon = Icons.Outlined.Visibility,
            modifier = Modifier.testTag("sharing_open_who_sees"),
            onClick = { onOpen(SharingPage.WhoSees) },
        )
        HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp))
        SharingDestinationRow(
            title = stringResource(R.string.sharing_history_title),
            summary = pluralStringResource(R.plurals.sharing_history_count, overview.history.size, overview.history.size),
            icon = Icons.Outlined.History,
            modifier = Modifier.testTag("sharing_open_history"),
            onClick = { onOpen(SharingPage.History) },
        )
    }
}

@Composable
private fun personSummary(friend: SharingFriend): String {
    val parts = mutableListOf(stringResource(SharingLabels.state(friend.visible)), stringResource(SharingLabels.template(friend.person.template)))
    if (friend.received > 0) parts += pluralStringResource(R.plurals.sharing_received_count, friend.received, friend.received)
    return parts.joinToString(" · ")
}

/** "Are you 16 or older?" (FEAT-073 §2.7): asked once, before anything can be shared. */
@Composable
private fun AgeCard(answered: Boolean, busy: Boolean, actions: SharingActions) {
    SettingsSectionCard {
        if (answered) {
            Text(stringResource(R.string.sharing_age_under), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("sharing_age_under"))
            OutlinedButton(onClick = { actions.setAge(true) }, enabled = !busy, modifier = Modifier.testTag("sharing_age_change")) {
                Text(stringResource(R.string.sharing_age_change))
            }
        } else {
            Text(stringResource(R.string.sharing_age_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.sharing_age_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.setAge(true) }, enabled = !busy, modifier = Modifier.testTag("sharing_age_yes")) {
                    Text(stringResource(R.string.sharing_age_yes))
                }
                OutlinedButton(onClick = { actions.setAge(false) }, enabled = !busy, modifier = Modifier.testTag("sharing_age_no")) {
                    Text(stringResource(R.string.sharing_age_no))
                }
            }
        }
    }
}

@Composable
private fun OwnIdRow(account: String, info: Int) {
    val clipboard = LocalClipboardManager.current
    val title = stringResource(R.string.sharing_own_id_title)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(SharingNames.shortId(account), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sharing_own_id"))
        }
        InfoButton(title, stringResource(info))
        IconButton(onClick = { clipboard.setText(AnnotatedString(Bech32.npub(account) ?: account)) }, modifier = Modifier.testTag("sharing_copy_id")) {
            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.sharing_copy_id))
        }
    }
}

@Composable
private fun InvitationCard(
    invitation: SharingInvitation,
    templates: List<ShareTemplate>,
    enabled: Boolean,
    actions: SharingActions,
) {
    var template by rememberSaveable(invitation.peer) { mutableStateOf(ShareTemplates.TRAINING_PARTNER.id.value) }
    // Off by default (owner decision 2026-10-25): accepting shares nothing of one's own yet.
    var outgoing by rememberSaveable(invitation.peer) { mutableStateOf(false) }
    val chosen = templates.firstOrNull { it.id.value == template } ?: templates.firstOrNull()
    SettingsSectionCard {
        Text(SharingNames.shortId(invitation.peer), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.sharing_invitation_body), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.sharing_template_label), style = MaterialTheme.typography.labelLarge)
        SettingsChoices(templates.map { it.id.value to stringResource(SharingLabels.template(it.id)) }, template, { template = it })
        SettingsToggleRow(
            title = stringResource(R.string.sharing_invitation_share_title),
            description = stringResource(R.string.sharing_invitation_share_info),
            checked = outgoing,
            onCheckedChange = { outgoing = it },
            modifier = Modifier.testTag("sharing_invitation_share"),
        )
        Text(
            if (outgoing) stringResource(R.string.sharing_invitation_you_share, categoriesLabel(chosen?.categories.orEmpty()))
            else stringResource(R.string.sharing_invitation_you_share_nothing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { chosen?.let { actions.accept(invitation.peer, it.id, outgoing) } }, enabled = enabled && chosen != null,
                modifier = Modifier.testTag("sharing_accept")) { Text(stringResource(R.string.sharing_accept)) }
            OutlinedButton(onClick = { actions.decline(invitation.peer) }, enabled = enabled,
                modifier = Modifier.testTag("sharing_decline")) { Text(stringResource(R.string.sharing_decline)) }
        }
    }
}

/** An opened invitation link: who sent it, and a request only on a tap. */
@Composable
private fun LinkOfferCard(offer: LinkOffer, templates: List<ShareTemplate>, enabled: Boolean, error: String?, actions: SharingActions) {
    var label by rememberSaveable(offer.payload) { mutableStateOf("") }
    var template by rememberSaveable(offer.payload) { mutableStateOf(ShareTemplates.TRAINING_PARTNER.id.value) }
    val chosen = templates.firstOrNull { it.id.value == template } ?: templates.firstOrNull()
    val ended = offer.issuer.expiresAt <= System.currentTimeMillis()
    SettingsSectionCard {
        Text(stringResource(R.string.sharing_link_offer_title, SharingNames.shortId(offer.issuer.peer)), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }.testTag("sharing_link_offer"))
        Text(stringResource(R.string.sharing_link_offer_body), style = MaterialTheme.typography.bodyMedium)
        val problem = when {
            ended -> R.string.sharing_link_error_expired
            else -> error?.let(::linkErrorText)
        }
        problem?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("sharing_link_error"))
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(64) },
            label = { Text(stringResource(R.string.sharing_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("sharing_link_name"),
        )
        Text(stringResource(R.string.sharing_template_label), style = MaterialTheme.typography.labelLarge)
        SettingsChoices(templates.map { it.id.value to stringResource(SharingLabels.template(it.id)) }, template, { template = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { chosen?.let { actions.openLink(it.id, label) } }, enabled = enabled && !ended && chosen != null,
                modifier = Modifier.testTag("sharing_link_send")) { Text(stringResource(R.string.sharing_add_send)) }
            OutlinedButton(onClick = actions.dismissLink, modifier = Modifier.testTag("sharing_link_dismiss")) {
                Text(stringResource(R.string.sharing_link_dismiss))
            }
        }
    }
}

private fun linkErrorText(code: String): Int = when (code) {
    "link_expired" -> R.string.sharing_link_error_expired
    "self_invite" -> R.string.sharing_link_error_self
    "person_exists", "invitation_pending" -> R.string.sharing_link_error_known
    "peer_quota" -> R.string.sharing_link_error_full
    "contact_format" -> R.string.sharing_link_error_damaged
    else -> R.string.sharing_link_error_other
}

/** Invite by link (Olm): a link to send, the open ones to share again or revoke. */
@Composable
private fun LinkInviteCard(
    links: List<com.cruxcoach.android.sharing.MarmotLink>,
    busy: Boolean,
    actions: SharingActions,
    onOpen: (SharingPage) -> Unit,
) {
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_add_title), stringResource(R.string.sharing_link_info))
        Text(stringResource(R.string.sharing_link_body), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = actions.createLink, enabled = !busy, modifier = Modifier.testTag("sharing_link_create")) {
            Text(stringResource(R.string.sharing_link_create))
        }
        // Meeting in person: one shows a code, the other scans it.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onOpen(SharingPage.ShowCode) }, enabled = !busy, modifier = Modifier.testTag("sharing_code_show")) {
                Text(stringResource(R.string.sharing_code_show))
            }
            OutlinedButton(onClick = { onOpen(SharingPage.Scan) }, enabled = !busy, modifier = Modifier.testTag("sharing_scan_open")) {
                Text(stringResource(R.string.sharing_scan_open))
            }
        }
        links.forEach { link ->
            HorizontalDivider()
            Text(stringResource(R.string.sharing_link_open, untilLabel(link.expiresAt)), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("sharing_link_${link.id}"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.shareLink(com.cruxcoach.android.sharing.ShareLinks.url(link.payload)) }, enabled = !busy,
                    modifier = Modifier.testTag("sharing_link_share_${link.id}")) { Text(stringResource(R.string.sharing_link_share)) }
                OutlinedButton(onClick = { link.id?.let(actions.revokeLink) }, enabled = !busy && link.id != null,
                    modifier = Modifier.testTag("sharing_link_revoke_${link.id}")) { Text(stringResource(R.string.sharing_link_revoke)) }
            }
        }
    }
}

/** The own contact code as QR, with the identity both compare. */
@Composable
private fun ShowCodePage(code: String?, account: String, busy: Boolean, actions: SharingActions) {
    LaunchedEffect(Unit) { if (code == null) actions.showCode() }
    DisposableEffect(Unit) { onDispose { actions.hideCode() } }
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_code_title), stringResource(R.string.sharing_code_info))
        if (code == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val matrix = remember(code) { QrCodes.encode(code, 720) }
            QrImage(matrix, Modifier.fillMaxWidth().aspectRatio(1f).testTag("sharing_code_qr"))
        }
        Text(stringResource(R.string.sharing_code_identity, SharingNames.shortId(account)), style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("sharing_code_identity"))
        Text(stringResource(R.string.sharing_code_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = actions.showCode, enabled = !busy, modifier = Modifier.testTag("sharing_code_new")) {
            Text(stringResource(R.string.sharing_code_new))
        }
    }
}

/** Dark modules on white, whatever the theme: scanners need the contrast. */
@Composable
private fun QrImage(matrix: com.google.zxing.common.BitMatrix, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.background(androidx.compose.ui.graphics.Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(androidx.compose.ui.graphics.Color.Black,
                androidx.compose.ui.geometry.Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
private fun ScanPage(refused: Boolean, scanner: @Composable (onCode: (String) -> Unit) -> Unit, onCode: (String) -> Unit) {
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_scan_title), stringResource(R.string.sharing_scan_info))
        if (refused) {
            Text(stringResource(R.string.sharing_scan_refused), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("sharing_scan_refused"))
        }
        scanner(onCode)
    }
}

@Composable
private fun AddPersonCard(state: SharingUiState, templates: List<ShareTemplate>, actions: SharingActions) {
    var typed by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf(ShareTemplates.TRAINING_PARTNER.id.value) }
    val chosen = templates.firstOrNull { it.id.value == template } ?: templates.firstOrNull()
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
        Text(stringResource(R.string.sharing_template_label), style = MaterialTheme.typography.labelLarge)
        SettingsChoices(templates.map { it.id.value to stringResource(SharingLabels.template(it.id)) }, template, { template = it })
        Button(
            // Cleared only once the request is really recorded.
            onClick = { chosen?.let { actions.request(typed, it.id, label) { typed = ""; label = "" } } },
            enabled = !state.busy && typed.isNotBlank() && chosen != null,
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
