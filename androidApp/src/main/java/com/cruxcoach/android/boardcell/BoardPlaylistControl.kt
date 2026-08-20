package com.cruxcoach.android.boardcell

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lifecycle and membership commands for the one joinable playlist of a
 * BoardCell.
 *
 * Deliberately separate from the GATT-encoded [com.cruxcoach.android.ble.SessionCommand]
 * payload that rides in [BoardCellWireMessage.SessionCommand]: those are queue
 * mutations expressed in the legacy binary protocol, while these are the
 * product decisions (who hosts, who is a member, what happens when two people
 * start a playlist at once) that must be typed and canonical.
 *
 * Every command carries [basePlaylistRevision] so the controller can apply the
 * existing staleness rules, and [commandId] so replay after a reconnect or a
 * process restart is idempotent through the same durable ack window as every
 * other BoardCell command.
 */
@Serializable
sealed interface BoardPlaylistControl {
    val commandId: String
    val basePlaylistRevision: Long

    /**
     * Start a joinable playlist, or ask the current playlist host for room.
     *
     * One command for both because the sender's view of "is a playlist
     * running" is always a replica and may be stale; only the controller's
     * canonical state decides whether this starts directly or becomes a
     * proposal.
     */
    @Serializable
    @SerialName("start")
    data class Start(
        override val commandId: String,
        override val basePlaylistRevision: Long,
        val requestId: String,
        val sessionId: Int,
        val items: List<Pair<String, Int>>,
        val restAfterSeconds: List<Int> = emptyList(),
    ) : BoardPlaylistControl

    /** The playlist host answers an open [Start] proposal. */
    @Serializable
    @SerialName("decide")
    data class Decide(
        override val commandId: String,
        override val basePlaylistRevision: Long,
        val requestId: String,
        val decision: BoardPlaylistProposalDecision,
    ) : BoardPlaylistControl

    /** An authenticated mesh member joins the running playlist. No host approval. */
    @Serializable
    @SerialName("join")
    data class Join(
        override val commandId: String,
        override val basePlaylistRevision: Long,
    ) : BoardPlaylistControl

    /**
     * Leave the playlist. A departing host may name [successorId]; without a
     * valid choice the longest-active remaining member takes over.
     */
    @Serializable
    @SerialName("leave")
    data class Leave(
        override val commandId: String,
        override val basePlaylistRevision: Long,
        val successorId: String? = null,
    ) : BoardPlaylistControl

    /** End the playlist for everyone. Only legal with exactly one member left. */
    @Serializable
    @SerialName("end")
    data class End(
        override val commandId: String,
        override val basePlaylistRevision: Long,
    ) : BoardPlaylistControl

    /** Re-attempt the physical projection of the canonical current entry. */
    @Serializable
    @SerialName("retry_projection")
    data class RetryProjection(
        override val commandId: String,
        override val basePlaylistRevision: Long,
    ) : BoardPlaylistControl

    /** Change the planned rest that follows one entry. */
    @Serializable
    @SerialName("set_rest")
    data class SetRest(
        override val commandId: String,
        override val basePlaylistRevision: Long,
        val index: Int,
        val seconds: Int,
    ) : BoardPlaylistControl

    /** A planned rest block has begun and everyone should show it. */
    @Serializable
    @SerialName("rest_started")
    data class RestStarted(
        override val commandId: String,
        override val basePlaylistRevision: Long,
        val seconds: Int,
        val nextIndex: Int,
    ) : BoardPlaylistControl

    /** The rest ran out or somebody skipped it. */
    @Serializable
    @SerialName("rest_ended")
    data class RestEnded(
        override val commandId: String,
        override val basePlaylistRevision: Long,
    ) : BoardPlaylistControl

    /** The controller records that the current entry is (not) on the wall. */
    @Serializable
    @SerialName("projection_pending")
    data class ProjectionPending(
        override val commandId: String,
        override val basePlaylistRevision: Long,
        val pending: BoardPlaylistPendingProjection?,
    ) : BoardPlaylistControl
}

