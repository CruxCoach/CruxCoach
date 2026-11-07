package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The board-size check's use of the board link.
 *
 * While the sheet is open the link stays up ([hold]): the user walks to the board and back, and
 * an idle disconnect in between would leave the marked holds lit with no way to clear them. An
 * Aurora controller keeps its last frame after the link drops, so [clear] reconnects once to each
 * board the check lit when the link is gone, and disconnects again afterwards.
 */
internal class BoardSizeCheckConnection(
    private val connection: BoardBleConnection,
    private val reconnectTimeoutMs: Long = RECONNECT_TIMEOUT_MS,
) {
    /** Boards the check's points were sent to, by address, until they are cleared. */
    private val litBoards = linkedMapOf<String, DiscoveredBoard>()

    /** [BoardBleConnection.boardWrites] of the check's last frame. */
    private var litWrite: Long? = null

    fun hold() = connection.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK)

    fun release() = connection.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK)

    /** Connected to a board of [brand] and free to send. */
    fun ready(brand: BoardBrand): Boolean =
        connection.connectionState.value == ConnectionState.CONNECTED && connection.connectedBoardBrand.value == brand

    suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>): Boolean {
        val board = connection.connectedBoardDescriptor.value
        val write = connection.sendRawFrame(leds, expectedBrand = brand)
        litWrite = write
        if (write != null && board != null) litBoards[board.address] = board
        return write != null
    }

    /** The check's last frame is still what the board shows: nothing was written since. */
    fun showsCheckFrame(): Boolean = litWrite != null && litWrite == connection.boardWrites.value

    /**
     * Clears the check's frames from the boards they were sent to. Another board connected
     * meanwhile is left alone: clearing it would wipe what that board shows, and leaving it to
     * reach an earlier board would drop a link the user made.
     */
    suspend fun clear(brand: BoardBrand): Boolean {
        litWrite = null
        connection.connectedBoardDescriptor.value?.let { connected ->
            val board = litBoards[connected.address] ?: return@let
            if (clearConnected(board, brand)) litBoards.remove(board.address)
        }
        for (board in litBoards.values.toList()) {
            if (connection.connectionState.value != ConnectionState.DISCONNECTED) break
            if (clearAfterReconnect(board, brand)) litBoards.remove(board.address)
        }
        return litBoards.isEmpty()
    }

    /** The link is up or on its way to [board]: a connect the user started is waited for, not undone. */
    private suspend fun clearConnected(board: DiscoveredBoard, brand: BoardBrand): Boolean {
        if (connection.connectionState.value == ConnectionState.CONNECTING) {
            val state = withTimeoutOrNull(reconnectTimeoutMs) {
                connection.connectionState.first { it != ConnectionState.CONNECTING }
            }
            if (state != ConnectionState.CONNECTED || connection.connectedBoardDescriptor.value?.address != board.address) {
                return false
            }
        }
        return connection.clearBoard(expectedBrand = brand)
    }

    private suspend fun clearAfterReconnect(board: DiscoveredBoard, brand: BoardBrand): Boolean {
        // One quiet attempt: the user has closed the sheet and is not waiting for it.
        connection.connect(board, maxAttempts = 1)
        val state = withTimeoutOrNull(reconnectTimeoutMs) {
            connection.connectionState.first { it != ConnectionState.CONNECTING }
        }
        if (state != ConnectionState.CONNECTED || connection.connectedBoardDescriptor.value?.address != board.address) {
            // Still trying: stop, the user did not ask for a connection.
            if (state == null) connection.disconnect()
            return false
        }
        val cleared = connection.clearBoard(expectedBrand = brand)
        // Back to how the user left it: disconnected, unless a session or the relay wants the board now.
        if (!connection.hasOtherKeepAliveOwners(BoardConnectionOwner.SIZE_CHECK)) connection.disconnect()
        return cleared
    }

    companion object {
        /** One connect attempt takes about 10 s; this leaves room for service discovery. */
        const val RECONNECT_TIMEOUT_MS = 15_000L
    }
}
