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
    @Serializable @SerialName("command_ack") data class CommandAck(val value: BoardCommandAck) : BoardCellWireMessage
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
    // V7 makes membership live: authenticated member heartbeats, explicit
    // leave requests and canonical MemberLeft events. Older peers fail closed.
    // V6 added semantic projection bases so heartbeat-only sequence advances
    // do not spuriously reject a participant's board command. V5 added
    // permissionless, member-sponsored multi-hop BoardCell admission.
    // Older peers must fail closed instead of interpreting the new authority flow.
    const val VERSION = 7
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
                }
                is BoardCellWireMessage.Event -> if (message.value.event is BoardCellEvent.MemberLeft) {
                    require(message.value.event.memberId.length in 1..256)
                }
                is BoardCellWireMessage.SessionCommand -> require(
                    message.commandId.length in 8..128 && message.payload.size in 1..BoardCellMeshTransport.MAX_SESSION_COMMAND_BYTES)
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
                is BoardCellWireMessage.MemberHeartbeat -> require(message.tick >= 0)
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

class BoardCellMeshTransport(private val link: AuthenticatedMeshLink) : BoardCellTransport {
    private var coordinator: BoardCellCoordinator? = null
    private val snapshots = mutableMapOf<BoardCellId, BoardCellSnapshot>()
    private val outbox = ArrayDeque<Pair<String, ByteArray>>()
    private var outboxBytes = 0
    private val seenFrames = LinkedHashSet<String>()
    private val seenCommands = LinkedHashMap<String, BoardCommandAck>()
    var onSessionCommand: (suspend (InboundSessionCommand) -> Unit)? = null
    var onCommandAck: (suspend (String, BoardCommandAck) -> Unit)? = null
    var onControllerRequest: (suspend (String, BoardCellControllerRequest) -> Unit)? = null
    var onControllerDecision: (suspend (String, BoardCellControllerDecision) -> Unit)? = null
    var onProjectionRequest: (suspend (InboundProjectionRequest) -> Unit)? = null

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
    fun sendMemberHeartbeat(snapshot: BoardCellSnapshot, tick: Long): Boolean {
        if (link.localNpub !in snapshot.members || link.localNpub == snapshot.controllerId ||
            snapshot.availability != BoardCellAvailability.ACTIVE) return false
        return link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.MemberHeartbeat(tick)))
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
                        sendAuthoritativeSnapshot(snapshot, authenticatedSender)
                    }
                    return BoardCellApplyResult.Rejected("member heartbeat sender/role mismatch")
                }
                target.observeMemberActivity(snapshot.physicalBoardId, authenticatedSender, nowMonotonicMs)
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
