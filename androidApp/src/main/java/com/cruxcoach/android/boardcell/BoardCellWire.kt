package com.cruxcoach.android.boardcell

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
    // V3 adds playlistRevision and semantic command preconditions. Mixing V2
    // would silently reinterpret a global sequence as a playlist revision.
    const val VERSION = 3
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
                        message.value.recentCommandIds.size <= 256)
                }
                is BoardCellWireMessage.SessionCommand -> require(
                    message.commandId.length in 8..128 && message.payload.size in 1..BoardCellMeshTransport.MAX_SESSION_COMMAND_BYTES)
                is BoardCellWireMessage.CommandAck -> require(message.value.commandId.length in 8..128)
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

class BoardCellMeshTransport(private val link: AuthenticatedMeshLink) : BoardCellTransport {
    private var coordinator: BoardCellCoordinator? = null
    private val snapshots = mutableMapOf<BoardCellId, BoardCellSnapshot>()
    private val outbox = ArrayDeque<Pair<String, ByteArray>>()
    private var outboxBytes = 0
    private val seenFrames = LinkedHashSet<String>()
    private val seenCommands = LinkedHashMap<String, BoardCommandAck>()
    var onSessionCommand: (suspend (InboundSessionCommand) -> Unit)? = null
    var onCommandAck: (suspend (String, BoardCommandAck) -> Unit)? = null

    fun attach(value: BoardCellCoordinator) { coordinator = value }
    fun rememberSnapshot(snapshot: BoardCellSnapshot) { snapshots[snapshot.cellId] = snapshot }

    override suspend fun publishClaim(claim: BoardCellClaim) {
        val frame = frame(claim.cellId.value, claim.cellId, claim.physicalBoardId, 0, claim.proposedTerm,
            BoardCellWireMessage.DirectClaim(claim))
        link.directAuthenticatedPeers().forEach { sendOrQueue(it, frame) }
    }

    override suspend fun publishEvent(envelope: BoardCellEnvelope) {
        val snapshot = coordinator?.snapshot(envelope.physicalBoardId) ?: snapshots[envelope.cellId]
        snapshot?.let { snapshots[it.cellId] = it }
        multicast(snapshot?.members.orEmpty(), frameFor(snapshot ?: return,
            BoardCellWireMessage.Event(envelope), envelope.controllerTerm))
    }

    override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) {
        snapshots[snapshot.cellId] = snapshot
        multicast(snapshot.members, frameFor(snapshot, BoardCellWireMessage.Snapshot(snapshot)))
    }

    override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) {
        val snapshot = snapshots[cellId] ?: return
        sendOrQueue(snapshot.controllerId, frameFor(snapshot, BoardCellWireMessage.SnapshotRequest(afterSequence)))
    }

    override suspend fun sendHandoverReady(target: String, ready: HandoverReady) {
        val snapshot = snapshots[ready.cellId] ?: return
        sendOrQueue(target, frameFor(snapshot, BoardCellWireMessage.Ready(ready)))
    }

    override suspend fun sendForkNotice(target: String, notice: BoardCellForkNotice) {
        val snapshot = notice.conflictingSnapshot
        sendOrQueue(target, frameFor(snapshot, BoardCellWireMessage.ForkNotice(notice)))
    }

    override suspend fun publishCommandAck(target: String, ack: BoardCommandAck) {
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
            payload.size > MAX_SESSION_COMMAND_BYTES) return false
        return link.send(snapshot.controllerId, frameFor(snapshot,
            BoardCellWireMessage.SessionCommand(commandId, basePlaylistRevision, payload, context)))
    }

    suspend fun receive(authenticatedSender: String, bytes: ByteArray, nowMonotonicMs: Long = 0): BoardCellApplyResult? {
        val value = runCatching { BoardCellWireCodec.decode(bytes) }.getOrElse {
            return BoardCellApplyResult.Rejected("invalid/unsupported wire frame")
        }
        if (value.senderId != authenticatedSender) return BoardCellApplyResult.Rejected("authenticated sender mismatch")
        val expectedRealm = link.activeRealmId()
        if (expectedRealm != null && value.realmId != expectedRealm) return BoardCellApplyResult.Rejected("realm mismatch")
        if (!seenFrames.add("$authenticatedSender:${value.messageId}")) return BoardCellApplyResult.IgnoredStale
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
            else -> local != null && value.epoch == local.epoch && value.controllerTerm == local.controllerTerm
        }
        if (!scopeMatchesPayload) return BoardCellApplyResult.Rejected("realm/cell/board/epoch/term mismatch")
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
                if (it is BoardCellApplyResult.NeedSnapshot) sendOrQueue(authenticatedSender,
                    frame(value.realmId, value.cellId, value.physicalBoardId, value.epoch, value.controllerTerm,
                        BoardCellWireMessage.SnapshotRequest(it.expectedSequence - 1)))
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
                if (authenticatedSender !in snapshot.members) return BoardCellApplyResult.Rejected("anti-entropy sender is not member")
                if (snapshot.sequence != message.sequence || snapshot.stateHash != message.stateHash)
                    requestSnapshot(value.cellId, snapshot.sequence)
                null
            }
            is BoardCellWireMessage.Ready -> {
                if (target.acceptTargetReady(authenticatedSender, message.value, nowMonotonicMs))
                    target.commitHandover(message.value.physicalBoardId, message.value.transferId, nowMonotonicMs)
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
                    publishCommandAck(authenticatedSender, ack)
                    return BoardCellApplyResult.Rejected("session command rejected")
                }
                val accepted = BoardCommandAck(message.commandId, BoardCommandStatus.ACCEPTED, snapshot.cellId,
                    snapshot.epoch, snapshot.controllerTerm, snapshot.sequence, snapshot.stateHash)
                seenCommands[key] = accepted
                trimMap(seenCommands, MAX_SEEN_COMMANDS)
                publishCommandAck(authenticatedSender, accepted)
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
                null
            }
        }
    }

    fun retryOutbox(limit: Int = 64) {
        repeat(minOf(limit, outbox.size)) {
            val item = outbox.removeFirst(); outboxBytes -= item.second.size
            if (!link.send(item.first, item.second)) enqueue(item.first, item.second)
        }
    }

    fun antiEntropy() {
        snapshots.values.forEach { snapshot -> multicast(snapshot.members,
            frameFor(snapshot, BoardCellWireMessage.AntiEntropy(snapshot.sequence, snapshot.stateHash))) }
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
        if (bytes.size > MAX_WIRE_BYTES) return
        if (!link.send(peer, bytes)) enqueue(peer, bytes)
    }

    private fun enqueue(peer: String, bytes: ByteArray) {
        while (outbox.isNotEmpty() && (outbox.size >= MAX_OUTBOX || outboxBytes + bytes.size > MAX_OUTBOX_BYTES))
            outboxBytes -= outbox.removeFirst().second.size
        if (bytes.size <= MAX_OUTBOX_BYTES) { outbox.addLast(peer to bytes); outboxBytes += bytes.size }
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
