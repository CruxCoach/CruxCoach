package com.cruxcoach.android.boardcell

import com.cruxcoach.android.fips.FipsDebugLog
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed interface BoardCellWireMessage {
    @Serializable @SerialName("direct_claim") data class DirectClaim(val value: BoardCellClaim) : BoardCellWireMessage
    @Serializable @SerialName("snapshot") data class Snapshot(val value: BoardCellSnapshot) : BoardCellWireMessage
    @Serializable @SerialName("event") data class Event(val value: BoardCellEnvelope) : BoardCellWireMessage
    @Serializable @SerialName("snapshot_request") data class SnapshotRequest(val afterSequence: Long) : BoardCellWireMessage
    @Serializable @SerialName("anti_entropy") data class AntiEntropy(val sequence: Long, val stateHash: String) : BoardCellWireMessage
    @Serializable @SerialName("handover_ready") data class Ready(val value: HandoverReady) : BoardCellWireMessage
    @Serializable @SerialName("controller_request") data class ControllerRequest(
        val value: BoardCellControllerRequest,
    ) : BoardCellWireMessage
    @Serializable @SerialName("controller_decision") data class ControllerDecision(
        val value: BoardCellControllerDecision,
    ) : BoardCellWireMessage
    @Serializable @SerialName("member_join_request") data class MemberJoinRequest(
        val value: BoardCellJoinRequest,
    ) : BoardCellWireMessage
    @Serializable @SerialName("member_heartbeat") data class MemberHeartbeat(
        val tick: Long,
        val diagnostics: BoardCellPeerDiagnostics? = null,
    ) : BoardCellWireMessage
    @Serializable @SerialName("peer_diagnostics") data class PeerDiagnostics(
        val tick: Long,
        val value: BoardCellPeerDiagnostics,
    ) : BoardCellWireMessage
    @Serializable @SerialName("member_leave_request") data class MemberLeaveRequest(
        val value: BoardCellLeaveRequest,
    ) : BoardCellWireMessage
    @Serializable @SerialName("controller_recovery") data class ControllerRecovery(
        val value: BoardCellControllerRecovery,
    ) : BoardCellWireMessage
    @Serializable @SerialName("projection_request") data class ProjectionRequest(
        val value: BoardProjectionRequest,
    ) : BoardCellWireMessage
    @Serializable @SerialName("fork_notice") data class ForkNotice(val value: BoardCellForkNotice) : BoardCellWireMessage
    @Serializable @SerialName("session_command") data class SessionCommand(
        val commandId: String,
        val basePlaylistRevision: Long,
        val payload: ByteArray,
        val context: BoardPlaylistCommandContext? = null,
    ) : BoardCellWireMessage

    /**
     * A queue edit an authenticated cell member is carrying for an API-28 GATT
     * leaf of its own.
     *
     * Its own message type rather than a flag on [SessionCommand], because the
     * two mean genuinely different things and a reader should not be able to
     * mistake one for the other: this one asserts "I have a joined leaf", and
     * the controller answers it with strictly *less* than membership — queue
     * verbs and projection retry, never start, end, leave, host or rest
     * scheduling. See [BoardPlaylistAuthority] for the trust boundary.
     */
    @Serializable @SerialName("leaf_session_command") data class LeafSessionCommand(
        val commandId: String,
        val basePlaylistRevision: Long,
        val payload: ByteArray,
        val context: BoardPlaylistCommandContext? = null,
    ) : BoardCellWireMessage

    /** A projection retry an authenticated cell member carries for its leaf. */
    @Serializable @SerialName("leaf_retry_projection") data class LeafRetryProjection(
        val commandId: String,
        val basePlaylistRevision: Long,
    ) : BoardCellWireMessage
    @Serializable @SerialName("command_ack") data class CommandAck(val value: BoardCommandAck) : BoardCellWireMessage
    @Serializable @SerialName("playlist_control") data class PlaylistControl(
        val value: BoardPlaylistControl,
    ) : BoardCellWireMessage
}

/** V1 had no authenticated realm/sender/term binding and is intentionally rejected. */
@Serializable
data class BoardCellWireFrame(
    val version: Int = BoardCellWireCodec.VERSION,
    val messageId: String,
    val senderId: String,
    val realmId: String,
    val cellId: BoardCellId,
    val physicalBoardId: PhysicalBoardId,
    val epoch: Long,
    val controllerTerm: Long,
    val message: BoardCellWireMessage,
)