/**
 * The product rules for the joinable playlist, as one pure function.
 *
 * Keeping this free of transport, coroutines and Android lets the controller,
 * the local fast path and the tests run byte-identical logic, and makes the
 * rules that matter (who may end a playlist, who inherits a lost host)
 * reviewable in one place.
 */
/**
 * Why a command is allowed to touch the playlist at all.
 *
 * **Trust boundary.** [MEMBER] is the only authority that can arrive over
 * FIPS: a peer is authenticated as a cell member by the transport, and
 * the canonical playlist mirrors that membership. It is never inferred from
 * a claim in a packet, so an unauthenticated peer still cannot edit it.
 *
 * [GATEWAY_PROXY] is how an API-28 device takes part at all. It has no BLE
 * L2CAP CoC and therefore no FIPS identity, so it joins over GATT to an
 * API-29+ gateway, which authenticates it and gates it behind a completed
 * JOIN before carrying any of its verbs.
 *
 * This authority is reached in exactly two ways, and never by a peer simply
 * asserting it on an ordinary command:
 *
 *  - the gateway is itself the technical controller and commits the leaf's
 *    verb locally; or
 *  - the gateway sends [BoardCellWireMessage.LeafSessionCommand] or
 *    [BoardCellWireMessage.LeafRetryProjection] — message types that exist
 *    only for this, so a reader cannot mistake one for the sender's own
 *    command. [BoardCellMeshTransport.acceptLeafCommand] admits them solely
 *    from a sender the transport has authenticated *and* the canonical
 *    snapshot lists as a cell member, which is the same bar every other
 *    gateway assertion clears.
 *
 * The enum value itself is never serialized; what crosses the wire is the
 * dedicated message, and the controller decides what it is worth. What it is
 * worth is strictly less than membership: queue verbs and a projection retry,
 * and no start, end, join, leave, host succession or rest scheduling. A
 * stranger outside the cell therefore gains nothing by sending one, and a
 * gateway using it does **not** become a playlist member — so it can neither
 * inherit the host role nor lose its own local queue.
 */
enum class BoardPlaylistAuthority { MEMBER, GATEWAY_PROXY }

object BoardPlaylistPolicy {
    const val MAX_ITEMS = 512
    const val MAX_MEMBERS = 64
    const val MAX_REST_SECONDS = 3_600
    const val MAX_ID_LENGTH = 256
    const val PROPOSAL_TIMEOUT_MS = 30_000L

    sealed interface Outcome {
        /** Commit this playlist as the next canonical state. */
        data class Commit(val playlist: BoardPlaylistState) : Outcome

        /**
         * Legal and already satisfied. The command is acknowledged as
         * committed so a retry never reads as a failure, but nothing about
         * the queue or the index moves a second time.
         */
        data object Accepted : Outcome

        data class Reject(val reason: String) : Outcome
    }

