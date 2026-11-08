package com.cruxcoach.android.communityclient

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.communityclient.ChannelType
import com.cruxcoach.communityclient.CommunityMessage
import com.cruxcoach.communityclient.CommunityProfile
import com.cruxcoach.communityclient.MAX_DISPLAY_NAME_LENGTH
import com.cruxcoach.communityclient.RoomListItem
import com.cruxcoach.communityclient.SessionState
import com.cruxcoach.communityclient.SystemNotice
import com.cruxcoach.communityclient.ThreadSummary
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// ── Home: turn on, join, room list ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityHomeScreen(
    onNavigateBack: () -> Unit,
    onOpenRoom: (channelId: String, type: String) -> Unit,
    initialInvite: String? = null,
    viewModel: CommunityHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(initialInvite, state.enabled, state.joined) {
        if (initialInvite != null && state.enabled && !state.joined) viewModel.useInvite(initialInvite)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.community_client_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.enabled && state.joined) {
                        ConnectionLabel(state.session)
                        IconButton(onClick = viewModel::refreshRooms) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.enabled -> IntroPane(state, onTurnOn = { viewModel.setEnabled(true) })
                !state.joined -> JoinPane(state, viewModel)
                state.session is SessionState.NotMember -> NotMemberPane(onUseInvite = viewModel::forgetCommunity)
                state.askName -> NamePane(
                    state.myName,
                    state.name,
                    onShown = viewModel::loadSuggestion,
                    onSave = viewModel::saveName,
                    onSkip = viewModel::skipName,
                )
                else -> RoomListPane(
                    state,
                    onOpenRoom,
                    onJoin = { viewModel.joinRoom(it) },
                    onTurnOff = { viewModel.setEnabled(false) },
                    onLoadSuggestion = viewModel::loadSuggestion,
                    onSaveName = viewModel::saveName,
                    onClearNameError = viewModel::clearNameError,
                )
            }
        }
    }
}

@Composable
private fun ConnectionLabel(session: SessionState) {
    val text = when (session) {
        SessionState.Ready -> return
        SessionState.Idle, SessionState.Connecting, SessionState.Authenticating -> stringResource(R.string.community_client_connecting)
        is SessionState.Offline -> stringResource(R.string.community_client_offline)
        is SessionState.NotMember -> return
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun IntroPane(state: CommunityHomeState, onTurnOn: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.community_client_intro_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.community_client_intro_body), style = MaterialTheme.typography.bodyMedium)
        InfoCard {
            Text(stringResource(R.string.community_client_intro_preview), style = MaterialTheme.typography.bodyMedium)
            IdentityRow(state)
        }
        Button(onClick = onTurnOn, modifier = Modifier.fillMaxWidth().testTag("community_turn_on")) {
            Text(stringResource(R.string.community_client_turn_on))
        }
    }
}

@Composable
private fun JoinPane(state: CommunityHomeState, viewModel: CommunityHomeViewModel) {
    when (val step = state.join) {
        is JoinStep.EnterInvite -> EnterInvitePane(step, state, onUse = viewModel::useInvite, onMember = viewModel::connectAsMember)
        JoinStep.LoadingRules, JoinStep.Joining -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is JoinStep.Rules -> RulesPane(step, onAccept = viewModel::acceptAndJoin, onCancel = viewModel::backToInvite)
    }
}

@Composable
private fun EnterInvitePane(step: JoinStep.EnterInvite, identity: CommunityHomeState, onUse: (String) -> Unit, onMember: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.community_client_join_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.community_client_join_body), style = MaterialTheme.typography.bodyMedium)
        IdentityRow(identity)
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text(stringResource(R.string.community_client_invite_link)) },
            isError = step.error != null,
            supportingText = step.error?.let { { Text(joinErrorText(it)) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("community_invite_field"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { link = it.trim() } }) {
                Text(stringResource(R.string.community_client_paste))
            }
            Button(onClick = { onUse(link) }, enabled = link.isNotBlank(), modifier = Modifier.testTag("community_invite_continue")) {
                Text(stringResource(R.string.community_client_continue))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.community_client_already_member), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onMember) { Text(stringResource(R.string.community_client_connect_member)) }
    }
}

