package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState

/**
 * Whether this climb can reach the wall, and if not, what is in the way.
 *
 * The middle of the action dock used to be a lamp or a Bluetooth icon, decided
 * by whether *this phone* held a BLE link. That is the wrong question. A climb
 * reaches a board over a direct link, over an authorised CruxRelay, or over the
 * mesh through whoever is holding the controller — and a member on a board
 * somebody else is connected to has a perfectly good path while having no BLE
 * connection of their own. Showing them a Bluetooth icon told them to fix
 * something that was not broken.
 *
 * So: a lamp whenever any path exists. Only when none does is the button
 * allowed to become something else, and then it names the actual blocker and
 * opens the thing that fixes it. Never a dead icon, never a disabled control
 * with no explanation.
 */
enum class BoardReachability {
    /** A direct BLE link to the board. */
    DIRECT,

    /** Through the BoardCell controller over the mesh. */
    MESH,

    /** Through an authorised CruxRelay. */
    RELAY,

    /** A link is being established right now. */
    CONNECTING,

    /** Bluetooth is off on this device. */
    BLUETOOTH_OFF,

    /** CruxCoach may not use Bluetooth yet. */
    PERMISSION_MISSING,

    /** Everything is available; no board has been connected. */
    NO_BOARD,

    /** A board is configured or remembered, but nothing can reach it. */
    UNREACHABLE;

    /** True when a climb can be put on the wall right now. */
    val canReachBoard: Boolean
        get() = this == DIRECT || this == MESH || this == RELAY

    /** Mesh and relay are worth naming; a direct link is the unremarkable case. */
    val carriesBadge: Boolean
        get() = this == MESH || this == RELAY
}

internal object BoardReachabilityPolicy {

    /**
     * [hasEverSeenBoard] separates "you have not connected a board yet" from
     * "your board is not answering": the first is an invitation, the second is
     * a fault, and offering *Connect a board* to somebody whose board is simply
     * out of range reads as though CruxCoach had not noticed.
     */
    fun resolve(
        connectionState: ConnectionState,
        connectedViaMesh: Boolean,
        connectedViaRelay: Boolean,
        bluetoothEnabled: Boolean,
        hasBluetoothPermission: Boolean,
        hasEverSeenBoard: Boolean,
    ): BoardReachability {
        // A working path outranks every local complaint. Bluetooth being off on
        // this phone does not matter when the controller across the room is the
        // one holding the board.
        if (connectedViaMesh) return BoardReachability.MESH
        if (connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.SENDING
        ) {
            return if (connectedViaRelay) BoardReachability.RELAY else BoardReachability.DIRECT
        }
        if (connectionState == ConnectionState.CONNECTING) return BoardReachability.CONNECTING
        if (!hasBluetoothPermission) return BoardReachability.PERMISSION_MISSING
        if (!bluetoothEnabled) return BoardReachability.BLUETOOTH_OFF
        return if (hasEverSeenBoard) BoardReachability.UNREACHABLE else BoardReachability.NO_BOARD
    }
}

/**
 * Semantic icon state for a control whose eventual effect is a physical board write.
 *
 * A lamp is a promise that the control can affect a board now. Merely having a climb,
 * playlist occurrence, remembered board, or cached projection does not satisfy that
 * promise. Connection recovery keeps the same useful control position, but uses the
 * Bluetooth metaphor until a direct, mesh, or relay projection path is ready.
 */
internal enum class BoardActionVisual {
    CONNECT,
    CONNECTING,
    LAMP,
}

internal object BoardActionVisualPolicy {
    fun resolve(sendCapable: Boolean, connecting: Boolean = false): BoardActionVisual = when {
        sendCapable -> BoardActionVisual.LAMP
        connecting -> BoardActionVisual.CONNECTING
        else -> BoardActionVisual.CONNECT
    }
}
