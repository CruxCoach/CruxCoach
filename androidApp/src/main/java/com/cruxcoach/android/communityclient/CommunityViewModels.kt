package com.cruxcoach.android.communityclient

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.communityclient.ChannelType
import com.cruxcoach.communityclient.ClaimRefusal
import com.cruxcoach.communityclient.ClaimResult
import com.cruxcoach.communityclient.CommunityClient
import com.cruxcoach.communityclient.CommunityInvite
import com.cruxcoach.communityclient.CommunityProfile
import com.cruxcoach.communityclient.ForumView
import com.cruxcoach.communityclient.JoinPolicy
import com.cruxcoach.communityclient.PublishResult
import com.cruxcoach.communityclient.RoomListItem
import com.cruxcoach.communityclient.RoomsState
import com.cruxcoach.communityclient.SessionState
import com.cruxcoach.communityclient.StreamTimeline
import com.cruxcoach.communityclient.ThreadRef
import com.cruxcoach.communityclient.ThreadView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The join steps the home screen walks through. */
sealed interface JoinStep {
    /** Waiting for an invite link. */
    data class EnterInvite(val error: JoinError? = null) : JoinStep
    data object LoadingRules : JoinStep

    /** Showing the community rules for [invite]. */
    data class Rules(val invite: CommunityInvite, val policy: JoinPolicy?, val error: JoinError? = null) : JoinStep
    data object Joining : JoinStep
}

/** User-facing join problems; the screen maps them to strings. */
enum class JoinError { BAD_LINK, EXPIRED, USED_UP, INVALID, RATE_LIMITED, SIGN_REFUSED, NETWORK, NOT_MEMBER }