    /**
     * Normalizes a playlist into its canonical shape.
     *
     * Applied on every commit so bounds, the index/rest invariants and the
     * host-is-a-member rule hold no matter which device produced the state —
     * a peer cannot smuggle an inconsistent playlist past the reducer, and
     * every replica derives the same bytes for the state hash.
     */
    fun normalize(playlist: BoardPlaylistState): BoardPlaylistState {
        val items = playlist.items.take(MAX_ITEMS)
        if (items.isEmpty() && playlist.hostId == null) return BoardPlaylistState()
        val rests = List(items.size) {
            playlist.restAfterSeconds.getOrElse(it) { 0 }.coerceIn(0, MAX_REST_SECONDS)
        }
        val members = playlist.members.distinct().take(MAX_MEMBERS)
        val host = playlist.hostId?.takeIf { it.isNotBlank() && it in members }
            ?: members.firstOrNull()
        if (host == null) return BoardPlaylistState()
        val index = if (items.isEmpty()) -1 else playlist.currentIndex.coerceIn(0, items.lastIndex)
        // The window must really be the duration it claims. Checking only the
        // far end let a "two minute" pause end in 2099, which every replica
        // would then have hashed and honoured, and which a process restart
        // would have started again at full length every time.
        val rest = playlist.activeRest
            ?.takeIf {
                it.totalSeconds in 1..MAX_REST_SECONDS && items.isNotEmpty() &&
                    BoardPlaylistInstant.isWindow(it.startedAtEpochMs, it.endsAtEpochMs,
                        it.totalSeconds * 1_000L)
            }
            ?.let { active -> active.copy(nextIndex = active.nextIndex.coerceIn(0, items.lastIndex)) }
        // The pending-send state describes the entry the wall is supposed to
        // be showing. Keeping one that named some other queued entry let a
        // stale "send pending" survive a next/remove and misreport a climb
        // that had since been projected perfectly well.
        val pending = playlist.pendingProjection?.takeIf { candidate ->
            val currentItem = items.getOrNull(index)
            currentItem != null && currentItem.first == candidate.climbUuid &&
                currentItem.second == candidate.angle
        }
        val proposal = playlist.proposal?.takeIf {
            it.requestId.isNotBlank() && it.requesterId.isNotBlank() && it.items.isNotEmpty() &&
                BoardPlaylistInstant.isWindow(it.requestedAtEpochMs, it.expiresAtEpochMs,
                    PROPOSAL_TIMEOUT_MS)
        }?.let { proposal ->
            val proposedItems = proposal.items.take(MAX_ITEMS)
            proposal.copy(
                items = proposedItems,
                restAfterSeconds = List(proposedItems.size) {
                    proposal.restAfterSeconds.getOrElse(it) { 0 }.coerceIn(0, MAX_REST_SECONDS)
                },
            )
        }
        return BoardPlaylistState(
            sessionId = playlist.sessionId,
            currentIndex = index,
            items = items,
            restAfterSeconds = rests,
            hostId = host,
            members = members,
            activeRest = rest,
            pendingProjection = pending,
            proposal = proposal,
        )
    }

    /**
     * Removes a node from the playlist because it left or was evicted from the
     * BoardCell itself.
     *
     * Losing the playlist host does not stop the playlist while other members
     * remain: the longest-active member — index 0 of the join-ordered member
     * list — inherits it, deterministically and identically on every replica.
     * The playlist only ends when its last member is gone.
     */
    fun withoutMember(playlist: BoardPlaylistState, memberId: String): BoardPlaylistState {
        if (!playlist.isJoinable || memberId !in playlist.members) return playlist
        val remaining = playlist.members - memberId
        if (remaining.isEmpty()) return BoardPlaylistState()
        val host = if (playlist.hostId == memberId) remaining.first() else playlist.hostId
        return normalize(playlist.copy(
            hostId = host,
            members = remaining,
            // A pending question the departed host can no longer answer must
            // not survive as an unanswerable dialog on the new host.
            proposal = playlist.proposal?.takeIf { playlist.hostId != memberId },
        ))
    }

    /**
     * Whether a purely local playlist must ask before lighting the wall over
     * the joinable playlist's current climb.
     *
     * Consent is needed exactly once per playlist, and only when a local send
     * would actually change what the group sees. A playlist member sending is
     * simply that playlist running, and re-sending the climb already on the
     * wall changes nothing anybody can see.
     */
    fun requiresOverwriteConsent(
        playlist: BoardPlaylistState,
        localNodeId: String,
        climbUuid: String,
        angle: Int,
        confirmedSessionId: Int?,
    ): Boolean {
        if (!playlist.isJoinable) return false
        if (localNodeId in playlist.members) return false
        val current = playlist.currentItem() ?: return false
        if (current.first == climbUuid && current.second == angle) return false
        return confirmedSessionId != playlist.sessionId
    }

