package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.BoardWrite
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
 * board the check lit when the link is gone, and disconnects again afterwards. A board that was
 * sent something else since is left alone: it shows someone else's frame now.
 */
internal class BoardSizeCheckConnection(
    private val connection: BoardBleConnection,
    private val reconnectTimeoutMs: Long = RECONNECT_TIMEOUT_MS,
) {
    /** A board the check's points went to, and the [BoardBleConnection.boardWrites] count of the check's last write to it. */
    private class Lit(val board: DiscoveredBoard, var write: Long)

    /** Boards that may still show the check's points, by address, until they are cleared or sent over. */
    private val litBoards = linkedMapOf<String, Lit>()

    /** [BoardBleConnection.boardWrites] count of the check's last write to any board: its points or a clear. */
    private var ownWrite: Long? = null

    fun hold() = connection.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK)

    fun release() = connection.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK)

    /** Connected to a board of [brand] and free to send. */
    fun ready(brand: BoardBrand): Boolean =
        connection.connectionState.value == ConnectionState.CONNECTED && connection.connectedBoardBrand.value == brand

    suspend fun light(brand: BoardBrand, leds: List<Pair<Int, Int>>): Boolean {
        noteWrites()
        val board = connection.connectedBoardDescriptor.value
        val sent = connection.sendRawFrame(leds, expectedBrand = brand)
        // A send that failed half way may have left part of its frame: that is the check's to clear too.
        sent.write?.let { write ->
            ownWrite = write
            board?.let { litBoards[it.address] = Lit(it, write) }
        }
        return sent.done
    }

    /** Nothing was written to any board since the check's last write. */
    fun showsCheckFrame(): Boolean = ownWrite != null && ownWrite == connection.boardWrites.value

    /**
     * Accounts for the writes since the last call; call it on every [BoardBleConnection.boardWrites]
     * change. A write to a lit board while it is connected was another sender's frame (a session,
     * the relay, a climb): the check's points are gone from it, and clearing it would wipe that
     * frame. A write while another board is connected leaves the lit board as it was.
     */
    fun noteWrites() {
        val count = connection.boardWrites.value
        val connected = connection.connectedBoardDescriptor.value?.address
        for (lit in litBoards.values.toList()) {
            if (lit.write == count) continue
            if (lit.board.address == connected) litBoards.remove(connected) else lit.write = count
        }
    }

    /**
     * Clears the check's frames from the boards they were sent to and nothing was sent to since.
     * Another board connected meanwhile is left alone: clearing it would wipe what that board
     * shows, and leaving it to reach an earlier board would drop a link the user made.
     */
    suspend fun clear(brand: BoardBrand): Boolean {
        noteWrites()
        connection.connectedBoardDescriptor.value?.let { connected ->
            val lit = litBoards[connected.address] ?: return@let
            settle(lit, clearConnected(lit.board, brand))
        }
        for (lit in litBoards.values.toList()) {
            if (connection.connectionState.value != ConnectionState.DISCONNECTED) break
            settle(lit, clearAfterReconnect(lit.board, brand))
        }
        return litBoards.isEmpty()
    }

    /** A clear is the check's write too, also one that failed half way: a frame after it is someone else's. */
    private fun settle(lit: Lit, cleared: BoardWrite) {
        cleared.write?.let { write ->
            ownWrite = write
            lit.write = write
        }
        if (cleared.done) litBoards.remove(lit.board.address)
    }

    /** The link is up or on its way to [board]: a connect the user started is waited for, not undone. */
    private suspend fun clearConnected(board: DiscoveredBoard, brand: BoardBrand): BoardWrite {
        if (connection.connectionState.value == ConnectionState.CONNECTING) {
            val state = withTimeoutOrNull(reconnectTimeoutMs) {
                connection.connectionState.first { it != ConnectionState.CONNECTING }
            }
            if (state != ConnectionState.CONNECTED || connection.connectedBoardDescriptor.value?.address != board.address) {
                return BoardWrite.NONE
            }
        }
        return connection.clearBoardWrite(expectedBrand = brand)
    }

    private suspend fun clearAfterReconnect(board: DiscoveredBoard, brand: BoardBrand): BoardWrite {
        // One quiet attempt: the user has closed the sheet and is not waiting for it.
        connection.connect(board, maxAttempts = 1)
        val state = withTimeoutOrNull(reconnectTimeoutMs) {
            connection.connectionState.first { it != ConnectionState.CONNECTING }
        }
        if (state != ConnectionState.CONNECTED || connection.connectedBoardDescriptor.value?.address != board.address) {
            // Still trying: stop, the user did not ask for a connection.
            if (state == null) connection.disconnect()
            return BoardWrite.NONE
        }
        val cleared = connection.clearBoardWrite(expectedBrand = brand)
        // Back to how the user left it: disconnected, unless a session or the relay wants the board now.
        if (!connection.hasOtherKeepAliveOwners(BoardConnectionOwner.SIZE_CHECK)) connection.disconnect()
        return cleared
    }

    companion object {
        /** One connect attempt takes about 10 s; this leaves room for service discovery. */
        const val RECONNECT_TIMEOUT_MS = 15_000L
    }
}