@Composable
private fun RulesPane(step: JoinStep.Rules, onAccept: (Boolean) -> Unit, onCancel: () -> Unit) {
    var ageConfirmed by rememberSaveable { mutableStateOf(false) }
    val policy = step.policy
    // Accepting needs the rules on screen: the text appears a moment after its card.
    // A text that renders to nothing must not block joining, hence the fallback.
    val texts = listOfNotNull(policy?.termsMarkdown, policy?.privacyMarkdown).filter { it.isNotBlank() }
    var shown by remember(step) { mutableStateOf(emptySet<String>()) }
    var waitedLongEnough by remember(step) { mutableStateOf(false) }
    LaunchedEffect(step) { delay(RULES_RENDER_FALLBACK_MS); waitedLongEnough = true }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.community_client_rules_title, step.invite.endpoint.host), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        if (policy == null) {
            Text(stringResource(R.string.community_client_rules_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            policy.termsMarkdown?.let { terms -> MarkdownCard(stringResource(R.string.community_client_rules_terms), terms) { shown = shown + terms } }
            policy.privacyMarkdown?.let { privacy -> MarkdownCard(stringResource(R.string.community_client_rules_privacy), privacy) { shown = shown + privacy } }
            if (policy.ageAttestationRequired) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ageConfirmed = !ageConfirmed }) {
                    Checkbox(checked = ageConfirmed, onCheckedChange = { ageConfirmed = it })
                    Text(stringResource(R.string.community_client_rules_age), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        step.error?.let { Text(joinErrorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = { onAccept(ageConfirmed) },
            enabled = (policy?.ageAttestationRequired != true || ageConfirmed) && (shown.containsAll(texts) || waitedLongEnough),
            modifier = Modifier.fillMaxWidth().testTag("community_accept_join"),
        ) { Text(stringResource(if (policy == null) R.string.community_client_join else R.string.community_client_accept_join)) }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
    }
}

/**
 * Right after joining: the name people see. Prefilled from the name the user already has
 * here, else from their public profile, never from fields saved only on this device.
 */
@Composable
private fun NamePane(myName: String?, name: NameEdit, onShown: () -> Unit, onSave: (String) -> Unit, onSkip: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    // The prefill last put into the field; while the text still equals it, the user has not typed.
    var applied by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { onShown() }
    // Both arrive in the background, the public name often first; the name here wins when it comes.
    val prefill = (myName?.takeIf { it.isNotBlank() } ?: name.suggested).take(MAX_DISPLAY_NAME_LENGTH)
    LaunchedEffect(prefill) {
        if (prefill.isNotBlank() && text == applied) {
            text = prefill
            applied = prefill
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.community_client_name_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.community_client_name_body), style = MaterialTheme.typography.bodyMedium)
        NameField(text, onChange = { text = it }, error = name.error, fromProfile = myName == null && name.suggested.isNotBlank() && text == name.suggested)
        Button(
            onClick = { onSave(text) },
            enabled = text.isNotBlank() && !name.saving,
            modifier = Modifier.fillMaxWidth().testTag("community_name_save"),
        ) { Text(stringResource(R.string.community_client_name_save)) }
        TextButton(onClick = onSkip, enabled = !name.saving, modifier = Modifier.fillMaxWidth().testTag("community_name_skip")) {
            Text(stringResource(R.string.community_client_name_skip))
        }
    }
}

@Composable
private fun NameField(text: String, onChange: (String) -> Unit, error: PublishError?, fromProfile: Boolean) {
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= MAX_DISPLAY_NAME_LENGTH) onChange(it) },
        label = { Text(stringResource(R.string.community_client_name_label)) },
        singleLine = true,
        isError = error != null,
        supportingText = when {
            error != null -> { { Text(stringResource(R.string.community_client_name_failed, publishErrorText(error))) } }
            fromProfile -> { { Text(stringResource(R.string.community_client_name_from_profile)) } }
            else -> null
        },
        modifier = Modifier.fillMaxWidth().testTag("community_name_field"),
    )
}