    /**
     * Whether [senderId] may edit the queue (add/remove/move/current/next/prev).
     *
     * The one place the "mesh membership only makes the playlist visible"
     * rule is decided, so no transport can route around it.
     */
    fun mayEditQueue(
        playlist: BoardPlaylistState,
        senderId: String,
        authority: BoardPlaylistAuthority,
    ): Boolean {
        if (senderId.isBlank() || senderId.length > MAX_ID_LENGTH) return false
        if (!playlist.isJoinable) return true
        return when (authority) {
            BoardPlaylistAuthority.MEMBER -> senderId in playlist.members
            // The leaf itself completed a GATT join; its gateway is only
            // carrying the verb. Queue edits are exactly what an API-28 device
            // is allowed to do.
            BoardPlaylistAuthority.GATEWAY_PROXY -> true
        }
    }

    fun apply(
        current: BoardPlaylistState,
        senderId: String,
        control: BoardPlaylistControl,
        nowEpochMs: Long,
        authority: BoardPlaylistAuthority = BoardPlaylistAuthority.MEMBER,
        cellMembers: Set<String> = setOf(senderId),
    ): Outcome {
        if (senderId.isBlank() || senderId.length > MAX_ID_LENGTH)
            return Outcome.Reject("invalid sender")
        // A GATT leaf may steer the queue it can see; it may not decide the
        // playlist's existence, its host or its membership. Those are FIPS
        // identities, and an API-28 device does not have one.
        if (authority == BoardPlaylistAuthority.GATEWAY_PROXY &&
            control !is BoardPlaylistControl.RetryProjection) {
            return Outcome.Reject("a GATT leaf may only edit the queue and retry the send")
        }
        return when (control) {
            is BoardPlaylistControl.Start -> start(current, senderId, control, cellMembers)
            is BoardPlaylistControl.Decide -> decide(current, senderId, control, nowEpochMs)
            is BoardPlaylistControl.Join -> join(current, senderId)
            is BoardPlaylistControl.Leave -> leave(current, senderId, control)
            is BoardPlaylistControl.End -> end(current, senderId)
            is BoardPlaylistControl.RetryProjection ->
                if (!current.isJoinable) Outcome.Reject("no joinable playlist")
                else if (authority == BoardPlaylistAuthority.MEMBER && senderId !in current.members)
                    Outcome.Reject("not a playlist member")
                // Retry never moves the queue or the index; only the physical
                // write is attempted again, so replaying it is harmless.
                else Outcome.Accepted
            is BoardPlaylistControl.SetRest -> setRest(current, senderId, control)
            is BoardPlaylistControl.RestStarted -> restStarted(current, senderId, control, nowEpochMs)
            is BoardPlaylistControl.RestEnded -> restEnded(current, senderId)
            is BoardPlaylistControl.ProjectionPending -> projectionPending(current, control)
        }
    }

    private fun start(
        current: BoardPlaylistState,
        senderId: String,
        control: BoardPlaylistControl.Start,
        cellMembers: Set<String>,
    ): Outcome {
        val items = control.items.take(MAX_ITEMS)
        if (items.isEmpty()) return Outcome.Reject("playlist is empty")
        if (control.requestId.length !in 8..MAX_ID_LENGTH) return Outcome.Reject("invalid request id")
        if (!current.isJoinable) {
            // Nothing is running: this is a plain start, and the initiator is
            // the playlist host and its first member. No technical controller
            // is consulted and none is visible in the result.
            return Outcome.Commit(normalize(BoardPlaylistState(
                sessionId = control.sessionId,
                currentIndex = 0,
                items = items,
                restAfterSeconds = control.restAfterSeconds,
                hostId = senderId,
                // A Board playlist belongs to the BoardCell, not to a second
                // hidden membership layer. Everyone already admitted to the
                // board follows it and has the same editing rights.
                members = listOf(senderId) + cellMembers.filter { it != senderId }.sorted(),
            )))
        }
        // Starting while the board already has a playlist means adding to
        // that playlist. Asking a "host" to approve this contradicted the
        // equal-rights model and forced an unnecessary modal round-trip.
        return Outcome.Commit(normalize(current.copy(
            items = current.items + items,
            restAfterSeconds = current.restAfterSeconds + control.restAfterSeconds,
            currentIndex = if (current.currentIndex < 0) 0 else current.currentIndex,
            members = (current.members + cellMembers).distinct(),
            proposal = null,
        )))
    }

