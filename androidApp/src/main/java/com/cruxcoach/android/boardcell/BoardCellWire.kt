package com.cruxcoach.android.boardcell

import com.cruxcoach.android.fips.FipsDebugLog
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    @Serializable @SerialName("join_mode_change") data class JoinModeChange(
        val mode: BoardJoinMode,
    ) : BoardCellWireMessage
    @Serializable @SerialName("member_join_request") data class MemberJoinRequest(
        val value: BoardCellJoinRequest,
    ) : BoardCellWireMessage
    @Serializable @SerialName("member_admission_prompt") data class MemberAdmissionPrompt(
        val value: BoardCellAdmissionPrompt,
    ) : BoardCellWireMessage
    @Serializable @SerialName("member_admission_decision") data class MemberAdmissionDecision(
        val value: BoardCellAdmissionDecision,
    ) : BoardCellWireMessage
    @Serializable @SerialName("member_admission_result") data class MemberAdmissionResult(
        val value: BoardCellAdmissionResult,
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

    /**
     * One bounded, typed batch of playlist operations for the controller to
     * serialize.
     *
     * The single mesh path for editing the shared playlist. It replaced two:
     * an opaque binary queue payload plus a separate lifecycle control
     * message. There is no lifecycle left to carry — the playlist is created
     * with the cell and every member may edit it — and an opaque payload could
     * not be bounds-checked, rebased or replayed on a replica.
     *
     * A gateway carrying an API-28 GATT leaf's edit sends exactly this, under
     * its own authenticated identity. It needs no special authority: what the
     * leaf asked for is something the gateway is itself allowed to do, so
     * there is nothing to elevate and nothing extra to verify.
     */
    @Serializable @SerialName("playlist_command") data class PlaylistCommand(
        val value: BoardPlaylistCommand,
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
    // V13 adds the restorable clear: the controller stamps the window in which
    // any member may put an emptied list back, and the list itself travels in
    // the canonical snapshot for as long as that offer stands. A V12 peer has
    // no field for either, so it would decode the offer as absent and show the
    // group a clear nobody can take back while the rest of the mesh is
    // counting one down — a disagreement about what is on the wall's list, not
    // a cosmetic one. Both directions fail closed at the exact-version check.
    // V12 is the entry-addressed shared playlist. Every occurrence has a
    // stable id, normal edits travel as bounded typed operation deltas rather
    // than whole-playlist broadcasts, and the playlist itself has no host, no
    // membership and no lifecycle: it is created with the cell and every
    // member edits it. Nothing about that shape is expressible in V11, and a
    // V11 peer sharing a board with a V12 one would be a peer whose edits are
    // rejected and whose playlist is empty. Both directions fail closed at the
    // exact-version check, with `ignoreUnknownKeys = false` behind it so an
    // unrecognised field can never be silently dropped instead.
    // V11 carries the winning first controller's join rule in claims and
    // canonical snapshots. V9 added bounded operational peer diagnostics to
    // liveness frames, deliberately outside canonical state and state hashing.
    // V8 made the shared playlist canonical. V7 made membership live:
    // authenticated member heartbeats, explicit leave requests and canonical
    // MemberLeft events. V6 added semantic projection bases so heartbeat-only
    // sequence advances do not spuriously reject a participant's board
    // command. V5 added permissionless, member-sponsored multi-hop BoardCell
    // admission. Older peers must fail closed instead of interpreting a newer
    // authority flow.
    // V14 is the cursor/current split and the canonical relay intent: the
    // snapshot gained `selectedEntryId` and `relayOperations`, and the
    // operation set gained `SetSelection` and `RecordRelayOperation`. Neither
    // is optional for a reader — `ignoreUnknownKeys` is false, so a V13 peer
    // cannot decode a frame carrying any of them, and a V14 peer reading a V13
    // frame would take a cursor for a confirmed board state. Announcing the
    // same version for both shapes was the bug: two builds agreed on a number
    // and disagreed on what it meant.
    const val VERSION = 14
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
                    require(message.value.members.size <= 128 &&
                        message.value.recentCommandIds.size <= 256 &&
                        message.value.membershipRevision >= 0)
                    require(message.value.members.all { it.length in 1..256 })
                    require(message.value.recentCommandIds.all { it.length in 8..128 })
                    requirePlaylistBounds(message.value.playlist)
                }
                is BoardCellWireMessage.Event -> when (val event = message.value.event) {
                    is BoardCellEvent.MemberLeft -> require(event.memberId.length in 1..256)
                    // A committed delta carries the controller's stamps, so it
                    // is held to the canonical form rather than the sender's.
                    is BoardCellEvent.PlaylistOpsCommitted -> {
                        require(event.commandId.length in 8..128)
                        requireOpsBounds(event.ops, committed = true)
                    }
                    else -> Unit
                }
                is BoardCellWireMessage.PlaylistCommand -> {
                    val command = message.value
                    require(command.commandId.length in 8..128 &&
                        command.basePlaylistRevision >= 0 && command.baseClearGeneration >= 0)
                    requireOpsBounds(command.ops, committed = false)
                }
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
                is BoardCellWireMessage.MemberAdmissionPrompt -> require(
                    message.value.requestId.length in 8..128 &&
                        message.value.candidateId.length in 1..256 &&
                        message.value.sponsorId.length in 1..256 &&
                        message.value.requestedAtEpochMs > 0 &&
                        message.value.expiresAtEpochMs > message.value.requestedAtEpochMs)
                is BoardCellWireMessage.MemberAdmissionDecision -> require(
                    message.value.requestId.length in 8..128 &&
                        message.value.candidateId.length in 1..256)
                is BoardCellWireMessage.MemberAdmissionResult -> require(
                    message.value.requestId.length in 8..128 &&
                        message.value.candidateId.length in 1..256 &&
                        message.value.retryAfterEpochMs >= 0)
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

    private fun requireEntryBounds(entryId: String, climbUuid: String, angle: Int) {
        require(entryId.length in 1..BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH)
        require(climbUuid.length in 1..64 && angle in 0..90)
    }

    private fun requireRestBounds(seconds: Int) {
        require(seconds in 0..BoardPlaylistPolicy.MAX_REST_SECONDS)
    }

    private fun requireAnchorBounds(anchor: BoardPlaylistAnchor) {
        if (anchor is BoardPlaylistAnchor.After)
            require(anchor.entryId.length in 1..BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH)
    }

    /**
     * Bounds one operation delta.
     *
     * [committed] separates what a member may ask for from what the controller
     * may assert. A member's command must leave the controller's stamps — the
     * rest window and the clear generation — at zero, so no peer can dictate a
     * canonical deadline or skip a generation; a committed delta must carry
     * them, so a replica cannot be handed an unstamped rest to invent one for.
     */
    private fun requireOpsBounds(ops: List<BoardPlaylistOp>, committed: Boolean) {
        require(ops.size in 1..BoardPlaylistPolicy.MAX_OPS_PER_COMMAND)
        ops.forEach { op ->
            when (op) {
                is BoardPlaylistOp.Add -> {
                    requireEntryBounds(op.entryId, op.climbUuid, op.angle)
                    requireRestBounds(op.restAfterSeconds)
                    requireAnchorBounds(op.anchor)
                }
                is BoardPlaylistOp.Remove ->
                    require(op.entryId.length in 1..BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH)
                is BoardPlaylistOp.SetSelection ->
                    require(op.entryId.length in 1..BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH)
                is BoardPlaylistOp.RecordRelayOperation -> {
                    // A committed record carries the controller's stamp; an
                    // uncommitted one cannot invent it, exactly like a rest.
                    requireRelayOperationBounds(op.operation)
                    if (!committed) require(op.operation.stampedAtEpochMs == 0L)
                    else require(BoardPlaylistInstant.isValid(op.operation.stampedAtEpochMs))
                }
                is BoardPlaylistOp.Move -> {
                    require(op.entryId.length in 1..BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH)
                    requireAnchorBounds(op.anchor)
                }
                is BoardPlaylistOp.SetCurrent ->
                    require(op.entryId.length in 1..BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH)
                is BoardPlaylistOp.SetRest -> {
                    require(op.entryId.length in 1..BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH)
                    requireRestBounds(op.seconds)
                }
                is BoardPlaylistOp.StartRest -> {
                    require(op.nextEntryId.length in 1..BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH)
                    require(op.totalSeconds in 1..BoardPlaylistPolicy.MAX_REST_SECONDS)
                    if (committed) {
                        require(op.generation > 0)
                        require(BoardPlaylistInstant.isWindow(op.startedAtEpochMs,
                            op.endsAtEpochMs, op.totalSeconds * 1_000L))
                    } else {
                        require(op.generation == 0L && op.startedAtEpochMs == 0L &&
                            op.endsAtEpochMs == 0L)
                    }
                }
                BoardPlaylistOp.EndRest -> Unit
                is BoardPlaylistOp.Clear -> if (committed) {
                    require(op.generation > 0)
                    // Zero is the honest "no offer": an older committed clear,
                    // or one whose controller could not stamp a believable
                    // window. Anything else has to describe a real window, so
                    // no peer can publish a restore offer that never lapses.
                    if (op.clearedAtEpochMs != 0L || op.restorableUntilEpochMs != 0L) {
                        require(BoardPlaylistInstant.isWindow(op.clearedAtEpochMs,
                            op.restorableUntilEpochMs, BoardPlaylistPolicy.RESTORE_WINDOW_MS))
                    }
                } else {
                    require(op.generation == 0L && op.clearedAtEpochMs == 0L &&
                        op.restorableUntilEpochMs == 0L)
                }
                // A member names the clear it wants back, so unlike the
                // controller's stamps this generation is carried in both
                // directions — it is a precondition, not an authority claim.
                is BoardPlaylistOp.RestoreClear -> require(op.generation > 0)
                is BoardPlaylistOp.ExpireClearUndo -> {
                    // Only the controller has the clock that decides this.
                    require(committed)
                    require(op.generation > 0)
                }
                is BoardPlaylistOp.SetPendingProjection -> {
                    // Only the controller ever reports the physical send, and
                    // it never has to send itself a packet to do it. Refusing
                    // it at the wire keeps that a property of the protocol
                    // rather than of one reducer branch remembering to check.
                    require(committed)
                    op.pending?.let { requireEntryBounds(it.entryId, it.climbUuid, it.angle) }
                }
            }
        }
    }

    /**
     * A peer may only ever send a playlist that the local reducer would also
     * have produced. Checking the shape here keeps an oversized or
     * self-inconsistent playlist out of the durable store and out of the state
     * hash, instead of relying on every later reader to be defensive.
     */
    private fun requireRelayOperationBounds(operation: BoardRelayOperation) {
        require(operation.fingerprint.length in 1..BoardPlaylistPolicy.MAX_ID_LENGTH)
        require(operation.guestKey.length in 1..BoardPlaylistPolicy.MAX_ID_LENGTH)
        require(operation.operationId.length in 1..BoardPlaylistPolicy.MAX_ID_LENGTH)
        require(operation.entryId.length in 1..BoardPlaylistPolicy.MAX_ENTRY_ID_LENGTH)
        require(operation.stampedAtEpochMs >= 0)
    }

    private fun requirePlaylistBounds(playlist: BoardPlaylistState) {
        require(playlist.entries.size <= BoardPlaylistPolicy.MAX_ENTRIES)
        require(playlist.clearGeneration >= 0)
        require(playlist.sessionId == null || playlist.sessionId > 0)
        require(playlist.sessionId != null || playlist.entries.isEmpty())
        val ids = HashSet<String>(playlist.entries.size * 2)
        playlist.entries.forEach {
            requireEntryBounds(it.entryId, it.climbUuid, it.angle)
            requireRestBounds(it.restAfterSeconds)
            // Duplicate occurrence ids would make every later edit ambiguous,
            // which is precisely what stable ids exist to prevent.
            require(ids.add(it.entryId))
        }
        // The cursor always points somewhere while there is anything to point
        // at. The confirmed current does not: null is the honest state of a
        // list nobody has sent from yet, and requiring one here is what used to
        // force the invented current that made "on the board" a lie.
        playlist.selectedEntryId?.let { selected -> require(selected in ids) }
        require(playlist.selectedEntryId != null || playlist.entries.isEmpty())
        playlist.currentEntryId?.let { current -> require(current in ids) }
        require(playlist.relayOperations.size <= BoardPlaylistPolicy.MAX_RELAY_OPERATIONS)
        val relayKeys = HashSet<Pair<String, String>>(playlist.relayOperations.size * 2)
        playlist.relayOperations.forEach {
            requireRelayOperationBounds(it)
            require(BoardPlaylistInstant.isValid(it.stampedAtEpochMs))
            // One record per intention, or a peer could pad canonical state
            // with copies of the same one.
            require(relayKeys.add(it.fingerprint to it.guestKey))
        }
        playlist.activeRest?.let {
            require(it.totalSeconds in 1..BoardPlaylistPolicy.MAX_REST_SECONDS)
            require(it.generation > 0)
            require(it.nextEntryId in ids)
            // The start/end pair has to describe exactly the duration it
            // claims. Bounding only the far end still allowed a "two minute"
            // pause that ran until 2099, which every replica would then have
            // hashed and honoured. Checking the difference needs no clock, so
            // the verdict is the same on every device.
            require(BoardPlaylistInstant.isWindow(it.startedAtEpochMs, it.endsAtEpochMs,
                it.totalSeconds * 1_000L))
        }
        playlist.pendingProjection?.let {
            requireEntryBounds(it.entryId, it.climbUuid, it.angle)
            // Any existing occurrence, not only the current one: a send that
            // failed leaves the confirmed current where it was, so the marker
            // names the occurrence behind it. It still has to name a real one,
            // with that occurrence's own climb and angle.
            val entry = playlist.entry(it.entryId)
            require(entry != null && entry.climbUuid == it.climbUuid && entry.angle == it.angle)
        }
        playlist.lastClear?.let { undo ->
            // The offer must belong to the clear the playlist has actually
            // reached, or a peer could keep an arbitrarily old list alive in
            // canonical state and hand it back into the group later.
            require(undo.generation > 0 && undo.generation == playlist.clearGeneration)
            require(undo.entries.size in 1..BoardPlaylistPolicy.MAX_ENTRIES)
            require(playlist.entries.size + undo.entries.size <=
                BoardPlaylistPolicy.MAX_ENTRIES)
            val undoIds = HashSet<String>(undo.entries.size * 2)
            undo.entries.forEach {
                requireEntryBounds(it.entryId, it.climbUuid, it.angle)
                requireRestBounds(it.restAfterSeconds)
                require(undoIds.add(it.entryId))
            }
            undo.selectedEntryId?.let { selected -> require(selected in undoIds) }
            require(BoardPlaylistInstant.isWindow(undo.clearedAtEpochMs,
                undo.restorableUntilEpochMs, BoardPlaylistPolicy.RESTORE_WINDOW_MS))
        }
        // Wire snapshots must already be canonical. Silently normalizing a
        // controller's malformed snapshot would produce a different hash and
        // let inconsistent state enter persistence before the next delta.
        require(BoardPlaylistPolicy.normalize(playlist) == playlist)
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
    /**
     * This device believes its playlist replica is current.
     *
     * False while the cell is frozen, a gap is unrepaired or the controller
     * has gone quiet — i.e. during a partition. Nothing may present the
     * playlist as synchronised on the strength of merely having a copy of it.
     */
    @SerialName("ps") val playlistSynchronized: Boolean = false,
    @SerialName("as") val awaitingExplicitSend: Boolean = false,
    @SerialName("eo") val externalBoardOverride: Boolean = false,
    @SerialName("pc") val pendingCommands: Int = 0,
    /** Optional user-approved local display name; never an npub or account id. */
    @SerialName("dn") val displayName: String? = null,
) {
    internal fun validate() {
        require(schema in 1..16)
        require(appVersionCode >= 0)
        require(meshRole.length <= 32 && meshMemberCount in 0..64)
        require(boardConnection.length <= 32 && autoDisconnectSeconds in 0..86_400)
        require(sessionRole.length <= 32)
        require(sessionVisibility.length <= 32 && sessionVisibilityRequested.length <= 32)
        require(sessionId >= 0 && queueSize in 0..BoardPlaylistPolicy.MAX_ENTRIES)
        require(currentIndex in -1 until BoardPlaylistPolicy.MAX_ENTRIES)
        require(currentClimbId == null || currentClimbId.length in 1..64)
        require(pendingCommands in 0..1_024)
        require(displayName == null || displayName.length in 1..40)
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
            "playlistSynchronized" to value.playlistSynchronized,
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
    fun recycleTransport(reason: String): Boolean = false
}

data class InboundProjectionRequest(
    val senderId: String,
    val request: BoardProjectionRequest,
)

data class InboundPlaylistCommand(
    val senderId: String,
    val command: BoardPlaylistCommand,
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
    private val pendingAdmissionControllers = mutableMapOf<String, String>()
    private val _peerDiagnostics = MutableStateFlow<Map<String, BoardCellPeerDiagnostics>>(emptyMap())
    val peerDiagnostics = _peerDiagnostics.asStateFlow()
    var onPlaylistCommand: (suspend (InboundPlaylistCommand) -> Unit)? = null
    var onCommandAck: (suspend (String, BoardCommandAck) -> Unit)? = null
    var onControllerRequest: (suspend (String, BoardCellControllerRequest) -> Unit)? = null
    var onControllerDecision: (suspend (String, BoardCellControllerDecision) -> Unit)? = null
    var onJoinModeChange: (suspend (String, BoardJoinMode) -> Unit)? = null
    var onAdmissionRequested: (suspend (String, BoardCellJoinRequest) -> Unit)? = null
    var onAdmissionPrompt: (suspend (BoardCellAdmissionPrompt) -> Unit)? = null
    var onAdmissionDecision: (suspend (String, BoardCellAdmissionDecision) -> Unit)? = null
    var onAdmissionResult: (suspend (BoardCellAdmissionResult) -> Unit)? = null
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
        lastPeerDiagnostics.clear()
        pendingAdmissionControllers.clear()
        _peerDiagnostics.value = emptyMap()
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

    fun sendJoinModeChange(snapshot: BoardCellSnapshot, mode: BoardJoinMode): Boolean {
        if (link.localNpub !in snapshot.members || link.localNpub == snapshot.controllerId ||
            snapshot.availability != BoardCellAvailability.ACTIVE) return false
        return link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.JoinModeChange(mode)))
    }

    /** Sponsor a directly authenticated neighbor under the cell's join rule. */
    fun sponsorMember(snapshot: BoardCellSnapshot, candidateId: String): Boolean {
        if (link.localNpub !in snapshot.members || link.localNpub == snapshot.controllerId ||
            snapshot.availability != BoardCellAvailability.ACTIVE ||
            candidateId !in link.directAuthenticatedPeers()) return false
        val request = BoardCellJoinRequest(UUID.randomUUID().toString(), candidateId, link.localNpub)
        return link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.MemberJoinRequest(request)))
    }

    fun publishAdmissionPrompt(snapshot: BoardCellSnapshot, prompt: BoardCellAdmissionPrompt) {
        val bytes = frameFor(snapshot, BoardCellWireMessage.MemberAdmissionPrompt(prompt))
        (snapshot.members + prompt.candidateId).asSequence().filter { it != link.localNpub }
            .forEach { link.send(it, bytes) }
    }

    fun sendAdmissionDecision(snapshot: BoardCellSnapshot, decision: BoardCellAdmissionDecision): Boolean {
        if (link.localNpub !in snapshot.members) return false
        return link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.MemberAdmissionDecision(decision)))
    }

    fun publishAdmissionResult(snapshot: BoardCellSnapshot, result: BoardCellAdmissionResult) {
        val bytes = frameFor(snapshot, BoardCellWireMessage.MemberAdmissionResult(result))
        (snapshot.members + result.candidateId).asSequence().filter { it != link.localNpub }
            .forEach { link.send(it, bytes) }
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
        _peerDiagnostics.value = _peerDiagnostics.value + (sender to value)
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
            snapshot.availability !in setOf(
                BoardCellAvailability.ACTIVE,
                BoardCellAvailability.FROZEN_WRITE_RECOVERY,
            )) return false
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
        // Only a decision about the command is worth remembering. Caching
        // "ask somebody else" or "you are ahead of me" would answer the
        // sender's next retry with a refusal whose cause had already gone.
        if (ack.status.isTerminalDecision) {
            seenCommands["$target:${ack.commandId}"] = ack
            trimMap(seenCommands, MAX_SEEN_COMMANDS)
        }
        sendOrQueue(target, frameFor(snapshot, BoardCellWireMessage.CommandAck(ack)))
    }

    /**
     * Ask the controller to serialize one playlist edit.
     *
     * Legal for every authenticated cell member without qualification: the
     * shared playlist has no membership of its own, so there is no second
     * gate here to route around.
     */
    fun sendPlaylistCommand(snapshot: BoardCellSnapshot, command: BoardPlaylistCommand): Boolean {
        if (link.localNpub !in snapshot.members || snapshot.controllerId == link.localNpub ||
            snapshot.availability != BoardCellAvailability.ACTIVE || command.ops.isEmpty()) {
            FipsDebugLog.warning("wire", "playlist_command_refused",
                "command" to FipsDebugLog.id(command.commandId),
                "localMember" to (link.localNpub in snapshot.members),
                "localIsController" to (snapshot.controllerId == link.localNpub),
                "availability" to snapshot.availability)
            return false
        }
        val sent = link.send(snapshot.controllerId,
            frameFor(snapshot, BoardCellWireMessage.PlaylistCommand(command)))
        FipsDebugLog.event("wire", if (sent) "playlist_command_tx" else "playlist_command_tx_failed",
            "controller" to FipsDebugLog.id(snapshot.controllerId),
            "command" to FipsDebugLog.id(command.commandId), "ops" to command.ops.size,
            "baseRevision" to command.basePlaylistRevision,
            "baseClearGeneration" to command.baseClearGeneration)
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
            is BoardCellWireMessage.MemberAdmissionResult ->
                (local != null && value.epoch == local.epoch &&
                    value.controllerTerm == local.controllerTerm) ||
                    (local == null && scoped.value.candidateId == link.localNpub &&
                        pendingAdmissionControllers[scoped.value.requestId] == authenticatedSender)
            is BoardCellWireMessage.MemberAdmissionPrompt ->
                (local != null && value.epoch == local.epoch &&
                    value.controllerTerm == local.controllerTerm) ||
                    (local == null && scoped.value.candidateId == link.localNpub)
            else -> local != null && value.epoch == local.epoch && value.controllerTerm == local.controllerTerm
        }
        if (!scopeMatchesPayload) return BoardCellApplyResult.Rejected("realm/cell/board/epoch/term mismatch")
        if (local != null && local.controllerId == link.localNpub &&
            authenticatedSender in local.members && authenticatedSender != link.localNpub) {
            target.observeAuthenticatedMemberFrame(
                local.physicalBoardId,
                authenticatedSender,
                nowMonotonicMs,
            )
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
            is BoardCellWireMessage.JoinModeChange -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("join mode change has no local cell")
                if (link.localNpub != snapshot.controllerId || authenticatedSender !in snapshot.members) {
                    return BoardCellApplyResult.Rejected("join mode change sender/role mismatch")
                }
                onJoinModeChange?.invoke(authenticatedSender, message.mode)
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
                    onAdmissionRequested?.invoke(authenticatedSender, message.value)
                }
                null
            }
            is BoardCellWireMessage.MemberAdmissionPrompt -> {
                val snapshot = snapshots[value.cellId]
                if (snapshot != null) {
                    if (authenticatedSender != snapshot.controllerId || link.localNpub !in snapshot.members) {
                        return BoardCellApplyResult.Rejected("admission prompt sender/receiver mismatch")
                    }
                } else {
                    if (message.value.candidateId != link.localNpub) {
                        return BoardCellApplyResult.Rejected("admission prompt is not for this candidate")
                    }
                    pendingAdmissionControllers[message.value.requestId] = authenticatedSender
                }
                onAdmissionPrompt?.invoke(message.value)
                null
            }
            is BoardCellWireMessage.MemberAdmissionDecision -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("admission decision has no local cell")
                if (link.localNpub != snapshot.controllerId || authenticatedSender !in snapshot.members) {
                    return BoardCellApplyResult.Rejected("admission decision sender/role mismatch")
                }
                onAdmissionDecision?.invoke(authenticatedSender, message.value)
                null
            }
            is BoardCellWireMessage.MemberAdmissionResult -> {
                // Membership snapshots remain the only authority for an
                // admission. This operational result merely makes rejection
                // and its cooldown visible to the requester without waiting
                // for a transport timeout.
                pendingAdmissionControllers.remove(message.value.requestId)
                onAdmissionResult?.invoke(message.value)
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
                target.observeAuthenticatedMemberFrame(
                    snapshot.physicalBoardId,
                    authenticatedSender,
                    nowMonotonicMs,
                )
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
                val left = target.leaveMember(snapshot.physicalBoardId, authenticatedSender,
                    BoardCellMemberLeaveReason.VOLUNTARY, nowMonotonicMs)
                target.snapshot(snapshot.physicalBoardId)?.let { current ->
                    snapshots[current.cellId] = current
                    // The native peer table can outlive the final L2CAP channel for
                    // several seconds.  Without retiring that generation, the normal
                    // permissionless admission loop can mistake the leaver's ghost for
                    // a fresh explicit join as soon as the departure fence expires.
                    if (left != null && current.members == setOf(link.localNpub)) {
                        link.recycleTransport("last remote member left voluntarily")
                    }
                }
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
            is BoardCellWireMessage.PlaylistCommand -> {
                val snapshot = snapshots[value.cellId]
                    ?: return BoardCellApplyResult.Rejected("playlist command has no local cell")
                val command = message.value
                val key = "$authenticatedSender:${command.commandId}"
                // A durable terminal result supersedes an in-flight ACCEPTED
                // cache entry when a command is retried after commit. This is
                // what survives a controller handover: the new controller
                // inherits the ack window in the snapshot it adopted.
                target.commandAck(command.commandId, snapshot)?.let {
                    FipsDebugLog.event("wire", "playlist_command_deduplicated_durable",
                        "command" to FipsDebugLog.id(command.commandId), "status" to it.status)
                    seenCommands[key] = it
                    publishCommandAck(authenticatedSender, it)
                    return null
                }
                seenCommands[key]?.let { publishCommandAck(authenticatedSender, it); return null }
                if (authenticatedSender !in snapshot.members || link.localNpub != snapshot.controllerId ||
                    command.basePlaylistRevision > snapshot.playlistRevision) {
                    val status = if (link.localNpub != snapshot.controllerId)
                        BoardCommandStatus.NOT_CONTROLLER else BoardCommandStatus.REJECTED_STALE
                    val ack = BoardCommandAck(command.commandId, status, snapshot.cellId, snapshot.epoch,
                        snapshot.controllerTerm, snapshot.sequence, snapshot.stateHash,
                        "playlist command scope/base rejected")
                    // Not cached: neither refusal is a decision about the
                    // command, and answering the sender's next retry from a
                    // cache would repeat it after the very condition that
                    // caused it had gone away.
                    FipsDebugLog.warning("wire", "playlist_command_rejected",
                        "command" to FipsDebugLog.id(command.commandId), "status" to status,
                        "baseRevision" to command.basePlaylistRevision,
                        "currentRevision" to snapshot.playlistRevision)
                    publishCommandAck(authenticatedSender, ack)
                    return BoardCellApplyResult.Rejected("playlist command rejected")
                }
                // Answered before the command is even applied, so the sender
                // stops retrying within one round trip instead of waiting for
                // a maintenance tick to notice the silence.
                val accepted = BoardCommandAck(command.commandId, BoardCommandStatus.ACCEPTED,
                    snapshot.cellId, snapshot.epoch, snapshot.controllerTerm, snapshot.sequence,
                    snapshot.stateHash)
                seenCommands[key] = accepted
                trimMap(seenCommands, MAX_SEEN_COMMANDS)
                publishCommandAck(authenticatedSender, accepted)
                FipsDebugLog.event("wire", "playlist_command_accepted",
                    "command" to FipsDebugLog.id(command.commandId),
                    "sender" to FipsDebugLog.id(authenticatedSender), "ops" to command.ops.size,
                    "baseRevision" to command.basePlaylistRevision)
                onPlaylistCommand?.invoke(InboundPlaylistCommand(authenticatedSender, command))
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
        const val MAX_SEEN_COMMANDS = 4_096
        const val MAX_SEEN_FRAMES = 8_192
    }
}