/** State of the community home: off, joining, or the room list. */
data class CommunityHomeState(
    val enabled: Boolean,
    val joined: Boolean,
    val join: JoinStep = JoinStep.EnterInvite(),
    val session: SessionState = SessionState.Idle,
    val rooms: RoomsState = RoomsState(),
    val pubkey: String = "",
    val npub: String = "",
    val usesAmber: Boolean = false,
    /** Why the last room join failed, until the next try. */
    val joinRoomError: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommunityHomeViewModel @Inject constructor(
    private val holder: CommunityClientHolder,
) : ViewModel() {
    private val join = MutableStateFlow<JoinStep>(JoinStep.EnterInvite())
    private val joinRoomError = MutableStateFlow<String?>(null)

    val state: StateFlow<CommunityHomeState> = holder.setup.flatMapLatest { setup ->
        val client = holder.client()
        val base = CommunityHomeState(enabled = setup.enabled, joined = client != null, pubkey = holder.pubkey, npub = holder.npub, usesAmber = holder.usesAmber)
        if (client == null) {
            join.map { base.copy(join = it) }
        } else {
            combine(client.session.state, client.rooms, joinRoomError) { session, rooms, joinError ->
                base.copy(session = session, rooms = rooms, joinRoomError = joinError)
            }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Start from the stored setup, so a member does not see the intro flash by.
        holder.setup.value.let { CommunityHomeState(enabled = it.enabled, joined = it.enabled && it.joined != null) },
    )

    fun setEnabled(enabled: Boolean) = holder.setEnabled(enabled)

    /** Reads the rules for a pasted or opened invite link. */
    fun useInvite(text: String) {
        val invite = CommunityInvite.parse(text)
        if (invite == null) {
            join.value = JoinStep.EnterInvite(JoinError.BAD_LINK)
            return
        }
        join.value = JoinStep.LoadingRules
        viewModelScope.launch {
            join.value = runCatching { holder.joinSteps(invite.endpoint).rules() }
                .fold({ JoinStep.Rules(invite, it) }, { JoinStep.EnterInvite(JoinError.NETWORK) })
        }
    }

    /** Accepts the rules shown and claims the invite. */
    fun acceptAndJoin(ageConfirmed: Boolean) {
        val step = join.value as? JoinStep.Rules ?: return
        join.value = JoinStep.Joining
        viewModelScope.launch {
            val result = runCatching { holder.joinSteps(step.invite.endpoint).join(step.invite, step.policy, ageConfirmed) }
            val error = result.fold(
                onSuccess = { claim ->
                    when (claim) {
                        is ClaimResult.Joined -> null
                        is ClaimResult.Refused -> when (claim.reason) {
                            ClaimRefusal.EXPIRED -> JoinError.EXPIRED
                            ClaimRefusal.EXHAUSTED -> JoinError.USED_UP
                            ClaimRefusal.RATE_LIMITED -> JoinError.RATE_LIMITED
                            else -> JoinError.INVALID
                        }
                    }
                },
                onFailure = { if (it is java.io.IOException) JoinError.NETWORK else JoinError.SIGN_REFUSED },
            )
            if (error == null) {
                join.value = JoinStep.EnterInvite()
                holder.markJoined(step.invite.endpoint)
            } else {
                join.value = step.copy(error = error)
            }
        }
    }

    /** For keys that are already members: connect to the CruxCoach community directly. */
    fun connectAsMember() = holder.markJoined(CommunityClientHolder.DEFAULT_COMMUNITY)

    fun backToInvite() {
        join.value = JoinStep.EnterInvite()
    }

    /** Leaves the joined state on this device, e.g. after "not a member". */
    fun forgetCommunity() = holder.forgetCommunity()

    fun refreshRooms() {
        val client = holder.client() ?: return
        viewModelScope.launch { client.refreshRooms() }
    }

    fun joinRoom(channelId: String) {
        val client = holder.client() ?: return
        joinRoomError.value = null
        viewModelScope.launch { joinRoomError.value = publishError { client.joinRoom(channelId) } }
    }
}

/** A room screen: stream timeline or forum topics, plus what the user may do there. */
data class CommunityRoomState(
    val room: RoomListItem? = null,
    val isForum: Boolean = false,
    val stream: StreamTimeline = StreamTimeline(),
    val forum: ForumView = ForumView(),
    val profiles: Map<String, CommunityProfile> = emptyMap(),
    val me: String = "",
    val sending: Boolean = false,
    val sendError: String? = null,
)

@HiltViewModel
class CommunityRoomViewModel @Inject constructor(
    holder: CommunityClientHolder,
    savedState: SavedStateHandle,
) : ViewModel() {
    val channelId: String = checkNotNull(savedState["channelId"])
    private val isForum: Boolean = savedState.get<String>("type") == ChannelType.FORUM.wire
    private val client: CommunityClient? = holder.client()
    private val local = MutableStateFlow(CommunityRoomState(isForum = isForum, me = client?.me.orEmpty()))

    val state: StateFlow<CommunityRoomState> = run {
        val c = client ?: return@run local.asStateFlow()
        val room = c.rooms.map { s -> s.rooms.firstOrNull { it.channel.id == channelId } }.distinctUntilChanged()
        val content: Flow<Pair<StreamTimeline, ForumView>> =
            if (isForum) c.forum(channelId).map { StreamTimeline(loading = false) to it }
            else c.streamTimeline(channelId).map { it to ForumView(loading = false) }
        combine(content.onEach { (s, f) -> requestAuthors(c, s, f) }, room, c.profiles, local) { (s, f), item, profiles, l ->
            l.copy(room = item, stream = s, forum = f, profiles = profiles)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)
    }

    private fun requestAuthors(c: CommunityClient, stream: StreamTimeline, forum: ForumView) {
        // A notice is signed by the relay; the people it names are actor and target.
        val authors = stream.messages.flatMap { it.notice?.pubkeys ?: listOf(it.author) } +
            stream.threads.values.flatMap { it.participants } + forum.posts.map { it.author }
        c.requestProfiles(authors.distinct())
    }

    fun send(text: String) {
        val c = client ?: return
        local.value = local.value.copy(sending = true, sendError = null)
        viewModelScope.launch {
            val error = publishError { if (isForum) c.createForumPost(channelId, text) else c.sendMessage(channelId, text) }
            local.value = local.value.copy(sending = false, sendError = error)
        }
    }

    fun join() {
        val c = client ?: return
        viewModelScope.launch {
            val error = publishError { c.joinRoom(channelId) }
            if (error != null) local.value = local.value.copy(sendError = error)
        }
    }
}

/** A thread: head, replies, and the reply composer. */
data class CommunityThreadState(
    val thread: ThreadView = ThreadView(),
    val isForum: Boolean = false,
    val room: RoomListItem? = null,
    val profiles: Map<String, CommunityProfile> = emptyMap(),
    val me: String = "",
    val sending: Boolean = false,
    val sendError: String? = null,
)

@HiltViewModel
class CommunityThreadViewModel @Inject constructor(
    holder: CommunityClientHolder,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val channelId: String = checkNotNull(savedState["channelId"])
    private val rootId: String = checkNotNull(savedState["rootId"])
    private val isForum: Boolean = savedState.get<String>("type") == ChannelType.FORUM.wire
    private val client: CommunityClient? = holder.client()
    private val local = MutableStateFlow(CommunityThreadState(isForum = isForum, me = client?.me.orEmpty()))

    val state: StateFlow<CommunityThreadState> = run {
        val c = client ?: return@run local.asStateFlow()
        val room = c.rooms.map { s -> s.rooms.firstOrNull { it.channel.id == channelId } }.distinctUntilChanged()
        combine(
            c.thread(channelId, rootId).onEach { t -> c.requestProfiles(listOfNotNull(t.root?.author) + t.replies.map { it.author }) },
            room,
            c.profiles,
            local,
        ) { thread, item, profiles, l -> l.copy(thread = thread, room = item, profiles = profiles) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)
    }

    fun reply(text: String) {
        val c = client ?: return
        local.value = local.value.copy(sending = true, sendError = null)
        viewModelScope.launch {
            val error = publishError {
                if (isForum) c.commentOnForumPost(channelId, rootId, text)
                else c.sendMessage(channelId, text, ThreadRef(rootId, rootId))
            }
            local.value = local.value.copy(sending = false, sendError = error)
        }
    }
}

/** Null when the relay accepted the event, else why not (relay message or signer refusal). */
private suspend fun publishError(block: suspend () -> PublishResult): String? =
    try {
        block().let { if (it.accepted) null else it.message.ifBlank { "refused" } }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
