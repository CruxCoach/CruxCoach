package com.cruxcoach.android.boardcell

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed interface BoardCellWireMessage {
    @Serializable @SerialName("direct_claim")
    data class DirectClaim(val value: BoardCellClaim) : BoardCellWireMessage
    @Serializable @SerialName("snapshot")
    data class Snapshot(val value: BoardCellSnapshot) : BoardCellWireMessage
    @Serializable @SerialName("event")
    data class Event(val value: BoardCellEnvelope) : BoardCellWireMessage
    @Serializable @SerialName("snapshot_request")
    data class SnapshotRequest(val cellId: BoardCellId, val afterSequence: Long) : BoardCellWireMessage
    @Serializable @SerialName("anti_entropy")
    data class AntiEntropy(val cellId: BoardCellId, val epoch: Long, val sequence: Long,
        val stateHash: String) : BoardCellWireMessage
    @Serializable @SerialName("session_command")
    data class SessionCommand(
        val commandId: String,
        val cellId: BoardCellId,
        val physicalBoardId: PhysicalBoardId,
        val epoch: Long,
        val sequence: Long,
        val payload: ByteArray,
    ) : BoardCellWireMessage
}

object BoardCellWireCodec {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    fun encode(message: BoardCellWireMessage): ByteArray =
        json.encodeToString(message).encodeToByteArray()
    fun decode(bytes: ByteArray): BoardCellWireMessage =
        json.decodeFromString(bytes.decodeToString())
}

interface AuthenticatedMeshLink {
    val localNpub: String
    fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean
    fun directAuthenticatedPeers(): Set<String> = emptySet()
}

/**
 * FIPS-backed BoardCell transport. Source npubs come from authenticated FIPS
 * sessions; the coordinator then enforces membership, cell, epoch and sequence.
 */