    private fun decide(
        current: BoardPlaylistState,
        senderId: String,
        control: BoardPlaylistControl.Decide,
        nowEpochMs: Long,
    ): Outcome {
        val proposal = current.proposal
            ?: return Outcome.Reject("no open playlist request")
        if (proposal.requestId != control.requestId) return Outcome.Reject("stale playlist request")
        if (senderId !in current.members) return Outcome.Reject("only board members decide")
        // The canonical deadline is the truth. An answer that arrives after it
        // resolves the request the way the timeout already promised, so the
        // requester is never told two different things.
        if (proposal.hasExpired(nowEpochMs))
            return Outcome.Commit(resolve(current, proposal, BoardPlaylistProposalDecision.REJECT))
        return Outcome.Commit(resolve(current, proposal, control.decision))
    }

    /** Shared by the host's answer and by the controller's timeout expiry. */
    fun resolve(
        current: BoardPlaylistState,
        proposal: BoardPlaylistProposal,
        decision: BoardPlaylistProposalDecision,
    ): BoardPlaylistState = when (decision) {
        BoardPlaylistProposalDecision.REJECT -> normalize(current.copy(proposal = null))
        BoardPlaylistProposalDecision.REPLACE -> normalize(current.copy(
            sessionId = proposal.sessionId,
            currentIndex = 0,
            items = proposal.items,
            restAfterSeconds = proposal.restAfterSeconds,
            members = (current.members + proposal.requesterId).distinct(),
            activeRest = null,
            pendingProjection = null,
            proposal = null,
        ))
        BoardPlaylistProposalDecision.APPEND -> normalize(current.copy(
            items = current.items + proposal.items,
            restAfterSeconds = current.restAfterSeconds + proposal.restAfterSeconds,
            currentIndex = if (current.currentIndex < 0) 0 else current.currentIndex,
            members = (current.members + proposal.requesterId).distinct(),
            proposal = null,
        ))
    }

    private fun join(current: BoardPlaylistState, senderId: String): Outcome {
        if (!current.isJoinable) return Outcome.Reject("no joinable playlist")
        if (senderId in current.members) return Outcome.Accepted
        if (current.members.size >= MAX_MEMBERS) return Outcome.Reject("playlist is full")
        return Outcome.Commit(normalize(current.copy(members = current.members + senderId)))
    }

    private fun leave(
        current: BoardPlaylistState,
        senderId: String,
        @Suppress("UNUSED_PARAMETER") control: BoardPlaylistControl.Leave,
    ): Outcome {
        if (!current.isJoinable) return Outcome.Reject("no joinable playlist")
        if (senderId !in current.members) return Outcome.Accepted
        // A Board playlist has no separate leave action. It follows the
        // BoardCell membership and [withoutMember] performs succession when
        // the person actually leaves the board group.
        return Outcome.Accepted
    }

    private fun end(current: BoardPlaylistState, senderId: String): Outcome {
        if (!current.isJoinable) return Outcome.Accepted
        if (senderId !in current.members) return Outcome.Reject("not a playlist member")
        // All board members have equal playlist rights, including ending the
        // shared queue. The confirmation belongs in UI; there is no hidden
        // host privilege in the canonical policy.
        return Outcome.Commit(BoardPlaylistState())
    }

