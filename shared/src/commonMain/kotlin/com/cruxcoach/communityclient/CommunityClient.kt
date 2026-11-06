package com.cruxcoach.communityclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The room list as the screen shows it. */
data class RoomsState(
    val rooms: List<RoomListItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** Unix seconds from start to the first loaded list, for the report. */
    val firstListAfterSeconds: Double? = null,
)

/**
 * The community client: one session with one community, the room list and the
 * views of single rooms. Nothing connects before [start].
 */
class CommunityClient(
    val endpoint: CommunityEndpoint,
    socketFactory: CommunitySocketFactory,
    http: CommunityHttp,
    private val signer: CommunitySigner,
    verifier: EventVerifier,
    private val clock: UnixClock,
    private val scope: CoroutineScope,
    /** Monotonic milliseconds, for timing the first room list. */
    private val elapsedMillis: () -> Long = { clock.nowSeconds() * 1000 },
    /** Steps to the first room list, with milliseconds since [start] (diagnostics only). */
    private val onTiming: (step: String, millis: Long) -> Unit = { _, _ -> },
) {
    val session = CommunitySession(endpoint, socketFactory, signer, verifier, clock, scope)
    val api = CommunityApi(endpoint, http, signer, verifier, clock)

    val me: String get() = signer.pubkey

    private val _rooms = MutableStateFlow(RoomsState())
    val rooms: StateFlow<RoomsState> = _rooms.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, CommunityProfile>>(emptyMap())

    /** Profiles fetched so far, by pubkey. Ask for more with [requestProfiles]. */
    val profiles: StateFlow<Map<String, CommunityProfile>> = _profiles.asStateFlow()

    private var watcher: Job? = null
    private var startedAt: Long = 0
    private val roomsLock = Mutex()
    private val profilesLock = Mutex()
    private val requestedProfiles = HashSet<String>()

    fun start() {
        if (watcher != null) return
        startedAt = elapsedMillis()
        watcher = scope.launch {
            // The relay key lets the session drop relay-only kinds signed by anyone else.
            runCatching { api.relayInfo() }.getOrNull()?.relayPubkey?.let { session.relayPubkey = it }
            timing("relay_info")
            session.start()
            launch {
                session.state.collect { state ->
                    if (state == SessionState.Ready) {
                        timing("signed_in")
                        refreshRooms()
                    }
                }
            }
            launch {
                session.state.first { it == SessionState.Ready }
                session.subscribe(listOf(CommunityFilters.myMembershipNotices(me, clock.nowSeconds())))
                    .collect { item -> if (item is SubscriptionItem.Event) refreshRooms() }
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        session.stop()
    }

    /** Loads my memberships, their metadata and the open-room directory. */
    suspend fun refreshRooms() = roomsLock.withLock {
        _rooms.value = _rooms.value.copy(loading = true, error = null)
        try {
            // The open-room directory does not depend on my memberships: fetch both at once.
            val (memberLists, metas) = coroutineScope {
                val directory = async {
                    session.query(listOf(CommunityFilters.channelDirectory())).also { timing("directory") }
                }
                val memberLists = session.query(listOf(CommunityFilters.myMemberships(me)))
                    .mapNotNull { ChannelMembers.fromEvent(it) }
                    .groupBy { it.channelId }
                    .mapValues { (_, lists) -> lists.maxBy { it.revisionAt } }
                timing("memberships")
                val mine = memberLists.values.filter { it.contains(me) }.map { it.channelId }
                val myMetas = if (mine.isEmpty()) emptyList() else session.query(listOf(CommunityFilters.channelMetadata(mine)))
                timing("my_rooms")
                memberLists to (myMetas + directory.await()).mapNotNull { CommunityChannel.fromEvent(it) }
            }
            val mine = memberLists.values.filter { it.contains(me) }.map { it.channelId }.toSet()
            val roles = memberLists.filterKeys { it in mine }.mapValues { (_, list) -> list.roleOf(me) }
            val list = buildRoomList(metas, mine, emptyMap(), roles)
            val first = _rooms.value.firstListAfterSeconds ?: ((elapsedMillis() - startedAt) / 1000.0)
            _rooms.value = RoomsState(list, loading = false, error = null, firstListAfterSeconds = first)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            _rooms.value = _rooms.value.copy(loading = false, error = e.message ?: "loading rooms failed")
        }
    }

    /** Reports a step until the first room list is there. */
    private fun timing(step: String) {
        if (_rooms.value.firstListAfterSeconds == null) onTiming(step, elapsedMillis() - startedAt)
    }

    /** Fetches the kind:0 profiles of [pubkeys] not asked for yet. */
    fun requestProfiles(pubkeys: Collection<String>) {
        scope.launch {
            val missing = profilesLock.withLock {
                pubkeys.filter { it.isHex(64) && requestedProfiles.add(it) }
            }
            if (missing.isEmpty()) return@launch
            session.state.first { it == SessionState.Ready }
            missing.chunked(100).forEach { chunk ->
                val found = runCatching { session.query(listOf(CommunityFilters.profiles(chunk))) }
                    .getOrElse {
                        profilesLock.withLock { requestedProfiles.removeAll(chunk.toSet()) }
                        return@forEach
                    }
                    .mapNotNull { CommunityProfile.fromEvent(it) }
                profilesLock.withLock {
                    val next = _profiles.value.toMutableMap()
                    found.forEach { p -> if ((next[p.pubkey]?.updatedAt ?: -1) < p.updatedAt) next[p.pubkey] = p }
                    _profiles.value = next
                }
            }
        }
    }

    /** Joins an open room (kind:9021). */
    suspend fun joinRoom(channelId: String): PublishResult {
        val result = session.publish(CommunityEventTemplates.joinRequest(channelId, clock.nowSeconds()))
        if (result.accepted) refreshRooms()
        return result
    }

    /** Sends a message, or a thread reply when [replyTo] is set. */
    suspend fun sendMessage(channelId: String, text: String, replyTo: ThreadRef? = null): PublishResult =
        session.publish(CommunityEventTemplates.streamMessage(channelId, text, clock.nowSeconds(), replyTo))

    suspend fun createForumPost(channelId: String, text: String): PublishResult =
        session.publish(CommunityEventTemplates.forumPost(channelId, text, clock.nowSeconds()))

    suspend fun commentOnForumPost(channelId: String, postId: String, text: String): PublishResult =
        session.publish(CommunityEventTemplates.forumComment(channelId, postId, text, clock.nowSeconds()))

    /**
     * A stream room: the newest top-level rows from the NIP-CW window, then live
     * events. Falls back to a plain REQ if the window query fails.
     */
    fun streamTimeline(channelId: String, pageSize: Int = 50): Flow<StreamTimeline> = callbackFlow {
        val buffer = TimelineBuffer(channelId, topLevelOnly = true)
        var state = StreamTimeline()
        fun emit(update: StreamTimeline.() -> StreamTimeline = { this }) {
            state = state.update().copy(messages = buffer.messages(), threads = buffer.threadSummaries())
            trySend(state)
        }
        session.state.first { it == SessionState.Ready }
        // Live first, so nothing falls between the stored page and the live stream.
        val live = launch {
            session.subscribe(listOf(CommunityFilters.streamLive(channelId, clock.nowSeconds() - 2))).collect { item ->
                if (item is SubscriptionItem.Event && buffer.add(listOf(item.event))) emit()
                if (item is SubscriptionItem.Closed) emit { copy(error = item.reason) }
            }
        }
        try {
            val page = api.channelWindow(channelId, null, pageSize, session.relayPubkey)
            buffer.add(page.rows + page.aux)
            buffer.addSummaries(page.summaries)
            emit { copy(loading = false, hasMore = page.bounds.hasMore, error = null) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            runCatching { session.query(listOf(CommunityFilters.streamHistory(channelId))) }
                .onSuccess { buffer.add(it); emit { copy(loading = false, hasMore = false) } }
                .onFailure { emit { copy(loading = false, error = it.message ?: "loading messages failed") } }
        }
        awaitClose { live.cancel() }
    }.conflate()

    /**
     * A thread: its head and every reply, live. The head lookup runs next to the
     * reply subscription, so a slow lookup never holds the replies back.
     */
    fun thread(channelId: String, rootId: String): Flow<ThreadView> = flow {
        session.state.first { it == SessionState.Ready }
        val buffer = TimelineBuffer(channelId, topLevelOnly = false)
        var repliesLoaded = false
        var rootLoaded = false
        var error: String? = null
        val head = flow {
            val event = try {
                session.query(listOf(CommunityFilters.threadRoot(channelId, rootId)), ROOT_LOOKUP_TIMEOUT_MS).firstOrNull()
            } catch (e: CommunityException) {
                null
            }
            emit(ThreadInput.Head(event))
        }
        val replies = session.subscribe(listOf(CommunityFilters.thread(channelId, rootId))).map { ThreadInput.Item(it) }
        merge(head, replies).collect { input ->
            val changed = when (input) {
                is ThreadInput.Head -> { rootLoaded = true; input.event?.let { buffer.add(listOf(it)) }; true }
                is ThreadInput.Item -> when (val item = input.item) {
                    is SubscriptionItem.Event -> buffer.add(listOf(item.event))
                    SubscriptionItem.Eose -> { repliesLoaded = true; true }
                    is SubscriptionItem.Closed -> { repliesLoaded = true; error = item.reason; true }
                }
            }
            if (changed && repliesLoaded) {
                // Every row of the subscription tags the root, so all but the root are replies.
                val all = buffer.messages()
                emit(ThreadView(all.firstOrNull { it.id == rootId }, all.filter { it.id != rootId }, loading = !rootLoaded, error = error))
            }
        }
    }.conflate()

    /** A forum room: topics, newest first, and how many comments each has. */
    fun forum(channelId: String): Flow<ForumView> = callbackFlow {
        session.state.first { it == SessionState.Ready }
        val posts = LinkedHashMap<String, CommunityEvent>()
        val comments = HashMap<String, MutableSet<String>>()
        var loaded = false
        fun emit(error: String? = null) {
            val list = posts.values.mapNotNull { CommunityMessage.fromEvent(it) }.sortedByDescending { it.createdAt }
            trySend(ForumView(list, comments.mapValues { it.value.size }, loading = !loaded, error = error))
        }
        val postsJob = launch {
            session.subscribe(listOf(CommunityFilters.forumPosts(channelId))).collect { item ->
                when (item) {
                    is SubscriptionItem.Event -> if (posts.put(item.event.id, item.event) == null && loaded) emit()
                    SubscriptionItem.Eose -> { loaded = true; emit() }
                    is SubscriptionItem.Closed -> { loaded = true; emit(item.reason) }
                }
            }
        }
        val commentsJob = launch {
            val filter = RelayFilter(kinds = listOf(CommunityKinds.FORUM_COMMENT), tags = mapOf('h' to listOf(channelId)), limit = 500)
            session.subscribe(listOf(filter)).filter { it is SubscriptionItem.Event }.collect { item ->
                val event = (item as SubscriptionItem.Event).event
                val parent = ThreadRef.of(event)?.rootId ?: return@collect
                if (comments.getOrPut(parent) { HashSet() }.add(event.id) && loaded) emit()
            }
        }
        awaitClose { postsJob.cancel(); commentsJob.cancel() }
    }.conflate()
}

/** What the thread view merges: the head lookup's answer and the reply subscription. */
private sealed interface ThreadInput {
    data class Head(val event: CommunityEvent?) : ThreadInput
    data class Item(val item: SubscriptionItem) : ThreadInput
}

/** How long a thread waits for its head before showing the replies without it. */
private const val ROOT_LOOKUP_TIMEOUT_MS = 8_000L

/**
 * The join steps before the first connection: read the rules, accept them, claim
 * the invite. Kept apart from [CommunityClient] because a not-yet-member cannot
 * authenticate on the relay.
 */
class CommunityJoin(private val api: CommunityApi) {
    suspend fun rules(): JoinPolicy? = api.joinPolicy()

    /** Accepts [policy] (if any) and claims [invite]. */
    suspend fun join(invite: CommunityInvite, policy: JoinPolicy?, ageConfirmed: Boolean): ClaimResult {
        val receipt = policy?.let { api.acceptPolicy(invite.code, it, ageConfirmed) }
        return api.claimInvite(invite.code, receipt)
    }
}