/** The user's name on the community, with a dialog to change it. */
@Composable
private fun NameRow(
    myName: String?,
    name: NameEdit,
    onLoadSuggestion: () -> Unit,
    onSave: (String) -> Unit,
    onClearError: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var openedAt by rememberSaveable { mutableStateOf(0) }
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(name.savedCount) { if (editing && name.savedCount != openedAt) editing = false }
    // Without a name here the dialog suggests the public one, which may arrive after it opened.
    LaunchedEffect(name.suggested) { if (editing && myName == null && text.isEmpty()) text = name.suggested }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            myName?.let { stringResource(R.string.community_client_name_row, it) } ?: stringResource(R.string.community_client_name_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                // A longer name set in a Buzz app would not be editable in the capped field.
                text = (myName ?: name.suggested).take(MAX_DISPLAY_NAME_LENGTH)
                openedAt = name.savedCount
                onClearError()
                if (myName == null) onLoadSuggestion()
                editing = true
            },
            modifier = Modifier.testTag("community_name_change"),
        ) { Text(stringResource(R.string.community_client_name_change)) }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { if (!name.saving) editing = false },
            title = { Text(stringResource(R.string.community_client_name_title)) },
            text = { NameField(text, onChange = { text = it }, error = name.error, fromProfile = false) },
            confirmButton = {
                TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank() && !name.saving && text.trim() != myName) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }, enabled = !name.saving) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** How long the accept button waits at most for the rules text to render. */
private const val RULES_RENDER_FALLBACK_MS = 3_000L

@Composable
private fun NotMemberPane(onUseInvite: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.community_client_not_member), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onUseInvite) { Text(stringResource(R.string.community_client_use_invite)) }
    }
}