    private fun setRest(
        current: BoardPlaylistState,
        senderId: String,
        control: BoardPlaylistControl.SetRest,
    ): Outcome {
        if (!current.isJoinable) return Outcome.Reject("no joinable playlist")
        if (senderId !in current.members) return Outcome.Reject("not a playlist member")
        if (control.index !in current.items.indices) return Outcome.Reject("rest index is no longer valid")
        val seconds = control.seconds.coerceIn(0, MAX_REST_SECONDS)
        if (current.restAt(control.index) == seconds) return Outcome.Accepted
        val rests = MutableList(current.items.size) { current.restAt(it) }
        rests[control.index] = seconds
        return Outcome.Commit(normalize(current.copy(restAfterSeconds = rests)))
    }

    private fun restStarted(
        current: BoardPlaylistState,
        senderId: String,
        control: BoardPlaylistControl.RestStarted,
        nowEpochMs: Long,
    ): Outcome {
        if (!current.isJoinable) return Outcome.Reject("no joinable playlist")
        if (senderId !in current.members) return Outcome.Reject("not a playlist member")
        val seconds = control.seconds.coerceIn(1, MAX_REST_SECONDS)
        if (control.nextIndex !in current.items.indices) return Outcome.Reject("rest target is not in the playlist")
        return Outcome.Commit(normalize(current.copy(
            activeRest = armRest(current.activeRest, seconds, control.nextIndex, nowEpochMs))))
    }

    /**
     * Stamps a rest with its canonical end.
     *
     * Only the one device that serializes the commit reads a clock, so every
     * replica derives the same bytes and the same state hash from it.
     */
    internal fun armRest(
        previous: BoardPlaylistRest?,
        seconds: Int,
        nextIndex: Int,
        nowEpochMs: Long,
    ): BoardPlaylistRest = BoardPlaylistRest(
        totalSeconds = seconds,
        generation = (previous?.generation ?: 0L) + 1,
        nextIndex = nextIndex,
        // Both ends, so the duration is checkable. Not clamped: a clamped end
        // would silently break the pair invariant and the rest would be
        // dropped by normalization anyway — better that a device with a wildly
        // wrong clock produces no rest than a subtly wrong one.
        startedAtEpochMs = nowEpochMs,
        endsAtEpochMs = nowEpochMs + seconds * 1_000L,
    )

    private fun restEnded(current: BoardPlaylistState, senderId: String): Outcome {
        if (!current.isJoinable) return Outcome.Reject("no joinable playlist")
        if (senderId !in current.members) return Outcome.Reject("not a playlist member")
        if (current.activeRest == null) return Outcome.Accepted
        return Outcome.Commit(normalize(current.copy(activeRest = null)))
    }

    private fun projectionPending(
        current: BoardPlaylistState,
        control: BoardPlaylistControl.ProjectionPending,
    ): Outcome {
        // Only the controller ever emits this; membership is irrelevant
        // because it reports a physical fact rather than a user decision.
        if (!current.isJoinable) return Outcome.Reject("no joinable playlist")
        if (current.pendingProjection == control.pending) return Outcome.Accepted
        return Outcome.Commit(normalize(current.copy(pendingProjection = control.pending)))
    }
}

/**
 * Index-based queue edits applied straight to canonical playlist state.
 *
 * These used to run through the controller's own [com.cruxcoach.android.data.SessionQueueManager],
 * which made the technical controller's private queue part of the shared
 * playlist's data path: a controller that was not even a playlist member
 * mutated its own UI on every remote command, and a controller running its own
 * local-only playlist had it overwritten. The canonical state is now edited
 * directly and the local queue is a projection of the result.
 */
