package com.cruxcoach.android.ui.board

import android.os.SystemClock
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
 * sent something else since is left alone: it shows someone else's frame now. So is a board whose
 * link has been gone for longer than [goneLimitMs]: another phone may have used it meanwhile.
 */
internal class BoardSizeCheckConnection(
    private val connection: BoardBleConnection,
    private val reconnectTimeoutMs: Long = RECONNECT_TIMEOUT_MS,
    private val goneLimitMs: Long = GONE_LIMIT_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    /**
     * A board the check's points went to, the [BoardBleConnection.boardWrites] count of the
     * check's last write to it, and since when its link is gone.
     */
    private class Lit(val board: DiscoveredBoard, var write: Long, var goneAt: Long? = null)

    /** Boards that may still show the check's points, by address, until they are cleared or sent over. */
    private val litBoards = linkedMapOf<String, Lit>()

    /** [BoardBleConnection.boardWrites] count of the check's last write to any board: its points or a clear. */
    private var ownWrite: Long? = null

    /** The board [pause] disconnected, to connect again on [resume], and when. */
    private var pausedBoard: DiscoveredBoard? = null
    private var pausedAt = 0L

    fun hold() = connection.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK)

    fun release() {
        pausedBoard = null
        connection.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK)
    }

    /**
     * The app went to the background: the link is let go, and disconnected unless a session or
     * the relay needs it, so another phone can connect to the board. [resume] connects again.
     */
    fun pause() {
        release()
        if (connection.hasOtherKeepAliveOwners(BoardConnectionOwner.SIZE_CHECK)) return
        if (connection.connectionState.value == ConnectionState.DISCONNECTED) return
        val board = connection.connectedBoardDescriptor.value ?: return
        pausedBoard = board
        pausedAt = now()
        connection.disconnect()
    }

    /**
     * Back in the app: holds the link again and connects to the board [pause] let go. Returns
     * false when that was longer ago than [goneLimitMs]: another phone may have used the board
     * meanwhile, and our write count cannot see it.
     */
    fun resume(): Boolean {
        hold()
        val board = pausedBoard ?: return true
        pausedBoard = null
        // Connected meanwhile (a session, the relay): that link stays.
        if (connection.connectionState.value == ConnectionState.DISCONNECTED) connection.connect(board)
        return now() - pausedAt <= goneLimitMs
    }

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
     * Accounts for the link; call it on every [BoardBleConnection.connectionState] change. Our
     * write count cannot see other phones: once a lit board's link has been gone for longer than
     * [goneLimitMs], another climber may have connected, sent a climb and left, so the board is
     * no longer the check's to clear, also when it comes back.
     */
    fun noteLink() {
        val time = now()
        val connected = connection.connectedBoardDescriptor.value?.address?.takeIf {
            connection.connectionState.value.let { it == ConnectionState.CONNECTED || it == ConnectionState.SENDING }
        }
        for (lit in litBoards.values.toList()) {
            val goneAt = lit.goneAt
            when {
                goneAt != null && time - goneAt > goneLimitMs -> litBoards.remove(lit.board.address)
                lit.board.address == connected -> lit.goneAt = null
                goneAt == null -> lit.goneAt = time
            }
        }
    }

    /**
     * Clears the check's frames from the boards they were sent to and nothing was sent to since,
     * once each: a board whose clear failed is not tried again by a later close. Another board
     * connected meanwhile is left alone: clearing it would wipe what that board shows, and leaving
     * it to reach an earlier board would drop a link the user made. Returns whether nothing is
     * left to clear.
     */
    suspend fun clear(brand: BoardBrand): Boolean {
        noteWrites()
        noteLink()
        connection.connectedBoardDescriptor.value?.let { connected ->
            val lit = litBoards[connected.address] ?: return@let
            clearConnected(lit, brand)?.let { settle(lit, it) }
        }
        for (lit in litBoards.values.toList()) {
            if (connection.connectionState.value != ConnectionState.DISCONNECTED) break
            // The clear of the board before was a write too, made while this one was away, and
            // it may have taken this one past the limit.
            noteWrites()
            noteLink()
            if (lit.board.address !in litBoards) continue
            settle(lit, clearAfterReconnect(lit, brand))
        }
        return litBoards.isEmpty()
    }

    /** A clear is the check's write too, also one that failed half way: a frame after it is someone else's. */
    private fun settle(lit: Lit, cleared: BoardWrite) {
        cleared.write?.let { write -> ownWrite = write }
        litBoards.remove(lit.board.address)
    }

    /**
     * The link is up or on its way to the board: a connect the user started is waited for, not
     * undone. Null when no clear was tried: the link is still on its way, or gone again, so the
     * reconnect may try.
     */
    private suspend fun clearConnected(lit: Lit, brand: BoardBrand): BoardWrite? {
        if (connection.connectionState.value == ConnectionState.CONNECTING) {
            val state = withTimeoutOrNull(reconnectTimeoutMs) {
                connection.connectionState.first { it != ConnectionState.CONNECTING }
            }
            if (state != ConnectionState.CONNECTED || connection.connectedBoardDescriptor.value?.address != lit.board.address) {
                return null
            }
        }
        if (!stillLit(lit)) return null
        // Only if nothing went to the board since, checked under the write lock: a session or the
        // relay may send the moment the link is up.
        return connection.clearBoardWrite(expectedBrand = brand, ifLastWrite = lit.write)
    }

    /** Still the check's to clear at the moment of the clear: waiting for the link counts against the limit. */
    private fun stillLit(lit: Lit): Boolean {
        noteLink()
        return litBoards[lit.board.address] === lit
    }

    private suspend fun clearAfterReconnect(lit: Lit, brand: BoardBrand): BoardWrite {
        // One quiet attempt: the user has closed the sheet and is not waiting for it.
        connection.connect(lit.board, maxAttempts = 1)
        val state = withTimeoutOrNull(reconnectTimeoutMs) {
            connection.connectionState.first { it != ConnectionState.CONNECTING }
        }
        if (state != ConnectionState.CONNECTED || connection.connectedBoardDescriptor.value?.address != lit.board.address) {
            // Still trying: stop, the user did not ask for a connection.
            if (state == null) connection.disconnect()
            return BoardWrite.NONE
        }
        val cleared = if (stillLit(lit)) {
            connection.clearBoardWrite(expectedBrand = brand, ifLastWrite = lit.write)
        } else {
            BoardWrite.NONE
        }
        // Back to how the user left it: disconnected, unless a session or the relay wants the board now.
        if (!connection.hasOtherKeepAliveOwners(BoardConnectionOwner.SIZE_CHECK)) connection.disconnect()
        return cleared
    }

    companion object {
        /** One connect attempt takes about 10 s; this leaves room for service discovery. */
        const val RECONNECT_TIMEOUT_MS = 15_000L

        /** How long after the link went a board is still taken to show the check's points. */
        const val GONE_LIMIT_MS = 30_000L
    }
}