class BoardCellMeshTransport(
    private val link: AuthenticatedMeshLink,
) : BoardCellTransport {
    private var coordinator: BoardCellCoordinator? = null
    private val snapshots = mutableMapOf<BoardCellId, BoardCellSnapshot>()
    private val outbox = ArrayDeque<Pair<String, ByteArray>>()
    private var outboxBytes = 0
    private val seenSessionCommands = LinkedHashSet<String>()
    var onSessionCommand: ((String, ByteArray) -> Unit)? = null

    fun attach(value: BoardCellCoordinator) { coordinator = value }
    fun rememberSnapshot(snapshot: BoardCellSnapshot) { snapshots[snapshot.cellId] = snapshot }

    /** Claims are sent only to authenticated direct BLE neighbors, never flooded. */
    override suspend fun publishClaim(claim: BoardCellClaim) {
        val bytes = BoardCellWireCodec.encode(BoardCellWireMessage.DirectClaim(claim))
        link.directAuthenticatedPeers().forEach { sendOrQueue(it, bytes) }
    }

    override suspend fun publishEvent(envelope: BoardCellEnvelope) {
        val snapshot = snapshots[envelope.cellId] ?: coordinator?.snapshot(envelope.physicalBoardId)
        multicast(snapshot?.members.orEmpty(), BoardCellWireMessage.Event(envelope))
        coordinator?.snapshot(envelope.physicalBoardId)?.let { snapshots[it.cellId] = it }
    }

    override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) {
        snapshots[snapshot.cellId] = snapshot
        multicast(snapshot.members, BoardCellWireMessage.Snapshot(snapshot))
    }

    override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) {
        val controller = snapshots[cellId]?.controllerId ?: return
        sendOrQueue(controller, BoardCellWireCodec.encode(
            BoardCellWireMessage.SnapshotRequest(cellId, afterSequence)))
    }

    fun sendSessionCommand(snapshot: BoardCellSnapshot, commandId: String, payload: ByteArray): Boolean {
        val controller = snapshot.controllerId
        if (link.localNpub !in snapshot.members || controller == link.localNpub ||
            snapshot.availability != BoardCellAvailability.ACTIVE ||
            payload.isEmpty() || payload.size > MAX_SESSION_COMMAND_BYTES) return false
        val bytes = BoardCellWireCodec.encode(BoardCellWireMessage.SessionCommand(
            commandId, snapshot.cellId, snapshot.physicalBoardId, snapshot.epoch,
            snapshot.sequence, payload,
        ))
        return link.send(controller, bytes)
    }

    suspend fun receive(authenticatedSender: String, bytes: ByteArray): BoardCellApplyResult? {
        val message = runCatching { BoardCellWireCodec.decode(bytes) }.getOrNull()
            ?: return BoardCellApplyResult.Rejected("invalid wire message")
        val target = coordinator ?: return BoardCellApplyResult.Rejected("coordinator unavailable")
        return when (message) {
            is BoardCellWireMessage.DirectClaim -> {
                // A source-authenticated one-hop hint; settling still resolves
                // conflicts before the first physical write.
                if (authenticatedSender !in link.directAuthenticatedPeers()) {
                    BoardCellApplyResult.Rejected("claim is not from a direct peer")
                } else if (message.value.claimantId != authenticatedSender) {
                    BoardCellApplyResult.Rejected("claimant/source mismatch")
                } else {
                    target.observeClaim(message.value, System.currentTimeMillis())
                    null
                }
            }
            is BoardCellWireMessage.Snapshot -> {
                val result = target.acceptSnapshot(authenticatedSender, message.value)
                if (result is BoardCellApplyResult.Applied) snapshots[message.value.cellId] = message.value
                result
            }
            is BoardCellWireMessage.Event -> target.acceptEvent(authenticatedSender, message.value).also { result ->
                if (result is BoardCellApplyResult.Applied) {
                    snapshots[result.snapshot.cellId] = result.snapshot
                } else if (result is BoardCellApplyResult.NeedSnapshot) {
                    // The replica/cache may not exist yet (e.g. the join
                    // snapshot was lost), so reply directly to the authenticated
                    // event source instead of depending on cached controller id.
                    sendOrQueue(authenticatedSender, BoardCellWireCodec.encode(
                        BoardCellWireMessage.SnapshotRequest(
                            message.value.cellId, result.expectedSequence - 1,
                        )))
                }
            }
            is BoardCellWireMessage.SnapshotRequest -> {
                val snapshot = snapshots[message.cellId]
                if (snapshot != null && snapshot.controllerId == link.localNpub &&
                    authenticatedSender in snapshot.members) {
                    sendOrQueue(authenticatedSender, BoardCellWireCodec.encode(
                        BoardCellWireMessage.Snapshot(snapshot)))
                }
                null
            }
            is BoardCellWireMessage.AntiEntropy -> {
                val local = snapshots[message.cellId]
                if (local == null || authenticatedSender !in local.members) {
                    return BoardCellApplyResult.Rejected("anti-entropy source is not a cell member")
                }
                if (local.epoch != message.epoch ||
                    local.sequence != message.sequence || local.stateHash != message.stateHash) {
                    requestSnapshot(message.cellId, local.sequence)
                }
                null
            }
            is BoardCellWireMessage.SessionCommand -> {
                val local = snapshots[message.cellId]
                    ?: return BoardCellApplyResult.Rejected("session command has no local cell")
                if (message.physicalBoardId != local.physicalBoardId ||
                    message.epoch != local.epoch || message.sequence != local.sequence ||
                    authenticatedSender !in local.members || link.localNpub != local.controllerId ||
                    authenticatedSender == local.controllerId ||
                    local.availability != BoardCellAvailability.ACTIVE ||
                    message.payload.isEmpty() || message.payload.size > MAX_SESSION_COMMAND_BYTES) {
                    return BoardCellApplyResult.Rejected("session command scope/member/sequence mismatch")
                }
                if (seenSessionCommands.add("$authenticatedSender:${message.commandId}")) {
                    while (seenSessionCommands.size > MAX_SEEN_COMMANDS) {
                        seenSessionCommands.remove(seenSessionCommands.first())
                    }
                    onSessionCommand?.invoke(authenticatedSender, message.payload)
                }
                null
            }
        }
    }

    fun retryOutbox(limit: Int = 64) {
        repeat(minOf(limit, outbox.size)) {
            val item = outbox.removeFirst()
            outboxBytes -= item.second.size
            if (!link.send(item.first, item.second)) enqueue(item.first, item.second)
        }
    }

    fun antiEntropy() {
        snapshots.values.forEach { snapshot ->
            multicast(snapshot.members, BoardCellWireMessage.AntiEntropy(
                snapshot.cellId, snapshot.epoch, snapshot.sequence, snapshot.stateHash))
        }
    }

    private fun multicast(members: Set<String>, message: BoardCellWireMessage) {
        val bytes = BoardCellWireCodec.encode(message)
        members.asSequence().filter { it != link.localNpub }.forEach { sendOrQueue(it, bytes) }
    }

    private fun sendOrQueue(peer: String, bytes: ByteArray) {
        if (bytes.size > MAX_WIRE_BYTES) return
        if (!link.send(peer, bytes)) enqueue(peer, bytes)
    }

    private fun enqueue(peer: String, bytes: ByteArray) {
        // Bounded backpressure. Anti-entropy/snapshot recovery repairs an
        // evicted delta; a sequence gap is never silently accepted.
        while (outbox.isNotEmpty() &&
            (outbox.size >= MAX_OUTBOX || outboxBytes + bytes.size > MAX_OUTBOX_BYTES)) {
            outboxBytes -= outbox.removeFirst().second.size
        }
        if (bytes.size <= MAX_OUTBOX_BYTES) {
            outbox.addLast(peer to bytes)
            outboxBytes += bytes.size
        }
    }

    companion object {
        const val MAX_OUTBOX = 2_048
        const val MAX_OUTBOX_BYTES = 4 * 1_048_576
        const val MAX_WIRE_BYTES = 256 * 1_024
        const val MAX_SESSION_COMMAND_BYTES = 512
        const val MAX_SEEN_COMMANDS = 4_096
    }
}