object BoardPlaylistOps {
    fun add(current: BoardPlaylistState, climbUuid: String, angle: Int): BoardPlaylistState? {
        if (climbUuid.isBlank() || current.items.size >= BoardPlaylistPolicy.MAX_ITEMS) return null
        return BoardPlaylistPolicy.normalize(current.copy(
            items = current.items + (climbUuid to angle),
            restAfterSeconds = current.restAfterSeconds + 0,
            currentIndex = if (current.currentIndex < 0) 0 else current.currentIndex,
        ))
    }

    /**
     * Removing the entry a running rest is waiting on ends the rest.
     *
     * Otherwise the group would keep counting down towards a climb that is no
     * longer in the playlist, and the index the rest names would silently come
     * to mean a different problem.
     */
    fun remove(current: BoardPlaylistState, index: Int): BoardPlaylistState? {
        if (index !in current.items.indices) return null
        val items = current.items.toMutableList().apply { removeAt(index) }
        val rests = MutableList(current.items.size) { current.restAt(it) }.apply { removeAt(index) }
        val nextIndex = when {
            items.isEmpty() -> -1
            index < current.currentIndex -> current.currentIndex - 1
            index == current.currentIndex -> current.currentIndex.coerceAtMost(items.lastIndex)
            else -> current.currentIndex
        }
        val keepsRestTarget = index != current.currentIndex
        return BoardPlaylistPolicy.normalize(current.copy(
            items = items,
            restAfterSeconds = rests,
            currentIndex = nextIndex,
            activeRest = current.activeRest
                ?.takeIf { keepsRestTarget && items.isNotEmpty() }
                ?.copy(nextIndex = nextIndex),
        ))
    }

    /** Jumping around the queue is a user override and cancels a running rest. */
    fun setCurrent(current: BoardPlaylistState, index: Int): BoardPlaylistState? {
        if (index !in current.items.indices) return null
        return BoardPlaylistPolicy.normalize(current.copy(currentIndex = index, activeRest = null))
    }

    /**
     * Advancing arms the planned rest of the entry being left, in the same
     * canonical step that moves the index.
     *
     * One event rather than an advance followed by a separate rest command:
     * the pair could otherwise be split by a reconnect or a controller
     * handover, and a peer would show the next climb ready to go while the
     * rest of the group was resting in front of the wall.
     */
    fun next(current: BoardPlaylistState, nowEpochMs: Long): BoardPlaylistState? {
        if (current.currentIndex !in 0 until current.items.lastIndex) return null
        val restSeconds = current.restAt(current.currentIndex)
        val nextIndex = current.currentIndex + 1
        return BoardPlaylistPolicy.normalize(current.copy(
            currentIndex = nextIndex,
            activeRest = if (restSeconds > 0)
                BoardPlaylistPolicy.armRest(current.activeRest, restSeconds, nextIndex, nowEpochMs)
            else null,
        ))
    }

    fun previous(current: BoardPlaylistState): BoardPlaylistState? =
        if (current.currentIndex > 0) BoardPlaylistPolicy.normalize(
            current.copy(currentIndex = current.currentIndex - 1, activeRest = null))
        else null

    fun move(current: BoardPlaylistState, from: Int, to: Int): BoardPlaylistState? {
        if (from !in current.items.indices || to !in current.items.indices || from == to) return null
        val items = current.items.toMutableList().apply { add(to, removeAt(from)) }
        val rests = MutableList(current.items.size) { current.restAt(it) }
            .apply { add(to, removeAt(from)) }
        val nextIndex = when (current.currentIndex) {
            from -> to
            in minOf(from, to)..maxOf(from, to) ->
                if (from < to) current.currentIndex - 1 else current.currentIndex + 1
            else -> current.currentIndex
        }
        // The current entry keeps its identity across a move, so a running
        // rest keeps counting — it just follows the index it now sits at.
        return BoardPlaylistPolicy.normalize(current.copy(
            items = items,
            restAfterSeconds = rests,
            currentIndex = nextIndex,
            activeRest = current.activeRest?.copy(nextIndex = nextIndex),
        ))
    }
}
