package com.cruxcoach.app.ble

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumActivePlayer
import com.cruxcoach.domain.board.QuantumBoardBroadcastParser
import com.cruxcoach.domain.board.QuantumBoardModel
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import com.cruxcoach.domain.board.QuantumBroadcast
import com.cruxcoach.domain.board.QuantumCommand

// Pure Quantum rules, ported one to one from Android's BoardBleConnection.kt.

enum class QuantumCommandFailure {
    ROUTE_IN_USE, SPOT_UNAVAILABLE, COLOR_TAKEN, USER_ID_IN_USE,
    BOARD_FULL, ROUTESETTER_MODE, DIODE_MISSING, ACK_TIMEOUT, REFUSED,
}

enum class QuantumControllerSyncStatus { UNSYNCED, SYNCING, LIVE, STALE }

data class QuantumControllerState(
    val players: List<QuantumActivePlayer> = emptyList(),
    val revision: Long = 0,
    /** Revision of the last complete snapshot; deltas never advance it. */
    val authoritativeRevision: Long = 0,
    /** Revision of the last explicit REQUEST_USER_ROUTE_LIST response. */
    val routeListRevision: Long = 0,
    val lastFailure: QuantumCommandFailure? = null,
    val authoritative: Boolean = false,
    val syncStatus: QuantumControllerSyncStatus = QuantumControllerSyncStatus.UNSYNCED,
    /** Wall-clock millis ([com.cruxcoach.app.platform.WallClock]); Android uses a monotonic clock here. */
    val lastAuthoritativeAtMs: Long? = null,
)

/** The physical controller and model a Quantum diode plan was built for. */
data class BoardLayerBoardIdentity(val physicalBoardId: String, val productSizeId: Long)

internal const val MAX_LAYER_IDENTITIES = 4

internal fun quantumForegroundRefreshRequired(state: QuantumControllerState, nowMs: Long, maxAgeMs: Long): Boolean =
    state.syncStatus != QuantumControllerSyncStatus.LIVE ||
        state.lastAuthoritativeAtMs == null ||
        nowMs - state.lastAuthoritativeAtMs >= maxAgeMs

internal enum class QuantumControllerEvidence { AUTHORITATIVE, DELTA, FAILURE, INFORMATIONAL, UNSUPPORTED }

internal fun classifyQuantumControllerEvidence(broadcast: QuantumBroadcast?): QuantumControllerEvidence =
    when (broadcast) {
        is QuantumBroadcast.RouteList, QuantumBroadcast.BoardCleared -> QuantumControllerEvidence.AUTHORITATIVE
        is QuantumBroadcast.UserTurnedOff -> QuantumControllerEvidence.DELTA
        is QuantumBroadcast.Exception -> QuantumControllerEvidence.FAILURE
        is QuantumBroadcast.BoardLit -> QuantumControllerEvidence.INFORMATIONAL
        null -> QuantumControllerEvidence.UNSUPPORTED
    }

/** fff4 exposes the controller's cached last event: a cached TURN_OFF_ALL is not proof of an empty wall. */
internal fun classifyQuantumFff4Evidence(broadcast: QuantumBroadcast?): QuantumControllerEvidence =
    when (broadcast) {
        QuantumBroadcast.BoardCleared -> QuantumControllerEvidence.INFORMATIONAL
        else -> classifyQuantumControllerEvidence(broadcast)
    }

internal fun quantumFff4PublishesSnapshot(broadcast: QuantumBroadcast?): Boolean =
    broadcast is QuantumBroadcast.RouteList

internal fun quantumFff4ConfirmsExplicitRouteList(broadcast: QuantumBroadcast?): Boolean =
    broadcast is QuantumBroadcast.RouteList && broadcast.command == QuantumCommand.REQUEST_USER_ROUTE_LIST

internal fun hasFreshExplicitQuantumRouteList(before: QuantumControllerState, after: QuantumControllerState): Boolean =
    after.lastFailure == null && after.authoritative && after.routeListRevision > before.routeListRevision

