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
 * Aurora controller keeps its last frame after the link drops, so [clear] reconnects once to the
 * board the check lit when the link is gone, and disconnects again afterwards.
 */
internal class BoardSizeCheckConnection(
    private val connection: BoardBleConnection,
    private val reconnectTimeoutMs: Long = RECONNECT_TIMEOUT_MS,
) {
    /** The board the check's points were last sent to, until they are cleared. */
    private var litBoard: DiscoveredBoard? = null

    fun hold() = connection.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK)

    fun release() = connection.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK)

    /** Connected to a board of [brand] and free to send. */
    fun ready(brand: BoardBrand): Boolean =
        connection.connectionState.value == ConnectionState.CONNECTED && connection.connectedBoardBrand.value == brand

    suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>): Boolean {
        val board = connection.connectedBoardDescriptor.value
        val sent = connection.sendRawLeds(leds, expectedBrand = brand)
        if (sent) litBoard = board
        return sent
    }

    /**
     * Clears the check's frame from the board it was sent to. Another board connected meanwhile
     * is left alone: clearing it would wipe what that board shows.
     */
    suspend fun clear(brand: BoardBrand): Boolean {
        val board = litBoard ?: return true
        val cleared = when {
            connection.connectedBoardDescriptor.value?.address == board.address -> connection.clearBoard(expectedBrand = brand)
            connection.connectionState.value == ConnectionState.DISCONNECTED -> clearAfterReconnect(board, brand)
            else -> false
        }
        if (cleared) litBoard = null
        return cleared
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