@Composable
private fun RoomListPane(
    state: CommunityHomeState,
    onOpenRoom: (String, String) -> Unit,
    onJoin: (String) -> Unit,
    onTurnOff: () -> Unit,
    onLoadSuggestion: () -> Unit,
    onSaveName: (String) -> Unit,
    onClearNameError: () -> Unit,
) {
    val rooms = state.rooms
    val streams = rooms.rooms.filter { it.channel.type != ChannelType.FORUM }
    val forums = rooms.rooms.filter { it.channel.type == ChannelType.FORUM }
    LazyColumn(Modifier.fillMaxSize().testTag("community_room_list"), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (streams.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.community_client_section_rooms)) }
            items(streams, key = { it.channel.id }) { RoomRow(it, onOpenRoom, onJoin) }
        }
        if (forums.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.community_client_section_forums)) }
            items(forums, key = { it.channel.id }) { RoomRow(it, onOpenRoom, onJoin) }
        }
        if (rooms.rooms.isEmpty()) {
            // Loading, empty or failed (also while offline): below it the name and key as with rooms.
            item(key = "status") {
                if (rooms.loading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Text(
                        stringResource(if (rooms.error != null) R.string.community_client_rooms_failed else R.string.community_client_no_rooms),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp).testTag("community_rooms_empty"),
                    )
                }
            }
        }
        // A fixed key keeps the name dialog open while rooms reload around it.
        item(key = "footer") {
            Column(Modifier.padding(16.dp)) {
                if (rooms.rooms.isNotEmpty() && rooms.error != null) {
                    Text(stringResource(R.string.community_client_rooms_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.joinRoomError?.let {
                    Text(stringResource(R.string.community_client_join_failed, publishErrorText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                NameRow(state.myName, state.name, onLoadSuggestion, onSaveName, onClearNameError)
                IdentityRow(state)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.community_client_enabled_switch), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = true, onCheckedChange = { if (!it) onTurnOff() })
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun RoomRow(item: RoomListItem, onOpen: (String, String) -> Unit, onJoin: (String) -> Unit) {
    val channel = item.channel
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(channel.id, channel.type.wire) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            when {
                channel.isPrivate -> Icons.Outlined.Lock
                channel.type == ChannelType.FORUM -> Icons.Outlined.Forum
                else -> Icons.AutoMirrored.Outlined.Chat
            },
            contentDescription = null,
            tint = if (item.isMember) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text("#" + channel.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (item.isMember) FontWeight.SemiBold else FontWeight.Normal)
            val about = channel.topic?.takeIf { it.isNotBlank() } ?: channel.about
            if (about.isNotBlank()) {
                Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!item.isMember) {
            TextButton(onClick = { onJoin(channel.id) }) { Text(stringResource(R.string.community_client_join_room)) }
        }
    }
}

// ── Room: stream timeline or forum topics ───────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityRoomScreen(
    onNavigateBack: () -> Unit,
    onOpenThread: (channelId: String, type: String, rootId: String) -> Unit,
    viewModel: CommunityRoomViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val room = state.room
    val type = if (state.isForum) ChannelType.FORUM.wire else ChannelType.STREAM.wire
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(room?.let { "#" + it.channel.name } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val about = room?.channel?.topic?.takeIf { it.isNotBlank() } ?: room?.channel?.about
                        if (!about.isNullOrBlank()) Text(about, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = {
            when {
                room == null -> Unit
                !room.isMember && room.channel.isReadOnlyOnJoin -> ReadOnlyBar(onJoin = viewModel::join, joining = state.joining, error = state.sendError)
                !room.isMember -> JoinBar(onJoin = viewModel::join, joining = state.joining, error = state.sendError)
                !room.canWrite -> ReadOnlyBar(onJoin = null)
                else -> Composer(
                    hint = stringResource(if (state.isForum) R.string.community_client_new_post_hint else R.string.community_client_message_hint, room.channel.name),
                    sending = state.sending,
                    error = state.sendError,
                    sentCount = state.sentCount,
                    onSend = viewModel::send,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.isForum) ForumList(state, onOpen = { onOpenThread(viewModel.channelId, type, it) })
            else StreamList(state, onOpenThread = { onOpenThread(viewModel.channelId, type, it) })
        }
    }
}

@Composable
private fun StreamList(state: CommunityRoomState, onOpenThread: (String) -> Unit) {
    val stream = state.stream
    if (stream.messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (stream.loading) CircularProgressIndicator()
            else Text(stream.error ?: stringResource(R.string.community_client_no_messages), modifier = Modifier.padding(32.dp))
        }
        return
    }
    val listState = rememberLazyListState()
    val newestFirst = remember(stream.messages) { stream.messages.asReversed() }
    LaunchedEffect(newestFirst.firstOrNull()?.id) { listState.animateScrollToItem(0) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("community_timeline"),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(newestFirst, key = { it.id }) { message ->
            val notice = message.notice
            if (notice != null) {
                NoticeRow(notice, message.createdAt, state.profiles, state.me)
            } else {
                MessageRow(
                    message = message,
                    profiles = state.profiles,
                    me = state.me,
                    thread = stream.threads[message.id],
                    // A reply also sent to the room belongs to its thread: open that thread.
                    onOpenThread = { onOpenThread(message.thread?.rootId ?: message.id) },
                )
            }
        }
    }
}

@Composable
private fun ForumList(state: CommunityRoomState, onOpen: (String) -> Unit) {
    val forum = state.forum
    if (forum.posts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (forum.loading) CircularProgressIndicator()
            else Text(forum.error ?: stringResource(R.string.community_client_no_posts), modifier = Modifier.padding(32.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("community_forum"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(forum.posts, key = { it.id }) { post ->
            Card(
                Modifier.fillMaxWidth().clickable { onOpen(post.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(post.displayContent.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trimStart('#', ' ').take(140), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${authorName(post.author, state.profiles, state.me)} · ${formatTime(post.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val count = forum.commentCounts[post.id] ?: 0
                    Text(pluralStringResource(R.plurals.community_client_comments, count, count), style = MaterialTheme.typography.labelMedium, color = OrangeAccent)
                }
            }
        }
    }
}

// ── Thread ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityThreadScreen(
    onNavigateBack: () -> Unit,
    viewModel: CommunityThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val room = state.room
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isForum) R.string.community_client_post_title else R.string.community_client_thread_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = {
            if (room?.canWrite == true) {
                Composer(
                    hint = stringResource(if (state.isForum) R.string.community_client_comment_hint else R.string.community_client_reply_hint),
                    sending = state.sending,
                    error = state.sendError,
                    sentCount = state.sentCount,
                    onSend = viewModel::reply,
                )
            }
        },
    ) { padding ->
        val thread = state.thread
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("community_thread"), contentPadding = PaddingValues(vertical = 8.dp)) {
            thread.root?.let { root ->
                item(key = root.id) { MessageRow(root, state.profiles, state.me, thread = null, onOpenThread = null) }
                item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            if (thread.loading && thread.replies.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            }
            items(thread.replies, key = { it.id }) { reply ->
                MessageRow(reply, state.profiles, state.me, thread = null, onOpenThread = null, indent = true)
            }
            thread.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        }
    }
}

// ── Shared pieces ───────────────────────────────────────────────────────────

@Composable
private fun MessageRow(
    message: CommunityMessage,
    profiles: Map<String, CommunityProfile>,
    me: String,
    thread: ThreadSummary?,
    onOpenThread: (() -> Unit)?,
    indent: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = onOpenThread != null) { onOpenThread?.invoke() }
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                authorName(message.author, profiles, me),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (message.author == me) OrangeAccent else MaterialTheme.colorScheme.onSurface,
            )
            Text(formatTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.editedContent != null) {
                Text(stringResource(R.string.community_client_edited), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (message.isDeleted) {
            Text(stringResource(R.string.community_client_deleted), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            RichText(style = linkStyle()) { Markdown(content = message.displayContent) }
        }
        if (message.reactions.isNotEmpty()) {
            Text(
                message.reactions.entries.sortedByDescending { it.value.size }.joinToString("  ") { "${it.key} ${it.value.size}" },
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (thread != null && thread.replyCount + thread.descendantCount > 0 && onOpenThread != null) {
            val count = maxOf(thread.replyCount, thread.descendantCount)
            Text(
                pluralStringResource(R.plurals.community_client_replies, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = OrangeAccent,
                fontWeight = FontWeight.SemiBold,
            )
        } else if (onOpenThread != null) {
            Text(stringResource(R.string.community_client_reply_action), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A relay notice (someone joined, the topic changed) as one quiet line. */
@Composable
private fun NoticeRow(notice: SystemNotice, createdAt: Long, profiles: Map<String, CommunityProfile>, me: String) {
    val actor = notice.actor?.let { authorLabel(it, profiles) } ?: stringResource(R.string.community_client_notice_someone)
    val target = notice.target?.let { authorLabel(it, profiles) } ?: stringResource(R.string.community_client_notice_someone)
    val selfJoined = notice.target == null || notice.target == notice.actor
    val text = when (notice.type) {
        SystemNotice.Type.MEMBER_JOINED -> when {
            selfJoined && notice.actor == me -> stringResource(R.string.community_client_notice_you_joined)
            selfJoined -> stringResource(R.string.community_client_notice_joined, actor)
            notice.target == me -> stringResource(R.string.community_client_notice_added_you, actor)
            else -> stringResource(R.string.community_client_notice_added, actor, target)
        }
        SystemNotice.Type.MEMBER_LEFT ->
            if (notice.actor == me) stringResource(R.string.community_client_notice_you_left)
            else stringResource(R.string.community_client_notice_left, actor)
        SystemNotice.Type.MEMBER_REMOVED ->
            if (notice.target == me) stringResource(R.string.community_client_notice_removed_you, actor)
            else stringResource(R.string.community_client_notice_removed, actor, target)
        SystemNotice.Type.TOPIC_CHANGED -> notice.text?.let { stringResource(R.string.community_client_notice_topic, actor, it) }
            ?: stringResource(R.string.community_client_notice_topic_cleared, actor)
        SystemNotice.Type.PURPOSE_CHANGED -> notice.text?.let { stringResource(R.string.community_client_notice_purpose, actor, it) }
            ?: stringResource(R.string.community_client_notice_purpose_cleared, actor)
        SystemNotice.Type.CHANNEL_CREATED -> stringResource(R.string.community_client_notice_created, actor)
        SystemNotice.Type.CHANNEL_ARCHIVED -> stringResource(R.string.community_client_notice_archived, actor)
        SystemNotice.Type.CHANNEL_UNARCHIVED -> stringResource(R.string.community_client_notice_unarchived, actor)
    }
    Text(
        "$text · ${formatTime(createdAt)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).testTag("community_notice"),
    )
}

@Composable
private fun Composer(hint: String, sending: Boolean, error: PublishError?, sentCount: Int, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    // The text stays until the relay took it, so a failed send can be retried.
    var clearedAt by rememberSaveable { mutableStateOf(sentCount) }
    LaunchedEffect(sentCount) {
        if (sentCount != clearedAt) {
            text = ""
            clearedAt = sentCount
        }
    }
    Column(Modifier.fillMaxWidth().imePadding()) {
        error?.let {
            Text(stringResource(R.string.community_client_send_failed, publishErrorText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).testTag("community_composer"),
                // Read-only, not disabled, while sending: disabling would drop the focus and close the keyboard.
                readOnly = sending,
                placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 5,
            )
            if (sending) {
                CircularProgressIndicator(Modifier.size(24.dp), color = OrangeAccent, strokeWidth = 2.dp)
            } else {
                IconButton(
                    onClick = { onSend(text.trim()) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.testTag("community_send"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.action_send), tint = if (text.isNotBlank()) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun JoinBar(onJoin: () -> Unit, joining: Boolean, error: PublishError?) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        error?.let { Text(stringResource(R.string.community_client_join_failed, publishErrorText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = onJoin, enabled = !joining, modifier = Modifier.fillMaxWidth().testTag("community_join_room")) { Text(stringResource(R.string.community_client_join_to_write)) }
    }
}

/** An announce room: anyone may read, joiners are guests who cannot write. */
@Composable
private fun ReadOnlyBar(onJoin: (() -> Unit)?, joining: Boolean = false, error: PublishError? = null) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.community_client_read_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(stringResource(R.string.community_client_join_failed, publishErrorText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (onJoin != null) {
            OutlinedButton(onClick = onJoin, enabled = !joining, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.community_client_follow_room)) }
        }
    }
}

/** Who signs, as an npub the user can copy and give to a community admin. */
@Composable
private fun IdentityRow(state: CommunityHomeState) {
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(
                if (state.usesAmber) R.string.community_client_identity_amber else R.string.community_client_identity_local,
                shortNpub(state.npub),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Which signatures to allow for good in Amber, and which not.
        if (state.usesAmber) {
            InfoButton(stringResource(R.string.community_client_amber_title), stringResource(R.string.community_client_amber_body))
        }
        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.npub)) }, modifier = Modifier.testTag("community_copy_npub")) {
            Text(stringResource(R.string.community_client_copy_key))
        }
    }
}

private fun shortNpub(npub: String): String = if (npub.length > 24) npub.take(12) + "…" + npub.takeLast(6) else npub

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun MarkdownCard(title: String, markdown: String, onShown: () -> Unit = {}) {
    InfoCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        // The library parses in the background, so the text has no height until it is there.
        RichText(style = linkStyle(), modifier = Modifier.onSizeChanged { if (it.height > 0) onShown() }) {
            Markdown(content = markdown)
        }
    }
}

@Composable
private fun joinErrorText(error: JoinError): String = stringResource(
    when (error) {
        JoinError.BAD_LINK -> R.string.community_client_error_bad_link
        JoinError.EXPIRED -> R.string.community_client_error_expired
        JoinError.USED_UP -> R.string.community_client_error_used_up
        JoinError.INVALID -> R.string.community_client_error_invalid
        JoinError.RATE_LIMITED -> R.string.community_client_error_rate_limited
        JoinError.SIGN_REFUSED -> R.string.community_client_error_sign_refused
        JoinError.NETWORK -> R.string.community_client_error_network
        JoinError.SERVER -> R.string.community_client_error_server
        JoinError.NOT_MEMBER -> R.string.community_client_not_member
    },
)

@Composable
private fun publishErrorText(error: PublishError): String = stringResource(
    when (error) {
        PublishError.SIGN_REFUSED -> R.string.community_client_publish_sign_refused
        PublishError.NETWORK -> R.string.community_client_error_network
        PublishError.RATE_LIMITED -> R.string.community_client_error_rate_limited
        PublishError.NOT_ALLOWED -> R.string.community_client_publish_not_allowed
        PublishError.REFUSED -> R.string.community_client_publish_refused
        PublishError.PROFILE_UNAVAILABLE -> R.string.community_client_publish_profile_unavailable
    },
)

private fun authorLabel(pubkey: String, profiles: Map<String, CommunityProfile>): String =
    profiles[pubkey]?.label ?: shortNpubOf(pubkey)

/** Like [authorLabel], but "You" for the user's own messages while they have no profile name. */
@Composable
private fun authorName(pubkey: String, profiles: Map<String, CommunityProfile>, me: String): String =
    profiles[pubkey]?.label ?: if (pubkey == me) stringResource(R.string.community_client_you) else shortNpubOf(pubkey)

private val npubs = java.util.concurrent.ConcurrentHashMap<String, String>()

/** A key without a profile, in the npub form people copy and share. */
private fun shortNpubOf(pubkey: String): String = npubs.getOrPut(pubkey) {
    runCatching { shortNpub(pubkey.hexToByteArray().toNpub()) }.getOrElse { pubkey.take(8) + "…" }
}

/** Links in the brand colour, readable on the dark surface. */
@Composable
private fun linkStyle(): RichTextStyle = remember {
    RichTextStyle(stringStyle = RichTextStringStyle(linkStyle = SpanStyle(color = OrangeAccent, textDecoration = TextDecoration.Underline)))
}

private fun formatTime(unixSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(unixSeconds * 1000))