/**
 * A Quantum command is one CRC-terminated frame which the controller validates
 * per characteristic write, so it must fit one ATT write. Android computes
 * `mtu - 3`; CoreBluetooth reports that payload size directly.
 */
internal fun quantumFrameFitsWriteLength(frameSize: Int, maximumWriteLength: Int): Boolean =
    frameSize > 0 && frameSize <= maximumWriteLength.coerceAtLeast(0)

internal class QuantumControllerMetadata(val model: QuantumBoardModel, val columns: Int, val rows: Int)

/** Strict decoder of the 41-byte fff5 record; anything unknown leaves the link non-writable. */
internal fun parseQuantumControllerMetadata(bytes: ByteArray): QuantumControllerMetadata? {
    if (bytes.size != 41) return null
    val model = when (bytes[34].toInt() and 0xff) {
        0 -> QuantumBoardModel.XL
        1 -> QuantumBoardModel.M
        2 -> QuantumBoardModel.S
        3 -> QuantumBoardModel.BELAY
        4 -> QuantumBoardModel.L
        else -> return null
    }
    val columns = ((bytes[35].toInt() and 0xff) shl 8) or (bytes[36].toInt() and 0xff)
    val rows = ((bytes[37].toInt() and 0xff) shl 8) or (bytes[38].toInt() and 0xff)
    if (columns != model.columns || rows != model.rows) return null
    return QuantumControllerMetadata(model, columns, rows)
}

internal fun isScopedQuantumUserId(userId: String, isOwnedByInstallation: (String) -> Boolean): Boolean =
    !userId.equals(QuantumBoardPacketEncoder.ZERO_UUID, ignoreCase = true) && isOwnedByInstallation(userId)

internal const val QUANTUM_NOTIFICATION_NEED_MORE = 0
internal const val QUANTUM_NOTIFICATION_INVALID = -1

internal fun quantumNotificationFrameSize(bytes: ByteArray): Int {
    if (bytes.isEmpty()) return QUANTUM_NOTIFICATION_NEED_MORE
    if ((bytes[0].toInt() and 0xff) != 1) return QUANTUM_NOTIFICATION_INVALID
    if (bytes.size < 2) return QUANTUM_NOTIFICATION_NEED_MORE
    val command = bytes[1].toInt() and 0xff
    if (command and 0x80 != 0) return 3
    return when (command) {
        0x41, 0x44, 0x47 -> {
            if (bytes.size < 4) {
                QUANTUM_NOTIFICATION_NEED_MORE
            } else {
                val players = bytes[2].toInt() and 0xff
                if (players > MAX_LAYER_IDENTITIES || bytes[3].toInt() != 0) {
                    QUANTUM_NOTIFICATION_INVALID
                } else {
                    4 + players * QuantumBoardBroadcastParser.PLAYER_BYTES
                }
            }
        }
        0x43 -> 21
        0x45 -> 6
        0x64 -> 3
        else -> QUANTUM_NOTIFICATION_INVALID
    }
}

internal class RecoveredQuantumNotification(val bytes: ByteArray, val crossedCallbackBoundary: Boolean)

/** fff1 reassembly. Not thread-safe: the controller is confined to one dispatcher. */
internal class QuantumNotificationAccumulator {
    private val buffer = ArrayList<Byte>()

    fun reset() = buffer.clear()

    fun consume(bytes: ByteArray): List<RecoveredQuantumNotification> {
        val crossedBoundary = buffer.isNotEmpty()
        buffer.addAll(bytes.toList())
        val out = mutableListOf<RecoveredQuantumNotification>()
        while (buffer.isNotEmpty()) {
            if ((buffer[0].toInt() and 0xff) != 1) {
                buffer.removeAt(0)
                continue
            }
            val candidate = buffer.toByteArray()
            val expected = quantumNotificationFrameSize(candidate)
            if (expected == QUANTUM_NOTIFICATION_NEED_MORE) break
            if (expected == QUANTUM_NOTIFICATION_INVALID) {
                buffer.removeAt(0)
                continue
            }
            if (candidate.size < expected) break
            out += RecoveredQuantumNotification(candidate.copyOf(expected), crossedBoundary)
            repeat(expected) { buffer.removeAt(0) }
        }
        return out
    }
}