object BoardCellWireCodec {
    // V9 adds bounded operational peer diagnostics to liveness frames. These
    // values are deliberately outside canonical state and state hashing.
    // V8 makes the joinable playlist canonical: playlist host and playlist
    // members as their own identities, per-entry rest plan, active rest,
    // pending projection and the start/replace/append request flow. A V7 peer
    // would read a snapshot carrying that state as a playlist with no host and
    // no members and quietly grant everyone the host's rights, so it must fail
    // closed — `ignoreUnknownKeys = false` plus this check does exactly that.
    // V7 makes membership live: authenticated member heartbeats, explicit
    // leave requests and canonical MemberLeft events. Older peers fail closed.
    // V6 added semantic projection bases so heartbeat-only sequence advances
    // do not spuriously reject a participant's board command. V5 added
    // permissionless, member-sponsored multi-hop BoardCell admission.
    // Older peers must fail closed instead of interpreting the new authority flow.
    const val VERSION = 9
    private val json = Json { classDiscriminator = "type"; encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(frame: BoardCellWireFrame): ByteArray = json.encodeToString(frame).encodeToByteArray()
    fun decode(bytes: ByteArray): BoardCellWireFrame {
        require(bytes.isNotEmpty() && bytes.size <= BoardCellMeshTransport.MAX_WIRE_BYTES) { "wire bounds" }
        return json.decodeFromString<BoardCellWireFrame>(bytes.decodeToString()).also {
            require(it.version == VERSION) { "unsupported BoardCell wire version" }
            require(it.messageId.length in 8..128 && it.senderId.length in 1..256 && it.realmId.length in 1..256)
            require(it.cellId.value.length <= 128 && it.physicalBoardId.value.length <= 256)
            require(it.epoch >= 0 && it.controllerTerm > 0)
            when (val message = it.message) {
                is BoardCellWireMessage.Snapshot -> {
                    require(message.value.members.size <= 128 && message.value.playlist.items.size <= 512 &&
                        message.value.recentCommandIds.size <= 256 &&
                        message.value.membershipRevision >= 0)
                    requirePlaylistBounds(message.value.playlist)
                }
                is BoardCellWireMessage.Event -> when (val event = message.value.event) {
                    is BoardCellEvent.MemberLeft -> require(event.memberId.length in 1..256)
                    is BoardCellEvent.PlaylistReplaced -> requirePlaylistBounds(event.playlist)
                    else -> Unit
                }
                is BoardCellWireMessage.PlaylistControl -> {
                    val control = message.value
                    require(control.commandId.length in 8..128 && control.basePlaylistRevision >= 0)
                    when (control) {
                        is BoardPlaylistControl.Start -> {
                            require(control.requestId.length in 8..BoardPlaylistPolicy.MAX_ID_LENGTH)
                            require(control.items.size in 1..BoardPlaylistPolicy.MAX_ITEMS)
                            require(control.restAfterSeconds.size <= BoardPlaylistPolicy.MAX_ITEMS)
                            control.items.forEach { requireItemBounds(it) }
                            control.restAfterSeconds.forEach { requireRestBounds(it) }
                        }
                        is BoardPlaylistControl.Decide ->
                            require(control.requestId.length in 8..BoardPlaylistPolicy.MAX_ID_LENGTH)
                        is BoardPlaylistControl.Leave -> require(
                            control.successorId == null ||
                                control.successorId.length in 1..BoardPlaylistPolicy.MAX_ID_LENGTH)
                        is BoardPlaylistControl.SetRest -> {
                            require(control.index in 0 until BoardPlaylistPolicy.MAX_ITEMS)
                            requireRestBounds(control.seconds)
                        }
                        is BoardPlaylistControl.RestStarted -> {
                            require(control.nextIndex in 0 until BoardPlaylistPolicy.MAX_ITEMS)
                            require(control.seconds in 1..BoardPlaylistPolicy.MAX_REST_SECONDS)
                        }
                        is BoardPlaylistControl.ProjectionPending ->
                            control.pending?.let { requireItemBounds(it.climbUuid to it.angle) }
                        else -> Unit
                    }
                }
                is BoardCellWireMessage.SessionCommand -> require(
                    message.commandId.length in 8..128 && message.payload.size in 1..BoardCellMeshTransport.MAX_SESSION_COMMAND_BYTES)
                is BoardCellWireMessage.LeafSessionCommand -> require(
                    message.commandId.length in 8..128 && message.basePlaylistRevision >= 0 &&
                        message.payload.size in 1..BoardCellMeshTransport.MAX_SESSION_COMMAND_BYTES)
                is BoardCellWireMessage.LeafRetryProjection -> require(
                    message.commandId.length in 8..128 && message.basePlaylistRevision >= 0)
                is BoardCellWireMessage.CommandAck -> require(message.value.commandId.length in 8..128)
                is BoardCellWireMessage.ControllerRequest -> require(
                    message.value.requestId.length in 8..128 &&
                        message.value.requesterId.length in 1..256)
                is BoardCellWireMessage.ControllerDecision -> require(
                    message.value.requestId.length in 8..128)
                is BoardCellWireMessage.MemberJoinRequest -> require(
                    message.value.requestId.length in 8..128 &&
                        message.value.candidateId.length in 1..256 &&
                        message.value.sponsorId.length in 1..256 &&
                        message.value.candidateId != message.value.sponsorId)
                is BoardCellWireMessage.MemberHeartbeat -> {
                    require(message.tick >= 0)
                    message.diagnostics?.validate()
                }
                is BoardCellWireMessage.PeerDiagnostics -> {
                    require(message.tick >= 0)
                    message.value.validate()
                }
                is BoardCellWireMessage.MemberLeaveRequest -> require(
                    message.value.requestId.length in 8..128)
                is BoardCellWireMessage.ControllerRecovery -> require(
                    message.value.claimantId.length in 1..256 &&
                        message.value.connectionProof.length in 8..256)
                is BoardCellWireMessage.ProjectionRequest -> require(
                    message.value.commandId.length in 8..128)
                else -> Unit
            }
        }
    }

    private fun requireItemBounds(item: Pair<String, Int>) {
        require(item.first.length in 1..64 && item.second in 0..90)
    }

    private fun requireRestBounds(seconds: Int) {
        require(seconds in 0..BoardPlaylistPolicy.MAX_REST_SECONDS)
    }

    /**
     * A peer may only ever send a playlist that the local reducer would also
     * have produced. Checking the shape here keeps an oversized or
     * self-inconsistent playlist out of the durable store and out of the state
     * hash, instead of relying on every later reader to be defensive.
     */
    private fun requirePlaylistBounds(playlist: BoardPlaylistState) {
        require(playlist.items.size <= BoardPlaylistPolicy.MAX_ITEMS)
        require(playlist.members.size <= BoardPlaylistPolicy.MAX_MEMBERS)
        require(playlist.currentIndex >= -1 && playlist.currentIndex < playlist.items.size.coerceAtLeast(1))
        playlist.items.forEach { requireItemBounds(it) }
        playlist.restAfterSeconds.forEach { requireRestBounds(it) }
        playlist.members.forEach { require(it.length in 1..BoardPlaylistPolicy.MAX_ID_LENGTH) }
        playlist.hostId?.let { require(it.length in 1..BoardPlaylistPolicy.MAX_ID_LENGTH) }
        playlist.activeRest?.let {
            require(it.totalSeconds in 1..BoardPlaylistPolicy.MAX_REST_SECONDS)
            require(it.generation >= 0)
            // The start/end pair has to describe exactly the duration it
            // claims. Bounding only the far end still allowed a "two minute"
            // pause that ran until 2099, which every replica would then have
            // hashed and honoured. Checking the difference needs no clock, so
            // the verdict is the same on every device.
            require(BoardPlaylistInstant.isWindow(it.startedAtEpochMs, it.endsAtEpochMs,
                it.totalSeconds * 1_000L))
        }
        playlist.pendingProjection?.let { requireItemBounds(it.climbUuid to it.angle) }
        playlist.proposal?.let { proposal ->
            require(proposal.requestId.length in 8..BoardPlaylistPolicy.MAX_ID_LENGTH)
            require(proposal.requesterId.length in 1..BoardPlaylistPolicy.MAX_ID_LENGTH)
            require(BoardPlaylistInstant.isWindow(proposal.requestedAtEpochMs,
                proposal.expiresAtEpochMs, BoardPlaylistPolicy.PROPOSAL_TIMEOUT_MS))
            require(proposal.items.size in 1..BoardPlaylistPolicy.MAX_ITEMS)
            require(proposal.restAfterSeconds.size <= BoardPlaylistPolicy.MAX_ITEMS)
            proposal.items.forEach { requireItemBounds(it) }
            proposal.restAfterSeconds.forEach { requireRestBounds(it) }
        }
    }
}

/**
 * Small operational snapshot piggy-backed on existing mesh liveness.
 *
 * It contains no user identity, board serial or Bluetooth address. The current
 * climb identifier is already part of the playlist protocol and is hashed in
 * logcat. Its only purpose is to make the state that decided routing,
 * auto-disconnect and UI truth visible from the other phone during a test.
 * Every field is defaulted so V9 can still decode a diagnostic payload whose
 * sender omitted newer fields. V8 peers fail closed at the wire-version gate.
 */
@Serializable
data class BoardCellPeerDiagnostics(
    @SerialName("v") val schema: Int = 1,
    @SerialName("b") val appVersionCode: Int = 0,
    @SerialName("bt") val bluetoothEnabled: Boolean = false,
    @SerialName("rt") val meshRuntimeRunning: Boolean = false,
    @SerialName("mr") val meshRole: String = "none",
    @SerialName("mc") val meshMemberCount: Int = 0,
    @SerialName("ca") val controllerAvailable: Boolean = false,
    @SerialName("bc") val boardConnection: String = "DISCONNECTED",
    @SerialName("ka") val boardKeepAlive: Boolean = false,
    @SerialName("ia") val idleDisconnectArmed: Boolean = false,
    @SerialName("ad") val autoDisconnectSeconds: Int = 0,
    @SerialName("sr") val sessionRole: String = "NONE",
    @SerialName("sv") val sessionVisibility: String = "LOCAL_ONLY",
    @SerialName("sw") val sessionVisibilityRequested: String = "LOCAL_ONLY",
    @SerialName("sc") val sessionConnecting: Boolean = false,
    @SerialName("si") val sessionId: Int = 0,
    @SerialName("qs") val queueSize: Int = 0,
    @SerialName("qi") val currentIndex: Int = -1,
    @SerialName("ci") val currentClimbId: String? = null,
    @SerialName("cp") val canonicalPlaylist: Boolean = false,
    @SerialName("ph") val playlistHost: Boolean = false,
    @SerialName("pm") val playlistMember: Boolean = false,
    @SerialName("as") val awaitingExplicitSend: Boolean = false,
    @SerialName("eo") val externalBoardOverride: Boolean = false,
    @SerialName("pc") val pendingCommands: Int = 0,
) {
    internal fun validate() {
        require(schema in 1..16)
        require(appVersionCode >= 0)
        require(meshRole.length <= 32 && meshMemberCount in 0..64)
        require(boardConnection.length <= 32 && autoDisconnectSeconds in 0..86_400)
        require(sessionRole.length <= 32)
        require(sessionVisibility.length <= 32 && sessionVisibilityRequested.length <= 32)
        require(sessionId >= 0 && queueSize in 0..BoardPlaylistPolicy.MAX_ITEMS)
        require(currentIndex in -1 until BoardPlaylistPolicy.MAX_ITEMS)
        require(currentClimbId == null || currentClimbId.length in 1..64)
        require(pendingCommands in 0..1_024)
    }
}

internal object BoardCellPeerDiagnosticsLog {
    fun emit(event: String, peer: String, value: BoardCellPeerDiagnostics) {
        FipsDebugLog.event(
            "peer_health", event,
            "peer" to FipsDebugLog.id(peer),
            "build" to value.appVersionCode,
            "bluetooth" to value.bluetoothEnabled,
            "runtime" to value.meshRuntimeRunning,
            "meshRole" to value.meshRole,
            "meshMembers" to value.meshMemberCount,
            "controllerAvailable" to value.controllerAvailable,
            "boardConnection" to value.boardConnection,
            "keepAlive" to value.boardKeepAlive,
            "idleTimer" to value.idleDisconnectArmed,
            "autoDisconnectSeconds" to value.autoDisconnectSeconds,
            "sessionRole" to value.sessionRole,
            "visibility" to value.sessionVisibility,
            "visibilityWanted" to value.sessionVisibilityRequested,
            "sessionConnecting" to value.sessionConnecting,
            "session" to value.sessionId,
            "queue" to value.queueSize,
            "index" to value.currentIndex,
            "climb" to FipsDebugLog.id(value.currentClimbId),
            "canonicalPlaylist" to value.canonicalPlaylist,
            "playlistHost" to value.playlistHost,
            "playlistMember" to value.playlistMember,
            "awaitingSend" to value.awaitingExplicitSend,
            "externalOverride" to value.externalBoardOverride,
            "pendingCommands" to value.pendingCommands,
        )
    }
}

interface AuthenticatedMeshLink {
    val localNpub: String
    fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean
    fun directAuthenticatedPeers(): Set<String> = emptySet()
    fun activeRealmId(): String? = null
}

data class InboundSessionCommand(
    val senderId: String,
    val commandId: String,
    val basePlaylistRevision: Long,
    val payload: ByteArray,
    val context: BoardPlaylistCommandContext?,
)

data class InboundProjectionRequest(
    val senderId: String,
    val request: BoardProjectionRequest,
)

data class InboundPlaylistControl(
    val senderId: String,
    val control: BoardPlaylistControl,
)

/**
 * A queue edit or projection retry an authenticated cell member is carrying
 * for its own API-28 GATT leaf.
 *
 * Kept as its own inbound type so the controller cannot accidentally treat it
 * as the sender's own command: it is granted bounded proxy authority, which is
 * strictly less than playlist membership.
 */
data class InboundLeafCommand(
    val senderId: String,
    val commandId: String,
    val basePlaylistRevision: Long,
    /** Null for a projection retry, which carries no queue payload. */
    val payload: ByteArray?,
    val context: BoardPlaylistCommandContext?,
)

class BoardCellMeshTransport(private val link: AuthenticatedMeshLink) : BoardCellTransport {
    private var coordinator: BoardCellCoordinator? = null
    private val snapshots = mutableMapOf<BoardCellId, BoardCellSnapshot>()
    private val outbox = ArrayDeque<Pair<String, ByteArray>>()
    private var outboxBytes = 0
    private val seenFrames = LinkedHashSet<String>()
    private val seenCommands = LinkedHashMap<String, BoardCommandAck>()
    private val lastPeerDiagnostics =
        mutableMapOf<String, Pair<String, BoardCellPeerDiagnostics>>()
    var onSessionCommand: (suspend (InboundSessionCommand) -> Unit)? = null
    var onCommandAck: (suspend (String, BoardCommandAck) -> Unit)? = null
    var onControllerRequest: (suspend (String, BoardCellControllerRequest) -> Unit)? = null
    var onControllerDecision: (suspend (String, BoardCellControllerDecision) -> Unit)? = null
    var onProjectionRequest: (suspend (InboundProjectionRequest) -> Unit)? = null
    var onPlaylistControl: (suspend (InboundPlaylistControl) -> Unit)? = null
    var onLeafCommand: (suspend (InboundLeafCommand) -> Unit)? = null