private fun playerKeys(players: List<QuantumActivePlayer>): List<String> = players.map {
    // remainingSeconds ticks between reads and is not an occupancy dimension.
    "${it.routeId.lowercase()}|${it.userId.lowercase()}|${it.color and 0xffffff}"
}.sorted()

internal fun quantumPlayersMatch(expected: List<QuantumActivePlayer>, actual: List<QuantumActivePlayer>): Boolean =
    playerKeys(expected) == playerKeys(actual)

/** Readback proves route/user/colour only, never diodes: a partial mapping must be refused up front. */
internal fun hasCompleteQuantumLedMapping(holds: List<BoardHold>, placementToLed: Map<Int, Int>): Boolean =
    holds.isNotEmpty() && holds.all { hold -> placementToLed[hold.placementId]?.let { it in 0..0xffff } == true }

internal fun hasConfirmableQuantumDiodeCount(holds: List<BoardHold>): Boolean =
    holds.size <= QuantumBoardPacketEncoder.ACTIVATE_CHUNK_LIMIT

internal fun quantumBoardWriteFenceMatches(
    connectedBoard: DiscoveredBoard?,
    connectedModel: QuantumBoardModel?,
    expectedBoard: BoardLayerBoardIdentity?,
): Boolean {
    if (connectedBoard?.boardBrand != BoardBrand.QUANTUM || expectedBoard == null) return false
    val expectedModel = QuantumBoardModel.fromProductSizeId(expectedBoard.productSizeId) ?: return false
    if (connectedModel != expectedModel) return false
    return PhysicalBoardIdentity.resolve(connectedBoard) == expectedBoard.physicalBoardId
}

internal fun isQuantumProjectionConfirmed(
    state: QuantumControllerState,
    playersBefore: List<QuantumActivePlayer>,
    routeId: String,
    userId: String,
    color: Int,
): Boolean {
    if (!state.authoritative || state.lastFailure != null) return false
    val nonTargetBefore = playersBefore.filterNot { it.userId.equals(userId, ignoreCase = true) }
    val nonTargetAfter = state.players.filterNot { it.userId.equals(userId, ignoreCase = true) }
    return quantumPlayersMatch(nonTargetBefore, nonTargetAfter) && state.players.any {
        it.userId.equals(userId, ignoreCase = true) &&
            it.routeId.equals(routeId, ignoreCase = true) &&
            it.color == (color and 0xffffff)
    }
}

internal fun isQuantumScopedRemovalConfirmed(
    state: QuantumControllerState,
    playersBefore: List<QuantumActivePlayer>,
    userId: String,
): Boolean {
    if (!state.authoritative || state.lastFailure != null) return false
    val nonTargetBefore = playersBefore.filterNot { it.userId.equals(userId, ignoreCase = true) }
    return state.players.none { it.userId.equals(userId, ignoreCase = true) } &&
        quantumPlayersMatch(nonTargetBefore, state.players)
}

internal fun quantumFailure(code: Int): QuantumCommandFailure = when (code) {
    5 -> QuantumCommandFailure.ROUTE_IN_USE
    6 -> QuantumCommandFailure.SPOT_UNAVAILABLE
    7 -> QuantumCommandFailure.COLOR_TAKEN
    8 -> QuantumCommandFailure.USER_ID_IN_USE
    9 -> QuantumCommandFailure.BOARD_FULL
    10 -> QuantumCommandFailure.ROUTESETTER_MODE
    11 -> QuantumCommandFailure.DIODE_MISSING
    254 -> QuantumCommandFailure.ACK_TIMEOUT
    else -> QuantumCommandFailure.REFUSED
}