    fun attach(value: BoardCellCoordinator) { coordinator = value }
    fun rememberSnapshot(snapshot: BoardCellSnapshot) { snapshots[snapshot.cellId] = snapshot }

    /** A native runtime carries exactly one authenticated realm. Transient
     * state from the previous realm is neither routable nor useful and must
     * not consume anti-entropy/outbox capacity after an explicit switch. */
    fun resetForRealm() {
        snapshots.clear()
        outbox.clear()
        outboxBytes = 0
        seenFrames.clear()
        seenCommands.clear()
        lastPeerDiagnostics.clear()
    }

    override suspend fun publishClaim(claim: BoardCellClaim) {
        val frame = frame(claim.cellId.value, claim.cellId, claim.physicalBoardId, 0, claim.proposedTerm,
            BoardCellWireMessage.DirectClaim(claim))
        val peers = link.directAuthenticatedPeers()
        FipsDebugLog.event("wire", "claim_publish", "claimant" to FipsDebugLog.id(claim.claimantId),
            "cell" to FipsDebugLog.id(claim.cellId.value), "directPeers" to peers.size)
        peers.forEach { sendOrQueue(it, frame) }
    }

    override suspend fun publishEvent(envelope: BoardCellEnvelope) {
        val snapshot = coordinator?.snapshot(envelope.physicalBoardId) ?: snapshots[envelope.cellId]
        snapshot?.let { snapshots[it.cellId] = it }
        FipsDebugLog.event("wire", "event_publish", "type" to envelope.event.javaClass.simpleName,
            "sequence" to envelope.sequence, "term" to envelope.controllerTerm,
            "members" to snapshot?.members?.size)
        val current = snapshot ?: return
        val bytes = frameFor(current, BoardCellWireMessage.Event(envelope), envelope.controllerTerm)
        if (envelope.event is BoardCellEvent.ControllerHeartbeat) {
            // Heartbeats are superseded by every newer heartbeat/snapshot and
            // must not evict canonical mutations for long-offline members.
            current.members.asSequence().filter { it != link.localNpub }
                .forEach { peer -> link.send(peer, bytes) }
        } else {
            multicast(current.members, bytes)
            (envelope.event as? BoardCellEvent.MemberLeft)?.memberId?.let { removed ->
                // Give an actively connected leaver its canonical tombstone.
                // It is intentionally best-effort: disconnected members are
                // repaired by an exclusion snapshot if they contact us again.
                link.send(removed, bytes)
            }
        }
    }

    override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) {
        FipsDebugLog.event("wire", "snapshot_publish", "sequence" to snapshot.sequence,
            "term" to snapshot.controllerTerm, "members" to snapshot.members.size,
            "hash" to FipsDebugLog.id(snapshot.stateHash))
        snapshots[snapshot.cellId] = snapshot
        multicast(snapshot.members, frameFor(snapshot, BoardCellWireMessage.Snapshot(snapshot)))
    }

    override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) {
        val snapshot = snapshots[cellId] ?: return
        // Requests are regenerated by the next gap/anti-entropy tick; keeping
        // stale duplicates must not evict canonical state from the outbox.
        link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.SnapshotRequest(afterSequence)))
    }

    override suspend fun sendHandoverReady(target: String, ready: HandoverReady) {
        val snapshot = snapshots[ready.cellId] ?: return
        sendOrQueue(target, frameFor(snapshot, BoardCellWireMessage.Ready(ready)))
    }

    fun sendControllerRequest(snapshot: BoardCellSnapshot, request: BoardCellControllerRequest): Boolean {
        if (link.localNpub !in snapshot.members || link.localNpub == snapshot.controllerId ||
            request.requesterId != link.localNpub) return false
        return link.send(snapshot.controllerId, frameFor(snapshot,
            BoardCellWireMessage.ControllerRequest(request)))
    }

    fun sendControllerDecision(target: String, snapshot: BoardCellSnapshot,
        decision: BoardCellControllerDecision): Boolean =
        link.send(target, frameFor(snapshot, BoardCellWireMessage.ControllerDecision(decision)))

    /** Sponsor a directly authenticated neighbor into the permissionless cell. */
    fun sponsorMember(snapshot: BoardCellSnapshot, candidateId: String): Boolean {
        if (link.localNpub !in snapshot.members || link.localNpub == snapshot.controllerId ||
            snapshot.availability != BoardCellAvailability.ACTIVE ||
            candidateId !in link.directAuthenticatedPeers()) return false
        val request = BoardCellJoinRequest(UUID.randomUUID().toString(), candidateId, link.localNpub)
        return link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.MemberJoinRequest(request)))
    }

    /** Periodic authenticated end-source proof; routing may be multi-hop. */
    fun sendMemberHeartbeat(
        snapshot: BoardCellSnapshot,
        tick: Long,
        diagnostics: BoardCellPeerDiagnostics? = null,
    ): Boolean {
        if (link.localNpub !in snapshot.members || link.localNpub == snapshot.controllerId ||
            snapshot.availability != BoardCellAvailability.ACTIVE) return false
        return link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.MemberHeartbeat(tick, diagnostics)))
    }

    /** Controller-side diagnostics travel outside canonical state/hash. */
    fun sendControllerDiagnostics(
        snapshot: BoardCellSnapshot,
        tick: Long,
        diagnostics: BoardCellPeerDiagnostics,
    ) {
        if (link.localNpub != snapshot.controllerId) return
        val frame = frameFor(snapshot, BoardCellWireMessage.PeerDiagnostics(tick, diagnostics))
        snapshot.members.asSequence().filter { it != link.localNpub }
            .forEach { link.send(it, frame) }
    }

    private fun logPeerDiagnostics(
        sender: String,
        value: BoardCellPeerDiagnostics,
        event: String = "remote_state_changed",
    ) {
        val next = event to value
        if (lastPeerDiagnostics.put(sender, next) == next) return
        BoardCellPeerDiagnosticsLog.emit(event, sender, value)
    }

    fun sendMemberLeaveRequest(snapshot: BoardCellSnapshot, requestId: String): Boolean {
        if (link.localNpub !in snapshot.members || link.localNpub == snapshot.controllerId ||
            requestId.length !in 8..128) return false
        return link.send(snapshot.controllerId, frameFor(snapshot,
            BoardCellWireMessage.MemberLeaveRequest(BoardCellLeaveRequest(requestId))))
    }

    private fun sendAuthoritativeSnapshot(snapshot: BoardCellSnapshot, target: String): Boolean =
        link.send(target, frameFor(snapshot, BoardCellWireMessage.Snapshot(snapshot)))

    /** Targeted full-state welcome/resync for a reachable canonical member. */
    fun sendSnapshotTo(snapshot: BoardCellSnapshot, memberId: String): Boolean {
        if (link.localNpub != snapshot.controllerId || memberId !in snapshot.members) return false
        return link.send(memberId, frameFor(snapshot, BoardCellWireMessage.Snapshot(snapshot)))
    }

    override suspend fun publishRecovery(recovery: BoardCellControllerRecovery) {
        val snapshot = coordinator?.snapshot(recovery.envelope.physicalBoardId) ?: return
        snapshots[snapshot.cellId] = snapshot
        val bytes = frameFor(snapshot, BoardCellWireMessage.ControllerRecovery(recovery),
            recovery.baseControllerTerm)
        multicast(snapshot.members, bytes)
        if (recovery.baseControllerId != snapshot.controllerId) {
            link.send(recovery.baseControllerId, bytes)
        }
    }

    fun sendProjectionRequest(snapshot: BoardCellSnapshot, request: BoardProjectionRequest): Boolean {
        if (link.localNpub !in snapshot.members || link.localNpub == snapshot.controllerId ||
            snapshot.availability != BoardCellAvailability.ACTIVE) return false
        return link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.ProjectionRequest(request)))
    }

    override suspend fun sendForkNotice(target: String, notice: BoardCellForkNotice) {
        val snapshot = notice.conflictingSnapshot
        sendOrQueue(target, frameFor(snapshot, BoardCellWireMessage.ForkNotice(notice)))
    }

    override suspend fun publishCommandAck(target: String, ack: BoardCommandAck) {
        FipsDebugLog.event("wire", "command_ack_publish", "target" to FipsDebugLog.id(target),
            "command" to FipsDebugLog.id(ack.commandId), "status" to ack.status,
            "sequence" to ack.resultingSequence)
        val snapshot = snapshots[ack.cellId] ?: return
        seenCommands["$target:${ack.commandId}"] = ack
        trimMap(seenCommands, MAX_SEEN_COMMANDS)
        sendOrQueue(target, frameFor(snapshot, BoardCellWireMessage.CommandAck(ack)))
    }

    /**
     * Ask the controller to serialize one playlist lifecycle command.
     *
     * Unlike [sendSessionCommand] this is legal for every authenticated cell
     * member, including one that is not (yet) a playlist member — starting or
     * joining a playlist is precisely how a member stops being an outsider.
     */
    fun sendPlaylistControl(snapshot: BoardCellSnapshot, control: BoardPlaylistControl): Boolean {
        if (link.localNpub !in snapshot.members || snapshot.controllerId == link.localNpub ||
            snapshot.availability != BoardCellAvailability.ACTIVE) {
            FipsDebugLog.warning("wire", "playlist_control_refused",
                "command" to FipsDebugLog.id(control.commandId),
                "localMember" to (link.localNpub in snapshot.members),
                "localIsController" to (snapshot.controllerId == link.localNpub),
                "availability" to snapshot.availability)
            return false
        }
        val sent = link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.PlaylistControl(control)))
        FipsDebugLog.event("wire", if (sent) "playlist_control_tx" else "playlist_control_tx_failed",
            "controller" to FipsDebugLog.id(snapshot.controllerId),
            "command" to FipsDebugLog.id(control.commandId),
            "kind" to control.javaClass.simpleName,
            "baseRevision" to control.basePlaylistRevision)
        return sent
    }

    /**
     * Carry a joined GATT leaf's queue edit to the controller.
     *
     * Legal for any authenticated cell member, including one that never joined
     * the playlist — that is the whole point: the gateway lends its
     * authenticated hop, not its membership.
     */
    fun sendLeafSessionCommand(snapshot: BoardCellSnapshot, commandId: String, payload: ByteArray,
        context: BoardPlaylistCommandContext?,
        basePlaylistRevision: Long = snapshot.playlistRevision): Boolean {
        if (link.localNpub !in snapshot.members || snapshot.controllerId == link.localNpub ||
            snapshot.availability != BoardCellAvailability.ACTIVE || payload.isEmpty() ||
            payload.size > MAX_SESSION_COMMAND_BYTES) return false
        val sent = link.send(snapshot.controllerId, frameFor(snapshot,
            BoardCellWireMessage.LeafSessionCommand(commandId, basePlaylistRevision, payload, context)))
        FipsDebugLog.event("wire", if (sent) "leaf_command_tx" else "leaf_command_tx_failed",
            "controller" to FipsDebugLog.id(snapshot.controllerId),
            "command" to FipsDebugLog.id(commandId), "kind" to context?.kind)
        return sent
    }

    fun sendLeafRetryProjection(snapshot: BoardCellSnapshot, commandId: String,
        basePlaylistRevision: Long = snapshot.playlistRevision): Boolean {
        if (link.localNpub !in snapshot.members || snapshot.controllerId == link.localNpub ||
            snapshot.availability != BoardCellAvailability.ACTIVE) return false
        return link.send(snapshot.controllerId, frameFor(snapshot,
            BoardCellWireMessage.LeafRetryProjection(commandId, basePlaylistRevision)))
    }

    fun sendSessionCommand(snapshot: BoardCellSnapshot, commandId: String, payload: ByteArray,
        context: BoardPlaylistCommandContext?,
        basePlaylistRevision: Long = snapshot.playlistRevision): Boolean {
        if (link.localNpub !in snapshot.members || snapshot.controllerId == link.localNpub ||
            snapshot.availability != BoardCellAvailability.ACTIVE || payload.isEmpty() ||
            payload.size > MAX_SESSION_COMMAND_BYTES) {
            FipsDebugLog.warning("wire", "session_command_refused", "command" to FipsDebugLog.id(commandId),
                "localMember" to (link.localNpub in snapshot.members),
                "localIsController" to (snapshot.controllerId == link.localNpub),
                "availability" to snapshot.availability, "bytes" to payload.size)
            return false
        }
        val sent = link.send(snapshot.controllerId, frameFor(snapshot,
            BoardCellWireMessage.SessionCommand(commandId, basePlaylistRevision, payload, context)))
        FipsDebugLog.event("wire", if (sent) "session_command_tx" else "session_command_tx_failed",
            "controller" to FipsDebugLog.id(snapshot.controllerId), "command" to FipsDebugLog.id(commandId),
            "baseRevision" to basePlaylistRevision, "kind" to context?.kind, "bytes" to payload.size)
        return sent
    }

    suspend fun receive(authenticatedSender: String, bytes: ByteArray, nowMonotonicMs: Long = 0): BoardCellApplyResult? {
        val value = runCatching { BoardCellWireCodec.decode(bytes) }.getOrElse {
            FipsDebugLog.warning("wire", "frame_rejected", "sender" to FipsDebugLog.id(authenticatedSender),
                "bytes" to bytes.size, "reason" to "invalid/unsupported wire frame")
            return BoardCellApplyResult.Rejected("invalid/unsupported wire frame")
        }
        FipsDebugLog.event("wire", "frame_rx", "sender" to FipsDebugLog.id(authenticatedSender),
            "message" to value.message.javaClass.simpleName, "messageId" to FipsDebugLog.id(value.messageId),
            "realm" to FipsDebugLog.id(value.realmId), "cell" to FipsDebugLog.id(value.cellId.value),
            "epoch" to value.epoch, "term" to value.controllerTerm, "bytes" to bytes.size)
        if (value.senderId != authenticatedSender) {
            FipsDebugLog.warning("wire", "frame_rejected", "reason" to "authenticated sender mismatch")
            return BoardCellApplyResult.Rejected("authenticated sender mismatch")
        }
        val expectedRealm = link.activeRealmId()
        if (expectedRealm != null && value.realmId != expectedRealm) {
            FipsDebugLog.warning("wire", "frame_rejected", "reason" to "realm mismatch",
                "expected" to FipsDebugLog.id(expectedRealm), "received" to FipsDebugLog.id(value.realmId))
            return BoardCellApplyResult.Rejected("realm mismatch")
        }
        if (!seenFrames.add("$authenticatedSender:${value.messageId}")) {
            FipsDebugLog.event("wire", "frame_duplicate_ignored", "messageId" to FipsDebugLog.id(value.messageId))
            return BoardCellApplyResult.IgnoredStale
        }
        trimSet(seenFrames, MAX_SEEN_FRAMES)
        val target = coordinator ?: return BoardCellApplyResult.Rejected("coordinator unavailable")
        val local = snapshots[value.cellId]
        if (local != null && value.physicalBoardId != local.physicalBoardId)
            return BoardCellApplyResult.Rejected("cell/board mismatch")
        val scopeMatchesPayload = when (val scoped = value.message) {
            is BoardCellWireMessage.Snapshot -> value.cellId == scoped.value.cellId &&
                value.physicalBoardId == scoped.value.physicalBoardId && value.epoch == scoped.value.epoch &&
                value.controllerTerm == scoped.value.controllerTerm
            is BoardCellWireMessage.Event -> value.cellId == scoped.value.cellId &&
                value.physicalBoardId == scoped.value.physicalBoardId && value.epoch == scoped.value.epoch &&
                value.controllerTerm == scoped.value.controllerTerm
            is BoardCellWireMessage.ForkNotice -> value.cellId == scoped.value.conflictingSnapshot.cellId &&
                value.physicalBoardId == scoped.value.conflictingSnapshot.physicalBoardId
            is BoardCellWireMessage.DirectClaim -> value.cellId == scoped.value.cellId &&
                value.physicalBoardId == scoped.value.physicalBoardId
            // Anti-entropy is precisely how peers discover a missed controller
            // term/recovery. Requiring the already-known term here creates a
            // catch-22 where the repair digest itself is rejected.
            is BoardCellWireMessage.AntiEntropy -> local != null && value.epoch == local.epoch
            else -> local != null && value.epoch == local.epoch && value.controllerTerm == local.controllerTerm
        }
        if (!scopeMatchesPayload) return BoardCellApplyResult.Rejected("realm/cell/board/epoch/term mismatch")
        if (local != null && local.controllerId == link.localNpub &&
            authenticatedSender in local.members && authenticatedSender != link.localNpub) {
            target.observeMemberActivity(local.physicalBoardId, authenticatedSender, nowMonotonicMs)
        }
        return when (val message = value.message) {
            is BoardCellWireMessage.DirectClaim -> {
                if (authenticatedSender !in link.directAuthenticatedPeers() || message.value.claimantId != authenticatedSender ||
                    message.value.cellId != value.cellId || message.value.physicalBoardId != value.physicalBoardId)
                    BoardCellApplyResult.Rejected("claim is not authenticated direct discovery")
                else { target.observeClaim(message.value, nowMonotonicMs); null }
            }
            is BoardCellWireMessage.Snapshot -> target.acceptSnapshot(authenticatedSender, message.value, nowMonotonicMs).also {
                if (it is BoardCellApplyResult.Applied) snapshots[it.snapshot.cellId] = it.snapshot
            }
            is BoardCellWireMessage.Event -> target.acceptEvent(authenticatedSender, message.value, nowMonotonicMs).also {
                if (it is BoardCellApplyResult.Applied) snapshots[it.snapshot.cellId] = it.snapshot
                // Coordinator.acceptEvent already issued the one best-effort
                // request. Do not enqueue a duplicate for the same gap.
            }
            is BoardCellWireMessage.SnapshotRequest -> {
                val snapshot = snapshots[value.cellId]
                if (snapshot != null && snapshot.controllerId == link.localNpub && authenticatedSender in snapshot.members)
                    sendOrQueue(authenticatedSender, frameFor(snapshot, BoardCellWireMessage.Snapshot(snapshot)))
                null
            }
            is BoardCellWireMessage.AntiEntropy -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("anti-entropy has no local cell")
                if (authenticatedSender !in snapshot.members) {
                    if (snapshot.controllerId == link.localNpub) {
                        sendAuthoritativeSnapshot(snapshot, authenticatedSender)
                    }
                    return BoardCellApplyResult.Rejected("anti-entropy sender is not member")
                }
                if (authenticatedSender == snapshot.controllerId) {
                    target.observeControllerActivity(snapshot.physicalBoardId, authenticatedSender,
                        nowMonotonicMs)
                }
                if (snapshot.sequence != message.sequence || snapshot.stateHash != message.stateHash) {
                    if (snapshot.controllerId == link.localNpub) {
                        // The controller is the canonical source. Asking itself
                        // for a snapshot leaves a stale member stuck forever;
                        // push the authoritative state back to that member.
                        sendOrQueue(authenticatedSender,
                            frameFor(snapshot, BoardCellWireMessage.Snapshot(snapshot)))
                    } else {
                        requestSnapshot(value.cellId, snapshot.sequence)
                    }
                }
                null
            }
            is BoardCellWireMessage.Ready -> {
                if (target.acceptTargetReady(authenticatedSender, message.value, nowMonotonicMs))
                    target.commitHandover(message.value.physicalBoardId, message.value.transferId, nowMonotonicMs)
                null
            }
            is BoardCellWireMessage.ControllerRequest -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("controller request has no local cell")
                if (link.localNpub != snapshot.controllerId || authenticatedSender !in snapshot.members ||
                    message.value.requesterId != authenticatedSender) {
                    return BoardCellApplyResult.Rejected("controller request sender/role mismatch")
                }
                onControllerRequest?.invoke(authenticatedSender, message.value)
                null
            }
            is BoardCellWireMessage.ControllerDecision -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("controller decision has no local cell")
                if (authenticatedSender != snapshot.controllerId || link.localNpub !in snapshot.members) {
                    return BoardCellApplyResult.Rejected("controller decision sender/role mismatch")
                }
                onControllerDecision?.invoke(authenticatedSender, message.value)
                null
            }
            is BoardCellWireMessage.MemberJoinRequest -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("member join request has no local cell")
                if (link.localNpub != snapshot.controllerId || authenticatedSender !in snapshot.members ||
                    message.value.sponsorId != authenticatedSender) {
                    return BoardCellApplyResult.Rejected("member join sponsor/role mismatch")
                }
                if (message.value.candidateId in snapshot.members) {
                    sendSnapshotTo(snapshot, message.value.candidateId)
                } else {
                    target.joinMember(snapshot.physicalBoardId, message.value.candidateId, nowMonotonicMs)
                    target.snapshot(snapshot.physicalBoardId)?.let { snapshots[it.cellId] = it }
                }
                null
            }
            is BoardCellWireMessage.MemberHeartbeat -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("member heartbeat has no local cell")
                if (link.localNpub != snapshot.controllerId || authenticatedSender == snapshot.controllerId ||
                    authenticatedSender !in snapshot.members) {
                    if (link.localNpub == snapshot.controllerId && authenticatedSender !in snapshot.members) {
                        message.diagnostics?.let {
                            logPeerDiagnostics(authenticatedSender, it, "remote_state_unadmitted")
                        }
                        sendAuthoritativeSnapshot(snapshot, authenticatedSender)
                    }
                    return BoardCellApplyResult.Rejected("member heartbeat sender/role mismatch")
                }
                message.diagnostics?.let { logPeerDiagnostics(authenticatedSender, it) }
                target.observeMemberActivity(snapshot.physicalBoardId, authenticatedSender, nowMonotonicMs)
                null
            }
            is BoardCellWireMessage.PeerDiagnostics -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("peer diagnostics has no local cell")
                if (authenticatedSender !in snapshot.members || link.localNpub !in snapshot.members) {
                    return BoardCellApplyResult.Rejected("peer diagnostics sender/receiver mismatch")
                }
                logPeerDiagnostics(authenticatedSender, message.value)
                null
            }
            is BoardCellWireMessage.MemberLeaveRequest -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("member leave has no local cell")
                if (link.localNpub != snapshot.controllerId || authenticatedSender == snapshot.controllerId ||
                    authenticatedSender !in snapshot.members) {
                    if (link.localNpub == snapshot.controllerId && authenticatedSender !in snapshot.members) {
                        sendAuthoritativeSnapshot(snapshot, authenticatedSender)
                    }
                    return BoardCellApplyResult.Rejected("member leave sender/role mismatch")
                }
                target.leaveMember(snapshot.physicalBoardId, authenticatedSender,
                    BoardCellMemberLeaveReason.VOLUNTARY, nowMonotonicMs)
                target.snapshot(snapshot.physicalBoardId)?.let { snapshots[it.cellId] = it }
                null
            }
            is BoardCellWireMessage.ControllerRecovery ->
                target.acceptControllerRecovery(authenticatedSender, message.value, nowMonotonicMs).also {
                    if (it is BoardCellApplyResult.Applied) snapshots[it.snapshot.cellId] = it.snapshot
                }
            is BoardCellWireMessage.ProjectionRequest -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("projection request has no local cell")
                if (link.localNpub != snapshot.controllerId || authenticatedSender !in snapshot.members ||
                    message.value.baseSequence > snapshot.sequence) {
                    return BoardCellApplyResult.Rejected("projection request sender/role mismatch")
                }
                onProjectionRequest?.invoke(InboundProjectionRequest(authenticatedSender, message.value))
                null
            }
            is BoardCellWireMessage.LeafSessionCommand -> acceptLeafCommand(
                authenticatedSender, value.cellId, target, message.commandId,
                message.basePlaylistRevision, message.payload, message.context)
            is BoardCellWireMessage.LeafRetryProjection -> acceptLeafCommand(
                authenticatedSender, value.cellId, target, message.commandId,
                message.basePlaylistRevision, payload = null, context = null)
            is BoardCellWireMessage.PlaylistControl -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("playlist control has no local cell")
                val control = message.value
                val key = "$authenticatedSender:${control.commandId}"
                target.commandAck(control.commandId)?.let {
                    seenCommands[key] = it
                    publishCommandAck(authenticatedSender, it)
                    return null
                }
                seenCommands[key]?.let { publishCommandAck(authenticatedSender, it); return null }
                if (link.localNpub != snapshot.controllerId || authenticatedSender !in snapshot.members ||
                    control.basePlaylistRevision > snapshot.playlistRevision) {
                    val status = if (link.localNpub != snapshot.controllerId)
                        BoardCommandStatus.NOT_CONTROLLER else BoardCommandStatus.REJECTED_STALE
                    val ack = BoardCommandAck(control.commandId, status, snapshot.cellId, snapshot.epoch,
                        snapshot.controllerTerm, snapshot.sequence, snapshot.stateHash,
                        "playlist control scope/base rejected")
                    seenCommands[key] = ack
                    trimMap(seenCommands, MAX_SEEN_COMMANDS)
                    FipsDebugLog.warning("wire", "playlist_control_rejected",
                        "command" to FipsDebugLog.id(control.commandId), "status" to status,
                        "baseRevision" to control.basePlaylistRevision,
                        "currentRevision" to snapshot.playlistRevision)
                    publishCommandAck(authenticatedSender, ack)
                    return BoardCellApplyResult.Rejected("playlist control rejected")
                }
                FipsDebugLog.event("wire", "playlist_control_accepted",
                    "command" to FipsDebugLog.id(control.commandId),
                    "sender" to FipsDebugLog.id(authenticatedSender),
                    "kind" to control.javaClass.simpleName)
                onPlaylistControl?.invoke(InboundPlaylistControl(authenticatedSender, control))
                null
            }
            is BoardCellWireMessage.ForkNotice -> { target.acceptForkNotice(authenticatedSender, message.value); null }
            is BoardCellWireMessage.SessionCommand -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("command has no local cell")
                val key = "$authenticatedSender:${message.commandId}"
                // A durable terminal result supersedes an in-flight ACCEPTED
                // cache entry when a command is retried after commit.
                target.commandAck(message.commandId)?.let {
                    FipsDebugLog.event("wire", "session_command_deduplicated_durable",
                        "command" to FipsDebugLog.id(message.commandId), "status" to it.status)
                    seenCommands[key] = it
                    publishCommandAck(authenticatedSender, it)
                    return null
                }
                seenCommands[key]?.let { publishCommandAck(authenticatedSender, it); return null }
                if (authenticatedSender !in snapshot.members || link.localNpub != snapshot.controllerId ||
                    message.basePlaylistRevision > snapshot.playlistRevision || message.payload.isEmpty() ||
                    message.payload.size > MAX_SESSION_COMMAND_BYTES) {
                    val status = if (link.localNpub != snapshot.controllerId) BoardCommandStatus.NOT_CONTROLLER
                    else BoardCommandStatus.REJECTED_STALE
                    val ack = BoardCommandAck(message.commandId, status, snapshot.cellId, snapshot.epoch,
                        snapshot.controllerTerm, snapshot.sequence, snapshot.stateHash, "command scope/base rejected")
                    seenCommands[key] = ack
                    FipsDebugLog.warning("wire", "session_command_rejected",
                        "command" to FipsDebugLog.id(message.commandId), "status" to status,
                        "baseRevision" to message.basePlaylistRevision,
                        "currentRevision" to snapshot.playlistRevision)
                    publishCommandAck(authenticatedSender, ack)
                    return BoardCellApplyResult.Rejected("session command rejected")
                }
                val accepted = BoardCommandAck(message.commandId, BoardCommandStatus.ACCEPTED, snapshot.cellId,
                    snapshot.epoch, snapshot.controllerTerm, snapshot.sequence, snapshot.stateHash)
                seenCommands[key] = accepted
                trimMap(seenCommands, MAX_SEEN_COMMANDS)
                publishCommandAck(authenticatedSender, accepted)
                FipsDebugLog.event("wire", "session_command_accepted",
                    "command" to FipsDebugLog.id(message.commandId),
                    "sender" to FipsDebugLog.id(authenticatedSender),
                    "baseRevision" to message.basePlaylistRevision, "kind" to message.context?.kind)
                onSessionCommand?.invoke(InboundSessionCommand(authenticatedSender, message.commandId,
                    message.basePlaylistRevision, message.payload, message.context))
                null
            }
            is BoardCellWireMessage.CommandAck -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("ack has no local cell")
                if (authenticatedSender != snapshot.controllerId ||
                    message.value.cellId != snapshot.cellId || message.value.epoch != snapshot.epoch ||
                    message.value.controllerTerm != snapshot.controllerTerm)
                    return BoardCellApplyResult.Rejected("ack sender/scope/term mismatch")
                onCommandAck?.invoke(authenticatedSender, message.value)
                FipsDebugLog.event("wire", "command_ack_rx", "controller" to FipsDebugLog.id(authenticatedSender),
                    "command" to FipsDebugLog.id(message.value.commandId), "status" to message.value.status,
                    "sequence" to message.value.resultingSequence)
                null
            }
        }
    }

    /**
     * Admits a gateway's leaf command, or refuses it with the same rigour an
     * ordinary session command gets.
     *
     * **Trust boundary.** The claim "I am carrying a leaf's verb" is only ever
     * accepted from a sender the transport has already authenticated *and*
     * that the canonical snapshot lists as a cell member — exactly the bar
     * every other gateway assertion clears. What it buys is deliberately less
     * than membership: the coordinator applies it under
     * [BoardPlaylistAuthority.GATEWAY_PROXY], which permits queue verbs and a
     * projection retry and nothing else. A stranger outside the cell gets
     * nothing from setting it.
     */
    private suspend fun acceptLeafCommand(
        authenticatedSender: String,
        cellId: BoardCellId,
        target: BoardCellCoordinator,
        commandId: String,
        basePlaylistRevision: Long,
        payload: ByteArray?,
        context: BoardPlaylistCommandContext?,
    ): BoardCellApplyResult? {
        val snapshot = snapshots[cellId]
            ?: return BoardCellApplyResult.Rejected("leaf command has no local cell")
        val key = "$authenticatedSender:$commandId"
        target.commandAck(commandId)?.let {
            seenCommands[key] = it
            publishCommandAck(authenticatedSender, it)
            return null
        }
        seenCommands[key]?.let { publishCommandAck(authenticatedSender, it); return null }
        if (authenticatedSender !in snapshot.members || link.localNpub != snapshot.controllerId ||
            basePlaylistRevision > snapshot.playlistRevision) {
            val status = if (link.localNpub != snapshot.controllerId)
                BoardCommandStatus.NOT_CONTROLLER else BoardCommandStatus.REJECTED_STALE
            val ack = BoardCommandAck(commandId, status, snapshot.cellId, snapshot.epoch,
                snapshot.controllerTerm, snapshot.sequence, snapshot.stateHash,
                "leaf command scope/base rejected")
            seenCommands[key] = ack
            trimMap(seenCommands, MAX_SEEN_COMMANDS)
            FipsDebugLog.warning("wire", "leaf_command_rejected",
                "command" to FipsDebugLog.id(commandId), "status" to status,
                "sender" to FipsDebugLog.id(authenticatedSender),
                "senderIsMember" to (authenticatedSender in snapshot.members))
            publishCommandAck(authenticatedSender, ack)
            return BoardCellApplyResult.Rejected("leaf command rejected")
        }
        val accepted = BoardCommandAck(commandId, BoardCommandStatus.ACCEPTED, snapshot.cellId,
            snapshot.epoch, snapshot.controllerTerm, snapshot.sequence, snapshot.stateHash)
        seenCommands[key] = accepted
        trimMap(seenCommands, MAX_SEEN_COMMANDS)
        publishCommandAck(authenticatedSender, accepted)
        FipsDebugLog.event("wire", "leaf_command_accepted",
            "command" to FipsDebugLog.id(commandId),
            "gateway" to FipsDebugLog.id(authenticatedSender),
            "kind" to (context?.kind?.name ?: "retry_projection"))
        onLeafCommand?.invoke(InboundLeafCommand(authenticatedSender, commandId,
            basePlaylistRevision, payload, context))
        return null
    }

    fun retryOutbox(limit: Int = 64) {
        if (outbox.isNotEmpty()) FipsDebugLog.event("wire", "outbox_retry",
            "queuedFrames" to outbox.size, "queuedBytes" to outboxBytes, "limit" to limit)
        repeat(minOf(limit, outbox.size)) {
            val item = outbox.removeFirst(); outboxBytes -= item.second.size
            if (!link.send(item.first, item.second)) enqueue(item.first, item.second)
        }
    }

    fun antiEntropy() {
        snapshots.values.forEach { snapshot ->
            val digest = frameFor(snapshot,
                BoardCellWireMessage.AntiEntropy(snapshot.sequence, snapshot.stateHash))
            // Digests are periodic hints, not durable history. Queueing one per
            // offline member on every maintenance tick crowds actual events,
            // snapshots and command ACKs out of the bounded outbox. A fresh
            // digest on the next tick fully supersedes a missed one.
            snapshot.members.asSequence().filter { it != link.localNpub }
                .forEach { peer -> link.send(peer, digest) }
        }
    }

    private fun frameFor(snapshot: BoardCellSnapshot, message: BoardCellWireMessage, term: Long = snapshot.controllerTerm) =
        frame(link.activeRealmId() ?: snapshot.cellId.value, snapshot.cellId, snapshot.physicalBoardId,
            snapshot.epoch, term, message)

    private fun frame(realm: String, cell: BoardCellId, board: PhysicalBoardId, epoch: Long, term: Long,
        message: BoardCellWireMessage) = BoardCellWireCodec.encode(BoardCellWireFrame(
        messageId = UUID.randomUUID().toString(), senderId = link.localNpub, realmId = realm,
        cellId = cell, physicalBoardId = board, epoch = epoch, controllerTerm = term, message = message))

    private fun multicast(members: Set<String>, bytes: ByteArray) =
        members.asSequence().filter { it != link.localNpub }.forEach { sendOrQueue(it, bytes) }

    private fun sendOrQueue(peer: String, bytes: ByteArray) {
        if (bytes.size > MAX_WIRE_BYTES) {
            FipsDebugLog.warning("wire", "frame_dropped_oversize", "peer" to FipsDebugLog.id(peer),
                "bytes" to bytes.size, "max" to MAX_WIRE_BYTES)
            return
        }
        if (!link.send(peer, bytes)) {
            FipsDebugLog.warning("wire", "frame_queued", "peer" to FipsDebugLog.id(peer),
                "bytes" to bytes.size)
            enqueue(peer, bytes)
        }
    }

    private fun enqueue(peer: String, bytes: ByteArray) {
        while (outbox.isNotEmpty() && (outbox.size >= MAX_OUTBOX || outboxBytes + bytes.size > MAX_OUTBOX_BYTES))
            outboxBytes -= outbox.removeFirst().second.size
        if (bytes.size <= MAX_OUTBOX_BYTES) {
            outbox.addLast(peer to bytes); outboxBytes += bytes.size
            FipsDebugLog.event("wire", "outbox_size", "frames" to outbox.size, "bytes" to outboxBytes)
        }
    }

    private fun <T> trimSet(set: LinkedHashSet<T>, max: Int) { while (set.size > max) set.remove(set.first()) }
    private fun <K, V> trimMap(map: LinkedHashMap<K, V>, max: Int) { while (map.size > max) map.remove(map.keys.first()) }

    companion object {
        const val MAX_OUTBOX = 2_048
        const val MAX_OUTBOX_BYTES = 4 * 1_048_576
        const val MAX_WIRE_BYTES = 256 * 1_024
        const val MAX_SESSION_COMMAND_BYTES = 512
        const val MAX_SEEN_COMMANDS = 4_096
        const val MAX_SEEN_FRAMES = 8_192
    }
}
